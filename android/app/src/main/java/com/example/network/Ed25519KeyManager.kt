package com.example.network

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Генератор ключей Ed25519 (RFC 8032) на чистом Kotlin для работы на всех версиях Android (API 24+).
 * Публичный ключ всегда кодируется как Base64 (без переносов строк) от ровно 32 сырых байт Ed25519.
 */
object Ed25519KeyManager {

  const val RAW_KEY_BYTE_LENGTH = 32

  // p = 2^255 - 19
  private val P: BigInteger = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))

  // d = -121665 * 121666^(-1) mod p
  private val D: BigInteger =
    BigInteger.valueOf(-121665)
      .multiply(BigInteger.valueOf(121666).modInverse(P))
      .mod(P)

  private val TWO_D: BigInteger = D.shiftLeft(1).mod(P)

  // Порядок подгруппы L = 2^252 + 27742317777372353535851937790883648493 (RFC 8032 Section 5.1)
  private val L: BigInteger =
    BigInteger.ONE.shiftLeft(252)
      .add(BigInteger("27742317777372353535851937790883648493"))

  // Базовая точка B (RFC 8032 Section 5.1)
  private val BX =
    BigInteger("15112221349535400772501151409588531511454012693041857206046113283949847762202")
  private val BY =
    BigInteger("46316835694926478169428394003475163141307993866256225615783033603165251855960")

  private data class ExtPoint(
    val x: BigInteger,
    val y: BigInteger,
    val z: BigInteger,
    val t: BigInteger,
  )

  private val IDENTITY = ExtPoint(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)
  private val BASE_POINT = ExtPoint(BX, BY, BigInteger.ONE, BX.multiply(BY).mod(P))

  data class KeyPairBytes(
    val privateSeed: ByteArray,
    val publicKey: ByteArray,
  ) {
    @OptIn(ExperimentalEncodingApi::class)
    val publicKeyBase64: String
      get() = Base64.Default.encode(publicKey)
  }

  fun generateKeyPair(): KeyPairBytes {
    val seed = ByteArray(RAW_KEY_BYTE_LENGTH)
    SecureRandom().nextBytes(seed)
    return fromSeed(seed)
  }

  fun fromSeed(seed: ByteArray): KeyPairBytes {
    require(seed.size == RAW_KEY_BYTE_LENGTH) {
      "Ed25519 seed must be $RAW_KEY_BYTE_LENGTH bytes, got ${seed.size}"
    }
    val digest = MessageDigest.getInstance("SHA-512").digest(seed)
    val scalarBytes = digest.copyOfRange(0, RAW_KEY_BYTE_LENGTH)
    clampScalarBytes(scalarBytes)

    val scalar = littleEndianToBigInt(scalarBytes)
    val publicPoint = scalarMult(scalar, BASE_POINT)
    val publicBytes = encodePoint(publicPoint)
    return KeyPairBytes(
      privateSeed = seed.copyOf(),
      publicKey = publicBytes,
    )
  }

  /**
   * Подпись сообщения по стандарту Ed25519 (RFC 8032 Section 5.1.6).
   * Используется для подписи записей офлайн-журнала после расшифровки seed из Android Keystore в памяти.
   */
  fun sign(privateSeed: ByteArray, message: ByteArray): ByteArray {
    require(privateSeed.size == RAW_KEY_BYTE_LENGTH) {
      "Ed25519 seed must be $RAW_KEY_BYTE_LENGTH bytes, got ${privateSeed.size}"
    }
    val h = MessageDigest.getInstance("SHA-512").digest(privateSeed)
    val sBytes = h.copyOfRange(0, RAW_KEY_BYTE_LENGTH)
    clampScalarBytes(sBytes)
    val s = littleEndianToBigInt(sBytes)
    val prefix = h.copyOfRange(RAW_KEY_BYTE_LENGTH, 64)
    val publicA = encodePoint(scalarMult(s, BASE_POINT))

    val sha512 = MessageDigest.getInstance("SHA-512")
    sha512.update(prefix)
    sha512.update(message)
    val r = littleEndianToBigInt(sha512.digest()).mod(L)
    val rBytes = encodePoint(scalarMult(r, BASE_POINT))

    sha512.reset()
    sha512.update(rBytes)
    sha512.update(publicA)
    sha512.update(message)
    val k = littleEndianToBigInt(sha512.digest()).mod(L)

    val bigS = r.add(k.multiply(s)).mod(L)
    val sOut = bigIntToLittleEndian32(bigS)
    return rBytes + sOut
  }

  private fun clampScalarBytes(scalarBytes: ByteArray) {
    scalarBytes[0] = (scalarBytes[0].toInt() and 0xF8).toByte()
    scalarBytes[31] = (scalarBytes[31].toInt() and 0x7F).toByte()
    scalarBytes[31] = (scalarBytes[31].toInt() or 0x40).toByte()
  }

  private fun bigIntToLittleEndian32(value: BigInteger): ByteArray {
    val out = ByteArray(RAW_KEY_BYTE_LENGTH)
    var temp = value
    val mask = BigInteger.valueOf(0xFF)
    for (i in 0 until RAW_KEY_BYTE_LENGTH) {
      out[i] = temp.and(mask).toInt().toByte()
      temp = temp.shiftRight(8)
    }
    return out
  }

  @OptIn(ExperimentalEncodingApi::class)
  fun decodeBase64(encoded: String): ByteArray = Base64.Default.decode(encoded.trim())

  @OptIn(ExperimentalEncodingApi::class)
  fun encodeBase64(bytes: ByteArray): String = Base64.Default.encode(bytes)

  private fun scalarMult(scalar: BigInteger, point: ExtPoint): ExtPoint {
    var result = IDENTITY
    var addend = point
    val bitLen = scalar.bitLength()
    for (bit in 0 until bitLen) {
      if (scalar.testBit(bit)) {
        result = pointAdd(result, addend)
      }
      addend = pointAdd(addend, addend)
    }
    return result
  }

  // Полная формула сложения на скрученной кривой Эдвардса (a = -1), безопасна также для удвоения.
  private fun pointAdd(p1: ExtPoint, p2: ExtPoint): ExtPoint {
    val a = p1.y.subtract(p1.x).multiply(p2.y.subtract(p2.x)).mod(P)
    val b = p1.y.add(p1.x).multiply(p2.y.add(p2.x)).mod(P)
    val c = TWO_D.multiply(p1.t).multiply(p2.t).mod(P)
    val d = p1.z.shiftLeft(1).multiply(p2.z).mod(P)
    val e = b.subtract(a).mod(P)
    val f = d.subtract(c).mod(P)
    val g = d.add(c).mod(P)
    val h = b.add(a).mod(P)

    val x3 = e.multiply(f).mod(P)
    val y3 = g.multiply(h).mod(P)
    val t3 = e.multiply(h).mod(P)
    val z3 = f.multiply(g).mod(P)
    return ExtPoint(x3, y3, z3, t3)
  }

  private fun encodePoint(point: ExtPoint): ByteArray {
    val zInv = point.z.modInverse(P)
    val x = point.x.multiply(zInv).mod(P)
    val y = point.y.multiply(zInv).mod(P)

    val out = ByteArray(RAW_KEY_BYTE_LENGTH)
    var tempY = y
    val mask = BigInteger.valueOf(0xFF)
    for (i in 0 until RAW_KEY_BYTE_LENGTH) {
      out[i] = tempY.and(mask).toInt().toByte()
      tempY = tempY.shiftRight(8)
    }
    if (x.testBit(0)) {
      out[31] = (out[31].toInt() or 0x80).toByte()
    }
    return out
  }

  private fun littleEndianToBigInt(bytes: ByteArray): BigInteger {
    val reversed = bytes.reversedArray()
    return BigInteger(1, reversed)
  }
}
