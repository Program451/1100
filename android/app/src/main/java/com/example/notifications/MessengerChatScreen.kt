package com.example.notifications

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.core.SenimContract
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class ChatSourceMode(val label: String) {
  DEMO("Демо-чат"),
  TELEGRAM("Telegram-бот"),
}

private enum class DemoSenderPreset(
  val label: String,
  val rawId: String,
  val senderKind: String,
) {
  SCAM_PHONE("+77000000001", SenimContract.TEST_SCAM_PHONE, "phone"),
  SCAM_TG("tg:900000001", SenimContract.TEST_SCAM_TELEGRAM_ID, "telegram"),
  SAFE_PHONE("+77015551234", "+77015551234", "phone"),
  CUSTOM("Свой ID", "+7700", "phone"),
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MessengerChatScreen(
  chatMessages: List<ChatPipelineMessage>,
  telegramPollingState: TelegramPollingState,
  onSendDemoMessage: (rawSenderId: String, senderName: String, senderKind: String, text: String) -> Unit,
  onStartTelegramPolling: () -> Unit,
  onStopTelegramPolling: () -> Unit,
  onClearChat: () -> Unit,
) {
  val context = LocalContext.current
  var selectedMode by rememberSaveable { mutableStateOf(ChatSourceMode.DEMO) }
  var selectedPreset by rememberSaveable { mutableStateOf(DemoSenderPreset.SCAM_PHONE) }
  var customSenderId by rememberSaveable { mutableStateOf("+77001234567") }
  var customSenderKind by rememberSaveable { mutableStateOf("phone") }
  var messageDraft by rememberSaveable { mutableStateOf("") }

  LazyColumn(
    modifier = Modifier.fillMaxSize().testTag("messenger_chat_list"),
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    item {
      Column(
        modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
          ChatSourceMode.entries.forEachIndexed { index, mode ->
            SegmentedButton(
              selected = selectedMode == mode,
              onClick = { selectedMode = mode },
              shape = SegmentedButtonDefaults.itemShape(index = index, count = ChatSourceMode.entries.size),
              modifier = Modifier.testTag("chat_mode_${mode.name.lowercase()}"),
            ) {
              Text(mode.label)
            }
          }
        }

        if (selectedMode == ChatSourceMode.DEMO) {
          DemoChatControlsCard(
            selectedPreset = selectedPreset,
            customSenderId = customSenderId,
            customSenderKind = customSenderKind,
            messageDraft = messageDraft,
            onSelectPreset = { selectedPreset = it },
            onCustomSenderChange = { customSenderId = it },
            onCustomKindChange = { customSenderKind = it },
            onMessageDraftChange = { messageDraft = it },
            onSendCustom = {
              val rawId =
                if (selectedPreset == DemoSenderPreset.CUSTOM) {
                  customSenderId.trim()
                } else {
                  selectedPreset.rawId
                }
              val kind =
                if (selectedPreset == DemoSenderPreset.CUSTOM) {
                  customSenderKind
                } else {
                  selectedPreset.senderKind
                }
              val display =
                if (kind == "telegram" && !rawId.startsWith("tg:")) "tg:$rawId" else rawId
              val normalizedRawId =
                if (kind == "telegram" && !rawId.startsWith("tg:")) "tg:$rawId" else rawId
              if (normalizedRawId.isNotBlank() && messageDraft.isNotBlank()) {
                onSendDemoMessage(normalizedRawId, display, kind, messageDraft)
                messageDraft = ""
              }
            },
            onTriggerScenario = { rawId, display, kind, text ->
              onSendDemoMessage(rawId, display, kind, text)
            },
          )
        } else {
          TelegramBotControlsCard(
            pollingState = telegramPollingState,
            onStart = onStartTelegramPolling,
            onStop = onStopTelegramPolling,
          )
        }

        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Text(
            text = "Поток проверки (${chatMessages.size})",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
          )
          if (chatMessages.isNotEmpty()) {
            TextButton(
              onClick = onClearChat,
              modifier = Modifier.heightIn(min = 48.dp).testTag("clear_chat_button"),
            ) {
              Icon(
                imageVector = Icons.Filled.DeleteSweep,
                contentDescription = null,
                modifier = Modifier.padding(end = 6.dp),
              )
              Text("Очистить")
            }
          }
        }
      }
    }

    if (chatMessages.isEmpty()) {
      item {
        Card(
          modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth().testTag("chat_empty_card"),
          shape = RoundedCornerShape(18.dp),
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
          Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
              Icon(
                imageVector = Icons.AutoMirrored.Filled.Chat,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
              )
              Text(
                text = "Сообщений пока нет",
                style = MaterialTheme.typography.titleMedium,
              )
            }
            Text(
              text =
                "Отправьте тестовый сценарий в «Демо-чате» (работает без интернета по Wi-Fi) или запустите опрос «Telegram-бота». Сообщения живут только в ОЗУ и не сохраняются на диск.",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }
    } else {
      items(chatMessages.asReversed(), key = { it.id }) { item ->
        ChatPipelineMessageCard(
          message = item,
          onOpenSafeLink = { url ->
            val browserPkg = LinkGatekeeperActivity.findExternalBrowserPackage(context)
            if (browserPkg == null) {
              Toast.makeText(
                context,
                "На устройстве не найден внешний браузер",
                Toast.LENGTH_SHORT,
              ).show()
            } else {
              val intent =
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                  addCategory(Intent.CATEGORY_BROWSABLE)
                  setPackage(browserPkg)
                  addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
              runCatching { context.startActivity(intent) }
            }
          },
        )
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DemoChatControlsCard(
  selectedPreset: DemoSenderPreset,
  customSenderId: String,
  customSenderKind: String,
  messageDraft: String,
  onSelectPreset: (DemoSenderPreset) -> Unit,
  onCustomSenderChange: (String) -> Unit,
  onCustomKindChange: (String) -> Unit,
  onMessageDraftChange: (String) -> Unit,
  onSendCustom: () -> Unit,
  onTriggerScenario: (rawId: String, display: String, kind: String, text: String) -> Unit,
) {
  Card(
    modifier = Modifier.fillMaxWidth().testTag("demo_chat_controls_card"),
    shape = RoundedCornerShape(20.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
  ) {
    Column(
      modifier = Modifier.padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Icon(
          imageVector = Icons.Outlined.Security,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
        )
        Text(
          text = "Тестовые сценарии (без интернета)",
          style = MaterialTheme.typography.titleMedium,
        )
      }

      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        OutlinedButton(
          onClick = {
            onTriggerScenario(
              SenimContract.TEST_SCAM_PHONE,
              SenimContract.TEST_SCAM_PHONE,
              "phone",
              "Мама, это я, у меня новый номер, телефон сломался. Срочно переведи 150000 тенге, никому не говори!",
            )
          },
          modifier = Modifier.heightIn(min = 48.dp).testTag("demo_scenario_family_scam"),
        ) {
          Text("Семейный развод")
        }

        OutlinedButton(
          onClick = {
            onTriggerScenario(
              SenimContract.TEST_SCAM_PHONE,
              SenimContract.TEST_SCAM_PHONE,
              "phone",
              "Вам начислен бонус! Срочно перейдите и подтвердите карту: http://kaspi-bonus-verify.example/pay?ref=1#x",
            )
          },
          modifier = Modifier.heightIn(min = 48.dp).testTag("demo_scenario_bad_link"),
        ) {
          Text("Опасная ссылка")
        }

        OutlinedButton(
          onClick = {
            onTriggerScenario(
              "+77015551234",
              "+77015551234",
              "phone",
              "Официальная справка банка: https://kaspi.kz/?utm=promo",
            )
          },
          modifier = Modifier.heightIn(min = 48.dp).testTag("demo_scenario_safe_link"),
        ) {
          Text("Безопасная ссылка")
        }

        OutlinedButton(
          onClick = {
            onTriggerScenario(
              SenimContract.TEST_SCAM_TELEGRAM_ID,
              SenimContract.TEST_SCAM_TELEGRAM_ID,
              "telegram",
              "Служба безопасности банка. Ваш счёт под угрозой, срочно назовите код из SMS!",
            )
          },
          modifier = Modifier.heightIn(min = 48.dp).testTag("demo_scenario_tg_scam"),
        ) {
          Text("Мошенник Telegram")
        }
      }

      Text(
        text = "Отправитель для ручной проверки:",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        DemoSenderPreset.entries.forEach { preset ->
          FilterChip(
            selected = selectedPreset == preset,
            onClick = { onSelectPreset(preset) },
            label = { Text(preset.label) },
            modifier = Modifier.testTag("demo_sender_${preset.name.lowercase()}"),
          )
        }
      }

      if (selectedPreset == DemoSenderPreset.CUSTOM) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          OutlinedTextField(
            value = customSenderId,
            onValueChange = onCustomSenderChange,
            label = { Text("Номер или ID") },
            singleLine = true,
            modifier = Modifier.weight(1f).testTag("demo_custom_sender_input"),
          )
          FilterChip(
            selected = customSenderKind == "telegram",
            onClick = {
              onCustomKindChange(if (customSenderKind == "telegram") "phone" else "telegram")
            },
            label = { Text(if (customSenderKind == "telegram") "TG" else "Тел") },
            modifier = Modifier.testTag("demo_custom_kind_chip"),
          )
        }
      }

      OutlinedTextField(
        value = messageDraft,
        onValueChange = onMessageDraftChange,
        label = { Text("Текст входящего сообщения") },
        placeholder = { Text("Например: Срочно переведи деньги или ссылка...") },
        minLines = 2,
        maxLines = 4,
        modifier = Modifier.fillMaxWidth().testTag("demo_message_input"),
      )

      Button(
        onClick = onSendCustom,
        enabled = messageDraft.isNotBlank(),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("demo_send_button"),
      ) {
        Icon(
          imageVector = Icons.AutoMirrored.Filled.Send,
          contentDescription = null,
          modifier = Modifier.padding(end = 8.dp),
        )
        Text("Проверить сообщение")
      }
    }
  }
}

@Composable
private fun TelegramBotControlsCard(
  pollingState: TelegramPollingState,
  onStart: () -> Unit,
  onStop: () -> Unit,
) {
  Card(
    modifier = Modifier.fillMaxWidth().testTag("telegram_bot_controls_card"),
    shape = RoundedCornerShape(20.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
  ) {
    Column(
      modifier = Modifier.padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text(
        text = "Тестовый Telegram-бот (getUpdates)",
        style = MaterialTheme.typography.titleMedium,
      )

      when (pollingState) {
        TelegramPollingState.Stopped -> {
          Text(
            text =
              "Напишите боту в Telegram с другого аккаунта — сообщения пройдут через единый конвейер с sender_kind=\"telegram\" и SHA-256 от \"tg:<user_id>\".",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        TelegramPollingState.Polling -> {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
            Text(
              text = "Идёт опрос Telegram Bot API каждые 2.5 сек...",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.primary,
            )
          }
        }
        TelegramPollingState.MissingToken -> {
          Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.errorContainer,
          ) {
            Text(
              text =
                "Токен TELEGRAM_BOT_TOKEN не задан. Добавьте его в панели Secrets в AI Studio (подставляется в BuildConfig.TELEGRAM_BOT_TOKEN без сохранения в Git).",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onErrorContainer,
              modifier = Modifier.padding(12.dp),
            )
          }
        }
        TelegramPollingState.UnavailableInRelease -> {
          Text(
            text = "Опрос Telegram-бота доступен только в debug-сборке.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
          )
        }
        is TelegramPollingState.Error -> {
          Text(
            text = "Ошибка Telegram API: ${pollingState.message}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
          )
        }
      }

      if (pollingState is TelegramPollingState.Polling) {
        OutlinedButton(
          onClick = onStop,
          modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("stop_telegram_polling_button"),
        ) {
          Icon(
            imageVector = Icons.Filled.Stop,
            contentDescription = null,
            modifier = Modifier.padding(end = 8.dp),
          )
          Text("Остановить опрос")
        }
      } else {
        Button(
          onClick = onStart,
          modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("start_telegram_polling_button"),
        ) {
          Icon(
            imageVector = Icons.Filled.PlayArrow,
            contentDescription = null,
            modifier = Modifier.padding(end = 8.dp),
          )
          Text("Запустить опрос бота")
        }
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChatPipelineMessageCard(
  message: ChatPipelineMessage,
  onOpenSafeLink: (String) -> Unit,
) {
  val timeFormatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
  val sourceLabel =
    if (message.source == TelegramBotSource.SOURCE_TELEGRAM_BOT) "Telegram-бот" else "Демо-чат"

  Card(
    modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth().testTag("chat_message_card_${message.id}"),
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
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = "${message.senderName} • $sourceLabel",
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.primary,
          modifier = Modifier.weight(1f),
        )
        Text(
          text = timeFormatter.format(Date(message.timestamp)),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
      ) {
        Text(
          text = message.text,
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.onSurface,
          modifier = Modifier.fillMaxWidth().padding(12.dp),
        )
      }

      NumberCheckStatusRow(
        status = message.numberStatus,
        localScore = message.localScore,
        patternFlags = message.patternFlags,
      )

      if (message.patternFlags.isNotEmpty()) {
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          message.patternFlags.forEach { flag ->
            AssistChip(
              onClick = {},
              enabled = false,
              label = { Text(flag, style = MaterialTheme.typography.labelSmall) },
            )
          }
        }
      }

      if (message.links.isNotEmpty()) {
        Spacer(modifier = Modifier.height(2.dp))
        message.links.forEach { linkState ->
          ChatLinkStateItem(
            linkState = linkState,
            onOpenSafeLink = onOpenSafeLink,
          )
        }
      }
    }
  }
}

@Composable
private fun NumberCheckStatusRow(
  status: NumberCheckStatus,
  localScore: Float,
  patternFlags: List<String>,
) {
  val scorePct = (localScore * 100).toInt()
  when (status) {
    NumberCheckStatus.Checking -> {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
        Text(
          text = "Проверка номера... (локальный скор $scorePct%)",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
    is NumberCheckStatus.LowRisk -> {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Icon(
          imageVector = Icons.Outlined.CheckCircle,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(18.dp),
        )
        Text(
          text =
            if (patternFlags.isNotEmpty()) {
              "Номер: низкий риск, но в тексте есть признаки ($scorePct%)"
            } else {
              "Номер: risk=low (локальный скор $scorePct%)"
            },
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.primary,
        )
      }
    }
    is NumberCheckStatus.HighRisk -> {
      Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer,
      ) {
        Column(
          modifier = Modifier.fillMaxWidth().padding(12.dp),
          verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Icon(
              imageVector = Icons.Filled.Warning,
              contentDescription = "Высокий риск отправителя",
              tint = MaterialTheme.colorScheme.error,
              modifier = Modifier.size(18.dp),
            )
            Text(
              text = "Опасный отправитель (risk=high)",
              style = MaterialTheme.typography.labelLarge,
              color = MaterialTheme.colorScheme.onErrorContainer,
            )
          }
          status.reasons.forEach { reason ->
            Text(
              text = "• $reason",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onErrorContainer,
            )
          }
        }
      }
    }
    is NumberCheckStatus.Error -> {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Icon(
          imageVector = Icons.Outlined.ErrorOutline,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.size(18.dp),
        )
        Text(
          text = "Проверка номера: ${status.message}",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

@Composable
private fun ChatLinkStateItem(
  linkState: ChatLinkUiState,
  onOpenSafeLink: (String) -> Unit,
) {
  when (linkState) {
    is ChatLinkUiState.Checking -> {
      // Некликабельный статус, пока сервер не ответит по POST /links/check
      Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().testTag("chat_link_checking"),
      ) {
        Row(
          modifier = Modifier.padding(12.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
          Text(
            text = "Проверяется... (${linkState.url})",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }

    is ChatLinkUiState.Safe -> {
      Button(
        onClick = { onOpenSafeLink(linkState.url) },
        colors =
          ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
          ),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("chat_link_safe_button"),
      ) {
        Icon(
          imageVector = Icons.Outlined.OpenInBrowser,
          contentDescription = null,
          modifier = Modifier.padding(end = 8.dp),
        )
        Text("Открыть безопасно: ${linkState.url}")
      }
    }

    is ChatLinkUiState.Blocked -> {
      // Строго БЕЗ кнопки открытия
      Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth().testTag("chat_link_blocked_card"),
      ) {
        Column(
          modifier = Modifier.padding(12.dp),
          verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Icon(
              imageVector = Icons.Filled.Block,
              contentDescription = "Ссылка заблокирована",
              tint = MaterialTheme.colorScheme.error,
              modifier = Modifier.size(18.dp),
            )
            Text(
              text = "Ссылка заблокирована: ${linkState.url}",
              style = MaterialTheme.typography.labelLarge,
              color = MaterialTheme.colorScheme.onErrorContainer,
            )
          }
          linkState.reasons.forEach { reason ->
            Text(
              text = "• $reason",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onErrorContainer,
            )
          }
        }
      }
    }

    is ChatLinkUiState.Error -> {
      Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().testTag("chat_link_error_card"),
      ) {
        Row(
          modifier = Modifier.padding(12.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Icon(
            imageVector = Icons.Outlined.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(18.dp),
          )
          Text(
            text = "Не удалось проверить ${linkState.url}: ${linkState.message}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }
  }
}
