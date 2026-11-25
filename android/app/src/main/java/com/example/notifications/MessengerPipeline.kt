package com.example.notifications

import android.content.Context
import com.example.core.AppRole
import com.example.core.AppSettingsRepository
import com.example.core.SenimContract
import com.example.network.LinkCheckRequest
import com.example.network.NetworkClientProvider
import com.example.network.NumberCheckRequest
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Единый формат входящего сообщения от любого источника (раздел 11).
 */
data class IncomingMessage(
  val senderKey: String,
  val senderName: String,
  val text: String,
  val timestamp: Long,
  val source: String,
  val senderKind: String = "phone",
  val inContacts: Boolean = false,
)

interface MessengerSource {
  val sourceName: String
  val messages: Flow<IncomingMessage>
}

sealed interface ChatLinkUiState {
  val url: String

  data class Checking(override val url: String) : ChatLinkUiState
  data class Safe(override val url: String, val reasons: List<String>) : ChatLinkUiState
  data class Blocked(override val url: String, val reasons: List<String>) : ChatLinkUiState
  data class Error(override val url: String, val message: String) : ChatLinkUiState
}

/**
 * Сообщение в интерфейсе тестового чата / бота (живёт только в ОЗУ).
 */
data class ChatPipelineMessage(
  val id: String = UUID.randomUUID().toString(),
  val senderKey: String,
  val senderName: String,
  val text: String,
  val timestamp: Long,
  val source: String,
  val senderKind: String,
  val patternFlags: List<String>,
  val localScore: Float,
  val numberStatus: NumberCheckStatus,
  val links: List<ChatLinkUiState>,
)

/**
 * Запасной источник сообщений для сцены (работает без интернета по сценарию или вручную).
 */
class DemoChatSource : MessengerSource {

  override val sourceName: String = SOURCE_DEMO_CHAT

  private val _messages = MutableSharedFlow<IncomingMessage>(extraBufferCapacity = 32)
  override val messages: Flow<IncomingMessage> = _messages.asSharedFlow()

  fun sendScenarioMessage(
    rawSenderId: String,
    senderName: String,
    senderKind: String,
    text: String,
  ) {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return

    val senderKey =
      if (senderKind == "telegram") {
        PhoneUtils.sha256Hex(rawSenderId)
      } else {
        PhoneUtils.hashNormalizedPhone(rawSenderId) ?: PhoneUtils.sha256Hex(rawSenderId)
      }

    _messages.tryEmit(
      IncomingMessage(
        senderKey = senderKey,
        senderName = senderName,
        text = trimmed,
        timestamp = System.currentTimeMillis(),
        source = SOURCE_DEMO_CHAT,
        senderKind = senderKind,
        inContacts = false,
      )
    )
  }

  companion object {
    const val SOURCE_DEMO_CHAT = "demo_chat"
  }
}

/**
 * Источник уведомлений WhatsApp и Telegram, адаптированный к общему интерфейсу MessengerSource.
 */
object NotificationSource : MessengerSource {

  const val SOURCE_NOTIFICATION = "notification"
  override val sourceName: String = SOURCE_NOTIFICATION

  private val _messages = MutableSharedFlow<IncomingMessage>(extraBufferCapacity = 64)
  override val messages: Flow<IncomingMessage> = _messages.asSharedFlow()

  fun emitNotificationMessage(message: IncomingMessage) {
    _messages.tryEmit(message)
  }
}

/**
 * Единый конвейер для всех источников (раздел 11):
 * окно 10 сообщений -> ScamClassifier -> POST /numbers/check -> извлечение ссылок -> POST /links/check.
 * Текст сообщений не уходит на сервер, не пишется в лог и не сохраняется на диск.
 */
class MessagePipeline(
  context: Context,
  private val settingsRepository: AppSettingsRepository = AppSettingsRepository(context.applicationContext),
  private val networkProvider: NetworkClientProvider = NetworkClientProvider(),
) {

  private val appContext = context.applicationContext
  private val classifier = CompositeScamClassifier(appContext)

  private val _chatMessages = MutableStateFlow<List<ChatPipelineMessage>>(emptyList())
  val chatMessages: StateFlow<List<ChatPipelineMessage>> = _chatMessages.asStateFlow()

  fun process(scope: CoroutineScope, message: IncomingMessage) {
    scope.launch(Dispatchers.IO) {
      val settings = settingsRepository.getCurrentSettings()
      if (settings.selectedRole == AppRole.TRUSTED) return@launch

      val userId = settings.selectedRole?.userId ?: SenimContract.USER_ID_MOM
      val windowSnapshot =
        MessageWindowStore.appendAndSnapshot(
          senderKey = message.senderKey,
          messageText = message.text,
          nowMs = message.timestamp,
        )

      val classification =
        if (windowSnapshot.isNotEmpty()) {
          classifier.classify(windowSnapshot)
        } else {
          ClassifierResult(score = 0f, flags = emptyList())
        }

      val extractedUrls = LinkExtractor.extractNormalizedLinks(message.text)

      // Для notification фиксируем данные в NotificationEventsHolder (без сохранения текста),
      // для demo_chat / telegram_bot добавляем карточку в живой поток чата (ссылки пока «Проверяется...»)
      val numberRecordId =
        if (message.senderKey.isNotBlank()) {
          NotificationEventsHolder.upsertCheckingNumber(
            phoneHash = message.senderKey,
            displaySender = message.senderName,
            messengerLabel = sourceLabel(message.source, message.senderKind),
            patternFlags = classification.flags,
            localScore = classification.score,
            timestampMs = message.timestamp,
          ).id
        } else {
          null
        }

      val chatMessageId =
        if (message.source != NotificationSource.SOURCE_NOTIFICATION) {
          val chatItem =
            ChatPipelineMessage(
              senderKey = message.senderKey,
              senderName = message.senderName,
              text = message.text,
              timestamp = message.timestamp,
              source = message.source,
              senderKind = message.senderKind,
              patternFlags = classification.flags,
              localScore = classification.score,
              numberStatus = NumberCheckStatus.Checking,
              links = extractedUrls.map { ChatLinkUiState.Checking(it) },
            )
          _chatMessages.update { (it + chatItem).takeLast(40) }
          chatItem.id
        } else {
          null
        }

      // 1) POST /numbers/check (только phone_hash, pattern_flags и local_score)
      if (numberRecordId != null) {
        launch {
          val numberOutcome =
            runNumberCheck(
              userId = userId,
              baseUrl = settings.baseUrl,
              apiToken = settings.effectiveApiToken,
              message = message,
              classification = classification,
            )
          NotificationEventsHolder.updateNumberStatus(numberRecordId, numberOutcome)
          if (chatMessageId != null) {
            updateChatNumberStatus(chatMessageId, numberOutcome)
          }
          if (numberOutcome is NumberCheckStatus.HighRisk) {
            LinkWarningNotifier.showSuspiciousNumberWarning(
              context = appContext,
              displaySender = message.senderName,
              messengerLabel = sourceLabel(message.source, message.senderKind),
              reasons = numberOutcome.reasons,
            )
          }
        }
      }

      // 2) POST /links/check для каждой извлечённой нормализованной ссылки (с source и sender_key)
      extractedUrls.forEach { normalizedUrl ->
        val linkRecord =
          if (message.source == NotificationSource.SOURCE_NOTIFICATION) {
            NotificationEventsHolder.addCheckingLink(
              normalizedUrl = normalizedUrl,
              messengerLabel = sourceLabel(message.source, message.senderKind),
              timestampMs = message.timestamp,
              senderKey = message.senderKey,
            )
          } else {
            null
          }

        launch {
          val linkOutcome =
            runLinkCheck(
              userId = userId,
              baseUrl = settings.baseUrl,
              apiToken = settings.effectiveApiToken,
              normalizedUrl = normalizedUrl,
              source = message.source,
              senderKey = message.senderKey,
            )

          if (linkRecord != null) {
            val holderStatus =
              when (linkOutcome) {
                is ChatLinkUiState.Checking -> LinkCheckStatus.Checking
                is ChatLinkUiState.Safe -> LinkCheckStatus.LowRisk(linkOutcome.reasons)
                is ChatLinkUiState.Blocked -> LinkCheckStatus.HighRisk(linkOutcome.reasons)
                is ChatLinkUiState.Error -> LinkCheckStatus.Error(linkOutcome.message)
              }
            NotificationEventsHolder.updateStatus(linkRecord.id, holderStatus)
          }

          if (chatMessageId != null) {
            updateChatLinkStatus(chatMessageId, linkOutcome)
          }

          if (linkOutcome is ChatLinkUiState.Blocked) {
            LinkWarningNotifier.showHighRiskWarning(
              context = appContext,
              url = normalizedUrl,
              messengerLabel = sourceLabel(message.source, message.senderKind),
              reasons = linkOutcome.reasons,
            )
          }
        }
      }
    }
  }

  fun clearChatHistory() {
    _chatMessages.value = emptyList()
  }

  private suspend fun runNumberCheck(
    userId: String,
    baseUrl: String,
    apiToken: String,
    message: IncomingMessage,
    classification: ClassifierResult,
  ): NumberCheckStatus {
    val api =
      networkProvider.getApi(baseUrl = baseUrl, apiToken = apiToken).getOrElse { error ->
        return NumberCheckStatus.Error(
          "Некорректный адрес сервера: ${error.localizedMessage ?: "ошибка"}"
        )
      }

    return try {
      val response =
        api.checkNumber(
          NumberCheckRequest(
            userId = userId,
            phoneHash = message.senderKey,
            senderKind = message.senderKind,
            channel = "message",
            inContacts = message.inContacts,
            patternFlags = classification.flags,
            localScore = classification.score,
          )
        )
      if (!response.isSuccessful) {
        val parsed = networkProvider.parseError(response.errorBody()?.string())
        val details =
          if (parsed != null) "${parsed.code}: ${parsed.message}" else "HTTP ${response.code()}"
        return NumberCheckStatus.Error(details)
      }
      val body = response.body() ?: return NumberCheckStatus.Error("Пустой ответ от сервера")
      if (body.risk == "high") {
        NumberCheckStatus.HighRisk(reasons = body.reasons, sources = body.sources)
      } else {
        NumberCheckStatus.LowRisk(reasons = body.reasons, sources = body.sources)
      }
    } catch (e: Exception) {
      NumberCheckStatus.Error(e.localizedMessage ?: "Сервер недоступен")
    }
  }

  private suspend fun runLinkCheck(
    userId: String,
    baseUrl: String,
    apiToken: String,
    normalizedUrl: String,
    source: String,
    senderKey: String,
  ): ChatLinkUiState {
    val api =
      networkProvider.getApi(baseUrl = baseUrl, apiToken = apiToken).getOrElse { error ->
        return ChatLinkUiState.Error(
          url = normalizedUrl,
          message = "Некорректный адрес сервера: ${error.localizedMessage ?: "ошибка"}",
        )
      }

    return try {
      val response =
        api.checkLink(
          LinkCheckRequest(
            userId = userId,
            url = normalizedUrl,
            source = source,
            senderKey = senderKey,
          )
        )
      if (!response.isSuccessful) {
        val parsed = networkProvider.parseError(response.errorBody()?.string())
        val details =
          if (parsed != null) "${parsed.code}: ${parsed.message}" else "HTTP ${response.code()}"
        return ChatLinkUiState.Error(url = normalizedUrl, message = details)
      }
      val body =
        response.body()
          ?: return ChatLinkUiState.Error(url = normalizedUrl, message = "Пустой ответ от сервера")
      if (body.risk == "high") {
        ChatLinkUiState.Blocked(url = normalizedUrl, reasons = body.reasons)
      } else {
        ChatLinkUiState.Safe(url = normalizedUrl, reasons = body.reasons)
      }
    } catch (e: Exception) {
      ChatLinkUiState.Error(
        url = normalizedUrl,
        message = e.localizedMessage ?: "Сервер недоступен",
      )
    }
  }

  private fun updateChatNumberStatus(messageId: String, status: NumberCheckStatus) {
    _chatMessages.update { list ->
      list.map { item -> if (item.id == messageId) item.copy(numberStatus = status) else item }
    }
  }

  private fun updateChatLinkStatus(messageId: String, updatedLink: ChatLinkUiState) {
    _chatMessages.update { list ->
      list.map { item ->
        if (item.id != messageId) {
          item
        } else {
          item.copy(
            links =
              item.links.map { existing ->
                if (existing.url == updatedLink.url) updatedLink else existing
              }
          )
        }
      }
    }
  }

  private fun sourceLabel(source: String, senderKind: String): String =
    when (source) {
      DemoChatSource.SOURCE_DEMO_CHAT -> "Тестовый чат"
      TelegramBotSource.SOURCE_TELEGRAM_BOT -> "Telegram-бот"
      else -> if (senderKind == "telegram") "Telegram" else "Уведомление"
    }
}
