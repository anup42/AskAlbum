package com.askphotos.android

import android.app.Application

/** Production dependency graph. There is deliberately no mutable test override in release code. */
class AppServices(private val application: AskPhotosApplication) {
    val galleryDatabase by lazy { GalleryDatabase(application) }
    val modelPackManager by lazy { ModelPackManager(application) }
    val modelDownloader by lazy { GemmaModelDownloader(application, modelPackManager) }
    val retrievalModelPackManager by lazy { RetrievalModelPackManager(application) }
    val embeddedRetrievalModelProvisioner by lazy {
        EmbeddedRetrievalModelProvisioner(application, retrievalModelPackManager)
    }
    val ocrModelPackManager by lazy { OcrModelPackManager(application) }
    val ocrModelDownloader by lazy { OcrModelDownloader(application, ocrModelPackManager) }
    val faceModelPackManager by lazy { FaceModelPackManager(application) }
    val faceModelDownloader by lazy { FaceModelDownloader(application, faceModelPackManager) }
    val embeddedFaceModelProvisioner by lazy { EmbeddedFaceModelProvisioner(application, faceModelPackManager) }
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
    val gemmaSessions by lazy { GemmaSessionManager(inferenceResources) }
    val embeddingEngine: ImageTextEmbeddingEngine by lazy {
        LiteRtImageTextEmbeddingEngine(retrievalModelPackManager, inferenceResources)
    }
    val semanticVectorStore by lazy {
        SemanticVectorStore(application, retrievalModelPackManager, embeddingEngine)
    }
    val visualVerifier: CandidateVerifier by lazy {
        LiteRtGemmaVisualVerifier(application, modelPackManager, gemmaSessions, galleryDatabase)
    }
    val groundedAnswerComposer by lazy {
        LiteRtGemmaGroundedAnswerComposer(modelPackManager, gemmaSessions)
    }
    val repository by lazy { GalleryRepository(application) }
}

class AskPhotosApplication : Application() {
    val services by lazy { AppServices(this) }
    val modelPackManager get() = services.modelPackManager
    val repository get() = services.repository

    override fun onCreate() {
        super.onCreate()
        if (!BuildConfig.MODEL_INDEPENDENT) {
            runCatching { services.embeddedRetrievalModelProvisioner.enqueueIfNeeded() }
            runCatching { services.embeddedFaceModelProvisioner.enqueueIfNeeded() }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            services.gemmaSessions.evictForMemoryPressure()
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        services.gemmaSessions.evictForMemoryPressure()
    }
}
