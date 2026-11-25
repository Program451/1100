package com.example.notifications

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.core.AppSettingsRepository
import com.example.core.SenimContract
import com.example.network.LinkCheckRequest
import com.example.network.NetworkClientProvider
import com.example.ui.theme.SenimTheme

private sealed interface GatekeeperUiState {
  data object Checking : GatekeeperUiState
  data class Allowed(val browserPackage: String?) : GatekeeperUiState
  data class Blocked(val reasons: List<String>) : GatekeeperUiState
  data class Error(val message: String) : GatekeeperUiState
}

/**
 * «Привратник» ссылок: открывается по нажатию http/https ссылки из мессенджера,
 * проверяет нормализованный адрес через POST /links/check и только при risk=low
 * передаёт исходную ссылку в настоящий внешний браузер явным Intent.
 */
class LinkGatekeeperActivity : ComponentActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()

    val originalUri = intent?.data
    val rawUrl = originalUri?.toString().orEmpty()
    val normalizedUrl = LinkExtractor.normalizeUrl(rawUrl)

    setContent {
      SenimTheme {
        var checkTrigger by remember { mutableIntStateOf(0) }
        var uiState by remember {
          mutableStateOf<GatekeeperUiState>(
            if (originalUri == null || normalizedUrl == null) {
              GatekeeperUiState.Error("Некорректная или неподдерживаемая ссылка")
            } else {
              GatekeeperUiState.Checking
            }
          )
        }

        LaunchedEffect(normalizedUrl, checkTrigger) {
          val safeUrl = normalizedUrl ?: return@LaunchedEffect
          val safeUri = originalUri ?: return@LaunchedEffect

          uiState = GatekeeperUiState.Checking
          uiState = evaluateLink(safeUrl, safeUri)
        }

        GatekeeperScreen(
          displayUrl = normalizedUrl ?: rawUrl,
          state = uiState,
          onRetry = { checkTrigger++ },
          onOpenBrowser = { pkg ->
            if (originalUri != null && pkg != null) {
              launchExplicitBrowser(originalUri, pkg)
            }
          },
          onClose = { finish() },
        )
      }
    }
  }

  private suspend fun evaluateLink(
    normalizedUrl: String,
    originalUri: Uri,
  ): GatekeeperUiState {
    val settings = AppSettingsRepository(applicationContext).getCurrentSettings()
    val userId = settings.selectedRole?.userId ?: SenimContract.USER_ID_MOM
    val networkProvider = NetworkClientProvider()

    val api =
      networkProvider.getApi(baseUrl = settings.baseUrl, apiToken = settings.effectiveApiToken)
        .getOrElse { error ->
        return GatekeeperUiState.Error(
          "Некорректный адрес сервера: ${error.localizedMessage ?: "ошибка"}"
        )
      }

    return try {
      val response =
        api.checkLink(
          LinkCheckRequest(
            userId = userId,
            url = normalizedUrl,
            source = SOURCE_GATEKEEPER,
          )
        )
      if (!response.isSuccessful) {
        val parsed = networkProvider.parseError(response.errorBody()?.string())
        val details =
          if (parsed != null) "${parsed.code}: ${parsed.message}" else "HTTP ${response.code()}"
        return GatekeeperUiState.Error(details)
      }

      val body = response.body() ?: return GatekeeperUiState.Error("Пустой ответ от сервера")
      if (body.risk == "high") {
        GatekeeperUiState.Blocked(reasons = body.reasons)
      } else {
        val browserPkg = findExternalBrowserPackage(applicationContext)
        if (browserPkg != null) {
          launchExplicitBrowser(originalUri, browserPkg)
          finish()
        }
        GatekeeperUiState.Allowed(browserPackage = browserPkg)
      }
    } catch (e: Exception) {
      GatekeeperUiState.Error(e.localizedMessage ?: "Сервер недоступен")
    }
  }

  private fun launchExplicitBrowser(uri: Uri, browserPackage: String) {
    val browserIntent =
      Intent(Intent.ACTION_VIEW, uri).apply {
        addCategory(Intent.CATEGORY_BROWSABLE)
        setPackage(browserPackage)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
    runCatching { startActivity(browserIntent) }
  }

  companion object {
    private const val SOURCE_GATEKEEPER = "gatekeeper"

    private val PREFERRED_BROWSERS =
      listOf(
        "com.android.chrome",
        "org.mozilla.firefox",
        "com.sec.android.app.sbrowser",
        "com.microsoft.emmx",
        "com.opera.browser",
        "com.brave.browser",
        "com.yandex.browser",
      )

    fun findExternalBrowserPackage(context: Context): String? {
      val probeIntent =
        Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")).apply {
          addCategory(Intent.CATEGORY_BROWSABLE)
        }
      val pm = context.packageManager
      val candidates =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
          pm.queryIntentActivities(
            probeIntent,
            PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()),
          )
        } else {
          @Suppress("DEPRECATION")
          pm.queryIntentActivities(probeIntent, PackageManager.MATCH_ALL)
        }

      val externalPackages =
        candidates
          .mapNotNull { it.activityInfo?.packageName }
          .filter { pkg -> pkg != context.packageName && pkg != "android" }
          .distinct()

      val preferred = PREFERRED_BROWSERS.firstOrNull { it in externalPackages }
      return preferred ?: externalPackages.firstOrNull()
    }
  }
}

@Composable
private fun GatekeeperScreen(
  displayUrl: String,
  state: GatekeeperUiState,
  onRetry: () -> Unit,
  onOpenBrowser: (String?) -> Unit,
  onClose: () -> Unit,
) {
  Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { innerPadding ->
    Column(
      modifier =
        Modifier
          .fillMaxSize()
          .padding(innerPadding)
          .verticalScroll(rememberScrollState())
          .padding(20.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
    ) {
      Column(
        modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        when (state) {
          GatekeeperUiState.Checking -> {
            Card(
              modifier = Modifier.fillMaxWidth().testTag("gatekeeper_checking_card"),
              shape = RoundedCornerShape(22.dp),
              colors =
                CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            ) {
              Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
              ) {
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                  Icon(
                    imageVector = Icons.Filled.Shield,
                    contentDescription = "Проверка ссылки Senim",
                    tint = MaterialTheme.colorScheme.primary,
                  )
                  Text(
                    text = "Senim проверяет ссылку",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                  )
                }
                Text(
                  text = displayUrl,
                  style = MaterialTheme.typography.bodyLarge,
                  color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                  CircularProgressIndicator(strokeWidth = 2.5.dp, modifier = Modifier.size(20.dp))
                  Text(
                    text = "Сверяем адрес с сервером безопасности...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                  )
                }
              }
            }
          }

          is GatekeeperUiState.Allowed -> {
            Card(
              modifier = Modifier.fillMaxWidth().testTag("gatekeeper_allowed_card"),
              shape = RoundedCornerShape(22.dp),
              colors =
                CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            ) {
              Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
              ) {
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                  Icon(
                    imageVector = Icons.Outlined.CheckCircle,
                    contentDescription = "Ссылка безопасна",
                    tint = MaterialTheme.colorScheme.primary,
                  )
                  Text(
                    text = "Ссылка безопасна (risk=low)",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                  )
                }
                Text(
                  text = displayUrl,
                  style = MaterialTheme.typography.bodyLarge,
                  color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                if (state.browserPackage != null) {
                  Button(
                    onClick = { onOpenBrowser(state.browserPackage) },
                    modifier =
                      Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("gatekeeper_open_browser_button"),
                  ) {
                    Icon(
                      imageVector = Icons.Outlined.OpenInBrowser,
                      contentDescription = null,
                      modifier = Modifier.padding(end = 8.dp),
                    )
                    Text("Открыть в браузере")
                  }
                } else {
                  Text(
                    text = "На устройстве не найден внешний браузер для открытия ссылки.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                  )
                }
              }
            }
          }

          is GatekeeperUiState.Blocked -> {
            // Экран блокировки при risk=high — строго БЕЗ кнопки «Открыть»
            Card(
              modifier = Modifier.fillMaxWidth().testTag("gatekeeper_blocked_card"),
              shape = RoundedCornerShape(24.dp),
              colors =
                CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            ) {
              Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
              ) {
                Surface(
                  shape = RoundedCornerShape(14.dp),
                  color = MaterialTheme.colorScheme.error,
                ) {
                  Icon(
                    imageVector = Icons.Filled.Block,
                    contentDescription = "Переход заблокирован",
                    tint = MaterialTheme.colorScheme.onError,
                    modifier = Modifier.padding(12.dp),
                  )
                }
                Text(
                  text = "Переход заблокирован",
                  style = MaterialTheme.typography.headlineMedium,
                  color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                  text =
                    "Senim остановил открытие опасной ссылки. Не вводите пароли, номера карт и коды из SMS.",
                  style = MaterialTheme.typography.bodyLarge,
                  color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Surface(
                  shape = RoundedCornerShape(12.dp),
                  color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                ) {
                  Text(
                    text = displayUrl,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                  )
                }
                if (state.reasons.isNotEmpty()) {
                  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                      text = "Причины блокировки:",
                      style = MaterialTheme.typography.titleMedium,
                      color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    state.reasons.forEach { reason ->
                      Text(
                        text = "• $reason",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                      )
                    }
                  }
                }
              }
            }
          }

          is GatekeeperUiState.Error -> {
            Card(
              modifier = Modifier.fillMaxWidth().testTag("gatekeeper_error_card"),
              shape = RoundedCornerShape(22.dp),
              colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
              Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
              ) {
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                  Icon(
                    imageVector = Icons.Outlined.ErrorOutline,
                    contentDescription = "Ошибка проверки ссылки",
                    tint = MaterialTheme.colorScheme.error,
                  )
                  Text(
                    text = "Не удалось проверить ссылку",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
                Text(
                  text = displayUrl,
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                  text = state.message,
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                  onClick = onRetry,
                  modifier =
                    Modifier
                      .fillMaxWidth()
                      .heightIn(min = 48.dp)
                      .testTag("gatekeeper_retry_button"),
                ) {
                  Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 8.dp),
                  )
                  Text("Повторить проверку")
                }
              }
            }
          }
        }

        Spacer(modifier = Modifier.height(4.dp))

        OutlinedButton(
          onClick = onClose,
          colors =
            ButtonDefaults.outlinedButtonColors(
              contentColor = MaterialTheme.colorScheme.onBackground
            ),
          modifier =
            Modifier
              .fillMaxWidth()
              .heightIn(min = 48.dp)
              .testTag("gatekeeper_close_button"),
        ) {
          Text("Закрыть")
        }
      }
    }
  }
}
