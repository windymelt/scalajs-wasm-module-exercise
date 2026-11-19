import mw._

/** すべての応答にセキュリティ系のヘッダーを付け、実装を明かすヘッダーを消す */
object SecurityHeaders extends Middleware {
  def onRequest(req: Request, ctx: Ctx): Action = Action.Continue(req)

  override def onResponse(req: Request, resp: Response, ctx: Ctx): Response =
    resp.copy(headers = resp.headers
      .set("X-Content-Type-Options", "nosniff")
      .set("X-Frame-Options", "DENY")
      .set("Referrer-Policy", "no-referrer")
      .set("Content-Security-Policy", "default-src 'none'")
      .remove("Server")
      .remove("X-Powered-By"))
}

object Main { def main(args: Array[String]): Unit = Abi.install(SecurityHeaders) }
