package io.github.xororz.localdream.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import io.github.xororz.localdream.R
import io.github.xororz.localdream.data.Model
import io.github.xororz.localdream.utils.Http
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request

class ModelDownloadService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var downloadJob: Job? = null

    @Volatile
    private var isPauseRequested = false

    private var activeModelId: String? = null
    private var activeModelName: String? = null
    private var activeFileUrl: String? = null
    private var activeIsZip: Boolean = false
    private var activeIsNpu: Boolean = false
    private var activeModelType: String = "sd"

    private var lastDownloadedBytes: Long = 0L
    private var lastTotalBytes: Long = 0L

    private val notificationManager by lazy {
        getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    }

    private val client = Http.client.newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val TAG = "ModelDownloadService"
        private const val NOTIFICATION_CHANNEL_ID = "model_download_channel"
        private const val NOTIFICATION_ID = 2001

        private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
        val downloadState: StateFlow<DownloadState> = _downloadState

        const val ACTION_START_DOWNLOAD = "action_start_download"
        const val ACTION_PAUSE_DOWNLOAD = "action_pause_download"
        const val ACTION_RESUME_DOWNLOAD = "action_resume_download"
        const val ACTION_CANCEL_DOWNLOAD = "action_cancel_download"

        const val EXTRA_MODEL_ID = "model_id"
        const val EXTRA_MODEL_NAME = "model_name"
        const val EXTRA_FILE_URL = "file_url"
        const val EXTRA_IS_ZIP = "is_zip"
        const val EXTRA_IS_NPU = "is_npu"
        const val EXTRA_MODEL_TYPE = "model_type" // "sd" or "upscaler"
    }

    sealed class DownloadState {
        object Idle : DownloadState()
        data class Downloading(
            val modelId: String,
            val progress: Float,
            val downloadedBytes: Long,
            val totalBytes: Long,
        ) : DownloadState()

        data class Paused(
            val modelId: String,
            val progress: Float,
            val downloadedBytes: Long,
            val totalBytes: Long,
        ) : DownloadState()

        data class Extracting(val modelId: String) : DownloadState()
        data class Success(val modelId: String) : DownloadState()
        data class Error(val modelId: String, val message: String) : DownloadState()
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_DOWNLOAD, ACTION_RESUME_DOWNLOAD -> {
                val modelId = intent.getStringExtra(EXTRA_MODEL_ID)
                    ?: activeModelId
                    ?: return START_NOT_STICKY
                val modelName = intent.getStringExtra(EXTRA_MODEL_NAME)
                    ?: activeModelName
                    ?: modelId
                val fileUrl = intent.getStringExtra(EXTRA_FILE_URL)
                    ?: activeFileUrl
                    ?: return START_NOT_STICKY
                val isZip = intent.getBooleanExtra(EXTRA_IS_ZIP, activeIsZip)
                val isNpu = intent.getBooleanExtra(EXTRA_IS_NPU, activeIsNpu)
                val modelType = intent.getStringExtra(EXTRA_MODEL_TYPE)
                    ?: activeModelType

                activeModelId = modelId
                activeModelName = modelName
                activeFileUrl = fileUrl
                activeIsZip = isZip
                activeIsNpu = isNpu
                activeModelType = modelType
                isPauseRequested = false

                startForeground(NOTIFICATION_ID, createNotification(modelName, 0f, modelId = modelId, isPaused = false))
                startDownload(modelId, modelName, fileUrl, isZip, isNpu, modelType)
            }

            ACTION_PAUSE_DOWNLOAD -> {
                pauseDownload()
            }

            ACTION_CANCEL_DOWNLOAD -> {
                cancelDownload()
            }
        }
        return START_NOT_STICKY
    }

    private fun startDownload(
        modelId: String,
        modelName: String,
        fileUrl: String,
        isZip: Boolean,
        isNpu: Boolean,
        modelType: String,
    ) {
        downloadJob?.cancel()
        downloadJob = serviceScope.launch {
            var tempFile: File? = null
            var extractTempDir: File? = null
            try {
                _downloadState.value = DownloadState.Downloading(
                    modelId = modelId,
                    progress = if (lastTotalBytes > 0) lastDownloadedBytes.toFloat() / lastTotalBytes else 0f,
                    downloadedBytes = lastDownloadedBytes,
                    totalBytes = lastTotalBytes,
                )

                val tempDir = File(filesDir, "temp_downloads").apply {
                    if (!exists()) mkdirs()
                }

                tempFile = File(tempDir, "${modelId}.part")

                downloadFileWithRetry(fileUrl, tempFile, modelId, modelName)

                if (isPauseRequested) {
                    return@launch
                }

                when (modelType) {
                    "sd" -> {
                        if (isZip) {
                            val modelDir = File(getModelsDir(), modelId)

                            if (modelDir.exists()) {
                                modelDir.deleteRecursively()
                            }
                            modelDir.mkdirs()

                            extractTempDir = File(tempDir, "${modelId}_extract")
                            if (extractTempDir.exists()) {
                                extractTempDir.deleteRecursively()
                            }
                            extractTempDir.mkdirs()

                            _downloadState.value = DownloadState.Extracting(modelId)
                            updateNotification(modelName, 0f, isExtracting = true, modelId = modelId)

                            unzipFile(tempFile, extractTempDir)

                            extractTempDir.listFiles()?.forEach { file ->
                                file.renameTo(File(modelDir, file.name))
                            }
                            extractTempDir.delete()
                            extractTempDir = null

                            if (isNpu) {
                                File(modelDir, "v3").createNewFile()
                            }
                        }
                    }

                    "upscaler" -> {
                        val upscalerDir = File(getModelsDir(), modelId).apply {
                            if (!exists()) mkdirs()
                        }
                        val targetFile = File(upscalerDir, Model.UPSCALER_FILE_NAME)

                        if (targetFile.exists()) {
                            targetFile.delete()
                        }

                        if (!tempFile.renameTo(targetFile)) {
                            tempFile.copyTo(targetFile, overwrite = true)
                        }
                    }
                }

                tempFile.delete()
                tempFile = null

                _downloadState.value = DownloadState.Success(modelId)
                updateNotification(modelName, 100f, success = true, modelId = modelId)

                withContext(Dispatchers.Main) {
                    delay(2000)
                    _downloadState.value = DownloadState.Idle
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            } catch (e: CancellationException) {
                if (isPauseRequested) {
                    val progress = if (lastTotalBytes > 0) lastDownloadedBytes.toFloat() / lastTotalBytes else 0f
                    _downloadState.value = DownloadState.Paused(modelId, progress, lastDownloadedBytes, lastTotalBytes)
                    updateNotification(modelName, progress, isPaused = true, modelId = modelId)
                }
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)

                extractTempDir?.deleteRecursively()

                _downloadState.value =
                    DownloadState.Error(modelId, e.message ?: getString(R.string.unknown_error))
                updateNotification(modelName, 0f, success = false, error = e.message, modelId = modelId)

                withContext(Dispatchers.Main) {
                    delay(3000)
                    _downloadState.value = DownloadState.Idle
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    private suspend fun downloadFileWithRetry(
        url: String,
        destFile: File,
        modelId: String,
        modelName: String,
    ) = withContext(Dispatchers.IO) {
        var attempts = 0
        val maxAttempts = 4
        var success = false
        var lastException: Exception? = null

        while (attempts < maxAttempts && !success && coroutineContext.isActive && !isPauseRequested) {
            attempts++
            try {
                downloadFile(url, destFile, modelId, modelName)
                success = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastException = e
                if (isPauseRequested || attempts >= maxAttempts) {
                    throw e
                }
                Log.w(TAG, "Download attempt $attempts failed: ${e.message}. Retrying in 2 seconds...")
                delay(2000)
            }
        }

        if (!success && lastException != null && !isPauseRequested) {
            throw lastException
        }
    }

    private suspend fun downloadFile(
        url: String,
        destFile: File,
        modelId: String,
        modelName: String,
    ) = withContext(Dispatchers.IO) {
        val existingBytes = if (destFile.exists()) destFile.length() else 0L

        val requestBuilder = Request.Builder().url(url)
        if (existingBytes > 0) {
            requestBuilder.header("Range", "bytes=$existingBytes-")
        }
        val request = requestBuilder.build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful && response.code != 416) {
                throw Exception(getString(R.string.error_download_failed, response.code.toString()))
            }

            if (response.code == 416) {
                // Requested Range Not Satisfiable: file might already be complete on disk
                return@use
            }

            val body = response.body ?: throw Exception("Response body is null")
            val isPartial = response.code == 206
            val contentLength = body.contentLength()

            val totalBytes = if (isPartial && existingBytes > 0 && contentLength > 0) {
                existingBytes + contentLength
            } else if (contentLength > 0) {
                contentLength
            } else {
                0L
            }

            var downloadedBytes = if (isPartial) existingBytes else 0L
            lastDownloadedBytes = downloadedBytes
            lastTotalBytes = totalBytes

            val append = isPartial && existingBytes > 0

            java.io.BufferedOutputStream(FileOutputStream(destFile, append)).use { output ->
                body.byteStream().buffered().use { input ->
                    val buffer = ByteArray(32 * 1024)
                    var bytes: Int
                    var lastUpdateTime = 0L

                    while (input.read(buffer).also { bytes = it } != -1) {
                        if (isPauseRequested || !coroutineContext.isActive) {
                            output.flush()
                            break
                        }

                        output.write(buffer, 0, bytes)
                        downloadedBytes += bytes
                        lastDownloadedBytes = downloadedBytes
                        if (totalBytes > 0) {
                            lastTotalBytes = totalBytes
                        }

                        val currentTime = System.currentTimeMillis()
                        if (currentTime - lastUpdateTime >= 500 || (totalBytes > 0 && downloadedBytes == totalBytes)) {
                            lastUpdateTime = currentTime
                            val progress = if (totalBytes > 0) {
                                downloadedBytes.toFloat() / totalBytes
                            } else {
                                0f
                            }

                            _downloadState.value = DownloadState.Downloading(
                                modelId,
                                progress,
                                downloadedBytes,
                                totalBytes,
                            )

                            updateNotification(modelName, progress, modelId = modelId)
                        }
                    }
                }
            }

            if (isPauseRequested) {
                return@use
            }

            if (totalBytes > 0 && downloadedBytes < totalBytes) {
                throw IOException(
                    getString(R.string.error_download_failed, "$downloadedBytes/$totalBytes"),
                )
            }
        }
    }

    private suspend fun unzipFile(zipFile: File, destDir: File) = withContext(Dispatchers.IO) {
        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry

            while (entry != null) {
                if (!entry.isDirectory) {
                    val fileName = entry.name.substringAfterLast('/')
                    if (fileName.isNotEmpty() && !fileName.startsWith(".") && !fileName.startsWith("__MACOSX")) {
                        val file = File(destDir, fileName)

                        java.io.BufferedOutputStream(FileOutputStream(file)).use { output ->
                            zis.copyTo(output)
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    private fun pauseDownload() {
        val modelId = activeModelId ?: return
        val modelName = activeModelName ?: modelId
        isPauseRequested = true
        downloadJob?.cancel()

        val progress = if (lastTotalBytes > 0) lastDownloadedBytes.toFloat() / lastTotalBytes else 0f
        _downloadState.value = DownloadState.Paused(
            modelId = modelId,
            progress = progress,
            downloadedBytes = lastDownloadedBytes,
            totalBytes = lastTotalBytes,
        )
        updateNotification(modelName, progress, isPaused = true, modelId = modelId)
    }

    private fun cancelDownload() {
        isPauseRequested = false
        downloadJob?.cancel()

        activeModelId?.let { id ->
            val tempDir = File(filesDir, "temp_downloads")
            File(tempDir, "${id}.part").delete()
        }

        activeModelId = null
        activeModelName = null
        activeFileUrl = null
        lastDownloadedBytes = 0L
        lastTotalBytes = 0L

        _downloadState.value = DownloadState.Idle
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun getModelsDir(): File = File(filesDir, "models").apply {
        if (!exists()) mkdirs()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.model_download_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.model_download_channel_desc)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun createNotification(
        modelName: String,
        progress: Float,
        isExtracting: Boolean = false,
        isPaused: Boolean = false,
        modelId: String? = null,
    ): android.app.Notification {
        val title = when {
            isExtracting -> getString(R.string.extracting)
            isPaused -> getString(R.string.download_paused)
            else -> getString(R.string.downloading_model, modelName)
        }

        val openAppIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val appPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(modelName)
            .setSmallIcon(
                if (isPaused) android.R.drawable.ic_media_pause else android.R.drawable.stat_sys_download
            )
            .setProgress(100, (progress * 100).toInt(), isExtracting)
            .setOngoing(!isPaused)
            .setContentIntent(appPendingIntent)

        if (!isExtracting) {
            if (isPaused) {
                val resumeIntent = Intent(this, ModelDownloadService::class.java).apply {
                    action = ACTION_RESUME_DOWNLOAD
                    if (modelId != null) putExtra(EXTRA_MODEL_ID, modelId)
                }
                val resumePendingIntent = PendingIntent.getService(
                    this,
                    1,
                    resumeIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                builder.addAction(
                    android.R.drawable.ic_media_play,
                    getString(R.string.resume),
                    resumePendingIntent,
                )
            } else {
                val pauseIntent = Intent(this, ModelDownloadService::class.java).apply {
                    action = ACTION_PAUSE_DOWNLOAD
                }
                val pausePendingIntent = PendingIntent.getService(
                    this,
                    2,
                    pauseIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                builder.addAction(
                    android.R.drawable.ic_media_pause,
                    getString(R.string.pause),
                    pausePendingIntent,
                )
            }

            val cancelIntent = Intent(this, ModelDownloadService::class.java).apply {
                action = ACTION_CANCEL_DOWNLOAD
            }
            val cancelPendingIntent = PendingIntent.getService(
                this,
                3,
                cancelIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(android.R.string.cancel),
                cancelPendingIntent,
            )
        }

        return builder.build()
    }

    private fun updateNotification(
        modelName: String,
        progress: Float,
        success: Boolean = false,
        error: String? = null,
        isExtracting: Boolean = false,
        isPaused: Boolean = false,
        modelId: String? = null,
    ) {
        val notification = when {
            success -> {
                NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                    .setContentTitle(getString(R.string.download_complete))
                    .setContentText(modelName)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setOngoing(false)
                    .build()
            }

            error != null -> {
                NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                    .setContentTitle(getString(R.string.download_failed))
                    .setContentText(error)
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setOngoing(false)
                    .build()
            }

            else -> {
                createNotification(modelName, progress, isExtracting, isPaused, modelId)
            }
        }

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTimeout(startId: Int) {
        super.onTimeout(startId)
        handleTimeout(0)
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        super.onTimeout(startId, fgsType)
        handleTimeout(fgsType)
    }

    private fun handleTimeout(fgsType: Int) {
        Log.e(TAG, "Foreground service timeout (fgsType=$fgsType)")
        downloadJob?.cancel()
        _downloadState.value = DownloadState.Error("timeout", "Foreground service timeout")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
