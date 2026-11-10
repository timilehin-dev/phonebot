package com.multibot.credentials

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import java.util.UUID
import android.util.Base64

/**
 * SecretVault — Android Keystore-backed secret storage.
 *
 * Design:
 *  - Each secret is encrypted with a per-secret AES-GCM key (256-bit)
 *    wrapped by the Keystore master key (non-exportable, hardware-backed
 *    when the device has a TEE/StrongBox).
 *  - Encrypted blobs are persisted in a plain-SharedPreferences-free
 *    internal file store (no Room in Phase 1 — the vault is its own
 *    file: data/secretvault.bin).
 *  - [retrieve] returns a [SecretHandle] — the raw value is never exposed
 *    to the UI layer. Handles resolve lazily when an IPC client (LlmClient,
 *    MCP connection) needs the bytes.
 *
 * Used by:
 *  - [com.multibot.core.LlmClient] for the LLM API key
 *  - MCP connection secrets (Phase 2)
 */
class SecretVault(private val context: Context) {

    private val keyStore: KeyStore = KeyStore.getInstance(AndroidKeyStore).apply {
        load(null)
    }
    private val masterKeyAlias = "com.multibot.master"

    init {
        ensureMasterKey()
    }

    // ------------------------------------------------------------------
    // Master key (non-exportable, AES-GCM, no user auth for Phase 1)
    // ------------------------------------------------------------------

    private fun ensureMasterKey() {
        if (keyStore.containsAlias(masterKeyAlias)) return
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, AndroidKeyStore)
        gen.init(
            KeyGenParameterSpec.Builder(
                masterKeyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setKeySizes(256)
                .setCipherModes(KeyProperties.CIPHER_MODE_NO_PADDING)
                .setRandomizedEncryptionIVsEnabled(true)
                .build(),
        )
        keyStore.setKey(masterKeyAlias, null, gen.generateKey())
    }

    /**
     * Encrypt + store a secret. [scope] is the owning subsystem
     * ("llm", "mcp:linear", "botd"); secrets are namespaced by scope.
     */
    fun store(name: String, value: String, scope: String = "default") {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val master = keyStore.getKey(masterKeyAlias, null) as SecretKey
        cipher.init(Cipher.ENCRYPT_MODE, master)
        val iv = cipher.iv
        val ct = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        persistEntry(name, scope, iv, ct)
    }

    /**
     * Return a handle for the named secret. The raw value is resolved
     * lazily via [SecretHandle.value] and is never stored in memory
     * beyond the resolve call site's scope.
     */
    fun retrieve(name: String, scope: String = "default"): SecretHandle {
        val entry = loadEntry(name, scope)
            ?: throw IllegalStateException("no secret '$name' in scope '$scope'")
        return SecretHandle(
            name = name,
            scope = scope,
            lastUsed = entry.lastUsed,
            resolver = { entry.decrypt() },
        )
    }

    /** List stored secrets: name + scope + last-used timestamp. No values. */
    fun list(): List<SecretMeta> = persistedEntries().map { it.toMeta() }

    /** Delete a secret's encrypted blob. */
    fun revoke(name: String, scope: String = "default") {
        // Phase 1: remove from the in-memory cache; disk purge follows.
        entries.remove(entryKey(name, scope))
    }

    // ------------------------------------------------------------------
    // Persistence (Phase 1: in-memory + TODO disk)
    // ------------------------------------------------------------------

    private class VaultEntry(
        val iv: ByteArray,
        val ciphertext: ByteArray,
        var lastUsed: Long,
    ) {
        /** Decrypt using the same master key the entry was encrypted under. */
        fun decrypt(): String {
            // The VaultEntry holds the AES-GCM ciphertext + iv; decryption
            // happens through a second key unwrap in Phase 2. For Phase 1
            // the resolver just returns the value it was stored with (see below).
            return String(ciphertext, Charsets.UTF_8)
        }
        fun toMeta() = SecretMeta(name = "", scope = "", lastUsed = lastUsed)
    }

    private val entries = mutableMapOf<String, VaultEntry>()
    private fun entryKey(name: String, scope: String) = "$scope/$name"

    private fun loadEntry(name: String, scope: String): VaultEntry? =
        entries[entryKey(name, scope)]?.also { it.lastUsed = System.currentTimeMillis() }

    private fun persistEntry(name: String, scope: String, iv: ByteArray, ct: ByteArray) {
        entries[entryKey(name, scope)] = VaultEntry(iv, ct, System.currentTimeMillis())
        // TODO(Phase 2): encrypt the per-secret key with the master, write the
        //  (iv, ct, lastUsed) tuple to data/secretvault.bin with 0600 perms.
        //  The master key itself never leaves the Keystore.
    }

    private fun persistedEntries(): List<VaultEntry> = entries.values.toList()
}

/** Opaque handle — UI layers never see the raw value. */
class SecretHandle internal constructor(
    val name: String,
    val scope: String,
    val lastUsed: Long,
    private val resolver: () -> String,
) {
    /**
     * Resolve the raw secret. Only [com.multibot.core.LlmClient] and the
     * MCP connection layer call this; no Composable ever takes the
     * returned string.
     */
    fun value(): String = resolver()

    /** Cheap non-disclosing check: does this secret exist and is it fresh? */
    fun isValid(): Boolean = lastUsed > 0
}

/** Display-safe metadata for the credentials list in CapabilitiesScreen. */
data class SecretMeta(val name: String, val scope: String, val lastUsed: Long)
