package io.lotus.executor

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
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
    private lateinit var sshStatus: TextView
    private lateinit var sshCreate: Button
    private lateinit var sshCopy: Button
    private var sshBusy = false
    private var sshCheckVersion = 0
    private var lastResult = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createUi()
    }

    override fun onResume() {
        super.onResume()
        refreshResult()
        refreshSshIdentity()
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
        textLine("v0.12 · проверяем sudo перед вводом пароля", 16f)
        val manualPython = Button(this).apply {
            text = "Ввести Python-код для Lotus"
            isAllCaps = false
            isSingleLine = false
            minHeight = dp(54)
            setOnClickListener {
                startActivity(Intent(this@MainActivity, ManualPythonActivity::class.java))
            }
        }
        root.addView(styleAction(manualPython), LinearLayout.LayoutParams(-1, dp(58)))
        textLine("Вставь код, проверь его и подтверди выполнение. Без автоматического запуска.", 13f)
        textLine("SSH-ключ телефона", 21f)
        sshStatus = textLine("Проверяю, создан ли ключ…", 16f)
        sshCreate = Button(this).apply {
            text = "Создать SSH-ключ"
            isAllCaps = false
            isEnabled = false
            setOnClickListener { confirmCreateSshKey() }
        }
        root.addView(styleAction(sshCreate), LinearLayout.LayoutParams(-1, dp(54)))
        sshCopy = Button(this).apply {
            text = "Копировать публичный ключ"
            isAllCaps = false
            isEnabled = false
            visibility = View.GONE
            setOnClickListener {
                val key = OwnerSshKeys.publicKey(this@MainActivity)
                if (key.startsWith("ssh-rsa ")) {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Публичный SSH-ключ Lotus Executor", key))
                    Toast.makeText(this@MainActivity, "Публичный ключ скопирован", Toast.LENGTH_SHORT).show()
                } else {
                    refreshSshIdentity()
                }
            }
        }
        root.addView(styleAction(sshCopy), LinearLayout.LayoutParams(-1, dp(54)))
        textLine("Приватный ключ остаётся на телефоне. Для разрешения SSH-доступа публичный ключ нужно отдельно добавить на Lotus.", 13f)
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

    private fun refreshSshIdentity() {
        if (sshBusy) return
        sshBusy = true
        val version = ++sshCheckVersion
        sshStatus.text = "Проверяю сохранённый ключ…"
        sshCreate.isEnabled = false
        sshCopy.isEnabled = false
        Thread({
            val identity = try {
                OwnerSshKeys.inspectIdentity(applicationContext)
            } catch (e: Exception) {
                OwnerSshKeys.IdentityStatus.Unusable("Ошибка проверки: ${e.javaClass.simpleName}")
            }
            runOnUiThread {
                if (!isFinishing && !isDestroyed && version == sshCheckVersion) {
                    showSshIdentity(identity)
                }
            }
        }, "executor-ssh-identity-check").start()
    }

    private fun showSshIdentity(identity: OwnerSshKeys.IdentityStatus) {
        sshBusy = false
        when (identity) {
            is OwnerSshKeys.IdentityStatus.Absent -> {
                sshStatus.text = "SSH-ключ ещё не создан."
                sshCreate.visibility = View.VISIBLE
                sshCreate.isEnabled = true
                sshCopy.visibility = View.GONE
            }
            is OwnerSshKeys.IdentityStatus.Ready -> {
                sshStatus.text = "SSH-ключ уже создан ✓\nПриватный ключ проверен и доступен на телефоне."
                sshCreate.visibility = View.GONE
                sshCopy.visibility = View.VISIBLE
                sshCopy.isEnabled = true
            }
            is OwnerSshKeys.IdentityStatus.Unusable -> {
                sshStatus.text = "Обнаружен сохранённый ключ, но он недоступен. ${identity.reason}\nАвтоматически заменять его нельзя."
                sshCreate.visibility = View.VISIBLE
                sshCreate.isEnabled = false
                sshCopy.visibility = View.GONE
            }
        }
    }

    private fun confirmCreateSshKey() {
        if (sshBusy) return
        if (OwnerSshKeys.hasIdentity(this)) {
            // Never overwrite an existing SSH identity; verify it and explain its state.
            refreshSshIdentity()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Создать SSH-ключ телефона?")
            .setMessage("Приватный ключ будет храниться зашифрованным на телефоне. Серверу потребуется только публичный ключ. Существующие ключи не заменяются.")
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Создать") { _, _ ->
                sshBusy = true
                val version = ++sshCheckVersion
                sshCreate.isEnabled = false
                sshStatus.text = "Создаю ключ…"
                Thread({
                    val identity = try {
                        OwnerSshKeys.generateOnce(applicationContext)
                        OwnerSshKeys.inspectIdentity(applicationContext)
                    } catch (e: Exception) {
                        OwnerSshKeys.IdentityStatus.Unusable("Ошибка создания: ${e.javaClass.simpleName}: ${e.message ?: "нет подробностей"}")
                    }
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed && version == sshCheckVersion) {
                            showSshIdentity(identity)
                            if (identity is OwnerSshKeys.IdentityStatus.Ready) {
                                Toast.makeText(this, "SSH-ключ телефона готов", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }, "executor-ssh-identity-create").start()
            }
            .show()
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
