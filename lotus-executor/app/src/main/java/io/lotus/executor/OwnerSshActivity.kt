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

/**
 * Owner-operated SSH, not a server-side Bridge/scope escalation.
 * App Links merely deliver a bounded script, NEVER auto-run it.
 * A separate user-approved SSH public key and pinned host key are mandatory.
 */
class OwnerSshActivity : Activity() {
    private lateinit var task: OwnerScriptLink.Task
    private lateinit var state: TextView
    private lateinit var output: TextView
    private lateinit var run: Button
    private val host = "135.106.163.200"
    private val username = "sergey"
    private val prefs by lazy { getSharedPreferences("lotus-owner-ssh-runs", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra("task_id")
        val script = intent.getStringExtra("task_script")
        val digest = intent.getStringExtra("task_sha256")
        if (id.isNullOrBlank() || script.isNullOrBlank() || digest.isNullOrBlank()) {
            finish()
            return
        }
        // Never run the entire script from an exported activity or a pasted Intent extra.
        val verified = OwnerScriptLink.parse(intent.getStringExtra("original_url"))
        if (verified == null || verified.id != id || verified.script != script ||
            verified.sha256 != digest) {
            finish()
            return
        }
        task = verified
        draw()
    }

    private fun copy(value: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Lotus Executor", value))
        Toast.makeText(this, "Скопировано", Toast.LENGTH_SHORT).show()
    }

    private fun draw() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(20))
        }
        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)
        fun label(s: String, sp: Float = 15f): TextView {
            val t = TextView(this).apply {
                text = s
                textSize = sp
                setTextColor(Color.rgb(27, 39, 55))
                setPadding(0, dp(6), 0, dp(7))
            }
            root.addView(t)
            return t
        }
        fun button(text: String, action: () -> Unit): Button {
            // Use density-independent height and explicit contrast: on Samsung/Android
            // 16, a 58-pixel platform Button clipped all the text, showing grey bars.
            val bg = GradientDrawable().apply {
                setColor(Color.rgb(24, 95, 194))
                cornerRadius = dp(10).toFloat()
            }
            val b = Button(this).apply {
                this.text = text
                textSize = 14f
                isAllCaps = false
                setTextColor(Color.WHITE)
                isSingleLine = false
                minHeight = dp(50)
                minimumHeight = dp(50)
                background = bg
                setPadding(dp(10), dp(7), dp(10), dp(7))
                setOnClickListener { action() }
            }
            root.addView(b, LinearLayout.LayoutParams(-1, dp(54)).apply {
                topMargin = dp(4)
                bottomMargin = dp(4)
            })
            return b
        }
        label("Lotus Executor · SSH владельца", 23f)
        label("Сервер: $username@$host   •   Задача: ${task.id}")
        label("SHA-256: ${task.sha256}")
        label("Команда, которая будет исполнена: python3 -")
        val code = label(task.script, 12f).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        // Display complete command, not a summary, before the owner can consent.
        button("Копировать код") { copy(task.script) }
        label("Первоначальная настройка SSH — один раз:")
        val pub = label(OwnerSshKeys.publicKey(this).ifEmpty { "Ключ телефона ещё не создан." }, 12f)
            .apply { setTextIsSelectable(true) }
        val keyFeedback = label(
            if (OwnerSshKeys.hasIdentity(this)) "SSH-ключ телефона уже создан. Его можно скопировать ниже."
            else "SSH-ключ ещё не создан. После подтверждения появится уведомление о ходе генерации.",
            14f
        )
        var createKeyButton: Button? = null
        createKeyButton = button("Создать SSH-ключ телефона") {
            if (OwnerSshKeys.hasIdentity(this)) {
                pub.text = OwnerSshKeys.publicKey(this)
                keyFeedback.text = "SSH-ключ уже существует. Он сохранён; новый ключ не создавался."
                Toast.makeText(this, "Ключ уже создан — нажмите «Копировать публичный ключ»", Toast.LENGTH_LONG).show()
                return@button
            }
            AlertDialog.Builder(this)
                .setTitle("Создать отдельный SSH-ключ?")
                .setMessage("Приватный ключ останется зашифрованным в памяти приложения. Публичный ключ потребуется один раз добавить на свой сервер через доверенный SSH-доступ.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Создать") { _, _ ->
                    keyFeedback.text = "Генерирую SSH-ключ телефона… Это может занять несколько секунд."
                    createKeyButton?.isEnabled = false
                    // RSA-3072 and Android Keystore initialization must never block the UI thread.
                    Thread({
                        val result = try {
                            Result.success(OwnerSshKeys.generateOnce(applicationContext))
                        } catch (e: Exception) {
                            Result.failure<String>(e)
                        }
                        runOnUiThread {
                            createKeyButton?.isEnabled = true
                            result.fold(
                                onSuccess = { key ->
                                    pub.text = key
                                    keyFeedback.text = "SSH-ключ создан и сохранён. Приватная часть остаётся на телефоне."
                                    Toast.makeText(this, "SSH-ключ создан", Toast.LENGTH_LONG).show()
                                },
                                onFailure = { error ->
                                    keyFeedback.text = "Ошибка создания SSH-ключа: ${error.javaClass.simpleName}: ${error.message ?: "нет подробностей"}"
                                    Toast.makeText(this, "Не удалось создать SSH-ключ — причина показана под кнопкой", Toast.LENGTH_LONG).show()
                                }
                            )
                        }
                    }, "executor-ssh-keygen").start()
                }.show()
        }
        button("Копировать публичный ключ") {
            val key = OwnerSshKeys.publicKey(this)
            if (key.isEmpty()) Toast.makeText(this, "Сначала создайте ключ", Toast.LENGTH_SHORT).show()
            else copy(key)
        }
        val known = EditText(this).apply {
            hint = "Проверенная строка known_hosts с компьютера"
            setSingleLine(false)
            minLines = 2
            setText(OwnerSshKeys.knownHosts(this@OwnerSshActivity))
        }
        root.addView(known)
        button("Сохранить ключ сервера") {
            try { OwnerSshKeys.saveKnownHosts(this, known.text.toString()); state.text = "Ключ сервера сохранён" }
            catch (e: Exception) { state.text = "Ошибка: ${e.message}" }
        }
        state = label("Из ссылки ничего автоматически не выполняется.")
        output = label("", 12f).apply { setTextIsSelectable(true); typeface = Typeface.MONOSPACE }
        run = button("Подтвердить и выполнить на Lotus") {
            if (!OwnerSshKeys.hasIdentity(this) || OwnerSshKeys.knownHosts(this).isEmpty()) {
                state.text = "Сначала настройте SSH-ключ телефона и проверенный ключ сервера."
                return@button
            }
            val marker = "${task.id}:${task.sha256}"
            if (prefs.getBoolean(marker, false)) {
                state.text = "Эта заявка уже запускалась. Для нового запуска нужна новая ссылка."
                return@button
            }
            AlertDialog.Builder(this)
                .setTitle("Выполнить Python на Lotus?")
                .setMessage("Будет запущен показанный выше скрипт от имени $username. Он может изменять файлы сайта. Проверьте весь текст перед подтверждением.\n\nSHA-256: ${task.sha256.take(16)}…")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Выполнить") { _, _ ->
                    if (!prefs.edit().putBoolean(marker, true).commit()) {
                        state.text = "Ошибка записи защиты от повторного запуска"
                        return@setPositiveButton
                    }
                    run.isEnabled = false
                    state.text = "Подключение к Lotus…"
                    Thread({ execute() }, "owner-ssh-exec").start()
                }.show()
        }
    }

    private fun execute() {
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
            // Erase temporary PEM bytes only after authentication has completed.
            secret.fill(0)
            secret = null
            channel = session.openChannel("exec") as ChannelExec
            channel.setCommand("python3 -")
            channel.setInputStream(ByteArrayInputStream(task.script.toByteArray(StandardCharsets.UTF_8)))
            val out = CappedOutput()
            val err = CappedOutput()
            channel.setOutputStream(out)
            channel.setErrStream(err)
            channel.connect(15000)
            val end = System.currentTimeMillis() + 200_000
            while (!channel.isClosed) {
                if (System.currentTimeMillis() >= end) throw RuntimeException("Команда превысила лимит 200 секунд")
                Thread.sleep(150)
            }
            val status = channel.exitStatus
            val report = "SSH exit code: $status\n\n" + out.asText() +
                if (err.asText().isNotEmpty()) "\nSTDERR:\n" + err.asText() else ""
            runOnUiThread {
                state.text = if (status == 0) "Команда завершилась успешно" else "Команда завершилась с ошибкой: $status"
                output.text = report
            }
        } catch (e: Exception) {
            runOnUiThread {
                state.text = "Выполнение не подтверждено: ${e.javaClass.simpleName}"
                output.text = (e.message ?: "Ошибка соединения").take(700)
            }
        } finally {
            secret?.fill(0)
            try { channel?.disconnect() } catch (_: Exception) {}
            try { session?.disconnect() } catch (_: Exception) {}
            runOnUiThread { run.isEnabled = false }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private class CappedOutput : OutputStream() {
        private val data = ByteArrayOutputStream()
        @Synchronized override fun write(b: Int) {
            if (data.size() < 64 * 1024) data.write(b)
        }
        @Synchronized override fun write(b: ByteArray, off: Int, len: Int) {
            if (data.size() < 64 * 1024) data.write(b, off, minOf(len, 64 * 1024 - data.size()))
        }
        @Synchronized fun asText(): String = data.toString("UTF-8") +
            if (data.size() >= 64 * 1024) "\n[output truncated]" else ""
    }
}
