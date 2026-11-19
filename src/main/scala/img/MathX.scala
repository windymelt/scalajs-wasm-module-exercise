package img

import java.lang.Double.{doubleToRawLongBits, longBitsToDouble}

/** WasmModule では Math.sin/cos/exp/log/pow などがリンクできない
 *  (JS の Math に委譲しているため) ので、グラフィックス用途に十分な精度で自前実装する。
 */
object MathX {
  final val Pi = 3.141592653589793
  final val TwoPi = 2 * Pi
  final val HalfPi = Pi / 2
  final val Ln2 = 0.6931471805599453

  def cos(x0: Double): Double = {
    // [-π, π] に還元
    var x = x0 - TwoPi * Math.floor((x0 + Pi) / TwoPi)
    if (x < 0) x = -x // cos は偶関数
    // [0, π/2] に還元
    var sign = 1.0
    if (x > HalfPi) { x = Pi - x; sign = -1.0 }
    val x2 = x * x
    var term = 1.0
    var sum = 1.0
    var k = 1
    while (k <= 10) {
      term *= -x2 / ((2 * k - 1) * (2 * k))
      sum += term
      k += 1
    }
    sign * sum
  }

  def sin(x0: Double): Double = {
    // [-π, π] に還元してから、sin(π - x) = sin(x) で [-π/2, π/2] に寄せる
    var x = x0 - TwoPi * Math.floor((x0 + Pi) / TwoPi)
    if (x > HalfPi) x = Pi - x
    else if (x < -HalfPi) x = -Pi - x
    val x2 = x * x
    var term = x
    var sum = x
    var k = 1
    while (k <= 10) {
      term *= -x2 / ((2 * k) * (2 * k + 1))
      sum += term
      k += 1
    }
    sum
  }
  def tan(x: Double): Double = sin(x) / cos(x)

  /** 2^k (正規化数の範囲はビットを直接組み立てる) */
  private def pow2(k: Int): Double =
    if (k > 1023) Double.PositiveInfinity
    else if (k >= -1022) longBitsToDouble((k + 1023).toLong << 52)
    else pow2(k + 1000) * pow2(-1000)

  def exp(x: Double): Double =
    if (x.isNaN) x
    else if (x > 709.8) Double.PositiveInfinity
    else if (x < -745.2) 0.0
    else {
      // x = k·ln2 + r, |r| <= ln2/2
      val k = Math.floor(x / Ln2 + 0.5)
      val r = x - k * Ln2
      var term = 1.0
      var sum = 1.0
      var i = 1
      while (i <= 16) {
        term *= r / i
        sum += term
        i += 1
      }
      sum * pow2(k.toInt)
    }

  def log(x: Double): Double =
    if (x.isNaN || x < 0) Double.NaN
    else if (x == 0) Double.NegativeInfinity
    else if (x.isInfinite) x
    else {
      var bits = doubleToRawLongBits(x)
      var e = ((bits >>> 52) & 0x7ff).toInt
      var adj = 0
      if (e == 0) { // 非正規化数
        bits = doubleToRawLongBits(x * 4503599627370496.0) // × 2^52
        e = ((bits >>> 52) & 0x7ff).toInt
        adj = -52
      }
      // x = m · 2^ex, m ∈ [1, 2)
      var m = longBitsToDouble((bits & 0x000fffffffffffffL) | 0x3ff0000000000000L)
      var ex = e - 1023 + adj
      if (m > 1.4142135623730951) { m /= 2; ex += 1 } // m ∈ [1/√2, √2)
      // log m = 2·atanh(s), s = (m-1)/(m+1), |s| <= 0.1716
      val s = (m - 1) / (m + 1)
      val s2 = s * s
      var term = s
      var sum = 0.0
      var k = 1
      while (k <= 23) {
        sum += term / k
        term *= s2
        k += 2
      }
      2 * sum + ex * Ln2
    }

  def pow(x: Double, y: Double): Double = exp(y * log(x))
}
