package com.wysebee.agent.android.core

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Where the link's secret is kept: the Android Keystore on a phone (device/KeystoreSecrets.kt),
 *  memory in tests. Never in the state file, never in a log line. */
interface SecretStore {
    fun get(): String?
    fun set(secret: String)
    fun clear()
}

class MemorySecrets : SecretStore {
    @Volatile private var value: String? = null
    override fun get(): String? = value
    override fun set(secret: String) { value = secret }
    override fun clear() { value = null }
}

object Secrets {
    /** 32 random bytes, base64url without padding (the protocol's extension secret). */
    fun newSecret(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(32).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
