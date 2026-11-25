package com.example.notifications

import android.app.Notification
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.example.core.AppRole
import com.example.core.AppSettingsRepository
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Служба чтения уведомлений WhatsApp и Telegram.
 * Подключена к общему конвейеру через NotificationSource / MessagePipeline.
 * При входящем видеозвонке с номера не из контактов показывает локальную памятку без выхода в сеть.
 */
class SenimNotificationListenerService : NotificationListenerService() {

  private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private lateinit var messagePipeline: MessagePipeline

  override fun onCreate() {
    super.onCreate()
    messagePipeline = MessagePipeline(applicationContext)
    serviceScope.launch {
      NotificationSource.messages.collect { incoming ->
        messagePipeline.process(serviceScope, incoming)
      }
    }
  }

  override fun onNotificationPosted(sbn: StatusBarNotification?) {
    if (sbn == null) return
    val messengerLabel = SUPPORTED_PACKAGES[sbn.packageName] ?: return
    val notification = sbn.notification ?: return

    // Игнорируем сводные групповые уведомления, чтобы не дублировать старые сообщения
    if ((notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return

    val extras = notification.extras ?: return
    val senderTitle = extractSenderTitle(extras)
    val messageTexts = extractMessageTexts(extras)

    val isPhoneSender = PhoneUtils.looksLikePhoneNumber(senderTitle)
    val normalizedPhone =
      if (isPhoneSender && senderTitle != null) PhoneUtils.normalizePhoneNumber(senderTitle) else null

    val context = applicationContext
    val postedAt = sbn.postTime.takeIf { it > 0L } ?: System.currentTimeMillis()

    // Локальная памятка при видеозвонке с номера не из контактов (без запросов на сервер)
    if (isIncomingVideoCall(notification, messageTexts, senderTitle) && normalizedPhone != null) {
      serviceScope.launch {
        val settings = AppSettingsRepository(context).getCurrentSettings()
        if (settings.selectedRole == AppRole.TRUSTED) return@launch

        NotificationEventsHolder.addVideoCallTip(
          displaySender = normalizedPhone,
          messengerLabel = messengerLabel,
          timestampMs = postedAt,
        )
        LinkWarningNotifier.showVideoCallChecklist(
          context = context,
          displaySender = normalizedPhone,
          messengerLabel = messengerLabel,
          familyCodeWord = settings.familyCodeWord,
        )
      }
      return
    }

    val combinedText = messageTexts.joinToString("\n").trim()
    val extractedUrls = LinkExtractor.extractNormalizedLinks(combinedText)
    val phoneHash = normalizedPhone?.let { PhoneUtils.sha256Hex(it) }

    if (extractedUrls.isEmpty() && phoneHash == null) {
      return
    }

    // Если номер неизвестен — отправляем через общий NotificationSource -> MessagePipeline.
    // Если отправитель из контактов, но есть ссылка — проверяем только ссылку без фейкового phone_hash.
    if (phoneHash != null) {
      NotificationSource.emitNotificationMessage(
        IncomingMessage(
          senderKey = phoneHash,
          senderName = normalizedPhone,
          text = combinedText,
          timestamp = postedAt,
          source = NotificationSource.SOURCE_NOTIFICATION,
          senderKind = "phone",
          inContacts = false,
        )
      )
    } else if (extractedUrls.isNotEmpty()) {
      serviceScope.launch {
        val settings = AppSettingsRepository(context).getCurrentSettings()
        if (settings.selectedRole == AppRole.TRUSTED) return@launch
        val userId = settings.selectedRole?.userId ?: com.example.core.SenimContract.USER_ID_MOM
        val linkCheckManager = LinkCheckManager(context)

        extractedUrls.forEach { normalizedUrl ->
          val record =
            NotificationEventsHolder.addCheckingLink(
              normalizedUrl = normalizedUrl,
              messengerLabel = messengerLabel,
              timestampMs = postedAt,
            )
          launch {
            linkCheckManager.checkNotificationLink(
              itemId = record.id,
              userId = userId,
              normalizedUrl = normalizedUrl,
              messengerLabel = messengerLabel,
              baseUrl = settings.baseUrl,
              apiToken = settings.effectiveApiToken,
              source = NotificationSource.SOURCE_NOTIFICATION,
            )
          }
        }
      }
    }
  }

  private fun isIncomingVideoCall(
    notification: Notification,
    messageTexts: List<String>,
    senderTitle: String?,
  ): Boolean {
    val combinedLower =
      (messageTexts + listOfNotNull(senderTitle))
        .joinToString(" ")
        .lowercase(Locale.ROOT)
    val isMissed =
      combinedLower.contains("пропущен") || combinedLower.contains("missed")
    if (isMissed) return false

    val hasCallCategory = notification.category == Notification.CATEGORY_CALL
    val hasVideoKeyword =
      VIDEO_CALL_KEYWORDS.any { keyword -> combinedLower.contains(keyword) }

    return hasCallCategory || hasVideoKeyword
  }

  private fun extractSenderTitle(extras: Bundle): String? {
    val convTitle = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()?.trim()
    val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()
    return title?.takeIf { it.isNotEmpty() } ?: convTitle?.takeIf { it.isNotEmpty() }
  }

  private fun extractMessageTexts(extras: Bundle): List<String> =
    buildList {
      extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.let(::add)
      extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.let(::add)
      extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.forEach { line ->
        line?.toString()?.let(::add)
      }
      @Suppress("DEPRECATION")
      val rawMessages = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
      rawMessages?.forEach { item ->
        val msgBundle = item as? Bundle ?: return@forEach
        msgBundle.getCharSequence("text")?.toString()?.let(::add)
      }
    }

  override fun onDestroy() {
    serviceScope.cancel()
    super.onDestroy()
  }

  companion object {
    private val VIDEO_CALL_KEYWORDS =
      listOf("видеозвонок", "видеовызов", "video call", "videocall", "бейнеқоңырау")

    private val SUPPORTED_PACKAGES =
      mapOf(
        "com.whatsapp" to "WhatsApp",
        "com.whatsapp.w4b" to "WhatsApp Business",
        "org.telegram.messenger" to "Telegram",
        "org.telegram.messenger.web" to "Telegram",
        "org.telegram.plus" to "Telegram",
      )
  }
}
