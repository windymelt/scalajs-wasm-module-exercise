package bench

import scala.concurrent.duration.*
import wasmisolated.*
import imaging.*

// 画像処理 (shared/src/main/scala/imaging) を wasmIsolated で隔離して動かす
//   sbt "demo/runMain bench.images"      動作の確認、細工した画像、計測
//   sbt "demo/runMain bench.imagesJvm"   比べるために、細工した画像を JVM でそのまま処理する

/** 画像を読み、ぼかしてグレースケールにし、QOI で返す。処理は Wasm の別ヒープの中で動く */
def grayBlurIsolated(input: Array[Byte], radius: Int)(using Limits): Array[Byte] =
  wasmIsolated { imaging.Convert.grayBlur(input, radius) }

/** 同じ処理を JVM でそのまま動かす */
def grayBlurJvm(input: Array[Byte], radius: Int): Array[Byte] = Convert.grayBlur(input, radius)

/** 受け渡しだけ (入力を持ち込み、出力と同じ長さの配列を返す) */
def transferOnly(input: Array[Byte], outLength: Int)(using Limits): Array[Byte] =
  wasmIsolated:
    val n = input.length
    new Array[Byte](outLength)

/** 試しに使う画像 (色の傾きと円) */
def sampleImage(w: Int, h: Int): Rgba =
  val px = new Array[Byte](w * h * 4)
  for y <- 0 until h; x <- 0 until w do
    val o = (y * w + x) * 4
    val dx = x - w / 2; val dy = y - h / 2
    val inCircle = dx * dx + dy * dy < (w * w) / 9
    px(o) = (if inCircle then 230 else x * 255 / w).toByte
    px(o + 1) = (if inCircle then 120 else y * 255 / h).toByte
    px(o + 2) = (if inCircle then 40 else 160).toByte
    px(o + 3) = 255.toByte
  Rgba(w, h, px)

/** 幅と高さだけが大きい QOI。中身は「直前の画素を 62 回繰り返す」命令が並ぶだけ */
def qoiBomb(w: Int, h: Int, runs: Int): Array[Byte] =
  val header = Array[Byte]('q', 'o', 'i', 'f') ++ java.nio.ByteBuffer.allocate(8).putInt(w).putInt(h).array() ++ Array[Byte](4, 0)
  header ++ Array.fill(runs)(0xfd.toByte) ++ Array[Byte](0, 0, 0, 0, 0, 0, 0, 1)

/** JVM の ImageIO で PNG に書く (試しに使う入力を作るため) */
def toPng(img: Rgba): Array[Byte] =
  val bi = new java.awt.image.BufferedImage(img.width, img.height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
  for y <- 0 until img.height; x <- 0 until img.width do
    val o = (y * img.width + x) * 4
    val p = img.pixels
    bi.setRGB(x, y, ((p(o + 3) & 0xff) << 24) | ((p(o) & 0xff) << 16) | ((p(o + 1) & 0xff) << 8) | (p(o + 2) & 0xff))
  val out = new java.io.ByteArrayOutputStream()
  javax.imageio.ImageIO.write(bi, "png", out)
  out.toByteArray

/** 真っ黒な w x h のグレースケール PNG。中身は 0 が並ぶだけなので、zlib で 1000 分の 1 ほどに縮む */
def pngBomb(w: Int, h: Int): Array[Byte] =
  val idat = new java.io.ByteArrayOutputStream()
  val z = new java.util.zip.DeflaterOutputStream(idat, new java.util.zip.Deflater(9), 1 << 16)
  val row = new Array[Byte](w + 1) // 先頭はフィルターの種類 (0 = なし)
  for _ <- 0 until h do z.write(row)
  z.close()
  val out = new java.io.ByteArrayOutputStream()
  out.write(Array[Byte](0x89.toByte, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a))
  def chunk(tpe: String, data: Array[Byte]): Unit =
    val crc = new java.util.zip.CRC32()
    crc.update(tpe.getBytes("US-ASCII")); crc.update(data)
    out.write(java.nio.ByteBuffer.allocate(4).putInt(data.length).array())
    out.write(tpe.getBytes("US-ASCII")); out.write(data)
    out.write(java.nio.ByteBuffer.allocate(4).putInt(crc.getValue.toInt).array())
  chunk("IHDR", java.nio.ByteBuffer.allocate(13).putInt(w).putInt(h).put(8.toByte).put(0.toByte).put(0.toByte).put(0.toByte).put(0.toByte).array())
  chunk("IDAT", idat.toByteArray)
  chunk("IEND", Array.empty)
  out.toByteArray

/** ヘッダーが途中で切れた PPM (Pnm のわざと残したバグで無限ループになる) */
val truncatedPpm: Array[Byte] = "P6\n# made by bench.images\n64 64".getBytes("US-ASCII")

def median(xs: Seq[Double]): Double = xs.sorted.apply(xs.size / 2)
def timeMs[A](body: => A): (A, Double) =
  val t = System.nanoTime(); val a = body; (a, (System.nanoTime() - t) / 1e6)

@main def images(): Unit =
  given Limits = Limits(timeout = 2.seconds, maxHeap = "64MB")
  def attempt(label: String)(body: => String): Unit =
    val (r, ms) = timeMs(try body catch case e: IsolationFailure => s"止めた (${e.getMessage})")
    println(f"$label: $r ($ms%.0f ms)")

  // 1. 普通の画像
  val img = sampleImage(64, 64)
  val qoi = Qoi.encode(img)
  val ppm = Pnm.encodePpm(img)
  println(s"QOI の往復で元に戻る: ${Qoi.decode(qoi).pixels.sameElements(img.pixels)}")
  println(s"64x64 の QOI は ${qoi.length} バイト、PPM は ${ppm.length} バイト")
  attempt("64x64 の QOI をぼかしてグレースケールに") {
    val out = grayBlurIsolated(qoi, 2)
    s"${out.length} バイト、JVM で処理した結果と一致: ${out.sameElements(grayBlurJvm(qoi, 2))}"
  }
  val pngIn = toPng(img)
  attempt(s"64x64 の PNG (${pngIn.length} バイト) を同じく") {
    val out = grayBlurIsolated(pngIn, 2)
    s"${out.length} バイト、JVM の結果と一致: ${out.sameElements(grayBlurJvm(pngIn, 2))}"
  }
  attempt("64x64 の PPM を同じく") {
    val out = grayBlurIsolated(ppm, 2)
    s"${out.length} バイト、JVM の結果と一致: ${out.sameElements(grayBlurJvm(ppm, 2))}"
  }

  // 2. 細工した画像
  val bomb = qoiBomb(16000, 16000, 64)
  attempt(s"16000x16000 と書いてある ${bomb.length} バイトの QOI")(s"${grayBlurIsolated(bomb, 2).length} バイト")
  attempt("そのあとの 64x64 の QOI")(s"${grayBlurIsolated(qoi, 2).length} バイト")
  val mid = qoiBomb(2000, 2000, 64)
  attempt(s"2000x2000 と書いてある ${mid.length} バイトの QOI")(s"${grayBlurIsolated(mid, 2).length} バイト")
  val bigPng = pngBomb(16000, 16000)
  attempt(s"展開すると 256 MB になる 16000x16000 の PNG (${bigPng.length} バイト)")(s"${grayBlurIsolated(bigPng, 2).length} バイト")
  attempt("そのあとの 64x64 の QOI")(s"${grayBlurIsolated(qoi, 2).length} バイト")
  val midPng = pngBomb(2000, 2000)
  attempt(s"展開すると 4 MB になる 2000x2000 の PNG (${midPng.length} バイト)")(s"${grayBlurIsolated(midPng, 2).length} バイト")
  attempt("ヘッダーが途中で切れた PPM")(s"${grayBlurIsolated(truncatedPpm, 2).length} バイト")
  attempt("そのあとの 64x64 の QOI")(s"${grayBlurIsolated(qoi, 2).length} バイト")

  // 3. 大きさごとの時間 (JIT が温まってから。各 7 回の中央値)
  for _ <- 1 to 200 do grayBlurJvm(Qoi.encode(sampleImage(64, 64)), 2)
  for _ <- 1 to 20 do grayBlurIsolated(Qoi.encode(sampleImage(64, 64)), 2)
  println("| 画像 | 入力 | 出力 | JVM | wasmIsolated | うち受け渡し |")
  println("|---|---|---|---|---|---|")
  for size <- Seq(16, 32, 64, 128, 256); format <- Seq("QOI", "PNG", "PPM") do
    val src = sampleImage(size, size)
    val input = format match
      case "QOI" => Qoi.encode(src)
      case "PNG" => toPng(src)
      case _ => Pnm.encodePpm(src)
    val expected = grayBlurJvm(input, 2)
    val jvm = median((1 to 7).map(_ => timeMs(grayBlurJvm(input, 2))._2))
    val wasm = median((1 to 7).map { _ =>
      val (out, ms) = timeMs(grayBlurIsolated(input, 2))
      require(out.sameElements(expected), "JVM と結果が違う")
      ms
    })
    val transfer = median((1 to 7).map(_ => timeMs(transferOnly(input, expected.length))._2))
    println(f"| ${size}x$size $format | ${input.length}%,d B | ${expected.length}%,d B | $jvm%.2f ms | $wasm%.1f ms | $transfer%.1f ms |")

@main def imagesJvm(): Unit =
  // 細工した QOI を JVM でそのまま処理する
  val rt = Runtime.getRuntime
  println(f"JVM のヒープの上限: ${rt.maxMemory / 1e6}%.0f MB")
  val (r, ms) = timeMs(try s"${grayBlurJvm(qoiBomb(16000, 16000, 64), 2).length} バイト" catch case e: OutOfMemoryError => s"OutOfMemoryError (${e.getMessage})")
  println(f"16000x16000 と書いてある QOI: $r ($ms%.0f ms)")
  val (r2, ms2) = timeMs(try s"${grayBlurJvm(pngBomb(16000, 16000), 2).length} バイト" catch case e: OutOfMemoryError => s"OutOfMemoryError (${e.getMessage})")
  println(f"展開すると 256 MB になる 16000x16000 の PNG: $r2 ($ms2%.0f ms)")
  // ヘッダーが途中で切れた PPM を別のスレッドで処理し、interrupt してみる
  val t = new Thread(() => { grayBlurJvm(truncatedPpm, 2); () })
  t.setDaemon(true)
  t.start()
  Thread.sleep(1000)
  t.interrupt()
  Thread.sleep(1000)
  println(s"ヘッダーが途中で切れた PPM: interrupt から 1 秒後もスレッドが動いている: ${t.isAlive}")

/** ファイルを変換する (sbt "demo/runMain bench.convertFile 入力 出力 [半径]")。入力は QOI か P5/P6 の PNM、出力は QOI */
@main def convertFile(in: String, out: String, radius: Int = 2): Unit =
  given Limits = Limits(timeout = 2.seconds, maxHeap = "64MB")
  val input = java.nio.file.Files.readAllBytes(java.nio.file.Path.of(in))
  val result = grayBlurIsolated(input, radius)
  java.nio.file.Files.write(java.nio.file.Path.of(out), result)
  println(s"$in (${input.length} バイト) → $out (${result.length} バイト)")

/**
 * PNG と DEFLATE の読み込みを、別の実装と比べる (sbt "demo/runMain bench.pngCheck ディレクトリ")。
 * ディレクトリの x.png と、別の実装で求めた RGBA の x.rgba を比べ、wasmIsolated の結果が JVM と同じかも確かめる
 */
@main def pngCheck(dir: String): Unit =
  given Limits = Limits(timeout = 2.seconds, maxHeap = "64MB")
  // 1. DEFLATE: java.util.zip で縮めたものを自前で展開する
  val rnd = new scala.util.Random(1)
  var inflateOk = 0; var inflateAll = 0
  for size <- Seq(0, 1, 10, 300, 5000, 70000, 300000); kind <- Seq("random", "text", "zeros");
      (level, strategy) <- Seq((0, 0), (1, 0), (6, 0), (9, 0), (6, java.util.zip.Deflater.FILTERED), (6, java.util.zip.Deflater.HUFFMAN_ONLY)) do
    val data = kind match
      case "random" => Array.fill(size)(rnd.nextInt(256).toByte)
      case "text" => Array.tabulate(size)(i => "sushi and beer, ".charAt((i * 7 + i / 13) % 16).toByte)
      case _ => new Array[Byte](size)
    val d = new java.util.zip.Deflater(level)
    d.setStrategy(strategy)
    val out = new java.io.ByteArrayOutputStream()
    val z = new java.util.zip.DeflaterOutputStream(out, d)
    z.write(data); z.close()
    inflateAll += 1
    if Inflate.zlib(out.toByteArray, data.length.max(1)).sameElements(data) then inflateOk += 1
    else println(s"展開が合わない: $size バイト $kind レベル $level 方式 $strategy")
  println(s"DEFLATE の展開: $inflateAll 通り中 $inflateOk 通りが一致")

  // 2. PNG: ほかの実装で書いた PNG と、そこで求めた RGBA
  val files = java.io.File(dir).listFiles().filter(_.getName.endsWith(".png")).sortBy(_.getName)
  for f <- files do
    val in = java.nio.file.Files.readAllBytes(f.toPath)
    val expected = java.nio.file.Files.readAllBytes(java.nio.file.Path.of(f.getPath.stripSuffix(".png") + ".rgba"))
    val img = Png.decode(in)
    val same = grayBlurIsolated(in, 1).sameElements(grayBlurJvm(in, 1))
    println(s"${f.getName} (${img.width}x${img.height}): 画素が一致 ${img.pixels.sameElements(expected)}、wasmIsolated と JVM が一致 $same")

  // 3. JVM の ImageIO で書いた PNG (行ごとにフィルターを選ぶ) を、ImageIO で読んだ画素と比べる
  for (label, tpe) <- Seq("ARGB" -> java.awt.image.BufferedImage.TYPE_INT_ARGB, "RGB" -> java.awt.image.BufferedImage.TYPE_3BYTE_BGR,
                          "パレット" -> java.awt.image.BufferedImage.TYPE_BYTE_INDEXED, "1 ビット" -> java.awt.image.BufferedImage.TYPE_BYTE_BINARY) do
    val bi = new java.awt.image.BufferedImage(97, 61, tpe)
    for y <- 0 until 61; x <- 0 until 97 do
      val a = if tpe == java.awt.image.BufferedImage.TYPE_INT_ARGB then (x * 5 + y) % 256 else 255
      bi.setRGB(x, y, (a << 24) | ((x * 37 + y * 11) % 256 << 16) | ((y * 29) % 256 << 8) | (rnd.nextInt(4) * 60))
    val out = new java.io.ByteArrayOutputStream()
    javax.imageio.ImageIO.write(bi, "png", out)
    val img = Png.decode(out.toByteArray)
    var ok = true
    for y <- 0 until 61; x <- 0 until 97 do
      val o = (y * 97 + x) * 4
      val got = ((img.pixels(o + 3) & 0xff) << 24) | ((img.pixels(o) & 0xff) << 16) | ((img.pixels(o + 1) & 0xff) << 8) | (img.pixels(o + 2) & 0xff)
      if got != bi.getRGB(x, y) then ok = false
    println(s"ImageIO で書いた $label の PNG (${out.size} バイト): 画素が一致 $ok")
