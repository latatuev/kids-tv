package io.lotus.executor

import android.content.Context
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Dedicated, user-confirmed SSH execution. Elevated mode is never selected
 * by a remote link. Only an on-device masked password dialog enables sudo.
 *
 * Password travels once via SSH stdin to sudo -S. The Python body is withheld
 * until a unique root-side readiness marker is actually observed.
 */
object SshRunner {
    data class Result(val exit: Int, val stdout: String, val stderr: String)
    private const val HOST = "135.106.163.200"
    private const val USER = "sergey"

    fun run(context: Context, script: String, sudoPassword: ByteArray?): Result {
        var session: Session? = null
        var channel: ChannelExec? = null
        var privateKey: ByteArray? = null
        var input: OutputStream? = null
        val scriptBytes = script.toByteArray(StandardCharsets.UTF_8)
        try {
            val jsch = JSch()
            jsch.setKnownHosts(ByteArrayInputStream(
                (OwnerSshKeys.knownHosts(context) + "\n").toByteArray(StandardCharsets.UTF_8)
            ))
            privateKey = OwnerSshKeys.privateKey(context)
            jsch.addIdentity("lotus-owner-device", privateKey, null, null)
            session = jsch.getSession(USER, HOST, 22)
            session.setConfig("StrictHostKeyChecking", "yes")
            session.setConfig("PreferredAuthentications", "publickey")
            session.connect(15000)
            privateKey.fill(0)
            privateKey = null
            channel = session.openChannel("exec") as ChannelExec
            val stdout = CappedOutput()
            val stderr = CappedOutput()
            channel.setOutputStream(stdout)
            channel.setErrStream(stderr)

            if (sudoPassword == null) {
                channel.setCommand("python3 -")
                channel.setInputStream(ByteArrayInputStream(scriptBytes))
                channel.connect(15000)
            } else {
                val nonce = UUID.randomUUID().toString().replace("-", "")
                val prompt = "LOTUS_SUDO_PROMPT_" + nonce
                val marker = "LOTUS_SUDO_READY_" + nonce
                // The prompt is a distinct handshake message from sudo itself.
                // Only after receiving it do we send a password. The root marker
                // is emitted by the authenticated shell, BEFORE receiving Python.
                // No SSH PTY is requested: terminal echo must not expose passwords.
                channel.setCommand(
                    "sudo -k -S -p '$prompt' -- /bin/sh -c 'printf $marker; exec python3 -'"
                )
                input = channel.outputStream
                channel.connect(15000)
                val promptDeadline = System.currentTimeMillis() + 10000
                while (!channel.isClosed && !SudoHandshake.confirmed(
                        stdout.asText(), stderr.asText(), prompt
                    ) && !SudoHandshake.confirmed(stdout.asText(), stderr.asText(), marker)) {
                    if (System.currentTimeMillis() >= promptDeadline) {
                        throw IllegalStateException("SUDO_PROMPT_TIMEOUT")
                    }
                    Thread.sleep(80)
                }
                val prompted = SudoHandshake.confirmed(stdout.asText(), stderr.asText(), prompt)
                var ready = SudoHandshake.confirmed(stdout.asText(), stderr.asText(), marker)
                if (prompted && !ready) {
                    input.write(sudoPassword)
                    input.flush()
                    sudoPassword.fill(0)
                    val authDeadline = System.currentTimeMillis() + 15000
                    while (!channel.isClosed && !ready) {
                        val outNow = stdout.asText()
                        val errNow = stderr.asText()
                        // A second custom password prompt is definitive refusal.
                        if (SudoHandshake.passwordRejected(outNow, errNow) ||
                            SudoHandshake.occurrences(outNow, errNow, prompt) > 1) {
                            throw IllegalStateException("SUDO_AUTH_REJECTED")
                        }
                        if (System.currentTimeMillis() >= authDeadline) {
                            throw IllegalStateException("SUDO_AUTH_TIMEOUT")
                        }
                        Thread.sleep(80)
                        ready = SudoHandshake.confirmed(stdout.asText(), stderr.asText(), marker)
                    }
                }
                if (!ready) {
                    throw IllegalStateException("SUDO_EXIT_BEFORE_READY")
                }
                // Root is confirmed. No sudo prompt can consume Python source now.
                input.write(scriptBytes)
                input.flush()
                input.close()
                input = null
                val end = System.currentTimeMillis() + 200_000
                while (!channel.isClosed) {
                    if (System.currentTimeMillis() >= end) throw IllegalStateException("Execution time limit 200s")
                    Thread.sleep(150)
                }
                return Result(channel.exitStatus,
                    SudoHandshake.redact(stdout.asText(), marker, prompt),
                    SudoHandshake.redact(stderr.asText(), marker, prompt))
            }

            val end = System.currentTimeMillis() + 200_000
            while (!channel.isClosed) {
                if (System.currentTimeMillis() >= end) throw IllegalStateException("Execution time limit 200s")
                Thread.sleep(150)
            }
            return Result(channel.exitStatus, stdout.asText(), stderr.asText())
        } finally {
            privateKey?.fill(0)
            sudoPassword?.fill(0)
            scriptBytes.fill(0)
            try { input?.close() } catch (_: Exception) {}
            try { channel?.disconnect() } catch (_: Exception) {}
            try { session?.disconnect() } catch (_: Exception) {}
        }
    }

    private class CappedOutput : OutputStream() {
        private val bytes = ByteArrayOutputStream()
        @Synchronized override fun write(b: Int) {
            if (bytes.size() < 65536) bytes.write(b)
        }
        @Synchronized override fun write(b: ByteArray, off: Int, len: Int) {
            if (bytes.size() < 65536) bytes.write(b, off, minOf(len, 65536 - bytes.size()))
        }
        @Synchronized fun contains(s: String) = bytes.toString("UTF-8").contains(s)
        @Synchronized fun asText(): String = bytes.toString("UTF-8") +
            if (bytes.size() >= 65536) "\n[output truncated]" else ""
    }
}
