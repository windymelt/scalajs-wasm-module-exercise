import mw._

/** text/plain の応答の末尾に 1 行足す (応答本文を書き換える例) */
object Signature extends Middleware {
  private var line = "-- served through a Scala.js Wasm middleware"

  override def configure(config: Map[String, String]): Unit = config.get("line").foreach(v => line = v.replace('_', ' '))
  override def needsResponseBody: Boolean = true

  def onRequest(req: Request, ctx: Ctx): Action = Action.Continue(req)

  override def onResponse(req: Request, resp: Response, ctx: Ctx): Response =
    if (resp.headers.get("Content-Type").exists(_.startsWith("text/plain")))
      resp.copy(body = (resp.bodyText.stripSuffix("\n") + "\n" + line + "\n").getBytes("UTF-8"))
    else resp
}

object Main { def main(args: Array[String]): Unit = Abi.install(Signature) }
