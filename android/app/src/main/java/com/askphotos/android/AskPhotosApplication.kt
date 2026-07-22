package com.askphotos.android

import android.app.Application

/** Production dependency graph. There is deliberately no mutable test override in release code. */
class AppServices(private val application: AskPhotosApplication) {
    val modelPackManager by lazy { ModelPackManager(application) }
    val modelDownloader by lazy { GemmaModelDownloader(application, modelPackManager) }
    val retrievalModelPackManager by lazy { RetrievalModelPackManager(application) }
    val retrievalModelDownloader by lazy { RetrievalModelDownloader(application, retrievalModelPackManager) }
    val ocrModelPackManager by lazy { OcrModelPackManager(application) }
    val ocrModelDownloader by lazy { OcrModelDownloader(application, ocrModelPackManager) }
    val faceModelPackManager by lazy { FaceModelPackManager(application) }
    val faceModelDownloader by lazy { FaceModelDownloader(application, faceModelPackManager) }
    val faceVectorStore by lazy { FaceVectorStore(application) }
    val ocrEngines by lazy {
        PluggableModelEngineRegistry<OcrEngine>(
            listOf(PaddleOcrEngineProvider(application, ocrModelPackManager), MlKitOcrEngineProvider()),
        )
    }
    val faceEngines by lazy {
        PluggableModelEngineRegistry<FaceEngine>(listOf(SFaceEngineProvider(faceModelPackManager)))
    }
    val inferenceResources: InferenceResourceManager by lazy { SerializedInferenceResourceManager() }
    val embeddingEngine: ImageTextEmbeddingEngine by lazy {
        LiteRtImageTextEmbeddingEngine(retrievalModelPackManager, inferenceResources)
    }
    val semanticVectorStore by lazy {
        SemanticVectorStore(application, retrievalModelPackManager, embeddingEngine)
    }
    val visualVerifier: CandidateVerifier by lazy {
        LiteRtGemmaVisualVerifier(application, modelPackManager, inferenceResources)
    }
    val groundedAnswerComposer by lazy {
        LiteRtGemmaGroundedAnswerComposer(modelPackManager, inferenceResources)
    }
    val repository by lazy { GalleryRepository(application) }
}

class AskPhotosApplication : Application() {
    val services by lazy { AppServices(this) }
    val modelPackManager get() = services.modelPackManager
    val repository get() = services.repository
}
