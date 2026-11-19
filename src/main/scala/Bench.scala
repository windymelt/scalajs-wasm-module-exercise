package img

import scala.scalajs.wasm.annotation._

object Bench {
  /** 確保なし: Double の演算だけ */
  @WasmExport("benchArith")
  def benchArith(n: Int): Double = {
    var x, y, z = 0.0
    var i = 0
    while (i < n) { x = x * 0.999 + 1.0; y = y * 0.998 + x; z += Math.sqrt(x * y); i += 1 }
    z
  }

  /** 短命オブジェクトを大量確保: V を毎回 new する */
  @WasmExport("benchAlloc")
  def benchAlloc(n: Int): Double = {
    var acc = V.Zero
    var i = 0
    while (i < n) { acc = acc * 0.999 + V(1.0, 2.0, 3.0); i += 1 }
    acc.x + acc.y + acc.z
  }

  private val arr = new Array[Double](4096)

  /** 確保なし・GC 配列の読み書きだけ */
  @WasmExport("benchArray")
  def benchArray(n: Int): Double = {
    val a = arr
    var i = 0
    while (i < n) { val k = i & 4095; a(k) = a(k) * 0.5 + a((k + 1) & 4095) + 1.0; i += 1 }
    a(0)
  }
}
