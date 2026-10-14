package io.lotus.executor

import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets

/**
 * One-shot masked sudo password. Never persisted, logged, copied, or included
 * in an Intent/URL. Caller must destroy the returned bytes after SSH use.
 */
object SudoSupport {
    fun prompt(activity: Activity, onApproved: (ByteArray) -> Unit) {
        val field = EditText(activity).apply {
            hint = "Пароль sudo пользователя sergey"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            setSingleLine(true)
            isSaveEnabled = false
            setPadding(35, 24, 35, 24)
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Подтвердить sudo на Lotus")
            .setMessage("Этот запуск выполнит проверенный Python-код с правами root. Пароль отправляется только sudo по защищённому SSH, не сохраняется и не попадёт в результат.")
            .setView(field)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Выполнить", null)
            .create()
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val editable = field.text
                if (editable.isEmpty() || editable.length > 256 ||
                    (0 until editable.length).any { editable[it] == '\n' || editable[it] == '\r' || editable[it] == '\u0000' }) {
                    field.error = "Введите пароль без перевода строки (до 256 символов)"
                    return@setOnClickListener
                }
                val chars = CharArray(editable.length) { i -> editable[i] }
                val auth: ByteArray
                try {
                    val buffer = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(chars))
                    auth = ByteArray(buffer.remaining() + 1)
                    buffer.get(auth, 0, auth.size - 1)
                    auth[auth.lastIndex] = 10  // sudo -S newline terminator
                    if (buffer.hasArray()) buffer.array().fill(0)
                } finally {
                    chars.fill('\u0000')
                    editable.clear()
                }
                dialog.dismiss()
                onApproved(auth)
            }
        }
        dialog.show()
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}
