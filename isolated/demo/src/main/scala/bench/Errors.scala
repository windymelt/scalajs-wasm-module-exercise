package bench

import scala.compiletime.testing.typeCheckErrors
import wasmisolated.*

def helper(x: Int): Int = x + 1

/** マクロがコンパイル時に出すエラーを表示する (sbt "demo/runMain bench.errors") */
@main def errors(): Unit =
  val name = "めるくん"
  val xs = List(1, 2, 3)
  var total = 0
  val buf = Array(1, 2, 3)
  val words = Array("a", "b")
  val cases = Seq(
    "外の文字列" -> typeCheckErrors("wasmIsolated { name.length }"),
    "外のコレクション" -> typeCheckErrors("wasmIsolated { xs.sum }"),
    "外のメソッド" -> typeCheckErrors("wasmIsolated { helper(1) }"),
    "外の変数への代入" -> typeCheckErrors("wasmIsolated { total = 1; total }"),
    "外の配列の書き換え" -> typeCheckErrors("wasmIsolated { buf(0) = 9; buf(0) }"),
    "外の文字列の配列" -> typeCheckErrors("wasmIsolated { words.length }"),
    "this" -> typeCheckErrors("class A { def f(): Int = wasmIsolated { this.hashCode() } }"),
    "戻り値が文字列" -> typeCheckErrors("wasmIsolated { \"abc\" }"),
  )
  for (label, errs) <- cases do
    println(s"$label: ${errs.map(_.message.linesIterator.next()).mkString(" / ")}")
