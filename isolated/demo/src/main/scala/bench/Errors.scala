package bench

import scala.compiletime.testing.typeCheckErrors
import wasmisolated.*

def helper(x: Int): Int = x + 1

/** borer の Encoder も Decoder もない型 */
final class Secret(val value: Int)

/** マクロがコンパイル時に出すエラーを表示する (sbt "demo/runMain bench.errors") */
@main def errors(): Unit =
  val rnd = new java.util.Random(1)
  val secret = Secret(42)
  var total = 0
  val buf = Array(1, 2, 3)
  val cases = Seq(
    "外の Java のオブジェクト" -> typeCheckErrors("wasmIsolated { rnd.nextInt() }"),
    "コーデックのない型" -> typeCheckErrors("wasmIsolated { secret.value }"),
    "外のメソッド" -> typeCheckErrors("wasmIsolated { helper(1) }"),
    "外の変数への代入" -> typeCheckErrors("wasmIsolated { total = 1; total }"),
    "外の配列の書き換え" -> typeCheckErrors("wasmIsolated { buf(0) = 9; buf(0) }"),
    "this" -> typeCheckErrors("class A { def f(): Int = wasmIsolated { this.hashCode() } }"),
    "戻り値がコーデックのない型" -> typeCheckErrors("wasmIsolated { new java.util.Random(1) }"),
  )
  for (label, errs) <- cases do
    println(s"$label: ${errs.map(_.message.linesIterator.next()).mkString(" / ")}")
