package com.example.notifications

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Report
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.PersonSearch
import androidx.compose.material.icons.outlined.PhoneInTalk
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Экран модуля защиты уведомлений:
 * - запрос доступа NotificationListenerService и разрешения POST_NOTIFICATIONS (Android 13+)
 * - проверка извлечённых ссылок через POST /links/check
 * - предупреждение с причинами (reasons) при risk=high
 */
@Composable
fun NotificationsScreen(
  userId: String,
  baseUrl: String,
  extractedLinks: List<ExtractedNotificationLink>,
  checkedNumbers: List<CheckedNumberItem>,
  videoCallTips: List<VideoCallTipItem>,
  familyCodeWord: String,
  onSaveFamilyCodeWord: (String) -> Unit,
  onRetryLinkCheck: (String) -> Unit,
  onRetryNumberCheck: (String) -> Unit,
  onReportScammer: (String) -> Unit,
  onClearExtractedLinks: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current

  var hasNotificationAccess by remember {
    mutableStateOf(isNotificationListenerEnabled(context))
  }
  var hasPostNotificationsPermission by remember {
    mutableStateOf(hasPostNotificationPermission(context))
  }
  var hasCallScreeningRole by remember {
    mutableStateOf(isCallScreeningRoleHeld(context))
  }
  val isCallScreeningSupported = remember { isCallScreeningRoleAvailable(context) }
  var showGatekeeperGuide by remember { mutableStateOf(false) }
  var showCodeWordDialog by remember { mutableStateOf(false) }

  val postNotificationLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      hasPostNotificationsPermission = granted
    }

  val callScreeningRoleLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
      hasCallScreeningRole = isCallScreeningRoleHeld(context)
    }

  DisposableEffect(lifecycleOwner, context) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME) {
        hasNotificationAccess = isNotificationListenerEnabled(context)
        hasPostNotificationsPermission = hasPostNotificationPermission(context)
        hasCallScreeningRole = isCallScreeningRoleHeld(context)
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose {
      lifecycleOwner.lifecycle.removeObserver(observer)
    }
  }

  val highRiskItems =
    remember(extractedLinks) {
      extractedLinks.filter { it.status is LinkCheckStatus.HighRisk }
    }
  val highRiskNumbers =
    remember(checkedNumbers) {
      checkedNumbers.filter { it.status is NumberCheckStatus.HighRisk }
    }

  Column(
    modifier =
      modifier
        .fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(16.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Column(
      modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth(),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Card(
        modifier = Modifier.fillMaxWidth().testTag("protection_status_card"),
        shape = RoundedCornerShape(20.dp),
        colors =
          CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
      ) {
        Row(
          modifier = Modifier.padding(20.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
          Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.primary,
          ) {
            Icon(
              imageVector = Icons.Filled.Shield,
              contentDescription = "Статус защиты Senim",
              tint = MaterialTheme.colorScheme.onPrimary,
              modifier = Modifier.padding(12.dp),
            )
          }
          Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
              text =
                if (hasNotificationAccess) {
                  "Защита уведомлений активна"
                } else {
                  "Требуется доступ к уведомлениям"
                },
              style = MaterialTheme.typography.titleMedium,
              color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
              text = "Профиль: $userId • Сервер: $baseUrl",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
            )
          }
        }
      }

      if (highRiskItems.isNotEmpty()) {
        HighRiskSummaryBanner(latestItem = highRiskItems.first())
      }

      if (highRiskNumbers.isNotEmpty()) {
        HighRiskNumberBanner(latestItem = highRiskNumbers.first())
      }

      NotificationPermissionCard(
        isGranted = hasNotificationAccess,
        onOpenSettings = { openNotificationListenerSettings(context) },
      )

      GatekeeperInstructionCard(
        onOpenGuide = { showGatekeeperGuide = true },
        onOpenDefaultLinksSettings = { openDefaultLinkHandlerSettings(context) },
      )

      CallScreeningCard(
        isSupported = isCallScreeningSupported,
        isRoleHeld = hasCallScreeningRole,
        onRequestRole = {
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(RoleManager::class.java)
            if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)) {
              val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING)
              callScreeningRoleLauncher.launch(intent)
            }
          }
        },
      )

      FamilyCodeWordCard(
        familyCodeWord = familyCodeWord,
        onEdit = { showCodeWordDialog = true },
      )

      if (videoCallTips.isNotEmpty()) {
        Text(
          text = "Памятки при видеозвонке (${videoCallTips.size})",
          style = MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.onBackground,
        )
        videoCallTips.forEach { tip ->
          VideoCallTipCard(
            item = tip,
            familyCodeWord = familyCodeWord,
          )
        }
      }

      if (!hasPostNotificationsPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        PostNotificationsPermissionCard(
          onRequestPermission = {
            postNotificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
          }
        )
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Text(
          text = "Проверенные ссылки (${extractedLinks.size})",
          style = MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.onBackground,
        )
        if (extractedLinks.isNotEmpty()) {
          TextButton(
            onClick = onClearExtractedLinks,
            modifier = Modifier.heightIn(min = 48.dp).testTag("clear_extracted_links_button"),
          ) {
            Icon(
              imageVector = Icons.Outlined.DeleteOutline,
              contentDescription = null,
              modifier = Modifier.padding(end = 6.dp),
            )
            Text("Очистить")
          }
        }
      }

      if (extractedLinks.isEmpty()) {
        Card(
          modifier = Modifier.fillMaxWidth().testTag("extracted_links_empty_card"),
          shape = RoundedCornerShape(16.dp),
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
          elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        ) {
          Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
              Icon(
                imageVector = Icons.Outlined.Link,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
              )
              Text(
                text = "Проверенных ссылок пока нет",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
              )
            }
            Text(
              text =
                "Когда в WhatsApp или Telegram придёт уведомление со ссылкой, Senim отправит на сервер только нормализованный URL (без текста сообщения) и покажет результат проверки.",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      } else {
        extractedLinks.forEach { item ->
          ExtractedLinkCard(
            item = item,
            onRetry = { onRetryLinkCheck(item.id) },
          )
        }
      }

      Text(
        text = "Неизвестные отправители (${checkedNumbers.size})",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onBackground,
      )

      if (checkedNumbers.isEmpty()) {
        Card(
          modifier = Modifier.fillMaxWidth().testTag("checked_numbers_empty_card"),
          shape = RoundedCornerShape(16.dp),
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
          elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        ) {
          Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
              Icon(
                imageVector = Icons.Outlined.PersonSearch,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
              )
              Text(
                text = "Неизвестных номеров пока нет",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
              )
            }
            Text(
              text =
                "Если сообщение придёт с номера не из контактов, Senim проанализирует последние 10 сообщений на телефоне и сверит SHA-256 хеш номера с базой жалоб.",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      } else {
        checkedNumbers.forEach { item ->
          CheckedNumberCard(
            item = item,
            onRetry = { onRetryNumberCheck(item.id) },
            onReportScammer = { onReportScammer(item.id) },
          )
        }
      }

      Spacer(modifier = Modifier.height(8.dp))
    }
  }

  if (showGatekeeperGuide) {
    GatekeeperGuideDialog(
      onDismiss = { showGatekeeperGuide = false },
      onOpenSettings = {
        showGatekeeperGuide = false
        openDefaultLinkHandlerSettings(context)
      },
    )
  }

  if (showCodeWordDialog) {
    FamilyCodeWordDialog(
      currentWord = familyCodeWord,
      onDismiss = { showCodeWordDialog = false },
      onSave = { newWord ->
        onSaveFamilyCodeWord(newWord)
        showCodeWordDialog = false
      },
    )
  }
}

@Composable
private fun HighRiskSummaryBanner(latestItem: ExtractedNotificationLink) {
  val status = latestItem.status as? LinkCheckStatus.HighRisk ?: return

  Card(
    modifier = Modifier.fillMaxWidth().testTag("high_risk_warning_banner"),
    shape = RoundedCornerShape(20.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
  ) {
    Column(
      modifier = Modifier.padding(20.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Icon(
          imageVector = Icons.Filled.Warning,
          contentDescription = "Опасная ссылка",
          tint = MaterialTheme.colorScheme.error,
        )
        Column {
          Text(
            text = "Осторожно: обнаружена опасная ссылка!",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
          )
          Text(
            text = "${latestItem.messengerLabel} • ${latestItem.normalizedUrl}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
          )
        }
      }

      if (status.reasons.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Text(
            text = "Причины:",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onErrorContainer,
          )
          status.reasons.forEach { reason ->
            Text(
              text = "• $reason",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onErrorContainer,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun PostNotificationsPermissionCard(onRequestPermission: () -> Unit) {
  Card(
    modifier = Modifier.fillMaxWidth().testTag("post_notifications_permission_card"),
    shape = RoundedCornerShape(18.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
  ) {
    Column(
      modifier = Modifier.padding(18.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Icon(
          imageVector = Icons.Outlined.NotificationsNone,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
        )
        Text(
          text = "Разрешите показ предупреждений",
          style = MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.onSurface,
        )
      }
      Text(
        text =
          "Чтобы сразу предупреждать вас всплывающим уведомлением при опасной ссылке, разрешите Senim показывать уведомления.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      OutlinedButton(
        onClick = onRequestPermission,
        modifier =
          Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .testTag("grant_post_notifications_button"),
      ) {
        Text("Разрешить уведомления")
      }
    }
  }
}

@Composable
private fun NotificationPermissionCard(
  isGranted: Boolean,
  onOpenSettings: () -> Unit,
) {
  Card(
    modifier = Modifier.fillMaxWidth().testTag("notification_permission_card"),
    shape = RoundedCornerShape(18.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
  ) {
    Column(
      modifier = Modifier.padding(18.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Icon(
          imageVector =
            if (isGranted) Icons.Outlined.CheckCircle else Icons.Filled.NotificationsActive,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
        )
        Text(
          text =
            if (isGranted) {
              "Доступ к уведомлениям разрешён"
            } else {
              "Зачем Senim доступ к уведомлениям"
            },
          style = MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.onSurface,
        )
      }

      Text(
        text =
          "Senim просматривает уведомления WhatsApp и Telegram только в памяти телефона, чтобы вовремя заметить опасную ссылку. Если содержимое уведомления скрыто настройками приватности, приложение ничего не делает.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
      ) {
        Row(
          modifier = Modifier.padding(12.dp),
          verticalAlignment = Alignment.Top,
          horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          Icon(
            imageVector = Icons.Outlined.PrivacyTip,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
          )
          Text(
            text =
              "Гарантия приватности: текст переписки никогда не сохраняется на диск, не пишется в лог и не отправляется на сервер — наружу уходит только адрес ссылки.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }

      if (isGranted) {
        OutlinedButton(
          onClick = onOpenSettings,
          modifier =
            Modifier
              .fillMaxWidth()
              .heightIn(min = 48.dp)
              .testTag("manage_notification_access_button"),
        ) {
          Icon(
            imageVector = Icons.Outlined.Settings,
            contentDescription = null,
            modifier = Modifier.padding(end = 8.dp),
          )
          Text("Настройки доступа к уведомлениям")
        }
      } else {
        Button(
          onClick = onOpenSettings,
          modifier =
            Modifier
              .fillMaxWidth()
              .heightIn(min = 48.dp)
              .testTag("grant_notification_access_button"),
        ) {
          Text("Разрешить доступ к уведомлениям")
        }
      }
    }
  }
}

@Composable
private fun ExtractedLinkCard(
  item: ExtractedNotificationLink,
  onRetry: () -> Unit,
) {
  val formattedTime =
    remember(item.timestampMs) {
      SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(item.timestampMs))
    }

  val containerColor =
    when (item.status) {
      is LinkCheckStatus.HighRisk -> MaterialTheme.colorScheme.errorContainer
      is LinkCheckStatus.LowRisk -> MaterialTheme.colorScheme.surface
      is LinkCheckStatus.Error -> MaterialTheme.colorScheme.surfaceVariant
      LinkCheckStatus.Checking -> MaterialTheme.colorScheme.surface
    }

  Card(
    modifier = Modifier.fillMaxWidth().testTag("extracted_link_card_${item.id}"),
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(containerColor = containerColor),
    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
  ) {
    Column(
      modifier = Modifier.padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = item.messengerLabel,
          style = MaterialTheme.typography.labelLarge,
          color =
            if (item.status is LinkCheckStatus.HighRisk) {
              MaterialTheme.colorScheme.onErrorContainer
            } else {
              MaterialTheme.colorScheme.primary
            },
        )
        Text(
          text = formattedTime,
          style = MaterialTheme.typography.labelSmall,
          color =
            if (item.status is LinkCheckStatus.HighRisk) {
              MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
            } else {
              MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
      }

      Text(
        text = item.normalizedUrl,
        style = MaterialTheme.typography.bodyLarge,
        color =
          if (item.status is LinkCheckStatus.HighRisk) {
            MaterialTheme.colorScheme.onErrorContainer
          } else {
            MaterialTheme.colorScheme.onSurface
          },
      )

      when (val status = item.status) {
        LinkCheckStatus.Checking -> {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
            Text(
              text = "Проверяется на сервере...",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
        is LinkCheckStatus.LowRisk -> {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Icon(
              imageVector = Icons.Outlined.VerifiedUser,
              contentDescription = "Безопасная ссылка",
              tint = MaterialTheme.colorScheme.primary,
            )
            Text(
              text = "Риск низкий (risk=low)",
              style = MaterialTheme.typography.labelLarge,
              color = MaterialTheme.colorScheme.primary,
            )
          }
          if (status.reasons.isNotEmpty()) {
            status.reasons.forEach { reason ->
              Text(
                text = "• $reason",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }
        is LinkCheckStatus.HighRisk -> {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Icon(
              imageVector = Icons.Filled.Warning,
              contentDescription = "Опасная ссылка",
              tint = MaterialTheme.colorScheme.error,
            )
            Text(
              text = "Высокий риск (risk=high)",
              style = MaterialTheme.typography.labelLarge,
              color = MaterialTheme.colorScheme.error,
            )
          }
          if (status.reasons.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
              status.reasons.forEach { reason ->
                Text(
                  text = "• $reason",
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onErrorContainer,
                )
              }
            }
          }
        }
        is LinkCheckStatus.Error -> {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Icon(
              imageVector = Icons.Outlined.ErrorOutline,
              contentDescription = "Ошибка проверки",
              tint = MaterialTheme.colorScheme.error,
            )
            Text(
              text = "Ошибка проверки: ${status.message}",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.weight(1f),
            )
          }
          OutlinedButton(
            onClick = onRetry,
            modifier = Modifier.heightIn(min = 48.dp).testTag("retry_link_check_${item.id}"),
          ) {
            Icon(
              imageVector = Icons.Outlined.Refresh,
              contentDescription = null,
              modifier = Modifier.padding(end = 6.dp),
            )
            Text("Повторить проверку")
          }
        }
      }
    }
  }
}

private fun isNotificationListenerEnabled(context: Context): Boolean =
  NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

private fun hasPostNotificationPermission(context: Context): Boolean =
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
      PackageManager.PERMISSION_GRANTED
  } else {
    true
  }

private fun openNotificationListenerSettings(context: Context) {
  val listenerIntent =
    Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
  val started = runCatching { context.startActivity(listenerIntent) }.isSuccess
  if (!started) {
    val fallbackIntent =
      Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", context.packageName, null)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
    runCatching { context.startActivity(fallbackIntent) }
  }
}

@Composable
private fun GatekeeperInstructionCard(
  onOpenGuide: () -> Unit,
  onOpenDefaultLinksSettings: () -> Unit,
) {
  Card(
    modifier = Modifier.fillMaxWidth().testTag("gatekeeper_instruction_card"),
    shape = RoundedCornerShape(18.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
  ) {
    Column(
      modifier = Modifier.padding(18.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Icon(
          imageVector = Icons.Outlined.OpenInBrowser,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
        )
        Text(
          text = "«Привратник» для ссылок",
          style = MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.onSurface,
        )
      }
      Text(
        text =
          "При нажатии на ссылку в мессенджере сначала откроется Senim, проверит адрес на сервере и только при низком риске передаст его в браузер.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        OutlinedButton(
          onClick = onOpenGuide,
          modifier =
            Modifier
              .weight(1f)
              .heightIn(min = 48.dp)
              .testTag("open_gatekeeper_guide_button"),
        ) {
          Icon(
            imageVector = Icons.Outlined.HelpOutline,
            contentDescription = null,
            modifier = Modifier.padding(end = 6.dp),
          )
          Text("Инструкция")
        }
        Button(
          onClick = onOpenDefaultLinksSettings,
          modifier =
            Modifier
              .weight(1f)
              .heightIn(min = 48.dp)
              .testTag("open_default_link_settings_button"),
        ) {
          Text("Настроить")
        }
      }
    }
  }
}

@Composable
private fun GatekeeperGuideDialog(
  onDismiss: () -> Unit,
  onOpenSettings: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Как выбрать Senim обработчиком ссылок") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
          text = "1. Нажмите «Открыть настройки» ниже — откроется экран настроек приложения Senim.",
          style = MaterialTheme.typography.bodyMedium,
        )
        Text(
          text = "2. Откройте пункт «Открывать по умолчанию» (Open by default) и включите поддержку ссылок.",
          style = MaterialTheme.typography.bodyMedium,
        )
        Text(
          text = "3. В настройках системы («Приложения по умолчанию» -> «Браузер») или при первом нажатии на ссылку в мессенджере выберите Senim.",
          style = MaterialTheme.typography.bodyMedium,
        )
        Text(
          text = "4. В WhatsApp/Telegram отключите «Встроенный браузер», если ссылки открываются внутри мессенджера.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    },
    confirmButton = {
      Button(
        onClick = onOpenSettings,
        modifier = Modifier.heightIn(min = 48.dp).testTag("guide_open_settings_button"),
      ) {
        Text("Открыть настройки")
      }
    },
    dismissButton = {
      TextButton(
        onClick = onDismiss,
        modifier = Modifier.heightIn(min = 48.dp).testTag("guide_close_button"),
      ) {
        Text("Понятно")
      }
    },
  )
}

private fun openDefaultLinkHandlerSettings(context: Context) {
  val targetIntent =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS).apply {
        data = Uri.fromParts("package", context.packageName, null)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
    } else {
      Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
    }
  val started = runCatching { context.startActivity(targetIntent) }.isSuccess
  if (!started) {
    val fallbackIntent =
      Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", context.packageName, null)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
    runCatching { context.startActivity(fallbackIntent) }
  }
}

@Composable
private fun HighRiskNumberBanner(latestItem: CheckedNumberItem) {
  val status = latestItem.status as? NumberCheckStatus.HighRisk ?: return

  Card(
    modifier = Modifier.fillMaxWidth().testTag("high_risk_number_banner"),
    shape = RoundedCornerShape(20.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
  ) {
    Column(
      modifier = Modifier.padding(20.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Icon(
          imageVector = Icons.Filled.Warning,
          contentDescription = "Подозрительный номер",
          tint = MaterialTheme.colorScheme.error,
        )
        Column {
          Text(
            text = "Возможен обман с неизвестного номера!",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
          )
          Text(
            text = "${latestItem.messengerLabel} • ${latestItem.displaySender}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
          )
        }
      }
      status.reasons.forEach { reason ->
        Text(
          text = "• $reason",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onErrorContainer,
        )
      }
    }
  }
}

@Composable
private fun CheckedNumberCard(
  item: CheckedNumberItem,
  onRetry: () -> Unit,
  onReportScammer: () -> Unit,
) {
  val formattedTime =
    remember(item.timestampMs) {
      SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(item.timestampMs))
    }

  val containerColor =
    when (item.status) {
      is NumberCheckStatus.HighRisk -> MaterialTheme.colorScheme.errorContainer
      is NumberCheckStatus.LowRisk -> MaterialTheme.colorScheme.surface
      is NumberCheckStatus.Error -> MaterialTheme.colorScheme.surfaceVariant
      NumberCheckStatus.Checking -> MaterialTheme.colorScheme.surface
    }

  Card(
    modifier = Modifier.fillMaxWidth().testTag("checked_number_card_${item.id}"),
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(containerColor = containerColor),
    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
  ) {
    Column(
      modifier = Modifier.padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = "${item.messengerLabel} • ${item.displaySender}",
          style = MaterialTheme.typography.titleMedium,
          color =
            if (item.status is NumberCheckStatus.HighRisk) {
              MaterialTheme.colorScheme.onErrorContainer
            } else {
              MaterialTheme.colorScheme.onSurface
            },
        )
        Text(
          text = formattedTime,
          style = MaterialTheme.typography.labelSmall,
          color =
            if (item.status is NumberCheckStatus.HighRisk) {
              MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
            } else {
              MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
      }

      val scoreFormatted = remember(item.localScore) { String.format(Locale.US, "%.2f", item.localScore) }
      val flagsText =
        if (item.patternFlags.isEmpty()) "нет" else item.patternFlags.joinToString(", ")
      Text(
        text = "in_contacts=false • local_score=$scoreFormatted • признаки: $flagsText",
        style = MaterialTheme.typography.bodyMedium,
        color =
          if (item.status is NumberCheckStatus.HighRisk) {
            MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.9f)
          } else {
            MaterialTheme.colorScheme.onSurfaceVariant
          },
      )

      when (val status = item.status) {
        NumberCheckStatus.Checking -> {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
            Text(
              text = "Сверяем хеш номера с сервером...",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
        is NumberCheckStatus.LowRisk -> {
          Text(
            text = "Риск низкий (risk=low)",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
          )
          status.reasons.forEach { reason ->
            Text(
              text = "• $reason",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
        is NumberCheckStatus.HighRisk -> {
          Text(
            text = "Высокий риск (risk=high)",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.error,
          )
          if (status.sources.isNotEmpty()) {
            Text(
              text = "Источники: ${status.sources.joinToString(", ")}",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onErrorContainer,
            )
          }
          status.reasons.forEach { reason ->
            Text(
              text = "• $reason",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onErrorContainer,
            )
          }
        }
        is NumberCheckStatus.Error -> {
          Text(
            text = "Ошибка проверки: ${status.message}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          OutlinedButton(
            onClick = onRetry,
            modifier = Modifier.heightIn(min = 48.dp).testTag("retry_number_check_${item.id}"),
          ) {
            Icon(
              imageVector = Icons.Outlined.Refresh,
              contentDescription = null,
              modifier = Modifier.padding(end = 6.dp),
            )
            Text("Повторить проверку")
          }
        }
      }

      when (val report = item.reportStatus) {
        NumberReportStatus.Idle -> {
          OutlinedButton(
            onClick = onReportScammer,
            modifier =
              Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag("report_scammer_${item.id}"),
          ) {
            Icon(
              imageVector = Icons.Filled.Report,
              contentDescription = null,
              modifier = Modifier.padding(end = 8.dp),
            )
            Text("Это мошенник")
          }
        }
        NumberReportStatus.Sending -> {
          OutlinedButton(
            onClick = {},
            enabled = false,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
          ) {
            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp).padding(end = 8.dp))
            Text("Отправка жалобы...")
          }
        }
        NumberReportStatus.Reported -> {
          Text(
            text = "Жалоба отправлена в базу (POST /numbers/report)",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
          )
        }
        is NumberReportStatus.Error -> {
          Text(
            text = "Ошибка отправки жалобы: ${report.message}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
          )
          OutlinedButton(
            onClick = onReportScammer,
            modifier =
              Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag("retry_report_scammer_${item.id}"),
          ) {
            Text("Повторить: Это мошенник")
          }
        }
      }
    }
  }
}

@Composable
private fun FamilyCodeWordCard(
  familyCodeWord: String,
  onEdit: () -> Unit,
) {
  Card(
    modifier = Modifier.fillMaxWidth().testTag("family_code_word_card"),
    shape = RoundedCornerShape(18.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
  ) {
    Column(
      modifier = Modifier.padding(18.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Icon(
          imageVector = Icons.Outlined.VpnKey,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
        )
        Text(
          text = "Защита при видеозвонке",
          style = MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.onSurface,
        )
      }
      Text(
        text =
          "При входящем видеозвонке с номера не из контактов Senim покажет памятку проверки собеседника. Лица не снимаются, ничего не уходит на сервер.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Text(
        text =
          if (familyCodeWord.isNotBlank()) {
            "Семейное кодовое слово: задано (хранится только на телефоне)"
          } else {
            "Семейное кодовое слово: не задано"
          },
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
      )
      OutlinedButton(
        onClick = onEdit,
        modifier =
          Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .testTag("edit_family_code_word_button"),
      ) {
        Text(
          if (familyCodeWord.isNotBlank()) "Изменить кодовое слово" else "Задать кодовое слово"
        )
      }
    }
  }
}

@Composable
private fun VideoCallTipCard(
  item: VideoCallTipItem,
  familyCodeWord: String,
) {
  val formattedTime =
    remember(item.timestampMs) {
      SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(item.timestampMs))
    }

  Card(
    modifier = Modifier.fillMaxWidth().testTag("videocall_tip_card_${item.id}"),
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
  ) {
    Column(
      modifier = Modifier.padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Icon(
            imageVector = Icons.Outlined.Videocam,
            contentDescription = "Памятка при видеозвонке",
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
          )
          Text(
            text = "${item.messengerLabel} • ${item.displaySender}",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
          )
        }
        Text(
          text = formattedTime,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
        )
      }
      Text(
        text = "1. Попросите провести рукой перед лицом и повернуть голову",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
      )
      Text(
        text =
          if (familyCodeWord.isNotBlank()) {
            "2. Попросите назвать семейное кодовое слово (ваше слово: $familyCodeWord)"
          } else {
            "2. Попросите назвать семейное кодовое слово"
          },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
      )
      Text(
        text = "3. Перезвоните сами по известному номеру",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
      )
    }
  }
}

@Composable
private fun FamilyCodeWordDialog(
  currentWord: String,
  onDismiss: () -> Unit,
  onSave: (String) -> Unit,
) {
  var wordDraft by remember(currentWord) { mutableStateOf(currentWord) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Семейное кодовое слово") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
          text =
            "Договоритесь с близкими о секретном слове для проверки видеозвонков. Оно хранится только на этом телефоне и никогда не передаётся на сервер.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
          value = wordDraft,
          onValueChange = { wordDraft = it },
          label = { Text("Кодовое слово") },
          placeholder = { Text("Например: Алматы-осень") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("family_code_word_input"),
        )
      }
    },
    confirmButton = {
      Button(
        onClick = { onSave(wordDraft) },
        modifier = Modifier.heightIn(min = 48.dp).testTag("save_family_code_word_button"),
      ) {
        Text("Сохранить")
      }
    },
    dismissButton = {
      TextButton(
        onClick = onDismiss,
        modifier = Modifier.heightIn(min = 48.dp).testTag("cancel_family_code_word_button"),
      ) {
        Text("Отмена")
      }
    },
  )
}

@Composable
private fun CallScreeningCard(
  isSupported: Boolean,
  isRoleHeld: Boolean,
  onRequestRole: () -> Unit,
) {
  Card(
    modifier = Modifier.fillMaxWidth().testTag("call_screening_card"),
    shape = RoundedCornerShape(18.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
  ) {
    Column(
      modifier = Modifier.padding(18.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Icon(
          imageVector = if (isRoleHeld) Icons.Outlined.CheckCircle else Icons.Outlined.PhoneInTalk,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
        )
        Text(
          text =
            if (isRoleHeld) {
              "Проверка входящих звонков включена"
            } else {
              "Проверка входящих звонков"
            },
          style = MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.onSurface,
        )
      }

      Text(
        text =
          "При входящем звонке с номера не из контактов Senim сверяет только SHA-256 хеш номера с базой жалоб и предупреждает уведомлением при риске. Вызов не сбрасывается и доступ к «Контактам» не запрашивается.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      when {
        !isSupported -> {
          Text(
            text =
              "Системная роль «Определитель и фильтр звонков» доступна начиная с Android 10 (API 29).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        isRoleHeld -> {
          Text(
            text = "Senim назначен системным определителем звонков (ROLE_CALL_SCREENING).",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
          )
        }
        else -> {
          Button(
            onClick = onRequestRole,
            modifier =
              Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag("request_call_screening_role_button"),
          ) {
            Text("Включить защиту звонков")
          }
        }
      }
    }
  }
}

private fun isCallScreeningRoleAvailable(context: Context): Boolean {
  if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
  val roleManager = context.getSystemService(RoleManager::class.java) ?: return false
  return roleManager.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)
}

private fun isCallScreeningRoleHeld(context: Context): Boolean {
  if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
  val roleManager = context.getSystemService(RoleManager::class.java) ?: return false
  return roleManager.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
}
