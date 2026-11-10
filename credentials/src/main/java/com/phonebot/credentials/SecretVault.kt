package com.phonebot.credentials

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * SecretVault — Android Keystore-backed secret storage.
 *
 * Design:
 *  - One non-exportable AES-256-GCM master key lives in the Android
 *    Keystore (hardware-backed where a TEE/StrongBox exists).
 *  - Each secret is encrypted with that master key and persisted as
 *    base64(iv) + base64(ciphertext) in `filesDir/secretvault.bin`, which
 *    is restricted to 0600. Values are never written in plaintext and
 *    never logged.
 *  - [retrieve] returns a [SecretHandle]; the raw value is resolved only
 *    when a caller (LlmClient, MCP connection) actually needs it.
 *
 * Used by:
 *  - [com.phonebot.core.LlmClient] for the LLM API key
 *  - MCP connection secrets (Phase 3)
 */
class SecretVault(private val context: Context) {

    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply {
        load(null)
    }
    private val masterKeyAlias = "com.phonebot.master"

    private companion object {
        /** Android Keystore provider name (not exported as a symbol by the JDK API). */
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
    private val vaultFile: File = File(context.filesDir, "secretvault.bin")

    init {
        ensureMasterKey()
        loadFromDisk()
    }

    // ------------------------------------------------------------------
    // Master key (non-exportable, AES-GCM, no user auth for Phase 1)
    // ------------------------------------------------------------------

    private fun ensureMasterKey() {
        if (keyStore.containsAlias(masterKeyAlias)) return
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                masterKeyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        // Generating under AndroidKeyStore stores the key; no setEntry needed.
        gen.generateKey()
    }

    private fun masterKey(): SecretKey =
        keyStore.getKey(masterKeyAlias, null) as SecretKey

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Encrypt + store a secret. [scope] is the owning subsystem
     * ("llm", "mcp:linear", "botd"); secrets are namespaced by scope.
     */
    fun store(name: String, value: String, scope: String = "default") {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, masterKey())
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        entries[entryKey(name, scope)] = VaultEntry(
            name = name,
            scope = scope,
            iv = cipher.iv,
            ciphertext = ciphertext,
            lastUsed = System.currentTimeMillis(),
        )
        persistToDisk()
    }

    /**
     * Return a handle for the named secret. The raw value is resolved
     * lazily and never stored in the UI layer.
     */
    fun retrieve(name: String, scope: String = "default"): SecretHandle {
        val entry = loadEntry(name, scope)
            ?: throw IllegalStateException("no secret '$name' in scope '$scope'")
        return SecretHandle(
            name = name,
            scope = scope,
            lastUsed = entry.lastUsed,
            resolver = { decrypt(entry) },
        )
    }

    /** List stored secrets: name + scope + last-used timestamp. No values. */
    fun list(): List<SecretMeta> = entries.values.map { it.toMeta() }

    /** Delete a secret's encrypted blob (and persist the removal). */
    fun revoke(name: String, scope: String = "default") {
        entries.remove(entryKey(name, scope))
        persistToDisk()
    }

    // ------------------------------------------------------------------
    // Crypto
    // ------------------------------------------------------------------

    private fun decrypt(entry: VaultEntry): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            masterKey(),
            GCMParameterSpec(128, entry.iv),
        )
        return String(cipher.doFinal(entry.ciphertext), Charsets.UTF_8)
    }

    // ------------------------------------------------------------------
    // Persistence: one line per secret, tab-separated base64 fields
    // ------------------------------------------------------------------

    private class VaultEntry(
        val name: String,
        val scope: String,
        val iv: ByteArray,
        val ciphertext: ByteArray,
        var lastUsed: Long,
    ) {
        fun toMeta() = SecretMeta(name = name, scope = scope, lastUsed = lastUsed)
    }

    private val entries = LinkedHashMap<String, VaultEntry>()

    private fun entryKey(name: String, scope: String) = "$scope/$name"

    private fun loadEntry(name: String, scope: String): VaultEntry? =
        entries[entryKey(name, scope)]?.also { it.lastUsed = System.currentTimeMillis() }

    private fun loadFromDisk() {
        if (!vaultFile.exists()) return
        runCatching {
            vaultFile.forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                val parts = line.split('\t')
                if (parts.size != 5) return@forEachLine
                val key = parts[0]
                val slash = key.indexOf('/')
                if (slash <= 0) return@forEachLine
                entries[key] = VaultEntry(
                    name = key.substring(slash + 1),
                    scope = key.substring(0, slash),
                    iv = Base64.decode(parts[1], Base64.NO_WRAP),
                    ciphertext = Base64.decode(parts[2], Base64.NO_WRAP),
                    lastUsed = parts[3].toLongOrNull() ?: 0L,
                )
            }
        }.onFailure {
            // A corrupt vault must not crash the app; start empty rather than
            // risk returning undecryptable secrets.
            entries.clear()
        }
    }

    private fun persistToDisk() {
        val tmp = File(context.filesDir, "secretvault.bin.tmp")
        val body = buildString {
            entries.forEach { (key, e) ->
                append(key).append('\t')
                append(Base64.encodeToString(e.iv, Base64.NO_WRAP)).append('\t')
                append(Base64.encodeToString(e.ciphertext, Base64.NO_WRAP)).append('\t')
                append(e.lastUsed).append('\t')
                append(e.name).append('\n')
            }
        }
        tmp.writeText(body, Charsets.UTF_8)
        restrictPermissions(tmp)
        if (!tmp.renameTo(vaultFile)) {
            vaultFile.writeText(body, Charsets.UTF_8)
            restrictPermissions(vaultFile)
            tmp.delete()
        }
    }

    /** Best-effort 0600: owner read/write only. */
    private fun restrictPermissions(file: File) {
        runCatching {
            file.setReadable(false, false)
            file.setWritable(false, false)
            file.setExecutable(false, false)
            file.setReadable(true, true)
            file.setWritable(true, true)
        }
    }
}

/** Opaque handle — UI layers never see the raw value. */
class SecretHandle internal constructor(
    val name: String,
    val scope: String,
    val lastUsed: Long,
    private val resolver: () -> String,
) {
    /**
     * Resolve the raw secret. Only [com.phonebot.core.LlmClient] and the
     * MCP connection layer call this; no Composable ever takes the
     * returned string.
     */
    fun value(): String = resolver()

    /** Cheap non-disclosing check: does this secret exist and is it fresh? */
    fun isValid(): Boolean = lastUsed > 0
}

/** Display-safe metadata for the credentials list in CapabilitiesScreen. */
data class SecretMeta(val name: String, val scope: String, val lastUsed: Long)
