# PaddleOCR Android SDK provenance

Source files in this module were imported from `PaddlePaddle/PaddleOCR` commit
`2661c7c0ef5c613e8f93c6e93b2e052399f0f854`, directory
`deploy/ppocr-android/ppocr-sdk`, under Apache License 2.0.

Local changes add app-private filesystem model loading. The upstream API and
preprocessing/postprocessing remain otherwise source-compatible.

Local preprocessing and postprocessing use Kotlin and Android Bitmap APIs. This
module deliberately has no OpenCV dependency.
