package imaging

/** RGBA 8 bit の画像。1 画素 4 バイトで、左上から行ごとに並ぶ */
final class Rgba(val width: Int, val height: Int, val pixels: Array[Byte])

/** 画像として読めなかった */
final class DecodeError(message: String) extends RuntimeException(message)

/** 外部の画像ライブラリを使わずに書いた、小さな画像処理 */
object Ops:

  /** グレースケールにする (ITU-R BT.601 の係数を 256 倍した整数で近似する) */
  def grayscale(img: Rgba): Rgba =
    val src = img.pixels
    val dst = new Array[Byte](src.length)
    var i = 0
    while i < src.length do
      val y = (77 * (src(i) & 0xff) + 150 * (src(i + 1) & 0xff) + 29 * (src(i + 2) & 0xff)) >> 8
      dst(i) = y.toByte
      dst(i + 1) = y.toByte
      dst(i + 2) = y.toByte
      dst(i + 3) = src(i + 3)
      i += 4
    Rgba(img.width, img.height, dst)

  /** 半径 radius の箱型のぼかし。横と縦に分けてかける。端は端の画素を伸ばして扱う */
  def boxBlur(img: Rgba, radius: Int): Rgba =
    if radius <= 0 then img
    else
      val tmp = pass(img.pixels, img.width, img.height, radius, horizontal = true)
      Rgba(img.width, img.height, pass(tmp, img.width, img.height, radius, horizontal = false))

  private def pass(src: Array[Byte], w: Int, h: Int, r: Int, horizontal: Boolean): Array[Byte] =
    val dst = new Array[Byte](src.length)
    val len = if horizontal then w else h
    val lines = if horizontal then h else w
    val n = 2 * r + 1
    var line = 0
    while line < lines do
      var c = 0
      while c < 4 do
        // 窓の中の合計を、1 画素ずつずらしながら足し引きする
        def at(k: Int): Int =
          val kk = if k < 0 then 0 else if k >= len then len - 1 else k
          val idx = if horizontal then (line * w + kk) * 4 + c else (kk * w + line) * 4 + c
          src(idx) & 0xff
        var sum = 0
        var k = -r
        while k <= r do { sum += at(k); k += 1 }
        var x = 0
        while x < len do
          val idx = if horizontal then (line * w + x) * 4 + c else (x * w + line) * 4 + c
          dst(idx) = ((sum + n / 2) / n).toByte
          sum += at(x + r + 1) - at(x - r)
          x += 1
        c += 1
      line += 1
    dst

/** 画像のバイト列を読み、ぼかしてグレースケールにし、QOI で返す。形式は先頭のバイトで見分ける */
object Convert:
  def grayBlur(input: Array[Byte], radius: Int): Array[Byte] =
    val img =
      if input.length >= 4 && input(0) == 'q' && input(1) == 'o' && input(2) == 'i' && input(3) == 'f' then Qoi.decode(input)
      else if input.length >= 2 && input(0) == 'P' then Pnm.decode(input)
      else throw DecodeError("知らない形式です")
    Qoi.encode(Ops.grayscale(Ops.boxBlur(img, radius)))
