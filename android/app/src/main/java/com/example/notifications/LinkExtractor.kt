package com.example.notifications

import java.net.URI
import java.util.Locale

/**
 * Извлечение и нормализация http/https ссылок согласно разделу 11 контракта:
 * схема и хост в нижнем регистре, параметры запроса (?...) и фрагмент (#...) удаляются.
 */
object LinkExtractor {

  private val URL_REGEX = Regex("""https?://[^\s<>"'\[\]{}()]+""", RegexOption.IGNORE_CASE)
  private val TRAILING_PUNCTUATION = charArrayOf('.', ',', '!', '?', ';', ':', ')', ']', '}')

  fun extractNormalizedLinks(text: String?): List<String> {
    if (text.isNullOrBlank()) return emptyList()
    return URL_REGEX.findAll(text)
      .mapNotNull { match ->
        val raw = match.value.trimEnd(*TRAILING_PUNCTUATION)
        normalizeUrl(raw)
      }
      .distinct()
      .toList()
  }

  fun normalizeUrl(rawUrl: String): String? {
    val trimmed = rawUrl.trim()
    if (trimmed.isEmpty()) return null
    return runCatching {
      val uri = URI(trimmed)
      val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return@runCatching null
      if (scheme != "http" && scheme != "https") return@runCatching null
      val host = uri.host?.lowercase(Locale.ROOT) ?: return@runCatching null
      val portPart = if (uri.port != -1) ":${uri.port}" else ""
      val rawPath = uri.rawPath.orEmpty()
      "$scheme://$host$portPart$rawPath"
    }.getOrNull()
  }
}
