package com.example.bank

import com.example.network.AccountDto
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

sealed interface ServerPingState {
  data object Idle : ServerPingState
  data object Loading : ServerPingState
  data class Connected(val account: AccountDto) : ServerPingState
  data class Failed(val message: String) : ServerPingState
}

sealed interface BankOperationNotice {
  data class Success(val message: String) : BankOperationNotice
  data class Warning(val message: String) : BankOperationNotice
  data class Error(val message: String) : BankOperationNotice
}

data class BankTransferItem(
  val transferId: String,
  val amount: Int,
  val recipient: String,
  val createdAt: String,
  val status: String,
  val isCancelling: Boolean = false,
)

sealed interface DecoyUseOutcome {
  data class Evaluated(val valid: Boolean, val alertRaised: Boolean) : DecoyUseOutcome
  data class Failed(val message: String) : DecoyUseOutcome
}

data class BankUiState(
  val account: AccountDto? = null,
  val isLoadingAccount: Boolean = false,
  val isClosingDeposit: Boolean = false,
  val isSendingTransfer: Boolean = false,
  val notice: BankOperationNotice? = null,
  val transfers: List<BankTransferItem> = emptyList(),
  val issuedDecoyCode: String? = null,
  val issuedDecoyExpiresAt: String? = null,
  val isIssuingDecoy: Boolean = false,
  val isTestingDecoyUse: Boolean = false,
  val decoyUseOutcome: DecoyUseOutcome? = null,
)

object BankFormatters {
  fun formatKzt(amount: Int): String {
    val formatter = NumberFormat.getIntegerInstance(Locale("ru", "KZ"))
    return "${formatter.format(amount)} ₸"
  }

  fun nowIsoUtc(): String {
    val formatter =
      SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
      }
    return formatter.format(Date())
  }
}
