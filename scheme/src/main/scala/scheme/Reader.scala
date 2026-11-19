package scheme

import Value.err

/** S 式の読み取り。WasmModule では正規表現が使えないので、1 文字ずつ手で読む */
object Reader {
  val quoteNames: Map[String, String] =
    Map("quote" -> "'", "quasiquote" -> "`", "unquote" -> ",", "unquote-splicing" -> ",@")

  def readAll(src: String): List[Value] = new Reader(src).all()

  private def isDelimiter(c: Char): Boolean =
    c == '(' || c == ')' || c == '[' || c == ']' || c == '"' || c == ';' || c == '\'' || c == '`' || c == ',' ||
      Character.isWhitespace(c)

  /** 整数なら SInt、小数なら SReal、それ以外は None */
  def parseNumber(s: String): Option[Value] = {
    val body = if (s.startsWith("+") || s.startsWith("-")) s.substring(1) else s
    if (body.isEmpty || !body.exists(_.isDigit)) None
    else if (body.forall(_.isDigit)) Some(SInt(BigInt(s)))
    else if (body.forall(c => c.isDigit || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') && (body.head.isDigit || body.head == '.'))
      try Some(SReal(s.toDouble))
      catch { case _: NumberFormatException => None }
    else None
  }
}

private final class Reader(src: String) {
  private var pos = 0

  private def peek: Int = if (pos < src.length) src.charAt(pos).toInt else -1

  private def skip(): Unit = {
    var going = true
    while (going && pos < src.length) {
      val c = src.charAt(pos)
      if (Character.isWhitespace(c)) pos += 1
      else if (c == ';') { while (pos < src.length && src.charAt(pos) != '\n') pos += 1 }
      else going = false
    }
  }

  def all(): List[Value] = {
    val b = List.newBuilder[Value]
    skip()
    while (pos < src.length) { b += read(); skip() }
    b.result()
  }

  private def read(): Value = {
    skip()
    if (pos >= src.length) throw new Incomplete
    src.charAt(pos) match {
      case '(' | '[' =>
        pos += 1
        readList()
      case ')' | ']' =>
        err("閉じ括弧が多すぎます")
      case '\'' => pos += 1; Value.list(SSym("quote"), read())
      case '`'  => pos += 1; Value.list(SSym("quasiquote"), read())
      case ',' =>
        pos += 1
        if (peek == '@') { pos += 1; Value.list(SSym("unquote-splicing"), read()) }
        else Value.list(SSym("unquote"), read())
      case '"' => pos += 1; readString()
      case _   => readAtom()
    }
  }

  private def readList(): Value = {
    val items = List.newBuilder[Value]
    var tail: Value = SNil
    var done = false
    while (!done) {
      skip()
      if (pos >= src.length) throw new Incomplete
      val c = src.charAt(pos)
      if (c == ')' || c == ']') { pos += 1; done = true }
      else if (c == '.' && pos + 1 < src.length && Character.isWhitespace(src.charAt(pos + 1))) {
        pos += 1
        tail = read()
        skip()
        if (pos >= src.length) throw new Incomplete
        if (src.charAt(pos) != ')' && src.charAt(pos) != ']') err("ドット対の後ろには要素を 1 つだけ書けます")
        pos += 1
        done = true
      } else items += read()
    }
    Value.fromList(items.result(), tail)
  }

  private def readString(): Value = {
    val sb = new java.lang.StringBuilder
    var done = false
    while (!done) {
      if (pos >= src.length) throw new Incomplete
      val c = src.charAt(pos)
      pos += 1
      if (c == '"') done = true
      else if (c == '\\') {
        if (pos >= src.length) throw new Incomplete
        val e = src.charAt(pos)
        pos += 1
        sb.append(e match {
          case 'n' => '\n'
          case 't' => '\t'
          case x   => x
        })
      } else sb.append(c)
    }
    SStr(sb.toString)
  }

  private def readAtom(): Value = {
    val start = pos
    while (pos < src.length && !Reader.isDelimiter(src.charAt(pos))) pos += 1
    val tok = src.substring(start, pos)
    tok match {
      case "#t" | "#true"  => Value.True
      case "#f" | "#false" => Value.False
      case _ =>
        Reader.parseNumber(tok).getOrElse {
          if (tok.startsWith("#")) err(s"この処理系では読めない記法です: $tok")
          SSym(tok)
        }
    }
  }
}
