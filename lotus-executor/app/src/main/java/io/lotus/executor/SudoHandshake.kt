package io.lotus.executor

/**
 * Helpers for the two-stage sudo protocol. No passwords enter these functions.
 * sudoers use_pty may put the prompt or child output in either SSH stream.
 */
internal object SudoHandshake {
    fun confirmed(stdout: String, stderr: String, marker: String): Boolean =
        stdout.contains(marker) || stderr.contains(marker)

    fun occurrences(stdout: String, stderr: String, marker: String): Int {
        fun count(s: String): Int {
            var found = 0
            var from = 0
            while (true) {
                val at = s.indexOf(marker, from)
                if (at < 0) return found
                found++
                from = at + marker.length
            }
        }
        return count(stdout) + count(stderr)
    }

    fun redact(stream: String, vararg markers: String): String =
        markers.fold(stream) { text, marker -> text.replace(marker, "") }

    fun passwordRejected(stdout: String, stderr: String): Boolean {
        val combined = stdout + "\n" + stderr
        return combined.contains("Sorry, try again.", ignoreCase = true) ||
            combined.contains("incorrect password attempt", ignoreCase = true) ||
            combined.contains("неверный пароль", ignoreCase = true)
    }
}
