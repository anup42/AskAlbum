# On-device model licenses

Model binaries are not committed to this repository. The application accepts an explicit local import, or the `consumer` build performs a user-started download from a pinned immutable revision. Every pack is activated only after its declared size and SHA-256 digest pass.

## OpenCV SFace

| Field | Pinned value |
| --- | --- |
| Purpose | Local face embeddings for opt-in familiar-person grouping |
| Upstream | [opencv/face_recognition_sface](https://huggingface.co/opencv/face_recognition_sface) |
| Revision | `c140188d35b7d0050f2dcfdfb8fe3e98d516744f` |
| File | `face_recognition_sface_2021dec.onnx` |
| Size | `38,696,353` bytes |
| SHA-256 | `0ba9fbfa01b5270c96627c4ef784da859931e02f04419c829e83484087c34e79` |
| Declared model license | Apache-2.0 |
| Runtime contract | ONNX `data` `[1,3,112,112]` to `fc1` `[1,128]` |
| Match threshold | cosine similarity `0.363`, the OpenCV reference threshold for same-identity verification |

The implementation follows OpenCV's five-landmark similarity alignment and produces an L2-normalized 128-dimensional vector. The app is not intended for commercialization, but the declared Apache-2.0 model license permits both non-commercial and commercial use. This does not remove privacy obligations: people indexing is disabled by default, embeddings remain app-private, users must explicitly opt in, and reset deletes the derived face index and labels without deleting gallery media.

The model is used only for familiar-person grouping. The application does not infer ethnicity, religion, health, sexuality, emotion, or other sensitive traits.

References: [OpenCV SFace model card](https://huggingface.co/opencv/face_recognition_sface), [OpenCV DNN face tutorial](https://docs.opencv.org/4.x/d0/dd4/tutorial_dnn_face.html), and [SFace paper](https://arxiv.org/abs/2205.12010).

## PaddleOCR PP-OCRv5 Mobile multilingual pack

The optional OCR pack contains five official PaddlePaddle ONNX/config artifacts. Each repository declares Apache-2.0. The app downloads from immutable revisions, verifies the exact byte count and SHA-256 of every file, and activates the pack atomically in app-private storage. Model files are not committed to Git.

| Target | Official repository | Revision | Bytes | SHA-256 |
| --- | --- | --- | ---: | --- |
| `det.onnx` | `PaddlePaddle/PP-OCRv5_mobile_det_onnx` | `e6f4fa85f00e168c862bc462aebca69eef9b3d3d` | 4,826,518 | `a431985659dc921974177a95adcfbb90fd9e51989a5e04d70d0b75f597b6e61d` |
| `latin.onnx` | `PaddlePaddle/latin_PP-OCRv5_mobile_rec_onnx` | `89d3a50e2c27e2e7cceeab0e944c25c807d5db4f` | 8,042,023 | `7888113072263cb471b93f66dd5e2ad70548dc526fa1ace760d0d973dd121498` |
| `latin.yml` | same | same | 6,817 | `0bbe984570f597af3638e50bdf2e8276f3ab26a61966096538b3b0d1849f5c84` |
| `devanagari.onnx` | `PaddlePaddle/devanagari_PP-OCRv5_mobile_rec_onnx` | `251aec19e36739540d35e2cc943f6aa7503b98e5` | 7,912,311 | `cb789212ce96c69d3e74728ae4309d179281d68cb3945d0616b67cafab41c986` |
| `devanagari.yml` | same | same | 5,027 | `9bd172dd26440c8ce94d1cde5d5baea6aefdc7cf3c5c8492e0beedef656d4e54` |

The embedded Android SDK source is pinned to PaddleOCR commit `2661c7c0ef5c613e8f93c6e93b2e052399f0f854` under Apache-2.0; provenance is recorded in `android/core/ocr-paddle/NOTICE.md`. The active configuration covers Latin-script languages and Hindi/Marathi/Nepali-family Devanagari text. Adding another OCR model requires a new `ModelEngineProvider<OcrEngine>`, not changes to the gallery indexer.

The Paddle Android adapter has no OpenCV dependency. Bitmap resize/crop, DB-map connected-component extraction, tensor normalization, and reading-order sorting are implemented in Kotlin/Android APIs; ONNX Runtime performs only model inference.

References: [official PaddleOCR Android deployment guide](https://www.paddleocr.ai/latest/en/version3.x/inference_deployment/cross_platform/android_deployment.html), [PP-OCRv5 multilingual documentation](https://github.com/PaddlePaddle/PaddleOCR/blob/main/docs/version3.x/algorithm/PP-OCRv5/PP-OCRv5_multi_languages.en.md), and [PaddleOCR repository](https://github.com/PaddlePaddle/PaddleOCR).

## SigLIP2 retrieval pack

The retrieval encoder is based on Google's
[`google/siglip2-base-patch16-224`](https://huggingface.co/google/siglip2-base-patch16-224)
model family. The upstream source model is Apache-2.0. AskAlbum uses a
converted, quantized, checksum-pinned pack; the pack's own manifest and license
files remain authoritative for redistribution.

The source and conversion revisions used by the build tooling are recorded in
the pack catalog and tests. We do not commit the converted weights to Git.

## Gemma local language models

The optional Gemma 4 E2B and E4B LiteRT-LM packs are downloaded only after an
explicit user action, verified before activation, and stored in app-private
storage. They are governed by the
[Gemma Terms of Use](https://ai.google.dev/gemma/terms), not by the Apache-2.0
license for AskAlbum source code. AskAlbum does not redistribute Gemma weights.

## Redistribution rule

Source code and optional model packs are separate distributions. Do not add
model binaries, signed packs, private model credentials, generated gallery
databases, or personal media to source archives or pull requests. When a pack is
redistributed by a downstream project, preserve its upstream license, notices,
checksums, and usage terms.
