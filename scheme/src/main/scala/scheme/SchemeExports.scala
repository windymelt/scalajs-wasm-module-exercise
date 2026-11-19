package scheme

import scala.scalajs.wasm.annotation._

/**
 * ホスト向けの API。String は境界を越えられないので、UTF-16 の符号単位を 1 つずつやり取りする。
 *
 *   inClear(); inPush(c)…; status = run(fuel)
 *   → outputLen()/outputAt(i): display などで出力された文字列
 *   → resultLen()/resultAt(i): 各式の値 (エラー時はメッセージ)
 */
object SchemeExports {
  private var interp = new Interp(42)
  private val input = new java.lang.StringBuilder
  private var output = ""
  private var result = ""

  @WasmExport("reset") def reset(seed: Int): Unit = interp = new Interp(seed)
  @WasmExport("inClear") def inClear(): Unit = input.setLength(0)
  @WasmExport("inPush") def inPush(c: Int): Unit = input.append(c.toChar)

  /** 0: 成功, 1: エラー, 2: 入力が途中 (閉じ括弧が足りない) */
  @WasmExport("run")
  def run(fuel: Int): Int = {
    val (values, status) = interp.run(input.toString, fuel.toLong)
    output = interp.out.toString
    result = values.mkString("\n")
    status
  }

  @WasmExport("outputLen") def outputLen(): Int = output.length
  @WasmExport("outputAt") def outputAt(i: Int): Int = output.charAt(i).toInt
  @WasmExport("resultLen") def resultLen(): Int = result.length
  @WasmExport("resultAt") def resultAt(i: Int): Int = result.charAt(i).toInt
  /** 直前の run で使った評価ステップ数 */
  @WasmExport("steps") def steps(): Double = interp.steps.toDouble
}
