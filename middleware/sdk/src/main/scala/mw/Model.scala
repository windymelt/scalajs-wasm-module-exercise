package mw

import java.nio.charset.StandardCharsets.UTF_8

/** ヘッダーは順序と重複を保ったまま持つ。名前の比較は大文字小文字を区別しない */
final case class Headers(entries: Vector[(String, String)]) {
  def get(name: String): Option[String] = entries.collectFirst { case (k, v) if Text.equalsIgnoreCase(k, name) => v }
  def set(name: String, value: String): Headers = Headers(entries.filterNot(e => Text.equalsIgnoreCase(e._1, name)) :+ (name -> value))
  def remove(name: String): Headers = Headers(entries.filterNot(e => Text.equalsIgnoreCase(e._1, name)))
  def render: String = entries.map { case (k, v) => s"$k: $v\r\n" }.mkString
}

object Headers {
  val empty: Headers = Headers(Vector.empty)

  /** "Name: value\r\n" の並びを読む (ホストとの受け渡しの形式) */
  def parse(block: String): Headers = Headers(
    Text.split(block, "\r\n").iterator.filter(_.nonEmpty).flatMap { line =>
      val i = line.indexOf(':')
      if (i <= 0) None else Some(line.substring(0, i).trim -> line.substring(i + 1).trim)
    }.toVector
  )
}

final case class Request(method: String, target: String, headers: Headers, body: Array[Byte], remote: String) {
  def path: String = { val i = target.indexOf('?'); if (i < 0) target else target.substring(0, i) }
  def query: String = { val i = target.indexOf('?'); if (i < 0) "" else target.substring(i + 1) }
  def bodyText: String = new String(body, UTF_8)
}

final case class Response(status: Int, headers: Headers, body: Array[Byte]) {
  def bodyText: String = new String(body, UTF_8)
}

object Response {
  def text(status: Int, body: String, headers: (String, String)*): Response =
    Response(status, Headers(Vector("Content-Type" -> "text/plain; charset=utf-8") ++ headers), body.getBytes(UTF_8))

  def json(status: Int, body: String, headers: (String, String)*): Response =
    Response(status, Headers(Vector("Content-Type" -> "application/json; charset=utf-8") ++ headers), body.getBytes(UTF_8))
}

/** onRequest の結果 */
sealed trait Action
object Action {
  /** 次へ進む。リクエストを書き換えて渡してもよい */
  final case class Continue(request: Request) extends Action
  /** ここで応答して打ち切る */
  final case class Respond(response: Response) extends Action
}

/** プラグインから使えるホストの機能。許可されていないものを呼ぶコードがあると、プラグインは読み込めない */
trait Ctx {
  def log(message: String): Unit
  /** 現在時刻 (ミリ秒)。権限 "now" が必要 */
  def nowMillis(): Double
  /** 全インスタンスで共有するカウンターを 1 増やし、今の時間窓での値を返す。権限 "counter" が必要 */
  def counterIncr(key: String, windowMs: Double): Int
}

trait Middleware {
  /** 起動時に設定 (key=value) を受け取る */
  def configure(config: Map[String, String]): Unit = ()
  def onRequest(req: Request, ctx: Ctx): Action
  /** アプリの応答を加工する。何もしなければそのまま返す */
  def onResponse(req: Request, resp: Response, ctx: Ctx): Response = resp

  /** ホストから受け取りたいもの。不要なものは送られてこないので速い */
  def needsRequestBody: Boolean = false
  def needsResponseBody: Boolean = false
  def hasResponseHook: Boolean = true
}

/** WasmModule では正規表現が使えないので、String.split の代わりに使う */
object Text {
  def split(s: String, sep: String): Vector[String] = {
    val b = Vector.newBuilder[String]
    var from = 0
    var i = s.indexOf(sep, from)
    while (i >= 0) { b += s.substring(from, i); from = i + sep.length; i = s.indexOf(sep, from) }
    b += s.substring(from)
    b.result()
  }

  /** ASCII の範囲だけ小文字にする (ヘッダー名や URL の比較用) */
  def lower(s: String): String = {
    val sb = new java.lang.StringBuilder(s.length)
    var i = 0
    while (i < s.length) { val c = s.charAt(i); sb.append(if (c >= 'A' && c <= 'Z') (c + 32).toChar else c); i += 1 }
    sb.toString
  }

  def equalsIgnoreCase(a: String, b: String): Boolean = a.length == b.length && lower(a) == lower(b)

  def words(s: String): Vector[String] = {
    val b = Vector.newBuilder[String]
    val cur = new java.lang.StringBuilder
    s.foreach { c =>
      if (Character.isWhitespace(c)) { if (cur.length > 0) { b += cur.toString; cur.setLength(0) } }
      else cur.append(c)
    }
    if (cur.length > 0) b += cur.toString
    b.result()
  }
}
