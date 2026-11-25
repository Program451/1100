package com.example.alerts

import com.example.core.AppRole
import com.example.core.AppSettingsRepository
import com.example.network.AlertDto
import com.example.network.DeviceRegistrationManager
import com.example.notifications.LinkWarningNotifier
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Служба Firebase Cloud Messaging для обновления токена устройства на сервере
 * и приёма data-сообщений с тревогами (Alert, все поля строками).
 */
class SenimMessagingService : FirebaseMessagingService() {

  private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  override fun onNewToken(token: String) {
    super.onNewToken(token)
    val context = applicationContext
    serviceScope.launch {
      val settingsRepository = AppSettingsRepository(context)
      settingsRepository.saveFcmToken(token)
      // Повторно регистрируем устройство на сервере при обновлении токена
      DeviceRegistrationManager(
        context = context,
        settingsRepository = settingsRepository,
      ).registerIfRoleSelected(overrideToken = token)
    }
  }

  override fun onMessageReceived(message: RemoteMessage) {
    super.onMessageReceived(message)
    // Приложение никогда не создаёт тревогу само — только разбирает полученную от сервера (все поля строками).
    val data = message.data
    if (data.isEmpty()) return

    val alertId = data["alert_id"]?.trim()?.takeIf { it.isNotEmpty() } ?: return
    val text = data["message"]?.trim()?.takeIf { it.isNotEmpty() } ?: return
    val alert =
      AlertDto(
        alertId = alertId,
        alertType = data["alert_type"].orEmpty(),
        aboutUserId = data["about_user_id"].orEmpty(),
        aboutUserName = data["about_user_name"].orEmpty(),
        transferId = data["transfer_id"]?.trim()?.takeIf { it.isNotEmpty() },
        message = text,
        createdAt = data["created_at"].orEmpty(),
      )

    val context = applicationContext
    serviceScope.launch {
      val settings = AppSettingsRepository(context).getCurrentSettings()
      if (settings.selectedRole != AppRole.TRUSTED) return@launch

      AlertsHolder.upsertFromPush(alert)
      LinkWarningNotifier.showTrustedAlert(
        context = context,
        alertId = alert.alertId,
        alertType = alert.alertType,
        message = alert.message,
      )
    }
  }

  override fun onDestroy() {
    serviceScope.cancel()
    super.onDestroy()
  }
}
