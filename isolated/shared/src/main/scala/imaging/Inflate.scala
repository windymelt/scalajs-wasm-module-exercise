package imaging

/**
 * zlib (RFC 1950) と DEFLATE (RFC 1951) の展開。Scala.js の標準ライブラリには java.util.zip が無いので自前で書く。
 * ハフマン符号は 1 ビットずつ読んで引く (zlib に付属する puff.c と同じやりかた)。速さより短さを取っている
 */
object Inflate:

  private val LengthBase = Array(3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31, 35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258)
  private val LengthExtra = Array(0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0)
  private val DistBase = Array(1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193, 257, 385, 513, 769, 1025, 1537, 2049, 3073, 4097, 6145, 8193, 12289, 16385, 24577)
  private val DistExtra = Array(0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13)
  private val CodeLengthOrder = Array(16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15)
  private val MaxBits = 15

  /** 符号の長さごとの数と、長さ順に並べた記号 */
  private final class Huffman(lengths: Array[Int], n: Int):
    val count = new Array[Int](MaxBits + 1)
    val symbol = new Array[Int](n)
    locally {
      var s = 0
      while s < n do { count(lengths(s)) += 1; s += 1 }
      count(0) = 0
      // 符号が多すぎないか (足りないのは、記号が 1 つだけの距離の符号などで許される)
      var left = 1
      var len = 1
      while len <= MaxBits do
        left = (left << 1) - count(len)
        if left < 0 then throw DecodeError("ハフマン符号がおかしいデータです")
        len += 1
      val offs = new Array[Int](MaxBits + 2)
      len = 1
      while len <= MaxBits do { offs(len + 1) = offs(len) + count(len); len += 1 }
      s = 0
      while s < n do
        if lengths(s) != 0 then { symbol(offs(lengths(s))) = s; offs(lengths(s)) += 1 }
        s += 1
    }

  private lazy val fixedLit: Huffman =
    val l = new Array[Int](288)
    var i = 0
    while i < 288 do { l(i) = if i < 144 then 8 else if i < 256 then 9 else if i < 280 then 7 else 8; i += 1 }
    Huffman(l, 288)
  private lazy val fixedDist: Huffman = Huffman(Array.fill(30)(5), 30)

  /** 伸びるバイト列。limit を超えて書こうとしたら止める */
  private final class Out(initial: Int, limit: Int):
    var buf = new Array[Byte](initial max 64)
    var size = 0
    def put(b: Int): Unit =
      if size >= limit then throw DecodeError("展開したデータが想定より長いです")
      if size == buf.length then
        buf = java.util.Arrays.copyOf(buf, (size.toLong * 2).min(limit.toLong).toInt.max(size + 1))
      buf(size) = b.toByte
      size += 1

  /** zlib の形式のデータを展開する。展開後の長さが maxOut を超えたら DecodeError */
  def zlib(in: Array[Byte], maxOut: Int): Array[Byte] =
    if in.length < 6 then throw DecodeError("zlib のデータが短すぎます")
    val cmf = in(0) & 0xff
    val flg = in(1) & 0xff
    if (cmf & 0x0f) != 8 || ((cmf << 8) | flg) % 31 != 0 || (flg & 0x20) != 0 then throw DecodeError("zlib のヘッダーがおかしいです")
    val r = new Reader(in, 2)
    val out = new Out(in.length * 4 min maxOut, maxOut)
    var last = false
    while !last do
      last = r.bits(1) == 1
      r.bits(2) match
        case 0 => stored(r, out)
        case 1 => codes(r, out, fixedLit, fixedDist)
        case 2 =>
          val (lit, dist) = dynamic(r)
          codes(r, out, lit, dist)
        case _ => throw DecodeError("DEFLATE のブロックの種類がおかしいです")
    // 末尾の Adler-32 を確かめる
    val p = r.alignedPos
    if p + 4 > in.length then throw DecodeError("zlib のデータが途中で切れています")
    val expected = ((in(p) & 0xffL) << 24) | ((in(p + 1) & 0xffL) << 16) | ((in(p + 2) & 0xffL) << 8) | (in(p + 3) & 0xffL)
    var a = 1L; var b = 0L; var i = 0
    while i < out.size do { a = (a + (out.buf(i) & 0xff)) % 65521; b = (b + a) % 65521; i += 1 }
    if ((b << 16) | a) != expected then throw DecodeError("zlib のチェックサムが合いません")
    java.util.Arrays.copyOf(out.buf, out.size)

  /** 下位のビットから読む */
  private final class Reader(in: Array[Byte], start: Int):
    private var pos = start
    private var bitBuf = 0
    private var bitCnt = 0
    def bits(n: Int): Int =
      var v = bitBuf
      while bitCnt < n do
        if pos >= in.length then throw DecodeError("DEFLATE のデータが途中で切れています")
        v |= (in(pos) & 0xff) << bitCnt
        pos += 1
        bitCnt += 8
      bitBuf = v >>> n
      bitCnt -= n
      v & ((1 << n) - 1)
    /** 残りのビットを捨てて、次のバイトの位置を返す */
    def alignedPos: Int = { bitBuf = 0; bitCnt = 0; pos }
    def byte(): Int =
      if pos >= in.length then throw DecodeError("DEFLATE のデータが途中で切れています")
      val b = in(pos) & 0xff
      pos += 1
      b
    def decode(h: Huffman): Int =
      var code = 0; var first = 0; var index = 0
      var len = 1
      while len <= MaxBits do
        code |= bits(1)
        val c = h.count(len)
        if code - c < first then return h.symbol(index + (code - first))
        index += c; first += c; first <<= 1; code <<= 1
        len += 1
      throw DecodeError("ハフマン符号に無い符号です")

  private def stored(r: Reader, out: Out): Unit =
    r.alignedPos
    val len = r.byte() | (r.byte() << 8)
    val nlen = r.byte() | (r.byte() << 8)
    if (len ^ 0xffff) != nlen then throw DecodeError("無圧縮のブロックの長さがおかしいです")
    var i = 0
    while i < len do { out.put(r.byte()); i += 1 }

  private def codes(r: Reader, out: Out, lit: Huffman, dist: Huffman): Unit =
    var sym = r.decode(lit)
    while sym != 256 do
      if sym < 256 then out.put(sym)
      else
        val li = sym - 257
        if li >= 29 then throw DecodeError("長さの記号がおかしいです")
        val len = LengthBase(li) + r.bits(LengthExtra(li))
        val di = r.decode(dist)
        if di >= 30 then throw DecodeError("距離の記号がおかしいです")
        val d = DistBase(di) + r.bits(DistExtra(di))
        if d > out.size then throw DecodeError("まだ無いところを指しています")
        var k = 0
        while k < len do { out.put(out.buf(out.size - d)); k += 1 }
      sym = r.decode(lit)

  private def dynamic(r: Reader): (Huffman, Huffman) =
    val nlen = r.bits(5) + 257
    val ndist = r.bits(5) + 1
    val ncode = r.bits(4) + 4
    if nlen > 286 || ndist > 30 then throw DecodeError("符号の数がおかしいです")
    val lengths = new Array[Int](320)
    var i = 0
    while i < ncode do { lengths(CodeLengthOrder(i)) = r.bits(3); i += 1 }
    val lencode = Huffman(lengths, 19)
    java.util.Arrays.fill(lengths, 0)
    i = 0
    while i < nlen + ndist do
      val sym = r.decode(lencode)
      if sym < 16 then { lengths(i) = sym; i += 1 }
      else
        val (v, rep) = sym match
          case 16 =>
            if i == 0 then throw DecodeError("繰り返す長さがありません")
            (lengths(i - 1), 3 + r.bits(2))
          case 17 => (0, 3 + r.bits(3))
          case _ => (0, 11 + r.bits(7))
        if i + rep > nlen + ndist then throw DecodeError("符号の長さが多すぎます")
        var k = 0
        while k < rep do { lengths(i) = v; i += 1; k += 1 }
    if lengths(256) == 0 then throw DecodeError("ブロックの終わりの符号がありません")
    (Huffman(lengths.take(nlen), nlen), Huffman(lengths.slice(nlen, nlen + ndist), ndist))
