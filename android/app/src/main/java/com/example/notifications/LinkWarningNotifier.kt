package com.example.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R

/**
 * Показывает локальные heads-up уведомления:
 * - при обнаружении опасной ссылки или подозрительного номера (risk=high)
 * - памятку при входящем видеозвонке с номера не из контактов (без выхода в сеть)
 * Не требует разрешения поверх других окон.
 */
object LinkWarningNotifier {

  private const val CHANNEL_ID = "senim_link_warnings"
  private const val CHANNEL_NAME = "Предупреждения об угрозах"

  private const val VIDEO_CALL_CHANNEL_ID = "senim_videocall_tips"
  private const val VIDEO_CALL_CHANNEL_NAME = "Памятки при видеозвонке"

  private const val TRUSTED_ALERT_CHANNEL_ID = "senim_trusted_alerts"
  private const val TRUSTED_ALERT_CHANNEL_NAME = "Тревоги доверенного лица"

  fun showHighRiskWarning(
    context: Context,
    url: String,
    messengerLabel: String,
    reasons: List<String>,
  ) {
    val reasonsText =
      if (reasons.isNotEmpty()) reasons.joinToString(separator = "; ") else "Подозрительная ссылка"
    postHeadsUp(
      context = context,
      channelId = CHANNEL_ID,
      channelName = CHANNEL_NAME,
      channelDescription = "Предупреждения об опасных ссылках и подозрительных номерах",
      notificationId = url.hashCode(),
      title = "Опасная ссылка ($messengerLabel)",
      shortText = "Не переходите по $url: $reasonsText",
      bigText = "Не переходите по ссылке:\n$url\n\nПричины: $reasonsText",
    )
  }

  fun showSuspiciousNumberWarning(
    context: Context,
    displaySender: String,
    messengerLabel: String,
    reasons: List<String>,
  ) {
    val reasonsText =
      if (reasons.isNotEmpty()) {
        reasons.joinToString(separator = "; ")
      } else {
        "Подозрительная активность с неизвестного номера"
      }
    postHeadsUp(
      context = context,
      channelId = CHANNEL_ID,
      channelName = CHANNEL_NAME,
      channelDescription = "Предупреждения об опасных ссылках и подозрительных номерах",
      notificationId = ("num_$displaySender").hashCode(),
      title = "Подозрительный отправитель ($messengerLabel)",
      shortText = "$displaySender: $reasonsText",
      bigText =
        "Осторожно! Номер $displaySender не в контактах.\nНе переводите деньги и не сообщайте коды.\n\nПричины: $reasonsText",
    )
  }

  /**
   * Локальная памятка при входящем видеозвонке с номера не из контактов.
   * Работает полностью на телефоне, на сервер ничего не уходит.
   */
  fun showVideoCallChecklist(
    context: Context,
    displaySender: String,
    messengerLabel: String,
    familyCodeWord: String,
  ) {
    val secondPoint =
      if (familyCodeWord.isNotBlank()) {
        "2. Попросите назвать семейное кодовое слово (ваше слово: $familyCodeWord)"
      } else {
        "2. Попросите назвать семейное кодовое слово"
      }
    val checklistText =
      "Видеозвонок с неизвестного номера $displaySender ($messengerLabel):\n" +
        "1. Попросите провести рукой перед лицом и повернуть голову\n" +
        "$secondPoint\n" +
        "3. Перезвоните сами по известному номеру"

    postHeadsUp(
      context = context,
      channelId = VIDEO_CALL_CHANNEL_ID,
      channelName = VIDEO_CALL_CHANNEL_NAME,
      channelDescription = "Локальные памятки проверки собеседника при видеозвонке",
      notificationId = ("video_$displaySender").hashCode(),
      title = "Видеозвонок с неизвестного номера ($messengerLabel)",
      shortText = "Проверьте собеседника: жест рукой, кодовое слово, перезвонить самому",
      bigText = checklistText,
    )
  }

  fun showTrustedAlert(
    context: Context,
    alertId: String,
    alertType: String,
    message: String,
  ) {
    val title =
      if (alertType.isNotBlank()) "Тревога Senim: $alertType" else "Тревога безопасности Senim"
    postHeadsUp(
      context = context,
      channelId = TRUSTED_ALERT_CHANNEL_ID,
      channelName = TRUSTED_ALERT_CHANNEL_NAME,
      channelDescription = "Срочные уведомления доверенному лицу от сервера безопасности",
      notificationId = ("alert_$alertId").hashCode(),
      title = title,
      shortText = message,
      bigText = message,
    )
  }

  private fun postHeadsUp(
    context: Context,
    channelId: String,
    channelName: String,
    channelDescription: String,
    notificationId: Int,
    title: String,
    shortText: String,
    bigText: String,
  ) {
    val appContext = context.applicationContext
    if (
      Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) !=
          PackageManager.PERMISSION_GRANTED
    ) {
      return
    }

    ensureChannel(appContext, channelId, channelName, channelDescription)

    val openAppIntent =
      Intent(appContext, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
      }
    val pendingIntent =
      PendingIntent.getActivity(
        appContext,
        notificationId,
        openAppIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )

    val notification =
      NotificationCompat.Builder(appContext, channelId)
        .setSmallIcon(R.drawable.ic_launcher_foreground)
        .setContentTitle(title)
        .setContentText(shortText)
        .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setCategory(NotificationCompat.CATEGORY_ALARM)
        .setAutoCancel(true)
        .setContentIntent(pendingIntent)
        .build()

    runCatching {
      NotificationManagerCompat.from(appContext).notify(notificationId, notification)
    }
  }

  private fun ensureChannel(
    context: Context,
    channelId: String,
    channelName: String,
    channelDescription: String,
  ) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
      ?: return
    val existing = manager.getNotificationChannel(channelId)
    if (existing != null) return

    val channel =
      NotificationChannel(
        channelId,
        channelName,
        NotificationManager.IMPORTANCE_HIGH,
      ).apply {
        description = channelDescription
        enableVibration(true)
      }
    manager.createNotificationChannel(channel)
  }
}
