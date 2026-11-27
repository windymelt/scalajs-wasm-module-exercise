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
  attempt("ヘッダーが途中で切れた PPM")(s"${grayBlurIsolated(truncatedPpm, 2).length} バイト")
  attempt("そのあとの 64x64 の QOI")(s"${grayBlurIsolated(qoi, 2).length} バイト")

  // 3. 大きさごとの時間 (JIT が温まってから。各 7 回の中央値)
  for _ <- 1 to 200 do grayBlurJvm(Qoi.encode(sampleImage(64, 64)), 2)
  for _ <- 1 to 20 do grayBlurIsolated(Qoi.encode(sampleImage(64, 64)), 2)
  println("| 画像 | 入力 | 出力 | JVM | wasmIsolated | うち受け渡し |")
  println("|---|---|---|---|---|---|")
  for size <- Seq(16, 32, 64, 128, 256); format <- Seq("QOI", "PPM") do
    val src = sampleImage(size, size)
    val input = if format == "QOI" then Qoi.encode(src) else Pnm.encodePpm(src)
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
