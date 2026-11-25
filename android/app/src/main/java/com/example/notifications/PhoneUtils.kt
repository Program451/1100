package com.example.notifications

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Нормализация номера телефона и вычисление SHA-256 по правилам раздела 7:
 * оставляются только '+' и цифры; хеш считается в UTF-8, hex в нижнем регистре.
 */
object PhoneUtils {

  private val ALLOWED_PHONE_CHARS = setOf('+', ' ', '-', '(', ')', '.')

  fun looksLikePhoneNumber(senderTitle: String?): Boolean {
    if (senderTitle.isNullOrBlank()) return false
    val trimmed = senderTitle.trim()
    // Если есть буквы — это имя контакта или название чата, а не телефонный номер
    if (trimmed.any { it.isLetter() }) return false
    if (trimmed.any { !it.isDigit() && it !in ALLOWED_PHONE_CHARS }) return false
    val digitsCount = trimmed.count { it.isDigit() }
    return digitsCount >= 7
  }

  fun normalizePhoneNumber(rawPhone: String): String =
    buildString {
      rawPhone.forEach { ch ->
        if (ch.isDigit() || (ch == '+' && isEmpty())) {
          append(ch)
        }
      }
    }

  fun sha256Hex(input: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val hashBytes = digest.digest(input.toByteArray(StandardCharsets.UTF_8))
    return hashBytes.joinToString(separator = "") { byte -> "%02x".format(byte) }
  }

  fun hashNormalizedPhone(rawPhone: String): String? {
    val normalized = normalizePhoneNumber(rawPhone)
    if (normalized.count { it.isDigit() } < 7) return null
    return sha256Hex(normalized)
  }
}
