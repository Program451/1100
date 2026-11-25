package com.example.notifications

import android.os.Build
import android.telecom.Call
import android.telecom.CallScreeningService
import com.example.core.AppRole
import com.example.core.AppSettingsRepository
import com.example.core.SenimContract
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Системная служба проверки входящих звонков (CallScreeningService).
 * Без READ_CONTACTS: на Android 11+ (API 30+) признак контакта берётся из callDetails.contactDisplayName.
 * Никогда не блокирует и не сбрасывает звонок — при risk=high показывает уведомление-предупреждение.
 */
class SenimCallScreeningService : CallScreeningService() {

  private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  override fun onScreenCall(callDetails: Call.Details) {
    // Проверяем только входящие звонки (API 29+ передаёт направление явно)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
      callDetails.callDirection != Call.Details.DIRECTION_INCOMING
    ) {
      respondAllowingCall(callDetails)
      return
    }

    // Если номер уже в контактах (API 30+), не отправляем даже хеш на сервер
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
      !callDetails.contactDisplayName.isNullOrBlank()
    ) {
      respondAllowingCall(callDetails)
      return
    }

    val rawNumber = callDetails.handle?.schemeSpecificPart
    val normalizedPhone =
      rawNumber?.let { PhoneUtils.normalizePhoneNumber(it) }
    if (normalizedPhone == null) {
      respondAllowingCall(callDetails)
      return
    }

    val context = applicationContext
    val phoneHash = PhoneUtils.sha256Hex(normalizedPhone)
    val nowMs = System.currentTimeMillis()

    serviceScope.launch {
      try {
        val settings = AppSettingsRepository(context).getCurrentSettings()
        if (settings.selectedRole == AppRole.TRUSTED) return@launch

        val userId = settings.selectedRole?.userId ?: SenimContract.USER_ID_MOM
        val record =
          NotificationEventsHolder.upsertCheckingNumber(
            phoneHash = phoneHash,
            displaySender = normalizedPhone,
            messengerLabel = CALL_LABEL,
            patternFlags = emptyList(),
            localScore = 0f,
            timestampMs = nowMs,
            senderKind = "phone",
            channel = CHANNEL_CALL,
          )

        val numberCheckManager = NumberCheckManager(context)
        // Ограничиваем ожидание, чтобы система не ждала сеть дольше ~2.5 секунд
        withTimeoutOrNull(SCREENING_TIMEOUT_MS) {
          numberCheckManager.checkNumber(
            itemId = record.id,
            userId = userId,
            phoneHash = phoneHash,
            displaySender = normalizedPhone,
            messengerLabel = CALL_LABEL,
            baseUrl = settings.baseUrl,
            patternFlags = emptyList(),
            localScore = 0f,
            apiToken = settings.effectiveApiToken,
            senderKind = "phone",
            channel = CHANNEL_CALL,
            inContacts = false,
          )
        }
      } finally {
        // Всегда пропускаем звонок — Senim никогда не сбрасывает и не глушит вызов
        respondAllowingCall(callDetails)
      }
    }
  }

  private fun respondAllowingCall(callDetails: Call.Details) {
    respondToCall(callDetails, CallResponse.Builder().build())
  }

  override fun onDestroy() {
    serviceScope.cancel()
    super.onDestroy()
  }

  companion object {
    private const val CHANNEL_CALL = "call"
    private const val CALL_LABEL = "Входящий звонок"
    private const val SCREENING_TIMEOUT_MS = 2_500L
  }
}
