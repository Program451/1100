package com.example.network

import android.content.Context
import com.example.core.AppRole
import com.example.core.AppSettingsRepository
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

sealed interface DeviceRegistrationState {
  data object Idle : DeviceRegistrationState
  data object Registering : DeviceRegistrationState
  data class Registered(
    val userId: String,
    val role: String,
    val isRealFcmToken: Boolean,
  ) : DeviceRegistrationState
  data class Error(
    val message: String,
    val isRealFcmToken: Boolean,
  ) : DeviceRegistrationState
}

/**
 * Управляет получением FCM-токена и регистрацией устройства на сервере (POST /devices/register).
 * Безопасно деградирует к запасному токену, если google-services.json ещё не подключён.
 */
class DeviceRegistrationManager(
  context: Context,
  private val settingsRepository: AppSettingsRepository = AppSettingsRepository(context.applicationContext),
  private val networkProvider: NetworkClientProvider = NetworkClientProvider(),
) {

  private val appContext = context.applicationContext

  suspend fun registerDevice(
    role: AppRole,
    baseUrl: String,
    apiToken: String = "",
    overrideToken: String? = null,
    overrideDeviceId: String? = null,
    overridePublicKey: String? = null,
  ): DeviceRegistrationState {
    val (token, isRealFcmToken) =
      if (!overrideToken.isNullOrBlank()) {
        settingsRepository.saveFcmToken(overrideToken)
        overrideToken.trim() to !overrideToken.startsWith("fallback_")
      } else {
        resolveFcmToken()
      }

    val deviceId =
      overrideDeviceId?.trim()?.takeIf { it.isNotEmpty() } ?: settingsRepository.getOrCreateDeviceId()
    val publicKey =
      overridePublicKey?.trim()?.takeIf { it.isNotEmpty() }
        ?: settingsRepository.getOrCreateEd25519PublicKeyBase64()

    val api =
      networkProvider.getApi(baseUrl = baseUrl, apiToken = apiToken).getOrElse { error ->
        return DeviceRegistrationState.Error(
          message = "Некорректный адрес сервера: ${error.localizedMessage ?: "ошибка URL"}",
          isRealFcmToken = isRealFcmToken,
        )
      }

    return try {
      val response =
        api.registerDevice(
          DeviceRegisterRequest(
            userId = role.userId,
            deviceId = deviceId,
            publicKey = publicKey,
            fcmToken = token,
            role = role.roleValue,
          )
        )
      if (response.isSuccessful) {
        val status = response.body()?.status
        if (status == "registered") {
          DeviceRegistrationState.Registered(
            userId = role.userId,
            role = role.roleValue,
            isRealFcmToken = isRealFcmToken,
          )
        } else {
          DeviceRegistrationState.Error(
            message = "Неожиданный статус регистрации: ${status ?: "пусто"}",
            isRealFcmToken = isRealFcmToken,
          )
        }
      } else {
        val parsed = networkProvider.parseError(response.errorBody()?.string())
        val details =
          if (parsed != null) "${parsed.code}: ${parsed.message}" else "HTTP ${response.code()}"
        DeviceRegistrationState.Error(
          message = details,
          isRealFcmToken = isRealFcmToken,
        )
      }
    } catch (e: Exception) {
      DeviceRegistrationState.Error(
        message = e.localizedMessage ?: "Сервер недоступен при регистрации",
        isRealFcmToken = isRealFcmToken,
      )
    }
  }

  /**
   * Вызывается из FirebaseMessagingService при смене FCM-токена.
   */
  suspend fun registerIfRoleSelected(overrideToken: String? = null): DeviceRegistrationState? {
    val settings = settingsRepository.getCurrentSettings()
    val role = settings.selectedRole ?: return null
    return registerDevice(
      role = role,
      baseUrl = settings.baseUrl,
      apiToken = settings.effectiveApiToken,
      overrideToken = overrideToken,
    )
  }

  private suspend fun resolveFcmToken(): Pair<String, Boolean> {
    val hasFirebaseApp =
      runCatching { FirebaseApp.getApps(appContext).isNotEmpty() }.getOrDefault(false)
    if (hasFirebaseApp) {
      val liveToken = runCatching { awaitFcmToken() }.getOrNull()
      if (!liveToken.isNullOrBlank()) {
        settingsRepository.saveFcmToken(liveToken)
        return liveToken to true
      }
    }

    val cachedToken = settingsRepository.getCurrentSettings().fcmToken
    if (!cachedToken.isNullOrBlank()) {
      return cachedToken to !cachedToken.startsWith("fallback_")
    }

    val fallbackToken = settingsRepository.getOrCreateFallbackToken()
    return fallbackToken to false
  }

  private suspend fun awaitFcmToken(): String? =
    suspendCancellableCoroutine { continuation ->
      val task = runCatching { FirebaseMessaging.getInstance().token }.getOrNull()
      if (task == null) {
        if (continuation.isActive) continuation.resume(null)
        return@suspendCancellableCoroutine
      }
      task
        .addOnSuccessListener { token ->
          if (continuation.isActive) continuation.resume(token)
        }
        .addOnFailureListener {
          if (continuation.isActive) continuation.resume(null)
        }
    }
}
