package io.lotus.executor

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.UUID

/** The private P-256 key stays in Android Keystore. Never place Lotus admin credentials in APK. */
object PairingClient {
    private const val ALIAS = "lotus_executor_result_key_v1"
    private const val BASE = "https://lotus-135-106-163-200.sslip.io/executor/v1"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("executor_pair_v1", Context.MODE_PRIVATE)
    private fun b64(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    private fun nonce() = UUID.randomUUID().toString().replace("-", "")

    private fun keyPair(): KeyPair {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val privateKey = store.getKey(ALIAS, null) as? java.security.PrivateKey
        val cert = store.getCertificate(ALIAS)
        if (privateKey != null && cert != null) return KeyPair(cert.publicKey, privateKey)
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        generator.initialize(KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256).build())
        return generator.generateKeyPair()
    }
    private fun sign(bytes: ByteArray): String {
        val s = Signature.getInstance("SHA256withECDSA")
        s.initSign(keyPair().private)
        s.update(bytes)
        return b64(s.sign())
    }
    private fun request(method: String, path: String, payload: JSONObject? = null): Pair<Int, JSONObject> {
        val conn = URL(BASE + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Accept", "application/json")
            if (payload != null) {
                val bytes = payload.toString().toByteArray(Charsets.UTF_8)
                require(bytes.size <= 2048)
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(bytes) }
            }
            val code = conn.responseCode
            val input = if (code in 200..299) conn.inputStream else conn.errorStream
            val raw = input?.bufferedReader()?.use { it.readText().take(4096) } ?: "{}"
            return code to JSONObject(raw)
        } finally { conn.disconnect() }
    }
    /** Explicit user action: creates pending request. Requires separately approved local admin step. */
    fun beginPairing(context: Context): String {
        val n = nonce()
        val proof = sign("lotus-executor-pair-v1\n$n".toByteArray(Charsets.US_ASCII))
        val body = JSONObject().put("public_key_spki", b64(keyPair().public.encoded))
            .put("proof_signature", proof).put("client_nonce", n)
            .put("device_label", "Android Lotus Executor")
        val (status, result) = request("POST", "/pair", body)
        check(status == 201) { "Pairing HTTP $status" }
        val pairing = result.getString("pairing_id")
        val fingerprint = result.getString("fingerprint")
        prefs(context).edit().putString("pairing_id", pairing)
            .putString("fingerprint", fingerprint).apply()
        return "Заявка: " + pairing.take(8) + " · отпечаток: " + fingerprint +
               "\nОжидает подтверждения владельца"
    }
    fun checkPairing(context: Context): String {
        val pairing = prefs(context).getString("pairing_id", null) ?: return "Сначала отправьте заявку"
        val (code, body) = request("GET", "/pair/$pairing")
        check(code == 200) { "Pairing HTTP $code" }
        val state = body.getString("status")
        if (state == "approved") {
            val device = body.getString("device_id")
            val firstApproval = deviceId(context) == null
            prefs(context).edit().putString("device_id", device).commit()
            if (firstApproval) ResultStore.enqueueLastAfterPairing(context)
            return "Телефон зарегистрирован: " + device.take(8)
        }
        return "Регистрация: $state"
    }
    fun deviceId(context: Context): String? = prefs(context).getString("device_id", null)
    /** Called from a constrained background worker only. */
    fun uploadPing(context: Context, task: String, taskNonce: String, timestamp: Long): Int {
        val device = deviceId(context) ?: return 503
        val manufacturer = Build.MANUFACTURER.take(64)
        val model = Build.MODEL.take(64)
        val androidRelease = Build.VERSION.RELEASE.take(64)
        val api = Build.VERSION.SDK_INT
        val canonical = listOf("lotus-executor-result-v1", device, task, "ping", timestamp.toString(),
            taskNonce, manufacturer, model, androidRelease, api.toString()).joinToString("\n")
        val payload = JSONObject().put("device_id", device).put("task_id", task)
            .put("operation", "ping").put("timestamp", timestamp).put("nonce", taskNonce)
            .put("result", JSONObject().put("manufacturer", manufacturer).put("model", model)
                .put("android", androidRelease).put("api_level", api))
            .put("signature", sign(canonical.toByteArray(Charsets.US_ASCII)))
        return request("POST", "/results", payload).first
    }
}
