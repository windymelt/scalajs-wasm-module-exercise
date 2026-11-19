import scala.scalajs.wasm
import scala.scalajs.wasm.annotation._

object Types {
  @WasmExport("addLong") def addLong(a: Long, b: Long): Long = a + b
  @WasmExport("hypot")   def hypot(a: Double, b: Double): Double = Math.sqrt(a * a + b * b)
  @WasmExport("halfF")   def halfF(a: Float): Float = a / 2

  @WasmExport("makeSquares")
  def makeSquares(n: Int): Array[Int] = Array.tabulate(n)(i => i * i)

  @WasmExport("sumArray")
  def sumArray(xs: Array[Int]): Int = xs.sum

  @WasmExport("arrayLen")
  def arrayLen(xs: Array[Double]): Int = xs.length
}
