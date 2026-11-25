package com.example

import com.example.alerts.AlertsHolder
import com.example.core.SenimContract
import com.example.network.DecoyUseRequest
import com.example.network.DepositCloseRequest
import com.example.network.DeviceRegisterRequest
import com.example.network.Ed25519KeyManager
import com.example.network.NetworkClientProvider
import com.example.network.TransferCancelRequest
import com.example.network.TransferCreateRequest
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Контрактные тесты Senim против локального embedded HTTP-сервера:
 * - DEFAULT_BASE_URL = https://senim.studynesthub.com и INITIAL_ALERTS_SINCE = 1970-01-01T00:00:00Z
 * - Заголовок Authorization: Bearer <token> на всех запросах API
 * - Ed25519 (32 сырых байта в Base64, вектор RFC 8032 + подпись и проверка стандартным Signature/KeyFactory)
 * - POST /devices/register (device_id + public_key)
 * - POST /deposits/close (user_id + amount)
 * - POST /transfers (transfer_id, from_user_id, to_account, amount, created_at)
 * - GET /alerts?user_id=...&since=1970-01-01T00:00:00Z
 * - POST /transfers/{id}/cancel
 * - POST /decoy/use (user_id, code -> valid, alert_raised)
 */
class SenimContractServerTest {

  private lateinit var server: HttpServer
  private lateinit var baseUrl: String

  private val testBearerToken = "test_bearer_token_for_contract_suite"
  private val capturedRequests = CopyOnWriteArrayList<Pair<String, String>>()
  private val capturedAuthHeaders = CopyOnWriteArrayList<Pair<String, String?>>()
  private val capturedAlertQueries = CopyOnWriteArrayList<String?>()
  private val alertsJsonList = CopyOnWriteArrayList<String>()
  private var mainBalance = 200_000
  private var depositBalance = 500_000
  private val transfers = LinkedHashMap<String, String>()

  @Before
  fun setUp() {
    capturedRequests.clear()
    capturedAuthHeaders.clear()
    capturedAlertQueries.clear()
    alertsJsonList.clear()
    AlertsHolder.clear()
    mainBalance = 200_000
    depositBalance = 500_000
    transfers.clear()

    server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/devices/register") { exchange ->
      if (!requireBearerAuth(exchange)) return@createContext
      val body = exchange.requestBody.bufferedReader(Charsets.UTF_8).readText()
      capturedRequests += "POST /devices/register" to body
      assertTrue(body.contains("\"user_id\":\"user_mom\""))
      assertTrue(body.contains("\"device_id\":\""))
      assertTrue(body.contains("\"public_key\":\""))
      assertTrue(body.contains("\"fcm_token\":\""))
      assertTrue(body.contains("\"role\":\"user\""))
      val pubKeyB64 = extractJsonString(body, "public_key")
      assertNotNull(pubKeyB64)
      assertEquals(32, Ed25519KeyManager.decodeBase64(pubKeyB64!!).size)
      respondJson(exchange, 200, """{"status":"registered"}""")
    }

    server.createContext("/accounts/") { exchange ->
      if (!requireBearerAuth(exchange)) return@createContext
      val userId = exchange.requestURI.path.substringAfterLast("/")
      respondJson(
        exchange,
        200,
        """{"user_id":"$userId","balance":$mainBalance,"deposit_balance":$depositBalance}""",
      )
    }

    server.createContext("/deposits/close") { exchange ->
      if (!requireBearerAuth(exchange)) return@createContext
      val body = exchange.requestBody.bufferedReader(Charsets.UTF_8).readText()
      capturedRequests += "POST /deposits/close" to body
      val userId = extractJsonString(body, "user_id")!!
      val amount = extractJsonInt(body, "amount")!!
      if (amount <= 0 || amount > depositBalance) {
        respondJson(exchange, 409, """{"error":{"code":"INVALID_STATE","message":"Недостаточно средств на вкладе"}}""")
      } else {
        depositBalance -= amount
        mainBalance += amount
        alertsJsonList +=
          """{"alert_id":"alert-dep-1","alert_type":"deposit_early_close","about_user_id":"$userId","about_user_name":"Мама","transfer_id":null,"message":"Досрочно снят вклад на сумму $amount ₸","created_at":"2026-11-25T10:00:00Z"}"""
        respondJson(exchange, 200, """{"status":"ok"}""")
      }
    }

    server.createContext("/transfers") { exchange ->
      if (!requireBearerAuth(exchange)) return@createContext
      val path = exchange.requestURI.path
      val body = exchange.requestBody.bufferedReader(Charsets.UTF_8).readText()
      if (path == "/transfers") {
        capturedRequests += "POST /transfers" to body
        val transferId = extractJsonString(body, "transfer_id")!!
        val fromUserId = extractJsonString(body, "from_user_id")!!
        val toAccount = extractJsonString(body, "to_account")!!
        val amount = extractJsonInt(body, "amount")!!
        assertNotNull(extractJsonString(body, "created_at"))

        mainBalance -= amount
        val status = if (amount >= 100_000) "flagged" else "completed"
        transfers[transferId] = status
        if (status == "flagged") {
          alertsJsonList +=
            """{"alert_id":"alert-tr-1","alert_type":"flagged_transfer","about_user_id":"$fromUserId","about_user_name":"Мама","transfer_id":"$transferId","message":"Крупный перевод $amount ₸ на $toAccount приостановлен","created_at":"2026-11-25T10:01:00Z"}"""
        }
        respondJson(exchange, 200, """{"transfer_id":"$transferId","status":"$status"}""")
      } else if (path.endsWith("/cancel")) {
        capturedRequests += "POST $path" to body
        val transferId = path.removePrefix("/transfers/").removeSuffix("/cancel")
        assertNotNull(extractJsonString(body, "user_id"))
        transfers[transferId] = "cancelled"
        mainBalance += 350_000
        respondJson(exchange, 200, """{"transfer_id":"$transferId","status":"cancelled"}""")
      } else {
        respondJson(exchange, 404, """{"error":{"code":"NOT_FOUND","message":"unknown path"}}""")
      }
    }

    server.createContext("/alerts") { exchange ->
      if (!requireBearerAuth(exchange)) return@createContext
      val query = exchange.requestURI.rawQuery
      capturedAlertQueries += query
      // Имитируем требование реального сервера: параметр since обязателен
      if (query == null || !query.contains("since=")) {
        respondJson(exchange, 400, """{"error":{"code":"MISSING_SINCE","message":"since query parameter is required"}}""")
        return@createContext
      }
      respondJson(exchange, 200, """{"alerts":[${alertsJsonList.joinToString(",")}]}""")
    }

    server.createContext("/decoy/use") { exchange ->
      if (!requireBearerAuth(exchange)) return@createContext
      val body = exchange.requestBody.bufferedReader(Charsets.UTF_8).readText()
      capturedRequests += "POST /decoy/use" to body
      assertNotNull(extractJsonString(body, "user_id"))
      val code = extractJsonString(body, "code")
      assertNotNull(code)
      val valid = code == "482910"
      respondJson(exchange, 200, """{"valid":$valid,"alert_raised":$valid}""")
    }

    server.start()
    baseUrl = "http://127.0.0.1:${server.address.port}"
  }

  @After
  fun tearDown() {
    server.stop(0)
  }

  @Test
  fun `contract defaults match senim server requirements`() {
    assertEquals("https://senim.studynesthub.com", SenimContract.DEFAULT_BASE_URL)
    assertEquals("1970-01-01T00:00:00Z", SenimContract.INITIAL_ALERTS_SINCE)
  }

  @Test
  fun `ed25519 derives exact RFC 8032 vector, signs, and verifies`() {
    val seedHex = "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60"
    val expectedPubHex = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"
    val kp = Ed25519KeyManager.fromSeed(hexToBytes(seedHex))
    assertEquals(32, kp.publicKey.size)
    assertEquals(expectedPubHex, bytesToHex(kp.publicKey))

    val randomKp = Ed25519KeyManager.generateKeyPair()
    val decoded = Ed25519KeyManager.decodeBase64(randomKp.publicKeyBase64)
    assertEquals(32, decoded.size)

    val spkiPrefix = hexToBytes("302a300506032b6570032100")
    val x509Key = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(spkiPrefix + decoded))
    assertNotNull(x509Key)

    val payload = "offline_journal_entry:user_mom:1732539600".toByteArray(Charsets.UTF_8)
    val signatureBytes = Ed25519KeyManager.sign(randomKp.privateSeed, payload)
    assertEquals(64, signatureBytes.size)
    val verifier = Signature.getInstance("Ed25519").apply {
      initVerify(x509Key)
      update(payload)
    }
    assertTrue(verifier.verify(signatureBytes))
  }

  @Test
  fun `full contract roundtrip with bearer auth and initial since param`() = runTest {
    val networkProvider = NetworkClientProvider()
    val api = networkProvider.getApi(baseUrl = baseUrl, apiToken = testBearerToken).getOrThrow()

    // 1. POST /devices/register (device_id + public_key Ed25519 Base64 32 bytes)
    val keyPair = Ed25519KeyManager.generateKeyPair()
    val deviceId = UUID.randomUUID().toString()
    val regResp =
      api.registerDevice(
        DeviceRegisterRequest(
          userId = "user_mom",
          deviceId = deviceId,
          publicKey = keyPair.publicKeyBase64,
          fcmToken = "test_fcm_token_123",
          role = "user",
        )
      )
    assertTrue(regResp.isSuccessful)
    assertEquals("registered", regResp.body()?.status)
    val regBody = capturedRequests.first { it.first == "POST /devices/register" }.second
    assertEquals(deviceId, extractJsonString(regBody, "device_id"))
    assertEquals(keyPair.publicKeyBase64, extractJsonString(regBody, "public_key"))

    // 2. POST /deposits/close (user_id + amount)
    val closeResp = api.closeDeposit(DepositCloseRequest(userId = "user_mom", amount = 500_000))
    assertTrue(closeResp.isSuccessful)
    assertEquals("ok", closeResp.body()?.status)
    val closeBody = capturedRequests.first { it.first == "POST /deposits/close" }.second
    assertEquals("user_mom", extractJsonString(closeBody, "user_id"))
    assertEquals(500_000, extractJsonInt(closeBody, "amount"))

    val accountAfterClose = api.getAccount("user_mom").body()!!
    assertEquals(700_000, accountAfterClose.balance)
    assertEquals(0, accountAfterClose.depositBalance)

    // 3. POST /transfers (transfer_id, from_user_id, to_account, amount, created_at)
    val transferId = "tr-test-001"
    val transferResp =
      api.createTransfer(
        TransferCreateRequest(
          transferId = transferId,
          fromUserId = "user_mom",
          toAccount = "+77000000001",
          amount = 350_000,
          createdAt = "2026-11-25T10:01:00Z",
        )
      )
    assertTrue(transferResp.isSuccessful)
    assertEquals("flagged", transferResp.body()?.status)
    val transferBody = capturedRequests.first { it.first == "POST /transfers" }.second
    assertEquals(transferId, extractJsonString(transferBody, "transfer_id"))
    assertEquals("user_mom", extractJsonString(transferBody, "from_user_id"))
    assertEquals("+77000000001", extractJsonString(transferBody, "to_account"))
    assertEquals(350_000, extractJsonInt(transferBody, "amount"))
    assertEquals("2026-11-25T10:01:00Z", extractJsonString(transferBody, "created_at"))

    // 4. GET /alerts с дефолтным since=1970-01-01T00:00:00Z
    val alertsResp = api.getAlerts(userId = "user_son")
    assertTrue(alertsResp.isSuccessful)
    val firstAlertQuery = capturedAlertQueries.first()
    assertNotNull(firstAlertQuery)
    assertTrue(firstAlertQuery!!.contains("since=1970-01-01T00%3A00%3A00Z") || firstAlertQuery.contains("since=1970-01-01T00:00:00Z"))

    val alerts = alertsResp.body()?.alerts.orEmpty()
    assertEquals(2, alerts.size)
    AlertsHolder.upsertAlerts(alerts)
    assertEquals(2, AlertsHolder.alerts.value.size)

    val depositAlert = alerts.first { it.alertId == "alert-dep-1" }
    assertEquals("deposit_early_close", depositAlert.alertType)
    assertEquals("user_mom", depositAlert.aboutUserId)
    assertEquals("Мама", depositAlert.aboutUserName)
    assertNull(depositAlert.transferId)

    val transferAlert = alerts.first { it.alertId == "alert-tr-1" }
    assertEquals("flagged_transfer", transferAlert.alertType)
    assertEquals("user_mom", transferAlert.aboutUserId)
    assertEquals("Мама", transferAlert.aboutUserName)
    assertEquals(transferId, transferAlert.transferId)

    // 5. POST /transfers/{id}/cancel
    val cancelResp = api.cancelTransfer(transferId, TransferCancelRequest(userId = "user_mom"))
    assertTrue(cancelResp.isSuccessful)
    assertEquals("cancelled", cancelResp.body()?.status)
    val accountAfterCancel = api.getAccount("user_mom").body()!!
    assertEquals(700_000, accountAfterCancel.balance)

    // 6. POST /decoy/use (user_id, code -> valid, alert_raised)
    val decoyValidResp = api.useDecoyCode(DecoyUseRequest(userId = "user_mom", code = "482910"))
    assertTrue(decoyValidResp.isSuccessful)
    assertEquals(true, decoyValidResp.body()?.valid)
    assertEquals(true, decoyValidResp.body()?.alertRaised)
    val decoyBody = capturedRequests.first { it.first == "POST /decoy/use" }.second
    assertEquals("user_mom", extractJsonString(decoyBody, "user_id"))
    assertEquals("482910", extractJsonString(decoyBody, "code"))
    assertFalse(decoyBody.contains("\"decoy_code\""))

    val decoyInvalidResp = api.useDecoyCode(DecoyUseRequest(userId = "user_mom", code = "000000"))
    assertTrue(decoyInvalidResp.isSuccessful)
    assertEquals(false, decoyInvalidResp.body()?.valid)
    assertEquals(false, decoyInvalidResp.body()?.alertRaised)

    // 7. Проверяем, что заголовок Authorization: Bearer отправлен на каждый запрос
    assertTrue(capturedAuthHeaders.isNotEmpty())
    assertTrue(capturedAuthHeaders.all { it.second == "Bearer $testBearerToken" })
  }

  private fun requireBearerAuth(exchange: HttpExchange): Boolean {
    val authHeader = exchange.requestHeaders.getFirst("Authorization")
    capturedAuthHeaders += exchange.requestURI.path to authHeader
    if (authHeader != "Bearer $testBearerToken") {
      respondJson(exchange, 401, """{"error":{"code":"UNAUTHORIZED","message":"Missing or invalid Bearer token"}}""")
      return false
    }
    return true
  }

  private fun respondJson(exchange: HttpExchange, status: Int, json: String) {
    val bytes = json.toByteArray(Charsets.UTF_8)
    exchange.responseHeaders.add("Content-Type", "application/json")
    exchange.sendResponseHeaders(status, bytes.size.toLong())
    exchange.responseBody.use { it.write(bytes) }
  }

  private fun extractJsonString(json: String, key: String): String? {
    val regex = Regex(""""$key"\s*:\s*"([^"]*)"""")
    return regex.find(json)?.groupValues?.get(1)
  }

  private fun extractJsonInt(json: String, key: String): Int? {
    val regex = Regex(""""$key"\s*:\s*(-?\d+)""")
    return regex.find(json)?.groupValues?.get(1)?.toIntOrNull()
  }

  private fun hexToBytes(hex: String): ByteArray {
    val out = ByteArray(hex.length / 2)
    for (i in out.indices) {
      out[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
    return out
  }

  private fun bytesToHex(bytes: ByteArray): String =
    bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
