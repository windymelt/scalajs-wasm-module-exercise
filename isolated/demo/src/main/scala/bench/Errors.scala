package bench

import scala.compiletime.testing.typeCheckErrors
import wasmisolated.*

def helper(x: Int): Int = x + 1

/** マクロがコンパイル時に出すエラーを表示する (sbt "demo/runMain bench.errors") */
@main def errors(): Unit =
  val name = "めるくん"
  val xs = List(1, 2, 3)
  var total = 0
  val cases = Seq(
    "外の文字列" -> typeCheckErrors("wasmIsolated { name.length }"),
    "外のコレクション" -> typeCheckErrors("wasmIsolated { xs.sum }"),
    "外のメソッド" -> typeCheckErrors("wasmIsolated { helper(1) }"),
    "外の変数への代入" -> typeCheckErrors("wasmIsolated { total = 1; total }"),
    "this" -> typeCheckErrors("class A { def f(): Int = wasmIsolated { this.hashCode() } }"),
    "戻り値が文字列" -> typeCheckErrors("wasmIsolated { \"abc\" }"),
  )
  for (label, errs) <- cases do
    println(s"$label: ${errs.map(_.message.linesIterator.next()).mkString(" / ")}")
