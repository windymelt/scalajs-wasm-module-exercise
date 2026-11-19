package img

object Fractal {
  private[img] def clamp255(v: Double): Int = (Math.max(0.0, Math.min(1.0, v)) * 255 + 0.5).toInt
  private[img] def rgb(c: V): Int = (clamp255(c.x) << 16) | (clamp255(c.y) << 8) | clamp255(c.z)

  /** 発散までの滑らかな反復回数。発散しなければ -1 */
  private[img] def smoothIter(zr0: Double, zi0: Double, cr: Double, ci: Double, maxIter: Int): Double = {
    var zr = zr0
    var zi = zi0
    var i = 0
    while (i < maxIter && zr * zr + zi * zi < 256.0) {
      val t = zr * zr - zi * zi + cr
      zi = 2 * zr * zi + ci
      zr = t
      i += 1
    }
    if (i == maxIter) -1.0
    else {
      // n + 1 - log2(log|z|)
      val logZ = 0.5 * MathX.log(zr * zr + zi * zi)
      i + 1 - MathX.log(logZ) / MathX.Ln2
    }
  }
}

/** ジュリア集合。zoom は高さ方向に見える範囲 (3.0 / zoom) を決める */
final class JuliaJob(w: Int, h: Int, cr: Double, ci: Double, zoom: Double, centerX: Double, centerY: Double) {
  private final val MaxIter = 400
  private val scale = 3.0 / zoom / h
  private val offsets = Array(0.25, 0.75) // 2x2 スーパーサンプリング

  /** Inigo Quilez のコサインパレット: a + b·cos(2π(c·t + d)) */
  private def palette(t: Double): V = {
    def ch(d: Double): Double = 0.5 + 0.5 * MathX.cos(MathX.TwoPi * (t + d))
    V(ch(0.0), ch(0.1), ch(0.2))
  }

  private def sample(px: Double, py: Double): V = {
    val s = Fractal.smoothIter(centerX + (px - w / 2.0) * scale, centerY + (py - h / 2.0) * scale, cr, ci, MaxIter)
    if (s < 0) V(0.02, 0.01, 0.04) else palette(0.55 + s * 0.025)
  }

  def renderRow(y: Int, out: Array[Int], offset: Int): Unit = {
    var x = 0
    while (x < w) {
      var acc = V.Zero
      for (oy <- offsets; ox <- offsets) acc = acc + sample(x + ox, y + oy)
      out(offset + x) = Fractal.rgb(acc * 0.25)
      x += 1
    }
  }
}

/** c を選ぶためのマンデルブロ集合マップ。実部 [-2.2, 0.8], 虚部 [-1.0, 1.0] (3:2) */
final class MandelJob(w: Int, h: Int, ink: Int, paper: Int) {
  private final val MaxIter = 120
  private def ch(c: Int, s: Int): Double = ((c >> s) & 0xff) / 255.0
  private val inkV = V(ch(ink, 16), ch(ink, 8), ch(ink, 0))
  private val paperV = V(ch(paper, 16), ch(paper, 8), ch(paper, 0))

  def renderRow(y: Int, out: Array[Int], offset: Int): Unit = {
    var x = 0
    while (x < w) {
      val cr = -2.2 + 3.0 * (x + 0.5) / w
      val ci = 1.0 - 2.0 * (y + 0.5) / h
      val s = Fractal.smoothIter(0, 0, cr, ci, MaxIter)
      val c =
        if (s < 0) inkV
        else {
          val t = Math.min(1.0, Math.sqrt(Math.max(0.0, s) / 40.0))
          paperV * (1 - t * 0.85) + inkV * (t * 0.85)
        }
      out(offset + x) = Fractal.rgb(c)
      x += 1
    }
  }
}

object Julia {
  def render(w: Int, h: Int, cr: Double, ci: Double): Array[Int] = {
    val job = new JuliaJob(w, h, cr, ci, 1.0, 0.0, 0.0)
    val out = new Array[Int](w * h)
    var y = 0
    while (y < h) { job.renderRow(y, out, y * w); y += 1 }
    out
  }
}
