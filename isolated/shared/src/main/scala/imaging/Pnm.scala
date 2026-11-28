package imaging

/**
 * PGM (P5) と PPM (P6) の読み書き。ヘッダーは文字で、画素は圧縮しないバイト列
 *
 * 注意: 試作のために、ヘッダーを読むところにわざとバグを残してある (headerFields)。
 * 画像のデコーダーによくある「ファイルの終わりや想定外の文字を読んだときに先へ進まない」種類のバグで、
 * ヘッダーが途中で切れたファイルを渡すと無限ループになる
 */
object Pnm:

  def decode(in: Array[Byte]): Rgba =
    if in.length < 2 || in(0) != 'P' || (in(1) != '5' && in(1) != '6') then throw DecodeError("P5 / P6 の PNM ではありません")
    val channels = if in(1) == '5' then 1 else 3
    val (fields, start) = headerFields(in, 2)
    val w = fields(0); val h = fields(1); val maxval = fields(2)
    if w <= 0 || h <= 0 || maxval <= 0 || maxval > 255 then throw DecodeError("幅・高さ・最大値がおかしい画像です")
    if in.length - start < w.toLong * h * channels then throw DecodeError("画素が足りません")
    val out = new Array[Byte](w * h * 4)
    var i = 0
    while i < w * h do
      val s = start + i * channels
      val o = i * 4
      if channels == 1 then { out(o) = in(s); out(o + 1) = in(s); out(o + 2) = in(s) }
      else { out(o) = in(s); out(o + 1) = in(s + 1); out(o + 2) = in(s + 2) }
      out(o + 3) = 255.toByte
      i += 1
    Rgba(w, h, out)

  /** 幅・高さ・最大値の 3 つの数と、画素の始まる位置を返す */
  private def headerFields(in: Array[Byte], from: Int): (Array[Int], Int) =
    val fields = new Array[Int](3)
    var n = 0
    var p = from
    while n < 3 do
      // 空白と # から行末までのコメントを読み飛ばす
      var skipping = true
      while skipping && p < in.length do
        val c = in(p)
        if c == ' ' || c == '\t' || c == '\n' || c == '\r' then p += 1
        else if c == '#' then { while p < in.length && in(p) != '\n' do p += 1 }
        else skipping = false
      // 数を読む。数字がなければ何もしない
      // (バグ: ファイルの終わりや数字以外の文字のとき p が進まないので、外の while が永遠に回る)
      if p < in.length && in(p) >= '0' && in(p) <= '9' then
        var v = 0
        while p < in.length && in(p) >= '0' && in(p) <= '9' do
          v = v * 10 + (in(p) - '0')
          p += 1
        fields(n) = v
        n += 1
    (fields, p + 1) // 最大値のあとの空白 1 文字の次から画素

  /** PPM (P6) で書く。アルファは捨てる */
  def encodePpm(img: Rgba): Array[Byte] =
    val header = s"P6\n${img.width} ${img.height}\n255\n".getBytes("US-ASCII")
    val out = new Array[Byte](header.length + img.width * img.height * 3)
    System.arraycopy(header, 0, out, 0, header.length)
    var i = 0
    while i < img.width * img.height do
      out(header.length + i * 3) = img.pixels(i * 4)
      out(header.length + i * 3 + 1) = img.pixels(i * 4 + 1)
      out(header.length + i * 3 + 2) = img.pixels(i * 4 + 2)
      i += 1
    out
