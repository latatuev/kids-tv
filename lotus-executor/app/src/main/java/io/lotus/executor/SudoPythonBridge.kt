package io.lotus.executor

import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/**
 * Executes a FIXED, signed-APK Python relay over the same existing SSH channel.
 * Neither password nor user code is placed in argv, environment or files.
 * All sensitive bytes are part of SSH stdin and are cleared after completion.
 */
internal object SudoPythonBridge {
    data class Request(val command: String, val payload: ByteArray)

    fun packet(password: ByteArray, script: ByteArray): ByteArray {
        require(password.size in 2..1100 && password.last() == '\n'.code.toByte()) {
            "Invalid password framing"
        }
        require(script.size in 1..20 * 1024) { "Invalid script length" }
        val bytes = ByteBuffer.allocate(8 + password.size + script.size)
            .order(ByteOrder.BIG_ENDIAN)
        bytes.putInt(password.size)
        bytes.putInt(script.size)
        bytes.put(password)
        bytes.put(script)
        return bytes.array()
    }

    fun create(context: Context, password: ByteArray, script: ByteArray): Request {
        // Fixed source ships INSIDE the signed APK, not fetched from the network.
        val source = context.assets.open("sudo_bridge.py").bufferedReader().use { it.readText() }
        require(source.length in 1000..12000 && source.contains("LOTUS_SUDO_BRIDGE")) {
            "Signed sudo bridge resource missing or malformed"
        }
        val encoded = Base64.getEncoder().encodeToString(source.toByteArray(Charsets.UTF_8))
        // Command contains ONLY fixed relay code; no user script or password.
        val command = "python3 -c 'import base64;exec(compile(base64.b64decode(\"" +
            encoded + "\"),\"<lotus-sudo-bridge>\",\"exec\"))'"
        return Request(command, packet(password, script))
    }
}
