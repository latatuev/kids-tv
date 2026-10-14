package io.lotus.executor

/**
 * No secret material enters these helpers. A random marker is printed only by
 * the root shell after sudo authenticates; sudoers use_pty may merge stdout
 * and stderr, so either SSH stream is valid evidence of readiness.
 */
internal object SudoHandshake {
    fun confirmed(stdout: String, stderr: String, marker: String): Boolean =
        stdout.contains(marker) || stderr.contains(marker)

    fun redact(stream: String, marker: String): String = stream.replace(marker, "")

    fun passwordRejected(stdout: String, stderr: String): Boolean {
        val combined = stdout + "\n" + stderr
        return combined.contains("Sorry, try again.", ignoreCase = true) ||
            combined.contains("incorrect password attempt", ignoreCase = true) ||
            combined.contains("неверный пароль", ignoreCase = true)
    }
}
