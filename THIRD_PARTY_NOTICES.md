# Third-party notices

AskAlbum source code is licensed under the Apache License 2.0. That license
does not relicense third-party libraries, model weights, model packs, or demo
media. The authoritative license and checksum files shipped with an installed
pack take precedence over this summary.

## Runtime libraries

- Kotlin, AndroidX, Jetpack Compose, WorkManager, Room, and related Android
  libraries are used under their respective Apache License 2.0 terms.
- ONNX Runtime is used under its MIT license and may include additional
  notices in the Android distribution.
- LiteRT and LiteRT-LM are Google products distributed under their applicable
  open-source and platform terms.
- ML Kit components are Google services/libraries with Google terms that apply
  to the selected artifact and its use.
- OpenCV components are used under their Apache License 2.0 terms.

## Optional model packs

- SigLIP2 text/image retrieval uses the `google/siglip2-base-patch16-224`
  model family, whose source model is Apache-2.0. Converted ONNX artifacts
  remain subject to the upstream model and artifact-repository terms.
- SFace and PaddleOCR packs are downloaded separately and carry their own
  license files and checksums.
- Gemma packs are governed by the [Gemma Terms of Use](https://ai.google.dev/gemma/terms),
  not by the Apache license in this repository. AskAlbum does not commit or
  redistribute Gemma weights.

Model pack downloads are explicit, on-device, checksum/signature verified, and
kept out of source distributions. Contributors must not commit model binaries,
private credentials, generated databases, or gallery exports.

## Demo media

The tracked sample-gallery assets are synthetic or openly licensed test media.
Any replacement demo media must include its source and license in the relevant
manifest before it is committed.
