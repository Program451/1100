package com.example.notifications

import android.content.Context
import com.example.network.NetworkClientProvider
import com.example.network.NumberCheckRequest
import com.example.network.NumberReportRequest

/**
 * Проверка неизвестных номеров (POST /numbers/check) и отправка жалоб (POST /numbers/report).
 * Текст переписки на сервер не передаётся — только phone_hash, pattern_flags и local_score.
 */
class NumberCheckManager(
  context: Context,
  private val networkProvider: NetworkClientProvider = NetworkClientProvider(),
) {

  private val appContext = context.applicationContext

  suspend fun checkNumber(
    itemId: String,
    userId: String,
    phoneHash: String,
    displaySender: String,
    messengerLabel: String,
    baseUrl: String,
    patternFlags: List<String>,
    localScore: Float,
    apiToken: String = "",
    senderKind: String = "phone",
    channel: String = "message",
    inContacts: Boolean = false,
  ) {
    NotificationEventsHolder.updateNumberStatus(itemId, NumberCheckStatus.Checking)

    val api =
      networkProvider.getApi(baseUrl = baseUrl, apiToken = apiToken).getOrElse { error ->
        NotificationEventsHolder.updateNumberStatus(
          itemId,
          NumberCheckStatus.Error("Некорректный адрес сервера: ${error.localizedMessage ?: "ошибка"}"),
        )
        return
      }

    try {
      val response =
        api.checkNumber(
          NumberCheckRequest(
            userId = userId,
            phoneHash = phoneHash,
            senderKind = senderKind,
            channel = channel,
            inContacts = inContacts,
            patternFlags = patternFlags,
            localScore = localScore,
          )
        )
      if (!response.isSuccessful) {
        val parsed = networkProvider.parseError(response.errorBody()?.string())
        val details =
          if (parsed != null) "${parsed.code}: ${parsed.message}" else "HTTP ${response.code()}"
        NotificationEventsHolder.updateNumberStatus(itemId, NumberCheckStatus.Error(details))
        return
      }

      val body =
        response.body()
          ?: run {
            NotificationEventsHolder.updateNumberStatus(
              itemId,
              NumberCheckStatus.Error("Пустой ответ от сервера"),
            )
            return
          }

      if (body.risk == "high") {
        NotificationEventsHolder.updateNumberStatus(
          itemId,
          NumberCheckStatus.HighRisk(reasons = body.reasons, sources = body.sources),
        )
        LinkWarningNotifier.showSuspiciousNumberWarning(
          context = appContext,
          displaySender = displaySender,
          messengerLabel = messengerLabel,
          reasons = body.reasons,
        )
      } else {
        NotificationEventsHolder.updateNumberStatus(
          itemId,
          NumberCheckStatus.LowRisk(reasons = body.reasons, sources = body.sources),
        )
      }
    } catch (e: Exception) {
      NotificationEventsHolder.updateNumberStatus(
        itemId,
        NumberCheckStatus.Error(e.localizedMessage ?: "Сервер недоступен"),
      )
    }
  }

  suspend fun reportScammer(
    itemId: String,
    userId: String,
    phoneHash: String,
    baseUrl: String,
    apiToken: String = "",
  ) {
    NotificationEventsHolder.updateNumberReportStatus(itemId, NumberReportStatus.Sending)

    val api =
      networkProvider.getApi(baseUrl = baseUrl, apiToken = apiToken).getOrElse { error ->
        NotificationEventsHolder.updateNumberReportStatus(
          itemId,
          NumberReportStatus.Error("Некорректный адрес сервера: ${error.localizedMessage ?: "ошибка"}"),
        )
        return
      }

    try {
      val response =
        api.reportNumber(
          NumberReportRequest(
            userId = userId,
            phoneHash = phoneHash,
          )
        )
      if (response.isSuccessful) {
        NotificationEventsHolder.updateNumberReportStatus(itemId, NumberReportStatus.Reported)
      } else {
        val parsed = networkProvider.parseError(response.errorBody()?.string())
        val details =
          if (parsed != null) "${parsed.code}: ${parsed.message}" else "HTTP ${response.code()}"
        NotificationEventsHolder.updateNumberReportStatus(
          itemId,
          NumberReportStatus.Error(details),
        )
      }
    } catch (e: Exception) {
      NotificationEventsHolder.updateNumberReportStatus(
        itemId,
        NumberReportStatus.Error(e.localizedMessage ?: "Сервер недоступен"),
      )
    }
  }
}
