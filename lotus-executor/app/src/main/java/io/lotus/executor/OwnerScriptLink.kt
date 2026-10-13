package io.lotus.executor

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.GZIPInputStream

/** The script stays in the URL fragment: HTTP servers do not receive fragments. */
object OwnerScriptLink {
    data class Task(val id: String, val script: String, val sha256: String)
    private const val HOST = "lotus-135-106-163-200.sslip.io"
    private const val MAX_URL = 14000
    private const val MAX_SCRIPT = 20 * 1024

    fun parse(raw: String?): Task? {
        if (raw.isNullOrBlank() || raw.length > MAX_URL) return null
        val uri = try { URI(raw) } catch (_: Exception) { return null }
        if (!uri.scheme.equals("https", ignoreCase = true) ||
            uri.host != HOST || uri.port != -1 || uri.rawPath != "/chat-executor/run" ||
            uri.rawUserInfo != null || uri.rawQuery == null) return null
        val parts = uri.rawQuery.split("&")
        if (parts.size != 2) return null
        val values = mutableMapOf<String, String>()
        for (part in parts) {
            val pos = part.indexOf('=')
            if (pos < 1 || pos != part.lastIndexOf('=')) return null
            val key = part.substring(0, pos)
            val value = part.substring(pos + 1)
            if (key !in setOf("op", "id") || values.put(key, value) != null) return null
        }
        if (values["op"] != "owner-ssh") return null
        val id = values["id"] ?: return null
        if (!Regex("[A-Za-z0-9_-]{8,48}").matches(id)) return null
        val fragment = uri.rawFragment ?: return null
        val match = Regex("v1\\.([A-Za-z0-9_-]{1,12000})\\.([a-f0-9]{64})").matchEntire(fragment) ?: return null
        val compressed = try { Base64.getUrlDecoder().decode(match.groupValues[1]) } catch (_: Exception) { return null }
        if (compressed.size > 12000) return null
        val bytes = try {
            GZIPInputStream(ByteArrayInputStream(compressed)).use { gzip ->
                val buffer = ByteArrayOutputStream()
                val chunk = ByteArray(2048)
                while (true) {
                    val n = gzip.read(chunk)
                    if (n < 0) break
                    if (buffer.size() + n > MAX_SCRIPT) return null
                    buffer.write(chunk, 0, n)
                }
                buffer.toByteArray()
            }
        } catch (_: Exception) { return null }
        val script = try {
            StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) { return null }
        if (script.isBlank() || script.indexOf('\u0000') >= 0) return null
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        if (digest != match.groupValues[2]) return null
        return Task(id, script, digest)
    }
}
