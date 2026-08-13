from __future__ import annotations

import argparse
import base64
import csv
import gzip
import hashlib
import json
import math
import os
import re
import shutil
import statistics
import subprocess
import sys
import uuid
import zipfile
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path, PurePosixPath
from typing import Any, Iterable

from common import adb, command, require_run_id, resolve_serial, run_as_read, run_instrumentation_driver


ROOT = Path(__file__).resolve().parents[2]
ANDROID_ROOT = ROOT / "android"
ARTIFACT_ROOT = ROOT / "artifacts" / "device-runs"
EVALUATION_PACKAGE = "io.github.anup42.askalbum.evaluation"
CONSUMER_PACKAGE = "io.github.anup42.askalbum"
VARIANT = "EvaluationDebug"
ID_PATTERN = re.compile(r"[A-Za-z0-9_-]{1,96}")
GENERATION_PATTERN = re.compile(r"generation-[A-Za-z0-9_-]{8,160}")
PACKAGE_PATTERN = re.compile(r"(?:[A-Za-z][A-Za-z0-9_]*\.)+[A-Za-z][A-Za-z0-9_]*")
SUPPORTED_IMAGE_SUFFIXES = frozenset((".jpg", ".jpeg", ".png", ".webp", ".heic", ".heif"))


def json_array(path: Path) -> list[dict[str, Any]]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, list) or not all(isinstance(item, dict) for item in value):
        raise RuntimeError(f"Expected a JSON object array: {path}")
    return value


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def normalized_timestamp(value: Any) -> str | None:
    if value is None or not str(value).strip():
        return None
    parsed = datetime.fromisoformat(str(value).strip().replace("Z", "+00:00"))
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return parsed.isoformat()


def validate_id(value: Any, label: str) -> str:
    result = str(value)
    if not ID_PATTERN.fullmatch(result):
        raise RuntimeError(f"Unsafe {label}: {result!r}")
    return result


def validate_package(value: str) -> str:
    if not PACKAGE_PATTERN.fullmatch(value):
        raise RuntimeError(f"Unsafe Android package name: {value!r}")
    return value


def prepare_dataset(dataset_dir: Path, run_id: str, artifacts: Path = ARTIFACT_ROOT) -> dict[str, Any]:
    run_id = require_run_id(run_id)
    dataset_dir = dataset_dir.resolve()
    context_path = dataset_dir / "image_context.json"
    query_path = dataset_dir / "queries_v2.json"
    zip_path = dataset_dir / "images.zip"
    for required in (context_path, query_path, zip_path):
        if not required.is_file():
            raise RuntimeError(f"Dataset file is missing: {required}")
    contexts = json_array(context_path)
    queries = json_array(query_path)
    context_ids = [validate_id(item.get("id"), "image ID") for item in contexts]
    query_ids = [validate_id(item.get("query_id"), "query ID") for item in queries]
    if len(set(context_ids)) != len(context_ids):
        raise RuntimeError("Image IDs are not unique")
    if len(set(query_ids)) != len(query_ids):
        raise RuntimeError("Query IDs are not unique")
    known_ids = set(context_ids)
    for query in queries:
        text = str(query.get("query") or "").strip()
        expected = query.get("image_ids")
        if not text or not isinstance(expected, list) or not expected:
            raise RuntimeError(f"Query {query.get('query_id')} has no text or relevance IDs")
        unknown = {str(value) for value in expected} - known_ids
        if unknown:
            raise RuntimeError(f"Query {query.get('query_id')} references unknown IDs: {sorted(unknown)}")

    fingerprint = hashlib.sha256(
        (sha256_file(context_path) + sha256_file(query_path) + sha256_file(zip_path)).encode("ascii")
    ).hexdigest()
    host = artifacts / run_id / "evaluation"
    gallery = host / "gallery"
    media = gallery / "media"
    manifest_path = gallery / "gallery-manifest.json"
    oracle_path = host / "oracle.json"
    existing = json.loads(manifest_path.read_text(encoding="utf-8")) if manifest_path.is_file() else None
    if existing is not None:
        if existing.get("dataset_fingerprint") != fingerprint:
            raise RuntimeError(f"Run {run_id} already contains a different dataset")
        return {
            "runId": run_id,
            "datasetFingerprint": fingerprint,
            "imageCount": len(contexts),
            "queryCount": len(queries),
            "gallery": str(gallery),
            "oracle": str(oracle_path),
            "reused": True,
        }
    if gallery.exists() and any(gallery.iterdir()):
        raise RuntimeError(f"Refusing to overwrite non-empty evaluation work directory: {gallery}")
    media.mkdir(parents=True, exist_ok=True)

    manifest_items: list[dict[str, Any]] = []
    normalized_tags: list[dict[str, Any]] = []
    with zipfile.ZipFile(zip_path) as archive:
        entry_names = set(archive.namelist())
        for item, image_id in zip(contexts, context_ids):
            source_name = str(item.get("image_path") or "")
            source_path = PurePosixPath(source_name)
            suffix = source_path.suffix.lower()
            if source_name not in entry_names or suffix not in SUPPORTED_IMAGE_SUFFIXES:
                raise RuntimeError(f"Missing or unsupported image for {image_id}: {source_name}")
            filename = f"{image_id}{suffix}"
            target = media / filename
            with archive.open(source_name) as source, target.open("xb") as output:
                shutil.copyfileobj(source, output, length=1024 * 1024)
            manifest_item: dict[str, Any] = {"filename": filename, "dataset_id": image_id}
            captured_at = normalized_timestamp(item.get("capture_time"))
            if captured_at:
                manifest_item["captured_at"] = captured_at
            manifest_items.append(manifest_item)
            normalized_tags.append({
                key: item.get(key)
                for key in (
                    "id", "image_path", "capture_time", "person_names", "relationship", "event",
                    "landmark", "thing", "address_text", "locality", "country_name", "latitude", "longitude",
                )
            })

    device_queries = [
        {"id": query_id, "query": str(item["query"]).strip()}
        for item, query_id in zip(queries, query_ids)
    ]
    manifest = {
        "schema_version": 1,
        "dataset_fingerprint": fingerprint,
        "items": manifest_items,
        "evaluation": {"queries": device_queries},
    }
    oracle = {
        "schemaVersion": 1,
        "runId": run_id,
        "datasetFingerprint": fingerprint,
        "source": str(dataset_dir),
        "tags": normalized_tags,
        "queries": [
            {
                "queryId": query_id,
                "query": str(item["query"]).strip(),
                "galleryQuery": str(item.get("gallery_query") or "").strip(),
                "referenceAnswer": str(item.get("answer") or "").strip(),
                "referenceImageIds": [str(value) for value in item["image_ids"]],
                "queryAskingTime": item.get("query_asking_time"),
            }
            for item, query_id in zip(queries, query_ids)
        ],
    }
    manifest_path.write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    oracle_path.write_text(json.dumps(oracle, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    result = {
        "runId": run_id,
        "datasetFingerprint": fingerprint,
        "imageCount": len(contexts),
        "queryCount": len(queries),
        "gallery": str(gallery),
        "oracle": str(oracle_path),
        "reused": False,
    }
    (host / "prepare-result.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    return result


def gradle_environment() -> dict[str, str]:
    adb_path = shutil.which("adb")
    if not adb_path:
        raise RuntimeError("adb is not available on PATH")
    sdk = str(Path(adb_path).resolve().parent.parent)
    return {**os.environ, "ANDROID_HOME": sdk, "ANDROID_SDK_ROOT": sdk}


def install_evaluation_app(serial: str) -> dict[str, Any]:
    build = subprocess.run(
        [str(ANDROID_ROOT / "gradlew.bat"), ":app:assembleEvaluationDebug", ":app:assembleEvaluationDebugAndroidTest", "--console=plain"],
        cwd=ANDROID_ROOT,
        env=gradle_environment(),
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        check=False,
        timeout=3600,
    )
    build_log = ARTIFACT_ROOT / "evaluation-interface" / "gradle-build.txt"
    build_log.parent.mkdir(parents=True, exist_ok=True)
    build_log.write_text(build.stdout, encoding="utf-8")
    if build.returncode:
        raise RuntimeError(f"Evaluation build failed; see {build_log}")
    app_apk = ANDROID_ROOT / "app/build/outputs/apk/evaluation/debug/app-evaluation-debug.apk"
    test_apk = ANDROID_ROOT / "app/build/outputs/apk/androidTest/evaluation/debug/app-evaluation-debug-androidTest.apk"
    for apk in (app_apk, test_apk):
        if not apk.is_file():
            raise RuntimeError(f"Expected APK is missing: {apk}")
    adb(serial, "install", "-r", "-d", str(app_apk), timeout_seconds=600)
    adb(serial, "install", "-r", "-t", str(test_apk), timeout_seconds=600)
    return {"state": "COMPLETE", "package": EVALUATION_PACKAGE, "appApk": str(app_apk), "testApk": str(test_apk)}


def provision_active_gemma(serial: str, source_package: str = CONSUMER_PACKAGE) -> dict[str, Any]:
    source_package = validate_package(source_package)
    if source_package == EVALUATION_PACKAGE:
        raise RuntimeError("Source and evaluation packages must differ")
    pointer = run_as_read(serial, source_package, "files/models/gemma/current")
    generation = pointer.decode("utf-8").strip() if pointer else ""
    if not GENERATION_PATTERN.fullmatch(generation):
        raise RuntimeError(f"The source package has no valid active Gemma generation: {generation!r}")
    adb(serial, "shell", "am", "force-stop", EVALUATION_PACKAGE)
    adb(serial, "shell", "run-as", EVALUATION_PACKAGE, "mkdir", "-p", "files/models/gemma/generations")
    current = run_as_read(serial, EVALUATION_PACKAGE, "files/models/gemma/current")
    model_relative = f"files/models/gemma/generations/{generation}/gemma-4-E2B-it.litertlm"
    source_model = f"files/models/gemma/generations/{generation}/gemma-4-E2B-it.litertlm"
    source_digest = adb(
        serial, "shell", "run-as", source_package, "sha256sum", source_model,
        timeout_seconds=600,
    ).stdout.decode().split()[0]
    model_exists = adb(serial, "shell", "run-as", EVALUATION_PACKAGE, "test", "-f", model_relative, check=False).returncode == 0
    if current and current.decode("utf-8").strip() == generation and model_exists:
        target_digest = adb(
            serial, "shell", "run-as", EVALUATION_PACKAGE, "sha256sum", model_relative,
            timeout_seconds=600,
        ).stdout.decode().split()[0]
        if target_digest == source_digest:
            return {
                "state": "COMPLETE",
                "generation": generation,
                "modelSha256": target_digest,
                "reused": True,
                "sourcePackage": source_package,
            }
    staging = f"/data/local/tmp/askalbum-evaluation-{hashlib.sha256(generation.encode()).hexdigest()[:16]}.tar"
    try:
        adb(
            serial,
            "shell",
            f"run-as {source_package} tar -C files/models/gemma -cf - current generations/{generation} > {staging}",
            timeout_seconds=3600,
        )
        adb(
            serial,
            "shell",
            f"cat {staging} | run-as {EVALUATION_PACKAGE} tar -C files/models/gemma -xf -",
            timeout_seconds=3600,
        )
    finally:
        adb(serial, "shell", "rm", "-f", staging, check=False, timeout_seconds=120)
    copied_pointer = run_as_read(serial, EVALUATION_PACKAGE, "files/models/gemma/current")
    if not copied_pointer or copied_pointer.decode("utf-8").strip() != generation:
        raise RuntimeError("Evaluation package did not retain the copied Gemma pointer")
    if adb(serial, "shell", "run-as", EVALUATION_PACKAGE, "test", "-f", model_relative, check=False).returncode:
        raise RuntimeError("Evaluation package did not retain the copied Gemma artifact")
    target_digest = adb(
        serial, "shell", "run-as", EVALUATION_PACKAGE, "sha256sum", model_relative,
        timeout_seconds=600,
    ).stdout.decode().split()[0]
    if target_digest != source_digest:
        raise RuntimeError("Evaluation Gemma artifact checksum does not match the verified source generation")
    return {
        "state": "COMPLETE",
        "generation": generation,
        "modelSha256": target_digest,
        "reused": False,
        "sourcePackage": source_package,
    }


def provision_active_retrieval(serial: str, source_package: str = CONSUMER_PACKAGE) -> dict[str, Any]:
    source_package = validate_package(source_package)
    pointer = run_as_read(serial, source_package, "files/models/retrieval/current")
    generation = pointer.decode("utf-8").strip() if pointer else ""
    if not GENERATION_PATTERN.fullmatch(generation):
        raise RuntimeError(f"The source package has no valid active retrieval generation: {generation!r}")
    source_manifest = f"files/models/retrieval/generations/{generation}/manifest.json"
    source_digest = adb(
        serial, "shell", "run-as", source_package, "sha256sum", source_manifest,
        timeout_seconds=120,
    ).stdout.decode().split()[0]
    adb(serial, "shell", "am", "force-stop", EVALUATION_PACKAGE)
    adb(serial, "shell", "run-as", EVALUATION_PACKAGE, "mkdir", "-p", "files/models/retrieval/generations")
    target_manifest = f"files/models/retrieval/generations/{generation}/manifest.json"
    current = run_as_read(serial, EVALUATION_PACKAGE, "files/models/retrieval/current")
    manifest_exists = adb(
        serial, "shell", "run-as", EVALUATION_PACKAGE, "test", "-f", target_manifest, check=False,
    ).returncode == 0
    if current and current.decode("utf-8").strip() == generation and manifest_exists:
        target_digest = adb(
            serial, "shell", "run-as", EVALUATION_PACKAGE, "sha256sum", target_manifest,
            timeout_seconds=120,
        ).stdout.decode().split()[0]
        if target_digest == source_digest:
            return {
                "state": "COMPLETE",
                "generation": generation,
                "manifestSha256": target_digest,
                "reused": True,
                "sourcePackage": source_package,
            }
    staging = f"/data/local/tmp/askalbum-evaluation-{hashlib.sha256(('retrieval:' + generation).encode()).hexdigest()[:16]}.tar"
    try:
        adb(
            serial,
            "shell",
            f"run-as {source_package} tar -C files/models/retrieval -cf - current generations/{generation} > {staging}",
            timeout_seconds=1800,
        )
        adb(
            serial,
            "shell",
            f"cat {staging} | run-as {EVALUATION_PACKAGE} tar -C files/models/retrieval -xf -",
            timeout_seconds=1800,
        )
    finally:
        adb(serial, "shell", "rm", "-f", staging, check=False, timeout_seconds=120)
    copied_pointer = run_as_read(serial, EVALUATION_PACKAGE, "files/models/retrieval/current")
    if not copied_pointer or copied_pointer.decode("utf-8").strip() != generation:
        raise RuntimeError("Evaluation package did not retain the copied retrieval pointer")
    target_digest = adb(
        serial, "shell", "run-as", EVALUATION_PACKAGE, "sha256sum", target_manifest,
        timeout_seconds=120,
    ).stdout.decode().split()[0]
    if target_digest != source_digest:
        raise RuntimeError("Evaluation retrieval manifest checksum does not match the verified source generation")
    return {
        "state": "COMPLETE",
        "generation": generation,
        "manifestSha256": target_digest,
        "reused": False,
        "sourcePackage": source_package,
    }


def run_tool(script: str, arguments: list[str], timeout_seconds: int) -> None:
    result = subprocess.run(
        [sys.executable, str(ROOT / "tools/device" / script), *arguments],
        cwd=ROOT,
        check=False,
        timeout=timeout_seconds,
    )
    if result.returncode:
        raise RuntimeError(f"{script} failed with exit code {result.returncode}")


def complete_index_coverage(coverage: dict[str, Any], expected: int) -> bool:
    index_states = coverage.get("indexStates", {})
    embedding = coverage.get("stages", {}).get("EMBEDDING", {})
    return (
        coverage.get("mediaCount") == expected
        and coverage.get("uniqueMediaIds") == expected
        and coverage.get("vectorCount") == expected
        and index_states.get("READY") == expected
        and sum(int(index_states.get(state, 0)) for state in (
            "PENDING", "INDEXING", "FAILED_RETRYABLE", "FAILED_EXHAUSTED", "FAILED_PERMANENT",
        )) == 0
        and embedding.get("COMPLETE") == expected
        and sum(int(embedding.get(state, 0)) for state in (
            "PENDING", "RUNNING", "FAILED_RETRYABLE", "FAILED_EXHAUSTED", "FAILED_PERMANENT",
        )) == 0
    )


def read_index_coverage(run_id: str) -> dict[str, Any]:
    return json.loads((ARTIFACT_ROOT / run_id / "index-coverage-result.json").read_text(encoding="utf-8"))


def index_dataset(serial: str, dataset_dir: Path, run_id: str, timeout_seconds: int) -> dict[str, Any]:
    retrieval_pointer = run_as_read(serial, EVALUATION_PACKAGE, "files/models/retrieval/current")
    retrieval_generation = retrieval_pointer.decode("utf-8").strip() if retrieval_pointer else ""
    if not GENERATION_PATTERN.fullmatch(retrieval_generation):
        raise RuntimeError("The evaluation package has no active verified retrieval pack; run provision-model first")
    prepared = prepare_dataset(dataset_dir, run_id)
    common = ["--serial", serial, "--package", EVALUATION_PACKAGE, "--component-package", "io.github.anup42.askalbum", "--run-id", run_id]
    run_tool(
        "seed_gallery.py",
        ["--serial", serial, "--package", EVALUATION_PACKAGE, "--component-package", "io.github.anup42.askalbum",
         "--gallery", prepared["gallery"], "--run-id", run_id, "--transport", "instrumentation",
         "--timeout-seconds", str(min(timeout_seconds, 3600))],
        timeout_seconds=min(timeout_seconds + 300, 3900),
    )
    expected = int(prepared["imageCount"])
    coverage: dict[str, Any] = {}
    for attempt in range(4):
        if attempt:
            run_tool(
                "index_seeded_gallery.py",
                [*common, "--action", "resume", "--timeout-seconds", "3600"],
                timeout_seconds=3900,
            )
        run_tool(
            "index_seeded_gallery.py",
            [*common, "--action", "foreground", "--max-cycles", "5000", "--timeout-seconds", str(timeout_seconds)],
            timeout_seconds=timeout_seconds + 300,
        )
        run_tool(
            "index_seeded_gallery.py",
            [*common, "--action", "status", "--timeout-seconds", "600"],
            timeout_seconds=900,
        )
        coverage = read_index_coverage(run_id)
        if complete_index_coverage(coverage, expected):
            break
    state = "COMPLETE" if complete_index_coverage(coverage, expected) else "PARTIAL"
    result = {"state": state, "prepared": prepared, "coverage": coverage}
    output = ARTIFACT_ROOT / run_id / "evaluation" / "index-interface-result.json"
    output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    if state != "COMPLETE":
        raise RuntimeError(f"Evaluation index is incomplete; see {output}")
    return result


def operation_report_path(run_id: str, operation_id: str, filename: str) -> str:
    return f"files/test-seed/{run_id}/evaluation/{operation_id}/{filename}"


def execute_queries(
    serial: str,
    run_id: str,
    operation_id: str,
    timeout_seconds: int,
    query: str | None = None,
    query_id: str | None = None,
) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    arguments = {"galleryOperationId": operation_id, "galleryResume": "true"}
    if query is not None:
        safe_query_id = validate_id(query_id or f"adhoc_{hashlib.sha256(query.encode()).hexdigest()[:16]}", "query ID")
        arguments.update({
            "galleryQueryId": safe_query_id,
            "galleryQueryBase64": base64.b64encode(query.encode("utf-8")).decode("ascii"),
        })
        query_ids = [safe_query_id]
    else:
        oracle_path = ARTIFACT_ROOT / run_id / "evaluation" / "oracle.json"
        oracle = json.loads(oracle_path.read_text(encoding="utf-8"))
        query_ids = [validate_id(item["queryId"], "query ID") for item in oracle["queries"]]
        device_queries = [
            {"id": item["queryId"], "query": item["query"]}
            for item in oracle["queries"]
        ]
        arguments["galleryQueriesBase64"] = base64.b64encode(
            gzip.compress(json.dumps(device_queries, ensure_ascii=False).encode("utf-8"), compresslevel=9)
        ).decode("ascii")
    run_instrumentation_driver(
        serial,
        EVALUATION_PACKAGE,
        run_id,
        "evaluate",
        arguments=arguments,
        timeout_seconds=timeout_seconds,
    )
    summary_payload = run_as_read(serial, EVALUATION_PACKAGE, operation_report_path(run_id, operation_id, "summary.json"))
    if not summary_payload:
        raise RuntimeError("Device evaluation summary is unavailable")
    summary = json.loads(summary_payload)
    reports: list[dict[str, Any]] = []
    host = ARTIFACT_ROOT / run_id / "evaluation" / operation_id
    host.mkdir(parents=True, exist_ok=True)
    (host / "device-summary.json").write_bytes(summary_payload)
    for current_id in query_ids:
        payload = run_as_read(
            serial,
            EVALUATION_PACKAGE,
            operation_report_path(run_id, operation_id, f"query-{current_id}.json"),
        )
        if not payload:
            reports.append({"state": "FAILED", "queryId": current_id, "error": "Missing device report"})
            continue
        report = json.loads(payload)
        reports.append(report)
        (host / f"query-{current_id}.json").write_bytes(payload)
    return summary, reports


def tokens(text: str) -> list[str]:
    return re.findall(r"\w+", text.casefold(), flags=re.UNICODE)


def rouge_n(reference: str, prediction: str, n: int) -> float:
    reference_tokens = tokens(reference)
    prediction_tokens = tokens(prediction)
    reference_ngrams = Counter(tuple(reference_tokens[index:index + n]) for index in range(len(reference_tokens) - n + 1))
    prediction_ngrams = Counter(tuple(prediction_tokens[index:index + n]) for index in range(len(prediction_tokens) - n + 1))
    if not reference_ngrams or not prediction_ngrams:
        return 0.0
    overlap = sum((reference_ngrams & prediction_ngrams).values())
    precision = overlap / sum(prediction_ngrams.values())
    recall = overlap / sum(reference_ngrams.values())
    return 0.0 if precision + recall == 0 else 2 * precision * recall / (precision + recall)


def rouge_l(reference: str, prediction: str) -> float:
    left = tokens(reference)
    right = tokens(prediction)
    if not left or not right:
        return 0.0
    previous = [0] * (len(right) + 1)
    for token in left:
        current = [0]
        for index, candidate in enumerate(right, start=1):
            current.append(previous[index - 1] + 1 if token == candidate else max(previous[index], current[-1]))
        previous = current
    common = previous[-1]
    precision = common / len(right)
    recall = common / len(left)
    return 0.0 if precision + recall == 0 else 2 * precision * recall / (precision + recall)


def f1(precision: float, recall: float) -> float:
    return 0.0 if precision + recall == 0 else 2 * precision * recall / (precision + recall)


def percentile(values: list[float], fraction: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    return ordered[max(0, math.ceil(fraction * len(ordered)) - 1)]


def score_reports(
    oracle: dict[str, Any],
    reports: Iterable[dict[str, Any]],
    top_k: int = 10,
) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    if top_k < 1:
        raise ValueError("top_k must be positive")
    by_id = {str(report.get("queryId")): report for report in reports}
    rows: list[dict[str, Any]] = []
    total_tp = 0
    total_returned = 0
    total_relevant = 0
    latency_values: list[float] = []
    rouge_rows: list[dict[str, float]] = []
    completed = 0
    for expected in oracle["queries"]:
        query_id = str(expected["queryId"])
        report = by_id.get(query_id, {"state": "FAILED", "queryId": query_id, "error": "No report"})
        state = str(report.get("state"))
        if state == "COMPLETE":
            completed += 1
        predicted = list(dict.fromkeys(str(value) for value in report.get("matchedImageIds", [])))[:top_k]
        relevant = set(str(value) for value in expected["referenceImageIds"])
        true_positive = len(relevant.intersection(predicted))
        precision = true_positive / len(predicted) if predicted else 0.0
        recall = true_positive / len(relevant)
        search_f1 = f1(precision, recall)
        total_tp += true_positive
        total_returned += len(predicted)
        total_relevant += len(relevant)
        latency = report.get("latencyMs")
        if isinstance(latency, (int, float)):
            latency_values.append(float(latency))
        reference_answer = str(expected.get("referenceAnswer") or "").strip()
        prediction = str(report.get("answerText") or "").strip()
        answer_scores = None
        if reference_answer:
            answer_scores = {
                "rouge1": rouge_n(reference_answer, prediction, 1),
                "rouge2": rouge_n(reference_answer, prediction, 2),
                "rougeL": rouge_l(reference_answer, prediction),
            }
            rouge_rows.append(answer_scores)
        rows.append({
            "queryId": query_id,
            "state": state,
            "precision": precision,
            "recall": recall,
            "f1": search_f1,
            "truePositive": true_positive,
            "returnedAtCutoff": len(predicted),
            "relevantCount": len(relevant),
            "rouge1": None if answer_scores is None else answer_scores["rouge1"],
            "rouge2": None if answer_scores is None else answer_scores["rouge2"],
            "rougeL": None if answer_scores is None else answer_scores["rougeL"],
            "answerScoreStatus": "NOT_SCORED_NO_REFERENCE" if answer_scores is None else "SCORED",
            "latencyMs": latency,
            "requiresAuthentication": bool(report.get("answer", {}).get("requiresAuthentication", False)),
            "error": report.get("error"),
        })

    mean = lambda values: statistics.fmean(values) if values else None
    macro_precision = mean([row["precision"] for row in rows]) or 0.0
    macro_recall = mean([row["recall"] for row in rows]) or 0.0
    macro_f1 = mean([row["f1"] for row in rows]) or 0.0
    micro_precision = total_tp / total_returned if total_returned else 0.0
    micro_recall = total_tp / total_relevant if total_relevant else 0.0
    summary = {
        "state": "COMPLETE" if completed == len(rows) else "PARTIAL",
        "queryCount": len(rows),
        "completedQueryCount": completed,
        "failedQueryCount": len(rows) - completed,
        "search": {
            "cutoff": top_k,
            "macroPrecision": macro_precision,
            "macroRecall": macro_recall,
            "macroF1": macro_f1,
            "microPrecision": micro_precision,
            "microRecall": micro_recall,
            "microF1": f1(micro_precision, micro_recall),
        },
        "answer": {
            "scoredQueryCount": len(rouge_rows),
            "notScoredNoReferenceCount": len(rows) - len(rouge_rows),
            "rouge1": mean([row["rouge1"] for row in rouge_rows]),
            "rouge2": mean([row["rouge2"] for row in rouge_rows]),
            "rougeL": mean([row["rougeL"] for row in rouge_rows]),
        },
        "latency": {
            "measuredQueryCount": len(latency_values),
            "averageQueryLatencyMs": mean(latency_values),
            "p50QueryLatencyMs": percentile(latency_values, 0.50),
            "p95QueryLatencyMs": percentile(latency_values, 0.95),
        },
    }
    return summary, rows


def write_metric_artifacts(run_id: str, operation_id: str, summary: dict[str, Any], rows: list[dict[str, Any]]) -> Path:
    host = ARTIFACT_ROOT / run_id / "evaluation" / operation_id
    host.mkdir(parents=True, exist_ok=True)
    result = {"schemaVersion": 1, "runId": run_id, "operationId": operation_id, "metrics": summary, "queries": rows}
    result_path = host / "evaluation-results.json"
    result_path.write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    with (host / "per-query-metrics.csv").open("w", newline="", encoding="utf-8") as output:
        writer = csv.DictWriter(output, fieldnames=list(rows[0]) if rows else ["queryId"])
        writer.writeheader()
        writer.writerows(rows)
    search = summary["search"]
    answer = summary["answer"]
    latency = summary["latency"]
    markdown = f"""# AskAlbum device evaluation

- Queries: {summary['completedQueryCount']}/{summary['queryCount']} completed
- Search cutoff: top {search['cutoff']}
- Precision: {search['macroPrecision']:.6f}
- Recall: {search['macroRecall']:.6f}
- F1: {search['macroF1']:.6f}
- ROUGE-1: {answer['rouge1'] if answer['rouge1'] is not None else 'NOT SCORED'}
- ROUGE-2: {answer['rouge2'] if answer['rouge2'] is not None else 'NOT SCORED'}
- ROUGE-L: {answer['rougeL'] if answer['rougeL'] is not None else 'NOT SCORED'}
- Answer references scored: {answer['scoredQueryCount']}; absent references: {answer['notScoredNoReferenceCount']}
- Average query latency: {latency['averageQueryLatencyMs']} ms
- P50 query latency: {latency['p50QueryLatencyMs']} ms
- P95 query latency: {latency['p95QueryLatencyMs']} ms

Precision, recall, and F1 are macro averages over each query's first {search['cutoff']} returned IDs.
ROUGE values are token-overlap F1 and exclude queries whose reference answer is empty.
"""
    (host / "evaluation-summary.md").write_text(markdown, encoding="utf-8")
    return result_path


def cleanup_run(serial: str, run_id: str, timeout_seconds: int = 1800) -> dict[str, Any]:
    operation_id = uuid.uuid4().hex
    run_instrumentation_driver(
        serial,
        EVALUATION_PACKAGE,
        run_id,
        "cleanup",
        arguments={"galleryOperationId": operation_id},
        timeout_seconds=timeout_seconds,
    )
    payload = run_as_read(serial, EVALUATION_PACKAGE, f"files/test-seed/{run_id}/cleanup-status.json")
    if not payload:
        raise RuntimeError("Cleanup status is unavailable")
    result = json.loads(payload)
    if result.get("state") != "COMPLETE" or result.get("remainingCount") != 0:
        raise RuntimeError(f"Run-scoped cleanup was incomplete: {result}")
    return result


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Isolated connected-device image indexing, JSON search, and evaluation")
    parser.add_argument("--serial")
    sub = parser.add_subparsers(dest="action", required=True)
    sub.add_parser("install", help="Build and replacement-install the isolated production-engine evaluation app")
    provision = sub.add_parser("provision-model", help="Copy the active verified Gemma generation read-only from another signed app")
    provision.add_argument("--source-package", default=CONSUMER_PACKAGE)
    prepare = sub.add_parser("prepare", help="Validate images/tags/queries and create a non-leaking run-scoped seed")
    prepare.add_argument("--dataset-dir", type=Path, required=True)
    prepare.add_argument("--run-id", required=True)
    index = sub.add_parser("index", help="Prepare, seed, and index a dataset in the evaluation package")
    index.add_argument("--dataset-dir", type=Path, required=True)
    index.add_argument("--run-id", required=True)
    index.add_argument("--timeout-seconds", type=int, default=21600)
    search = sub.add_parser("search", help="Run one query and print the complete JSON result and trace")
    search.add_argument("--run-id", required=True)
    search.add_argument("--query", required=True)
    search.add_argument("--query-id")
    search.add_argument("--operation-id")
    search.add_argument("--timeout-seconds", type=int, default=1800)
    evaluate = sub.add_parser("evaluate", help="Run every labelled query and compute retrieval, ROUGE, and latency metrics")
    evaluate.add_argument("--dataset-dir", type=Path, required=True)
    evaluate.add_argument("--run-id", required=True)
    evaluate.add_argument("--operation-id")
    evaluate.add_argument("--top-k", type=int, default=10)
    evaluate.add_argument("--timeout-seconds", type=int, default=21600)
    cleanup = sub.add_parser("cleanup", help="Delete only MediaStore rows owned by this exact evaluation run")
    cleanup.add_argument("--run-id", required=True)
    cleanup.add_argument("--timeout-seconds", type=int, default=1800)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    serial = resolve_serial(args.serial)
    if args.action == "install":
        result = install_evaluation_app(serial)
    elif args.action == "provision-model":
        result = {
            "state": "COMPLETE",
            "gemma": provision_active_gemma(serial, args.source_package),
            "retrieval": provision_active_retrieval(serial, args.source_package),
        }
    elif args.action == "prepare":
        result = prepare_dataset(args.dataset_dir, args.run_id)
    elif args.action == "index":
        result = index_dataset(serial, args.dataset_dir, require_run_id(args.run_id), args.timeout_seconds)
    elif args.action == "search":
        run_id = require_run_id(args.run_id)
        operation_id = args.operation_id or uuid.uuid4().hex
        summary, reports = execute_queries(
            serial, run_id, operation_id, args.timeout_seconds, query=args.query, query_id=args.query_id,
        )
        result = {"deviceSummary": summary, "result": reports[0]}
    elif args.action == "evaluate":
        run_id = require_run_id(args.run_id)
        prepare_dataset(args.dataset_dir, run_id)
        operation_id = args.operation_id or uuid.uuid4().hex
        device_summary, reports = execute_queries(serial, run_id, operation_id, args.timeout_seconds)
        oracle = json.loads((ARTIFACT_ROOT / run_id / "evaluation" / "oracle.json").read_text(encoding="utf-8"))
        metrics, rows = score_reports(oracle, reports, args.top_k)
        result_path = write_metric_artifacts(run_id, operation_id, metrics, rows)
        result = {"deviceSummary": device_summary, "metrics": metrics, "resultPath": str(result_path)}
    else:
        result = cleanup_run(serial, require_run_id(args.run_id), args.timeout_seconds)
    print(json.dumps(result, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
