package com.example.core

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.network.Ed25519KeystoreStorage
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.senimDataStore: DataStore<Preferences> by preferencesDataStore(name = "senim_settings")

data class AppSettings(
  val baseUrl: String = SenimContract.DEFAULT_BASE_URL,
  val apiAccessToken: String = "",
  val selectedRole: AppRole? = null,
  val fcmToken: String? = null,
  // Семейное кодовое слово хранится ТОЛЬКО на телефоне и никогда не отправляется на сервер
  val familyCodeWord: String = "",
  // Телефон близкого для кнопки «Позвонить» (ACTION_DIAL), хранится локально
  val trustedContactPhone: String = "",
) {
  val effectiveApiToken: String
    get() = apiAccessToken.trim().ifBlank { SenimContract.defaultApiToken() }
}

/**
 * Хранилище локальных настроек приложения (базовый URL сервера, режим, FCM-токен и кодовое слово).
 */
class AppSettingsRepository(private val context: Context) {

  private val keystoreStorage by lazy { Ed25519KeystoreStorage(context) }

  private object Keys {
    val BASE_URL = stringPreferencesKey("base_url")
    val API_ACCESS_TOKEN = stringPreferencesKey("api_access_token")
    val APP_ROLE = stringPreferencesKey("app_role")
    val FCM_TOKEN = stringPreferencesKey("fcm_token")
    val DEVICE_ID = stringPreferencesKey("device_id")
    // Устаревшие ключи DataStore очищаются автоматически при переносе в Android Keystore
    val LEGACY_ED25519_PRIVATE_SEED_B64 = stringPreferencesKey("ed25519_private_seed_b64")
    val LEGACY_ED25519_PUBLIC_KEY_B64 = stringPreferencesKey("ed25519_public_key_b64")
    val FALLBACK_DEVICE_ID = stringPreferencesKey("fallback_device_id")
    val FAMILY_CODE_WORD = stringPreferencesKey("family_code_word")
    val TRUSTED_CONTACT_PHONE = stringPreferencesKey("trusted_contact_phone")
  }

  val settingsFlow: Flow<AppSettings> =
    context.senimDataStore.data.map { prefs ->
      val savedUrl = prefs[Keys.BASE_URL]?.takeIf { it.isNotBlank() } ?: SenimContract.DEFAULT_BASE_URL
      val savedApiToken = prefs[Keys.API_ACCESS_TOKEN].orEmpty()
      val savedRole = AppRole.fromRoleValue(prefs[Keys.APP_ROLE])
      val savedToken = prefs[Keys.FCM_TOKEN]?.takeIf { it.isNotBlank() }
      val savedCodeWord = prefs[Keys.FAMILY_CODE_WORD].orEmpty()
      val savedTrustedPhone = prefs[Keys.TRUSTED_CONTACT_PHONE].orEmpty()
      AppSettings(
        baseUrl = savedUrl,
        apiAccessToken = savedApiToken,
        selectedRole = savedRole,
        fcmToken = savedToken,
        familyCodeWord = savedCodeWord,
        trustedContactPhone = savedTrustedPhone,
      )
    }

  suspend fun getCurrentSettings(): AppSettings = settingsFlow.first()

  suspend fun saveBaseUrl(rawUrl: String): String {
    val normalized = normalizeBaseUrl(rawUrl)
    context.senimDataStore.edit { prefs ->
      prefs[Keys.BASE_URL] = normalized
    }
    return normalized
  }

  suspend fun saveApiAccessToken(rawToken: String) {
    val trimmed = rawToken.trim()
    context.senimDataStore.edit { prefs ->
      if (trimmed.isEmpty()) {
        prefs.remove(Keys.API_ACCESS_TOKEN)
      } else {
        prefs[Keys.API_ACCESS_TOKEN] = trimmed
      }
    }
  }

  suspend fun saveRole(role: AppRole?) {
    context.senimDataStore.edit { prefs ->
      if (role == null) {
        prefs.remove(Keys.APP_ROLE)
      } else {
        prefs[Keys.APP_ROLE] = role.roleValue
      }
    }
  }

  suspend fun saveFcmToken(token: String) {
    val trimmed = token.trim()
    if (trimmed.isEmpty()) return
    context.senimDataStore.edit { prefs ->
      prefs[Keys.FCM_TOKEN] = trimmed
    }
  }

  suspend fun saveFamilyCodeWord(word: String) {
    val trimmed = word.trim()
    context.senimDataStore.edit { prefs ->
      if (trimmed.isEmpty()) {
        prefs.remove(Keys.FAMILY_CODE_WORD)
      } else {
        prefs[Keys.FAMILY_CODE_WORD] = trimmed
      }
    }
  }

  suspend fun saveTrustedContactPhone(phone: String) {
    val trimmed = phone.trim()
    context.senimDataStore.edit { prefs ->
      if (trimmed.isEmpty()) {
        prefs.remove(Keys.TRUSTED_CONTACT_PHONE)
      } else {
        prefs[Keys.TRUSTED_CONTACT_PHONE] = trimmed
      }
    }
  }

  /**
   * Стабильный запасной идентификатор устройства, если google-services.json не подключён
   * и Firebase Messaging ещё не инициализирован.
   */
  suspend fun getOrCreateFallbackToken(): String {
    val existing = context.senimDataStore.data.first()[Keys.FALLBACK_DEVICE_ID]
    if (!existing.isNullOrBlank()) return "fallback_$existing"
    val generated = UUID.randomUUID().toString()
    context.senimDataStore.edit { prefs ->
      prefs[Keys.FALLBACK_DEVICE_ID] = generated
    }
    return "fallback_$generated"
  }

  /**
   * Постоянный device_id устройства для регистрации и офлайн-журнала.
   */
  suspend fun getOrCreateDeviceId(): String {
    val existing = context.senimDataStore.data.first()[Keys.DEVICE_ID]
    if (!existing.isNullOrBlank()) return existing
    val generated = UUID.randomUUID().toString()
    context.senimDataStore.edit { prefs ->
      prefs[Keys.DEVICE_ID] = generated
    }
    return generated
  }

  /**
   * Постоянный публичный ключ Ed25519 (Base64 от 32 сырых байт) для офлайн-журнала.
   * Приватный ключ защищён в Android Keystore и никогда не хранится в открытом виде в DataStore.
   */
  suspend fun getOrCreateEd25519PublicKeyBase64(): String {
    val prefs = context.senimDataStore.data.first()
    val legacySeed = prefs[Keys.LEGACY_ED25519_PRIVATE_SEED_B64]
    val hasLegacyPub = prefs[Keys.LEGACY_ED25519_PUBLIC_KEY_B64] != null

    val publicKeyB64 = keystoreStorage.getOrCreatePublicKeyBase64(legacySeed)
    if (legacySeed != null || hasLegacyPub) {
      context.senimDataStore.edit { mutablePrefs ->
        mutablePrefs.remove(Keys.LEGACY_ED25519_PRIVATE_SEED_B64)
        mutablePrefs.remove(Keys.LEGACY_ED25519_PUBLIC_KEY_B64)
      }
    }
    return publicKeyB64
  }

  companion object {
    fun normalizeBaseUrl(rawUrl: String): String {
      val trimmed = rawUrl.trim().trimEnd('/')
      if (trimmed.isEmpty()) return SenimContract.DEFAULT_BASE_URL
      return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
        trimmed
      } else {
        "http://$trimmed"
      }
    }
  }
}
