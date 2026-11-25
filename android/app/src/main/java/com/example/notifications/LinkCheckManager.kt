package com.example.notifications

import android.content.Context
import com.example.network.LinkCheckRequest
import com.example.network.NetworkClientProvider

/**
 * Проверка нормализованных ссылок через POST /links/check.
 * На сервер отправляется только адрес ссылки, user_id, source и при наличии sender_key — без текста сообщений.
 */
class LinkCheckManager(
  context: Context,
  private val networkProvider: NetworkClientProvider = NetworkClientProvider(),
) {

  private val appContext = context.applicationContext

  suspend fun checkNotificationLink(
    itemId: String,
    userId: String,
    normalizedUrl: String,
    messengerLabel: String,
    baseUrl: String,
    apiToken: String = "",
    source: String = SOURCE_NOTIFICATION,
    senderKey: String? = null,
  ) {
    NotificationEventsHolder.updateStatus(itemId, LinkCheckStatus.Checking)

    val api =
      networkProvider.getApi(baseUrl = baseUrl, apiToken = apiToken).getOrElse { error ->
        NotificationEventsHolder.updateStatus(
          itemId,
          LinkCheckStatus.Error("Некорректный адрес сервера: ${error.localizedMessage ?: "ошибка"}"),
        )
        return
      }

    try {
      val response =
        api.checkLink(
          LinkCheckRequest(
            userId = userId,
            url = normalizedUrl,
            source = source,
            senderKey = senderKey,
          )
        )
      if (response.isSuccessful) {
        val body = response.body()
        if (body == null) {
          NotificationEventsHolder.updateStatus(
            itemId,
            LinkCheckStatus.Error("Пустой ответ от сервера"),
          )
          return
        }
        if (body.risk == "high") {
          NotificationEventsHolder.updateStatus(
            itemId,
            LinkCheckStatus.HighRisk(body.reasons),
          )
          LinkWarningNotifier.showHighRiskWarning(
            context = appContext,
            url = normalizedUrl,
            messengerLabel = messengerLabel,
            reasons = body.reasons,
          )
        } else {
          NotificationEventsHolder.updateStatus(
            itemId,
            LinkCheckStatus.LowRisk(body.reasons),
          )
        }
      } else {
        val parsed = networkProvider.parseError(response.errorBody()?.string())
        val details =
          if (parsed != null) "${parsed.code}: ${parsed.message}" else "HTTP ${response.code()}"
        NotificationEventsHolder.updateStatus(itemId, LinkCheckStatus.Error(details))
      }
    } catch (e: Exception) {
      NotificationEventsHolder.updateStatus(
        itemId,
        LinkCheckStatus.Error(e.localizedMessage ?: "Сервер недоступен"),
      )
    }
  }

  companion object {
    const val SOURCE_NOTIFICATION = "notification"
  }
}
