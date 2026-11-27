package imaging

/**
 * QOI (The Quite OK Image Format, https://qoiformat.org/qoi-specification.pdf) の読み書き。
 * 画素を「直前と同じ画素の繰り返し」「直前との差」などで短く書く、可逆圧縮の形式
 */
object Qoi:
  /** 参照実装 (qoi.h) と同じ画素数の上限 */
  val MaxPixels: Long = 400_000_000L

  private val OpIndex = 0x00
  private val OpDiff = 0x40
  private val OpLuma = 0x80
  private val OpRun = 0xc0
  private val OpRgb = 0xfe
  private val OpRgba = 0xff
  private val Padding = 8

  private def hash(r: Int, g: Int, b: Int, a: Int): Int = (r * 3 + g * 5 + b * 7 + a * 11) % 64

  private def u32(in: Array[Byte], at: Int): Long =
    ((in(at) & 0xffL) << 24) | ((in(at + 1) & 0xffL) << 16) | ((in(at + 2) & 0xffL) << 8) | (in(at + 3) & 0xffL)

  /**
   * 読む。画素の領域はヘッダーの幅と高さから先に確保する (参照実装と同じ)。
   * そのため、幅と高さだけ大きくて中身は「繰り返し」ばかりの小さなファイルでも、大きなメモリを使う
   */
  def decode(in: Array[Byte]): Rgba =
    if in.length < 14 + Padding then throw DecodeError("QOI としては短すぎます")
    if !(in(0) == 'q' && in(1) == 'o' && in(2) == 'i' && in(3) == 'f') then throw DecodeError("QOI ではありません")
    val w = u32(in, 4)
    val h = u32(in, 8)
    val channels = in(12)
    if w == 0 || h == 0 || w * h > MaxPixels then throw DecodeError("幅か高さがおかしい画像です")
    if channels != 3 && channels != 4 then throw DecodeError("チャンネル数がおかしい画像です")
    val total = (w * h).toInt
    val out = new Array[Byte](total * 4)
    val index = new Array[Int](64)
    var r = 0; var g = 0; var b = 0; var a = 255
    var p = 14
    val end = in.length - Padding
    var run = 0
    var px = 0
    def need(k: Int): Unit = if p + k > in.length then throw DecodeError("データが途中で切れています")
    while px < total do
      if run > 0 then run -= 1
      else if p < end then
        val b1 = in(p) & 0xff
        p += 1
        if b1 == OpRgb then
          need(3); r = in(p) & 0xff; g = in(p + 1) & 0xff; b = in(p + 2) & 0xff; p += 3
        else if b1 == OpRgba then
          need(4); r = in(p) & 0xff; g = in(p + 1) & 0xff; b = in(p + 2) & 0xff; a = in(p + 3) & 0xff; p += 4
        else
          (b1 & 0xc0) match
            case OpIndex =>
              val c = index(b1)
              r = c >>> 24; g = (c >>> 16) & 0xff; b = (c >>> 8) & 0xff; a = c & 0xff
            case OpDiff =>
              r = (r + ((b1 >> 4) & 3) - 2) & 0xff
              g = (g + ((b1 >> 2) & 3) - 2) & 0xff
              b = (b + (b1 & 3) - 2) & 0xff
            case OpLuma =>
              need(1)
              val b2 = in(p) & 0xff
              p += 1
              val vg = (b1 & 0x3f) - 32
              r = (r + vg - 8 + ((b2 >> 4) & 0x0f)) & 0xff
              g = (g + vg) & 0xff
              b = (b + vg - 8 + (b2 & 0x0f)) & 0xff
            case _ =>
              run = b1 & 0x3f
        index(hash(r, g, b, a)) = (r << 24) | (g << 16) | (b << 8) | a
      val o = px * 4
      out(o) = r.toByte; out(o + 1) = g.toByte; out(o + 2) = b.toByte; out(o + 3) = a.toByte
      px += 1
    Rgba(w.toInt, h.toInt, out)

  /** 書く (4 チャンネル、sRGB) */
  def encode(img: Rgba): Array[Byte] =
    val px = img.pixels
    val total = img.width * img.height
    val out = new Array[Byte](14 + total * 5 + Padding) // 最悪でも 1 画素 5 バイト
    var p = 0
    def put(v: Int): Unit = { out(p) = v.toByte; p += 1 }
    def put32(v: Int): Unit = { put(v >>> 24); put(v >>> 16); put(v >>> 8); put(v) }
    put('q'); put('o'); put('i'); put('f')
    put32(img.width); put32(img.height); put(4); put(0)
    val index = new Array[Int](64)
    var pr = 0; var pg = 0; var pb = 0; var pa = 255
    var run = 0
    var i = 0
    while i < total do
      val o = i * 4
      val r = px(o) & 0xff; val g = px(o + 1) & 0xff; val b = px(o + 2) & 0xff; val a = px(o + 3) & 0xff
      if r == pr && g == pg && b == pb && a == pa then
        run += 1
        if run == 62 || i == total - 1 then { put(OpRun | (run - 1)); run = 0 }
      else
        if run > 0 then { put(OpRun | (run - 1)); run = 0 }
        val packed = (r << 24) | (g << 16) | (b << 8) | a
        val h = hash(r, g, b, a)
        if index(h) == packed then put(OpIndex | h)
        else
          index(h) = packed
          if a == pa then
            val vr = ((r - pr + 128) & 0xff) - 128 // -128..127 に折り返す
            val vg = ((g - pg + 128) & 0xff) - 128
            val vb = ((b - pb + 128) & 0xff) - 128
            val vgr = vr - vg
            val vgb = vb - vg
            if vr > -3 && vr < 2 && vg > -3 && vg < 2 && vb > -3 && vb < 2 then
              put(OpDiff | ((vr + 2) << 4) | ((vg + 2) << 2) | (vb + 2))
            else if vgr > -9 && vgr < 8 && vg > -33 && vg < 32 && vgb > -9 && vgb < 8 then
              put(OpLuma | (vg + 32)); put(((vgr + 8) << 4) | (vgb + 8))
            else { put(OpRgb); put(r); put(g); put(b) }
          else { put(OpRgba); put(r); put(g); put(b); put(a) }
      pr = r; pg = g; pb = b; pa = a
      i += 1
    var k = 0
    while k < 7 do { put(0); k += 1 }
    put(1)
    java.util.Arrays.copyOf(out, p)
