package imaging

/**
 * PNG (https://www.w3.org/TR/png-3/) の読み込み。
 * 対応: インターレースなし、色の種類はすべて (グレー、RGB、パレット、グレーとアルファ、RGBA)、
 * ビット深度はグレーとパレットが 1/2/4/8、ほかが 8 と 16 (16 は上位 8 ビットだけ使う)。
 * 透明色の tRNS はパレットのときだけ読む
 */
object Png:
  private val Signature = Array[Byte](0x89.toByte, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a)

  def isPng(in: Array[Byte]): Boolean =
    in.length >= 8 && (0 until 8).forall(i => in(i) == Signature(i))

  private lazy val crcTable: Array[Int] =
    val t = new Array[Int](256)
    var n = 0
    while n < 256 do
      var c = n
      var k = 0
      while k < 8 do { c = if (c & 1) != 0 then 0xedb88320 ^ (c >>> 1) else c >>> 1; k += 1 }
      t(n) = c
      n += 1
    t

  private def crc(in: Array[Byte], from: Int, until: Int): Int =
    var c = 0xffffffff
    var i = from
    while i < until do { c = crcTable((c ^ in(i)) & 0xff) ^ (c >>> 8); i += 1 }
    ~c

  private def u32(in: Array[Byte], at: Int): Long =
    ((in(at) & 0xffL) << 24) | ((in(at + 1) & 0xffL) << 16) | ((in(at + 2) & 0xffL) << 8) | (in(at + 3) & 0xffL)

  def decode(in: Array[Byte]): Rgba =
    if !isPng(in) then throw DecodeError("PNG ではありません")
    var p = 8
    var w = 0L; var h = 0L; var depth = 0; var colorType = -1
    var palette: Array[Int] = null // RGBA を 1 つの Int に詰める
    val idat = new java.io.ByteArrayOutputStream()
    var ended = false
    while !ended do
      if p + 12 > in.length then throw DecodeError("PNG が途中で切れています")
      val len = u32(in, p)
      if len > in.length - p - 12 then throw DecodeError("チャンクの長さがおかしいです")
      val n = len.toInt
      val tpe = new String(in.slice(p + 4, p + 8).map(_.toChar))
      val data = p + 8
      if crc(in, p + 4, data + n) != u32(in, data + n).toInt then throw DecodeError(s"$tpe チャンクの CRC が合いません")
      tpe match
        case "IHDR" =>
          if n != 13 then throw DecodeError("IHDR の長さがおかしいです")
          w = u32(in, data); h = u32(in, data + 4)
          depth = in(data + 8) & 0xff; colorType = in(data + 9) & 0xff
          if in(data + 10) != 0 || in(data + 11) != 0 then throw DecodeError("知らない圧縮か知らないフィルターです")
          if in(data + 12) != 0 then throw DecodeError("インターレースした PNG には対応していません")
        case "PLTE" =>
          palette = Array.tabulate(n / 3)(i => ((in(data + i * 3) & 0xff) << 24) | ((in(data + i * 3 + 1) & 0xff) << 16) | ((in(data + i * 3 + 2) & 0xff) << 8) | 0xff)
        case "tRNS" if colorType == 3 && palette != null =>
          var i = 0
          while i < n && i < palette.length do { palette(i) = (palette(i) & 0xffffff00) | (in(data + i) & 0xff); i += 1 }
        case "IDAT" => idat.write(in, data, n)
        case "IEND" => ended = true
        case _ => () // ほかのチャンクは読み飛ばす
      p = data + n + 4

    val channels = colorType match
      case 0 => 1
      case 2 => 3
      case 3 => 1
      case 4 => 2
      case 6 => 4
      case _ => throw DecodeError("知らない色の種類です")
    val okDepth = colorType match
      case 0 => Set(1, 2, 4, 8, 16)
      case 3 => Set(1, 2, 4, 8)
      case _ => Set(8, 16)
    if !okDepth(depth) then throw DecodeError("色の種類とビット深度の組み合わせがおかしいです")
    if colorType == 3 && palette == null then throw DecodeError("パレットがありません")
    if w == 0 || h == 0 || w * h * 4 > Int.MaxValue then throw DecodeError("幅か高さがおかしい画像です")
    val bitsPerPixel = channels * depth
    val rowBytes = ((w * bitsPerPixel + 7) / 8)
    val rawLength = h * (rowBytes + 1)
    if rawLength > Int.MaxValue then throw DecodeError("画像が大きすぎます")

    // 展開する。長さは幅と高さから決まるので、それを超えるデータは読まない
    val raw = Inflate.zlib(idat.toByteArray, rawLength.toInt)
    if raw.length != rawLength then throw DecodeError("画素のデータの長さが合いません")
    unfilter(raw, h.toInt, rowBytes.toInt, ((bitsPerPixel + 7) / 8).max(1))

    val width = w.toInt
    val height = h.toInt
    val out = new Array[Byte](width * height * 4)
    val step = depth / 8 // 16 ビットのときは 2 バイトごとの上位を使う
    var y = 0
    while y < height do
      val row = y * (rowBytes.toInt + 1) + 1
      var x = 0
      while x < width do
        val o = (y * width + x) * 4
        def sample(c: Int): Int = raw(row + (x * channels + c) * step) & 0xff
        colorType match
          case 0 | 3 if depth < 8 =>
            val bit = x * depth
            val v = ((raw(row + bit / 8) & 0xff) >> (8 - depth - bit % 8)) & ((1 << depth) - 1)
            if colorType == 3 then setPalette(out, o, palette, v)
            else { val g = v * 255 / ((1 << depth) - 1); out(o) = g.toByte; out(o + 1) = g.toByte; out(o + 2) = g.toByte; out(o + 3) = 255.toByte }
          case 3 => setPalette(out, o, palette, raw(row + x) & 0xff)
          case 0 => val g = sample(0); out(o) = g.toByte; out(o + 1) = g.toByte; out(o + 2) = g.toByte; out(o + 3) = 255.toByte
          case 4 => val g = sample(0); out(o) = g.toByte; out(o + 1) = g.toByte; out(o + 2) = g.toByte; out(o + 3) = sample(1).toByte
          case 2 => out(o) = sample(0).toByte; out(o + 1) = sample(1).toByte; out(o + 2) = sample(2).toByte; out(o + 3) = 255.toByte
          case _ => out(o) = sample(0).toByte; out(o + 1) = sample(1).toByte; out(o + 2) = sample(2).toByte; out(o + 3) = sample(3).toByte
        x += 1
      y += 1
    Rgba(width, height, out)

  private def setPalette(out: Array[Byte], o: Int, palette: Array[Int], i: Int): Unit =
    if i >= palette.length then throw DecodeError("パレットに無い色です")
    val c = palette(i)
    out(o) = (c >>> 24).toByte; out(o + 1) = (c >>> 16).toByte; out(o + 2) = (c >>> 8).toByte; out(o + 3) = c.toByte

  /** 行ごとのフィルター (なし、左、上、平均、Paeth) を戻す。raw を書き換える */
  private def unfilter(raw: Array[Byte], height: Int, rowBytes: Int, bpp: Int): Unit =
    val stride = rowBytes + 1
    var y = 0
    while y < height do
      val row = y * stride
      val filter = raw(row)
      var i = 0
      while i < rowBytes do
        val at = row + 1 + i
        val a = if i >= bpp then raw(at - bpp) & 0xff else 0
        val b = if y > 0 then raw(at - stride) & 0xff else 0
        val c = if y > 0 && i >= bpp then raw(at - stride - bpp) & 0xff else 0
        val add = filter match
          case 0 => 0
          case 1 => a
          case 2 => b
          case 3 => (a + b) >>> 1
          case 4 =>
            val pp = a + b - c
            val pa = math.abs(pp - a); val pb = math.abs(pp - b); val pc = math.abs(pp - c)
            if pa <= pb && pa <= pc then a else if pb <= pc then b else c
          case _ => throw DecodeError("知らないフィルターの種類です")
        raw(at) = ((raw(at) & 0xff) + add).toByte
        i += 1
      y += 1
