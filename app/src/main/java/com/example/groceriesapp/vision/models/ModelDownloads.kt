package com.example.groceriesapp.vision.models

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** What the settings screen shows for one model. */
sealed interface ModelDownloadStatus {
    data object Idle : ModelDownloadStatus

    /** Queued, usually waiting for a (Wi-Fi) network. */
    data object Waiting : ModelDownloadStatus

    data class Running(val bytesDone: Long, val bytesTotal: Long) : ModelDownloadStatus {
        val fraction: Float get() = if (bytesTotal <= 0) 0f else (bytesDone.toFloat() / bytesTotal).coerceIn(0f, 1f)
    }

    data class Failed(val message: String) : ModelDownloadStatus

    data object Finished : ModelDownloadStatus
}

/**
 * Entry point for model downloads: queues a [ModelDownloadWorker] per model, reports its progress,
 * and deletes downloaded files. Whether a model is ready stays the business of the model managers,
 * which find the files at the catalog's destination paths.
 */
object ModelDownloads {
    private const val PREFERENCES = "appearance"
    private const val KEY_WIFI_ONLY = "model_download_wifi_only"

    fun uniqueWorkName(model: DownloadableModel) = "model-download-${model.id}"

    fun wifiOnly(context: Context): Boolean =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getBoolean(KEY_WIFI_ONLY, true)

    fun setWifiOnly(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putBoolean(KEY_WIFI_ONLY, enabled).apply()
    }

    fun start(context: Context, model: DownloadableModel) {
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(workDataOf(ModelDownloadWorker.KEY_MODEL to model.id))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (wifiOnly(context)) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .setRequiresStorageNotLow(true)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(uniqueWorkName(model), ExistingWorkPolicy.REPLACE, request)
    }

    fun cancel(context: Context, model: DownloadableModel) {
        WorkManager.getInstance(context).cancelUniqueWork(uniqueWorkName(model))
    }

    fun status(context: Context, model: DownloadableModel): Flow<ModelDownloadStatus> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(uniqueWorkName(model)).map { infos ->
            val info = infos.lastOrNull() ?: return@map ModelDownloadStatus.Idle
            when (info.state) {
                // Also covers a retry after a dropped connection: the partial file stays and resumes.
                WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> ModelDownloadStatus.Waiting
                WorkInfo.State.RUNNING -> ModelDownloadStatus.Running(
                    info.progress.getLong(ModelDownloadWorker.KEY_DONE, 0),
                    info.progress.getLong(ModelDownloadWorker.KEY_TOTAL, 0),
                )
                WorkInfo.State.SUCCEEDED -> ModelDownloadStatus.Finished
                WorkInfo.State.FAILED -> ModelDownloadStatus.Failed(
                    info.outputData.getString(ModelDownloadWorker.KEY_ERROR) ?: "Download failed",
                )
                WorkInfo.State.CANCELLED -> ModelDownloadStatus.Idle
            }
        }

    /** Where a catalog file lives on this device. */
    fun target(context: Context, file: ModelFileSpec): File =
        File(File(context.filesDir, file.destDir), file.destName)

    /** Removes the model's files (and partial downloads). The model managers then report it missing. */
    suspend fun delete(context: Context, spec: ModelSpec) = withContext(Dispatchers.IO) {
        cancel(context, spec.model)
        spec.files.forEach { file ->
            val target = target(context, file)
            target.delete()
            File(target.path + ModelDownloader.PART_SUFFIX).delete()
            // The LiteRT manager also looks in the external files dir, where files used to be pushed by hand.
            context.getExternalFilesDir(null)?.let { File(File(it, file.destDir), file.destName).delete() }
        }
    }
}

class ModelDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val model = DownloadableModel.entries.firstOrNull { it.id == inputData.getString(KEY_MODEL) }
            ?: return Result.failure(workDataOf(KEY_ERROR to "Unknown model"))
        val store = ModelCatalogStore(applicationContext)
        store.refresh()
        val spec = store.current().spec(model)
            ?: return Result.failure(workDataOf(KEY_ERROR to "This model is not in the catalog"))
        runCatching { setForeground(foregroundInfo(spec, null)) }
        val downloader = ModelDownloader()
        var doneBefore = 0L
        return try {
            for (file in spec.files) {
                downloader.download(file, ModelDownloads.target(applicationContext, file)) { done, _ ->
                    val total = spec.totalBytes
                    setProgressAsync(workDataOf(KEY_DONE to doneBefore + done, KEY_TOTAL to total))
                    notify(spec, (doneBefore + done).toFloat() / total)
                }
                doneBefore += file.bytes
            }
            Result.success()
        } catch (e: InsufficientStorageException) {
            Result.failure(workDataOf(KEY_ERROR to (e.message ?: "Not enough storage")))
        } catch (e: IOException) {
            // Dropped connections and server errors: the partial file stays and the next attempt resumes.
            if (runAttemptCount < MAX_ATTEMPTS) {
                Result.retry()
            } else {
                Result.failure(workDataOf(KEY_ERROR to (e.message ?: "Download failed")))
            }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val model = DownloadableModel.entries.firstOrNull { it.id == inputData.getString(KEY_MODEL) }
        val spec = model?.let { ModelCatalogStore(applicationContext).current().spec(it) }
        return foregroundInfo(spec, null)
    }

    private fun notify(spec: ModelSpec, fraction: Float) {
        val now = System.currentTimeMillis()
        if (now - lastNotified < 1_000) return
        lastNotified = now
        applicationContext.getSystemService(NotificationManager::class.java)
            .notify(notificationId(spec.model), notification(spec, fraction))
    }

    private var lastNotified = 0L

    private fun foregroundInfo(spec: ModelSpec?, fraction: Float?): ForegroundInfo {
        val notification = notification(spec, fraction)
        val id = spec?.let { notificationId(it.model) } ?: NOTIFICATION_ID
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(id, notification)
        }
    }

    private fun notification(spec: ModelSpec?, fraction: Float?): Notification {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Model downloads", NotificationManager.IMPORTANCE_LOW),
        )
        return NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading ${spec?.name ?: "model"}")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .apply {
                if (fraction == null) setProgress(0, 0, true) else setProgress(1000, (fraction * 1000).toInt(), false)
            }
            .build()
    }

    companion object {
        const val KEY_MODEL = "model"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_ERROR = "error"
        private const val CHANNEL = "model_downloads"
        private const val NOTIFICATION_ID = 4711
        private const val MAX_ATTEMPTS = 6

        /** One notification per model, so parallel downloads don't overwrite each other's progress. */
        private fun notificationId(model: DownloadableModel) = NOTIFICATION_ID + model.ordinal
    }
}
