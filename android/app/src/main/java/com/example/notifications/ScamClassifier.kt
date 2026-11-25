package com.example.notifications

import android.content.Context
import java.util.Locale
import kotlin.math.exp
import kotlin.math.max
import org.json.JSONObject

/**
 * Контракт локальной модели классификации переписки (раздел 10).
 * Работает строго на устройстве; текст сообщений никуда не отправляется.
 */
interface ScamClassifier {
  fun classify(messages: List<String>): ClassifierResult
}

data class ClassifierResult(
  val score: Float,
  val flags: List<String>,
)

object PatternFlags {
  const val FAMILY_CLAIM_NEW_NUMBER = "family_claim_new_number"
  const val URGENT_MONEY_REQUEST = "urgent_money_request"
  const val ASKS_CODE = "asks_code"
  const val ASKS_SCREEN_SHARE = "asks_screen_share"
}

/**
 * Нормализация текста для правил и локальной модели:
 * нижний регистр, схлопывание пробелов, казахские буквы сохраняются без замены.
 */
object ScamTextNormalizer {
  private val MULTI_SPACE_REGEX = Regex("\\s+")

  fun normalize(text: String): String =
    text
      .lowercase(Locale.ROOT)
      .replace(MULTI_SPACE_REGEX, " ")
      .trim()
}

/**
 * Запасной и базовый классификатор на ключевых фразах (русский, казахский и смешанный текст).
 */
class RulesClassifier : ScamClassifier {

  override fun classify(messages: List<String>): ClassifierResult {
    if (messages.isEmpty()) return ClassifierResult(score = 0f, flags = emptyList())
    val combined = ScamTextNormalizer.normalize(messages.joinToString("\n"))
    if (combined.isEmpty()) return ClassifierResult(score = 0f, flags = emptyList())

    val flags = mutableListOf<String>()
    var score = 0f

    val hasFamilyClaim = FAMILY_PHRASES.any { combined.contains(it) }
    val hasNewNumberHint = NEW_NUMBER_PHRASES.any { combined.contains(it) }
    if (hasFamilyClaim && (hasNewNumberHint || combined.contains("новый номер") || combined.contains("жаңа нөмір"))) {
      flags += PatternFlags.FAMILY_CLAIM_NEW_NUMBER
      score += 0.55f
    } else if (STRONG_FAMILY_SCAM_PHRASES.any { combined.contains(it) }) {
      flags += PatternFlags.FAMILY_CLAIM_NEW_NUMBER
      score += 0.65f
    }

    if (URGENT_MONEY_PHRASES.any { combined.contains(it) }) {
      flags += PatternFlags.URGENT_MONEY_REQUEST
      score += 0.45f
    }

    if (ASKS_CODE_PHRASES.any { combined.contains(it) }) {
      flags += PatternFlags.ASKS_CODE
      score += 0.75f
    }

    if (ASKS_SCREEN_SHARE_PHRASES.any { combined.contains(it) }) {
      flags += PatternFlags.ASKS_SCREEN_SHARE
      score += 0.80f
    }

    if (flags.contains(PatternFlags.FAMILY_CLAIM_NEW_NUMBER) &&
      flags.contains(PatternFlags.URGENT_MONEY_REQUEST)
    ) {
      score = max(score, 0.90f)
    }

    return ClassifierResult(
      score = score.coerceIn(0f, 1f),
      flags = flags.distinct(),
    )
  }

  companion object {
    private val FAMILY_PHRASES =
      listOf("мама", "папа", "анашым", "әке", "апа", "брат", "сестра", "сынок", "дочка", "это я")

    private val NEW_NUMBER_PHRASES =
      listOf(
        "новый номер",
        "другой номер",
        "сменил номер",
        "сменила номер",
        "разбил телефон",
        "потерял телефон",
        "жаңа нөмір",
        "басқа нөмір",
        "нөмірім ауысты",
      )

    private val STRONG_FAMILY_SCAM_PHRASES =
      listOf(
        "мама, это я",
        "мама это я",
        "папа, это я",
        "анашым, бұл мен",
        "мама у меня новый номер",
        "у меня новый номер",
      )

    private val URGENT_MONEY_PHRASES =
      listOf(
        "срочно переведи",
        "срочно скинь",
        "срочно нужны деньги",
        "выручай деньгами",
        "скинь на каспи",
        "переведи на карту",
        "одолжи срочно",
        "безопасный счет",
        "безопасный счёт",
        "тез ақша жібер",
        "шұғыл ақша керек",
        "каспиге аудар",
        "ақша аударып жібер",
      )

    private val ASKS_CODE_PHRASES =
      listOf(
        "код из смс",
        "смс код",
        "смс-код",
        "продиктуй код",
        "скажи код",
        "пришли код",
        "назови код",
        "код подтверждения",
        "одноразовый код",
        "смс келген код",
        "кодты айт",
        "кодты жібер",
      )

    private val ASKS_SCREEN_SHARE_PHRASES =
      listOf(
        "покажи экран",
        "демонстрация экрана",
        "демонстрацию экрана",
        "включи экран",
        "anydesk",
        "teamviewer",
        "rustdesk",
        "экранды көрсет",
      )
  }
}

/**
 * Локальная TF-IDF + линейная модель из assets/scam_model_v1.json (модуль Г роли 3).
 * Если файл модели отсутствует или повреждён, возвращает null и приложение не падает.
 */
class OnDeviceModelClassifier private constructor(
  private val ngramMin: Int,
  private val ngramMax: Int,
  private val vocab: Map<String, Int>,
  private val idf: FloatArray,
  private val weights: FloatArray,
  private val bias: Float,
) : ScamClassifier {

  override fun classify(messages: List<String>): ClassifierResult {
    if (messages.isEmpty() || vocab.isEmpty()) return ClassifierResult(0f, emptyList())
    val normalized = ScamTextNormalizer.normalize(messages.joinToString("\n"))
    if (normalized.isEmpty()) return ClassifierResult(0f, emptyList())

    val termFreq = HashMap<Int, Int>()
    for (n in ngramMin..ngramMax) {
      if (normalized.length < n) continue
      for (i in 0..(normalized.length - n)) {
        val gram = normalized.substring(i, i + n)
        val idx = vocab[gram] ?: continue
        termFreq[idx] = (termFreq[idx] ?: 0) + 1
      }
    }

    if (termFreq.isEmpty()) {
      val prob = sigmoid(bias)
      return ClassifierResult(score = prob, flags = emptyList())
    }

    var logit = bias
    var normSq = 0.0
    val tfidfValues = HashMap<Int, Double>(termFreq.size)
    for ((idx, tf) in termFreq) {
      if (idx < idf.size) {
        val v = tf.toDouble() * idf[idx].toDouble()
        tfidfValues[idx] = v
        normSq += v * v
      }
    }

    val norm = kotlin.math.sqrt(normSq).takeIf { it > 0.0 } ?: 1.0
    for ((idx, value) in tfidfValues) {
      if (idx < weights.size) {
        logit += ((value / norm) * weights[idx].toDouble()).toFloat()
      }
    }

    return ClassifierResult(
      score = sigmoid(logit).coerceIn(0f, 1f),
      flags = emptyList(),
    )
  }

  private fun sigmoid(x: Float): Float = (1.0 / (1.0 + exp(-x.toDouble()))).toFloat()

  companion object {
    private const val MODEL_ASSET_NAME = "scam_model_v1.json"

    fun tryLoad(context: Context): OnDeviceModelClassifier? =
      runCatching {
        val jsonText =
          context.assets.open(MODEL_ASSET_NAME).bufferedReader().use { it.readText() }
        val root = JSONObject(jsonText)
        val ngramRange = root.optJSONArray("ngram_range")
        val ngramMin = ngramRange?.optInt(0, 2) ?: 2
        val ngramMax = ngramRange?.optInt(1, 5) ?: 5

        val vocabObj = root.getJSONObject("vocab")
        val vocab = HashMap<String, Int>(vocabObj.length())
        val keys = vocabObj.keys()
        while (keys.hasNext()) {
          val key = keys.next()
          vocab[key] = vocabObj.getInt(key)
        }

        val idfArray = root.getJSONArray("idf")
        val idf = FloatArray(idfArray.length()) { i -> idfArray.getDouble(i).toFloat() }

        val weightsArray = root.getJSONArray("weights")
        val weights = FloatArray(weightsArray.length()) { i -> weightsArray.getDouble(i).toFloat() }

        val bias = root.optDouble("bias", 0.0).toFloat()

        OnDeviceModelClassifier(
          ngramMin = ngramMin,
          ngramMax = ngramMax,
          vocab = vocab,
          idf = idf,
          weights = weights,
          bias = bias,
        )
      }.getOrNull()
  }
}

/**
 * Объединённый классификатор: score = максимум из RulesClassifier и OnDeviceModelClassifier,
 * flags объединяются (раздел 10).
 */
class CompositeScamClassifier(context: Context) : ScamClassifier {

  private val rulesClassifier = RulesClassifier()
  private val onDeviceModelClassifier: OnDeviceModelClassifier? =
    OnDeviceModelClassifier.tryLoad(context.applicationContext)

  val isMlModelLoaded: Boolean
    get() = onDeviceModelClassifier != null

  override fun classify(messages: List<String>): ClassifierResult {
    val rulesResult = rulesClassifier.classify(messages)
    val modelResult = onDeviceModelClassifier?.classify(messages)
    if (modelResult == null) return rulesResult

    return ClassifierResult(
      score = max(rulesResult.score, modelResult.score).coerceIn(0f, 1f),
      flags = (rulesResult.flags + modelResult.flags).distinct(),
    )
  }
}
