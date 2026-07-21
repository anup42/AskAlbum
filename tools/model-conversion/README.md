# Retrieval model pack

These development-only tools convert a pinned `google/siglip2-base-patch16-224`
revision into separate LiteRT image/text encoders, validate conversion parity, export
the tokenizer vocabulary, and create a signed `.agretrieval` pack. Generated models
must remain outside Git.

1. Create a disposable virtual environment and install `requirements.txt`.
2. Run `convert_siglip2.py --revision <40-char-commit> --output build/retrieval-converted`.
3. Set `AG_KEYSTORE_PASSWORD` and optionally `AG_KEY_PASSWORD`.
4. Run `build_retrieval_pack.py --converted build/retrieval-converted --output build/siglip2.agretrieval --pack-version <version> --keystore <apk-signing-keystore> --alias <alias>`.

The pack must be signed by the same certificate as the installed APK. The app rejects
unknown signatures, changed files, extra ZIP entries, unsafe names, unsupported tensor
contracts, non-pinned source revisions, and failed conversion parity.

## Signed Gemma pack

Wrap an externally obtained, license-compliant LiteRT-LM Gemma 4 model in the app's signed `.agemma` format:

```powershell
$env:AG_KEYSTORE_PASSWORD = "..."
$env:AG_KEY_PASSWORD = "..."
python tools/model-conversion/build_gemma_pack.py `
  --model C:\models\gemma-4-e2b.litertlm `
  --license C:\models\GEMMA-LICENSE.txt `
  --tier E2B `
  --pack-version 2026-07-21 `
  --source-revision <pinned-lowercase-revision> `
  --keystore <the-keystore-used-to-sign-the-APK> `
  --alias <signing-alias> `
  --output C:\models\gemma-4-e2b.agemma
```

Android verifies the certificate-key fingerprint, exact manifest signature, entry set, sizes, and SHA-256 values before atomically activating a generation. E2B is the default tier. E4B declares at least 8 GiB RAM and is accepted only when the device assessment recommends it. The app never accepts a raw `.litertlm` file directly.
