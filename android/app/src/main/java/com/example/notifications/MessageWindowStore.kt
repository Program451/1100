package com.example.notifications

/**
 * Окно последних 10 сообщений от одного отправителя строго в оперативной памяти (раздел 10).
 * Очищается автоматически через 30 минут без новых сообщений.
 */
object MessageWindowStore {

  private const val MAX_MESSAGES_PER_SENDER = 10
  private const val WINDOW_TTL_MS = 30L * 60L * 1000L

  private data class SenderWindow(
    val messages: ArrayDeque<String> = ArrayDeque(),
    var lastUpdatedMs: Long = 0L,
  )

  private val windows = HashMap<String, SenderWindow>()

  @Synchronized
  fun appendAndSnapshot(
    senderKey: String,
    messageText: String,
    nowMs: Long = System.currentTimeMillis(),
  ): List<String> {
    purgeExpired(nowMs)
    val trimmed = messageText.trim()
    if (trimmed.isEmpty()) return emptyList()

    val window = windows.getOrPut(senderKey) { SenderWindow() }
    if (window.lastUpdatedMs > 0L && nowMs - window.lastUpdatedMs > WINDOW_TTL_MS) {
      window.messages.clear()
    }
    if (window.messages.size >= MAX_MESSAGES_PER_SENDER) {
      window.messages.removeFirst()
    }
    window.messages.addLast(trimmed)
    window.lastUpdatedMs = nowMs
    return window.messages.toList()
  }

  @Synchronized
  fun clearAll() {
    windows.clear()
  }

  private fun purgeExpired(nowMs: Long) {
    val iterator = windows.entries.iterator()
    while (iterator.hasNext()) {
      val entry = iterator.next()
      if (nowMs - entry.value.lastUpdatedMs > WINDOW_TTL_MS) {
        iterator.remove()
      }
    }
  }
}
