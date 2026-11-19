package scheme

import img.MathX

import Value._

object Builtins {
  private def toD(v: Value): Double = v match {
    case SInt(n)  => n.toDouble
    case SReal(d) => d
    case other    => err(s"数ではありません: ${Printer.show(other)}")
  }

  private def toInt(v: Value, who: String): BigInt = v match {
    case SInt(n)                           => n
    case SReal(d) if d == Math.floor(d) && !d.isInfinite => BigInt(d.toLong)
    case other                             => err(s"$who には整数が必要です: ${Printer.show(other)}")
  }

  private def add(a: Value, b: Value): Value = (a, b) match {
    case (SInt(x), SInt(y)) => SInt(x + y)
    case _                  => SReal(toD(a) + toD(b))
  }
  private def sub(a: Value, b: Value): Value = (a, b) match {
    case (SInt(x), SInt(y)) => SInt(x - y)
    case _                  => SReal(toD(a) - toD(b))
  }
  private def mul(a: Value, b: Value): Value = (a, b) match {
    case (SInt(x), SInt(y)) => SInt(x * y)
    case _                  => SReal(toD(a) * toD(b))
  }
  /** 有理数は持たないので、割り切れないときは実数にする */
  private def div(a: Value, b: Value): Value = (a, b) match {
    case (SInt(_), SInt(y)) if y == 0           => err("0 で割りました")
    case (SInt(x), SInt(y)) if (x % y) == 0     => SInt(x / y)
    case _                                      => SReal(toD(a) / toD(b))
  }
  private def cmp(a: Value, b: Value): Int = (a, b) match {
    case (SInt(x), SInt(y)) => x.compare(y)
    case _                  => java.lang.Double.compare(toD(a), toD(b))
  }

  /** R7RS の round (偶数丸め)。Math.round は WasmModule でリンクできない */
  private def roundEven(d: Double): Double = {
    val f = Math.floor(d)
    val diff = d - f
    if (diff < 0.5) f else if (diff > 0.5) f + 1 else if (f % 2 == 0) f else f + 1
  }

  def eqv(a: Value, b: Value): Boolean = (a, b) match {
    case (SInt(x), SInt(y))   => x == y
    case (SReal(x), SReal(y)) => x == y
    case (SBool(x), SBool(y)) => x == y
    case (SSym(x), SSym(y))   => x == y
    case (SNil, SNil)         => true
    case _                    => a.asInstanceOf[AnyRef] eq b.asInstanceOf[AnyRef]
  }

  def equal(a: Value, b: Value): Boolean = (a, b) match {
    case (SPair(a1, d1), SPair(a2, d2)) => equal(a1, a2) && equal(d1, d2)
    case (SStr(x), SStr(y))             => x == y
    case _                              => eqv(a, b)
  }

  private def str(v: Value, who: String): String = v match {
    case SStr(s) => s
    case other   => err(s"$who には文字列が必要です: ${Printer.show(other)}")
  }

  def install(in: Interp): Unit = {
    val g = in.global
    def prim(name: String, min: Int, max: Int)(f: List[Value] => Value): Unit = g.define(name, new Prim(name, min, max, f))
    def fixed1(name: String)(f: Value => Value): Unit = prim(name, 1, 1)(xs => f(xs.head))
    def fixed2(name: String)(f: (Value, Value) => Value): Unit = prim(name, 2, 2)(xs => f(xs.head, xs(1)))
    def pred(name: String)(f: Value => Boolean): Unit = fixed1(name)(v => SBool(f(v)))
    def real1(name: String)(f: Double => Double): Unit = fixed1(name)(v => SReal(f(toD(v))))
    val Many = Int.MaxValue

    // ---- 数
    prim("+", 0, Many)(_.foldLeft(SInt(0): Value)(add))
    prim("*", 0, Many)(_.foldLeft(SInt(1): Value)(mul))
    prim("-", 1, Many) { case List(x) => sub(SInt(0), x); case x :: xs => xs.foldLeft(x)(sub) }
    prim("/", 1, Many) { case List(x) => div(SInt(1), x); case x :: xs => xs.foldLeft(x)(div) }
    for ((name, ok) <- List[(String, Int => Boolean)]("=" -> (_ == 0), "<" -> (_ < 0), ">" -> (_ > 0), "<=" -> (_ <= 0), ">=" -> (_ >= 0)))
      prim(name, 1, Many)(xs => SBool(xs.zip(xs.tail).forall { case (a, b) => ok(cmp(a, b)) }))
    def intOp(name: String)(f: (BigInt, BigInt) => BigInt): Unit = fixed2(name) { (a, b) =>
      val y = toInt(b, name)
      if (y == 0) err("0 で割りました")
      SInt(f(toInt(a, name), y))
    }
    intOp("quotient")(_ / _)
    intOp("remainder")(_ % _)
    intOp("modulo")((x, y) => { val r = x % y; if (r != 0 && (r.signum != y.signum)) r + y else r })
    prim("gcd", 0, Many)(xs => SInt(xs.map(toInt(_, "gcd")).foldLeft(BigInt(0))(_ gcd _)))
    fixed1("abs") { case SInt(n) => SInt(n.abs); case v => SReal(Math.abs(toD(v))) }
    prim("min", 1, Many)(xs => xs.reduceLeft((a, b) => if (cmp(a, b) <= 0) a else b))
    prim("max", 1, Many)(xs => xs.reduceLeft((a, b) => if (cmp(a, b) >= 0) a else b))
    fixed2("expt") {
      case (SInt(b), SInt(e)) if e >= 0 =>
        if (e > 100000) err("指数が大きすぎます")
        SInt(b.pow(e.toInt))
      case (b, e) => SReal(MathX.pow(toD(b), toD(e)))
    }
    fixed1("sqrt") {
      case SInt(n) if n >= 0 =>
        val r = BigInt(Math.floor(Math.sqrt(n.toDouble)).toLong)
        val exact = (r - 1 to r + 1).find(c => c >= 0 && c * c == n)
        exact.map(SInt(_): Value).getOrElse(SReal(Math.sqrt(n.toDouble)))
      case v => SReal(Math.sqrt(toD(v)))
    }
    real1("exp")(MathX.exp)
    real1("log")(MathX.log)
    real1("sin")(MathX.sin)
    real1("cos")(MathX.cos)
    real1("tan")(MathX.tan)
    fixed1("floor") { case v: SInt => v; case v => SReal(Math.floor(toD(v))) }
    fixed1("ceiling") { case v: SInt => v; case v => SReal(Math.ceil(toD(v))) }
    fixed1("round") { case v: SInt => v; case v => SReal(roundEven(toD(v))) }
    fixed1("truncate") { case v: SInt => v; case v => val d = toD(v); SReal(if (d < 0) Math.ceil(d) else Math.floor(d)) }
    fixed1("exact->inexact")(v => SReal(toD(v)))
    fixed1("inexact->exact")(v => SInt(toInt(v, "inexact->exact")))
    g.define("exact", g.lookup("inexact->exact"))
    g.define("inexact", g.lookup("exact->inexact"))
    pred("number?") { case _: SInt | _: SReal => true; case _ => false }
    pred("integer?") { case _: SInt => true; case SReal(d) => d == Math.floor(d) && !d.isInfinite; case _ => false }
    pred("zero?")(v => cmp(v, SInt(0)) == 0)
    pred("positive?")(v => cmp(v, SInt(0)) > 0)
    pred("negative?")(v => cmp(v, SInt(0)) < 0)
    pred("even?")(v => toInt(v, "even?") % 2 == 0)
    pred("odd?")(v => toInt(v, "odd?") % 2 != 0)
    fixed1("random") {
      case SInt(n) if n > 0 && n <= Int.MaxValue => SInt(in.rnd.nextInt(n.toInt))
      case SReal(d) if d > 0                     => SReal(in.rnd.nextDouble() * d)
      case v                                     => err(s"random には正の数が必要です: ${Printer.show(v)}")
    }

    // ---- 真偽と同値
    pred("not")(v => !truthy(v))
    pred("boolean?")(_.isInstanceOf[SBool])
    fixed2("eq?")((a, b) => SBool(eqv(a, b)))
    fixed2("eqv?")((a, b) => SBool(eqv(a, b)))
    fixed2("equal?")((a, b) => SBool(equal(a, b)))

    // ---- ペアとリスト
    fixed2("cons")(SPair(_, _))
    fixed1("car") { case SPair(a, _) => a; case v => err(s"car はペアにしか使えません: ${Printer.show(v)}") }
    fixed1("cdr") { case SPair(_, d) => d; case v => err(s"cdr はペアにしか使えません: ${Printer.show(v)}") }
    for (path <- List("aa", "ad", "da", "dd", "add", "ddd", "dddd", "addd")) {
      val name = s"c${path}r"
      fixed1(name) { v =>
        path.reverse.foldLeft(v) {
          case (SPair(a, _), 'a') => a
          case (SPair(_, d), 'd') => d
          case _                  => err(s"$name の対象の形が合いません: ${Printer.show(v)}")
        }
      }
    }
    prim("list", 0, Many)(fromList(_))
    fixed1("length")(v => SInt(toList(v, "length の引数").length))
    prim("append", 0, Many) { xs =>
      if (xs.isEmpty) SNil else xs.init.foldRight(xs.last)((l, acc) => fromList(toList(l, "append の引数"), acc))
    }
    fixed1("reverse")(v => fromList(toList(v, "reverse の引数").reverse))
    fixed2("list-ref")((l, k) => toList(l).lift(toInt(k, "list-ref").toInt).getOrElse(err("list-ref の添字が範囲外です")))
    fixed2("list-tail") { (l, k) =>
      var c = l
      for (_ <- 0 until toInt(k, "list-tail").toInt) c = c match { case SPair(_, d) => d; case _ => err("list-tail の添字が範囲外です") }
      c
    }
    def member(name: String, same: (Value, Value) => Boolean): Unit = fixed2(name) { (x, l) =>
      var c = l
      var found: Value = False
      while (found == False && c.isInstanceOf[SPair]) {
        val p = c.asInstanceOf[SPair]
        if (same(x, p.car)) found = c else c = p.cdr
      }
      found
    }
    member("memq", eqv); member("memv", eqv); member("member", equal)
    def assoc(name: String, same: (Value, Value) => Boolean): Unit = fixed2(name) { (x, l) =>
      toList(l, s"$name の引数").collectFirst { case p @ SPair(k, _) if same(x, k) => p: Value }.getOrElse(False)
    }
    assoc("assq", eqv); assoc("assv", eqv); assoc("assoc", equal)
    pred("null?")(_ == SNil)
    pred("pair?")(_.isInstanceOf[SPair])
    pred("list?") { v => var c = v; while (c.isInstanceOf[SPair]) c = c.asInstanceOf[SPair].cdr; c == SNil }
    pred("symbol?")(_.isInstanceOf[SSym])
    pred("string?")(_.isInstanceOf[SStr])
    pred("procedure?") { case _: Closure | _: Prim => true; case _ => false }
    prim("iota", 1, 3) { xs =>
      val n = toInt(xs.head, "iota").toInt
      val start = xs.lift(1).getOrElse(SInt(0))
      val step = xs.lift(2).getOrElse(SInt(1))
      fromList(List.iterate(start, Math.max(0, n))(add(_, step)))
    }

    // ---- 高階手続き
    prim("apply", 1, Many) { xs =>
      val f = xs.head
      val spread = if (xs.length == 1) Nil else xs.tail.init ++ toList(xs.last, "apply の最後の引数")
      in.apply(f, spread)
    }
    prim("map", 2, Many) { xs =>
      val lists = xs.tail.map(toList(_, "map の引数"))
      val n = lists.map(_.length).min
      fromList((0 until n).toList.map(i => in.apply(xs.head, lists.map(_(i)))))
    }
    prim("for-each", 2, Many) { xs =>
      val lists = xs.tail.map(l => toList(l, "for-each の引数").toArray)
      val n = lists.map(_.length).min
      for (i <- 0 until n) in.apply(xs.head, lists.map(_(i)))
      SUnspec
    }
    fixed2("filter")((f, l) => fromList(toList(l, "filter の引数").filter(x => truthy(in.apply(f, List(x))))))
    prim("fold-left", 3, 3) { case List(f, init, l) => toList(l, "fold-left の引数").foldLeft(init)((acc, x) => in.apply(f, List(acc, x))) }
    prim("fold-right", 3, 3) { case List(f, init, l) => toList(l, "fold-right の引数").foldRight(init)((x, acc) => in.apply(f, List(x, acc))) }
    prim("reduce", 3, 3) { case List(f, init, l) =>
      toList(l, "reduce の引数") match {
        case Nil     => init
        case x :: xs => xs.foldLeft(x)((acc, y) => in.apply(f, List(y, acc)))
      }
    }

    // ---- 文字列とシンボル
    prim("string-append", 0, Many)(xs => SStr(xs.map(str(_, "string-append")).mkString))
    fixed1("string-length")(v => SInt(str(v, "string-length").length))
    fixed1("symbol->string")(v => SStr(symName(v, "symbol->string")))
    fixed1("string->symbol")(v => SSym(str(v, "string->symbol")))
    fixed1("number->string")(v => SStr(Printer.show(v)))
    fixed1("string->number")(v => Reader.parseNumber(str(v, "string->number")).getOrElse(False))
    fixed2("string=?")((a, b) => SBool(str(a, "string=?") == str(b, "string=?")))
    prim("substring", 2, 3) { xs =>
      val s = str(xs.head, "substring")
      val from = toInt(xs(1), "substring").toInt
      val to = xs.lift(2).map(toInt(_, "substring").toInt).getOrElse(s.length)
      if (from < 0 || to > s.length || from > to) err("substring の範囲が正しくありません")
      SStr(s.substring(from, to))
    }

    // ---- 入出力とエラー
    prim("display", 1, 1) { xs => in.out.append(Printer.show(xs.head, write = false)); SUnspec }
    prim("write", 1, 1) { xs => in.out.append(Printer.show(xs.head)); SUnspec }
    prim("newline", 0, 0) { _ => in.out.append('\n'); SUnspec }
    prim("error", 1, Many) { xs =>
      err((Printer.show(xs.head, write = false) :: xs.tail.map(Printer.show(_))).mkString(" "))
    }
  }
}
