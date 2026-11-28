package bench

import scala.concurrent.duration.*
import wasmisolated.*

/** 配列の受け渡しを確かめる (sbt "demo/runMain bench.arrays") */
def revBytes(xs: Array[Byte]): Array[Byte] = wasmIsolated { xs.reverse }
def revShorts(xs: Array[Short]): Array[Short] = wasmIsolated { xs.reverse }
def revInts(xs: Array[Int]): Array[Int] = wasmIsolated { xs.reverse }
def revLongs(xs: Array[Long]): Array[Long] = wasmIsolated { xs.reverse }
def revFloats(xs: Array[Float]): Array[Float] = wasmIsolated { xs.reverse }
def revDoubles(xs: Array[Double]): Array[Double] = wasmIsolated { xs.reverse }

/** 配列を 2 つ持ち込み、スカラーを返す */
def dot(a: Array[Double], b: Array[Double]): Double =
  wasmIsolated:
    var s = 0.0
    var i = 0
    while i < a.length do
      s += a(i) * b(i)
      i += 1
    s

/** Wasm の中で作った配列を返すだけ */
def squares(n: Int): Array[Int] = wasmIsolated { Array.tabulate(n)(i => i * i) }

/** 持ち込むだけ */
def sumInts(xs: Array[Int]): Long = wasmIsolated { xs.foldLeft(0L)(_ + _) }

@main def arrays(): Unit =
  given Limits = Limits(timeout = 500.millis, maxHeap = "256MB")
  def check(label: String, ok: Boolean): Unit = println(s"$label: ${if ok then "一致" else "不一致"}")
  check("Array[Byte]", revBytes(Array[Byte](-128, 0, 1, 127)).sameElements(Array[Byte](127, 1, 0, -128)))
  check("Array[Short]", revShorts(Array[Short](-32768, 0, 32767)).sameElements(Array[Short](32767, 0, -32768)))
  check("Array[Int]", revInts(Array(Int.MinValue, 0, Int.MaxValue)).sameElements(Array(Int.MaxValue, 0, Int.MinValue)))
  check("Array[Long]", revLongs(Array(Long.MinValue, 0L, Long.MaxValue)).sameElements(Array(Long.MaxValue, 0L, Long.MinValue)))
  check("Array[Float]", revFloats(Array(-1.5f, 0f, Float.MaxValue)).sameElements(Array(Float.MaxValue, 0f, -1.5f)))
  check("Array[Double]", revDoubles(Array(-1.5, Double.MinPositiveValue, 1e300)).sameElements(Array(1e300, Double.MinPositiveValue, -1.5)))
  check("空の配列", revInts(Array.empty[Int]).isEmpty)
  println(s"内積 (配列 2 つ): ${dot(Array(1.0, 2.0, 3.0), Array(4.0, 5.0, 6.0))}")
  val src = Array(1, 2, 3)
  val back = revInts(src)
  println(s"渡した配列は変わらない: ${src.mkString(",")} → ${back.mkString(",")}")

  for n <- Seq(1_000, 100_000, 1_000_000); round <- 1 to 3 do
    val xs = Array.tabulate(n)(identity)
    var t = System.nanoTime(); val s = sumInts(xs); val tin = (System.nanoTime() - t) / 1e6
    t = System.nanoTime(); val ys = squares(n); val tout = (System.nanoTime() - t) / 1e6
    t = System.nanoTime(); val zs = revInts(xs); val tboth = (System.nanoTime() - t) / 1e6
    println(f"n=$n%,d 第 $round 回: 持ち込む ${tin}%.1f ms / 受け取る ${tout}%.1f ms / 往復 ${tboth}%.1f ms (検算 ${s == n.toLong * (n - 1) / 2 && ys(n - 1) == (n - 1) * (n - 1) && zs(0) == n - 1})")
