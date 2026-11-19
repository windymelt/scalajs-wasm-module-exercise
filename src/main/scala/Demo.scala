import java.nio.charset.StandardCharsets.UTF_8

import scala.scalajs.wasm
import scala.scalajs.wasm.annotation._

/** ホストから提供される唯一の出力手段 */
object Out {
  @WasmImport("host", "putChar")
  def putChar(c: Int): Unit = wasm.native

  def print(s: String): Unit = s.foreach(c => putChar(c.toInt))
  def println(s: String): Unit = print(s + "\n")
}

/** 1. マンデルブロ集合 */
object Mandelbrot {
  private val palette = " .:-=+*#%@"

  @WasmExport("mandelbrot")
  def render(w: Int, h: Int, maxIter: Int): Unit = {
    for (y <- 0 until h) {
      val sb = new StringBuilder
      for (x <- 0 until w) {
        val cr = -2.2 + 3.0 * x / w
        val ci = -1.2 + 2.4 * y / h
        var zr, zi = 0.0
        var i = 0
        while (i < maxIter && zr * zr + zi * zi < 4.0) {
          val t = zr * zr - zi * zi + cr
          zi = 2 * zr * zi + ci
          zr = t
          i += 1
        }
        sb += (if (i == maxIter) '@' else palette(i * (palette.length - 1) / maxIter))
      }
      Out.println(sb.toString)
    }
  }
}

/** 2. sealed trait + パターンマッチ + 例外 */
object Calc {
  sealed trait Expr
  final case class Num(n: Long) extends Expr
  final case class Add(a: Expr, b: Expr) extends Expr
  final case class Mul(a: Expr, b: Expr) extends Expr
  final case class Div(a: Expr, b: Expr) extends Expr

  def eval(e: Expr): Long = e match {
    case Num(n)    => n
    case Add(a, b) => eval(a) + eval(b)
    case Mul(a, b) => eval(a) * eval(b)
    case Div(a, b) => eval(a) / eval(b)
  }

  def show(e: Expr): String = e match {
    case Num(n)    => n.toString
    case Add(a, b) => s"(${show(a)} + ${show(b)})"
    case Mul(a, b) => s"${show(a)} * ${show(b)}"
    case Div(a, b) => s"${show(a)} / ${show(b)}"
  }

  @WasmExport("calcDemo")
  def demo(): Unit = {
    val exprs = List(
      Add(Num(1), Mul(Num(2), Num(3))),
      Div(Mul(Num(6), Num(7)), Num(2)),
      Div(Num(1), Add(Num(1), Num(-1))),
    )
    exprs.foreach { e =>
      val result =
        try eval(e).toString
        catch { case ex: ArithmeticException => s"<${ex.getClass.getSimpleName}: ${ex.getMessage}>" }
      Out.println(s"${show(e)} = $result")
    }
  }
}

/** 3. String の受け渡し
 *
 *  配列は境界で必ずコピーされる（参照渡しされない）ので、
 *  バッファは Scala 側に持ち、境界はスカラー値だけでやり取りする。
 */
object Text {
  private val in = new java.io.ByteArrayOutputStream
  private var out: Array[Byte] = Array.emptyByteArray

  @WasmExport("inClear") def inClear(): Unit = in.reset()
  @WasmExport("inPush")  def inPush(b: Int): Unit = in.write(b)
  @WasmExport("outLen")  def outLen(): Int = out.length
  @WasmExport("outAt")   def outAt(i: Int): Int = out(i) & 0xff

  /** 一括で受け取るならこちら（1回コピーされるだけなので問題ない） */
  @WasmExport("decorateArray")
  def decorateArray(input: Array[Byte]): Int = { out = decorate(input); out.length }

  @WasmExport("decorateBuffered")
  def decorateBuffered(): Int = { out = decorate(in.toByteArray); out.length }

  private def decorate(input: Array[Byte]): Array[Byte] = {
    val s = new String(input, UTF_8)
    val cps = Iterator.unfold(0)(i => Option.when(i < s.length)((s.codePointAt(i), i + Character.charCount(s.codePointAt(i))))).toArray
    val reversed = new String(cps.reverse, 0, cps.length)
    val out =
      s"""input     : $s
         |chars     : ${s.length} UTF-16 units, ${cps.length} code points, ${input.length} bytes
         |reversed  : $reversed
         |freq > 1  : ${s.toSeq.groupBy(identity).view.mapValues(_.size).filter(_._2 > 1).toList.sortBy(-_._2).mkString(", ")}""".stripMargin
    out.getBytes(UTF_8)
  }
}
