# Contributing to AskAlbum

## Before opening a change

- Read `README.md`, `PRIVACY.md`, and `THIRD_PARTY_NOTICES.md`.
- Keep changes focused and preserve existing gallery data, People corrections,
  indexes, and model validation.
- Do not commit APKs, model binaries, gallery databases, photos, credentials,
  device logs, or generated review archives.
- Do not add cloud inference, a local HTTP server, generated SQL, or arbitrary
  model-generated tool execution.

## Local development

Use Java 17 and the Gradle wrapper from `android/`.

```powershell
cd android
./gradlew :app:testCiDebugUnitTest :app:assembleCiDebug --no-daemon
```

`ciDebug` is the model-independent fixture/CI path. `consumerDebug` and
`offlineDemoDebug` require the documented local model-pack setup; model packs
are intentionally not part of the repository.

## Pull requests

- Explain the behavior change and privacy impact.
- Add or update focused unit, migration, or device tests.
- Report build/test variants and clearly distinguish skipped tests from passes.
- Keep the offline variant free of Internet permission.
- Use replacement installation for device validation; never clear app data or
  reset indexes as part of a normal test.
