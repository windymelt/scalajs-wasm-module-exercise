package core

enum Expr:
  case Num(n: Long)
  case Add(a: Expr, b: Expr)
  case Mul(a: Expr, b: Expr)
  case Div(a: Expr, b: Expr)

object Calc:
  import Expr.*

  extension (e: Expr)
    def eval: Long = e match
      case Num(n)    => n
      case Add(a, b) => a.eval + b.eval
      case Mul(a, b) => a.eval * b.eval
      case Div(a, b) => a.eval / b.eval

    def show: String = e match
      case Num(n)    => n.toString
      case Add(a, b) => s"(${a.show} + ${b.show})"
      case Mul(a, b) => s"${a.show} * ${b.show}"
      case Div(a, b) => s"${a.show} / ${b.show}"

  /** Scala 3 側の公開 API（Scala 2 から呼びやすい素朴なシグネチャにしておく） */
  def report(): List[String] =
    List(
      Add(Num(1), Mul(Num(2), Num(3))),
      Div(Mul(Num(6), Num(7)), Num(2)),
      Div(Num(1), Add(Num(1), Num(-1))),
    ).map { e =>
      val r =
        try e.eval.toString
        catch case ex: ArithmeticException => s"<${ex.getClass.getSimpleName}: ${ex.getMessage}>"
      s"${e.show} = $r"
    }

  def fib(n: Int): Int = if n < 2 then n else fib(n - 1) + fib(n - 2)
