import mw._

/** わざと悪さをするプラグイン。ホストが自分を守れるかを試す */
object Evil extends Middleware {
  override def hasResponseHook: Boolean = false

  private def recurse(n: Int): Int = recurse(n + 1) + 1

  def onRequest(req: Request, ctx: Ctx): Action = req.path match {
    case "/evil/loop" =>
      var i = 0L
      while (true) i += 1
      Action.Continue(req)
    case "/evil/alloc" =>
      val hoard = scala.collection.mutable.ArrayBuffer.empty[Array[Byte]]
      while (true) hoard += new Array[Byte](1 << 20)
      Action.Continue(req)
    case "/evil/crash" =>
      throw new IllegalStateException("evil plugin crashed on purpose")
    case "/evil/recurse" =>
      recurse(0)
      Action.Continue(req)
    case _ => Action.Continue(req)
  }
}

object Main { def main(args: Array[String]): Unit = Abi.install(Evil) }
