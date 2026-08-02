# Contributing to AskAlbum

AskAlbum welcomes focused contributions across Android, on-device ML,
multimodal retrieval, privacy, accessibility, testing, and documentation.

## Find the right starting point

- Browse [`good first issue`](https://github.com/anup42/AskAlbum/labels/good%20first%20issue)
  for a bounded first change.
- Browse [`help wanted`](https://github.com/anup42/AskAlbum/labels/help%20wanted)
  for larger contributions.
- Use [Discussions](https://github.com/anup42/AskAlbum/discussions) for design
  questions and proposals that are not ready to become issues.
- Open a focused issue before a large behavior, schema, permission, dependency,
  or model-lifecycle change.

## Before opening a change

- Read `README.md`, `PRIVACY.md`, `SECURITY.md`, and
  `THIRD_PARTY_NOTICES.md`.
- Keep the change focused and preserve existing gallery data, People
  corrections, indexes, and model validation.
- Do not add cloud inference, a local HTTP server, generated SQL, or arbitrary
  model-generated tool execution.
- Do not commit APKs, model binaries, gallery databases, photos, credentials,
  device logs, or generated review archives.
- Never use real private gallery content as a fixture. Prefer synthetic or
  explicitly consented demo assets with clear provenance.

## Local development

Use JDK 17 and the Gradle wrapper from `android/`.

```bash
cd android
./gradlew :app:testCiDebugUnitTest :app:assembleCiDebug --no-daemon
```

On Windows PowerShell, use `./gradlew.bat`. The `ciDebug` variant uses fixture
engines and is the required model-independent development path.

Before changing offline behavior, also build:

```bash
./gradlew :app:assembleOfflineDemoDebug --no-daemon
```

Model packs are intentionally absent from the repository. Do not weaken
signature, checksum, activation, or rollback validation to make a local build
easier.

## Engineering expectations

- Keep planner output behind typed validation and real capability executors.
- Apply hard eligibility filters before semantic top-K retrieval.
- Preserve evidence scope, provenance, uncertainty, and person-cluster binding.
- Report partial, unavailable, and failed retrieval channels truthfully.
- Keep sensitive OCR behind authentication and out of model prompts, logs, and
  unauthenticated evidence.
- Keep People indexing explicit opt-in and preserve reviewed corrections.
- Use non-destructive Room migrations and data-preserving replacement installs.
- Add focused unit, migration, or device tests for behavior changes.

## Pull requests

1. Explain the user-visible behavior and why the change is needed.
2. Describe privacy, storage, permission, indexing, and model-pack impact.
3. List exact test/build commands and their outcomes.
4. Distinguish `SKIPPED` and `NOT RUN` from `PASS`.
5. Keep the offline variant free of Internet permission.
6. Do not clear app data, reset indexes, or uninstall during normal device
   validation.

Small, reviewable pull requests are preferred. A pull request should solve one
coherent problem rather than combine unrelated cleanup and features.

## Security and privacy reports

Do not disclose suspected vulnerabilities or private media in an issue or
discussion. Follow [`SECURITY.md`](SECURITY.md) and use the repository's
[private vulnerability report](https://github.com/anup42/AskAlbum/security/advisories/new).

