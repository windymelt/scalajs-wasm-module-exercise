import mw._

/** prefix 以下に Bearer トークンを要求する。通したら Authorization を消し、認証済みの印を付けてアプリへ渡す */
object Auth extends Middleware {
  private var prefix = "/admin"
  private var token = ""

  override def configure(config: Map[String, String]): Unit = {
    config.get("prefix").foreach(prefix = _)
    config.get("token").foreach(token = _)
  }

  override def hasResponseHook: Boolean = false

  /** 長さが同じなら、どこで食い違っても同じ時間をかけて比べる */
  private def constantTimeEquals(a: String, b: String): Boolean =
    a.length == b.length && a.zip(b).foldLeft(0)((acc, p) => acc | (p._1 ^ p._2)) == 0

  def onRequest(req: Request, ctx: Ctx): Action =
    if (!req.path.startsWith(prefix)) Action.Continue(req)
    else req.headers.get("Authorization") match {
      case Some(v) if token.nonEmpty && v.startsWith("Bearer ") && constantTimeEquals(v.substring(7), token) =>
        Action.Continue(req.copy(headers = req.headers.remove("Authorization").set("X-Authenticated-User", "admin")))
      case _ =>
        ctx.log(s"unauthorized: ${req.method} ${req.path} from ${req.remote}")
        Action.Respond(Response.json(401, """{"error":"unauthorized"}""", "WWW-Authenticate" -> "Bearer realm=\"admin\""))
    }
}

object Main { def main(args: Array[String]): Unit = Abi.install(Auth) }
