package io.lotus.executor

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Persistent WorkManager job survives process termination and temporary network loss. */
class ResultUploadWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val task = inputData.getString("task") ?: return Result.failure()
        val nonce = inputData.getString("nonce") ?: return Result.failure()
        // Do not retry indefinitely while device is not yet paired or the server is unavailable.
        if (PairingClient.deviceId(applicationContext) == null) return Result.failure()
        if (runAttemptCount >= 12) return Result.failure()
        val timestamp = System.currentTimeMillis() / 1000
        return try {
            when (PairingClient.uploadPing(applicationContext, task, nonce, timestamp)) {
                200 -> Result.success()
                400, 401, 403, 404, 409, 413, 415 -> Result.failure()
                else -> Result.retry()
            }
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        fun enqueue(context: Context, task: String) {
            val data = Data.Builder().putString("task", task)
                .putString("nonce", UUID.randomUUID().toString().replace("-", "")).build()
            val request = OneTimeWorkRequestBuilder<ResultUploadWorker>()
                .setInputData(data)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueue(request)
        }
    }
}
