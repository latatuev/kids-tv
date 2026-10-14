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
                val marker = "LOTUS_SUDO_READY_" + UUID.randomUUID().toString().replace("-", "")
                // Root shell emits marker only if sudo authentication succeeded.
                // No script lines are presented to sudo on a failed password.
                channel.setCommand(
                    "sudo -k -S -p '' -- /bin/sh -c 'printf $marker; exec python3 -'"
                )
                input = channel.outputStream
                channel.connect(15000)
                input.write(sudoPassword)
                input.flush()
                sudoPassword.fill(0)
                // sudoers use_pty can merge child's stderr into SSH stdout.
                // Detect both streams; never send the Python body until the
                // authenticated root shell itself emits the random marker.
                val authDeadline = System.currentTimeMillis() + 25000
                var confirmed = SudoHandshake.confirmed(stdout.asText(), stderr.asText(), marker)
                while (!channel.isClosed && !confirmed) {
                    val outNow = stdout.asText()
                    val errNow = stderr.asText()
                    if (SudoHandshake.passwordRejected(outNow, errNow)) {
                        throw IllegalStateException("SUDO_AUTH_REJECTED")
                    }
                    if (System.currentTimeMillis() >= authDeadline) {
                        throw IllegalStateException("SUDO_AUTH_TIMEOUT")
                    }
                    Thread.sleep(80)
                    confirmed = SudoHandshake.confirmed(stdout.asText(), stderr.asText(), marker)
                }
                if (!confirmed && !SudoHandshake.confirmed(stdout.asText(), stderr.asText(), marker)) {
                    return Result(channel.exitStatus, stdout.asText(),
                        stderr.asText() + "\\nsudo did not authenticate; Python not sent")
                }
                input.write(scriptBytes)
                input.flush()
                input.close()
                input = null
                val end = System.currentTimeMillis() + 200_000
                while (!channel.isClosed) {
                    if (System.currentTimeMillis() >= end) throw IllegalStateException("Execution time limit 200s")
                    Thread.sleep(150)
                }
                return Result(channel.exitStatus, SudoHandshake.redact(stdout.asText(), marker),
                    SudoHandshake.redact(stderr.asText(), marker))
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
