package app.muka.bonsai.llama

import app.muka.bonsai.llama.InferenceEngine.State
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Interface defining the core LLM inference operations.
 */
interface InferenceEngine {
    /**
     * Current state of the inference engine
     */
    val state: StateFlow<State>

    /**
     * Load a model from the given path.
     *
     * @throws UnsupportedArchitectureException if model architecture not supported
     */
    suspend fun loadModel(pathToModel: String, params: InferenceParams = InferenceParams())

    /**
     * Sends a system prompt to the loaded model
     */
    suspend fun setSystemPrompt(systemPrompt: String)

    /**
     * Sends a user prompt to the loaded model and returns a Flow of generated tokens.
     */
    fun sendUserPrompt(message: String, predictLength: Int = DEFAULT_PREDICT_LENGTH): Flow<String>

    /**
     * Runs a benchmark with the specified parameters.
     */
    suspend fun bench(pp: Int, tg: Int, pl: Int, nr: Int = 1): String

    /**
     * Cancel an in-progress generation without unloading the model.
     */
    fun stopGeneration()

    /**
     * Unloads the currently loaded model.
     */
    fun cleanUp()

    /**
     * Reload the last loaded model with the same parameters.
     * Useful after changing configuration or stopping generation.
     */
    suspend fun reload()

    /**
     * Cleans up resources when the engine is no longer needed.
     */
    fun destroy()

    /**
     * States of the inference engine
     */
    sealed class State {
        object Uninitialized : State()
        object Initializing : State()
        object Initialized : State()

        object LoadingModel : State()
        object UnloadingModel : State()
        object ModelReady : State()

        object Benchmarking : State()
        object ProcessingSystemPrompt : State()
        object ProcessingUserPrompt : State()

        object Generating : State()

        data class Error(val exception: Exception) : State()
    }

    companion object {
        const val DEFAULT_PREDICT_LENGTH = 1024
    }
}

/**
 * KV cache storage type. Quantized types roughly halve (Q8_0) or quarter (Q4_0)
 * KV cache memory at a small quality cost. Applied to both K and V.
 */
enum class KvCacheType(val label: String) {
    F16("不量化 (F16)"),
    Q8_0("Q8_0"),
    Q4_0("Q4_0"),
}

/**
 * Runtime parameters for model loading and text generation.
 */
data class InferenceParams(
    val contextSize: Int = 8192,
    val maxTokens: Int = 2048,
    val temperature: Float = 0.3f,
    // Default to the number of hardware threads (Snapdragon 8 Elite: 8).
    val threadCount: Int = Runtime.getRuntime().availableProcessors(),
    val systemPrompt: String = "你是 Bonsai，一位乐于助人的本地设备助手。",
    val kvCacheType: KvCacheType = KvCacheType.F16,
)

val State.isUninterruptible
    get() = this is State.Initializing ||
        this is State.LoadingModel ||
        this is State.UnloadingModel ||
        this is State.Benchmarking ||
        this is State.ProcessingSystemPrompt ||
        this is State.ProcessingUserPrompt

val State.isModelLoaded: Boolean
    get() = this is State.ModelReady ||
        this is State.Benchmarking ||
        this is State.ProcessingSystemPrompt ||
        this is State.ProcessingUserPrompt ||
        this is State.Generating

class UnsupportedArchitectureException : Exception()
