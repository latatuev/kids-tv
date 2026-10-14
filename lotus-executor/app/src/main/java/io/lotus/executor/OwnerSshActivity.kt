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
    private lateinit var copyResult: Button
    private lateinit var sudoMode: CheckBox
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
            val elevated = sudoMode.isChecked
            AlertDialog.Builder(this)
                .setTitle("Выполнить Python на Lotus?")
                .setMessage("Будет запущен показанный скрипт " +
                    (if (elevated) "с правами root после отдельного ввода пароля sudo" else "от имени $username") +
                    ". Проверьте весь текст перед подтверждением.\n\nSHA-256: ${task.sha256.take(16)}…")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Выполнить") { _, _ ->
                    val start: (ByteArray?) -> Unit = { password ->
                        if (!prefs.edit().putBoolean(marker, true).commit()) {
                            password?.fill(0)
                            state.text = "Ошибка записи защиты от повторного запуска"
                        } else {
                            run.isEnabled = false
                            sudoMode.isEnabled = false
                            state.text = "Подключение к Lotus…"
                            output.text = ""
                            copyResult.isEnabled = false
                            Thread({ execute(password) }, "owner-ssh-exec").start()
                        }
                    }
                    if (elevated) SudoSupport.prompt(this) { password -> start(password) }
                    else start(null)
                }.show()
        }
        sudoMode = CheckBox(this).apply {
            text = "Выполнить с sudo (ввод пароля на телефоне)"
            textSize = 15f
            setTextColor(Color.rgb(27, 39, 55))
            isChecked = false
        }
        root.addView(sudoMode)
        state = label("Из ссылки ничего автоматически не выполняется.")
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
        output = label("", 12f).apply { setTextIsSelectable(true); typeface = Typeface.MONOSPACE }
        // Copy exactly the displayed task result, including SSH status and stderr,
        // without copying the executable Python script or any private SSH material.
        copyResult = button("Копировать результат") {
            val report = output.text.toString()
            if (report.isNotBlank()) {
                copy("Задача: ${task.id}\n${state.text}\n\n$report")
            } else {
                Toast.makeText(this, "Результата пока нет", Toast.LENGTH_SHORT).show()
            }
        }
        copyResult.isEnabled = false

    }

    private fun execute(sudoPassword: ByteArray?) {
        try {
            val result = SshRunner.run(applicationContext, task.script, sudoPassword)
            val report = "SSH exit code: ${result.exit}\n\n" + result.stdout +
                if (result.stderr.isNotEmpty()) "\nSTDERR:\n" + result.stderr else ""
            runOnUiThread {
                output.text = report
                copyResult.isEnabled = true
                if (result.exit == 0) {
                    try {
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText(
                            "Lotus Executor result", "Задача: ${task.id}\nКоманда завершилась успешно\n\n$report"
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
                    "SUDO_PROMPT_TIMEOUT" -> "SSH подключён, но sudo не запросил пароль за 10 секунд. Код не отправлен."
                    "SUDO_AUTH_TIMEOUT" -> "Пароль передан sudo, но root не подтвердился за 15 секунд. Код не отправлен."
                    "SUDO_EXIT_BEFORE_READY" -> "sudo завершился без root-подтверждения. Код не отправлен."
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

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

}
