package io.lotus.executor

import android.content.Context
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/**
 * Dedicated, user-confirmed SSH execution. Elevated mode is never selected
 * by a remote link. Only an on-device masked password dialog enables sudo.
 *
 * A signed, fixed Python relay uses subprocess(sudo) on Lotus. Password and
 * user script are provided ONLY through SSH stdin, never argv or disk. The
 * relay does not forward user code to root before authenticated readiness.
 */
object SshRunner {
    data class Result(val exit: Int, val stdout: String, val stderr: String)
    private const val HOST = "135.106.163.200"
    private const val USER = "sergey"

    fun run(context: Context, script: String, sudoPassword: ByteArray?): Result {
        var session: Session? = null
        var channel: ChannelExec? = null
        var privateKey: ByteArray? = null
        var elevatedPacket: ByteArray? = null
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
                // Proven non-interactive Python SSH stdin path, with sudo handled
                // as a local subprocess on Lotus. No JSch prompt interception.
                val request = SudoPythonBridge.create(context, sudoPassword, scriptBytes)
                elevatedPacket = request.payload
                channel.setCommand(request.command)
                channel.setInputStream(ByteArrayInputStream(request.payload))
                channel.connect(15000)
                sudoPassword.fill(0)
            }

            val end = System.currentTimeMillis() + 220_000
            while (!channel.isClosed) {
                if (System.currentTimeMillis() >= end) {
                    throw IllegalStateException("SSH command exceeded 220 seconds")
                }
                Thread.sleep(150)
            }
            return Result(channel.exitStatus, stdout.asText(), stderr.asText())
        } finally {
            privateKey?.fill(0)
            sudoPassword?.fill(0)
            scriptBytes.fill(0)
            elevatedPacket?.fill(0)
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
