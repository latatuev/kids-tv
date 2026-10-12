package io.lotus.executor

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Limited read-only prototype. Links are untrusted input, not authorization. */
class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var result: TextView
    private lateinit var run: Button
    private lateinit var copy: Button
    private lateinit var share: Button
    private var task: LinkParser.TestTask? = null
    private var lastResult = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createUi()
        acceptIntent(intent)
    }

    override fun onNewIntent(newIntent: Intent) {
        super.onNewIntent(newIntent)
        setIntent(newIntent)
        acceptIntent(newIntent)
    }

    private fun createUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
            setBackgroundColor(Color.rgb(247, 248, 250))
        }
        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)

        fun textLine(value: String, size: Float): TextView {
            val view = TextView(this).apply {
                text = value
                textSize = size
                setTextColor(Color.rgb(30, 40, 57))
                setPadding(0, 0, 0, dp(18))
            }
            root.addView(view)
            return view
        }

        textLine("Lotus Executor", 28f)
        textLine("Тест запуска из ChatGPT без Termux", 16f)
        status = textLine("Ожидаю задание", 17f)

        run = Button(this).apply {
            text = "Выполнить тест"
            isAllCaps = false
            isEnabled = false
            setOnClickListener {
                val accepted = task ?: return@setOnClickListener
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Подтвердить выполнение")
                    .setMessage("Прочитать модель телефона и версию Android? Без изменений и сетевых запросов.")
                    .setNegativeButton("Отмена", null)
                    .setPositiveButton("Выполнить") { _, _ -> performTest(accepted) }
                    .show()
            }
        }
        root.addView(run, LinearLayout.LayoutParams(-1, dp(54)))
        result = textLine("Результат появится здесь.", 15f)
        copy = Button(this).apply {
            text = "Скопировать результат"
            isAllCaps = false
            isEnabled = false
            setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Lotus Executor", lastResult))
                Toast.makeText(this@MainActivity, "Скопировано", Toast.LENGTH_SHORT).show()
            }
        }
        root.addView(copy, LinearLayout.LayoutParams(-1, dp(52)))
        share = Button(this).apply {
            text = "Поделиться результатом"
            isAllCaps = false
            isEnabled = false
            setOnClickListener {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, lastResult)
                }
                startActivity(Intent.createChooser(send, "Отправить результат"))
            }
        }
        root.addView(share, LinearLayout.LayoutParams(-1, dp(52)))
        textLine("v0.1 · без SSH · без выполнения скриптов", 13f).gravity = Gravity.CENTER
    }

    private fun acceptIntent(incoming: Intent?) {
        task = null
        lastResult = ""
        run.isEnabled = false
        copy.isEnabled = false
        share.isEnabled = false
        result.text = "Результат появится здесь."
        if (incoming?.action != Intent.ACTION_VIEW) {
            status.text = "Открой тестовую ссылку из ChatGPT."
            return
        }
        val accepted = LinkParser.parse(incoming.dataString)
        if (accepted == null) {
            status.text = "Ссылка отклонена: неизвестное или некорректное действие."
            return
        }
        task = accepted
        status.text = "Получено задание: проверка телефона\nID: ${accepted.id}\nНужно подтверждение."
        run.isEnabled = true
    }

    private fun performTest(accepted: LinkParser.TestTask) {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        lastResult = "Lotus Executor v0.1 — OK\n" +
            "Task: ${accepted.id}\n" +
            "Device: ${Build.MANUFACTURER} ${Build.MODEL}\n" +
            "Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
            "UTC: ${format.format(Date())}\n" +
            "Operation: read-only device-info"
        result.text = lastResult
        status.text = "Тест выполнен. Результат можно отправить в чат."
        copy.isEnabled = true
        share.isEnabled = true
        try {
            openFileOutput("last_result.txt", MODE_PRIVATE).bufferedWriter().use { it.write(lastResult) }
        } catch (_: Exception) {
            result.append("\nНе удалось сохранить результат локально.")
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
}
