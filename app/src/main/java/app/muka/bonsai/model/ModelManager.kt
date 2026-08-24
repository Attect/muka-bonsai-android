package app.muka.bonsai.model

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.core.content.getSystemService
import app.muka.bonsai.llama.gguf.GgufMetadataReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class DownloadStatus private constructor(val type: Type, val file: File? = null, val progress: Float = 0f, val reason: String = "") {
    enum class Type { NotDownloaded, Downloading, Downloaded, Failed }

    companion object {
        val NotDownloaded = DownloadStatus(Type.NotDownloaded)
        fun Downloading(progress: Float) = DownloadStatus(Type.Downloading, progress = progress)
        fun Downloaded(file: File) = DownloadStatus(Type.Downloaded, file = file)
        fun Failed(reason: String) = DownloadStatus(Type.Failed, reason = reason)
    }

    override fun toString(): String = when (type) {
        Type.NotDownloaded -> "NotDownloaded"
        Type.Downloading -> "Downloading(progress=$progress)"
        Type.Downloaded -> "Downloaded(file=${file?.absolutePath})"
        Type.Failed -> "Failed(reason=$reason)"
    }
}

/** Classification of an imported file. */
enum class GgufKind { MODEL, MMPROJ, INVALID }

class ModelManager(private val context: Context) {

    private val downloadManager = context.getSystemService<DownloadManager>()
        ?: throw IllegalStateException("DownloadManager not available")

    val modelsDir: File
        get() = File(context.filesDir, "models").also { it.mkdirs() }

    fun modelFile(model: BonsaiModel): File = File(modelsDir, model.filename)

    fun isDownloaded(model: BonsaiModel): Boolean = modelFile(model).exists()

    // ------------------------------------------------------------------
    // mmproj pairing
    // ------------------------------------------------------------------

    /** mmproj file name for a catalog model: "<model base>.mmproj". */
    fun mmprojFileName(model: BonsaiModel): String =
        model.filename.removeSuffix(".gguf") + MMPROJ_EXT

    /** mmproj file name paired with a local GGUF file: "<base>.mmproj". */
    fun mmprojFileNameFor(modelFile: File): String =
        modelFile.nameWithoutExtension + MMPROJ_EXT

    fun mmprojFile(model: BonsaiModel): File = File(modelsDir, mmprojFileName(model))

    fun mmprojFileFor(modelFile: File): File = File(modelsDir, mmprojFileNameFor(modelFile))

    fun hasMmproj(model: BonsaiModel): Boolean =
        model.mmprojFilename != null && mmprojFile(model).exists()

    fun hasMmprojFor(modelFile: File): Boolean = mmprojFileFor(modelFile).exists()

    /**
     * Download [model] (and its mmproj when configured) through the
     * DownloadManager, emitting status updates. Already-downloaded files are
     * skipped, so this can also be used to fetch a missing mmproj later.
     */
    fun downloadModel(model: BonsaiModel, source: DownloadSource): Flow<DownloadStatus> = flow {
        val gguf = modelFile(model)
        if (!gguf.exists()) {
            val status = awaitDownloadFile(
                url = model.downloadUrl(source),
                fileName = model.filename,
                title = "${model.family} ${model.sizeParam}",
            )
            if (status.type != DownloadStatus.Type.Downloaded) return@flow
        }
        val mmprojFileName = model.mmprojFilename?.let { mmprojFileName(model) }
        if (mmprojFileName != null && !File(modelsDir, mmprojFileName).exists()) {
            awaitDownloadFile(
                url = model.mmprojDownloadUrl(source) ?: return@flow,
                fileName = mmprojFileName,
                title = "${model.id} 多模态投影",
            )
        }
    }.flowOn(Dispatchers.IO)

    /**
     * DownloadManager can only write to external storage, so downloads land in the
     * app-specific external dir first and are moved to [modelsDir] on completion.
     */
    private fun stagingFile(fileName: String): File {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: throw IllegalStateException("外部存储不可用，无法下载")
        return File(dir, fileName)
    }

    /** Move a completed staging download into [modelsDir]. */
    private fun moveStagingToModels(fileName: String): File {
        val staging = stagingFile(fileName)
        val dest = File(modelsDir, fileName)
        if (dest.exists()) dest.delete()
        if (!staging.renameTo(dest)) {
            staging.copyTo(dest, overwrite = true)
            staging.delete()
        }
        return dest
    }

    private suspend fun enqueueDownload(url: String, fileName: String, title: String): Long =
        withContext(Dispatchers.IO) {
            // Remove any stale partial download from a previous attempt.
            stagingFile(fileName).delete()

            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle(title)
                .setDescription(if (fileName.endsWith(MMPROJ_EXT)) "Downloading multimodal projector…" else "Downloading GGUF model…")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
                .setAllowedOverMetered(false)
                .setAllowedOverRoaming(false)

            downloadManager.enqueue(request)
        }

    /** Enqueue the download and emit status until a terminal state, returning it. */
    private suspend fun FlowCollector<DownloadStatus>.awaitDownloadFile(
        url: String,
        fileName: String,
        title: String,
    ): DownloadStatus {
        val id = enqueueDownload(url, fileName, title)
        var last: DownloadStatus = DownloadStatus.NotDownloaded
        downloadStatus(fileName, id).collect { status ->
            last = status
            emit(status)
        }
        return last
    }

    /**
     * Returns a [Flow] that emits the current download status of a file.
     *
     * Completion is driven by polling the DownloadManager status instead of the
     * ACTION_DOWNLOAD_COMPLETE broadcast, which is not always delivered to
     * runtime-registered receivers. The flow completes when the download reaches
     * a terminal state.
     */
    fun downloadStatus(fileName: String, downloadId: Long? = null): Flow<DownloadStatus> =
        callbackFlow {
            val file = File(modelsDir, fileName)
            if (file.exists()) {
                trySend(DownloadStatus.Downloaded(file))
                close()
                return@callbackFlow
            }

            if (downloadId == null || downloadId == -1L) {
                trySend(DownloadStatus.NotDownloaded)
                close()
                return@callbackFlow
            }

            val progressJob = launch {
                while (true) {
                    when (queryStatus(downloadId)) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            // The destination file can lag the status flip by a moment.
                            val staging = stagingFile(fileName)
                            var waited = 0
                            while (!staging.exists() && waited < 10_000) {
                                delay(500)
                                waited += 500
                            }
                            if (staging.exists()) {
                                try {
                                    val dest = moveStagingToModels(fileName)
                                    trySend(DownloadStatus.Downloaded(dest))
                                } catch (e: Exception) {
                                    trySend(DownloadStatus.Failed("移动到模型目录失败: ${e.message}"))
                                }
                            } else {
                                trySend(DownloadStatus.Failed("下载完成但文件缺失"))
                            }
                            close()
                            break
                        }
                        DownloadManager.STATUS_FAILED -> {
                            stagingFile(fileName).delete()
                            trySend(DownloadStatus.Failed("Download failed: ${queryReason(downloadId)}"))
                            close()
                            break
                        }
                        else -> {
                            val (bytes, total) = queryProgress(downloadId)
                            val progress = if (total > 0) bytes.toFloat() / total else 0f
                            trySend(DownloadStatus.Downloading(progress.coerceIn(0f, 1f)))
                        }
                    }
                    delay(500)
                }
            }

            awaitClose {
                progressJob.cancel()
            }
        }.flowOn(Dispatchers.IO)

    /**
     * Delete a downloaded model file together with its paired mmproj.
     */
    suspend fun deleteModel(model: BonsaiModel) = withContext(Dispatchers.IO) {
        modelFile(model).delete()
        mmprojFile(model).delete()
    }

    /**
     * Scan [modelsDir] and return any local GGUF model files found there.
     * Projector (mmproj) files are excluded by their metadata architecture.
     */
    suspend fun scanLocalModels(): List<File> = withContext(Dispatchers.IO) {
        val gguFs = modelsDir.listFiles { _, name -> name.lowercase().endsWith(GGUF_EXT) }
        val files = gguFs?.filter { file ->
            file.inputStream().buffered().use { input ->
                runCatching {
                    GgufMetadataReader.create().readStructuredMetadata(input)
                        .architecture?.architecture != CLIP_ARCH
                }.getOrDefault(true)
            }
        }
        android.util.Log.i("ModelManager", "Scanning ${modelsDir.absolutePath}: found ${files?.size ?: 0} models")
        files?.toList() ?: emptyList()
    }

    /** Scan [modelsDir] and return any mmproj projector files found there. */
    suspend fun scanLocalMmproj(): List<File> = withContext(Dispatchers.IO) {
        modelsDir.listFiles { _, name -> name.lowercase().endsWith(MMPROJ_EXT) }?.toList() ?: emptyList()
    }

    /**
     * Move any completed downloads left in the staging dir into [modelsDir].
     *
     * DownloadManager survives process death but the completion receiver does not,
     * so a download that finished while the app was gone would otherwise be stranded.
     */
    suspend fun sweepStagingDownloads() = withContext(Dispatchers.IO) {
        val stagingDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return@withContext
        stagingDir.listFiles { _, name ->
            name.lowercase().endsWith(GGUF_EXT) || name.lowercase().endsWith(MMPROJ_EXT)
        }?.forEach { staging ->
            val dest = File(modelsDir, staging.name)
            try {
                if (dest.exists()) dest.delete()
                if (!staging.renameTo(dest)) {
                    staging.copyTo(dest, overwrite = true)
                    staging.delete()
                }
                android.util.Log.i("ModelManager", "Swept staged download ${staging.name} into models dir")
            } catch (e: Exception) {
                android.util.Log.w("ModelManager", "Failed to sweep ${staging.name}", e)
            }
        }
    }

    // ------------------------------------------------------------------
    // Local import
    // ------------------------------------------------------------------

    /**
     * Classify an imported file by name and GGUF metadata: model, mmproj
     * projector (architecture "clip"), or an unsupported file type.
     */
    suspend fun classifyFile(uri: Uri, displayName: String): GgufKind = withContext(Dispatchers.IO) {
        val lower = displayName.lowercase()
        when {
            lower.endsWith(MMPROJ_EXT) -> GgufKind.MMPROJ
            !lower.endsWith(GGUF_EXT) -> GgufKind.INVALID
            else -> {
                val arch = runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        GgufMetadataReader.create().readStructuredMetadata(input)
                            .architecture?.architecture
                    }
                }.getOrNull()
                if (arch == CLIP_ARCH) GgufKind.MMPROJ else GgufKind.MODEL
            }
        }
    }

    /** Copy a content URI into [dest], overwriting it. Returns [dest]. */
    suspend fun copyUriToFile(uri: Uri, dest: File): File = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(dest).use { output ->
                input.copyTo(output)
            }
        } ?: throw IllegalStateException("Cannot open $uri")
        dest
    }

    /**
     * Read the model's maximum context length (`<arch>.context_length`) from GGUF metadata.
     * Returns null if the file cannot be parsed.
     */
    suspend fun readContextLength(file: File): Int? = withContext(Dispatchers.IO) {
        runCatching {
            file.inputStream().buffered().use { input ->
                GgufMetadataReader.create().readStructuredMetadata(input).dimensions?.contextLength
            }
        }.onFailure {
            android.util.Log.w("ModelManager", "Failed to read GGUF metadata from ${file.name}", it)
        }.getOrNull()
    }

    private fun queryProgress(id: Long): Pair<Long, Long> {
        val cursor = downloadManager.query(DownloadManager.Query().setFilterById(id))
        return cursor.use {
            if (it.moveToFirst()) {
                val bytesIdx = it.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val totalIdx = it.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                it.getLong(bytesIdx) to it.getLong(totalIdx)
            } else {
                0L to 0L
            }
        }
    }

    private fun queryStatus(id: Long): Int {
        val cursor = downloadManager.query(DownloadManager.Query().setFilterById(id))
        return cursor.use {
            if (it.moveToFirst()) {
                it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            } else {
                DownloadManager.STATUS_FAILED
            }
        }
    }

    private fun queryReason(id: Long): String {
        val cursor = downloadManager.query(DownloadManager.Query().setFilterById(id))
        return cursor.use {
            if (it.moveToFirst()) {
                it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)).toString()
            } else {
                "unknown"
            }
        }
    }

    companion object {
        const val GGUF_EXT = ".gguf"
        const val MMPROJ_EXT = ".mmproj"
        const val CLIP_ARCH = "clip"
    }
}
