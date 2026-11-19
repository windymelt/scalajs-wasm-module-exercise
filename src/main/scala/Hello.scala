import scala.scalajs.wasm
import scala.scalajs.wasm.annotation._

object Host {
  @WasmImport("host", "printInt")
  def printInt(x: Int): Unit = wasm.native
}

object Hello {
  @WasmExport("fib")
  def fib(n: Int): Int =
    if (n < 2) n else fib(n - 1) + fib(n - 2)

  @WasmExport("countdown")
  def countdown(n: Int): Unit =
    (n to 0 by -1).foreach(Host.printInt)
}
