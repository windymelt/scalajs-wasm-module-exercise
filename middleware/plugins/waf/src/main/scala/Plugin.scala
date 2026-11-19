import mw._

/** URL にあやしい文字列が含まれていたら 403 で止める。正規表現は使えないので、部分文字列で探す */
object Waf extends Middleware {
  private val rules: List[(String, String)] = List(
    "パストラバーサル" -> "../",
    "パストラバーサル" -> "%2e%2e",
    "SQL インジェクション" -> "' or 1=1",
    "SQL インジェクション" -> "%27 or 1=1",
    "SQL インジェクション" -> "union select",
    "XSS" -> "<script",
    "XSS" -> "%3cscript",
  )

  override def hasResponseHook: Boolean = false

  def onRequest(req: Request, ctx: Ctx): Action = {
    val t = Text.lower(req.target).replace("+", " ").replace("%20", " ")
    rules.find(r => t.contains(r._2)) match {
      case Some((kind, pattern)) =>
        ctx.log(s"blocked ($kind, pattern=$pattern): ${req.method} ${req.target} from ${req.remote}")
        Action.Respond(Response.json(403, s"""{"error":"forbidden","reason":"$kind"}"""))
      case None => Action.Continue(req)
    }
  }
}

object Main { def main(args: Array[String]): Unit = Abi.install(Waf) }
