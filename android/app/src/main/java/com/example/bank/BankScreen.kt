package com.example.bank

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.core.SenimContract

/**
 * Экран демобанка для роли user (user_mom):
 * - отображение баланса и вклада (GET /accounts/{user_id})
 * - досрочное снятие вклада (POST /deposits/close)
 * - перевод средств (POST /transfers) с обработкой status="flagged"
 * - отмена перевода (POST /transfers/{transfer_id}/cancel)
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BankScreen(
  userId: String,
  baseUrl: String,
  pingState: ServerPingState,
  bankUiState: BankUiState,
  onRefreshAccount: () -> Unit,
  onCloseDepositEarly: (amountText: String) -> Unit,
  onSendTransfer: (recipient: String, amountText: String) -> Unit,
  onCancelTransfer: (transferId: String) -> Unit,
  onIssueDecoyCode: () -> Unit,
  onUseDecoyCode: (String) -> Unit,
  onDismissNotice: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var recipientDraft by rememberSaveable { mutableStateOf(SenimContract.TEST_SCAM_PHONE) }
  var amountDraft by rememberSaveable { mutableStateOf("") }
  var showCloseDepositConfirm by rememberSaveable { mutableStateOf(false) }
  var depositCloseAmountDraft by rememberSaveable { mutableStateOf("") }
  var showDecoyPresentation by rememberSaveable { mutableStateOf(false) }
  var decoyInputDraft by rememberSaveable(bankUiState.issuedDecoyCode) {
    mutableStateOf(bankUiState.issuedDecoyCode.orEmpty())
  }

  LazyColumn(
    modifier = modifier.fillMaxSize().testTag("demobank_list"),
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    item {
      Column(
        modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
      ) {
        AccountBalanceCard(
          userId = userId,
          baseUrl = baseUrl,
          bankUiState = bankUiState,
          pingState = pingState,
          onRefresh = onRefreshAccount,
        )

        bankUiState.notice?.let { notice ->
          OperationNoticeBanner(
            notice = notice,
            onDismiss = onDismissNotice,
          )
        }

        DepositCard(
          depositBalance = bankUiState.account?.depositBalance,
          isClosing = bankUiState.isClosingDeposit,
          onRequestClose = {
            depositCloseAmountDraft = (bankUiState.account?.depositBalance ?: 0).toString()
            showCloseDepositConfirm = true
          },
        )

        TransferFormCard(
          recipient = recipientDraft,
          amount = amountDraft,
          isSending = bankUiState.isSendingTransfer,
          onRecipientChange = { recipientDraft = it },
          onAmountChange = { input ->
            // Разрешаем только цифры (целые суммы в тенге)
            amountDraft = input.filter { it.isDigit() }
          },
          onSend = {
            onSendTransfer(recipientDraft, amountDraft)
          },
        )

        DecoyCodeCard(
          issuedCode = bankUiState.issuedDecoyCode,
          expiresAt = bankUiState.issuedDecoyExpiresAt,
          isIssuing = bankUiState.isIssuingDecoy,
          isTestingUse = bankUiState.isTestingDecoyUse,
          decoyInput = decoyInputDraft,
          decoyUseOutcome = bankUiState.decoyUseOutcome,
          onIssueDecoy = onIssueDecoyCode,
          onShowToScammer = { showDecoyPresentation = true },
          onDecoyInputChange = { decoyInputDraft = it },
          onUseDecoy = { onUseDecoyCode(decoyInputDraft) },
        )

        if (bankUiState.transfers.isNotEmpty()) {
          Text(
            text = "Операции текущей сессии (${bankUiState.transfers.size})",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
          )
        }
      }
    }

    items(bankUiState.transfers, key = { it.transferId }) { transfer ->
      TransferItemCard(
        item = transfer,
        onCancel = { onCancelTransfer(transfer.transferId) },
      )
    }
  }

  if (showCloseDepositConfirm) {
    AlertDialog(
      onDismissRequest = { showCloseDepositConfirm = false },
      title = { Text("Досрочно снять вклад?") },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Text(
            text =
              "Средства с вклада будут переведены на основной баланс. При досрочном снятии вклада система безопасности может уведомить доверенное лицо.",
            style = MaterialTheme.typography.bodyMedium,
          )
          OutlinedTextField(
            value = depositCloseAmountDraft,
            onValueChange = { input -> depositCloseAmountDraft = input.filter { it.isDigit() } },
            label = { Text("Сумма снятия (₸)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth().testTag("close_deposit_amount_input"),
          )
        }
      },
      confirmButton = {
        Button(
          onClick = {
            showCloseDepositConfirm = false
            onCloseDepositEarly(depositCloseAmountDraft)
          },
          colors =
            ButtonDefaults.buttonColors(
              containerColor = MaterialTheme.colorScheme.error,
              contentColor = MaterialTheme.colorScheme.onError,
            ),
          modifier = Modifier.heightIn(min = 48.dp).testTag("confirm_close_deposit_button"),
        ) {
          Text("Снять вклад")
        }
      },
      dismissButton = {
        TextButton(
          onClick = { showCloseDepositConfirm = false },
          modifier = Modifier.heightIn(min = 48.dp).testTag("cancel_close_deposit_button"),
        ) {
          Text("Отмена")
        }
      },
    )
  }

  val activeDecoyCode = bankUiState.issuedDecoyCode
  if (showDecoyPresentation && activeDecoyCode != null) {
    DecoyShowToScammerDialog(
      decoyCode = activeDecoyCode,
      expiresAt = bankUiState.issuedDecoyExpiresAt.orEmpty(),
      onDismiss = { showDecoyPresentation = false },
    )
  }
}

@Composable
private fun AccountBalanceCard(
  userId: String,
  baseUrl: String,
  bankUiState: BankUiState,
  pingState: ServerPingState,
  onRefresh: () -> Unit,
) {
  Card(
    modifier = Modifier.fillMaxWidth().testTag("demobank_account_card"),
    shape = RoundedCornerShape(20.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
  ) {
    Column(
      modifier = Modifier.padding(20.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.primary,
          ) {
            Icon(
              imageVector = Icons.Filled.AccountBalance,
              contentDescription = "Демобанк Senim",
              tint = MaterialTheme.colorScheme.onPrimary,
              modifier = Modifier.padding(10.dp),
            )
          }
          Column {
            Text(
              text = "Демобанк",
              style = MaterialTheme.typography.titleLarge,
              color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
              text = "$userId • $baseUrl",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
            )
          }
        }
        IconButton(
          onClick = onRefresh,
          enabled = !bankUiState.isLoadingAccount,
          modifier = Modifier.testTag("refresh_account_button"),
        ) {
          if (bankUiState.isLoadingAccount) {
            CircularProgressIndicator(
              strokeWidth = 2.dp,
              color = MaterialTheme.colorScheme.onPrimaryContainer,
              modifier = Modifier.size(20.dp),
            )
          } else {
            Icon(
              imageVector = Icons.Outlined.Refresh,
              contentDescription = "Обновить баланс",
              tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
          }
        }
      }

      val account = bankUiState.account
      if (account != null) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.weight(1f),
          ) {
            Column(
              modifier = Modifier.padding(14.dp),
              verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
              Text(
                text = "Основной счёт",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              Text(
                text = BankFormatters.formatKzt(account.balance),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.testTag("account_balance_value"),
              )
            }
          }

          Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.weight(1f),
          ) {
            Column(
              modifier = Modifier.padding(14.dp),
              verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
              Text(
                text = "Вклад (депозит)",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              Text(
                text = BankFormatters.formatKzt(account.depositBalance),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.testTag("deposit_balance_value"),
              )
            }
          }
        }
      } else {
        val statusText =
          when (pingState) {
            ServerPingState.Loading -> "Загрузка баланса с сервера..."
            is ServerPingState.Failed -> "Сервер недоступен: ${pingState.message}"
            else -> "Нажмите кнопку обновления для загрузки счёта."
          }
        Text(
          text = statusText,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
      }
    }
  }
}

@Composable
private fun OperationNoticeBanner(
  notice: BankOperationNotice,
  onDismiss: () -> Unit,
) {
  val (containerColor, contentColor, icon) =
    when (notice) {
      is BankOperationNotice.Success ->
        Triple(
          MaterialTheme.colorScheme.primaryContainer,
          MaterialTheme.colorScheme.onPrimaryContainer,
          Icons.Outlined.CheckCircle,
        )
      is BankOperationNotice.Warning ->
        Triple(
          MaterialTheme.colorScheme.errorContainer,
          MaterialTheme.colorScheme.onErrorContainer,
          Icons.Filled.Warning,
        )
      is BankOperationNotice.Error ->
        Triple(
          MaterialTheme.colorScheme.errorContainer,
          MaterialTheme.colorScheme.onErrorContainer,
          Icons.Outlined.ErrorOutline,
        )
    }

  val text =
    when (notice) {
      is BankOperationNotice.Success -> notice.message
      is BankOperationNotice.Warning -> notice.message
      is BankOperationNotice.Error -> notice.message
    }

  Surface(
    shape = RoundedCornerShape(16.dp),
    color = containerColor,
    modifier = Modifier.fillMaxWidth().testTag("bank_operation_notice_banner"),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Icon(imageVector = icon, contentDescription = null, tint = contentColor)
      Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = contentColor,
        modifier = Modifier.weight(1f),
      )
      IconButton(
        onClick = onDismiss,
        modifier = Modifier.testTag("dismiss_bank_notice_button"),
      ) {
        Icon(
          imageVector = Icons.Outlined.Close,
          contentDescription = "Закрыть сообщение",
          tint = contentColor,
        )
      }
    }
  }
}

@Composable
private fun DepositCard(
  depositBalance: Int?,
  isClosing: Boolean,
  onRequestClose: () -> Unit,
) {
  Card(
    modifier = Modifier.fillMaxWidth().testTag("demobank_deposit_card"),
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
          imageVector = Icons.Filled.Savings,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
        )
        Text(
          text = "Управление вкладом",
          style = MaterialTheme.typography.titleMedium,
        )
      }
      Text(
        text =
          if ((depositBalance ?: 0) > 0) {
            "На вкладе ${BankFormatters.formatKzt(depositBalance ?: 0)}. Досрочное снятие закрывает вклад и переводит сумму на основной счёт."
          } else {
            "Вклад сейчас равен 0 ₸. Вы всё равно можете проверить обработку статуса сервером (409 INVALID_STATE)."
          },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      OutlinedButton(
        onClick = onRequestClose,
        enabled = !isClosing,
        modifier =
          Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .testTag("close_deposit_early_button"),
      ) {
        if (isClosing) {
          CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp).padding(end = 8.dp))
          Text("Закрытие вклада...")
        } else {
          Text("Снять вклад досрочно")
        }
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TransferFormCard(
  recipient: String,
  amount: String,
  isSending: Boolean,
  onRecipientChange: (String) -> Unit,
  onAmountChange: (String) -> Unit,
  onSend: () -> Unit,
) {
  Card(
    modifier = Modifier.fillMaxWidth().testTag("demobank_transfer_form_card"),
    shape = RoundedCornerShape(18.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
  ) {
    Column(
      modifier = Modifier.padding(18.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text(
        text = "Перевод средств",
        style = MaterialTheme.typography.titleMedium,
      )

      OutlinedTextField(
        value = recipient,
        onValueChange = onRecipientChange,
        label = { Text("Получатель (телефон или карта)") },
        placeholder = { Text("+77000000001") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("transfer_recipient_input"),
      )

      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        FilterChip(
          selected = recipient.trim() == SenimContract.TEST_SCAM_PHONE,
          onClick = { onRecipientChange(SenimContract.TEST_SCAM_PHONE) },
          label = { Text("Тест: +77000000001") },
          modifier = Modifier.testTag("preset_recipient_scam_chip"),
        )
        FilterChip(
          selected = recipient.trim() == "+77015551234",
          onClick = { onRecipientChange("+77015551234") },
          label = { Text("Знакомый номер") },
          modifier = Modifier.testTag("preset_recipient_safe_chip"),
        )
      }

      OutlinedTextField(
        value = amount,
        onValueChange = onAmountChange,
        label = { Text("Сумма (₸, целое число)") },
        placeholder = { Text("350000") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth().testTag("transfer_amount_input"),
      )

      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        FilterChip(
          selected = amount == "15000",
          onClick = { onAmountChange("15000") },
          label = { Text("15 000 ₸") },
          modifier = Modifier.testTag("preset_amount_small_chip"),
        )
        FilterChip(
          selected = amount == "350000",
          onClick = { onAmountChange("350000") },
          label = { Text("350 000 ₸") },
          modifier = Modifier.testTag("preset_amount_large_chip"),
        )
      }

      Button(
        onClick = onSend,
        enabled = !isSending,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("send_transfer_button"),
      ) {
        if (isSending) {
          CircularProgressIndicator(
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(18.dp).padding(end = 8.dp),
          )
          Text("Отправка перевода...")
        } else {
          Icon(
            imageVector = Icons.AutoMirrored.Filled.Send,
            contentDescription = null,
            modifier = Modifier.padding(end = 8.dp),
          )
          Text("Перевести")
        }
      }
    }
  }
}

@Composable
private fun TransferItemCard(
  item: BankTransferItem,
  onCancel: () -> Unit,
) {
  val isFlagged = item.status == "flagged"
  val isCancelled = item.status == "cancelled"
  val containerColor =
    when {
      isFlagged -> MaterialTheme.colorScheme.errorContainer
      isCancelled -> MaterialTheme.colorScheme.surfaceVariant
      else -> MaterialTheme.colorScheme.surface
    }

  Card(
    modifier =
      Modifier.widthIn(max = 600.dp).fillMaxWidth().testTag("transfer_card_${item.transferId}"),
    shape = RoundedCornerShape(18.dp),
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
          text = BankFormatters.formatKzt(item.amount),
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Bold,
          color =
            if (isFlagged) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
        )
        Text(
          text =
            when (item.status) {
              "flagged" -> "Приостановлен / проверка (flagged)"
              "cancelled" -> "Отменён"
              else -> "Выполнен (${item.status})"
            },
          style = MaterialTheme.typography.labelLarge,
          color =
            when {
              isFlagged -> MaterialTheme.colorScheme.error
              isCancelled -> MaterialTheme.colorScheme.onSurfaceVariant
              else -> MaterialTheme.colorScheme.primary
            },
        )
      }

      Text(
        text = "Получатель: ${item.recipient}",
        style = MaterialTheme.typography.bodyMedium,
        color =
          if (isFlagged) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
      )
      Text(
        text = "ID: ${item.transferId.take(8)}… • ${item.createdAt}",
        style = MaterialTheme.typography.labelSmall,
        color =
          if (isFlagged) {
            MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
          } else {
            MaterialTheme.colorScheme.onSurfaceVariant
          },
      )

      if (!isCancelled) {
        OutlinedButton(
          onClick = onCancel,
          enabled = !item.isCancelling,
          modifier =
            Modifier
              .fillMaxWidth()
              .heightIn(min = 48.dp)
              .testTag("cancel_transfer_button_${item.transferId}"),
        ) {
          if (item.isCancelling) {
            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp).padding(end = 8.dp))
            Text("Отмена перевода...")
          } else {
            Icon(
              imageVector = Icons.Filled.Cancel,
              contentDescription = null,
              modifier = Modifier.padding(end = 8.dp),
            )
            Text("Отменить перевод")
          }
        }
      }
    }
  }
}

@Composable
private fun DecoyCodeCard(
  issuedCode: String?,
  expiresAt: String?,
  isIssuing: Boolean,
  isTestingUse: Boolean,
  decoyInput: String,
  decoyUseOutcome: DecoyUseOutcome?,
  onIssueDecoy: () -> Unit,
  onShowToScammer: () -> Unit,
  onDecoyInputChange: (String) -> Unit,
  onUseDecoy: () -> Unit,
) {
  Card(
    modifier = Modifier.fillMaxWidth().testTag("demobank_decoy_card"),
    shape = RoundedCornerShape(18.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
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
          imageVector = Icons.Outlined.Shield,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
        )
        Text(
          text = "Код-приманка от мошенников",
          style = MaterialTheme.typography.titleMedium,
        )
      }

      Text(
        text =
          "Если злоумышленник требует продиктовать код или показать экран, выпустите код-приманку. Он никогда не подтверждает платёж и отправляет тревогу доверенному лицу при попытке ввода.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      Button(
        onClick = onIssueDecoy,
        enabled = !isIssuing,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("issue_decoy_code_button"),
      ) {
        if (isIssuing) {
          CircularProgressIndicator(
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(18.dp).padding(end = 8.dp),
          )
          Text("Выпуск кода...")
        } else {
          Text(if (issuedCode == null) "Выпустить код-приманку" else "Выпустить новый код-приманку")
        }
      }

      if (issuedCode != null) {
        Surface(
          shape = RoundedCornerShape(14.dp),
          color = MaterialTheme.colorScheme.secondaryContainer,
        ) {
          Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
          ) {
            Text(
              text = issuedCode,
              style = MaterialTheme.typography.headlineLarge,
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.onSecondaryContainer,
              modifier = Modifier.testTag("issued_decoy_code_text"),
            )
            if (!expiresAt.isNullOrBlank()) {
              Text(
                text = "Действует до: $expiresAt",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f),
              )
            }
            OutlinedButton(
              onClick = onShowToScammer,
              modifier =
                Modifier
                  .fillMaxWidth()
                  .heightIn(min = 48.dp)
                  .testTag("show_decoy_to_scammer_button"),
            ) {
              Icon(
                imageVector = Icons.Filled.Visibility,
                contentDescription = null,
                modifier = Modifier.padding(end = 8.dp),
              )
              Text("Показать мошеннику")
            }
          }
        }
      }

      OutlinedTextField(
        value = decoyInput,
        onValueChange = onDecoyInputChange,
        label = { Text("Проверка ввода кода (POST /decoy/use)") },
        placeholder = { Text("Введите 6-значный код") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth().testTag("decoy_use_code_input"),
      )

      OutlinedButton(
        onClick = onUseDecoy,
        enabled = !isTestingUse && decoyInput.isNotBlank(),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("use_decoy_code_button"),
      ) {
        if (isTestingUse) {
          CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp).padding(end = 8.dp))
          Text("Проверка кода...")
        } else {
          Text("Имитировать ввод кода мошенником")
        }
      }

      when (decoyUseOutcome) {
        null -> Unit
        is DecoyUseOutcome.Evaluated -> {
          val bannerContainer =
            if (decoyUseOutcome.valid || decoyUseOutcome.alertRaised) {
              MaterialTheme.colorScheme.errorContainer
            } else {
              MaterialTheme.colorScheme.surfaceVariant
            }
          val bannerContent =
            if (decoyUseOutcome.valid || decoyUseOutcome.alertRaised) {
              MaterialTheme.colorScheme.onErrorContainer
            } else {
              MaterialTheme.colorScheme.onSurfaceVariant
            }
          Surface(
            shape = RoundedCornerShape(12.dp),
            color = bannerContainer,
            modifier = Modifier.fillMaxWidth().testTag("decoy_triggered_banner"),
          ) {
            Column(
              modifier = Modifier.padding(12.dp),
              verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
              Text(
                text =
                  if (decoyUseOutcome.valid) {
                    "Код-приманка распознан (valid=true) — платёж НЕ подтверждён"
                  } else {
                    "Код не совпал с активным кодом-приманкой (valid=false)"
                  },
                style = MaterialTheme.typography.labelLarge,
                color = bannerContent,
              )
              Text(
                text =
                  "Тревога доверенному (alert_raised): ${if (decoyUseOutcome.alertRaised) "да" else "нет"}",
                style = MaterialTheme.typography.bodyMedium,
                color = bannerContent,
              )
            }
          }
        }
        is DecoyUseOutcome.Failed -> {
          Text(
            text = "Ошибка проверки кода: ${decoyUseOutcome.message}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
          )
        }
      }
    }
  }
}

@Composable
private fun DecoyShowToScammerDialog(
  decoyCode: String,
  expiresAt: String,
  onDismiss: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    icon = {
      Icon(
        imageVector = Icons.Outlined.Lock,
        contentDescription = "Код подтверждения",
        tint = MaterialTheme.colorScheme.primary,
      )
    },
    title = { Text("Код подтверждения операции") },
    text = {
      Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Text(
          text = "Никому не сообщайте код посторонним:",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(
          shape = RoundedCornerShape(16.dp),
          color = MaterialTheme.colorScheme.primaryContainer,
        ) {
          Text(
            text = decoyCode,
            style = MaterialTheme.typography.displayMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 14.dp).testTag("scammer_display_code"),
          )
        }
        if (expiresAt.isNotBlank()) {
          Text(
            text = "Код действует до $expiresAt",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    },
    confirmButton = {
      Button(
        onClick = onDismiss,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("close_decoy_presentation_button"),
      ) {
        Text("Закрыть")
      }
    },
  )
}
