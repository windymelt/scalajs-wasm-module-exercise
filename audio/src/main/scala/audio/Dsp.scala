package audio

import java.util.Random

import img.MathX

/** 素材とインパルス応答の合成。サンプリング周波数は 48 kHz 固定 */
object Dsp {
  final val SR = 48000

  private def midiToHz(m: Int): Double = 440.0 * MathX.pow(2.0, (m - 69) / 12.0)

  // ---- 素材

  /** Karplus-Strong 法の撥弦で、ペンタトニックの短いフレーズを弾く */
  def pluckPhrase(): Array[Float] = {
    val rnd = new Random(3)
    // (開始秒, MIDI ノート番号, 強さ)
    val notes = List(
      (0.00, 57, 0.9), (0.30, 60, 0.7), (0.60, 64, 0.8), (0.90, 67, 0.7),
      (1.20, 69, 0.9), (1.65, 67, 0.6), (1.95, 64, 0.7), (2.40, 62, 0.8),
      (2.85, 57, 0.6), (2.86, 64, 0.6), (2.87, 69, 0.7),
    )
    val out = new Array[Float]((4.2 * SR).toInt)
    for ((start, midi, amp) <- notes) {
      val period = Math.max(2, Math.floor(SR / midiToHz(midi)).toInt)
      val buf = Array.fill(period)(rnd.nextDouble() * 2 - 1)
      var idx = 0
      var i = (start * SR).toInt
      val end = Math.min(out.length, i + (1.3 * SR).toInt)
      while (i < end) {
        val next = (idx + 1) % period
        val v = buf(idx)
        out(i) = (out(i) + v * amp * 0.28).toFloat
        buf(idx) = 0.5 * (v + buf(next)) * 0.996 // 平均で高域を落としつつ減衰させる
        idx = next
        i += 1
      }
    }
    out
  }

  /** 手拍子: 短いノイズの破裂を 3 回重ねたものを、リズムに並べる */
  def claps(): Array[Float] = {
    val rnd = new Random(11)
    val out = new Array[Float]((3.0 * SR).toInt)
    for (start <- List(0.0, 0.5, 1.0, 1.25, 1.5)) {
      for (burst <- 0 until 3) {
        val s0 = ((start + burst * 0.011) * SR).toInt
        val len = (0.09 * SR).toInt
        var lp = 0.0
        var slow = 0.0
        var i = 0
        while (i < len && s0 + i < out.length) {
          val t = i.toDouble / SR
          val env = MathX.exp(-t / (if (burst == 2) 0.022 else 0.006))
          val n = rnd.nextDouble() * 2 - 1
          // 速いローパスから遅いローパスを引いて、低い音と高すぎる音を削る (1〜3 kHz あたりが残る)
          lp += 0.35 * (n - lp)
          slow += 0.05 * (lp - slow)
          out(s0 + i) = (out(s0 + i) + (lp - slow) * env * 1.6).toFloat
          i += 1
        }
      }
    }
    out
  }

  // ---- インパルス応答 (左右 2 チャンネル)

  /**
   * 残響: 指数関数的に減衰するノイズに、初期反射のまばらなパルスを足す。
   * 高い音ほど早く減衰するよう、時間とともにローパスを強めていく。
   */
  def reverb(t60: Double, preDelay: Double, damping: Double, earlyCount: Int, earlySpan: Double, seed: Int): Array[Array[Float]] = {
    val len = ((preDelay + t60 * 1.05) * SR).toInt
    Array.tabulate(2) { ch =>
      val rnd = new Random(seed * 31L + ch * 7919L)
      val out = new Array[Float](len)
      var y = 0.0
      val start = (preDelay * SR).toInt
      var i = start
      while (i < len) {
        val t = (i - start).toDouble / SR
        val env = MathX.exp(-6.907755278982137 * t / t60) // 60 dB 減衰 = e^-6.9078
        val a = Math.max(0.04, 1.0 - damping * Math.min(1.0, t / t60) * 1.4)
        y += a * ((rnd.nextDouble() * 2 - 1) - y)
        // 1 次のローパスは、通したノイズの分散を a / (2 - a) 倍に縮める。そのぶんを戻して、
        // 減衰の速さが env (T60) だけで決まるようにする
        out(i) = (y * env / Math.sqrt(a / (2 - a))).toFloat
        i += 1
      }
      // 初期反射
      for (_ <- 0 until earlyCount) {
        val d = 0.004 + rnd.nextDouble() * earlySpan
        val idx = start + (d * SR).toInt
        if (idx < len) {
          val g = (0.5 + 0.5 * rnd.nextDouble()) * (1 - 0.5 * d / earlySpan) * (if (rnd.nextBoolean()) 1 else -1)
          out(idx) = (out(idx) + g * 0.9).toFloat
        }
      }
      out
    }
  }

  /** ハン窓の小さな山。幅が広いほど高域が丸まる */
  private def bump(out: Array[Float], center: Int, width: Int, gain: Double): Unit = {
    var k = -width
    while (k <= width) {
      val idx = center + k
      if (idx >= 0 && idx < out.length) {
        val w = 0.5 * (1 + MathX.cos(MathX.Pi * k / (width + 1)))
        out(idx) = (out(idx) + gain * w / (width + 1)).toFloat
      }
      k += 1
    }
  }

  /** やまびこ: 左右交互に跳ね返る (ピンポン) エコー。回を重ねるごとに小さく、こもっていく */
  def pingPong(interval: Double = 0.32, feedback: Double = 0.6, taps: Int = 11): Array[Array[Float]] = {
    val len = ((interval * (taps + 1)) * SR).toInt
    val l = new Array[Float](len)
    val r = new Array[Float](len)
    for (k <- 1 to taps) {
      val target = if (k % 2 == 1) l else r
      bump(target, (k * interval * SR).toInt, 1 + 5 * k, MathX.pow(feedback, k - 1))
    }
    Array(l, r)
  }

  /** 電話: 300〜3400 Hz を通すバンドパス (窓関数法の FIR、ブラックマン窓) */
  def telephone(taps: Int = 1023): Array[Array[Float]] = {
    val f1 = 300.0 / SR
    val f2 = 3400.0 / SR
    val m = taps - 1
    val h = new Array[Float](taps)
    var i = 0
    while (i < taps) {
      val n = i - m / 2.0
      val ideal =
        if (n == 0) 2 * (f2 - f1)
        else (MathX.sin(MathX.TwoPi * f2 * n) - MathX.sin(MathX.TwoPi * f1 * n)) / (MathX.Pi * n)
      val w = 0.42 - 0.5 * MathX.cos(MathX.TwoPi * i / m) + 0.08 * MathX.cos(2 * MathX.TwoPi * i / m)
      h(i) = (ideal * w).toFloat
      i += 1
    }
    Array(h, h.clone())
  }

  /** 金属の筒: 数ミリ秒ごとの反射が続くくし形の応答。周期の逆数の倍音で鳴る */
  def metalTube(t60: Double): Array[Array[Float]] = {
    val len = (t60 * 1.05 * SR).toInt
    Array.tabulate(2) { ch =>
      val period = if (ch == 0) 0.0043 else 0.0047
      val out = new Array[Float](len)
      var k = 1
      var t = period
      while (t < t60 * 1.05) {
        val g = MathX.exp(-6.907755278982137 * t / t60) * (if (k % 2 == 0) 1 else -0.9)
        bump(out, (t * SR).toInt, 1, g)
        k += 1
        t = k * period
      }
      out
    }
  }

  /** プリセットの番号から作る。t60 は残響の長さ (秒) で、使わないプリセットもある */
  def preset(kind: Int, t60: Double): Array[Array[Float]] = kind match {
    case 0 => reverb(t60, 0.004, 0.55, 12, 0.03, 1)
    case 1 => reverb(t60, 0.025, 0.7, 18, 0.08, 2)
    case 2 => reverb(t60, 0.06, 0.85, 26, 0.16, 3)
    case 3 => pingPong()
    case 4 => telephone()
    case _ => metalTube(t60)
  }
}
