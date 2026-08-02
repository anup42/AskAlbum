# Privacy

AskAlbum is designed for private, on-device photo search.

- Gallery media is read from Android MediaStore after the user grants access.
- OCR, face indexing, embeddings, retrieval, planning, verification, and
  grounded answers run locally when the required packs are installed.
- The app does not require a cloud account or a local HTTP server.
- The consumer build may use network access only for explicit model-pack
  downloads and update checks implemented by the app. Inference remains local.
- The `offlineDemo` variant has no Internet permission and uses fixture engines.
- Face indexing is explicit opt-in. People-derived data can be reset from the
  app and is not uploaded by AskAlbum.
- Sensitive OCR facts are authentication-protected and are redacted before
  model prompts, embeddings, logs, and ordinary evidence display.
- AskAlbum does not include analytics, advertising identifiers, or a bundled
  telemetry service.

The operating system, downloaded model repositories, and third-party libraries
may have separate terms or diagnostics. Review `THIRD_PARTY_NOTICES.md` before
redistributing a build.
