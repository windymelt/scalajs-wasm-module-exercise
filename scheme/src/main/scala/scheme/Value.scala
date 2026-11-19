package scheme

sealed trait Value
final case class SInt(v: BigInt) extends Value
final case class SReal(v: Double) extends Value
final case class SBool(v: Boolean) extends Value
final case class SStr(v: String) extends Value
final case class SSym(name: String) extends Value
final case class SPair(car: Value, cdr: Value) extends Value
case object SNil extends Value
case object SUnspec extends Value

/** params: 必須引数, rest: 残りを受け取る引数 (lambda (a . rest) ...) */
final class Closure(val params: List[String], val rest: Option[String], val body: Value, val env: Env, var name: String)
    extends Value

final class Prim(val name: String, val minArgs: Int, val maxArgs: Int, val f: List[Value] => Value) extends Value

final class SchemeError(msg: String) extends RuntimeException(msg)

/** 入力の途中で終わった (閉じ括弧が足りない) */
final class Incomplete extends RuntimeException("incomplete")

object Value {
  val True: Value = SBool(true)
  val False: Value = SBool(false)

  def err(msg: String): Nothing = throw new SchemeError(msg)

  def truthy(v: Value): Boolean = v match {
    case SBool(false) => false
    case _            => true
  }

  def list(xs: Value*): Value = fromList(xs.toList)
  def fromList(xs: List[Value], tail: Value = SNil): Value = xs.foldRight(tail)(SPair(_, _))

  def toList(v: Value, what: String = "引数"): List[Value] = {
    val b = List.newBuilder[Value]
    var c = v
    while (c.isInstanceOf[SPair]) { val p = c.asInstanceOf[SPair]; b += p.car; c = p.cdr }
    if (c != SNil) err(s"${what}が真のリストではありません: ${Printer.show(v)}")
    b.result()
  }

  def symName(v: Value, what: String): String = v match {
    case SSym(n) => n
    case other   => err(s"${what}にはシンボルが必要です: ${Printer.show(other)}")
  }
}

object Printer {
  def show(v: Value, write: Boolean = true): String = {
    val sb = new java.lang.StringBuilder
    go(v, write, sb)
    sb.toString
  }

  /** Scala.js では 1.0.toString が "1" になる (JS の数値の文字列化に合わせている) ので、Scheme 流に .0 を補う */
  def real(d: Double): String =
    if (d.isNaN) "+nan.0"
    else if (d.isInfinite) (if (d > 0) "+inf.0" else "-inf.0")
    else {
      val s = d.toString
      if (s.exists(c => c == '.' || c == 'e' || c == 'E' || c == 'n' || c == 'I')) s else s + ".0"
    }

  private def escape(s: String, sb: java.lang.StringBuilder): Unit = {
    sb.append('"')
    s.foreach {
      case '"'  => sb.append("\\\"")
      case '\\' => sb.append("\\\\")
      case '\n' => sb.append("\\n")
      case '\t' => sb.append("\\t")
      case c    => sb.append(c)
    }
    sb.append('"')
  }

  private def go(v: Value, write: Boolean, sb: java.lang.StringBuilder): Unit = v match {
    case SInt(n)     => sb.append(n.toString)
    case SReal(d)    => sb.append(real(d))
    case SBool(b)    => sb.append(if (b) "#t" else "#f")
    case SStr(s)     => if (write) escape(s, sb) else sb.append(s)
    case SSym(n)     => sb.append(n)
    case SNil        => sb.append("()")
    case SUnspec     => sb.append("#<unspecified>")
    case c: Closure  => sb.append("#<procedure ").append(c.name).append('>')
    case p: Prim     => sb.append("#<primitive ").append(p.name).append('>')
    case SPair(SSym(q), SPair(x, SNil)) if Reader.quoteNames.contains(q) =>
      sb.append(Reader.quoteNames(q)); go(x, write, sb)
    case p: SPair =>
      sb.append('(')
      go(p.car, write, sb)
      var c = p.cdr
      while (c.isInstanceOf[SPair]) {
        val q = c.asInstanceOf[SPair]
        sb.append(' '); go(q.car, write, sb)
        c = q.cdr
      }
      if (c != SNil) { sb.append(" . "); go(c, write, sb) }
      sb.append(')')
  }
}
