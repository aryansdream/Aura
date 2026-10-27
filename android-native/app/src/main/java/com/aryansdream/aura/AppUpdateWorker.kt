package com.aryansdream.aura

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class AppUpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        withContext(Dispatchers.IO) { AppUpdater.checkAndNotify(applicationContext) }
        Result.success()
    } catch (_: Exception) {
        Result.retry()
    }
}

object AppUpdateScheduler {
    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<AppUpdateWorker>(12, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "aura_version_watch",
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }
}
