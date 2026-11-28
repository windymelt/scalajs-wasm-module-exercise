package bench

import scala.concurrent.duration.*
import wasmisolated.*
import shop.*

/** CBOR (borer) での受け渡しを確かめる (sbt "demo/runMain bench.cbor") */
def totalJvm(order: Order): Int = order.items.map(i => i.unitPrice * i.quantity).sum

def totalWasm(order: Order): Int =
  wasmIsolated:
    order.items.map(i => i.unitPrice * i.quantity).sum

def echo(order: Order): Order = wasmIsolated { order }

def shout(s: String): String = wasmIsolated { s.map(c => if c >= 'a' && c <= 'z' then (c - 32).toChar else c) + "!" }
def sumList(xs: List[Int]): Long = wasmIsolated { xs.foldLeft(0L)(_ + _) }
def wordCount(text: String): Map[String, Int] =
  wasmIsolated:
    // String.split は正規表現を使うので Wasm にリンクできない。空白で区切るのは自分で書く
    val words = text.foldLeft(List("")) { (acc, c) => if c == ' ' then "" :: acc else (acc.head + c) :: acc.tail }
    words.filter(_.nonEmpty).groupBy(identity).map((w, ws) => w -> ws.size)

@main def cbor(): Unit =
  given Limits = Limits(timeout = 2.seconds, maxHeap = "256MB")
  println(s"String: ${shout("scala wasm")}")
  println(s"List[Int]: ${sumList(List(1, 2, 3, 4))}")
  println(s"Map[String, Int]: ${wordCount("sushi beer sushi")}")
  val o = Order("めるくん", List(Item("寿司", 1800, 2)), None)
  println(s"case class の往復: ${echo(o) == o}")

  for n <- Seq(10, 1_000, 10_000); round <- 1 to 3 do
    val order = Order("めるくん", List.tabulate(n)(i => Item(s"商品$i", 100 + i % 50, 1 + i % 3)), Some("BEER10"))
    val bytes = CborBridge.toBytes(order).length
    var t = System.nanoTime(); val a = totalJvm(order); val tj = (System.nanoTime() - t) / 1e6
    t = System.nanoTime(); val b = totalWasm(order); val tw = (System.nanoTime() - t) / 1e6
    t = System.nanoTime(); val e = echo(order); val te = (System.nanoTime() - t) / 1e6
    println(f"商品 $n%,d 件 (CBOR $bytes%,d バイト) 第 $round 回: JVM $tj%.2f ms / 持ち込んで合計 $tw%.1f ms / 往復 $te%.1f ms (一致 ${a == b && e == order})")
