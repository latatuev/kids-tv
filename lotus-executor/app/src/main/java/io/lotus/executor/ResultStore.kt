package io.lotus.executor

import android.content.Context
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** No network transmission. Private local record only. */
object ResultStore {
    private const val KEY = "last_result"
    private const val TASK = "last_task"
    private const val TIME = "last_completed_ms"

    fun executePing(context: Context, task: LinkParser.TestTask): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        val result = "Lotus Executor v0.2 — OK\n" +
            "Task: ${task.id}\n" +
            "Device: ${Build.MANUFACTURER} ${Build.MODEL}\n" +
            "Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
            "UTC: ${format.format(Date())}\n" +
            "Operation: read-only device-info\n" +
            "Return: local-only, server upload not yet configured"
        context.getSharedPreferences("executor_v02", Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, result)
            .putString(TASK, task.id)
            .putLong(TIME, System.currentTimeMillis())
            .apply()
        return result
    }

    fun lastResult(context: Context): String =
        context.getSharedPreferences("executor_v02", Context.MODE_PRIVATE)
            .getString(KEY, "") ?: ""
}
