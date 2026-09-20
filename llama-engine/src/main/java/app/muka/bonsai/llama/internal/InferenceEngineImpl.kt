package app.muka.bonsai.llama.internal

import android.content.Context
import android.util.Log
import app.muka.bonsai.llama.InferenceEngine
import app.muka.bonsai.llama.InferenceParams
import app.muka.bonsai.llama.KvCacheType
import app.muka.bonsai.llama.SamplingParams
import app.muka.bonsai.llama.UnsupportedArchitectureException
import app.muka.bonsai.llama.internal.InferenceEngineImpl.Companion.getInstance
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * JNI wrapper for the llama.cpp library providing Android-friendly access to large language models.
 *
 * This class implements a singleton pattern for managing the lifecycle of a single LLM instance.
 * All operations are executed on a dedicated single-threaded dispatcher to ensure thread safety
 * with the underlying C++ native code.
 *
 * The typical usage flow is:
 * 1. Get instance via [getInstance]
 * 2. Load a model with [loadModel]
 * 3. Send prompts with [sendUserPrompt]
 * 4. Generate responses as token streams
 * 5. Perform [cleanUp] when done with a model
 * 6. Properly [destroy] when completely done
 *
 * State transitions are managed automatically and validated at each operation.
 *
 * @see ai_chat.cpp for the native implementation details
 */
internal class InferenceEngineImpl private constructor(
    private val nativeLibDir: String
) : InferenceEngine {

    companion object {
        private val TAG = InferenceEngineImpl::class.java.simpleName

        @Volatile
        private var instance: InferenceEngine? = null

        /**
         * Create or obtain [InferenceEngineImpl]'s single instance.
         *
         * @param Context for obtaining native library directory
         * @throws IllegalArgumentException if native library path is invalid
         * @throws UnsatisfiedLinkError if library failed to load
         */
        internal fun getInstance(context: Context) =
            instance ?: synchronized(this) {
                val nativeLibDir = context.applicationInfo.nativeLibraryDir
                require(nativeLibDir.isNotBlank()) { "Expected a valid native library path!" }

                try {
                    Log.i(TAG, "Instantiating InferenceEngineImpl,,,")
                    InferenceEngineImpl(nativeLibDir).also { instance = it }
                } catch (e: UnsatisfiedLinkError) {
                    Log.e(TAG, "Failed to load native library from $nativeLibDir", e)
                    throw e
                }
            }
    }

    /**
     * JNI methods
     * @see ai_chat.cpp
     */
    private external fun init(nativeLibDir: String)

    private external fun load(modelPath: String, mmprojPath: String): Int

    private external fun prepare(nCtx: Int, nThreads: Int, temperature: Float, kvType: Int): Int

    private external fun systemInfo(): String

    private external fun benchModel(pp: Int, tg: Int, pl: Int, nr: Int): String

    private external fun tokenizeText(text: String): IntArray?

    private external fun detokenizeText(ids: IntArray): String?

    private external fun processSystemPrompt(systemPrompt: String): Int

    private external fun processUserPrompt(userPrompt: String, predictLength: Int): Int

    private external fun processUserPromptMtmd(userPrompt: String, imagePaths: Array<String>, predictLength: Int): Int

    private external fun isVisionEnabled(): Boolean

    private external fun generateNextToken(): String?

    private external fun getLastStopReasonImpl(): Int

    private external fun setSamplingParams(
        temp: Float,
        topK: Int,
        topP: Float,
        penaltyRepeat: Float,
        penaltyFreq: Float,
        penaltyPresent: Float,
        seed: Int,
    )

    private external fun unload()

    private external fun shutdown()

    private val _state =
        MutableStateFlow<InferenceEngine.State>(InferenceEngine.State.Uninitialized)
    override val state: StateFlow<InferenceEngine.State> = _state.asStateFlow()

    @Volatile
    override var isMultimodal: Boolean = false
        private set

    private var _readyForSystemPrompt = false
    @Volatile
    private var _cancelGeneration = false

    /**
     * Single-threaded coroutine dispatcher & scope for LLama asynchronous operations
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val llamaDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val llamaScope = CoroutineScope(llamaDispatcher + SupervisorJob())

    init {
        llamaScope.launch {
            try {
                check(_state.value is InferenceEngine.State.Uninitialized) {
                    "Cannot load native library in ${_state.value.javaClass.simpleName}!"
                }
                _state.value = InferenceEngine.State.Initializing
                Log.i(TAG, "Loading native library...")
                System.loadLibrary("ai-chat")
                init(nativeLibDir)
                _state.value = InferenceEngine.State.Initialized
                Log.i(TAG, "Native library loaded! System info: \n${systemInfo()}")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to load native library", e)
                throw e
            }
        }
    }

    private var _currentParams: InferenceParams = InferenceParams()
    private var _currentModelPath: String? = null

    private var _mmprojLoaded = false

    /**
     * Load the LLM
     */
    override suspend fun loadModel(pathToModel: String, params: InferenceParams, mmprojPath: String?) =
        withContext(llamaDispatcher) {
            check(_state.value is InferenceEngine.State.Initialized) {
                "Cannot load model in ${_state.value.javaClass.simpleName}!"
            }

            try {
                Log.i(TAG, "Checking access to model file... \n$pathToModel")
                File(pathToModel).let {
                    require(it.exists()) { "File not found" }
                    require(it.isFile) { "Not a valid file" }
                    require(it.canRead()) { "Cannot read file" }
                }
                if (mmprojPath != null) {
                    File(mmprojPath).let {
                        require(it.exists()) { "mmproj file not found" }
                        require(it.isFile) { "Not a valid mmproj file" }
                        require(it.canRead()) { "Cannot read mmproj file" }
                    }
                }

                Log.i(TAG, "Loading model... \n$pathToModel\nmmproj=$mmprojPath")
                _readyForSystemPrompt = false
                _state.value = InferenceEngine.State.LoadingModel
                load(pathToModel, mmprojPath ?: "").let {
                    when (it) {
                        0 -> {}
                        2 -> throw IllegalArgumentException("mmproj 加载失败，请检查文件是否与模型匹配")
                        else -> throw UnsupportedArchitectureException()
                    }
                }
                _mmprojLoaded = isVisionEnabled()
                isMultimodal = _mmprojLoaded
                Log.i(TAG, "isVisionEnabled native result=$_mmprojLoaded")
                if (mmprojPath != null && !_mmprojLoaded) {
                    throw IllegalArgumentException("mmproj 不支持视觉输入")
                }
                prepare(
                    params.contextSize,
                    params.threadCount,
                    params.sampling.temperature,
                    params.kvCacheType.ordinal,
                ).let {
                    if (it != 0) throw IOException("Failed to prepare resources")
                }
                _currentModelPath = pathToModel
                _currentParams = params
                Log.i(TAG, "Model loaded! params=$params")
                _readyForSystemPrompt = true

                _cancelGeneration = false
                _state.value = InferenceEngine.State.ModelReady
            } catch (e: Exception) {
                Log.e(TAG, (e.message ?: "Error loading model") + "\n" + pathToModel, e)
                _state.value = InferenceEngine.State.Error(e)
                throw e
            }
        }

    /**
     * Process the plain text system prompt
     *
     * TODO-han.yin: return error code if system prompt not correct processed?
     */
    override suspend fun setSystemPrompt(systemPrompt: String) =
        withContext(llamaDispatcher) {
            require(systemPrompt.isNotBlank()) { "Cannot process empty system prompt!" }
            check(_readyForSystemPrompt) { "System prompt must be set ** RIGHT AFTER ** model loaded!" }
            check(_state.value is InferenceEngine.State.ModelReady) {
                "Cannot process system prompt in ${_state.value.javaClass.simpleName}!"
            }

            Log.i(TAG, "Sending system prompt...")
            _readyForSystemPrompt = false
            _state.value = InferenceEngine.State.ProcessingSystemPrompt
            processSystemPrompt(systemPrompt).let { result ->
                if (result != 0) {
                    RuntimeException("Failed to process system prompt: $result").also {
                        _state.value = InferenceEngine.State.Error(it)
                        throw it
                    }
                }
            }
            Log.i(TAG, "System prompt processed! Awaiting user prompt...")
            _state.value = InferenceEngine.State.ModelReady
        }

    /**
     * Send plain text user prompt to LLM, which starts generating tokens in a [Flow]
     */
    override fun sendUserPrompt(
        message: String,
        predictLength: Int,
        sampling: SamplingParams,
        imagePaths: List<String>,
    ): Flow<String> = flow {
        require(message.isNotEmpty() || imagePaths.isNotEmpty()) {
            "User prompt discarded due to being empty!"
        }
        check(_state.value is InferenceEngine.State.ModelReady) {
            "User prompt discarded due to: ${_state.value.javaClass.simpleName}"
        }

        try {
            Log.i(TAG, "Sending user prompt...")
            _readyForSystemPrompt = false
            _cancelGeneration = false
            _historyWasDropped = false
            _state.value = InferenceEngine.State.ProcessingUserPrompt

            setSamplingParams(
                sampling.temperature,
                sampling.topK,
                sampling.topP,
                sampling.repeatPenalty,
                sampling.frequencyPenalty,
                sampling.presencePenalty,
                sampling.seed,
            )

            // With a vision model, always go through mtmd: markers from earlier
            // image messages survive in the re-formatted conversation history,
            // so the bitmap count must match whatever the text path would render.
            val useMtmd = _mmprojLoaded || imagePaths.isNotEmpty()
            val result = if (useMtmd) {
                if (!_mmprojLoaded) {
                    throw IllegalStateException("当前模型未加载 mmproj，无法处理图片")
                }
                processUserPromptMtmd(message, imagePaths.toTypedArray(), predictLength)
            } else {
                processUserPrompt(message, predictLength)
            }
            result.let {
                when (it) {
                    0 -> {}
                    1 -> throw IllegalStateException("当前模型未加载 mmproj")
                    5 -> throw IllegalStateException("用户输入超过上下文长度，请缩短消息或增大上下文")
                    // succeeded, but only because the earlier turns were dropped
                    7 -> _historyWasDropped = true
                    else -> {
                        Log.e(TAG, "Failed to process user prompt: $it")
                        return@flow
                    }
                }
            }

            Log.i(TAG, "User prompt processed. Generating assistant prompt...")
            _state.value = InferenceEngine.State.Generating
            while (!_cancelGeneration) {
                generateNextToken()?.let { utf8token ->
                    if (utf8token.isNotEmpty()) emit(utf8token)
                } ?: break
            }
            if (_cancelGeneration) {
                Log.i(TAG, "Assistant generation aborted per requested.")
            } else {
                Log.i(TAG, "Assistant generation complete. Awaiting user prompt...")
            }
            _state.value = InferenceEngine.State.ModelReady
        } catch (e: CancellationException) {
            Log.i(TAG, "Assistant generation's flow collection cancelled.")
            _state.value = InferenceEngine.State.ModelReady
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error during generation!", e)
            _state.value = InferenceEngine.State.Error(e)
            throw e
        }
    }.flowOn(llamaDispatcher)

    /**
     * Benchmark the model
     */
    override suspend fun bench(pp: Int, tg: Int, pl: Int, nr: Int): String =
        withContext(llamaDispatcher) {
            check(_state.value is InferenceEngine.State.ModelReady) {
                "Benchmark request discarded due to: $state"
            }
            Log.i(TAG, "Start benchmark (pp: $pp, tg: $tg, pl: $pl, nr: $nr)")
            _readyForSystemPrompt = false   // Just to be safe
            _state.value = InferenceEngine.State.Benchmarking
            benchModel(pp, tg, pl, nr).also {
                _state.value = InferenceEngine.State.ModelReady
            }
        }

    /**
     * Tokenizer accessors for the vocabulary-analysis page. Both yield null when no
     * model is resident; the native guards own that, not the engine state here,
     * because a loaded-but-idle model is exactly when they are usable.
     */
    override suspend fun tokenize(text: String): IntArray? =
        withContext(llamaDispatcher) { tokenizeText(text) }

    override suspend fun detokenize(tokens: IntArray): String? =
        withContext(llamaDispatcher) { detokenizeText(tokens) }

    override suspend fun debugGpuCpuParity(modelPath: String, prompt: String): String? =
        withContext(llamaDispatcher) { debugGpuCpuParityNative(modelPath, prompt) }

    private external fun debugGpuCpuParityNative(modelPath: String, prompt: String): String?

    /**
     * Cancel an in-progress generation without unloading the model.
     */
    override fun stopGeneration() {
        _cancelGeneration = true
        Log.i(TAG, "Generation cancel requested")
    }

    override fun getLastStopReason(): Int = getLastStopReasonImpl()

    @Volatile
    private var _historyWasDropped = false
    override val historyWasDropped: Boolean get() = _historyWasDropped

    /**
     * Unloads the model and frees resources, or reset error states
     */
    override fun cleanUp() {
        _cancelGeneration = true
        runBlocking(llamaDispatcher) {
            when (val state = _state.value) {
                is InferenceEngine.State.ModelReady -> {
                    Log.i(TAG, "Unloading model and free resources...")
                    _readyForSystemPrompt = false
                    _state.value = InferenceEngine.State.UnloadingModel

                    unload()
                    _mmprojLoaded = false
                    isMultimodal = false

                    _state.value = InferenceEngine.State.Initialized
                    Log.i(TAG, "Model unloaded!")
                    Unit
                }

                is InferenceEngine.State.Error -> {
                    Log.i(TAG, "Resetting error states...")
                    _mmprojLoaded = false
                    isMultimodal = false
                    _state.value = InferenceEngine.State.Initialized
                    Log.i(TAG, "States reset!")
                    Unit
                }

                else -> throw IllegalStateException("Cannot unload model in ${state.javaClass.simpleName}")
            }
        }
    }

    /**
     * Reload the last loaded model with the same parameters.
     */
    override suspend fun reload() {
        cleanUp()
        val path = _currentModelPath
            ?: throw IllegalStateException("No model has been loaded yet")
        loadModel(path, _currentParams)
    }
    override fun destroy() {
        _cancelGeneration = true
        runBlocking(llamaDispatcher) {
            _readyForSystemPrompt = false
            when(_state.value) {
                is InferenceEngine.State.Uninitialized -> {}
                is InferenceEngine.State.Initialized -> shutdown()
                else -> { unload(); shutdown() }
            }
            // Leave no stale state behind: any later load in this process would
            // otherwise try to unload already-freed native resources.
            _state.value = InferenceEngine.State.Uninitialized
        }
        llamaScope.cancel()
    }
}
