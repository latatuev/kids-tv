package io.lotus.executor

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Optional status/diagnostic UI; normal links use LinkDispatchActivity. */
class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var result: TextView
    private lateinit var copy: Button
    private lateinit var share: Button
    private var lastResult = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createUi()
    }

    override fun onResume() {
        super.onResume()
        refreshResult()
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
        textLine("v0.2 · быстрый тест без второго подтверждения", 16f)
        status = textLine("Ожидание команды из ChatGPT.", 17f)
        val test = Button(this).apply {
            text = "Локальный тест (без подтверждения)"
            isAllCaps = false
            setOnClickListener {
                ResultStore.executePing(this@MainActivity, LinkParser.TestTask("manual"))
                refreshResult()
            }
        }
        root.addView(test, LinearLayout.LayoutParams(-1, dp(54)))
        result = textLine("", 15f)
        copy = Button(this).apply {
            text = "Скопировать результат"
            isAllCaps = false
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
            setOnClickListener {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, lastResult)
                }
                startActivity(Intent.createChooser(send, "Отправить результат"))
            }
        }
        root.addView(share, LinearLayout.LayoutParams(-1, dp(52)))
        textLine("Автоотправка на Lotus ещё не включена; результаты хранятся только на телефоне.", 14f).gravity = Gravity.CENTER
    }

    private fun refreshResult() {
        lastResult = ResultStore.lastResult(this)
        val ready = lastResult.isNotEmpty()
        status.text = if (ready) "Последний тест выполнен." else "Ожидаю ссылку из ChatGPT."
        result.text = if (ready) lastResult else "Результатов пока нет."
        copy.isEnabled = ready
        share.isEnabled = ready
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
}
