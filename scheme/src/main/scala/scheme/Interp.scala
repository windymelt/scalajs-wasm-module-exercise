package scheme

import scala.collection.mutable

import Value._

final class Env(val parent: Env) {
  val vars = new mutable.HashMap[String, Value]

  def lookup(name: String): Value = {
    var e = this
    while (e ne null) {
      val v = e.vars.getOrElse(name, null)
      if (v ne null) return v
      e = e.parent
    }
    err(s"未定義の変数です: $name")
  }

  def set(name: String, v: Value): Unit = {
    var e = this
    while (e ne null) {
      if (e.vars.contains(name)) { e.vars(name) = v; return }
      e = e.parent
    }
    err(s"set! の前に define されていない変数です: $name")
  }

  def define(name: String, v: Value): Unit = vars(name) = v
}

final class Interp(seed: Int) {
  val global = new Env(null)
  val out = new java.lang.StringBuilder
  val rnd = new java.util.Random(seed.toLong)
  var steps = 0L
  private var fuel = Long.MaxValue

  Builtins.install(this)

  private val Begin = SSym("begin")

  // ---- 末尾位置の続き。特殊形式のメソッドは「値」か「null (続きは tailX / tailEnv)」を返す。
  //      タプルを返すと呼び出しのたびに確保が起きるので、フィールドで受け渡す。
  private var tailX: Value = null
  private var tailEnv: Env = null
  private def tail(x: Value, env: Env): Value = { tailX = x; tailEnv = env; null }

  // ---- 引数の取り出し
  private def args(rest: Value, form: String, min: Int, max: Int = Int.MaxValue): List[Value] = {
    val xs = toList(rest, s"$form の引数")
    if (xs.length < min || xs.length > max) err(s"$form の形が正しくありません")
    xs
  }

  private def makeClosure(params: Value, body: Value, env: Env, name: String): Closure = {
    val ps = List.newBuilder[String]
    var c = params
    while (c.isInstanceOf[SPair]) { val p = c.asInstanceOf[SPair]; ps += symName(p.car, "仮引数"); c = p.cdr }
    val rest = c match {
      case SNil    => None
      case SSym(n) => Some(n)
      case other   => err(s"仮引数の並びが正しくありません: ${Printer.show(other)}")
    }
    if (body == SNil) err("lambda の本体が空です")
    new Closure(ps.result(), rest, SPair(Begin, body), env, name)
  }

  private def nameIfAnonymous(v: Value, name: String): Unit = v match {
    case c: Closure if c.name == "λ" => c.name = name
    case _                           =>
  }

  private def bind(c: Closure, argv: List[Value]): Env = {
    val e = new Env(c.env)
    var ps = c.params
    var as = argv
    while (ps.nonEmpty) {
      if (as.isEmpty) err(s"${c.name} の引数が足りません (${c.params.length} 個必要)")
      e.define(ps.head, as.head)
      ps = ps.tail
      as = as.tail
    }
    c.rest match {
      case Some(r) => e.define(r, fromList(as))
      case None    => if (as.nonEmpty) err(s"${c.name} の引数が多すぎます (${c.params.length} 個まで)")
    }
    e
  }

  /** 手続きを呼ぶ (map や apply などの組み込みから使う。末尾呼び出しにはならない) */
  def apply(f: Value, argv: List[Value]): Value = f match {
    case p: Prim    => callPrim(p, argv)
    case c: Closure => eval(c.body, bind(c, argv))
    case other      => err(s"手続きではないものを呼び出そうとしました: ${Printer.show(other)}")
  }

  private def callPrim(p: Prim, argv: List[Value]): Value = {
    val n = argv.length
    if (n < p.minArgs || n > p.maxArgs) {
      val want =
        if (p.minArgs == p.maxArgs) s"${p.minArgs} 個"
        else if (p.maxArgs == Int.MaxValue) s"${p.minArgs} 個以上"
        else s"${p.minArgs}〜${p.maxArgs} 個"
      err(s"${p.name} の引数は $want です (${n} 個渡されました)")
    }
    p.f(argv)
  }

  /**
   * 評価器の本体。末尾位置の式 (if の枝、begin の最後、手続きの本体…) は
   * 再帰せずに x と env を差し替えてループを回すので、末尾再帰はスタックを消費しない。
   *
   * 末尾でない再帰ではこのメソッドが入れ子になるので、ここにはローカル変数を増やさない。
   * 特殊形式の処理を全部ここに書くと Wasm 関数のローカルが膨れ、1 段あたりのスタックが太くなる。
   */
  def eval(x0: Value, env0: Env): Value = {
    var x = x0
    var env = env0
    while (true) {
      steps += 1
      if (steps > fuel) err(s"計算が $fuel ステップを超えたので打ち切りました (無限ループかもしれません)")
      x match {
        case SSym(n) => return env.lookup(n)
        case p: SPair =>
          val r = p.car match {
            case SSym(n) if Interp.specialForms.contains(n) => special(n, p.cdr, env)
            case head                                       => application(head, p.cdr, env)
          }
          if (r ne null) return r
          x = tailX
          env = tailEnv
        case other => return other // 数・文字列・真偽値などは自分自身に評価される
      }
    }
    null // 到達しない
  }

  private def application(head: Value, rest: Value, env: Env): Value = {
    val f = eval(head, env)
    val argv = List.newBuilder[Value]
    var c = rest
    while (c.isInstanceOf[SPair]) { val p = c.asInstanceOf[SPair]; argv += eval(p.car, env); c = p.cdr }
    if (c != SNil) err("呼び出しの引数が真のリストではありません")
    f match {
      case p: Prim     => callPrim(p, argv.result())
      case cl: Closure => tail(cl.body, bind(cl, argv.result()))
      case other       => err(s"手続きではないものを呼び出そうとしました: ${Printer.show(other)}")
    }
  }

  private def special(name: String, rest: Value, env: Env): Value = name match {
    case "quote"                          => args(rest, "quote", 1, 1).head
    case "quasiquote"                     => quasi(args(rest, "quasiquote", 1, 1).head, env, 1)
    case "if"                             => evalIf(rest, env)
    case "define"                         => evalDefine(rest, env)
    case "set!"                           => evalSet(rest, env)
    case "lambda"                         => evalLambda(rest, env)
    case "begin"                          => evalBegin(rest, env)
    case "let"                            => evalLet(rest, env)
    case "let*" | "letrec" | "letrec*"    => evalLetStar(name, rest, env)
    case "cond"                           => evalCond(rest, env)
    case "and"                            => evalAnd(rest, env)
    case "or"                             => evalOr(rest, env)
    case "when" | "unless"                => evalWhen(name, rest, env)
  }

  private def evalIf(rest: Value, env: Env): Value = {
    val xs = args(rest, "if", 2, 3)
    if (truthy(eval(xs.head, env))) tail(xs(1), env)
    else if (xs.length == 3) tail(xs(2), env)
    else SUnspec
  }

  private def evalDefine(rest: Value, env: Env): Value = rest match {
    case SPair(SPair(SSym(name), params), body) =>
      env.define(name, makeClosure(params, body, env, name))
      SSym(name)
    case SPair(SSym(name), SPair(expr, SNil)) =>
      val v = eval(expr, env)
      nameIfAnonymous(v, name)
      env.define(name, v)
      SSym(name)
    case _ => err("define の形が正しくありません")
  }

  private def evalSet(rest: Value, env: Env): Value = {
    val xs = args(rest, "set!", 2, 2)
    env.set(symName(xs.head, "set! の対象"), eval(xs(1), env))
    SUnspec
  }

  private def evalLambda(rest: Value, env: Env): Value = rest match {
    case SPair(params, body) => makeClosure(params, body, env, "λ")
    case _                   => err("lambda の形が正しくありません")
  }

  private def evalBegin(rest: Value, env: Env): Value =
    if (rest == SNil) SUnspec
    else {
      var c = rest.asInstanceOf[SPair]
      while (c.cdr != SNil) {
        eval(c.car, env)
        c = c.cdr match {
          case p: SPair => p
          case _        => err("begin の形が正しくありません")
        }
      }
      tail(c.car, env)
    }

  private def bindings(v: Value, form: String): List[(String, Value)] = toList(v, s"$form の束縛").map {
    case SPair(SSym(n), SPair(e, SNil)) => (n, e)
    case b                              => err(s"$form の束縛の形が正しくありません: ${Printer.show(b)}")
  }

  private def evalLet(rest: Value, env: Env): Value = rest match {
    // 名前付き let: (let loop ((i 0)) ...)
    case SPair(SSym(loopName), SPair(bs0, body)) =>
      val bs = bindings(bs0, "let")
      val loopEnv = new Env(env)
      val proc = makeClosure(fromList(bs.map(b => SSym(b._1))), body, loopEnv, loopName)
      loopEnv.define(loopName, proc)
      tail(proc.body, bind(proc, bs.map(b => eval(b._2, env))))
    case SPair(bs0, body) =>
      val e = new Env(env)
      bindings(bs0, "let").foreach { case (n, v) => e.define(n, eval(v, env)) }
      tail(SPair(Begin, body), e)
    case _ => err("let の形が正しくありません")
  }

  private def evalLetStar(form: String, rest: Value, env: Env): Value = rest match {
    case SPair(bs0, body) =>
      var e = new Env(env)
      val rec = form != "let*"
      bindings(bs0, form).foreach { case (n, v) =>
        val value = eval(v, e)
        nameIfAnonymous(value, n)
        e.define(n, value)
        if (!rec) e = new Env(e)
      }
      tail(SPair(Begin, body), e)
    case _ => err(s"$form の形が正しくありません")
  }

  private def evalCond(rest: Value, env: Env): Value = {
    var clauses = toList(rest, "cond の節")
    while (clauses.nonEmpty) {
      clauses.head match {
        case SPair(SSym("else"), body) => return tail(SPair(Begin, body), env)
        case SPair(test, SPair(SSym("=>"), SPair(fexpr, SNil))) =>
          val v = eval(test, env)
          if (truthy(v)) return apply(eval(fexpr, env), List(v))
        case SPair(test, body) =>
          val t = eval(test, env)
          if (truthy(t)) return (if (body == SNil) t else tail(SPair(Begin, body), env))
        case c => err(s"cond の節の形が正しくありません: ${Printer.show(c)}")
      }
      clauses = clauses.tail
    }
    SUnspec
  }

  private def evalAnd(rest: Value, env: Env): Value = {
    var xs = toList(rest, "and の引数")
    if (xs.isEmpty) return True
    while (xs.tail.nonEmpty) {
      if (!truthy(eval(xs.head, env))) return False
      xs = xs.tail
    }
    tail(xs.head, env)
  }

  private def evalOr(rest: Value, env: Env): Value = {
    var xs = toList(rest, "or の引数")
    if (xs.isEmpty) return False
    while (xs.tail.nonEmpty) {
      val v = eval(xs.head, env)
      if (truthy(v)) return v
      xs = xs.tail
    }
    tail(xs.head, env)
  }

  private def evalWhen(form: String, rest: Value, env: Env): Value = rest match {
    case SPair(test, body) =>
      if (truthy(eval(test, env)) == (form == "when")) tail(SPair(Begin, body), env) else SUnspec
    case _ => err(s"$form の形が正しくありません")
  }

  private def quasi(t: Value, env: Env, depth: Int): Value = t match {
    case SPair(SSym("unquote"), SPair(e, SNil)) =>
      if (depth == 1) eval(e, env) else list(SSym("unquote"), quasi(e, env, depth - 1))
    case SPair(SPair(SSym("unquote-splicing"), SPair(e, SNil)), rest) if depth == 1 =>
      fromList(toList(eval(e, env), "unquote-splicing の値"), quasi(rest, env, depth))
    case SPair(SSym("quasiquote"), SPair(e, SNil)) => list(SSym("quasiquote"), quasi(e, env, depth + 1))
    case SPair(a, d)                               => SPair(quasi(a, env, depth), quasi(d, env, depth))
    case other                                     => other
  }

  /** トップレベルの式を順に評価する。戻り値は (各式の値, 状態)。状態は 0: 成功, 1: エラー, 2: 入力が途中 */
  def run(src: String, fuelLimit: Long): (List[String], Int) = {
    out.setLength(0)
    steps = 0
    fuel = fuelLimit
    val forms =
      try Reader.readAll(src)
      catch {
        case _: Incomplete  => return (Nil, 2)
        case e: SchemeError => return (List(e.getMessage), 1)
      }
    val results = List.newBuilder[String]
    try {
      forms.foreach { f =>
        val v = eval(f, global)
        if (v != SUnspec) results += Printer.show(v)
      }
      (results.result(), 0)
    } catch {
      case e: SchemeError         => (results.result() :+ e.getMessage, 1)
      case e: ArithmeticException => (results.result() :+ s"算術エラー: ${e.getMessage}", 1)
      case e: Throwable           => (results.result() :+ s"内部エラー: $e", 1)
    }
  }
}

object Interp {
  val specialForms: Set[String] =
    Set("quote", "quasiquote", "if", "define", "set!", "lambda", "begin", "let", "let*", "letrec", "letrec*", "cond", "and", "or", "when", "unless")
}
