package com.example.alerts

import com.example.network.AlertDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

sealed interface AlertsSyncState {
  data object Idle : AlertsSyncState
  data class Active(val lastSyncAtMs: Long? = null, val isSyncing: Boolean = false) : AlertsSyncState
  data class Error(val message: String, val lastSyncAtMs: Long? = null) : AlertsSyncState
}

/**
 * Потокобезопасное хранилище тревог, полученных от сервера (FCM data или GET /alerts).
 * Клиент никогда не создаёт Alert самостоятельно.
 */
object AlertsHolder {

  private const val MAX_ALERTS = 50

  private val _alerts = MutableStateFlow<List<AlertDto>>(emptyList())
  val alerts: StateFlow<List<AlertDto>> = _alerts.asStateFlow()

  fun upsertAlerts(incoming: List<AlertDto>): List<AlertDto> {
    if (incoming.isEmpty()) return emptyList()
    val newlyAdded = mutableListOf<AlertDto>()
    _alerts.update { current ->
      val existingIds = current.mapTo(HashSet()) { it.alertId }
      val mergedById = LinkedHashMap<String, AlertDto>()
      incoming.forEach { alert ->
        if (alert.alertId !in existingIds) {
          newlyAdded += alert
        }
        mergedById[alert.alertId] = alert
      }
      current.forEach { alert ->
        if (alert.alertId !in mergedById) {
          mergedById[alert.alertId] = alert
        }
      }
      mergedById.values
        .sortedByDescending { it.createdAt }
        .take(MAX_ALERTS)
    }
    return newlyAdded
  }

  fun upsertFromPush(alert: AlertDto): Boolean {
    val added = upsertAlerts(listOf(alert))
    return added.isNotEmpty()
  }

  fun latestCreatedAt(): String? = _alerts.value.maxOfOrNull { it.createdAt }

  fun clear() {
    _alerts.value = emptyList()
  }
}
