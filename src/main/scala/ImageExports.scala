package img

import scala.scalajs.wasm.annotation._

/** 画像は Scala 側で PNG まで作り、ホストは 4 バイトずつ吸い出す。
 *  (配列は境界でコピーされ、JS からは中身も読めないため)
 */
object ImageExports {
  private var png: Array[Byte] = Array.emptyByteArray

  @WasmExport("rayTrace")
  def rayTrace(w: Int, h: Int, spp: Int, seed: Int): Int = {
    png = Png.encode(w, h, RayTracer.render(w, h, spp, seed))
    png.length
  }

  @WasmExport("julia")
  def julia(w: Int, h: Int, cr: Double, ci: Double): Int = {
    png = Png.encode(w, h, Julia.render(w, h, cr, ci))
    png.length
  }

  /** i 番目の 4 バイトをリトルエンディアンで返す */
  @WasmExport("pngWord")
  def pngWord(i: Int): Int = {
    def at(j: Int): Int = if (j < png.length) png(j) & 0xff else 0
    val j = i * 4
    at(j) | (at(j + 1) << 8) | (at(j + 2) << 16) | (at(j + 3) << 24)
  }

  // 自前数学関数の精度チェック用
  @WasmExport("mx_cos") def mxCos(x: Double): Double = MathX.cos(x)
  @WasmExport("mx_sin") def mxSin(x: Double): Double = MathX.sin(x)
  @WasmExport("mx_exp") def mxExp(x: Double): Double = MathX.exp(x)
  @WasmExport("mx_log") def mxLog(x: Double): Double = MathX.log(x)
  @WasmExport("mx_pow") def mxPow(x: Double, y: Double): Double = MathX.pow(x, y)
}
