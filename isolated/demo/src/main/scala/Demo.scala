import scala.concurrent.duration.*
import wasmisolated.*
import shop.*

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

/** 5. 配列を持ち込み、配列を返す */
def evens(xs: Array[Int]): Array[Int] =
  wasmIsolated:
    xs.filter(_ % 2 == 0).map(_ * 10)

/** 6. case class を持ち込み、case class を返す (borer で CBOR のバイト列にして渡す。型は shared/ にある) */
def checkout(order: Order): Receipt =
  wasmIsolated:
    import shop.Receipt // ブロックの中は呼び出し側の import を引き継がない
    val lines = order.items.map(i => s"${i.name} x${i.quantity} = ${i.unitPrice * i.quantity}")
    val subtotal = order.items.map(i => i.unitPrice * i.quantity).sum
    val discount = if order.coupon.contains("BEER10") then subtotal / 10 else 0
    Receipt(lines, subtotal - discount, discount)

def attempt[A](label: String)(body: => A): Unit =
  try println(s"$label: ${body}")
  catch case e: IsolationFailure => println(s"$label: ${e.getMessage}")

@main def demo(): Unit =
  attempt("fib(32)")(fibonacci(32))
  attempt("終わらない計算")(spin())
  attempt("メモリを使い果たす計算")(hog())
  attempt("深すぎる再帰")(deep())
  attempt("配列の受け渡し")(evens(Array(1, 2, 3, 4, 5, 6)).mkString("[", ", ", "]"))
  attempt("case class の受け渡し")(checkout(Order("めるくん", List(Item("寿司", 1800, 2), Item("ビール", 600, 3)), Some("BEER10"))))
  // 7. 止めたあとも、JVM もほかのブロックも普通に動き続ける
  attempt("そのあとの fib(20)")(fibonacci(20))
