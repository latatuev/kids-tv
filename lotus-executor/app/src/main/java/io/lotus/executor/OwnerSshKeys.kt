package io.lotus.executor

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import java.io.ByteArrayOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Device-owned SSH identity. Private key never sent to ChatGPT, Lotus control plane or GitHub. */
object OwnerSshKeys {
    private const val ALIAS = "lotus-executor-owner-ssh-aes-v1"
    private const val PREF = "lotus-owner-ssh"
    private const val PRIVATE = "private_aes_gcm"
    private const val PUBLIC = "public_openssh"
    private const val HOSTS = "verified_known_hosts"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private fun aesKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
        val existing = store.getKey(ALIAS, null)
        if (existing is SecretKey) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    fun hasIdentity(ctx: Context) =
        !prefs(ctx).getString(PRIVATE, null).isNullOrEmpty()

    fun publicKey(ctx: Context) = prefs(ctx).getString(PUBLIC, "") ?: ""

    @Synchronized
    fun generateOnce(ctx: Context): String {
        if (hasIdentity(ctx)) return publicKey(ctx)
        val jsch = JSch()
        val identity = KeyPair.genKeyPair(jsch, KeyPair.RSA, 3072)
        try {
            val privateBytes = ByteArrayOutputStream()
            val publicBytes = ByteArrayOutputStream()
            identity.writePrivateKey(privateBytes)
            identity.writePublicKey(publicBytes, "lotus-executor-device")
            val privateKey = privateBytes.toByteArray()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, aesKey())
            val encrypted = cipher.doFinal(privateKey)
            privateKey.fill(0)
            val value = Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
            val pub = publicBytes.toString("UTF-8").trim()
            check(pub.startsWith("ssh-rsa ")) { "Unexpected generated public key format" }
            check(prefs(ctx).edit().putString(PRIVATE, value).putString(PUBLIC, pub).commit()) {
                "Cannot persist encrypted SSH identity"
            }
            return pub
        } finally {
            identity.dispose()
        }
    }

    fun privateKey(ctx: Context): ByteArray {
        val wrapped = prefs(ctx).getString(PRIVATE, null)
            ?: error("Generate the device identity first")
        val bytes = Base64.decode(wrapped, Base64.NO_WRAP)
        check(bytes.size > 28) { "Invalid encrypted key" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, aesKey(), GCMParameterSpec(128, bytes, 0, 12))
        return cipher.doFinal(bytes.copyOfRange(12, bytes.size))
    }

    fun knownHosts(ctx: Context) = prefs(ctx).getString(HOSTS, "") ?: ""

    fun saveKnownHosts(ctx: Context, line: String) {
        val trimmed = line.trim()
        // Only a host key copied from the owner's independently verified SSH known_hosts.
        check(trimmed.length in 60..3000 && trimmed.lines().size == 1 &&
            (trimmed.startsWith("135.106.163.200 ") || trimmed.startsWith("|1|"))) {
            "Paste a verified known_hosts entry for 135.106.163.200"
        }
        check(prefs(ctx).edit().putString(HOSTS, trimmed).commit()) {
            "Could not save host fingerprint"
        }
    }
}
