package img

import java.util.Arrays

/** 伸長可能なバイト列 */
final class Bytes(initial: Int = 1024) {
  private var buf = new Array[Byte](initial)
  private var pos = 0

  def size: Int = pos
  def byte(b: Int): Unit = {
    if (pos == buf.length) buf = Arrays.copyOf(buf, buf.length * 2)
    buf(pos) = b.toByte
    pos += 1
  }
  def int32BE(v: Int): Unit = { byte(v >>> 24); byte(v >>> 16); byte(v >>> 8); byte(v) }
  def bytes(a: Array[Byte]): Unit = a.foreach(b => byte(b))
  def result(): Array[Byte] = Arrays.copyOf(buf, pos)
}

/** java.util.zip が無いので Deflate (固定ハフマン + LZ77) を自前で書く */
object Deflate {
  private val LenBase  = Array(3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31, 35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258)
  private val LenExtra = Array(0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0)
  private val DistBase = Array(1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193, 257, 385, 513, 769, 1025, 1537,
    2049, 3073, 4097, 6145, 8193, 12289, 16385, 24577)
  private val DistExtra = Array(0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13)

  private final val WindowSize = 32768
  private final val MaxChain = 48

  /** LSB ファーストのビット書き込み */
  private final class BitWriter {
    val out = new Bytes(1 << 16)
    private var acc = 0L
    private var n = 0

    def bits(v: Int, len: Int): Unit = {
      acc |= (v.toLong & ((1L << len) - 1)) << n
      n += len
      while (n >= 8) { out.byte((acc & 0xff).toInt); acc >>>= 8; n -= 8 }
    }
    /** ハフマン符号は MSB ファーストなので反転して書く */
    def huff(code: Int, len: Int): Unit = bits(Integer.reverse(code) >>> (32 - len), len)
    def align(): Unit = if (n > 0) { out.byte((acc & 0xff).toInt); acc = 0; n = 0 }
  }

  private def writeSym(w: BitWriter, sym: Int): Unit =
    if (sym < 144) w.huff(0x30 + sym, 8)
    else if (sym < 256) w.huff(0x190 + sym - 144, 9)
    else if (sym < 280) w.huff(sym - 256, 7)
    else w.huff(0xc0 + sym - 280, 8)

  private def writeMatch(w: BitWriter, len: Int, dist: Int): Unit = {
    var li = LenBase.length - 1
    while (LenBase(li) > len) li -= 1
    writeSym(w, 257 + li)
    w.bits(len - LenBase(li), LenExtra(li))
    var di = DistBase.length - 1
    while (DistBase(di) > dist) di -= 1
    w.huff(di, 5)
    w.bits(dist - DistBase(di), DistExtra(di))
  }

  private def adler32(data: Array[Byte]): Int = {
    var a = 1
    var b = 0
    var i = 0
    while (i < data.length) {
      a = (a + (data(i) & 0xff)) % 65521
      b = (b + a) % 65521
      i += 1
    }
    (b << 16) | a
  }

  def zlib(data: Array[Byte]): Array[Byte] = {
    val w = new BitWriter
    w.bits(0x78, 8); w.bits(0x01, 8) // zlib ヘッダ
    w.bits(1, 1); w.bits(1, 2)       // BFINAL=1, BTYPE=01 (固定ハフマン)

    val n = data.length
    val head = Array.fill(1 << 15)(-1)
    val prev = new Array[Int](WindowSize)
    def hash(i: Int): Int =
      (((data(i) & 0xff) << 10) ^ ((data(i + 1) & 0xff) << 5) ^ (data(i + 2) & 0xff)) & 0x7fff
    def insert(i: Int): Unit = if (i + 2 < n) {
      val h = hash(i)
      prev(i & (WindowSize - 1)) = head(h)
      head(h) = i
    }

    var i = 0
    while (i < n) {
      var bestLen = 0
      var bestDist = 0
      if (i + 2 < n) {
        val max = Math.min(258, n - i)
        var cand = head(hash(i))
        var chain = MaxChain
        while (cand >= 0 && i - cand <= WindowSize && chain > 0) {
          var l = 0
          while (l < max && data(cand + l) == data(i + l)) l += 1
          if (l > bestLen) { bestLen = l; bestDist = i - cand }
          val next = prev(cand & (WindowSize - 1))
          cand = if (l == max || next >= cand) -1 else next
          chain -= 1
        }
      }
      if (bestLen >= 3) {
        writeMatch(w, bestLen, bestDist)
        var k = 0
        while (k < bestLen) { insert(i + k); k += 1 }
        i += bestLen
      } else {
        writeSym(w, data(i) & 0xff)
        insert(i)
        i += 1
      }
    }
    writeSym(w, 256) // ブロック終端
    w.align()
    w.out.int32BE(adler32(data))
    w.out.result()
  }
}

object Png {
  private val CrcTable: Array[Int] = Array.tabulate(256) { n =>
    var c = n
    var k = 0
    while (k < 8) { c = if ((c & 1) != 0) 0xedb88320 ^ (c >>> 1) else c >>> 1; k += 1 }
    c
  }

  private def crc32(data: Array[Byte]): Int = {
    var c = 0xffffffff
    var i = 0
    while (i < data.length) { c = CrcTable((c ^ data(i)) & 0xff) ^ (c >>> 8); i += 1 }
    ~c
  }

  private def paeth(a: Int, b: Int, c: Int): Int = {
    val p = a + b - c
    val pa = Math.abs(p - a); val pb = Math.abs(p - b); val pc = Math.abs(p - c)
    if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
  }

  /** 行ごとに 5 種類のフィルタを試し、残差の絶対値和が最小のものを選ぶ */
  private def filter(w: Int, h: Int, rgb: Array[Int]): Array[Byte] = {
    val stride = w * 3
    val out = new Array[Byte]((stride + 1) * h)
    val cur = new Array[Int](stride)
    val prev = new Array[Int](stride) // 1 行目の「前の行」は 0
    val cand = Array.ofDim[Byte](5, stride)
    var y = 0
    while (y < h) {
      var x = 0
      while (x < w) {
        val p = rgb(y * w + x)
        cur(x * 3) = (p >> 16) & 0xff; cur(x * 3 + 1) = (p >> 8) & 0xff; cur(x * 3 + 2) = p & 0xff
        x += 1
      }
      var best = 0
      var bestScore = Long.MaxValue
      var f = 0
      while (f < 5) {
        var score = 0L
        var i = 0
        while (i < stride) {
          val a = if (i >= 3) cur(i - 3) else 0
          val b = prev(i)
          val c = if (i >= 3) prev(i - 3) else 0
          val pred = f match {
            case 0 => 0
            case 1 => a
            case 2 => b
            case 3 => (a + b) >>> 1
            case _ => paeth(a, b, c)
          }
          val v = (cur(i) - pred) & 0xff
          cand(f)(i) = v.toByte
          score += (if (v < 128) v else 256 - v)
          i += 1
        }
        if (score < bestScore) { bestScore = score; best = f }
        f += 1
      }
      out(y * (stride + 1)) = best.toByte
      System.arraycopy(cand(best), 0, out, y * (stride + 1) + 1, stride)
      System.arraycopy(cur, 0, prev, 0, stride)
      y += 1
    }
    out
  }

  private def chunk(out: Bytes, tpe: String, data: Array[Byte]): Unit = {
    val body = new Bytes(data.length + 4)
    tpe.foreach(c => body.byte(c.toInt))
    body.bytes(data)
    val b = body.result()
    out.int32BE(data.length)
    out.bytes(b)
    out.int32BE(crc32(b))
  }

  /** rgb: 1 画素 = 0xRRGGBB */
  def encode(w: Int, h: Int, rgb: Array[Int]): Array[Byte] = {
    val out = new Bytes(1 << 16)
    Array(0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a).foreach(b => out.byte(b.toInt))
    val ihdr = new Bytes(13)
    ihdr.int32BE(w); ihdr.int32BE(h)
    ihdr.byte(8) // ビット深度
    ihdr.byte(2) // カラータイプ: RGB
    ihdr.byte(0); ihdr.byte(0); ihdr.byte(0)
    chunk(out, "IHDR", ihdr.result())
    chunk(out, "IDAT", Deflate.zlib(filter(w, h, rgb)))
    chunk(out, "IEND", Array.emptyByteArray)
    out.result()
  }
}
