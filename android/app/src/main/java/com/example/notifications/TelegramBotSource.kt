package com.example.notifications

import com.example.BuildConfig
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

@JsonClass(generateAdapter = true)
data class TgUpdatesResponse(
  @Json(name = "ok") val ok: Boolean,
  @Json(name = "result") val result: List<TgUpdate> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class TgUpdate(
  @Json(name = "update_id") val updateId: Long,
  @Json(name = "message") val message: TgMessage? = null,
)

@JsonClass(generateAdapter = true)
data class TgMessage(
  @Json(name = "message_id") val messageId: Long,
  @Json(name = "date") val date: Long,
  @Json(name = "text") val text: String? = null,
  @Json(name = "from") val from: TgUser? = null,
)

@JsonClass(generateAdapter = true)
data class TgUser(
  @Json(name = "id") val id: Long,
  @Json(name = "first_name") val firstName: String? = null,
  @Json(name = "username") val username: String? = null,
)

interface TelegramBotApi {
  @GET("bot{token}/getUpdates")
  suspend fun getUpdates(
    @Path("token") token: String,
    @Query("offset") offset: Long?,
    @Query("timeout") timeoutSeconds: Int = 5,
  ): Response<TgUpdatesResponse>
}

sealed interface TelegramPollingState {
  data object Stopped : TelegramPollingState
  data object UnavailableInRelease : TelegramPollingState
  data object MissingToken : TelegramPollingState
  data object Polling : TelegramPollingState
  data class Error(val message: String) : TelegramPollingState
}

/**
 * Источник сообщений из тестового Telegram-бота (getUpdates).
 * Работает только в debug-сборке; токен берётся из BuildConfig.TELEGRAM_BOT_TOKEN
 * и никогда не попадает в Git или логи.
 */
class TelegramBotSource : MessengerSource {

  override val sourceName: String = SOURCE_TELEGRAM_BOT

  private val _pollingState = MutableStateFlow<TelegramPollingState>(TelegramPollingState.Stopped)
  val pollingState: StateFlow<TelegramPollingState> = _pollingState.asStateFlow()

  private val botApi: TelegramBotApi by lazy {
    val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    // Намеренно не подключаем HttpLoggingInterceptor, чтобы токен бота в URL не попал в Logcat
    val client =
      OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    Retrofit.Builder()
      .baseUrl("https://api.telegram.org/")
      .client(client)
      .addConverterFactory(MoshiConverterFactory.create(moshi))
      .build()
      .create(TelegramBotApi::class.java)
  }

  override val messages: Flow<IncomingMessage> = flow {
    if (!BuildConfig.DEBUG) {
      _pollingState.value = TelegramPollingState.UnavailableInRelease
      return@flow
    }

    val token = BuildConfig.TELEGRAM_BOT_TOKEN.trim()
    if (token.isEmpty() || token == "MY_TELEGRAM_BOT_TOKEN") {
      _pollingState.value = TelegramPollingState.MissingToken
      return@flow
    }

    var nextOffset: Long? = null
    _pollingState.value = TelegramPollingState.Polling

    while (currentCoroutineContext().isActive) {
      try {
        val response = botApi.getUpdates(token = token, offset = nextOffset)
        if (response.isSuccessful) {
          _pollingState.value = TelegramPollingState.Polling
          val updates = response.body()?.result.orEmpty()
          for (update in updates) {
            nextOffset = update.updateId + 1
            val msg = update.message ?: continue
            val text = msg.text?.trim().orEmpty()
            if (text.isEmpty()) continue
            val from = msg.from ?: continue

            val senderKey = PhoneUtils.sha256Hex("tg:${from.id}")
            val displayName =
              from.username?.let { "@$it" }
                ?: from.firstName?.takeIf { it.isNotBlank() }
                ?: "tg:${from.id}"

            emit(
              IncomingMessage(
                senderKey = senderKey,
                senderName = displayName,
                text = text,
                timestamp = msg.date * 1000L,
                source = SOURCE_TELEGRAM_BOT,
                senderKind = "telegram",
                inContacts = false,
              )
            )
          }
        } else {
          _pollingState.value =
            TelegramPollingState.Error("Telegram API HTTP ${response.code()}")
        }
      } catch (cancel: CancellationException) {
        _pollingState.value = TelegramPollingState.Stopped
        throw cancel
      } catch (e: Exception) {
        _pollingState.value =
          TelegramPollingState.Error(e.localizedMessage ?: "Ошибка подключения к Telegram API")
      }
      delay(2_500L)
    }
    _pollingState.value = TelegramPollingState.Stopped
  }

  fun markStopped() {
    _pollingState.value = TelegramPollingState.Stopped
  }

  companion object {
    const val SOURCE_TELEGRAM_BOT = "telegram_bot"
  }
}
