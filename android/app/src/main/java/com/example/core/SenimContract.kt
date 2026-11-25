package com.example.core

import com.example.BuildConfig

/**
 * Единые константы контракта Senim v1 (разделы 1–7 общего документа).
 */
object SenimContract {
  const val DEFAULT_BASE_URL = "https://senim.studynesthub.com"
  const val LOCAL_WIFI_BASE_URL = "http://192.168.1.100:8000"
  const val EMULATOR_BASE_URL = "http://10.0.2.2:8000"

  const val INITIAL_ALERTS_SINCE = "1970-01-01T00:00:00Z"

  const val USER_ID_MOM = "user_mom"
  const val USER_ID_SON = "user_son"

  const val ROLE_USER = "user"
  const val ROLE_TRUSTED = "trusted"

  const val TEST_SCAM_PHONE = "+77000000001"
  const val TEST_SCAM_TELEGRAM_ID = "tg:900000001"

  fun defaultApiToken(): String {
    val fromBuildConfig = BuildConfig.SENIM_API_TOKEN.trim()
    return if (fromBuildConfig.isEmpty() || fromBuildConfig == "MY_SENIM_API_TOKEN") {
      ""
    } else {
      fromBuildConfig
    }
  }
}

/**
 * Режим работы одного APK: «пользователь» или «доверенное лицо».
 */
enum class AppRole(
  val roleValue: String,
  val userId: String,
  val displayTitle: String,
) {
  USER(
    roleValue = SenimContract.ROLE_USER,
    userId = SenimContract.USER_ID_MOM,
    displayTitle = "Пользователь",
  ),
  TRUSTED(
    roleValue = SenimContract.ROLE_TRUSTED,
    userId = SenimContract.USER_ID_SON,
    displayTitle = "Доверенное лицо",
  );

  companion object {
    fun fromRoleValue(value: String?): AppRole? =
      entries.firstOrNull { it.roleValue == value }
  }
}
