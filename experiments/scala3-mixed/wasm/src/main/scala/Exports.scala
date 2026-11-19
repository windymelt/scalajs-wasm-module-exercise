import scala.scalajs.wasm
import scala.scalajs.wasm.annotation._

object Exports {
  @WasmImport("host", "putChar")
  def putChar(c: Int): Unit = wasm.native

  @WasmExport("fib")
  def fib(n: Int): Int = core.Calc.fib(n)

  @WasmExport("calcDemo")
  def calcDemo(): Unit =
    core.Calc.report().foreach(line => (line + "\n").foreach(c => putChar(c.toInt)))
}
