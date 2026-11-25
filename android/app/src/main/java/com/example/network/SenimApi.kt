package com.example.network

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Контрактные DTO приложения Senim v1.
 */
@JsonClass(generateAdapter = true)
data class DeviceRegisterRequest(
  @Json(name = "user_id") val userId: String,
  @Json(name = "device_id") val deviceId: String,
  @Json(name = "public_key") val publicKey: String,
  @Json(name = "fcm_token") val fcmToken: String,
  @Json(name = "role") val role: String,
)

@JsonClass(generateAdapter = true)
data class DeviceRegisterResponse(
  @Json(name = "status") val status: String,
)

@JsonClass(generateAdapter = true)
data class LinkCheckRequest(
  @Json(name = "user_id") val userId: String,
  @Json(name = "url") val url: String,
  @Json(name = "source") val source: String,
  @Json(name = "sender_key") val senderKey: String? = null,
)

@JsonClass(generateAdapter = true)
data class LinkCheckResponse(
  @Json(name = "risk") val risk: String,
  @Json(name = "reasons") val reasons: List<String> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class NumberCheckRequest(
  @Json(name = "user_id") val userId: String,
  @Json(name = "phone_hash") val phoneHash: String,
  @Json(name = "sender_kind") val senderKind: String = "phone",
  @Json(name = "channel") val channel: String = "message",
  @Json(name = "in_contacts") val inContacts: Boolean = false,
  @Json(name = "pattern_flags") val patternFlags: List<String> = emptyList(),
  @Json(name = "local_score") val localScore: Float? = null,
)

@JsonClass(generateAdapter = true)
data class NumberCheckResponse(
  @Json(name = "risk") val risk: String,
  @Json(name = "reasons") val reasons: List<String> = emptyList(),
  @Json(name = "sources") val sources: List<String> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class NumberReportRequest(
  @Json(name = "user_id") val userId: String,
  @Json(name = "phone_hash") val phoneHash: String,
)

@JsonClass(generateAdapter = true)
data class StatusOkResponse(
  @Json(name = "status") val status: String,
)

@JsonClass(generateAdapter = true)
data class AccountDto(
  @Json(name = "user_id") val userId: String,
  @Json(name = "balance") val balance: Int,
  @Json(name = "deposit_balance") val depositBalance: Int,
)

@JsonClass(generateAdapter = true)
data class DepositCloseRequest(
  @Json(name = "user_id") val userId: String,
  @Json(name = "amount") val amount: Int,
)

@JsonClass(generateAdapter = true)
data class TransferCreateRequest(
  @Json(name = "transfer_id") val transferId: String,
  @Json(name = "from_user_id") val fromUserId: String,
  @Json(name = "to_account") val toAccount: String,
  @Json(name = "amount") val amount: Int,
  @Json(name = "created_at") val createdAt: String,
)

@JsonClass(generateAdapter = true)
data class TransferCancelRequest(
  @Json(name = "user_id") val userId: String,
)

@JsonClass(generateAdapter = true)
data class TransferActionResponse(
  @Json(name = "transfer_id") val transferId: String,
  @Json(name = "status") val status: String,
)

@JsonClass(generateAdapter = true)
data class DecoyIssueRequest(
  @Json(name = "user_id") val userId: String,
)

@JsonClass(generateAdapter = true)
data class DecoyIssueResponse(
  @Json(name = "decoy_code") val decoyCode: String,
  @Json(name = "expires_at") val expiresAt: String,
)

@JsonClass(generateAdapter = true)
data class DecoyUseRequest(
  @Json(name = "user_id") val userId: String,
  @Json(name = "code") val code: String,
)

@JsonClass(generateAdapter = true)
data class DecoyUseResponse(
  @Json(name = "valid") val valid: Boolean,
  @Json(name = "alert_raised") val alertRaised: Boolean,
)

@JsonClass(generateAdapter = true)
data class ApiErrorEnvelope(
  @Json(name = "error") val error: ApiErrorBody?,
)

@JsonClass(generateAdapter = true)
data class ApiErrorBody(
  @Json(name = "code") val code: String,
  @Json(name = "message") val message: String,
)

@JsonClass(generateAdapter = true)
data class AlertDto(
  @Json(name = "alert_id") val alertId: String,
  @Json(name = "alert_type") val alertType: String = "",
  @Json(name = "about_user_id") val aboutUserId: String = "",
  @Json(name = "about_user_name") val aboutUserName: String = "",
  @Json(name = "transfer_id") val transferId: String? = null,
  @Json(name = "message") val message: String,
  @Json(name = "created_at") val createdAt: String,
)

@JsonClass(generateAdapter = true)
data class AlertsResponse(
  @Json(name = "alerts") val alerts: List<AlertDto> = emptyList(),
)

interface SenimApi {
  @POST("devices/register")
  suspend fun registerDevice(@Body request: DeviceRegisterRequest): Response<DeviceRegisterResponse>

  @POST("links/check")
  suspend fun checkLink(@Body request: LinkCheckRequest): Response<LinkCheckResponse>

  @POST("numbers/check")
  suspend fun checkNumber(@Body request: NumberCheckRequest): Response<NumberCheckResponse>

  @POST("numbers/report")
  suspend fun reportNumber(@Body request: NumberReportRequest): Response<StatusOkResponse>

  @GET("accounts/{user_id}")
  suspend fun getAccount(@Path("user_id") userId: String): Response<AccountDto>

  @POST("deposits/close")
  suspend fun closeDeposit(@Body request: DepositCloseRequest): Response<StatusOkResponse>

  @POST("transfers")
  suspend fun createTransfer(@Body request: TransferCreateRequest): Response<TransferActionResponse>

  @POST("transfers/{transfer_id}/cancel")
  suspend fun cancelTransfer(
    @Path("transfer_id") transferId: String,
    @Body request: TransferCancelRequest,
  ): Response<TransferActionResponse>

  @POST("decoy/issue")
  suspend fun issueDecoyCode(@Body request: DecoyIssueRequest): Response<DecoyIssueResponse>

  @POST("decoy/use")
  suspend fun useDecoyCode(@Body request: DecoyUseRequest): Response<DecoyUseResponse>

  @GET("alerts")
  suspend fun getAlerts(
    @Query("user_id") userId: String,
    @Query("since") since: String = "1970-01-01T00:00:00Z",
  ): Response<AlertsResponse>
}
