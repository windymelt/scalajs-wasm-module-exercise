package shop

import io.bullet.borer.Codec
import io.bullet.borer.derivation.MapBasedCodecs.derived

// JVM と Wasm の両方でコンパイルする型。wasmIsolated のブロックとは CBOR でやりとりする

final case class Item(name: String, unitPrice: Int, quantity: Int) derives Codec
final case class Order(customer: String, items: List[Item], coupon: Option[String]) derives Codec
final case class Receipt(lines: List[String], total: Int, discount: Int) derives Codec
