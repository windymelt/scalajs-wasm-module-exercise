package img

import java.util.Random

/**
 * 箱の中に鏡とガラスの球を置いた、プログレッシブなパストレーサー。
 *
 *  - 拡散面ではコサイン重み付きで次の方向を選ぶ (単位円板の点を持ち上げる Malley の方法なので三角関数が要らない)
 *  - nee = true なら、拡散面ごとに天井の面光源を直接サンプリングする (Next Event Estimation)
 *  - ロシアンルーレットで経路を確率的に打ち切る
 *  - ガラスの中を進んだ光は、ランベルト・ベールの法則で色ごとに exp(-σ·距離) の割合で吸収される
 *
 *  ホットループではオブジェクトを一切確保しない。ベクトルは Double のローカル変数とフィールドで持つ。
 *
 *  座標系: 箱は x ∈ [0, 160], y ∈ [0, 100], z ∈ [0, 100] で、手前 (z = 100) が開いている。
 *  カメラは箱の外 (z = 260) から -z 方向を見て、画角は開口部にぴったり合わせる。
 */
final class PathTracer(val w: Int, val h: Int, seed: Int, maxDepth: Int, nee: Boolean, glassTint: Int = 0, density: Double = 1.0) {
  private val rnd = new Random(seed.toLong)
  private final val Eps = 1e-4
  private final val InvPi = 1.0 / MathX.Pi

  // ---- シーン
  private final val BoxX = 160.0
  private final val BoxY = 100.0
  private final val BoxZ = 100.0
  // 天井の面光源 (y = BoxY, 下向き)
  private final val LX0 = 62.0; private final val LX1 = 98.0
  private final val LZ0 = 35.0; private final val LZ1 = 65.0
  private val lightArea = (LX1 - LX0) * (LZ1 - LZ0)
  private final val Le = 16.0

  // 球: 中心, 半径, 種類 (0: 拡散, 1: 鏡, 2: ガラス), 色
  private val sx = Array(45.0, 112.0, 80.0)
  private val sy = Array(22.0, 22.0, 12.0)
  private val sz = Array(38.0, 58.0, 80.0)
  private val sr = Array(22.0, 22.0, 12.0)
  private val skind = Array(1, 2, 0)
  private val scr = Array(0.97, 0.98, 0.95)
  private val scg = Array(0.97, 0.98, 0.72)
  private val scb = Array(0.97, 0.98, 0.18)

  // ガラスの吸収係数。「直径 44 の玉を通り抜けたときに各色が何割残るか」から逆算する
  private val (sigR, sigG, sigB) = {
    val (tr, tg, tb) = glassTint match {
      case 1 => (0.92, 0.55, 0.12) // ビール
      case 2 => (0.22, 0.85, 0.45) // エメラルド
      case 3 => (0.18, 0.42, 0.95) // サファイア
      case _ => (1.0, 1.0, 1.0)    // 透明
    }
    def sigma(t: Double): Double = -MathX.log(t) / 44.0 * density
    (sigma(tr), sigma(tg), sigma(tb))
  }

  // ---- 交差判定の結果 (フィールドで受け渡して確保を避ける)
  // obj: 0..2 球, 10: 床, 11: 天井, 12: 奥, 13: 開口部から外へ抜けた, 14: 左, 15: 右
  private var hitT = 0.0
  private var hitObj = -1
  var rays = 0L

  private def intersect(ox: Double, oy: Double, oz: Double, dx: Double, dy: Double, dz: Double): Unit = {
    rays += 1
    var bestT = Double.MaxValue
    var best = -1
    // 箱の内側の壁: 正の t を持つものの最小
    if (dy < 0) { val t = -oy / dy; if (t > Eps && t < bestT) { bestT = t; best = 10 } }
    if (dy > 0) { val t = (BoxY - oy) / dy; if (t > Eps && t < bestT) { bestT = t; best = 11 } }
    if (dz < 0) { val t = -oz / dz; if (t > Eps && t < bestT) { bestT = t; best = 12 } }
    if (dz > 0) { val t = (BoxZ - oz) / dz; if (t > Eps && t < bestT) { bestT = t; best = 13 } }
    if (dx < 0) { val t = -ox / dx; if (t > Eps && t < bestT) { bestT = t; best = 14 } }
    if (dx > 0) { val t = (BoxX - ox) / dx; if (t > Eps && t < bestT) { bestT = t; best = 15 } }
    var i = 0
    while (i < 3) {
      val px = ox - sx(i); val py = oy - sy(i); val pz = oz - sz(i)
      val b = px * dx + py * dy + pz * dz
      val c = px * px + py * py + pz * pz - sr(i) * sr(i)
      val disc = b * b - c
      if (disc > 0) {
        val sq = Math.sqrt(disc)
        var t = -b - sq
        if (t <= Eps) t = -b + sq
        if (t > Eps && t < bestT) { bestT = t; best = i }
      }
      i += 1
    }
    hitT = bestT
    hitObj = best
  }

  /** 光源上の点まで遮るものがないか (遮りうるのは球だけ) */
  private def visible(ox: Double, oy: Double, oz: Double, dx: Double, dy: Double, dz: Double, dist: Double): Boolean = {
    rays += 1
    var i = 0
    while (i < 3) {
      val px = ox - sx(i); val py = oy - sy(i); val pz = oz - sz(i)
      val b = px * dx + py * dy + pz * dz
      val c = px * px + py * py + pz * pz - sr(i) * sr(i)
      val disc = b * b - c
      if (disc > 0) {
        val sq = Math.sqrt(disc)
        val t1 = -b - sq
        val t2 = -b + sq
        if ((t1 > Eps && t1 < dist) || (t2 > Eps && t2 < dist)) return false
      }
      i += 1
    }
    true
  }

  // 1 本の経路の放射輝度 (結果は lr, lg, lb に入る)
  private var lr = 0.0
  private var lg = 0.0
  private var lb = 0.0

  private def radiance(ox0: Double, oy0: Double, oz0: Double, dx0: Double, dy0: Double, dz0: Double): Unit = {
    var ox = ox0; var oy = oy0; var oz = oz0
    var dx = dx0; var dy = dy0; var dz = dz0
    var tr = 1.0; var tg = 1.0; var tb = 1.0
    lr = 0; lg = 0; lb = 0
    var specular = true // 直前が鏡面 (またはカメラ) なら、光源に当たったときに放射を数える
    var depth = 0
    while (depth < maxDepth) {
      intersect(ox, oy, oz, dx, dy, dz)
      val t = hitT
      val obj = hitObj
      if (obj < 0 || obj == 13) return // 箱の外へ抜けた (外の世界は真っ暗)
      val px = ox + dx * t; val py = oy + dy * t; val pz = oz + dz * t

      // 天井の光源に当たった
      if (obj == 11 && px >= LX0 && px <= LX1 && pz >= LZ0 && pz <= LZ1) {
        if (specular || !nee) { lr += tr * Le; lg += tg * Le; lb += tb * Le }
        return
      }

      // 法線と材質
      var nx = 0.0; var ny = 0.0; var nz = 0.0
      var cr = 0.75; var cg = 0.75; var cb = 0.75
      var kind = 0
      obj match {
        case 10 => ny = 1
        case 11 => ny = -1
        case 12 => nz = 1
        case 14 => nx = 1; cr = 0.78; cg = 0.2; cb = 0.16
        case 15 => nx = -1; cr = 0.14; cg = 0.45; cb = 0.62
        case i =>
          val inv = 1.0 / sr(i)
          nx = (px - sx(i)) * inv; ny = (py - sy(i)) * inv; nz = (pz - sz(i)) * inv
          cr = scr(i); cg = scg(i); cb = scb(i)
          kind = skind(i)
      }

      kind match {
        case 0 => // 拡散面
          if (nee) {
            // 面光源上の点を一様に選んで、直接光を足す
            val qx = LX0 + (LX1 - LX0) * rnd.nextDouble()
            val qz = LZ0 + (LZ1 - LZ0) * rnd.nextDouble()
            var wx = qx - px; var wy = BoxY - py; var wz = qz - pz
            val d2 = wx * wx + wy * wy + wz * wz
            val dist = Math.sqrt(d2)
            wx /= dist; wy /= dist; wz /= dist
            val cosS = nx * wx + ny * wy + nz * wz
            val cosL = wy // 光源の法線は (0, -1, 0)
            if (cosS > 0 && cosL > 0 && visible(px, py, pz, wx, wy, wz, dist - Eps)) {
              val g = Le * InvPi * cosS * cosL * lightArea / d2
              lr += tr * cr * g; lg += tg * cg * g; lb += tb * cb * g
            }
          }
          tr *= cr; tg *= cg; tb *= cb
          // コサイン重み付きの半球サンプリング: 単位円板の点 (u, v) を持ち上げる
          var u = 0.0; var v = 0.0
          var r2 = 2.0
          while (r2 > 1.0) { u = rnd.nextDouble() * 2 - 1; v = rnd.nextDouble() * 2 - 1; r2 = u * u + v * v }
          val wl = Math.sqrt(1 - r2)
          // 法線 n を z 軸とする正規直交基底 (a, b, n)
          var ax = 0.0; var ay = 0.0; var az = 0.0
          if (Math.abs(nx) > 0.9) { ay = 1 } else { ax = 1 }
          // a = normalize(a × n), b = n × a
          var bx = ay * nz - az * ny; var by = az * nx - ax * nz; var bz = ax * ny - ay * nx
          val bl = 1.0 / Math.sqrt(bx * bx + by * by + bz * bz)
          bx *= bl; by *= bl; bz *= bl
          ax = ny * bz - nz * by; ay = nz * bx - nx * bz; az = nx * by - ny * bx
          dx = ax * u + bx * v + nx * wl
          dy = ay * u + by * v + ny * wl
          dz = az * u + bz * v + nz * wl
          specular = false

        case 1 => // 鏡
          val dn = dx * nx + dy * ny + dz * nz
          dx -= 2 * dn * nx; dy -= 2 * dn * ny; dz -= 2 * dn * nz
          tr *= cr; tg *= cg; tb *= cb
          specular = true

        case _ => // ガラス (屈折率 1.5、フレネルは Schlick 近似)
          var cosI = -(dx * nx + dy * ny + dz * nz)
          var eta = 1.0 / 1.5
          if (cosI < 0) { // 内側から外へ: 直前の区間 (長さ t) はガラスの中を通ってきた
            nx = -nx; ny = -ny; nz = -nz
            cosI = -cosI
            eta = 1.5
            if (sigR > 0 || sigG > 0 || sigB > 0) {
              tr *= MathX.exp(-sigR * t); tg *= MathX.exp(-sigG * t); tb *= MathX.exp(-sigB * t)
            }
          }
          val k = 1 - eta * eta * (1 - cosI * cosI)
          val r0 = (1 - 1.5) / (1 + 1.5)
          val f0 = r0 * r0
          val m = 1 - cosI
          val fresnel = f0 + (1 - f0) * m * m * m * m * m
          if (k < 0 || rnd.nextDouble() < fresnel) {
            dx += 2 * cosI * nx; dy += 2 * cosI * ny; dz += 2 * cosI * nz // 反射
          } else {
            val c = eta * cosI - Math.sqrt(k)
            dx = eta * dx + c * nx; dy = eta * dy + c * ny; dz = eta * dz + c * nz
          }
          tr *= cr; tg *= cg; tb *= cb
          specular = true
      }

      ox = px; oy = py; oz = pz
      depth += 1
      // ロシアンルーレット
      if (depth >= 3) {
        val q = Math.min(0.95, Math.max(tr, Math.max(tg, tb)))
        if (rnd.nextDouble() > q) return
        tr /= q; tg /= q; tb /= q
      }
    }
  }

  // ---- 累積バッファ
  private val acc = new Array[Double](w * h * 3)
  private val rowSpp = new Array[Int](h)

  private final val CamX = 80.0
  private final val CamY = 50.0
  private final val CamZ = 260.0
  // 開口部 (z = 100, 160×100) が画面いっぱいになる画角。少しだけ内側に寄せて縁の取りこぼしを防ぐ
  private val tanY = 0.995 * (BoxY / 2) / (CamZ - BoxZ)
  private val tanX = tanY * w / h

  /** y 行目に 1 サンプル/画素を足す */
  def renderRow(y: Int): Unit = {
    var x = 0
    var o = y * w * 3
    while (x < w) {
      val u = ((x + rnd.nextDouble()) / w * 2 - 1) * tanX
      val v = (1 - (y + rnd.nextDouble()) / h * 2) * tanY
      val inv = 1.0 / Math.sqrt(u * u + v * v + 1)
      val dx = u * inv; val dy = v * inv; val dz = -inv
      // 開口部の面まで進めてから追跡を始める (壁の平面は箱の内側からしか正しく判定できないため)
      val t0 = (CamZ - BoxZ) / inv
      val ox = CamX + dx * t0; val oy = CamY + dy * t0
      if (ox > 0 && ox < BoxX && oy > 0 && oy < BoxY) radiance(ox, oy, BoxZ - Eps, dx, dy, dz)
      else { lr = 0; lg = 0; lb = 0 }
      acc(o) += lr; acc(o + 1) += lg; acc(o + 2) += lb
      o += 3
      x += 1
    }
    rowSpp(y) += 1
  }

  def spp(y: Int): Int = rowSpp(y)

  /** 平均してガンマ 2.0 で 0xRRGGBB に */
  def pixel(i: Int): Int = {
    val n = rowSpp(i / w)
    if (n == 0) 0
    else {
      val s = 1.0 / n
      def ch(v: Double): Int = (Math.sqrt(Math.max(0.0, Math.min(1.0, v * s))) * 255 + 0.5).toInt
      val o = i * 3
      (ch(acc(o)) << 16) | (ch(acc(o + 1)) << 8) | ch(acc(o + 2))
    }
  }
}
