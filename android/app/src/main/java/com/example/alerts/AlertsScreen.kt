package com.example.alerts

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.network.AlertDto
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Экран тревог для доверенного лица (role=trusted, user_son).
 * Отображает тревоги, полученные через FCM data-payload или запасной опрос GET /alerts (раз в 5 сек),
 * крупный текст сообщения и кнопку «Позвонить» (ACTION_DIAL).
 * Не имеет доступа к счетам, переводам и текстам переписки близкого.
 */
@Composable
fun AlertsScreen(
  userId: String,
  baseUrl: String,
  alerts: List<AlertDto>,
  syncState: AlertsSyncState,
  trustedContactPhone: String,
  onRefreshAlerts: () -> Unit,
  onSaveTrustedPhone: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current

  var hasPostNotificationsPermission by remember {
    mutableStateOf(hasPostNotificationPermission(context))
  }
  var showPhoneDialog by remember { mutableStateOf(false) }

  val permissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      hasPostNotificationsPermission = granted
    }

  DisposableEffect(lifecycleOwner, context) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME) {
        hasPostNotificationsPermission = hasPostNotificationPermission(context)
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose {
      lifecycleOwner.lifecycle.removeObserver(observer)
    }
  }

  LazyColumn(
    modifier = modifier.fillMaxSize().testTag("alerts_screen_list"),
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    item {
      Column(
        modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Card(
          modifier = Modifier.fillMaxWidth().testTag("trusted_mode_banner"),
          shape = RoundedCornerShape(20.dp),
          colors =
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        ) {
          Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
          ) {
            Surface(
              shape = RoundedCornerShape(14.dp),
              color = MaterialTheme.colorScheme.secondary,
            ) {
              Icon(
                imageVector = Icons.Filled.Notifications,
                contentDescription = "Режим доверенного лица",
                tint = MaterialTheme.colorScheme.onSecondary,
                modifier = Modifier.padding(12.dp),
              )
            }
            Column(
              modifier = Modifier.weight(1f),
              verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
              Text(
                text = "Режим доверенного лица",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
              )
              Text(
                text = "Профиль: $userId • Сервер: $baseUrl",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f),
              )
            }
          }
        }

        AlertsSyncStatusCard(
          syncState = syncState,
          trustedContactPhone = trustedContactPhone,
          onRefresh = onRefreshAlerts,
          onEditPhone = { showPhoneDialog = true },
        )

        if (!hasPostNotificationsPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
          Card(
            modifier = Modifier.fillMaxWidth().testTag("trusted_post_notifications_card"),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
          ) {
            Column(
              modifier = Modifier.padding(16.dp),
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
                  text = "Разрешите уведомления о тревогах",
                  style = MaterialTheme.typography.titleSmall,
                )
              }
              OutlinedButton(
                onClick = {
                  permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                },
                modifier =
                  Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("grant_trusted_notifications_button"),
              ) {
                Text("Разрешить уведомления")
              }
            }
          }
        }
      }
    }

    if (alerts.isEmpty()) {
      item {
        Card(
          modifier =
            Modifier.widthIn(max = 600.dp).fillMaxWidth().testTag("alerts_empty_state_card"),
          shape = RoundedCornerShape(18.dp),
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
          elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        ) {
          Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
              Icon(
                imageVector = Icons.Outlined.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
              )
              Text(
                text = "Активных тревог пока нет",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
              )
            }
            Text(
              text =
                "Когда сервер обнаружит у близкого опасную операцию (досрочное снятие вклада, крупный перевод или код-приманку), здесь сразу появится тревога.",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
              onClick = { dialCloseContact(context, trustedContactPhone) },
              modifier =
                Modifier
                  .fillMaxWidth()
                  .heightIn(min = 48.dp)
                  .testTag("dial_close_contact_idle_button"),
            ) {
              Icon(
                imageVector = Icons.Filled.Call,
                contentDescription = null,
                modifier = Modifier.padding(end = 8.dp),
              )
              Text(
                if (trustedContactPhone.isNotBlank()) {
                  "Позвонить близкому ($trustedContactPhone)"
                } else {
                  "Открыть набор номера"
                }
              )
            }
          }
        }
      }
    } else {
      itemsIndexed(alerts, key = { _, item -> item.alertId }) { index, alert ->
        AlertCard(
          alert = alert,
          isLatest = index == 0,
          trustedContactPhone = trustedContactPhone,
          onCallClick = { dialCloseContact(context, trustedContactPhone) },
        )
      }
    }
  }

  if (showPhoneDialog) {
    TrustedPhoneDialog(
      currentPhone = trustedContactPhone,
      onDismiss = { showPhoneDialog = false },
      onSave = { newPhone ->
        onSaveTrustedPhone(newPhone)
        showPhoneDialog = false
      },
    )
  }
}

@Composable
private fun AlertsSyncStatusCard(
  syncState: AlertsSyncState,
  trustedContactPhone: String,
  onRefresh: () -> Unit,
  onEditPhone: () -> Unit,
) {
  val timeFormatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

  Card(
    modifier = Modifier.fillMaxWidth().testTag("alerts_sync_status_card"),
    shape = RoundedCornerShape(18.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
  ) {
    Column(
      modifier = Modifier.padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        when (syncState) {
          AlertsSyncState.Idle -> {
            Text(
              text = "Ожидание подключения...",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.weight(1f),
            )
          }
          is AlertsSyncState.Active -> {
            if (syncState.isSyncing) {
              CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
            }
            val lastTimeText =
              syncState.lastSyncAtMs?.let { " • ${timeFormatter.format(Date(it))}" }.orEmpty()
            Text(
              text = "FCM + опрос GET /alerts каждые 5 сек$lastTimeText",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.primary,
              modifier = Modifier.weight(1f),
            )
          }
          is AlertsSyncState.Error -> {
            Text(
              text = "Ошибка опроса: ${syncState.message}",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.error,
              modifier = Modifier.weight(1f),
            )
          }
        }
        IconButton(
          onClick = onRefresh,
          modifier = Modifier.testTag("refresh_alerts_button"),
        ) {
          Icon(
            imageVector = Icons.Outlined.Refresh,
            contentDescription = "Обновить тревоги",
          )
        }
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Text(
          text =
            if (trustedContactPhone.isNotBlank()) {
              "Телефон близкого: $trustedContactPhone"
            } else {
              "Телефон близкого: не указан (откроет набор)"
            },
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.weight(1f),
        )
        TextButton(
          onClick = onEditPhone,
          modifier = Modifier.heightIn(min = 48.dp).testTag("edit_trusted_phone_button"),
        ) {
          Icon(
            imageVector = Icons.Outlined.Edit,
            contentDescription = null,
            modifier = Modifier.padding(end = 4.dp),
          )
          Text(if (trustedContactPhone.isNotBlank()) "Изменить" else "Указать")
        }
      }
    }
  }
}

@Composable
private fun AlertCard(
  alert: AlertDto,
  isLatest: Boolean,
  trustedContactPhone: String,
  onCallClick: () -> Unit,
) {
  Card(
    modifier =
      Modifier.widthIn(max = 600.dp).fillMaxWidth().testTag("alert_card_${alert.alertId}"),
    shape = RoundedCornerShape(22.dp),
    colors =
      CardDefaults.cardColors(
        containerColor =
          if (isLatest) {
            MaterialTheme.colorScheme.errorContainer
          } else {
            MaterialTheme.colorScheme.surface
          }
      ),
    elevation = CardDefaults.cardElevation(defaultElevation = if (isLatest) 3.dp else 1.dp),
  ) {
    Column(
      modifier = Modifier.padding(20.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Surface(
          shape = RoundedCornerShape(12.dp),
          color = MaterialTheme.colorScheme.error,
        ) {
          Icon(
            imageVector = Icons.Filled.Warning,
            contentDescription = "Срочная тревога",
            tint = MaterialTheme.colorScheme.onError,
            modifier = Modifier.padding(8.dp),
          )
        }
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text =
              if (alert.alertType.isNotBlank()) {
                alert.alertType.replaceFirstChar { it.uppercase() }
              } else {
                "Тревога безопасности"
              },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color =
              if (isLatest) {
                MaterialTheme.colorScheme.onErrorContainer
              } else {
                MaterialTheme.colorScheme.error
              },
          )
          val aboutLabel =
            when {
              alert.aboutUserName.isNotBlank() && alert.aboutUserId.isNotBlank() ->
                "${alert.aboutUserName} (${alert.aboutUserId})"
              alert.aboutUserName.isNotBlank() -> alert.aboutUserName
              alert.aboutUserId.isNotBlank() -> alert.aboutUserId
              else -> "user_mom"
            }
          Text(
            text = "Близкий: $aboutLabel • ${alert.createdAt}",
            style = MaterialTheme.typography.labelMedium,
            color =
              if (isLatest) {
                MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f)
              } else {
                MaterialTheme.colorScheme.onSurfaceVariant
              },
          )
          val transferId = alert.transferId
          if (!transferId.isNullOrBlank()) {
            Text(
              text = "Перевод: $transferId",
              style = MaterialTheme.typography.labelSmall,
              color =
                if (isLatest) {
                  MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                } else {
                  MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
          }
        }
      }

      // Крупный текст сообщения для мгновенного считывания доверенным лицом
      Text(
        text = alert.message,
        style = if (isLatest) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color =
          if (isLatest) {
            MaterialTheme.colorScheme.onErrorContainer
          } else {
            MaterialTheme.colorScheme.onSurface
          },
      )

      Spacer(modifier = Modifier.height(2.dp))

      Button(
        onClick = onCallClick,
        colors =
          ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError,
          ),
        modifier =
          Modifier
            .fillMaxWidth()
            .heightIn(min = 54.dp)
            .testTag("call_user_button_${alert.alertId}"),
      ) {
        Icon(
          imageVector = Icons.Filled.Call,
          contentDescription = null,
          modifier = Modifier.padding(end = 10.dp),
        )
        Text(
          text =
            if (trustedContactPhone.isNotBlank()) {
              "Позвонить ($trustedContactPhone)"
            } else {
              "Позвонить"
            },
          style = MaterialTheme.typography.titleMedium,
        )
      }
    }
  }
}

@Composable
private fun TrustedPhoneDialog(
  currentPhone: String,
  onDismiss: () -> Unit,
  onSave: (String) -> Unit,
) {
  var phoneDraft by remember(currentPhone) { mutableStateOf(currentPhone) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Телефон близкого") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
          text =
            "Укажите номер, который подставится при нажатии кнопки «Позвонить». Номер хранится только на этом телефоне.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
          value = phoneDraft,
          onValueChange = { phoneDraft = it },
          label = { Text("Номер телефона") },
          placeholder = { Text("+77010000000") },
          singleLine = true,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
          modifier = Modifier.fillMaxWidth().testTag("trusted_phone_input"),
        )
      }
    },
    confirmButton = {
      Button(
        onClick = { onSave(phoneDraft) },
        modifier = Modifier.heightIn(min = 48.dp).testTag("save_trusted_phone_button"),
      ) {
        Text("Сохранить")
      }
    },
    dismissButton = {
      TextButton(
        onClick = onDismiss,
        modifier = Modifier.heightIn(min = 48.dp).testTag("cancel_trusted_phone_button"),
      ) {
        Text("Отмена")
      }
    },
  )
}

private fun dialCloseContact(context: Context, phone: String) {
  val trimmed = phone.trim()
  val uri = if (trimmed.isNotEmpty()) Uri.parse("tel:$trimmed") else Uri.parse("tel:")
  val dialIntent =
    Intent(Intent.ACTION_DIAL, uri).apply {
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
  runCatching { context.startActivity(dialIntent) }
}

private fun hasPostNotificationPermission(context: Context): Boolean =
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
      PackageManager.PERMISSION_GRANTED
  } else {
    true
  }
