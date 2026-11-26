import scala.concurrent.duration.*
import wasmisolated.*

/** 1. ブロックだけを Wasm にして動かす。外の n は値として持ち込まれる */
def fibonacci(n: Long): Long =
  wasmIsolated:
    def fib(k: Long): Long = if k < 2 then k else fib(k - 1) + fib(k - 2)
    fib(n)

// 2 と 3 の上限は、文脈の Limits で決める
given Limits = Limits(timeout = 500.millis, maxHeap = "32MB")

/** 2. 終わらない計算 */
def spin(): Long =
  wasmIsolated:
    var i = 0L
    while i >= 0 do i = (i + 1) % 1000
    i

/** 3. メモリを使い果たす計算 */
def hog(): Int =
  wasmIsolated:
    var keep = List.empty[Array[Long]]
    while true do keep = Array.ofDim[Long](1 << 16) :: keep
    keep.length

/** 4. 深すぎる再帰 */
def deep(): Long =
  wasmIsolated:
    def down(k: Long): Long = if k == 0 then 0 else 1 + down(k - 1)
    down(100_000_000L)

def attempt[A](label: String)(body: => A): Unit =
  try println(s"$label: ${body}")
  catch case e: IsolationFailure => println(s"$label: ${e.getMessage}")

@main def demo(): Unit =
  attempt("fib(32)")(fibonacci(32))
  attempt("終わらない計算")(spin())
  attempt("メモリを使い果たす計算")(hog())
  attempt("深すぎる再帰")(deep())
  // 5. 止めたあとも、JVM もほかのブロックも普通に動き続ける
  attempt("そのあとの fib(20)")(fibonacci(20))
