package wasmisolated.exports

import scala.scalajs.wasm.annotation.WasmExport

/**
 * JVM 側から Wasm に配列を渡すための窓口 (wasmIsolated の実行時ライブラリが使う)。
 *
 * GraalWasm には JVM の int[] をそのまま渡せない。そこで、まずこの関数で Wasm の配列を作ってもらい、
 * JVM 側で要素を書き込んでから、ブロックの関数に引数として渡す。
 */
object Alloc {
  @WasmExport("wasmIsolated_alloc_Byte") def byte(n: Int): Array[Byte] = new Array[Byte](n)
  @WasmExport("wasmIsolated_alloc_Short") def short(n: Int): Array[Short] = new Array[Short](n)
  @WasmExport("wasmIsolated_alloc_Int") def int(n: Int): Array[Int] = new Array[Int](n)
  @WasmExport("wasmIsolated_alloc_Long") def long(n: Int): Array[Long] = new Array[Long](n)
  @WasmExport("wasmIsolated_alloc_Float") def float(n: Int): Array[Float] = new Array[Float](n)
  @WasmExport("wasmIsolated_alloc_Double") def double(n: Int): Array[Double] = new Array[Double](n)
}
