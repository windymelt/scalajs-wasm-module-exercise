import mw._

/** 接続元ごとに、時間窓の中のリクエスト数を数える。カウンターはホストの共有機能を使う (権限 counter) */
object RateLimit extends Middleware {
  private var limit = 5
  private var windowMs = 10000.0
  private var remaining = 0

  override def configure(config: Map[String, String]): Unit = {
    config.get("limit").foreach(v => limit = v.toInt)
    config.get("windowMs").foreach(v => windowMs = v.toDouble)
  }

  def onRequest(req: Request, ctx: Ctx): Action = {
    val n = ctx.counterIncr("ip:" + req.remote, windowMs)
    remaining = Math.max(0, limit - n)
    if (n > limit) {
      ctx.log(s"rate limited: ${req.remote} ($n requests in ${windowMs.toLong} ms)")
      Action.Respond(Response.json(429, s"""{"error":"too many requests","limit":$limit,"windowMs":${windowMs.toLong}}""",
        "Retry-After" -> Math.ceil(windowMs / 1000).toInt.toString))
    } else Action.Continue(req)
  }

  override def onResponse(req: Request, resp: Response, ctx: Ctx): Response =
    resp.copy(headers = resp.headers.set("X-RateLimit-Limit", limit.toString).set("X-RateLimit-Remaining", remaining.toString))
}

object Main { def main(args: Array[String]): Unit = Abi.install(RateLimit) }
