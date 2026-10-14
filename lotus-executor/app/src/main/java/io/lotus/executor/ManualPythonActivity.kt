package io.lotus.executor

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

/**
 * Explicit owner-only Python editor, launched solely from the app's home screen.
 * No remote link / intent can inject commands: the Activity is not exported.
 * Never auto-runs. The private SSH identity remains in Android Keystore storage.
 */
class ManualPythonActivity : Activity() {
    private val host = "135.106.163.200"
    private val username = "sergey"
    private val taskId = "manual-" + UUID.randomUUID().toString().take(12)
    private lateinit var editor: EditText
    private lateinit var state: TextView
    private lateinit var output: TextView
    private lateinit var run: Button
    private lateinit var copyResult: Button
    private lateinit var sudoMode: CheckBox
    private var executionStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        draw()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun draw() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(15), dp(16), dp(22))
            setBackgroundColor(Color.rgb(247, 248, 250))
        }
        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)

        fun label(message: String, size: Float = 15f): TextView {
            val view = TextView(this).apply {
                text = message
                textSize = size
                setTextColor(Color.rgb(27, 39, 55))
                setPadding(0, dp(5), 0, dp(8))
            }
            root.addView(view)
            return view
        }

        fun button(title: String, action: () -> Unit): Button {
            val b = Button(this).apply {
                text = title
                textSize = 14f
                isAllCaps = false
                isSingleLine = false
                minHeight = dp(50)
                minimumHeight = dp(50)
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    setColor(Color.rgb(24, 95, 194))
                    cornerRadius = dp(10).toFloat()
                }
                setPadding(dp(10), dp(7), dp(10), dp(7))
                setOnClickListener { action() }
            }
            root.addView(b, LinearLayout.LayoutParams(-1, dp(54)).apply {
                topMargin = dp(4)
                bottomMargin = dp(4)
            })
            return b
        }

        label("Lotus Executor · Python", 23f)
        label("Ручной ввод · $username@$host. Выполнение только после отдельного подтверждения.", 13f)
        run = button("Проверить и выполнить на Lotus") { confirmAndRun() }
        sudoMode = CheckBox(this).apply {
            text = "Выполнить с sudo (потребуется пароль на телефоне)"
            textSize = 15f
            setTextColor(Color.rgb(27, 39, 55))
            isChecked = false
        }
        root.addView(sudoMode)
        state = label("Код не выполнялся. SSH-ключ остаётся на телефоне.", 14f)
        label("Python-код (передаётся как python3 -):")
        editor = EditText(this).apply {
            hint = "Вставьте сюда Python-код…"
            minLines = 8
            maxLines = 18
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            typeface = Typeface.MONOSPACE
            textSize = 14f
            setHorizontallyScrolling(false)
            setSingleLine(false)
            setTextIsSelectable(true)
        }
        root.addView(editor, LinearLayout.LayoutParams(-1, -2))

        output = label("", 12f).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        copyResult = button("Копировать результат") {
            val report = output.text.toString()
            if (report.isBlank()) {
                Toast.makeText(this, "Результата пока нет", Toast.LENGTH_SHORT).show()
            } else {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText(
                    "Lotus Executor result", "Задача: $taskId\n${state.text}\n\n$report"
                ))
                Toast.makeText(this, "Результат скопирован", Toast.LENGTH_SHORT).show()
            }
        }
        copyResult.isEnabled = false

        label("Настройка проверенного ключа сервера (только при необходимости):", 13f)
        val known = EditText(this).apply {
            hint = "Проверенная строка known_hosts"
            minLines = 2
            setSingleLine(false)
            setText(OwnerSshKeys.knownHosts(this@ManualPythonActivity))
        }
        root.addView(known, LinearLayout.LayoutParams(-1, -2))
        button("Сохранить known_hosts") {
            try {
                OwnerSshKeys.saveKnownHosts(this, known.text.toString())
                state.text = "Проверенный ключ сервера сохранён."
            } catch (e: Exception) {
                state.text = "Ошибка сохранения known_hosts: ${e.message}"
            }
        }
    }

    private fun confirmAndRun() {
        if (executionStarted) return
        if (!OwnerSshKeys.hasIdentity(this) || OwnerSshKeys.knownHosts(this).isBlank()) {
            state.text = "Нет настроенного SSH-ключа или known_hosts. Проверьте настройки."
            return
        }
        val elevated = sudoMode.isChecked
        val script = editor.text.toString()
        val bytes = script.toByteArray(StandardCharsets.UTF_8)
        if (script.isBlank() || bytes.size > 20 * 1024 || script.indexOf('\u0000') >= 0) {
            state.text = "Введите Python-код (не более 20 КиБ, без NUL)."
            return
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        val review = ScrollView(this)
        val readable = TextView(this).apply {
            text = script
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextColor(Color.rgb(27, 39, 55))
            setPadding(dp(14), dp(9), dp(14), dp(9))
            setTextIsSelectable(true)
        }
        review.addView(readable)
        AlertDialog.Builder(this)
            .setTitle("Проверить Python-код и выполнить?")
            .setMessage("Будет выполнен показанный ниже код " +
                (if (elevated) "с правами root через sudo после отдельного ввода пароля" else "от имени $username") +
                " на Lotus. Проверьте код.\nSHA-256: $digest")
            .setView(review)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Выполнить один раз") { _, _ ->
                val start: (ByteArray?) -> Unit = { password ->
                    if (executionStarted) {
                        password?.fill(0)
                    } else {
                        executionStarted = true
                        run.isEnabled = false
                        sudoMode.isEnabled = false
                        editor.isEnabled = false
                        copyResult.isEnabled = false
                        output.text = ""
                        state.text = "Подключаюсь к Lotus…"
                        Thread({ execute(script, digest, password) }, "executor-manual-python").start()
                    }
                }
                if (elevated) SudoSupport.prompt(this) { password -> start(password) }
                else start(null)
            }.show()
    }

    private fun execute(script: String, sha: String, sudoPassword: ByteArray?) {
        try {
            val result = SshRunner.run(applicationContext, script, sudoPassword)
            val report = "SHA-256: $sha\nSSH exit code: ${result.exit}\n\n" +
                result.stdout +
                if (result.stderr.isNotEmpty()) "\nSTDERR:\n" + result.stderr else ""
            runOnUiThread {
                output.text = report
                copyResult.isEnabled = true
                if (result.exit == 0) {
                    try {
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText(
                            "Lotus Executor result", "Задача: $taskId\nКоманда завершилась успешно\n\n$report"
                        ))
                        state.text = "Выполнено успешно, результат скопирован в буфер"
                        state.setTextColor(Color.rgb(20, 125, 70))
                    } catch (_: Exception) {
                        state.text = "Команда выполнена, но результат не скопирован. Нажмите «Копировать результат»."
                        state.setTextColor(Color.rgb(151, 85, 12))
                    }
                } else {
                    state.text = "Команда завершилась с ошибкой: ${result.exit}"
                    state.setTextColor(Color.rgb(170, 36, 36))
                }
            }
        } catch (e: Exception) {
            runOnUiThread {
                val reason = when (e.message) {
                    "SUDO_AUTH_TIMEOUT" -> "sudo: нет подтверждения за 25 секунд. Python-код не отправлен."
                    "SUDO_AUTH_REJECTED" -> "sudo отклонил пароль. Python-код не отправлен."
                    else -> "SSH/sudo: ${e.javaClass.simpleName}. Выполнение не подтверждено."
                }
                state.text = reason
                state.setTextColor(Color.rgb(170, 36, 36))
                output.text = "Команда не подтверждена. Не повторяйте автоматически; новый запуск требует проверки кода."
                copyResult.isEnabled = true
            }
        } finally {
            sudoPassword?.fill(0)
        }
    }

}
