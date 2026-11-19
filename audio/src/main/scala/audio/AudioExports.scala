package audio

import scala.scalajs.wasm.annotation._

import img.MathX

/**
 * ブラウザ向けの API。音声は 48 kHz の Float32 で Scala 側に持ち、サンプルを 1 つずつ受け渡す
 * (配列は境界でコピーされ、JS からは中身を読めないため)。
 *
 *   素材:      loadSample(kind) か inBegin(channels, length) + inSet(ch, i, v)
 *   応答:      makeIr(kind, t60) か irBegin(channels, length) + irSet(ch, i, v)
 *   変換:      process(wet) → outAt(ch, i)
 *   表示用:    envelope(which, ch, cols) → envAt(i)、spectrum(points) → specAt(i)
 */
object AudioExports {
  import Dsp.SR

  final val MaxInputSec = 20
  final val MaxIrSec = 8

  private var input: Array[Array[Float]] = Array(new Array[Float](1))
  private var ir: Array[Array[Float]] = Array(new Array[Float](1))
  private var output: Array[Array[Float]] = Array(new Array[Float](1), new Array[Float](1))

  @WasmExport("sampleRate") def sampleRate(): Int = SR

  // ---- 素材
  /** 0: 撥弦のフレーズ, 1: 手拍子。戻り値はサンプル数 */
  @WasmExport("loadSample")
  def loadSample(kind: Int): Int = {
    val x = if (kind == 0) Dsp.pluckPhrase() else Dsp.claps()
    // ピークを 0.7 にそろえる
    var peak = 0.0
    x.foreach(v => peak = Math.max(peak, Math.abs(v.toDouble)))
    if (peak > 0) { val s = 0.7 / peak; var i = 0; while (i < x.length) { x(i) = (x(i) * s).toFloat; i += 1 } }
    input = Array(x)
    input(0).length
  }

  /** 長さが上限を超えるぶんは切り捨てる。戻り値は実際に確保した長さ */
  @WasmExport("inBegin")
  def inBegin(channels: Int, length: Int): Int = {
    val n = Math.max(1, Math.min(length, MaxInputSec * SR))
    input = Array.fill(Math.max(1, Math.min(2, channels)))(new Array[Float](n))
    n
  }
  @WasmExport("inSet") def inSet(ch: Int, i: Int, v: Double): Unit = input(ch)(i) = v.toFloat
  @WasmExport("inLen") def inLen(): Int = input(0).length
  @WasmExport("inChannels") def inChannels(): Int = input.length
  @WasmExport("inAt") def inAt(ch: Int, i: Int): Double = input(Math.min(ch, input.length - 1))(i)

  // ---- インパルス応答
  /** 0: 小部屋, 1: ホール, 2: 大聖堂, 3: やまびこ, 4: 電話, 5: 金属の筒 */
  @WasmExport("makeIr")
  def makeIr(kind: Int, t60: Double): Int = {
    ir = Dsp.preset(kind, t60)
    ir(0).length
  }

  @WasmExport("irBegin")
  def irBegin(channels: Int, length: Int): Int = {
    val n = Math.max(1, Math.min(length, MaxIrSec * SR))
    ir = Array.fill(Math.max(1, Math.min(2, channels)))(new Array[Float](n))
    n
  }
  @WasmExport("irSet") def irSet(ch: Int, i: Int, v: Double): Unit = ir(ch)(i) = v.toFloat
  @WasmExport("irLen") def irLen(): Int = ir(0).length
  @WasmExport("irChannels") def irChannels(): Int = ir.length
  @WasmExport("irAt") def irAt(ch: Int, i: Int): Double = ir(Math.min(ch, ir.length - 1))(i)

  // ---- 畳み込み
  private var convolveMs = 0.0

  /**
   * 左右それぞれ「素材 × 応答」を畳み込み、原音と混ぜる。
   * 響きの音量は原音と同じ RMS にそろえてから wet の割合で混ぜ、最後にピークが 0.98 を超えないよう縮める。
   * 戻り値は出力のサンプル数 (素材の長さ + 応答の長さ - 1)。
   */
  @WasmExport("process")
  def process(wet: Double): Int = {
    val wetSig = Array.tabulate(2) { ch =>
      Fft.convolve(input(Math.min(ch, input.length - 1)), ir(Math.min(ch, ir.length - 1)))
    }
    val outLen = wetSig(0).length
    def rms(xs: Array[Array[Float]], limit: Int): Double = {
      var s = 0.0
      var n = 0
      for (x <- xs) { var i = 0; while (i < Math.min(limit, x.length)) { s += x(i).toDouble * x(i); i += 1 }; n += Math.min(limit, x.length) }
      Math.sqrt(s / Math.max(1, n))
    }
    val dryRms = rms(input, Int.MaxValue)
    val wetRms = rms(wetSig, Int.MaxValue)
    val wetGain = if (wetRms > 1e-12) dryRms / wetRms else 0.0
    val w = Math.max(0.0, Math.min(1.0, wet))
    output = Array.tabulate(2) { ch =>
      val dry = input(Math.min(ch, input.length - 1))
      val ws = wetSig(ch)
      val o = new Array[Float](outLen)
      var i = 0
      while (i < outLen) {
        val d = if (i < dry.length) dry(i) else 0f
        o(i) = ((1 - w) * d + w * wetGain * ws(i)).toFloat
        i += 1
      }
      o
    }
    var peak = 0.0
    for (o <- output; v <- o) peak = Math.max(peak, Math.abs(v.toDouble))
    if (peak > 0.98) {
      val s = 0.98 / peak
      for (o <- output) { var i = 0; while (i < o.length) { o(i) = (o(i) * s).toFloat; i += 1 } }
    }
    outLen
  }

  @WasmExport("fftSize") def fftSize(): Int = Fft.lastSize
  @WasmExport("outLen") def outLen(): Int = output(0).length
  @WasmExport("outAt") def outAt(ch: Int, i: Int): Double = output(ch)(i)

  // ---- 表示用
  private var env = new Array[Double](2)

  /** which: 0 素材, 1 応答, 2 出力。列ごとの (最小, 最大) を envAt(2c), envAt(2c+1) に入れる */
  @WasmExport("envelope")
  def envelope(which: Int, ch: Int, cols: Int): Unit = {
    val src = which match {
      case 0 => input
      case 1 => ir
      case _ => output
    }
    val x = src(Math.min(ch, src.length - 1))
    env = new Array[Double](cols * 2)
    var c = 0
    while (c < cols) {
      val a = (c.toLong * x.length / cols).toInt
      val b = Math.max(a + 1, ((c + 1).toLong * x.length / cols).toInt)
      var lo = 0.0
      var hi = 0.0
      var i = a
      while (i < b && i < x.length) { val v = x(i).toDouble; if (v < lo) lo = v; if (v > hi) hi = v; i += 1 }
      env(2 * c) = lo
      env(2 * c + 1) = hi
      c += 1
    }
  }
  @WasmExport("envAt") def envAt(i: Int): Double = env(i)

  private var spec = new Array[Double](1)

  /**
   * 応答 (左チャンネル) の周波数特性。20 Hz〜20 kHz を対数で points 点に分け、
   * 各帯域のパワーの平均を dB にする (最大を 0 dB にそろえる)
   */
  @WasmExport("spectrum")
  def spectrum(points: Int): Unit = {
    val x = ir(0)
    val n = Fft.nextPow2(Math.max(x.length, 8192))
    val re = new Array[Double](n)
    val im = new Array[Double](n)
    var i = 0
    while (i < x.length) { re(i) = x(i); i += 1 }
    Fft.transform(re, im, n, inverse = false)
    def binOf(f: Double): Int = Math.max(1, Math.min(n / 2, (f / SR * n).toInt))
    def freq(p: Double): Double = 20.0 * MathX.pow(1000.0, p / (points - 1))
    spec = new Array[Double](points)
    var maxDb = -1e9
    var p = 0
    while (p < points) {
      val lo = binOf(freq(p - 0.5))
      val hi = Math.max(lo + 1, binOf(freq(p + 0.5)))
      var s = 0.0
      var k = lo
      while (k < hi) { s += re(k) * re(k) + im(k) * im(k); k += 1 }
      val db = 10 * MathX.log(s / (hi - lo) + 1e-30) / MathX.log(10)
      spec(p) = db
      if (db > maxDb) maxDb = db
      p += 1
    }
    p = 0
    while (p < points) { spec(p) -= maxDb; p += 1 }
  }
  @WasmExport("specAt") def specAt(i: Int): Double = spec(i)
}
