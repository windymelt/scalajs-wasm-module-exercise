package bench

import scala.concurrent.duration.*
import wasmisolated.*

/** 記事用の計測 (sbt "demo/runMain bench.measure") */
def fibJvm(k: Long): Long = if k < 2 then k else fibJvm(k - 1) + fibJvm(k - 2)

def fibWasm(n: Long): Long =
  wasmIsolated {
    def fib(k: Long): Long = if (k < 2) k else fib(k - 1) + fib(k - 2)
    fib(n)
  }

def add(a: Int, b: Int): Int = wasmIsolated { a + b }

def spinFor(limit: FiniteDuration): Long =
  given Limits = Limits(timeout = limit)
  wasmIsolated {
    var i = 0L
    while (i >= 0) i = (i + 1) % 1000
    i
  }

def ms(t0: Long): String = f"${(System.nanoTime() - t0) / 1e6}%.1f ms"

@main def measure(): Unit =
  var t = System.nanoTime(); add(1, 2); println(s"初回の呼び出し (別ヒープとインスタンスを作る): ${ms(t)}")
  t = System.nanoTime(); add(1, 2); println(s"2 回目の呼び出し: ${ms(t)}")
  t = System.nanoTime(); var i = 0; while i < 10000 do { add(i, 1); i += 1 }
  println(f"小さな呼び出し 1 回あたり: ${(System.nanoTime() - t) / 1e3 / 10000}%.2f µs")

  for round <- 1 to 5 do
    t = System.nanoTime(); val a = fibJvm(35); val tj = ms(t)
    t = System.nanoTime(); val b = fibWasm(35); val tw = ms(t)
    println(s"fib(35) 第 $round 回: JVM $tj / wasmIsolated $tw (値 $a, $b)")

  for limit <- Seq(100.millis, 500.millis, 1.second) do
    t = System.nanoTime()
    try spinFor(limit) catch case _: TimedOut => ()
    println(s"上限 $limit の暴走が止まるまで: ${ms(t)}")
