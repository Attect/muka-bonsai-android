package app.muka.bonsai.model

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.core.content.getSystemService
import app.muka.bonsai.llama.gguf.GgufMetadataReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File

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

class ModelManager(private val context: Context) {

    private val downloadManager = context.getSystemService<DownloadManager>()
        ?: throw IllegalStateException("DownloadManager not available")

    val modelsDir: File
        get() = File(context.filesDir, "models").also { it.mkdirs() }

    fun modelFile(model: BonsaiModel): File = File(modelsDir, model.filename)

    fun isDownloaded(model: BonsaiModel): Boolean = modelFile(model).exists()

    /**
     * DownloadManager can only write to external storage, so downloads land in the
     * app-specific external dir first and are moved to [modelsDir] on completion.
     */
    private fun downloadStagingFile(model: BonsaiModel): File {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: throw IllegalStateException("外部存储不可用，无法下载")
        return File(dir, model.filename)
    }

    /** Move a completed staging download into [modelsDir]. */
    private fun moveStagingToModels(model: BonsaiModel): File {
        val staging = downloadStagingFile(model)
        val dest = modelFile(model)
        if (dest.exists()) dest.delete()
        if (!staging.renameTo(dest)) {
            staging.copyTo(dest, overwrite = true)
            staging.delete()
        }
        return dest
    }

    /**
     * Returns a [Flow] that emits the current download status of [model].
     *
     * Completion is driven by polling the DownloadManager status instead of the
     * ACTION_DOWNLOAD_COMPLETE broadcast, which is not always delivered to
     * runtime-registered receivers. The flow completes when the download reaches
     * a terminal state.
     */
    fun downloadStatus(model: BonsaiModel, downloadId: Long? = null): Flow<DownloadStatus> =
        callbackFlow {
            val file = modelFile(model)
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
                            val staging = downloadStagingFile(model)
                            var waited = 0
                            while (!staging.exists() && waited < 10_000) {
                                delay(500)
                                waited += 500
                            }
                            if (staging.exists()) {
                                try {
                                    val dest = moveStagingToModels(model)
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
                            downloadStagingFile(model).delete()
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
     * Enqueue a DownloadManager request for [model] via [source]. Returns the download ID.
     */
    suspend fun enqueueDownload(model: BonsaiModel, source: DownloadSource): Long = withContext(Dispatchers.IO) {
        // Remove any stale partial download from a previous attempt.
        downloadStagingFile(model).delete()

        val request = DownloadManager.Request(Uri.parse(model.downloadUrl(source)))
            .setTitle("${model.family} ${model.sizeParam}")
            .setDescription("Downloading GGUF model…")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, model.filename)
            .setAllowedOverMetered(false)
            .setAllowedOverRoaming(false)

        downloadManager.enqueue(request)
    }

    /**
     * Delete a downloaded model file.
     */
    suspend fun deleteModel(model: BonsaiModel) = withContext(Dispatchers.IO) {
        modelFile(model).delete()
    }

    /**
     * Scan [modelsDir] and return any GGUF files found there.
     */
    suspend fun scanLocalModels(): List<File> = withContext(Dispatchers.IO) {
        android.util.Log.i("ModelManager", "Scanning ${modelsDir.absolutePath}, exists=${modelsDir.exists()}, canRead=${modelsDir.canRead()}")
        val files = modelsDir.listFiles { _, name -> name.endsWith(".gguf", ignoreCase = true) }
        android.util.Log.i("ModelManager", "Found ${files?.size ?: 0} files: ${files?.map { it.name }}")
        files?.toList() ?: emptyList()
    }

    /**
     * Move any completed downloads left in the staging dir into [modelsDir].
     *
     * DownloadManager survives process death but the completion receiver does not,
     * so a download that finished while the app was gone would otherwise be stranded.
     */
    suspend fun sweepStagingDownloads() = withContext(Dispatchers.IO) {
        val stagingDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return@withContext
        stagingDir.listFiles { _, name -> name.endsWith(".gguf", ignoreCase = true) }?.forEach { staging ->
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
}
