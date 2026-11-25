package com.example.network

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

/**
 * Единый результат сетевого вызова без падений при недоступности сервера.
 */
sealed interface NetworkResult<out T> {
  data class Success<T>(val data: T) : NetworkResult<T>
  data class Error(val code: String?, val message: String) : NetworkResult<Nothing>
}

/**
 * Фабрика сетевого клиента, пересобирающая Retrofit при изменении базового URL в настройках.
 */
class NetworkClientProvider {

  private val moshi: Moshi =
    Moshi.Builder()
      .addLast(KotlinJsonAdapterFactory())
      .build()

  @Volatile
  private var activeApiToken: String = ""

  private val okHttpClient: OkHttpClient =
    OkHttpClient.Builder()
      .connectTimeout(5, TimeUnit.SECONDS)
      .readTimeout(10, TimeUnit.SECONDS)
      .writeTimeout(10, TimeUnit.SECONDS)
      .addInterceptor { chain ->
        val token = activeApiToken.trim()
        val request =
          if (token.isNotEmpty()) {
            chain.request().newBuilder().header("Authorization", "Bearer $token").build()
          } else {
            chain.request()
          }
        chain.proceed(request)
      }
      .addInterceptor(
        HttpLoggingInterceptor().apply {
          // Используем BASIC вместо BODY/HEADERS, чтобы в логи не попадали токен авторизации и детали запросов
          level = HttpLoggingInterceptor.Level.BASIC
        }
      )
      .build()

  private var cachedBaseUrl: String? = null
  private var cachedApi: SenimApi? = null

  @Synchronized
  fun getApi(baseUrl: String, apiToken: String = activeApiToken): Result<SenimApi> = runCatching {
    activeApiToken = apiToken.trim()
    val normalizedWithSlash = baseUrl.trim().trimEnd('/') + "/"
    if (cachedApi != null && cachedBaseUrl == normalizedWithSlash) {
      return@runCatching cachedApi!!
    }
    val retrofit =
      Retrofit.Builder()
        .baseUrl(normalizedWithSlash)
        .client(okHttpClient)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()
    val api = retrofit.create(SenimApi::class.java)
    cachedBaseUrl = normalizedWithSlash
    cachedApi = api
    api
  }

  fun parseError(rawBody: String?): ApiErrorBody? {
    if (rawBody.isNullOrBlank()) return null
    return runCatching {
      moshi.adapter(ApiErrorEnvelope::class.java).fromJson(rawBody)?.error
    }.getOrNull()
  }
}
