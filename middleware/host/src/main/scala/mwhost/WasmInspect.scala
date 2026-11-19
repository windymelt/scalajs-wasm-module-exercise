package mwhost

/** .wasm のインポートとエクスポートの名前を読む。読み込む前に、要求している権限を確かめるため */
object WasmInspect:
  final case class Info(imports: List[(String, String)], exports: List[String])

  def apply(bytes: Array[Byte]): Info =
    var pos = 8 // マジックナンバーとバージョン
    def u8(): Int = { val b = bytes(pos) & 0xff; pos += 1; b }
    def leb(): Int =
      var result = 0
      var shift = 0
      var b = 0
      while
        b = u8()
        result |= (b & 0x7f) << shift
        shift += 7
        (b & 0x80) != 0
      do ()
      result
    def name(): String =
      val n = leb()
      val s = new String(bytes, pos, n, "UTF-8")
      pos += n
      s

    require(bytes.length >= 8 && bytes(0) == 0 && bytes(1) == 'a' && bytes(2) == 's' && bytes(3) == 'm', "Wasm のバイナリではありません")
    val imports = List.newBuilder[(String, String)]
    val exports = List.newBuilder[String]
    while pos < bytes.length do
      val id = u8()
      val size = leb()
      val end = pos + size
      id match
        case 2 => // import section
          val count = leb()
          var i = 0
          var ok = true
          while ok && i < count do
            val module = name()
            val field = name()
            imports += module -> field
            u8() match
              case 0 => leb() // 関数: 型の番号
              case 4 => u8(); leb() // タグ: 属性と型の番号
              case _ => ok = false // それ以外は読み飛ばし方が複雑なので、名前の一覧はここまで
            i += 1
        case 7 => // export section
          val count = leb()
          for _ <- 0 until count do
            exports += name()
            u8(); leb()
        case _ =>
      pos = end
    Info(imports.result(), exports.result())
