import mw._
import scala.scalajs.wasm
import scala.scalajs.wasm.annotation._

/** ホストが用意していない権限 (ファイルの読み出し) を要求するプラグイン。読み込みの時点で拒否されるはず */
object Forbidden {
  @WasmImport("host", "read_file")
  def readFile(pathHash: Long): Int = wasm.native
}

object Greedy extends Middleware {
  def onRequest(req: Request, ctx: Ctx): Action = {
    Forbidden.readFile(Fnv.hash64("/etc/passwd"))
    Action.Continue(req)
  }
}

object Main { def main(args: Array[String]): Unit = Abi.install(Greedy) }
