package io.github.anup42.askalbum

data class ModelEngineDescriptor(
    val id: String,
    val displayName: String,
    val producerVersion: String,
    val license: String,
    val capabilities: Set<ModelCapability>,
)

interface ModelEngineProvider<T : AutoCloseable> {
    val descriptor: ModelEngineDescriptor
    fun isAvailable(): Boolean
    suspend fun create(): T
}

data class ModelEngineLease<T : AutoCloseable>(
    val descriptor: ModelEngineDescriptor,
    val engine: T,
) : AutoCloseable {
    override fun close() = engine.close()
}

/** Ordered provider registry. A new model is plugged in by registering one provider. */
class PluggableModelEngineRegistry<T : AutoCloseable>(
    private val providers: List<ModelEngineProvider<T>>,
) {
    init {
        require(providers.isNotEmpty()) { "At least one engine provider is required" }
        require(providers.map { it.descriptor.id }.distinct().size == providers.size) { "Engine provider IDs must be unique" }
    }

    fun availableDescriptors(): List<ModelEngineDescriptor> = providers.filter { it.isAvailable() }.map { it.descriptor }

    fun activeDescriptor(): ModelEngineDescriptor? = providers.firstOrNull { it.isAvailable() }?.descriptor

    suspend fun acquire(): ModelEngineLease<T> {
        val provider = providers.firstOrNull { it.isAvailable() }
            ?: error("No compatible on-device engine is available")
        return ModelEngineLease(provider.descriptor, provider.create())
    }

    suspend fun acquireOrNull(): ModelEngineLease<T>? = providers.firstOrNull { it.isAvailable() }?.let { provider ->
        ModelEngineLease(provider.descriptor, provider.create())
    }
}
