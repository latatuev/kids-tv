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

        state = label("Код не выполнялся. SSH-ключ остаётся на телефоне.", 14f)
        run = button("Проверить и выполнить на Lotus") { confirmAndRun() }
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
            .setMessage("Будет выполнен показанный ниже код от имени $username на Lotus. Он может изменить файлы. Проверка прав Lotus сохраняется.\nSHA-256: $digest")
            .setView(review)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Выполнить один раз") { _, _ ->
                if (executionStarted) return@setPositiveButton
                executionStarted = true
                run.isEnabled = false
                editor.isEnabled = false
                copyResult.isEnabled = false
                output.text = ""
                state.text = "Подключаюсь к Lotus…"
                Thread({ execute(script, digest) }, "executor-manual-python").start()
            }.show()
    }

    private fun execute(script: String, sha: String) {
        var session: Session? = null
        var channel: ChannelExec? = null
        var secret: ByteArray? = null
        try {
            val jsch = JSch()
            jsch.setKnownHosts(ByteArrayInputStream(
                (OwnerSshKeys.knownHosts(this) + "\n").toByteArray(StandardCharsets.UTF_8)
            ))
            secret = OwnerSshKeys.privateKey(this)
            jsch.addIdentity("lotus-owner-device", secret, null, null)
            session = jsch.getSession(username, host, 22)
            session.setConfig("StrictHostKeyChecking", "yes")
            session.setConfig("PreferredAuthentications", "publickey")
            session.connect(15000)
            secret.fill(0)
            secret = null
            channel = session.openChannel("exec") as ChannelExec
            channel.setCommand("python3 -")
            channel.setInputStream(ByteArrayInputStream(script.toByteArray(StandardCharsets.UTF_8)))
            val stdout = CappedOutput()
            val stderr = CappedOutput()
            channel.setOutputStream(stdout)
            channel.setErrStream(stderr)
            channel.connect(15000)
            val end = System.currentTimeMillis() + 200_000
            while (!channel.isClosed) {
                if (System.currentTimeMillis() >= end) {
                    throw IllegalStateException("Превышен лимит 200 секунд")
                }
                Thread.sleep(150)
            }
            val exit = channel.exitStatus
            val report = "SHA-256: $sha\nSSH exit code: $exit\n\n" +
                stdout.asText() +
                if (stderr.asText().isNotEmpty()) "\nSTDERR:\n" + stderr.asText() else ""
            runOnUiThread {
                state.text = if (exit == 0) "Команда завершилась успешно" else
                    "Команда завершилась с ошибкой: $exit"
                output.text = report
                copyResult.isEnabled = true
            }
        } catch (e: Exception) {
            runOnUiThread {
                state.text = "Результат выполнения не подтверждён: ${e.javaClass.simpleName}"
                output.text = (e.message ?: "Ошибка соединения").take(700)
                copyResult.isEnabled = true
            }
        } finally {
            secret?.fill(0)
            try { channel?.disconnect() } catch (_: Exception) {}
            try { session?.disconnect() } catch (_: Exception) {}
        }
    }

    private class CappedOutput : OutputStream() {
        private val data = ByteArrayOutputStream()
        @Synchronized override fun write(b: Int) {
            if (data.size() < 64 * 1024) data.write(b)
        }
        @Synchronized override fun write(b: ByteArray, off: Int, len: Int) {
            if (data.size() < 64 * 1024) {
                data.write(b, off, minOf(len, 64 * 1024 - data.size()))
            }
        }
        @Synchronized fun asText(): String = data.toString("UTF-8") +
            if (data.size() >= 64 * 1024) "\n[output truncated]" else ""
    }
}
