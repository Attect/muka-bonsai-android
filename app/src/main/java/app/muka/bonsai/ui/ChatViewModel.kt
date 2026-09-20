package app.muka.bonsai.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Debug
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.muka.bonsai.BuildConfig
import app.muka.bonsai.llama.AiChat
import app.muka.bonsai.llama.InferenceEngine
import app.muka.bonsai.llama.InferenceParams
import app.muka.bonsai.llama.KvCacheType
import app.muka.bonsai.llama.SamplingParams
import app.muka.bonsai.llama.gguf.FileType
import app.muka.bonsai.llama.gguf.GgufMetadata
import app.muka.bonsai.api.OpenAiServer
import app.muka.bonsai.model.AVAILABLE_MODELS
import app.muka.bonsai.model.BonsaiModel
import app.muka.bonsai.model.DownloadSource
import app.muka.bonsai.model.DownloadStatus
import app.muka.bonsai.model.GgufKind
import app.muka.bonsai.model.ModelManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

data class ChatMessage(
    val role: Role,
    val text: String,
    val isGenerating: Boolean = false,
    /** Local path of an image attached to this message (user messages only). */
    val imagePath: String? = null,
) {
    enum class Role { User, Assistant }
}

data class AppUiState(
    val selectedTab: Screen = Screen.Chat,
    val messages: List<ChatMessage> = emptyList(),
    val inputText: String = "",
    val isGenerating: Boolean = false,
    val selectedModel: BonsaiModel = AVAILABLE_MODELS.first(),
    val modelPath: String? = null,
    /** Profile being edited on the settings page when no model is loaded (file name). */
    val profileFileName: String? = null,
    val engineState: InferenceEngine.State = InferenceEngine.State.Uninitialized,
    val errorMessage: String? = null,
    val infoMessage: String? = null,
    val params: InferenceParams = InferenceParams(),
    val metrics: PerformanceMetrics = PerformanceMetrics(),
    val localModels: List<File> = emptyList(),
    /** Locally imported mmproj projector files (paired or orphaned). */
    val mmprojFiles: List<File> = emptyList(),
    val downloadStatuses: Map<String, DownloadStatus> = emptyMap(),
    /** Model file absolute path -> max context length read from GGUF metadata. */
    val modelContextLengths: Map<String, Int> = emptyMap(),
    /** Catalog model ids whose GGUF is present but the mmproj is still missing. */
    val mmprojMissingIds: Set<String> = emptySet(),
    /** Whether the loaded model has a vision mmproj attached. */
    val isMultimodal: Boolean = false,
    /** Local path of the image attached to the next message, if any. */
    val pendingImagePath: String? = null,
    val apiServerRunning: Boolean = false,
    val apiServerPort: Int = ChatViewModel.API_PORT,
    val apiServerError: String? = null,
    /** Whether API requests auto-load the model named in their "model" field. */
    val apiAutoSwitchModel: Boolean = true,
    val downloadSource: DownloadSource = DownloadSource.Default,

    /** Vocabulary analysis page: text to count plus the in-memory display filter. */
    val vocabInput: VocabInput = VocabInput(),
    /** Last analysis outcome, or null if none has completed. */
    val vocabAnalysis: VocabAnalysis? = null,
    val vocabAnalyzing: Boolean = false,
    /** Model file name [vocabAnalysis] was produced with. */
    val vocabSource: String? = null,
    /** Analysis failure; kept off [errorMessage] so it does not fire the global snackbar. */
    val vocabError: String? = null,
)

enum class Screen { Chat, Models, Settings, Vocab, Test, About, Licenses }

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val engine: InferenceEngine = AiChat.getInferenceEngine(application)
    val modelManager = ModelManager(application)

    /** Serializes inference between the chat UI and the OpenAI API server. */
    private val inferenceMutex = Mutex()
    private var apiServer: OpenAiServer? = null

    private val _uiState = MutableStateFlow(AppUiState())
    val uiState: StateFlow<AppUiState> = _uiState.asStateFlow()

    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Default profile: seeded from the legacy global keys (pre per-model profiles). */
    private val defaultParams: InferenceParams

    init {
        val savedSource = prefs.getString(KEY_DOWNLOAD_SOURCE, null)
            ?.let { name -> DownloadSource.entries.firstOrNull { it.name == name } }
            ?: DownloadSource.Default
        defaultParams = InferenceParams(
            contextSize = prefs.getInt(KEY_CONTEXT_SIZE, InferenceParams().contextSize),
            maxTokens = prefs.getInt(KEY_MAX_TOKENS, InferenceParams().maxTokens),
            threadCount = prefs.getInt(KEY_THREAD_COUNT, InferenceParams().threadCount),
            systemPrompt = prefs.getString(KEY_SYSTEM_PROMPT, null) ?: InferenceParams().systemPrompt,
            kvCacheType = prefs.getString(KEY_KV_CACHE_TYPE, null)
                ?.let { name -> KvCacheType.entries.firstOrNull { it.name == name } }
                ?: InferenceParams().kvCacheType,
            sampling = SamplingParams(
                temperature = prefs.getFloat(KEY_TEMPERATURE, SamplingParams().temperature),
            ),
        )
        val apiAutoSwitchModel = prefs.getBoolean(KEY_API_AUTO_SWITCH_MODEL, true)
        _uiState.update {
            it.copy(
                downloadSource = savedSource,
                params = defaultParams,
                apiAutoSwitchModel = apiAutoSwitchModel,
            )
        }
        val apiServerWasEnabled = prefs.getBoolean(KEY_API_SERVER_ENABLED, false)
        viewModelScope.launch {
            engine.state.collect { state ->
                _uiState.update { it.copy(engineState = state) }
            }
        }
        viewModelScope.launch {
            // Deferred to a worker dispatcher: Main.immediate would run this
            // inline during construction, when apiHandler (declared further
            // down the class body) is still null.
            if (apiServerWasEnabled) {
                withContext(Dispatchers.IO) {
                    setApiServerEnabled(true)
                }
            }
            refreshLocalModels()
            startMetricsUpdater()
        }
    }

    fun selectTab(screen: Screen) {
        _uiState.update { it.copy(selectedTab = screen) }
    }

    fun selectModel(model: BonsaiModel) {
        // Without a loaded model, the settings page edits the selected model's
        // profile; switch to it so it shows the right values.
        val fileName = modelManager.modelFile(model).name
        _uiState.update {
            it.copy(
                selectedModel = model,
                profileFileName = fileName,
                params = if (it.modelPath == null) loadProfile(fileName) else it.params,
            )
        }
    }

    // ------------------------------------------------------------------
    // Vocabulary analysis
    // ------------------------------------------------------------------

    private var vocabJob: Job? = null

    /** Push a copied input holder (text + display filter) from the page. */
    fun setVocabInput(input: VocabInput) {
        _uiState.update { it.copy(vocabInput = input) }
    }

    /**
     * Count how the loaded model fragments [AppUiState.vocabInput], and whether the
     * tokens can be reassembled into the original text. Supersedes a run still in flight.
     */
    fun analyzeVocabulary() {
        val text = _uiState.value.vocabInput.text
        if (text.isEmpty()) return
        vocabJob?.cancel()
        _uiState.update { it.copy(vocabAnalyzing = true, vocabError = null) }
        vocabJob = viewModelScope.launch(Dispatchers.Default) {
            try {
                val ids = engine.tokenize(text)
                    ?: throw IllegalStateException("请先加载模型，再分析输入文本的分词频次")
                val counts = LinkedHashMap<Int, Int>()
                for (id in ids) counts[id] = (counts[id] ?: 0) + 1
                val freqs = counts.map { (id, count) ->
                    VocabFreq(id = id, count = count, text = engine.detokenize(intArrayOf(id)) ?: "?")
                }.sortedWith(compareByDescending<VocabFreq> { it.count }.thenBy { it.id })
                val restored = engine.detokenize(ids)
                _uiState.update {
                    it.copy(
                        vocabAnalyzing = false,
                        vocabSource = currentProfileFileName(),
                        vocabAnalysis = VocabAnalysis(
                            charCount = text.length,
                            tokenCount = ids.size,
                            uniqueCount = counts.size,
                            restoredOk = restored == text,
                            restored = restored,
                            freqs = freqs,
                        ),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Vocabulary analysis failed", e)
                _uiState.update {
                    it.copy(vocabAnalyzing = false, vocabAnalysis = null, vocabError = e.message ?: "分词分析失败")
                }
            }
        }
    }

    /**
     * Debug builds only: compare last-position logits for [file] between a CPU-only run
     * and a fully offloaded one. The check loads the model twice on its own, so the
     * resident model is released first and has to be loaded again afterwards.
     */
    fun runGpuCpuParity(file: File) {
        viewModelScope.launch {
            inferenceMutex.withLock {
                engine.cleanUp()
                _uiState.update {
                    it.copy(
                        modelPath = null,
                        isMultimodal = false,
                        infoMessage = "正在比对 CPU 与 GPU（需两次加载，请稍候）…",
                    )
                }
                val report = try {
                    engine.debugGpuCpuParity(file.absolutePath, "你好，请介绍一下你自己。")
                } catch (e: Exception) {
                    Log.e(TAG, "GPU/CPU parity check failed", e)
                    null
                }
                _uiState.update {
                    when {
                        report == null -> it.copy(errorMessage = "GPU/CPU 比对未能完成")
                        report.startsWith("parity_failed") -> it.copy(errorMessage = "GPU/CPU 比对失败：$report")
                        else -> it.copy(infoMessage = "GPU/CPU 比对 ${file.name}：$report")
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Per-model settings profiles, keyed by model file name
    // ------------------------------------------------------------------

    /** File name of the model the settings currently apply to, if any. */
    private fun currentProfileFileName(): String? =
        _uiState.value.modelPath?.let { File(it).name }
            ?: _uiState.value.profileFileName
            ?: _uiState.value.selectedModel?.let { modelManager.modelFile(it).name }

    /** Choose which model's profile the settings page edits (no load required). */
    fun selectProfile(fileName: String) {
        _uiState.update {
            it.copy(
                profileFileName = fileName,
                params = if (it.modelPath == null) loadProfile(fileName) else it.params,
            )
        }
    }

    /**
     * Params for [fileName]. A saved profile always wins. Without one, the app
     * defaults are merged with the model's own `general.sampling.*` presets, so a
     * model's very first load uses the sampling its author recommended.
     */
    private fun loadProfile(
        fileName: String,
        presets: GgufMetadata.SamplingInfo? = null,
    ): InferenceParams {
        val json = prefs.getString(KEY_PROFILE_PREFIX + fileName, null) ?: return defaultParams.let { base ->
            if (presets == null) base else base.copy(
                sampling = base.sampling.copy(
                    temperature = presets.temp ?: base.sampling.temperature,
                    topK = presets.topK ?: base.sampling.topK,
                    topP = presets.topP ?: base.sampling.topP,
                )
            )
        }
        return try {
            val o = JSONObject(json)
            InferenceParams(
                contextSize = o.optInt("contextSize", defaultParams.contextSize),
                maxTokens = o.optInt("maxTokens", defaultParams.maxTokens),
                threadCount = o.optInt("threadCount", defaultParams.threadCount),
                systemPrompt = o.optString("systemPrompt", defaultParams.systemPrompt),
                kvCacheType = o.optString("kvCacheType", "")
                    .let { name -> KvCacheType.entries.firstOrNull { it.name == name } }
                    ?: defaultParams.kvCacheType,
                sampling = SamplingParams(
                    temperature = o.optDouble("temperature", defaultParams.sampling.temperature.toDouble()).toFloat(),
                    topK = o.optInt("topK", defaultParams.sampling.topK),
                    topP = o.optDouble("topP", defaultParams.sampling.topP.toDouble()).toFloat(),
                    repeatPenalty = o.optDouble("repeatPenalty", defaultParams.sampling.repeatPenalty.toDouble()).toFloat(),
                    frequencyPenalty = o.optDouble("frequencyPenalty", defaultParams.sampling.frequencyPenalty.toDouble()).toFloat(),
                    presencePenalty = o.optDouble("presencePenalty", defaultParams.sampling.presencePenalty.toDouble()).toFloat(),
                    seed = o.optInt("seed", defaultParams.sampling.seed),
                ),
            )
        } catch (e: Exception) {
            Log.w(TAG, "Corrupt profile for $fileName, using defaults", e)
            defaultParams
        }
    }

    private fun saveProfile(fileName: String, params: InferenceParams) {
        val o = JSONObject()
            .put("contextSize", params.contextSize)
            .put("maxTokens", params.maxTokens)
            .put("threadCount", params.threadCount)
            .put("systemPrompt", params.systemPrompt)
            .put("kvCacheType", params.kvCacheType.name)
            .put("temperature", params.sampling.temperature.toDouble())
            .put("topK", params.sampling.topK)
            .put("topP", params.sampling.topP.toDouble())
            .put("repeatPenalty", params.sampling.repeatPenalty.toDouble())
            .put("frequencyPenalty", params.sampling.frequencyPenalty.toDouble())
            .put("presencePenalty", params.sampling.presencePenalty.toDouble())
            .put("seed", params.sampling.seed)
        prefs.edit().putString(KEY_PROFILE_PREFIX + fileName, o.toString()).apply()
    }

    fun updateParams(params: InferenceParams) {
        _uiState.update { it.copy(params = params) }
        currentProfileFileName()?.let { saveProfile(it, params) }
    }

    fun loadSelectedModel() {
        val model = _uiState.value.selectedModel
        val file = modelManager.modelFile(model)
        loadModel(file)
    }

    fun loadModel(file: File) {
        viewModelScope.launch {
            if (!file.exists()) {
                _uiState.update { it.copy(errorMessage = "未找到模型文件：${file.name}") }
                return@launch
            }
            performLoad(file)
        }
    }

    /**
     * Import one or more files picked from a document picker. GGUF model files
     * are stored under their display name; mmproj projectors are stored as
     * "<model base>.mmproj" so they pair with the model by file name. When a
     * mmproj is picked alongside exactly one model, it is paired with it.
     */
    private data class ImportedEntry(val uri: Uri, val name: String, val kind: GgufKind)

    fun importFiles(uris: List<Uri>) {
        viewModelScope.launch {
            try {
                _uiState.update { it.copy(errorMessage = null, infoMessage = "正在导入模型…") }
                val entries = uris.map { uri ->
                    val name = displayName(uri)
                    ImportedEntry(uri, name, modelManager.classifyFile(uri, name))
                }
                val models = entries.filter { it.kind == GgufKind.MODEL }
                val mmprojs = entries.filter { it.kind == GgufKind.MMPROJ }
                val invalid = entries.filter { it.kind == GgufKind.INVALID }

                // Models keep their display name (deduped if already imported).
                val modelFiles = models.map { entry ->
                    val name = uniqueName(modelManager.modelsDir, entry.name)
                    modelManager.copyUriToFile(entry.uri, name)
                }
                // A mmproj picked together with exactly one model is paired with it;
                // otherwise it keeps its own base name for manual pairing later.
                val pairBaseName = if (modelFiles.size == 1) modelFiles.first().nameWithoutExtension else null
                val importedMmproj = mmprojs.map { entry ->
                    val baseName = pairBaseName ?: entry.name.substringBeforeLast('.')
                    val dest = uniqueName(modelManager.modelsDir, "$baseName${ModelManager.MMPROJ_EXT}")
                    modelManager.copyUriToFile(entry.uri, dest)
                }

                val summary = buildString {
                    if (modelFiles.isNotEmpty()) append("已导入 ${modelFiles.size} 个模型")
                    if (importedMmproj.isNotEmpty()) {
                        if (isNotEmpty()) append("，")
                        append("已导入 ${importedMmproj.size} 个多模态投影")
                    }
                    if (invalid.isNotEmpty()) {
                        if (isNotEmpty()) append("；")
                        append("跳过不支持的文件：${invalid.joinToString() { it.name }}")
                    }
                }
                refreshLocalModels()
                _uiState.update { it.copy(infoMessage = summary.ifEmpty { "未导入任何文件" }) }
                if (modelFiles.size == 1) {
                    performLoad(modelFiles.first())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to import files", e)
                _uiState.update { it.copy(errorMessage = e.message ?: "导入失败") }
            }
        }
    }

    /** Pair a picked mmproj file with a local GGUF model file. */
    fun associateMmproj(modelFile: File, uri: Uri) {
        viewModelScope.launch {
            try {
                val dest = modelManager.mmprojFileFor(modelFile)
                modelManager.copyUriToFile(uri, dest)
                refreshLocalModels()
                _uiState.update { it.copy(infoMessage = "已关联 mmproj：${dest.name}") }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to associate mmproj", e)
                _uiState.update { it.copy(errorMessage = e.message ?: "关联 mmproj 失败") }
            }
        }
    }

    /** Remove the mmproj paired with a local model file. */
    fun removeMmproj(modelFile: File) {
        viewModelScope.launch {
            val mmproj = modelManager.mmprojFileFor(modelFile)
            mmproj.delete()
            refreshLocalModels()
            _uiState.update { it.copy(infoMessage = "已移除 mmproj：${mmproj.name}") }
        }
    }

    /** Message naming the projector the loaded model is missing, and where to get it. */
    private fun missingMmprojMessage(): String {
        val expected = _uiState.value.modelPath?.let { modelManager.mmprojFileFor(File(it)).name }
        return if (expected != null) {
            "当前模型缺少多模态投影（mmproj）$expected，无法发送图片；请在模型页下载或导入它，然后重新加载模型"
        } else {
            "当前模型未加载多模态投影（mmproj），无法发送图片"
        }
    }

    /** Shown when the attach button is tapped while no projector is loaded. */
    fun warnMissingMmproj() {
        _uiState.update { it.copy(errorMessage = missingMmprojMessage()) }
    }

    /** Attach an image to the next outgoing message (copied to app cache first). */
    fun attachImage(uri: Uri) {
        viewModelScope.launch {
            try {
                if (!_uiState.value.isMultimodal) {
                    _uiState.update { it.copy(errorMessage = missingMmprojMessage()) }
                    return@launch
                }
                val dir = File(getApplication<Application>().cacheDir, "images").apply { mkdirs() }
                val ext = uri.lastPathSegment?.substringAfterLast('.', "")?.takeIf { it.isNotEmpty() }?.lowercase() ?: "jpg"
                val dest = File(dir, "img_${System.currentTimeMillis()}.$ext")
                modelManager.copyUriToFile(uri, dest)
                _uiState.update { it.copy(pendingImagePath = dest.absolutePath) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to attach image", e)
                _uiState.update { it.copy(errorMessage = e.message ?: "图片加载失败") }
            }
        }
    }

    fun clearPendingImage() {
        _uiState.update { it.copy(pendingImagePath = null) }
    }

    fun reloadModel() {
        viewModelScope.launch {
            try {
                val path = _uiState.value.modelPath
                    ?: throw IllegalStateException("No model loaded")
                _uiState.update { it.copy(infoMessage = "正在重新加载模型…") }
                engine.cleanUp()
                performLoad(File(path))
                _uiState.update { it.copy(infoMessage = "模型已重新加载") }
            } catch (e: Exception) {
                Log.e(TAG, "Reload failed", e)
                _uiState.update { it.copy(errorMessage = e.message ?: "重新加载失败") }
            }
        }
    }

    fun unloadModel() {
        try {
            engine.cleanUp()
            _uiState.update { it.copy(modelPath = null, messages = emptyList(), isMultimodal = false) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unload model", e)
        }
    }

    fun onInputChange(text: String) {
        _uiState.update { it.copy(inputText = text) }
    }

    fun sendMessage() {
        val text = _uiState.value.inputText.trim()
        val image = _uiState.value.pendingImagePath
        if (text.isEmpty() && image == null) return
        if (_uiState.value.isGenerating) return

        _uiState.update {
            it.copy(
                inputText = "",
                pendingImagePath = null,
                messages = it.messages + ChatMessage(ChatMessage.Role.User, text, imagePath = image),
                isGenerating = true,
                metrics = PerformanceMetrics(),
            )
        }

        viewModelScope.launch {
            inferenceMutex.withLock {
                try {
                    val assistantMessage = ChatMessage(ChatMessage.Role.Assistant, "", isGenerating = true)
                    _uiState.update { it.copy(messages = it.messages + assistantMessage) }

                    val startTime = System.currentTimeMillis()
                    val buffer = StringBuilder()
                    var tokenCount = 0
                    var promptTokenCount = 0

                    engine.sendUserPrompt(
                        text,
                        predictLength = _uiState.value.params.maxTokens,
                        sampling = _uiState.value.params.sampling,
                        imagePaths = if (image != null) listOf(image) else emptyList(),
                    ).collect { token ->
                        buffer.append(token)
                        tokenCount++
                        if (promptTokenCount == 0) {
                            promptTokenCount = estimatePromptTokens(text)
                        }
                        val elapsed = System.currentTimeMillis() - startTime
                        val tps = if (elapsed > 0) tokenCount * 1000.0 / elapsed else 0.0
                        _uiState.update { state ->
                            state.copy(
                                metrics = state.metrics.copy(
                                    tokensGenerated = tokenCount,
                                    promptTokens = promptTokenCount,
                                    tokensPerSecond = tps,
                                    totalDurationMs = elapsed,
                                )
                            )
                        }
                        updateLastAssistantMessage(buffer.toString(), isGenerating = true)
                    }

                    val elapsed = System.currentTimeMillis() - startTime
                    updateLastAssistantMessage(buffer.toString(), isGenerating = false)
                    _uiState.update { state ->
                        state.copy(
                            isGenerating = false,
                            // Surface a length-limit stop, which otherwise looks
                            // like the model gave up mid-answer.
                            infoMessage = if (engine.getLastStopReason() == 1)
                                "已达最大输出长度（${state.params.maxTokens} tokens），可在设置中调大"
                            else state.infoMessage,
                            metrics = state.metrics.copy(
                                tokensGenerated = tokenCount,
                                totalDurationMs = elapsed,
                            )
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Generation failed", e)
                    _uiState.update { it.copy(errorMessage = e.message ?: "生成失败", isGenerating = false) }
                }
            }
        }
    }

    fun stopGeneration() {
        try {
            engine.stopGeneration()
            _uiState.update { it.copy(isGenerating = false) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop generation", e)
        }
    }

    fun clearMessages() {
        _uiState.update { it.copy(messages = emptyList()) }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun clearInfo() {
        _uiState.update { it.copy(infoMessage = null) }
    }

    fun downloadModel(model: BonsaiModel) {
        viewModelScope.launch {
            try {
                modelManager.downloadModel(model, _uiState.value.downloadSource).collect { status ->
                    _uiState.update { state ->
                        state.copy(downloadStatuses = state.downloadStatuses + (model.id to status))
                    }
                    when (status.type) {
                        // Progress is shown on the model card itself; pushing every
                        // tick into infoMessage would spam the snackbar.
                        DownloadStatus.Type.Downloaded -> {
                            _uiState.update {
                                it.copy(infoMessage = "${model.id} ${if (modelManager.hasMmproj(model)) "（含多模态投影）" else ""} 已下载")
                            }
                            refreshLocalModels()
                        }
                        DownloadStatus.Type.Failed -> {
                            _uiState.update { it.copy(errorMessage = status.reason) }
                        }
                        else -> {}
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)
                _uiState.update { it.copy(errorMessage = e.message ?: "下载失败") }
            }
        }
    }

    fun setDownloadSource(source: DownloadSource) {
        prefs.edit().putString(KEY_DOWNLOAD_SOURCE, source.name).apply()
        _uiState.update { it.copy(downloadSource = source) }
    }

    fun setApiAutoSwitchModel(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_API_AUTO_SWITCH_MODEL, enabled).apply()
        _uiState.update { it.copy(apiAutoSwitchModel = enabled) }
    }

    fun deleteModel(model: BonsaiModel) {
        viewModelScope.launch {
            modelManager.deleteModel(model)
            refreshLocalModels()
            _uiState.update { it.copy(infoMessage = "${model.id} 已删除") }
        }
    }

    fun deleteLocalFile(file: File) {
        viewModelScope.launch {
            file.delete()
            refreshLocalModels()
            _uiState.update { it.copy(infoMessage = "${file.name} 已删除") }
        }
    }

    fun refreshLocalModels() {
        viewModelScope.launch {
            // Pick up downloads that completed while the app was not running.
            modelManager.sweepStagingDownloads()
            val locals = modelManager.scanLocalModels()
            val mmprojs = modelManager.scanLocalMmproj()
            val statuses = mutableMapOf<String, DownloadStatus>()
            val missingMmproj = mutableSetOf<String>()
            AVAILABLE_MODELS.forEach { model ->
                val file = modelManager.modelFile(model)
                val exists = file.exists()
                android.util.Log.i(TAG, "Model ${model.id}: path=${file.absolutePath}, exists=$exists")
                if (exists) {
                    if (model.mmprojFilename != null && !modelManager.hasMmproj(model)) {
                        missingMmproj += model.id
                    } else {
                        statuses[model.id] = DownloadStatus.Downloaded(file)
                    }
                }
            }
            // Read each model's max context length from its GGUF header (metadata only, fast).
            val contextLengths = mutableMapOf<String, Int>()
            locals.forEach { file ->
                modelManager.readMetadata(file)?.dimensions?.contextLength
                    ?.let { contextLengths[file.absolutePath] = it }
            }
            // Keep in-progress / failed downloads; replace completed ones with fresh scan results.
            val pruned = _uiState.value.downloadStatuses.filterValues {
                it.type == DownloadStatus.Type.Downloading || it.type == DownloadStatus.Type.Failed
            }
            _uiState.update {
                it.copy(
                    localModels = locals,
                    mmprojFiles = mmprojs,
                    downloadStatuses = pruned + statuses,
                    modelContextLengths = contextLengths,
                    mmprojMissingIds = missingMmproj,
                )
            }
            android.util.Log.i(TAG, "refreshLocalModels done, models=${locals.map { it.name }}, mmprojs=${mmprojs.map { it.name }}, missingMmproj=$missingMmproj")
        }
    }

    private fun autoLoadSingleModel() {
        viewModelScope.launch {
            val locals = modelManager.scanLocalModels()
            if (locals.size == 1) {
                val file = locals.first()
                _uiState.update { it.copy(infoMessage = "正在自动加载 ${file.name}…") }
                performLoad(file)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        apiServer?.stop()
        apiServer = null
        engine.destroy()
    }

    // ------------------------------------------------------------------
    // OpenAI-compatible API server
    // ------------------------------------------------------------------

    fun setApiServerEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_API_SERVER_ENABLED, enabled).apply()
        if (enabled) {
            if (apiServer != null) return
            try {
                val server = OpenAiServer(API_PORT, apiHandler)
                server.start()
                apiServer = server
                _uiState.update {
                    it.copy(
                        apiServerRunning = true,
                        apiServerError = null,
                        infoMessage = "API 服务已启动，端口 $API_PORT"
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start API server", e)
                _uiState.update {
                    it.copy(apiServerRunning = false, apiServerError = e.message ?: "API 服务启动失败")
                }
            }
        } else {
            apiServer?.stop()
            apiServer = null
            _uiState.update { it.copy(apiServerRunning = false, apiServerError = null) }
        }
    }

    private val apiHandler = object : OpenAiServer.Handler {
        override fun listModelIds(): List<String> =
            _uiState.value.localModels.map { it.name }

        override fun loadedModelId(): String? =
            _uiState.value.modelPath?.let { File(it).name }

        override suspend fun chatCompletion(
            model: String?,
            messages: List<Pair<String, String>>,
            maxTokens: Int,
            temperature: Double?,
            topP: Double?,
            seed: Int?,
            onDelta: suspend (String) -> Unit,
        ): OpenAiServer.CompletionResult {
            // Lock first: the model switch below also touches the engine, so it
            // must be serialized against UI/chat generation just like inference.
            if (!inferenceMutex.tryLock()) throw OpenAiServer.ApiBusyException()
            try {
                if (model != null && _uiState.value.apiAutoSwitchModel) {
                    ensureApiModelLoaded(model)
                }
                if (_uiState.value.modelPath == null) throw OpenAiServer.NoModelLoadedException()
                val prompt = buildApiPrompt(messages)
                val promptTokens = estimatePromptTokens(prompt)
                // Client omitted max_tokens: use the app's configured generation
                // length rather than a hidden server-side cap.
                val effectiveMaxTokens = if (maxTokens > 0) maxTokens else _uiState.value.params.maxTokens
                // Request-level sampling overrides (OpenAI-compatible fields).
                val profileSampling = _uiState.value.params.sampling
                val sampling = profileSampling.copy(
                    temperature = temperature?.toFloat() ?: profileSampling.temperature,
                    topP = topP?.toFloat() ?: profileSampling.topP,
                    seed = seed ?: profileSampling.seed,
                )
                val buffer = StringBuilder()
                var tokenCount = 0
                var clientGone = false
                val startTime = System.currentTimeMillis()

                _uiState.update { it.copy(isGenerating = true, metrics = PerformanceMetrics()) }
                try {
                    engine.sendUserPrompt(prompt, predictLength = effectiveMaxTokens, sampling = sampling).collect { token ->
                        buffer.append(token)
                        tokenCount++
                        val elapsed = System.currentTimeMillis() - startTime
                        _uiState.update { state ->
                            state.copy(
                                metrics = state.metrics.copy(
                                    tokensGenerated = tokenCount,
                                    promptTokens = promptTokens,
                                    tokensPerSecond = if (elapsed > 0) tokenCount * 1000.0 / elapsed else 0.0,
                                    totalDurationMs = elapsed,
                                )
                            )
                        }
                        if (!clientGone) {
                            try {
                                onDelta(token)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                // API client disconnected: stop generating instead of
                                // propagating (which would push the engine into Error state).
                                Log.w(TAG, "API client disconnected: ${e.message}")
                                clientGone = true
                                engine.stopGeneration()
                            }
                        }
                    }
                } finally {
                    _uiState.update { it.copy(isGenerating = false) }
                }
                if (clientGone) throw IOException("API client disconnected")
                return OpenAiServer.CompletionResult(
                    text = buffer.toString(),
                    promptTokens = promptTokens,
                    completionTokens = tokenCount,
                    finishReason = if (engine.getLastStopReason() == 1) "length" else "stop",
                )
            } finally {
                inferenceMutex.unlock()
            }
        }
    }

    /**
     * Load the local GGUF whose file name matches the API-requested model id,
     * unless it is already the loaded model. Matching is case-insensitive and
     * also accepts the file name without its ".gguf" extension.
     *
     * Must be called with [inferenceMutex] held.
     */
    private suspend fun ensureApiModelLoaded(requested: String) {
        fun File.matches(id: String) =
            name.equals(id, ignoreCase = true) || nameWithoutExtension.equals(id, ignoreCase = true)

        val current = _uiState.value.modelPath?.let { File(it) }
        if (current != null && current.matches(requested)) return
        val target = _uiState.value.localModels.firstOrNull { it.matches(requested) }
            ?: throw OpenAiServer.ModelNotFoundException(requested)
        performLoad(target)
        // performLoad swallows load errors into errorMessage; verify the outcome.
        if (_uiState.value.modelPath != target.absolutePath) {
            throw IOException("模型加载失败：${_uiState.value.errorMessage ?: target.name}")
        }
    }

    private fun buildApiPrompt(messages: List<Pair<String, String>>): String {
        if (messages.size == 1 && messages[0].first == "user") return messages[0].second
        return messages.joinToString("\n") { (role, content) ->
            when (role) {
                "system" -> "System: $content"
                "assistant" -> "Assistant: $content"
                else -> "User: $content"
            }
        }
    }

    private suspend fun performLoad(file: File) {
        try {
            _uiState.update { it.copy(errorMessage = null, infoMessage = "正在加载 ${file.name}…") }
            // Switching models: unload the currently loaded one first.
            when (engine.state.value) {
                is InferenceEngine.State.ModelReady, is InferenceEngine.State.Error -> engine.cleanUp()
                else -> {}
            }
            // One header parse feeds the context clamp, the quant guard and the presets.
            val meta = modelManager.readMetadata(file)
            // Clamp the context size to the model's own maximum (from GGUF metadata).
            val maxCtx = meta?.dimensions?.contextLength
            val hasSavedProfile = prefs.contains(KEY_PROFILE_PREFIX + file.name)
            var params = loadProfile(file.name, meta?.sampling)
            if (maxCtx != null) {
                _uiState.update {
                    it.copy(modelContextLengths = it.modelContextLengths + (file.absolutePath to maxCtx))
                }
                if (params.contextSize > maxCtx) {
                    params = params.copy(contextSize = maxCtx)
                    saveProfile(file.name, params)
                    _uiState.update {
                        it.copy(infoMessage = "上下文长度已调整为模型上限 $maxCtx")
                    }
                }
            }
            if (!hasSavedProfile && meta?.sampling != null) {
                // Persist the author's presets so the settings page shows what was actually used.
                saveProfile(file.name, params)
            }
            // Settings page edits the loaded model's profile from now on.
            _uiState.update { it.copy(params = params) }
            if (meta?.architecture?.fileType == FileType.MOSTLY_PTQ1_0.code) {
                // no GPU kernels yet: the whole model would stay on the CPU, which is far
                // too slow to be a usable release experience, so release builds refuse it.
                if (!BuildConfig.DEBUG) {
                    throw IllegalStateException(
                        "暂不支持 PTQ1_0（1.75 bpw）：GPU 内核尚未实现，请下载 PQ2_0 版本")
                }
                Log.i(TAG, "PTQ1_0 loaded in a debug build: CPU-only path, for measurement")
            }
            val pairedMmproj = modelManager.mmprojFileFor(file).takeIf { it.exists() }
            if (pairedMmproj != null) {
                android.util.Log.i(TAG, "Loading multimodally with mmproj ${pairedMmproj.absolutePath}")
            }
            engine.loadModel(file.absolutePath, params, pairedMmproj?.absolutePath)
            engine.setSystemPrompt(params.systemPrompt)
            android.util.Log.i(
                TAG,
                "performLoad done: mmproj=${pairedMmproj?.absolutePath}, engine.isMultimodal=${engine.isMultimodal}",
            )
            _uiState.update {
                it.copy(
                    modelPath = file.absolutePath,
                    infoMessage = if (engine.isMultimodal) "模型就绪（多模态）" else "模型就绪",
                    isMultimodal = engine.isMultimodal,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model", e)
            _uiState.update { it.copy(errorMessage = e.message ?: "加载模型失败") }
        }
    }

    private fun updateLastAssistantMessage(text: String, isGenerating: Boolean) {
        _uiState.update { state ->
            val messages = state.messages.toMutableList()
            if (messages.isNotEmpty()) {
                val last = messages.last()
                if (last.role == ChatMessage.Role.Assistant) {
                    messages[messages.size - 1] = last.copy(text = text, isGenerating = isGenerating)
                }
            }
            state.copy(messages = messages)
        }
    }

    private fun startMetricsUpdater() {
        viewModelScope.launch {
            while (isActive) {
                val memInfo = Debug.MemoryInfo()
                Debug.getMemoryInfo(memInfo)
                val nativeHeap = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
                _uiState.update { state ->
                    state.copy(
                        metrics = state.metrics.copy(
                            nativeHeapMb = nativeHeap / 1024 / 1024,
                            totalPssMb = memInfo.totalPss / 1024L,
                        )
                    )
                }
                delay(1000)
            }
        }
    }

    private fun estimatePromptTokens(text: String): Int {
        // Very rough estimate: ~4 chars per token for CJK/English mix.
        return (text.length / 3.5).toInt().coerceAtLeast(1)
    }

    companion object {
        private const val TAG = "ChatViewModel"
        const val API_PORT = 8000
        private const val PREFS_NAME = "bonsai_settings"
        private const val KEY_DOWNLOAD_SOURCE = "download_source"
        private const val KEY_CONTEXT_SIZE = "context_size"
        private const val KEY_MAX_TOKENS = "max_tokens"
        private const val KEY_TEMPERATURE = "temperature"
        private const val KEY_THREAD_COUNT = "thread_count"
        private const val KEY_SYSTEM_PROMPT = "system_prompt"
        private const val KEY_KV_CACHE_TYPE = "kv_cache_type"
        private const val KEY_API_SERVER_ENABLED = "api_server_enabled"
        private const val KEY_API_AUTO_SWITCH_MODEL = "api_auto_switch_model"

        /** Per-model profile JSON: KEY_PROFILE_PREFIX + model file name. */
        private const val KEY_PROFILE_PREFIX = "profile_"
    }
}

/** Display name of a content URI, falling back to the last path segment. */
private fun ChatViewModel.displayName(uri: Uri): String {
    val name = runCatching {
        getApplication<Application>().contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    }.getOrNull()
    return name?.takeIf { it.isNotBlank() }
        ?: uri.lastPathSegment?.substringAfterLast('/') ?: "imported_${System.currentTimeMillis()}.gguf"
}

/** A non-conflicting file name inside [dir]: inserts a timestamp before the extension if needed. */
private fun uniqueName(dir: File, name: String): File {
    val dest = File(dir, name)
    if (!dest.exists()) return dest
    val base = name.substringBeforeLast('.', name)
    val ext = name.substringAfterLast('.', "")
    val suffixed = if (ext.isEmpty()) "${base}-${System.currentTimeMillis()}" else "$base-${System.currentTimeMillis()}.$ext"
    return File(dir, suffixed)
}
