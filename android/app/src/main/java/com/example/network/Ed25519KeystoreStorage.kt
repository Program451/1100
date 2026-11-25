package com.example.network

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Защищённое хранилище ключевой пары Ed25519 для офлайн-журнала:
 * - Мастер-ключ AES-256-GCM создаётся и хранится ТОЛЬКО в аппаратном/системном Android Keystore.
 * - Приватный seed Ed25519 никогда не записывается в открытом виде в DataStore или SharedPreferences:
 *   на диске сохраняется исключительно шифротекст, зашифрованный ключом из Android Keystore.
 */
class Ed25519KeystoreStorage(context: Context) {

  private val appContext = context.applicationContext
  private val sealedPrefs by lazy {
    appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
  }

  @Synchronized
  fun getOrCreatePublicKeyBase64(legacyPlaintextSeedB64: String? = null): String {
    val existingPub = sealedPrefs.getString(KEY_PUBLIC_B64, null)
    val hasSealedSeed =
      !sealedPrefs.getString(KEY_SEALED_SEED_B64, null).isNullOrBlank() &&
        !sealedPrefs.getString(KEY_SEALED_IV_B64, null).isNullOrBlank()

    if (!existingPub.isNullOrBlank() && hasSealedSeed) {
      val decoded = runCatching { Ed25519KeyManager.decodeBase64(existingPub) }.getOrNull()
      if (decoded != null && decoded.size == Ed25519KeyManager.RAW_KEY_BYTE_LENGTH) {
        return existingPub
      }
    }

    val legacySeed =
      legacyPlaintextSeedB64?.let { raw ->
        runCatching { Ed25519KeyManager.decodeBase64(raw) }.getOrNull()
      }?.takeIf { it.size == Ed25519KeyManager.RAW_KEY_BYTE_LENGTH }

    val keyPair =
      if (legacySeed != null) {
        Ed25519KeyManager.fromSeed(legacySeed)
      } else {
        Ed25519KeyManager.generateKeyPair()
      }

    try {
      sealPrivateSeedWithAndroidKeystore(
        seed = keyPair.privateSeed,
        publicKeyBase64 = keyPair.publicKeyBase64,
      )
      return keyPair.publicKeyBase64
    } finally {
      keyPair.privateSeed.fill(0)
      legacySeed?.fill(0)
    }
  }

  /**
   * Подписывает данные офлайн-журнала приватным ключом, расшифрованным только в памяти через Android Keystore.
   */
  @Synchronized
  fun signOfflineRecord(payload: ByteArray): ByteArray {
    val sealedB64 =
      sealedPrefs.getString(KEY_SEALED_SEED_B64, null)
        ?: throw IllegalStateException("Ключ Ed25519 ещё не инициализирован в Android Keystore")
    val ivB64 =
      sealedPrefs.getString(KEY_SEALED_IV_B64, null)
        ?: throw IllegalStateException("IV для ключа Ed25519 отсутствует")

    val masterKey = getOrCreateMasterKey()
    val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
    cipher.init(
      Cipher.DECRYPT_MODE,
      masterKey,
      GCMParameterSpec(GCM_TAG_LENGTH_BITS, Ed25519KeyManager.decodeBase64(ivB64)),
    )
    val seed = cipher.doFinal(Ed25519KeyManager.decodeBase64(sealedB64))
    return try {
      Ed25519KeyManager.sign(seed, payload)
    } finally {
      seed.fill(0)
    }
  }

  fun isProtectedInAndroidKeystore(): Boolean =
    runCatching {
      val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
      keyStore.containsAlias(KEYSTORE_ALIAS) &&
        !sealedPrefs.getString(KEY_SEALED_SEED_B64, null).isNullOrBlank()
    }.getOrDefault(false)

  private fun sealPrivateSeedWithAndroidKeystore(seed: ByteArray, publicKeyBase64: String) {
    val masterKey = getOrCreateMasterKey()
    val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
    cipher.init(Cipher.ENCRYPT_MODE, masterKey)
    val iv = cipher.iv
    val ciphertext = cipher.doFinal(seed)

    sealedPrefs
      .edit()
      .putString(KEY_SEALED_IV_B64, Ed25519KeyManager.encodeBase64(iv))
      .putString(KEY_SEALED_SEED_B64, Ed25519KeyManager.encodeBase64(ciphertext))
      .putString(KEY_PUBLIC_B64, publicKeyBase64)
      .apply()
  }

  private fun getOrCreateMasterKey(): SecretKey {
    val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    val existing = (keyStore.getEntry(KEYSTORE_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
    if (existing != null) return existing

    val keyGenerator =
      KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
    val spec =
      KeyGenParameterSpec.Builder(
        KEYSTORE_ALIAS,
        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
      )
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(256)
        .build()
    keyGenerator.init(spec)
    return keyGenerator.generateKey()
  }

  companion object {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEYSTORE_ALIAS = "senim_offline_log_ed25519_master_v1"
    private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH_BITS = 128

    private const val PREFS_NAME = "senim_keystore_sealed_keys"
    private const val KEY_SEALED_IV_B64 = "sealed_iv_b64"
    private const val KEY_SEALED_SEED_B64 = "sealed_seed_b64"
    private const val KEY_PUBLIC_B64 = "ed25519_public_b64"
  }
}
