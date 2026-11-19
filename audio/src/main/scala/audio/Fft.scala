package audio

import img.MathX

/** 反復型の基数 2 FFT。回転因子は自前の cos / sin で作り、同じ長さが続く間は使い回す */
object Fft {
  private var cachedN = 0
  private var cosT: Array[Double] = Array.emptyDoubleArray
  private var sinT: Array[Double] = Array.emptyDoubleArray

  private def twiddles(n: Int): Unit = if (n != cachedN) {
    val half = n / 2
    cosT = new Array[Double](half)
    sinT = new Array[Double](half)
    var k = 0
    while (k < half) {
      val a = MathX.TwoPi * k / n
      cosT(k) = MathX.cos(a)
      sinT(k) = MathX.sin(a)
      k += 1
    }
    cachedN = n
  }

  /** その場で変換する。n は 2 のべき乗。inverse = true なら逆変換 (1/n の正規化はしない) */
  def transform(re: Array[Double], im: Array[Double], n: Int, inverse: Boolean): Unit = {
    twiddles(n)
    // ビット反転の並べ替え
    var j = 0
    var i = 1
    while (i < n) {
      var bit = n >> 1
      while ((j & bit) != 0) { j ^= bit; bit >>= 1 }
      j ^= bit
      if (i < j) {
        val tr = re(i); re(i) = re(j); re(j) = tr
        val ti = im(i); im(i) = im(j); im(j) = ti
      }
      i += 1
    }
    // バタフライ
    val sign = if (inverse) 1.0 else -1.0
    var len = 2
    while (len <= n) {
      val half = len >> 1
      val step = n / len
      var start = 0
      while (start < n) {
        var k = 0
        while (k < half) {
          val wr = cosT(k * step)
          val wi = sign * sinT(k * step)
          val a = start + k
          val b = a + half
          val xr = re(b) * wr - im(b) * wi
          val xi = re(b) * wi + im(b) * wr
          re(b) = re(a) - xr; im(b) = im(a) - xi
          re(a) += xr; im(a) += xi
          k += 1
        }
        start += len
      }
      len <<= 1
    }
  }

  def nextPow2(x: Int): Int = { var n = 1; while (n < x) n <<= 1; n }

  /**
   * 2 本の実数信号 a, b の線形畳み込み。
   * a を実部、b を虚部に入れて 1 回だけ複素 FFT し、共役対称性から 2 本のスペクトルを取り出して掛ける。
   * 逆変換も 1 回なので、FFT は合計 2 回で済む。
   */
  def convolve(a: Array[Float], b: Array[Float]): Array[Float] = {
    val outLen = a.length + b.length - 1
    val n = nextPow2(outLen)
    val re = new Array[Double](n)
    val im = new Array[Double](n)
    var i = 0
    while (i < a.length) { re(i) = a(i); i += 1 }
    i = 0
    while (i < b.length) { im(i) = b(i); i += 1 }
    transform(re, im, n, inverse = false)
    var k = 0
    while (k <= n / 2) {
      val j = (n - k) & (n - 1)
      val xr = re(k); val xi = im(k); val yr = re(j); val yi = im(j)
      // A = (X[k] + conj(X[n-k])) / 2,  B = (X[k] - conj(X[n-k])) / 2i
      val ar = (xr + yr) * 0.5; val ai = (xi - yi) * 0.5
      val br = (xi + yi) * 0.5; val bi = -(xr - yr) * 0.5
      val pr = ar * br - ai * bi
      val pi = ar * bi + ai * br
      // 実数信号の畳み込みなので、結果のスペクトルも共役対称
      re(k) = pr; im(k) = pi
      re(j) = pr; im(j) = -pi
      k += 1
    }
    transform(re, im, n, inverse = true)
    val out = new Array[Float](outLen)
    val s = 1.0 / n
    i = 0
    while (i < outLen) { out(i) = (re(i) * s).toFloat; i += 1 }
    lastSize = n
    out
  }

  /** 直前の convolve で使った FFT の長さ */
  var lastSize = 0
}
