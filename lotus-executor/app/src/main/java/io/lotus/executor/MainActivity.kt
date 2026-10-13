package io.lotus.executor

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
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

        // Explicit button contrast, independent of Samsung theme/accent settings.
        fun styleAction(b: Button): Button = b.apply {
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.rgb(24, 95, 194))
                cornerRadius = dp(10).toFloat()
            }
            setPadding(dp(10), dp(6), dp(10), dp(6))
            textSize = 14f
        }

        textLine("Lotus Executor", 28f)
        textLine("v0.3 · быстрый тест + защищённая отправка на Lotus", 16f)
        status = textLine("Ожидание команды из ChatGPT.", 17f)
        val test = Button(this).apply {
            text = "Локальный тест (без подтверждения)"
            isAllCaps = false
            setOnClickListener {
                ResultStore.executePing(this@MainActivity, LinkParser.TestTask("manual"))
                refreshResult()
            }
        }
        root.addView(styleAction(test), LinearLayout.LayoutParams(-1, dp(54)))
        val pairing = Button(this).apply {
            text = "Зарегистрировать телефон на Lotus"
            isAllCaps = false
            setOnClickListener { backgroundPairing { PairingClient.beginPairing(this@MainActivity) } }
        }
        root.addView(styleAction(pairing), LinearLayout.LayoutParams(-1, dp(52)))
        val checkPair = Button(this).apply {
            text = "Проверить регистрацию"
            isAllCaps = false
            setOnClickListener { backgroundPairing { PairingClient.checkPairing(this@MainActivity) } }
        }
        root.addView(styleAction(checkPair), LinearLayout.LayoutParams(-1, dp(52)))
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
        root.addView(styleAction(copy), LinearLayout.LayoutParams(-1, dp(52)))
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
        root.addView(styleAction(share), LinearLayout.LayoutParams(-1, dp(52)))
        textLine("Результат сначала сохраняется на телефоне. Автоотправка работает после подтверждённой регистрации.", 14f).gravity = Gravity.CENTER
    }

    private fun backgroundPairing(action: () -> String) {
        status.text = "Связываюсь с Lotus…"
        Thread {
            val message = try { action() }
                catch (e: Exception) { "Нет соединения или ошибка регистрации: " + (e.message ?: "неизвестно") }
            runOnUiThread { status.text = message }
        }.start()
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
