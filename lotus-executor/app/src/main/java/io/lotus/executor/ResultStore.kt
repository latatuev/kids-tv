package io.lotus.executor

import android.content.Context
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Local-first result: writes atomically to app preferences, then queues upload. */
object ResultStore {
    private const val KEY = "last_result"
    private const val TASK = "last_task"
    private const val TIME = "last_completed_ms"

    fun executePing(context: Context, task: LinkParser.TestTask): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        val result = "Lotus Executor v0.3 — OK\n" +
            "Task: ${task.id}\n" +
            "Device: ${Build.MANUFACTURER} ${Build.MODEL}\n" +
            "Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
            "UTC: ${format.format(Date())}\n" +
            "Operation: read-only device-info\n" +
            "Return: saved locally; upload only after approved pairing"
        context.getSharedPreferences("executor_v02", Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, result)
            .putString(TASK, task.id)
            .putLong(TIME, System.currentTimeMillis())
            .commit()
        // Never spawn a retry loop while the device has not yet been paired.
        if (PairingClient.deviceId(context) != null) {
            ResultUploadWorker.enqueue(context, task.id)
        }
        return result
    }

    /** Pairing may happen after the last local ping. Upload that final saved result once. */
    fun enqueueLastAfterPairing(context: Context) {
        val prefs = context.getSharedPreferences("executor_v02", Context.MODE_PRIVATE)
        val task = prefs.getString(TASK, null)
        if (task != null && Regex("[A-Za-z0-9_-]{1,32}").matches(task) &&
            PairingClient.deviceId(context) != null) {
            ResultUploadWorker.enqueue(context, task)
        }
    }

    fun lastResult(context: Context): String =
        context.getSharedPreferences("executor_v02", Context.MODE_PRIVATE)
            .getString(KEY, "") ?: ""
}
