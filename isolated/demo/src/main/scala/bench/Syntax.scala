package bench

import wasmisolated.*

/** Scala 3 の書きかたがブロックの中で使えるか (sbt "demo/runMain bench.syntax") */
def shapes(scale: Double): Double =
  wasmIsolated:
    enum Shape:
      case Circle(r: Double)
      case Rect(w: Double, h: Double)
    import Shape.*

    extension (s: Shape)
      def area: Double = s match
        case Circle(r) => 3.0 * r * r
        case Rect(w, h) => w * h

    val all = List(Circle(scale), Rect(scale, 2 * scale))
    all.map(_.area).sum

def collatz(start: Long): Int =
  wasmIsolated:
    @annotation.tailrec
    def go(n: Long, steps: Int): Int =
      if n == 1 then steps
      else if n % 2 == 0 then go(n / 2, steps + 1)
      else go(3 * n + 1, steps + 1)
    go(start, 0)

@main def syntax(): Unit =
  println(s"enum と extension: ${shapes(2.0)}")
  println(s"collatz(27) の手数: ${collatz(27)}")
