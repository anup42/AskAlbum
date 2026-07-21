package com.askphotos.android

import android.app.Application

/** Production dependency graph. There is deliberately no mutable test override in release code. */
class AppServices(private val application: AskPhotosApplication) {
    val modelPackManager by lazy { ModelPackManager(application) }
    val retrievalModelPackManager by lazy { RetrievalModelPackManager(application) }
    val inferenceResources: InferenceResourceManager by lazy { SerializedInferenceResourceManager() }
    val embeddingEngine: ImageTextEmbeddingEngine by lazy {
        LiteRtImageTextEmbeddingEngine(retrievalModelPackManager, inferenceResources)
    }
    val semanticVectorStore by lazy {
        SemanticVectorStore(application, retrievalModelPackManager, embeddingEngine)
    }
    val repository by lazy { GalleryRepository(application) }
}

class AskPhotosApplication : Application() {
    val services by lazy { AppServices(this) }
    val modelPackManager get() = services.modelPackManager
    val repository get() = services.repository
}
