package wasmisolated

import io.bullet.borer.{Cbor, Decoder, Encoder}

/** 数値でも数値の配列でもない値は、borer で CBOR のバイト列にして Wasm との境界を越える (マクロが使う) */
object CborBridge:
  def toBytes[A](value: A)(using Encoder[A]): Array[Byte] = Cbor.encode(value).toByteArray
  def fromBytes[A](bytes: Array[Byte])(using Decoder[A]): A = Cbor.decode(bytes).to[A].value
