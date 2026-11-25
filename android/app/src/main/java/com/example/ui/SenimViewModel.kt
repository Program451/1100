package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.alerts.AlertsHolder
import com.example.alerts.AlertsSyncState
import com.example.bank.BankFormatters
import com.example.bank.BankOperationNotice
import com.example.bank.BankTransferItem
import com.example.bank.BankUiState
import com.example.bank.DecoyUseOutcome
import com.example.bank.ServerPingState
import com.example.core.AppRole
import com.example.core.AppSettings
import com.example.core.AppSettingsRepository
import com.example.core.SenimContract
import com.example.network.AlertDto
import com.example.network.DecoyIssueRequest
import com.example.network.DecoyUseRequest
import com.example.network.DepositCloseRequest
import com.example.network.DeviceRegistrationManager
import com.example.network.DeviceRegistrationState
import com.example.network.NetworkClientProvider
import com.example.network.TransferCancelRequest
import com.example.network.TransferCreateRequest
import com.example.notifications.ChatPipelineMessage
import com.example.notifications.CheckedNumberItem
import com.example.notifications.DemoChatSource
import com.example.notifications.ExtractedNotificationLink
import com.example.notifications.LinkCheckManager
import com.example.notifications.LinkWarningNotifier
import com.example.notifications.MessagePipeline
import com.example.notifications.NotificationEventsHolder
import com.example.notifications.NumberCheckManager
import com.example.notifications.TelegramBotSource
import com.example.notifications.TelegramPollingState
import com.example.notifications.VideoCallTipItem
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Основная ViewModel приложения: управляет режимом (user / trusted),
 * базовым URL сервера, автоматической регистрацией устройства и конвейером проверки сообщений.
 */
class SenimViewModel(application: Application) : AndroidViewModel(application) {

  private val settingsRepository = AppSettingsRepository(application.applicationContext)
  private val networkProvider = NetworkClientProvider()
  private val registrationManager =
    DeviceRegistrationManager(
      context = application.applicationContext,
      settingsRepository = settingsRepository,
      networkProvider = networkProvider,
    )
  private val linkCheckManager =
    LinkCheckManager(
      context = application.applicationContext,
      networkProvider = networkProvider,
    )
  private val numberCheckManager =
    NumberCheckManager(
      context = application.applicationContext,
      networkProvider = networkProvider,
    )
  private val messagePipeline =
    MessagePipeline(
      context = application.applicationContext,
      settingsRepository = settingsRepository,
      networkProvider = networkProvider,
    )
  private val demoChatSource = DemoChatSource()
  private val telegramBotSource = TelegramBotSource()

  val settings: StateFlow<AppSettings> =
    settingsRepository.settingsFlow.stateIn(
      scope = viewModelScope,
      started = SharingStarted.WhileSubscribed(5_000),
      initialValue = AppSettings(),
    )

  private val _pingState = MutableStateFlow<ServerPingState>(ServerPingState.Idle)
  val pingState: StateFlow<ServerPingState> = _pingState.asStateFlow()

  private val _registrationState =
    MutableStateFlow<DeviceRegistrationState>(DeviceRegistrationState.Idle)
  val registrationState: StateFlow<DeviceRegistrationState> = _registrationState.asStateFlow()

  val extractedLinks: StateFlow<List<ExtractedNotificationLink>> =
    NotificationEventsHolder.extractedLinks

  val checkedNumbers: StateFlow<List<CheckedNumberItem>> =
    NotificationEventsHolder.checkedNumbers

  val videoCallTips: StateFlow<List<VideoCallTipItem>> =
    NotificationEventsHolder.videoCallTips

  val chatMessages: StateFlow<List<ChatPipelineMessage>> = messagePipeline.chatMessages

  val telegramPollingState: StateFlow<TelegramPollingState> = telegramBotSource.pollingState

  val alerts: StateFlow<List<AlertDto>> = AlertsHolder.alerts

  private val _alertsSyncState = MutableStateFlow<AlertsSyncState>(AlertsSyncState.Idle)
  val alertsSyncState: StateFlow<AlertsSyncState> = _alertsSyncState.asStateFlow()

  private val _bankUiState = MutableStateFlow(BankUiState())
  val bankUiState: StateFlow<BankUiState> = _bankUiState.asStateFlow()

  private var registrationJob: Job? = null
  private var telegramPollingJob: Job? = null
  private var alertsPollingJob: Job? = null
  private var lastAlertsSyncAtMs: Long? = null

  init {
    // Подписываемся на локальный демо-чат через общий MessagePipeline
    viewModelScope.launch {
      demoChatSource.messages.collect { incoming ->
        messagePipeline.process(viewModelScope, incoming)
      }
    }

    // Регистрируем устройство и синхронизируем активную роль (USER -> счёт, TRUSTED -> опрос тревог)
    viewModelScope.launch {
      settingsRepository.settingsFlow
        .map { Triple(it.selectedRole, it.baseUrl, it.effectiveApiToken) }
        .distinctUntilChanged()
        .collect { (role, baseUrl, apiToken) ->
          if (role == null) {
            registrationJob?.cancel()
            stopTelegramPolling()
            stopAlertsPolling()
            _registrationState.value = DeviceRegistrationState.Idle
            _bankUiState.value = BankUiState()
          } else {
            if (role == AppRole.TRUSTED) {
              stopTelegramPolling()
              _bankUiState.value = BankUiState()
              startAlertsPolling(trustedUserId = role.userId, baseUrl = baseUrl, apiToken = apiToken)
            } else {
              stopAlertsPolling()
              refreshBankAccountFor(userId = role.userId, baseUrl = baseUrl, apiToken = apiToken)
            }
            launchDeviceRegistration(role = role, baseUrl = baseUrl, apiToken = apiToken)
          }
        }
    }
  }

  fun selectRole(role: AppRole) {
    viewModelScope.launch {
      _pingState.value = ServerPingState.Idle
      settingsRepository.saveRole(role)
    }
  }

  fun clearRole() {
    stopTelegramPolling()
    stopAlertsPolling()
    viewModelScope.launch {
      _pingState.value = ServerPingState.Idle
      _bankUiState.value = BankUiState()
      settingsRepository.saveRole(null)
    }
  }

  fun sendDemoChatMessage(
    rawSenderId: String,
    senderName: String,
    senderKind: String,
    text: String,
  ) {
    demoChatSource.sendScenarioMessage(
      rawSenderId = rawSenderId,
      senderName = senderName,
      senderKind = senderKind,
      text = text,
    )
  }

  fun startTelegramPolling() {
    if (telegramPollingJob?.isActive == true) return
    telegramPollingJob =
      viewModelScope.launch {
        telegramBotSource.messages.collect { incoming ->
          messagePipeline.process(viewModelScope, incoming)
        }
      }
  }

  fun stopTelegramPolling() {
    telegramPollingJob?.cancel()
    telegramPollingJob = null
    telegramBotSource.markStopped()
  }

  fun clearDemoChat() {
    messagePipeline.clearChatHistory()
  }

  fun updateBaseUrl(rawUrl: String) {
    viewModelScope.launch {
      _pingState.value = ServerPingState.Idle
      settingsRepository.saveBaseUrl(rawUrl)
    }
  }

  fun updateServerSettings(rawUrl: String, rawApiToken: String) {
    viewModelScope.launch {
      _pingState.value = ServerPingState.Idle
      settingsRepository.saveBaseUrl(rawUrl)
      settingsRepository.saveApiAccessToken(rawApiToken)
    }
  }

  fun saveFamilyCodeWord(word: String) {
    viewModelScope.launch {
      settingsRepository.saveFamilyCodeWord(word)
    }
  }

  fun saveTrustedContactPhone(phone: String) {
    viewModelScope.launch {
      settingsRepository.saveTrustedContactPhone(phone)
    }
  }

  fun refreshAlertsNow() {
    val current = settings.value
    val role = current.selectedRole
    if (role != AppRole.TRUSTED) return
    viewModelScope.launch {
      fetchAlertsOnce(
        trustedUserId = role.userId,
        baseUrl = current.baseUrl,
        apiToken = current.effectiveApiToken,
        useSince = false,
      )
    }
  }

  private fun startAlertsPolling(trustedUserId: String, baseUrl: String, apiToken: String) {
    alertsPollingJob?.cancel()
    alertsPollingJob =
      viewModelScope.launch {
        var useSince = false
        while (currentCoroutineContext().isActive) {
          val ok =
            fetchAlertsOnce(
              trustedUserId = trustedUserId,
              baseUrl = baseUrl,
              apiToken = apiToken,
              useSince = useSince,
            )
          if (ok) {
            useSince = true
          }
          delay(5_000L)
        }
      }
  }

  private fun stopAlertsPolling() {
    alertsPollingJob?.cancel()
    alertsPollingJob = null
    lastAlertsSyncAtMs = null
    _alertsSyncState.value = AlertsSyncState.Idle
  }

  private suspend fun fetchAlertsOnce(
    trustedUserId: String,
    baseUrl: String,
    apiToken: String,
    useSince: Boolean,
  ): Boolean {
    _alertsSyncState.value = AlertsSyncState.Active(lastSyncAtMs = lastAlertsSyncAtMs, isSyncing = true)
    val api =
      networkProvider.getApi(baseUrl = baseUrl, apiToken = apiToken).getOrElse { error ->
        _alertsSyncState.value =
          AlertsSyncState.Error(
            message = "Некорректный URL сервера: ${error.localizedMessage ?: "ошибка"}",
            lastSyncAtMs = lastAlertsSyncAtMs,
          )
        return false
      }

    return try {
      val sinceParam =
        if (useSince) {
          AlertsHolder.latestCreatedAt() ?: SenimContract.INITIAL_ALERTS_SINCE
        } else {
          SenimContract.INITIAL_ALERTS_SINCE
        }
      val response = api.getAlerts(userId = trustedUserId, since = sinceParam)
      if (!response.isSuccessful) {
        val parsed = networkProvider.parseError(response.errorBody()?.string())
        val details =
          if (parsed != null) "${parsed.code}: ${parsed.message}" else "HTTP ${response.code()}"
        _alertsSyncState.value = AlertsSyncState.Error(details, lastAlertsSyncAtMs)
        return false
      }

      val incomingAlerts = response.body()?.alerts.orEmpty()
      val newlyAdded = AlertsHolder.upsertAlerts(incomingAlerts)
      if (newlyAdded.isNotEmpty()) {
        val latestNew = newlyAdded.maxByOrNull { it.createdAt } ?: newlyAdded.first()
        LinkWarningNotifier.showTrustedAlert(
          context = getApplication<Application>().applicationContext,
          alertId = latestNew.alertId,
          alertType = latestNew.alertType,
          message = latestNew.message,
        )
      }
      lastAlertsSyncAtMs = System.currentTimeMillis()
      _alertsSyncState.value = AlertsSyncState.Active(lastSyncAtMs = lastAlertsSyncAtMs, isSyncing = false)
      true
    } catch (cancel: CancellationException) {
      throw cancel
    } catch (e: Exception) {
      _alertsSyncState.value =
        AlertsSyncState.Error(
          message = e.localizedMessage ?: "Сервер недоступен",
          lastSyncAtMs = lastAlertsSyncAtMs,
        )
      false
    }
  }

  fun retryDeviceRegistration() {
    val current = settings.value
    val role = current.selectedRole ?: return
    launchDeviceRegistration(
      role = role,
      baseUrl = current.baseUrl,
      apiToken = current.effectiveApiToken,
    )
  }

  fun clearExtractedLinks() {
    NotificationEventsHolder.clear()
  }

  fun retryLinkCheck(itemId: String) {
    val item = NotificationEventsHolder.findById(itemId) ?: return
    val current = settings.value
    val userId = current.selectedRole?.userId ?: return
    viewModelScope.launch {
      linkCheckManager.checkNotificationLink(
        itemId = item.id,
        userId = userId,
        normalizedUrl = item.normalizedUrl,
        messengerLabel = item.messengerLabel,
        baseUrl = current.baseUrl,
        apiToken = current.effectiveApiToken,
        source = LinkCheckManager.SOURCE_NOTIFICATION,
        senderKey = item.senderKey,
      )
    }
  }

  fun retryNumberCheck(itemId: String) {
    val item = NotificationEventsHolder.findNumberById(itemId) ?: return
    val current = settings.value
    val userId = current.selectedRole?.userId ?: return
    viewModelScope.launch {
      numberCheckManager.checkNumber(
        itemId = item.id,
        userId = userId,
        phoneHash = item.phoneHash,
        displaySender = item.displaySender,
        messengerLabel = item.messengerLabel,
        baseUrl = current.baseUrl,
        patternFlags = item.patternFlags,
        localScore = item.localScore,
        apiToken = current.effectiveApiToken,
        senderKind = item.senderKind,
        channel = item.channel,
        inContacts = false,
      )
    }
  }

  fun reportScammerNumber(itemId: String) {
    val item = NotificationEventsHolder.findNumberById(itemId) ?: return
    val current = settings.value
    val userId = current.selectedRole?.userId ?: return
    viewModelScope.launch {
      numberCheckManager.reportScammer(
        itemId = item.id,
        userId = userId,
        phoneHash = item.phoneHash,
        baseUrl = current.baseUrl,
        apiToken = current.effectiveApiToken,
      )
    }
  }

  private fun launchDeviceRegistration(role: AppRole, baseUrl: String, apiToken: String) {
    registrationJob?.cancel()
    registrationJob =
      viewModelScope.launch {
        _registrationState.value = DeviceRegistrationState.Registering
        _registrationState.value =
          registrationManager.registerDevice(
            role = role,
            baseUrl = baseUrl,
            apiToken = apiToken,
          )
      }
  }

  fun testServerConnection() {
    val currentSettings = settings.value
    val userId = currentSettings.selectedRole?.userId ?: return
    refreshBankAccountFor(
      userId = userId,
      baseUrl = currentSettings.baseUrl,
      apiToken = currentSettings.effectiveApiToken,
    )
  }

  fun refreshBankAccount() {
    val currentSettings = settings.value
    val userId = currentSettings.selectedRole?.userId ?: return
    refreshBankAccountFor(
      userId = userId,
      baseUrl = currentSettings.baseUrl,
      apiToken = currentSettings.effectiveApiToken,
    )
  }

  fun dismissBankNotice() {
    _bankUiState.update { it.copy(notice = null) }
  }

  fun closeDepositEarly(amountRaw: String? = null) {
    val currentSettings = settings.value
    val role = currentSettings.selectedRole
    if (role != AppRole.USER) return
    if (_bankUiState.value.isClosingDeposit) return

    val parsedAmount =
      if (amountRaw != null) {
        val trimmed = amountRaw.trim()
        val numeric = trimmed.toIntOrNull()
        if (numeric == null || numeric < 0) {
          _bankUiState.update {
            it.copy(notice = BankOperationNotice.Error("Укажите корректную сумму снятия вклада (целое число)."))
          }
          return
        }
        numeric
      } else {
        _bankUiState.value.account?.depositBalance ?: 0
      }

    viewModelScope.launch {
      _bankUiState.update { it.copy(isClosingDeposit = true, notice = null) }
      val apiToken = currentSettings.effectiveApiToken
      val api =
        networkProvider.getApi(baseUrl = currentSettings.baseUrl, apiToken = apiToken)
          .getOrElse { error ->
            _bankUiState.update {
              it.copy(
                isClosingDeposit = false,
                notice =
                  BankOperationNotice.Error(
                    "Некорректный URL сервера: ${error.localizedMessage ?: "ошибка"}"
                  ),
              )
            }
            return@launch
          }

      try {
        val response =
          api.closeDeposit(
            DepositCloseRequest(
              userId = role.userId,
              amount = parsedAmount,
            )
          )
        if (response.isSuccessful) {
          val refreshed =
            fetchAccountInternal(
              userId = role.userId,
              baseUrl = currentSettings.baseUrl,
              apiToken = apiToken,
            )
          _bankUiState.update {
            it.copy(
              isClosingDeposit = false,
              account = refreshed ?: it.account,
              notice =
                BankOperationNotice.Warning(
                  "Вклад досрочно снят, средства зачислены на основной баланс."
                ),
            )
          }
        } else {
          val msg = formatApiError(response.code(), response.errorBody()?.string())
          _bankUiState.update {
            it.copy(isClosingDeposit = false, notice = BankOperationNotice.Error(msg))
          }
        }
      } catch (e: Exception) {
        _bankUiState.update {
          it.copy(
            isClosingDeposit = false,
            notice = BankOperationNotice.Error(e.localizedMessage ?: "Сервер недоступен"),
          )
        }
      }
    }
  }

  fun sendTransfer(recipientRaw: String, amountRaw: String) {
    val currentSettings = settings.value
    val role = currentSettings.selectedRole
    if (role != AppRole.USER) return
    if (_bankUiState.value.isSendingTransfer) return

    val recipient = recipientRaw.trim()
    val amount = amountRaw.trim().toIntOrNull()
    if (recipient.isEmpty()) {
      _bankUiState.update {
        it.copy(notice = BankOperationNotice.Error("Укажите получателя перевода."))
      }
      return
    }
    if (amount == null || amount <= 0) {
      _bankUiState.update {
        it.copy(notice = BankOperationNotice.Error("Введите целую сумму в тенге больше 0."))
      }
      return
    }

    val transferId = UUID.randomUUID().toString().lowercase(Locale.ROOT)
    val createdAt = BankFormatters.nowIsoUtc()

    viewModelScope.launch {
      _bankUiState.update { it.copy(isSendingTransfer = true, notice = null) }
      val apiToken = currentSettings.effectiveApiToken
      val api =
        networkProvider.getApi(baseUrl = currentSettings.baseUrl, apiToken = apiToken)
          .getOrElse { error ->
            _bankUiState.update {
              it.copy(
                isSendingTransfer = false,
                notice =
                  BankOperationNotice.Error(
                    "Некорректный URL сервера: ${error.localizedMessage ?: "ошибка"}"
                  ),
              )
            }
            return@launch
          }

      try {
        val response =
          api.createTransfer(
            TransferCreateRequest(
              transferId = transferId,
              fromUserId = role.userId,
              toAccount = recipient,
              amount = amount,
              createdAt = createdAt,
            )
          )
        if (response.isSuccessful) {
          val body = response.body()
          if (body == null) {
            _bankUiState.update {
              it.copy(
                isSendingTransfer = false,
                notice = BankOperationNotice.Error("Пустой ответ от сервера"),
              )
            }
            return@launch
          }
          val item =
            BankTransferItem(
              transferId = body.transferId,
              amount = amount,
              recipient = recipient,
              createdAt = createdAt,
              status = body.status,
            )
          val refreshed =
            fetchAccountInternal(
              userId = role.userId,
              baseUrl = currentSettings.baseUrl,
              apiToken = apiToken,
            )
          val notice =
            if (body.status == "flagged") {
              BankOperationNotice.Warning(
                "Перевод отмечен как рискованный (flagged). При необходимости отмените его ниже."
              )
            } else {
              BankOperationNotice.Success("Перевод выполнен (${BankFormatters.formatKzt(amount)}).")
            }
          _bankUiState.update { state ->
            state.copy(
              isSendingTransfer = false,
              account = refreshed ?: state.account,
              transfers = (listOf(item) + state.transfers).take(20),
              notice = notice,
            )
          }
        } else {
          val msg = formatApiError(response.code(), response.errorBody()?.string())
          _bankUiState.update {
            it.copy(isSendingTransfer = false, notice = BankOperationNotice.Error(msg))
          }
        }
      } catch (e: Exception) {
        _bankUiState.update {
          it.copy(
            isSendingTransfer = false,
            notice = BankOperationNotice.Error(e.localizedMessage ?: "Сервер недоступен"),
          )
        }
      }
    }
  }

  fun cancelTransfer(transferId: String) {
    val currentSettings = settings.value
    val role = currentSettings.selectedRole
    if (role != AppRole.USER) return

    viewModelScope.launch {
      _bankUiState.update { state ->
        state.copy(
          notice = null,
          transfers =
            state.transfers.map {
              if (it.transferId == transferId) it.copy(isCancelling = true) else it
            },
        )
      }

      val apiToken = currentSettings.effectiveApiToken
      val api =
        networkProvider.getApi(baseUrl = currentSettings.baseUrl, apiToken = apiToken)
          .getOrElse { error ->
            _bankUiState.update { state ->
              state.copy(
                notice =
                  BankOperationNotice.Error(
                    "Некорректный URL сервера: ${error.localizedMessage ?: "ошибка"}"
                  ),
                transfers =
                  state.transfers.map {
                    if (it.transferId == transferId) it.copy(isCancelling = false) else it
                  },
              )
            }
            return@launch
          }

      try {
        val response =
          api.cancelTransfer(
            transferId = transferId,
            request = TransferCancelRequest(userId = role.userId),
          )
        if (response.isSuccessful) {
          val body = response.body()
          val newStatus = body?.status ?: "cancelled"
          val refreshed =
            fetchAccountInternal(
              userId = role.userId,
              baseUrl = currentSettings.baseUrl,
              apiToken = apiToken,
            )
          _bankUiState.update { state ->
            state.copy(
              account = refreshed ?: state.account,
              transfers =
                state.transfers.map {
                  if (it.transferId == transferId) {
                    it.copy(status = newStatus, isCancelling = false)
                  } else {
                    it
                  }
                },
              notice = BankOperationNotice.Success("Перевод отменён ($newStatus)."),
            )
          }
        } else {
          val msg = formatApiError(response.code(), response.errorBody()?.string())
          _bankUiState.update { state ->
            state.copy(
              notice = BankOperationNotice.Error(msg),
              transfers =
                state.transfers.map {
                  if (it.transferId == transferId) it.copy(isCancelling = false) else it
                },
            )
          }
        }
      } catch (e: Exception) {
        _bankUiState.update { state ->
          state.copy(
            notice = BankOperationNotice.Error(e.localizedMessage ?: "Сервер недоступен"),
            transfers =
              state.transfers.map {
                if (it.transferId == transferId) it.copy(isCancelling = false) else it
              },
          )
        }
      }
    }
  }

  private fun refreshBankAccountFor(userId: String, baseUrl: String, apiToken: String) {
    viewModelScope.launch {
      _pingState.value = ServerPingState.Loading
      _bankUiState.update { it.copy(isLoadingAccount = true) }

      val api =
        networkProvider.getApi(baseUrl = baseUrl, apiToken = apiToken).getOrElse { error ->
          val msg = "Некорректный URL сервера: ${error.localizedMessage ?: "ошибка"}"
          _pingState.value = ServerPingState.Failed(msg)
          _bankUiState.update {
            it.copy(isLoadingAccount = false, notice = BankOperationNotice.Error(msg))
          }
          return@launch
        }

      try {
        val response = api.getAccount(userId)
        if (response.isSuccessful) {
          val body = response.body()
          if (body != null) {
            _pingState.value = ServerPingState.Connected(body)
            _bankUiState.update { it.copy(account = body, isLoadingAccount = false) }
          } else {
            val msg = "Пустой ответ от сервера"
            _pingState.value = ServerPingState.Failed(msg)
            _bankUiState.update {
              it.copy(isLoadingAccount = false, notice = BankOperationNotice.Error(msg))
            }
          }
        } else {
          val msg = formatApiError(response.code(), response.errorBody()?.string())
          _pingState.value = ServerPingState.Failed(msg)
          _bankUiState.update {
            it.copy(isLoadingAccount = false, notice = BankOperationNotice.Error(msg))
          }
        }
      } catch (e: Exception) {
        val msg = e.localizedMessage ?: "Не удалось подключиться к серверу"
        _pingState.value = ServerPingState.Failed(msg)
        _bankUiState.update {
          it.copy(isLoadingAccount = false, notice = BankOperationNotice.Error(msg))
        }
      }
    }
  }

  private suspend fun fetchAccountInternal(userId: String, baseUrl: String, apiToken: String) =
    runCatching {
      val api = networkProvider.getApi(baseUrl = baseUrl, apiToken = apiToken).getOrNull()
        ?: return@runCatching null
      val response = api.getAccount(userId)
      if (response.isSuccessful) {
        response.body()?.also { _pingState.value = ServerPingState.Connected(it) }
      } else {
        null
      }
    }.getOrNull()

  private fun formatApiError(statusCode: Int, rawErrorBody: String?): String {
    val parsed = networkProvider.parseError(rawErrorBody)
    return when {
      parsed?.code == "INVALID_STATE" || statusCode == 409 ->
        "Конфликт состояния (409): ${parsed?.message ?: "операция недоступна в текущем статусе"}"
      statusCode == 404 ->
        "Не найдено (404): ${parsed?.message ?: "объект не найден на сервере"}"
      statusCode == 400 ->
        "Ошибка запроса (400): ${parsed?.message ?: "проверьте введённые данные"}"
      parsed != null -> "${parsed.code}: ${parsed.message}"
      else -> "HTTP $statusCode"
    }
  }

  fun issueDecoyCode() {
    val currentSettings = settings.value
    val role = currentSettings.selectedRole
    if (role != AppRole.USER) return
    if (_bankUiState.value.isIssuingDecoy) return

    viewModelScope.launch {
      _bankUiState.update { it.copy(isIssuingDecoy = true, decoyUseOutcome = null, notice = null) }
      val api =
        networkProvider.getApi(
          baseUrl = currentSettings.baseUrl,
          apiToken = currentSettings.effectiveApiToken,
        ).getOrElse { error ->
          _bankUiState.update {
            it.copy(
              isIssuingDecoy = false,
              notice =
                BankOperationNotice.Error(
                  "Некорректный URL сервера: ${error.localizedMessage ?: "ошибка"}"
                ),
            )
          }
          return@launch
        }

      try {
        val response = api.issueDecoyCode(DecoyIssueRequest(userId = role.userId))
        if (response.isSuccessful) {
          val body = response.body()
          if (body != null) {
            _bankUiState.update {
              it.copy(
                isIssuingDecoy = false,
                issuedDecoyCode = body.decoyCode,
                issuedDecoyExpiresAt = body.expiresAt,
              )
            }
          } else {
            _bankUiState.update {
              it.copy(
                isIssuingDecoy = false,
                notice = BankOperationNotice.Error("Пустой ответ от сервера"),
              )
            }
          }
        } else {
          val msg = formatApiError(response.code(), response.errorBody()?.string())
          _bankUiState.update {
            it.copy(isIssuingDecoy = false, notice = BankOperationNotice.Error(msg))
          }
        }
      } catch (e: Exception) {
        _bankUiState.update {
          it.copy(
            isIssuingDecoy = false,
            notice = BankOperationNotice.Error(e.localizedMessage ?: "Сервер недоступен"),
          )
        }
      }
    }
  }

  fun useDecoyCode(rawCode: String) {
    val currentSettings = settings.value
    val role = currentSettings.selectedRole
    if (role != AppRole.USER) return
    if (_bankUiState.value.isTestingDecoyUse) return

    val code = rawCode.trim()
    if (code.isEmpty()) {
      _bankUiState.update {
        it.copy(decoyUseOutcome = DecoyUseOutcome.Failed("Введите код-приманку для проверки."))
      }
      return
    }

    viewModelScope.launch {
      _bankUiState.update { it.copy(isTestingDecoyUse = true, decoyUseOutcome = null) }
      val api =
        networkProvider.getApi(
          baseUrl = currentSettings.baseUrl,
          apiToken = currentSettings.effectiveApiToken,
        ).getOrElse { error ->
          _bankUiState.update {
            it.copy(
              isTestingDecoyUse = false,
              decoyUseOutcome =
                DecoyUseOutcome.Failed(
                  "Некорректный URL сервера: ${error.localizedMessage ?: "ошибка"}"
                ),
            )
          }
          return@launch
        }

      try {
        val response =
          api.useDecoyCode(
            DecoyUseRequest(
              userId = role.userId,
              code = code,
            )
          )
        if (response.isSuccessful) {
          val body = response.body()
          if (body != null) {
            _bankUiState.update {
              it.copy(
                isTestingDecoyUse = false,
                decoyUseOutcome =
                  DecoyUseOutcome.Evaluated(
                    valid = body.valid,
                    alertRaised = body.alertRaised,
                  ),
              )
            }
          } else {
            _bankUiState.update {
              it.copy(
                isTestingDecoyUse = false,
                decoyUseOutcome = DecoyUseOutcome.Failed("Пустой ответ от сервера"),
              )
            }
          }
        } else {
          val msg = formatApiError(response.code(), response.errorBody()?.string())
          _bankUiState.update {
            it.copy(
              isTestingDecoyUse = false,
              decoyUseOutcome = DecoyUseOutcome.Failed(msg),
            )
          }
        }
      } catch (e: Exception) {
        _bankUiState.update {
          it.copy(
            isTestingDecoyUse = false,
            decoyUseOutcome =
              DecoyUseOutcome.Failed(e.localizedMessage ?: "Сервер недоступен"),
          )
        }
      }
    }
  }
}
