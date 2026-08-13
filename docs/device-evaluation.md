# Connected-device dataset evaluation

The evaluation harness uses the isolated `io.github.anup42.askalbum.evaluation` package. It runs
the production SigLIP2, OCR, planner, verifier, and answer-composer implementations without
opening or modifying the consumer app database.

## Dataset contract

An input directory contains:

- `images.zip`, with image paths referenced by `image_context.json`;
- `image_context.json`, an array with unique `id`, `image_path`, and optional `capture_time` and tags;
- `queries_v2.json`, an array with `query_id`, `query`, `answer`, and `image_ids`.

Context tags and scoring answers remain in ignored host artifacts. Only image bytes, capture time,
query ID, and query text are transferred to the evaluation app. This prevents relevance labels,
reference answers, and benchmark rewrites from leaking into retrieval or generation.

## Interface

```powershell
python tools/device/evaluate_gallery_dataset.py install
python tools/device/evaluate_gallery_dataset.py provision-model
python tools/device/evaluate_gallery_dataset.py index --dataset-dir C:\path\to\eval_dataset --run-id eval_run_001
python tools/device/evaluate_gallery_dataset.py search --run-id eval_run_001 --query "What is shown?"
python tools/device/evaluate_gallery_dataset.py evaluate --dataset-dir C:\path\to\eval_dataset --run-id eval_run_001
python tools/device/evaluate_gallery_dataset.py cleanup --run-id eval_run_001
```

`install` uses replacement installation. `provision-model` copies only the active verified Gemma
generation from the signed consumer package into the isolated evaluation package; it does not
write to the consumer package. `cleanup` deletes only MediaStore rows owned by the exact run ID.

## Search JSON

Each search returns:

- `matchedImageIds` in ranked order;
- `answerText` and typed answer metadata;
- per-hit evidence;
- the validated plan;
- progressive module outputs and elapsed intervals;
- typed retrieval-channel coverage, errors, and model versions;
- repository and end-to-end latency.

## Metrics

Search precision, recall, and F1 are macro-averaged per query over the first 10 returned image
IDs. Micro metrics are also retained. ROUGE-1, ROUGE-2, and ROUGE-L are token-overlap F1 scores;
queries with an empty reference answer are reported as `NOT_SCORED_NO_REFERENCE`, never as pass
or zero. Average, p50, and p95 end-to-end query latency are reported for device results.
