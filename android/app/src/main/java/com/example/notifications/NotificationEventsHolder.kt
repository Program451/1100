package com.example.notifications

import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

sealed interface LinkCheckStatus {
  data object Checking : LinkCheckStatus
  data class LowRisk(val reasons: List<String>) : LinkCheckStatus
  data class HighRisk(val reasons: List<String>) : LinkCheckStatus
  data class Error(val message: String) : LinkCheckStatus
}

sealed interface NumberCheckStatus {
  data object Checking : NumberCheckStatus
  data class LowRisk(val reasons: List<String>, val sources: List<String>) : NumberCheckStatus
  data class HighRisk(val reasons: List<String>, val sources: List<String>) : NumberCheckStatus
  data class Error(val message: String) : NumberCheckStatus
}

sealed interface NumberReportStatus {
  data object Idle : NumberReportStatus
  data object Sending : NumberReportStatus
  data object Reported : NumberReportStatus
  data class Error(val message: String) : NumberReportStatus
}

/**
 * Запись о проверке ссылки из уведомления.
 * Содержит ТОЛЬКО нормализованный URL, источник, время и вердикт сервера — без текста переписки.
 */
data class ExtractedNotificationLink(
  val id: String = UUID.randomUUID().toString(),
  val normalizedUrl: String,
  val messengerLabel: String,
  val timestampMs: Long,
  val status: LinkCheckStatus = LinkCheckStatus.Checking,
  val senderKey: String? = null,
)

/**
 * Запись о проверке неизвестного отправителя (in_contacts=false).
 * Текст переписки здесь НЕ хранится — только phone_hash, флаги и числовой score.
 */
data class CheckedNumberItem(
  val id: String = UUID.randomUUID().toString(),
  val phoneHash: String,
  val displaySender: String,
  val messengerLabel: String,
  val senderKind: String = "phone",
  val channel: String = "message",
  val patternFlags: List<String>,
  val localScore: Float,
  val timestampMs: Long,
  val status: NumberCheckStatus = NumberCheckStatus.Checking,
  val reportStatus: NumberReportStatus = NumberReportStatus.Idle,
)

/**
 * Локальная памятка о входящем видеозвонке с номера не из контактов (хранится только в ОЗУ).
 */
data class VideoCallTipItem(
  val id: String = UUID.randomUUID().toString(),
  val displaySender: String,
  val messengerLabel: String,
  val timestampMs: Long,
)

/**
 * Оперативное хранилище проверенных ссылок и номеров только в ОЗУ (на диск не сохраняется).
 */
object NotificationEventsHolder {

  private const val MAX_ITEMS = 25

  private val _extractedLinks = MutableStateFlow<List<ExtractedNotificationLink>>(emptyList())
  val extractedLinks: StateFlow<List<ExtractedNotificationLink>> = _extractedLinks.asStateFlow()

  private val _checkedNumbers = MutableStateFlow<List<CheckedNumberItem>>(emptyList())
  val checkedNumbers: StateFlow<List<CheckedNumberItem>> = _checkedNumbers.asStateFlow()

  private val _videoCallTips = MutableStateFlow<List<VideoCallTipItem>>(emptyList())
  val videoCallTips: StateFlow<List<VideoCallTipItem>> = _videoCallTips.asStateFlow()

  fun addCheckingLink(
    normalizedUrl: String,
    messengerLabel: String,
    timestampMs: Long,
    senderKey: String? = null,
  ): ExtractedNotificationLink {
    val item =
      ExtractedNotificationLink(
        normalizedUrl = normalizedUrl,
        messengerLabel = messengerLabel,
        timestampMs = timestampMs,
        status = LinkCheckStatus.Checking,
        senderKey = senderKey,
      )
    _extractedLinks.update { current ->
      (listOf(item) + current).take(MAX_ITEMS)
    }
    return item
  }

  fun updateStatus(id: String, status: LinkCheckStatus) {
    _extractedLinks.update { current ->
      current.map { item ->
        if (item.id == id) item.copy(status = status) else item
      }
    }
  }

  fun findById(id: String): ExtractedNotificationLink? =
    _extractedLinks.value.firstOrNull { it.id == id }

  fun upsertCheckingNumber(
    phoneHash: String,
    displaySender: String,
    messengerLabel: String,
    patternFlags: List<String>,
    localScore: Float,
    timestampMs: Long,
    senderKind: String = "phone",
    channel: String = "message",
  ): CheckedNumberItem {
    val existing = _checkedNumbers.value.firstOrNull { it.phoneHash == phoneHash }
    val item =
      CheckedNumberItem(
        id = existing?.id ?: UUID.randomUUID().toString(),
        phoneHash = phoneHash,
        displaySender = displaySender,
        messengerLabel = messengerLabel,
        senderKind = senderKind,
        channel = channel,
        patternFlags = patternFlags,
        localScore = localScore,
        timestampMs = timestampMs,
        status = NumberCheckStatus.Checking,
        reportStatus = existing?.reportStatus ?: NumberReportStatus.Idle,
      )
    _checkedNumbers.update { current ->
      (listOf(item) + current.filterNot { it.phoneHash == phoneHash }).take(MAX_ITEMS)
    }
    return item
  }

  fun updateNumberStatus(id: String, status: NumberCheckStatus) {
    _checkedNumbers.update { current ->
      current.map { item ->
        if (item.id == id) item.copy(status = status) else item
      }
    }
  }

  fun updateNumberReportStatus(id: String, reportStatus: NumberReportStatus) {
    _checkedNumbers.update { current ->
      current.map { item ->
        if (item.id == id) item.copy(reportStatus = reportStatus) else item
      }
    }
  }

  fun findNumberById(id: String): CheckedNumberItem? =
    _checkedNumbers.value.firstOrNull { it.id == id }

  fun addVideoCallTip(
    displaySender: String,
    messengerLabel: String,
    timestampMs: Long,
  ) {
    val item =
      VideoCallTipItem(
        displaySender = displaySender,
        messengerLabel = messengerLabel,
        timestampMs = timestampMs,
      )
    _videoCallTips.update { current ->
      (listOf(item) + current).take(MAX_ITEMS)
    }
  }

  fun clear() {
    _extractedLinks.value = emptyList()
    _checkedNumbers.value = emptyList()
    _videoCallTips.value = emptyList()
    MessageWindowStore.clearAll()
  }
}
