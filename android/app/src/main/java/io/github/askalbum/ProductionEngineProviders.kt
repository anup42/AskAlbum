package io.github.anup42.askalbum

import android.content.Context

class PaddleOcrEngineProvider(
    private val context: Context,
    private val packs: OcrModelPackManager,
) : ModelEngineProvider<OcrEngine> {
    override val descriptor = ModelEngineDescriptor(
        id = "paddleocr-v5-multilingual",
        displayName = OcrModelCatalog.paddleV5Multilingual.displayName,
        producerVersion = OcrModelCatalog.paddleV5Multilingual.producerVersion,
        license = OcrModelCatalog.paddleV5Multilingual.license,
        capabilities = setOf(ModelCapability.OCR),
    )

    override fun isAvailable(): Boolean = packs.current() != null
    override suspend fun create(): OcrEngine = PaddleMultilingualOcrEngine.create(context, requireNotNull(packs.current()))
}

class MlKitOcrEngineProvider : ModelEngineProvider<OcrEngine> {
    override val descriptor = ModelEngineDescriptor(
        id = "mlkit-ocr-latin",
        displayName = "Bundled ML Kit Latin OCR",
        producerVersion = MlKitOcrEngine.PRODUCER_VERSION,
        license = "Google ML Kit terms",
        capabilities = setOf(ModelCapability.OCR),
    )

    override fun isAvailable(): Boolean = true
    override suspend fun create(): OcrEngine = MlKitOcrEngine()
}

class SFaceEngineProvider(
    private val packs: FaceModelPackManager,
) : ModelEngineProvider<FaceEngine> {
    override val descriptor = ModelEngineDescriptor(
        id = "opencv-sface",
        displayName = FaceModelCatalog.sface.displayName,
        producerVersion = FaceModelCatalog.sface.producerVersion,
        license = FaceModelCatalog.sface.license,
        capabilities = setOf(ModelCapability.FACES),
    )

    override fun isAvailable(): Boolean = packs.current() != null
    override suspend fun create(): FaceEngine = OpenCvSFaceEngine(packs)
}
