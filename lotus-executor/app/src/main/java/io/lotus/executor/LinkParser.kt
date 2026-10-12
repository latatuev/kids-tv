package io.lotus.executor

import java.net.URI
import java.net.URISyntaxException

object LinkParser {
    data class TestTask(val id: String)

    fun parse(raw: String?): TestTask? {
        if (raw.isNullOrEmpty() || raw.length > 2048) return null
        val uri = try { URI(raw) } catch (_: URISyntaxException) { return null }
        if (!uri.isAbsolute || uri.isOpaque || uri.rawFragment != null || uri.rawUserInfo != null) return null
        if (uri.port != -1) return null
        val recognized = when (uri.scheme) {
            "https" -> uri.host == "lotus-135-106-163-200.sslip.io" && uri.rawPath == "/chat-executor/run"
            "lotusexec" -> uri.host == "run" && (uri.rawPath.isNullOrEmpty() || uri.rawPath == "/")
            else -> false
        }
        if (!recognized) return null
        val rawQuery = uri.rawQuery ?: return null
        if (rawQuery.isEmpty()) return null
        val parts = rawQuery.split('&')
        if (parts.size !in 1..2) return null
        val params = mutableMapOf<String, String>()
        for (part in parts) {
            val equals = part.indexOf('=')
            if (equals < 1 || equals != part.lastIndexOf('=')) return null
            val key = part.substring(0, equals)
            val value = part.substring(equals + 1)
            if (key !in setOf("op", "id") || key in params) return null
            params[key] = value
        }
        if (params["op"] != "ping") return null
        val id = params["id"] ?: "demo"
        if (!Regex("[A-Za-z0-9_-]{1,32}").matches(id)) return null
        return TestTask(id)
    }
}
