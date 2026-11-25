package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SupervisorAccount
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.R
import com.example.alerts.AlertsScreen
import com.example.alerts.AlertsSyncState
import com.example.bank.BankScreen
import com.example.bank.BankUiState
import com.example.bank.ServerPingState
import com.example.core.AppRole
import com.example.core.SenimContract
import com.example.network.AlertDto
import com.example.network.DeviceRegistrationState
import com.example.notifications.ChatPipelineMessage
import com.example.notifications.CheckedNumberItem
import com.example.notifications.ExtractedNotificationLink
import com.example.notifications.MessengerChatScreen
import com.example.notifications.NotificationsScreen
import com.example.notifications.TelegramPollingState
import com.example.notifications.VideoCallTipItem
import com.example.ui.theme.SenimCobalt
import com.example.ui.theme.SenimCobaltDeep

private enum class UserTab(val labelRes: Int, val icon: ImageVector) {
  PROTECTION(R.string.tab_protection, Icons.Filled.Security),
  CHAT(R.string.tab_chat, Icons.AutoMirrored.Filled.Chat),
  BANK(R.string.tab_bank, Icons.Filled.AccountBalance),
}

@Composable
fun SenimApp(viewModel: SenimViewModel = viewModel()) {
  val settings by viewModel.settings.collectAsStateWithLifecycle()
  val pingState by viewModel.pingState.collectAsStateWithLifecycle()
  val registrationState by viewModel.registrationState.collectAsStateWithLifecycle()
  val extractedLinks by viewModel.extractedLinks.collectAsStateWithLifecycle()
  val checkedNumbers by viewModel.checkedNumbers.collectAsStateWithLifecycle()
  val videoCallTips by viewModel.videoCallTips.collectAsStateWithLifecycle()
  val chatMessages by viewModel.chatMessages.collectAsStateWithLifecycle()
  val telegramPollingState by viewModel.telegramPollingState.collectAsStateWithLifecycle()
  val alerts by viewModel.alerts.collectAsStateWithLifecycle()
  val alertsSyncState by viewModel.alertsSyncState.collectAsStateWithLifecycle()
  val bankUiState by viewModel.bankUiState.collectAsStateWithLifecycle()

  var showServerDialog by rememberSaveable { mutableStateOf(false) }

  val selectedRole = settings.selectedRole
  if (selectedRole == null) {
    RoleSelectionScreen(
      baseUrl = settings.baseUrl,
      onSelectRole = viewModel::selectRole,
      onOpenServerSettings = { showServerDialog = true },
    )
  } else {
    BackHandler {
      viewModel.clearRole()
    }
    MainRoleScaffold(
      role = selectedRole,
      baseUrl = settings.baseUrl,
      pingState = pingState,
      bankUiState = bankUiState,
      registrationState = registrationState,
      extractedLinks = extractedLinks,
      checkedNumbers = checkedNumbers,
      videoCallTips = videoCallTips,
      chatMessages = chatMessages,
      telegramPollingState = telegramPollingState,
      alerts = alerts,
      alertsSyncState = alertsSyncState,
      familyCodeWord = settings.familyCodeWord,
      trustedContactPhone = settings.trustedContactPhone,
      onSaveFamilyCodeWord = viewModel::saveFamilyCodeWord,
      onSaveTrustedContactPhone = viewModel::saveTrustedContactPhone,
      onRefreshAlerts = viewModel::refreshAlertsNow,
      onRetryLinkCheck = viewModel::retryLinkCheck,
      onRetryNumberCheck = viewModel::retryNumberCheck,
      onReportScammer = viewModel::reportScammerNumber,
      onClearExtractedLinks = viewModel::clearExtractedLinks,
      onSendDemoMessage = viewModel::sendDemoChatMessage,
      onStartTelegramPolling = viewModel::startTelegramPolling,
      onStopTelegramPolling = viewModel::stopTelegramPolling,
      onClearChat = viewModel::clearDemoChat,
      onRefreshBankAccount = viewModel::refreshBankAccount,
      onCloseDepositEarly = viewModel::closeDepositEarly,
      onSendTransfer = viewModel::sendTransfer,
      onCancelTransfer = viewModel::cancelTransfer,
      onIssueDecoyCode = viewModel::issueDecoyCode,
      onUseDecoyCode = viewModel::useDecoyCode,
      onDismissBankNotice = viewModel::dismissBankNotice,
      onRetryRegistration = viewModel::retryDeviceRegistration,
      onSwitchRole = viewModel::clearRole,
      onOpenServerSettings = { showServerDialog = true },
    )
  }

  if (showServerDialog) {
    ServerSettingsDialog(
      currentUrl = settings.baseUrl,
      currentApiToken = settings.apiAccessToken,
      hasBuildConfigToken = SenimContract.defaultApiToken().isNotEmpty(),
      onDismiss = { showServerDialog = false },
      onSave = { newUrl, newToken ->
        viewModel.updateServerSettings(newUrl, newToken)
        showServerDialog = false
      },
    )
  }
}

@Composable
private fun RoleSelectionScreen(
  baseUrl: String,
  onSelectRole: (AppRole) -> Unit,
  onOpenServerSettings: () -> Unit,
) {
  Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { innerPadding ->
    Column(
      modifier =
        Modifier
          .fillMaxSize()
          .padding(innerPadding)
          .verticalScroll(rememberScrollState())
          .padding(16.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Column(
        modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        Box(
          modifier =
            Modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(24.dp))
              .background(Brush.linearGradient(colors = listOf(SenimCobalt, SenimCobaltDeep)))
              .padding(24.dp)
        ) {
          Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(
              shape = RoundedCornerShape(14.dp),
              color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.16f),
            ) {
              Icon(
                imageVector = Icons.Filled.Shield,
                contentDescription = "Логотип Senim",
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.padding(10.dp),
              )
            }
            Text(
              text = stringResource(R.string.app_name),
              style = MaterialTheme.typography.headlineMedium,
              color = MaterialTheme.colorScheme.onPrimary,
            )
            Text(
              text = stringResource(R.string.role_selection_title),
              style = MaterialTheme.typography.titleLarge,
              color = MaterialTheme.colorScheme.onPrimary,
            )
            Text(
              text = stringResource(R.string.role_selection_subtitle),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.88f),
            )
          }
        }

        RoleOptionCard(
          title = stringResource(R.string.role_user_title),
          description = stringResource(R.string.role_user_desc),
          badge = "role=user • user_mom",
          icon = Icons.Filled.Person,
          testTag = "select_role_user_card",
          onClick = { onSelectRole(AppRole.USER) },
        )

        RoleOptionCard(
          title = stringResource(R.string.role_trusted_title),
          description = stringResource(R.string.role_trusted_desc),
          badge = "role=trusted • user_son",
          icon = Icons.Filled.SupervisorAccount,
          testTag = "select_role_trusted_card",
          onClick = { onSelectRole(AppRole.TRUSTED) },
        )

        OutlinedButton(
          onClick = onOpenServerSettings,
          modifier =
            Modifier
              .fillMaxWidth()
              .heightIn(min = 52.dp)
              .testTag("role_screen_server_settings_button"),
        ) {
          Icon(
            imageVector = Icons.Outlined.Settings,
            contentDescription = null,
            modifier = Modifier.padding(end = 8.dp),
          )
          Text(text = "Сервер: $baseUrl")
        }
      }
    }
  }
}

@Composable
private fun RoleOptionCard(
  title: String,
  description: String,
  badge: String,
  icon: ImageVector,
  testTag: String,
  onClick: () -> Unit,
) {
  Card(
    modifier =
      Modifier
        .fillMaxWidth()
        .minimumInteractiveComponentSize()
        .clip(RoundedCornerShape(20.dp))
        .clickable(onClick = onClick)
        .testTag(testTag),
    shape = RoundedCornerShape(20.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
  ) {
    Row(
      modifier = Modifier.padding(20.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
      ) {
        Icon(
          imageVector = icon,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onPrimaryContainer,
          modifier = Modifier.padding(12.dp),
        )
      }
      Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        Text(
          text = title,
          style = MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
          text = description,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
          text = badge,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.primary,
        )
      }
      Icon(
        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
        contentDescription = "Выбрать режим",
        tint = MaterialTheme.colorScheme.primary,
      )
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainRoleScaffold(
  role: AppRole,
  baseUrl: String,
  pingState: ServerPingState,
  bankUiState: BankUiState,
  registrationState: DeviceRegistrationState,
  extractedLinks: List<ExtractedNotificationLink>,
  checkedNumbers: List<CheckedNumberItem>,
  videoCallTips: List<VideoCallTipItem>,
  chatMessages: List<ChatPipelineMessage>,
  telegramPollingState: TelegramPollingState,
  alerts: List<AlertDto>,
  alertsSyncState: AlertsSyncState,
  familyCodeWord: String,
  trustedContactPhone: String,
  onSaveFamilyCodeWord: (String) -> Unit,
  onSaveTrustedContactPhone: (String) -> Unit,
  onRefreshAlerts: () -> Unit,
  onRetryLinkCheck: (String) -> Unit,
  onRetryNumberCheck: (String) -> Unit,
  onReportScammer: (String) -> Unit,
  onClearExtractedLinks: () -> Unit,
  onSendDemoMessage: (String, String, String, String) -> Unit,
  onStartTelegramPolling: () -> Unit,
  onStopTelegramPolling: () -> Unit,
  onClearChat: () -> Unit,
  onRefreshBankAccount: () -> Unit,
  onCloseDepositEarly: (String) -> Unit,
  onSendTransfer: (String, String) -> Unit,
  onCancelTransfer: (String) -> Unit,
  onIssueDecoyCode: () -> Unit,
  onUseDecoyCode: (String) -> Unit,
  onDismissBankNotice: () -> Unit,
  onRetryRegistration: () -> Unit,
  onSwitchRole: () -> Unit,
  onOpenServerSettings: () -> Unit,
) {
  var selectedUserTab by rememberSaveable { mutableStateOf(UserTab.PROTECTION) }

  BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
    val useNavigationRail = maxWidth >= 600.dp && role == AppRole.USER

    Scaffold(
      contentWindowInsets = WindowInsets.safeDrawing,
      topBar = {
        TopAppBar(
          title = {
            Column {
              Text(
                text = "Senim • ${role.displayTitle}",
                style = MaterialTheme.typography.titleMedium,
              )
              Text(
                text = "user_id: ${role.userId}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          },
          colors =
            TopAppBarDefaults.topAppBarColors(
              containerColor = MaterialTheme.colorScheme.surface,
              titleContentColor = MaterialTheme.colorScheme.onSurface,
            ),
          actions = {
            IconButton(
              onClick = onOpenServerSettings,
              modifier = Modifier.testTag("open_server_settings_button"),
            ) {
              Icon(
                imageVector = Icons.Outlined.Settings,
                contentDescription = stringResource(R.string.server_settings),
              )
            }
            IconButton(
              onClick = onSwitchRole,
              modifier = Modifier.testTag("switch_role_button"),
            ) {
              Icon(
                imageVector = Icons.Outlined.SwapHoriz,
                contentDescription = stringResource(R.string.switch_role),
              )
            }
          },
        )
      },
      bottomBar = {
        if (role == AppRole.USER && !useNavigationRail) {
          NavigationBar {
            UserTab.entries.forEach { tab ->
              NavigationBarItem(
                selected = selectedUserTab == tab,
                onClick = { selectedUserTab = tab },
                icon = { Icon(imageVector = tab.icon, contentDescription = null) },
                label = { Text(stringResource(tab.labelRes)) },
                modifier = Modifier.testTag("bottom_tab_${tab.name.lowercase()}"),
              )
            }
          }
        }
      },
    ) { innerPadding ->
      Row(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
        if (useNavigationRail) {
          NavigationRail(modifier = Modifier.fillMaxHeight()) {
            Spacer(modifier = Modifier.height(12.dp))
            UserTab.entries.forEach { tab ->
              NavigationRailItem(
                selected = selectedUserTab == tab,
                onClick = { selectedUserTab = tab },
                icon = { Icon(imageVector = tab.icon, contentDescription = null) },
                label = { Text(stringResource(tab.labelRes)) },
                modifier = Modifier.testTag("rail_tab_${tab.name.lowercase()}"),
              )
            }
          }
        }

        Column(modifier = Modifier.weight(1f).fillMaxSize()) {
          DeviceRegistrationBanner(
            state = registrationState,
            onRetry = onRetryRegistration,
          )

          Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (role) {
              AppRole.USER -> {
                when (selectedUserTab) {
                  UserTab.PROTECTION ->
                    NotificationsScreen(
                      userId = role.userId,
                      baseUrl = baseUrl,
                      extractedLinks = extractedLinks,
                      checkedNumbers = checkedNumbers,
                      videoCallTips = videoCallTips,
                      familyCodeWord = familyCodeWord,
                      onSaveFamilyCodeWord = onSaveFamilyCodeWord,
                      onRetryLinkCheck = onRetryLinkCheck,
                      onRetryNumberCheck = onRetryNumberCheck,
                      onReportScammer = onReportScammer,
                      onClearExtractedLinks = onClearExtractedLinks,
                    )
                  UserTab.CHAT ->
                    MessengerChatScreen(
                      chatMessages = chatMessages,
                      telegramPollingState = telegramPollingState,
                      onSendDemoMessage = onSendDemoMessage,
                      onStartTelegramPolling = onStartTelegramPolling,
                      onStopTelegramPolling = onStopTelegramPolling,
                      onClearChat = onClearChat,
                    )
                  UserTab.BANK ->
                    BankScreen(
                      userId = role.userId,
                      baseUrl = baseUrl,
                      pingState = pingState,
                      bankUiState = bankUiState,
                      onRefreshAccount = onRefreshBankAccount,
                      onCloseDepositEarly = onCloseDepositEarly,
                      onSendTransfer = onSendTransfer,
                      onCancelTransfer = onCancelTransfer,
                      onIssueDecoyCode = onIssueDecoyCode,
                      onUseDecoyCode = onUseDecoyCode,
                      onDismissNotice = onDismissBankNotice,
                    )
                }
              }
              AppRole.TRUSTED -> {
                AlertsScreen(
                  userId = role.userId,
                  baseUrl = baseUrl,
                  alerts = alerts,
                  syncState = alertsSyncState,
                  trustedContactPhone = trustedContactPhone,
                  onRefreshAlerts = onRefreshAlerts,
                  onSaveTrustedPhone = onSaveTrustedContactPhone,
                )
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun DeviceRegistrationBanner(
  state: DeviceRegistrationState,
  onRetry: () -> Unit,
) {
  if (state is DeviceRegistrationState.Idle) return

  val containerColor =
    when (state) {
      is DeviceRegistrationState.Registered -> MaterialTheme.colorScheme.primaryContainer
      is DeviceRegistrationState.Error -> MaterialTheme.colorScheme.errorContainer
      DeviceRegistrationState.Registering,
      DeviceRegistrationState.Idle -> MaterialTheme.colorScheme.surfaceVariant
    }
  val contentColor =
    when (state) {
      is DeviceRegistrationState.Registered -> MaterialTheme.colorScheme.onPrimaryContainer
      is DeviceRegistrationState.Error -> MaterialTheme.colorScheme.onErrorContainer
      DeviceRegistrationState.Registering,
      DeviceRegistrationState.Idle -> MaterialTheme.colorScheme.onSurfaceVariant
    }

  Surface(
    color = containerColor,
    modifier = Modifier.fillMaxWidth().testTag("device_registration_banner"),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      when (state) {
        DeviceRegistrationState.Registering -> {
          CircularProgressIndicator(
            strokeWidth = 2.dp,
            color = contentColor,
            modifier = Modifier.size(18.dp),
          )
          Text(
            text = "Регистрация устройства на сервере...",
            style = MaterialTheme.typography.bodyMedium,
            color = contentColor,
            modifier = Modifier.weight(1f),
          )
        }
        is DeviceRegistrationState.Registered -> {
          Icon(
            imageVector = Icons.Outlined.CheckCircle,
            contentDescription = "Устройство зарегистрировано",
            tint = contentColor,
          )
          val tokenLabel =
            if (state.isRealFcmToken) "FCM подключён" else "без google-services.json (резервный токен)"
          Text(
            text = "Устройство зарегистрировано (${state.userId}, ${state.role}) • $tokenLabel",
            style = MaterialTheme.typography.labelLarge,
            color = contentColor,
            modifier = Modifier.weight(1f),
          )
        }
        is DeviceRegistrationState.Error -> {
          Icon(
            imageVector = Icons.Outlined.ErrorOutline,
            contentDescription = "Ошибка регистрации устройства",
            tint = contentColor,
          )
          Text(
            text = "Не удалось зарегистрировать устройство: ${state.message}",
            style = MaterialTheme.typography.bodyMedium,
            color = contentColor,
            modifier = Modifier.weight(1f),
          )
          IconButton(
            onClick = onRetry,
            modifier = Modifier.testTag("retry_registration_button"),
          ) {
            Icon(
              imageVector = Icons.Outlined.Refresh,
              contentDescription = "Повторить регистрацию",
              tint = contentColor,
            )
          }
        }
        DeviceRegistrationState.Idle -> Unit
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ServerSettingsDialog(
  currentUrl: String,
  currentApiToken: String,
  hasBuildConfigToken: Boolean,
  onDismiss: () -> Unit,
  onSave: (String, String) -> Unit,
) {
  var urlDraft by remember(currentUrl) { mutableStateOf(currentUrl) }
  var apiTokenDraft by remember(currentApiToken) { mutableStateOf(currentApiToken) }
  var showToken by rememberSaveable { mutableStateOf(false) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.server_settings)) },
    text = {
      Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Text(
          text = "Укажите адрес сервера Senim и ключ доступа (Bearer-токен для заголовка Authorization).",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
          value = urlDraft,
          onValueChange = { urlDraft = it },
          label = { Text(stringResource(R.string.server_url_label)) },
          placeholder = { Text(stringResource(R.string.server_url_hint)) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("server_url_input"),
        )
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          FilterChip(
            selected = urlDraft.trim().trimEnd('/') == SenimContract.DEFAULT_BASE_URL,
            onClick = { urlDraft = SenimContract.DEFAULT_BASE_URL },
            label = { Text("Сервер Senim") },
            modifier = Modifier.testTag("preset_cloud_url_chip"),
          )
          FilterChip(
            selected = urlDraft.trim().trimEnd('/') == SenimContract.LOCAL_WIFI_BASE_URL,
            onClick = { urlDraft = SenimContract.LOCAL_WIFI_BASE_URL },
            label = { Text("Wi-Fi IP") },
            modifier = Modifier.testTag("preset_wifi_ip_chip"),
          )
          FilterChip(
            selected = urlDraft.trim().trimEnd('/') == SenimContract.EMULATOR_BASE_URL,
            onClick = { urlDraft = SenimContract.EMULATOR_BASE_URL },
            label = { Text("Эмулятор 10.0.2.2") },
            modifier = Modifier.testTag("preset_emulator_ip_chip"),
          )
        }
        OutlinedTextField(
          value = apiTokenDraft,
          onValueChange = { apiTokenDraft = it },
          label = { Text("Ключ доступа (Bearer)") },
          placeholder = {
            Text(
              if (hasBuildConfigToken) {
                "Задан через BuildConfig (можно переопределить)"
              } else {
                "Введите Bearer-токен доступа"
              }
            )
          },
          singleLine = true,
          visualTransformation =
            if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
          trailingIcon = {
            IconButton(
              onClick = { showToken = !showToken },
              modifier = Modifier.testTag("toggle_api_key_visibility_button"),
            ) {
              Icon(
                imageVector = if (showToken) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                contentDescription = if (showToken) "Скрыть ключ доступа" else "Показать ключ доступа",
              )
            }
          },
          modifier = Modifier.fillMaxWidth().testTag("server_api_key_input"),
        )
      }
    },
    confirmButton = {
      Button(
        onClick = { onSave(urlDraft, apiTokenDraft) },
        modifier = Modifier.heightIn(min = 48.dp).testTag("save_server_url_button"),
      ) {
        Text(stringResource(R.string.save))
      }
    },
    dismissButton = {
      TextButton(
        onClick = onDismiss,
        modifier = Modifier.heightIn(min = 48.dp).testTag("cancel_server_url_button"),
      ) {
        Text(stringResource(R.string.cancel))
      }
    },
  )
}
