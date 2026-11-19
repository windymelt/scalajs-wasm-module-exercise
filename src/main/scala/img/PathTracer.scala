package img

import java.util.Random

/**
 * 箱の中に鏡の球と、ガラス玉またはグラスを置いた、プログレッシブなパストレーサー。
 *
 *  - 拡散面ではコサイン重み付きで次の方向を選ぶ (単位円板の点を持ち上げる Malley の方法なので三角関数が要らない)
 *  - nee = true なら、拡散面ごとに天井の面光源を直接サンプリングする (Next Event Estimation)
 *  - ロシアンルーレットで経路を確率的に打ち切る
 *  - ガラスやビールの中を進んだ光は、ランベルト・ベールの法則で色ごとに exp(-σ·距離) の割合で吸収される
 *
 *  グラスは「面」の集まりで表す。面ごとに表側と裏側の媒質 (空気・ガラス・ビール) を決めておくので、
 *  光線が面に当たったときに、法線との向きだけで「どの媒質から、どの媒質へ」進むかがわかる。
 *  今どの媒質の中にいるかを覚えておく必要がない。
 *
 *  ホットループではオブジェクトを一切確保しない。ベクトルは Double のローカル変数とフィールドで持つ。
 *
 *  座標系: 箱は x ∈ [0, 160], y ∈ [0, 100], z ∈ [0, 100] で、手前 (z = 100) が開いている。
 *  カメラは箱の外 (z = 260) から -z 方向を見て、画角は開口部にぴったり合わせる。
 *
 * @param glassTint  0: 透明, 1: 琥珀, 2: エメラルド, 3: サファイア
 * @param density    ガラスの色の濃さの倍率
 * @param objectKind 右に置く物体。0: ガラス玉, 1: 空のグラス, 2: ビールを注いだグラス
 */
final class PathTracer(
    val w: Int,
    val h: Int,
    seed: Int,
    maxDepth: Int,
    nee: Boolean,
    glassTint: Int = 0,
    density: Double = 1.0,
    objectKind: Int = 0,
) {
  import PathTracer._

  private val rnd = new Random(seed.toLong)
  private final val Eps = 1e-4
  private final val InvPi = 1.0 / MathX.Pi

  // ---- 箱と光源
  private final val BoxX = 160.0
  private final val BoxY = 100.0
  private final val BoxZ = 100.0
  // 天井の面光源 (y = BoxY, 下向き)
  private final val LX0 = 62.0; private final val LX1 = 98.0
  private final val LZ0 = 35.0; private final val LZ1 = 65.0
  private val lightArea = (LX1 - LX0) * (LZ1 - LZ0)
  private final val Le = 16.0

  // ---- 媒質: 屈折率と吸収係数
  private val ior = Array(1.0, 1.5, 1.33)
  private val sigR = new Array[Double](3)
  private val sigG = new Array[Double](3)
  private val sigB = new Array[Double](3)
  locally {
    // 「その距離を通り抜けたときに各色が何割残るか」から吸収係数を逆算する
    def sigma(transmittance: Double, distance: Double): Double = -MathX.log(transmittance) / distance
    val (gr, gg, gb) = glassTint match {
      case 1 => (0.92, 0.55, 0.12) // 琥珀
      case 2 => (0.22, 0.85, 0.45) // エメラルド
      case 3 => (0.18, 0.42, 0.95) // サファイア
      case _ => (1.0, 1.0, 1.0)    // 透明
    }
    sigR(Glass) = sigma(gr, 44) * density
    sigG(Glass) = sigma(gg, 44) * density
    sigB(Glass) = sigma(gb, 44) * density
    // ビール: グラスの内径 (約 27) を通り抜けると、赤 85%、緑 40%、青 6% が残る
    sigR(Beer) = sigma(0.85, 27.2)
    sigG(Beer) = sigma(0.40, 27.2)
    sigB(Beer) = sigma(0.06, 27.2)
  }

  // ---- 球: 中心, 半径, 種類 (0: 拡散, 1: 鏡, 2: ガラス), 色。ガラス玉 (1 番) は objectKind = 0 のときだけ置く
  private val sx = Array(45.0, 112.0, 80.0)
  private val sy = Array(22.0, 22.0, 12.0)
  private val sz = Array(38.0, 58.0, 80.0)
  private val sr = Array(22.0, 22.0, 12.0)
  private val skind = Array(1, 2, 0)
  private val scr = Array(0.97, 0.98, 0.95)
  private val scg = Array(0.97, 0.98, 0.72)
  private val scb = Array(0.97, 0.98, 0.18)
  private val sOn = Array(true, objectKind == 0, true)

  // ---- グラス (タンブラー)。中心 (TX, TZ)、床からほんの少し浮かせて床の面と重ならないようにする
  private final val TX = 112.0
  private final val TZ = 60.0
  private final val TY0 = 0.05
  private final val TRo = 15.0          // 外径
  private final val TRi = 13.6          // 内径
  private final val TYB = TY0 + 4.5     // 内側の底 (厚い底)
  private final val TYT = TY0 + 46.0    // 縁
  private final val TYL = TYB + 26.0    // ビールの液面
  private final val TYF = TYL + 6.0     // 泡の上面

  private val faces: Array[Face] = {
    val outer = Face(Cylinder, TRo, 0, TY0, TYT, +1, Air, Glass, foam = false)  // 外側の側面
    val bottom = Face(Disk, 0, TRo, TY0, 0, -1, Air, Glass, foam = false)      // 底の下面
    val rim = Face(Disk, TRi, TRo, TYT, 0, +1, Air, Glass, foam = false)       // 縁
    objectKind match {
      case 1 =>
        Array(
          outer, bottom, rim,
          Face(Disk, 0, TRi, TYB, 0, +1, Air, Glass, foam = false),       // 内側の底
          Face(Cylinder, TRi, 0, TYB, TYT, -1, Air, Glass, foam = false), // 内側の側面
        )
      case 2 =>
        Array(
          outer, bottom, rim,
          Face(Disk, 0, TRi, TYB, 0, +1, Beer, Glass, foam = false),       // 内側の底: ビールとガラス
          Face(Cylinder, TRi, 0, TYB, TYL, -1, Beer, Glass, foam = false), // 内側の側面: ビールとガラス
          Face(Cylinder, TRi, 0, TYL, TYF, +1, Glass, -1, foam = true),    // 泡をガラス越しに見る面
          Face(Disk, 0, TRi, TYL, 0, -1, Beer, -1, foam = true),           // 泡の下面 (ビールの中から見える)
          Face(Disk, 0, TRi, TYF, 0, +1, Air, -1, foam = true),            // 泡の上面
          Face(Cylinder, TRi, 0, TYF, TYT, -1, Air, Glass, foam = false),  // 泡より上の内側の側面
        )
      case _ => Array.empty[Face]
    }
  }

  // ---- 交差判定の結果 (フィールドで受け渡して確保を避ける)
  // obj: 0..2 球, 10: 床, 11: 天井, 12: 奥, 13: 開口部から外へ抜けた, 14: 左, 15: 右, 20 以上: グラスの面
  private var hitT = 0.0
  private var hitObj = -1
  /** true のあいだ、交差判定でグラスの面を無視する (薄いガラスの近似で光源を狙うとき) */
  private var ignoreFaces = false
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
      if (sOn(i)) {
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
      }
      i += 1
    }
    var k = 0
    val nFaces = if (ignoreFaces) 0 else faces.length
    while (k < nFaces) {
      val f = faces(k)
      if (f.kind == Cylinder) {
        // 鉛直な円柱 (x - TX)² + (z - TZ)² = a² と、高さの範囲
        val a = dx * dx + dz * dz
        if (a > 1e-12) {
          val qx = ox - TX; val qz = oz - TZ
          val b = qx * dx + qz * dz
          val c = qx * qx + qz * qz - f.a * f.a
          val disc = b * b - a * c
          if (disc > 0) {
            val sq = Math.sqrt(disc)
            var t = (-b - sq) / a
            if (t > Eps && t < bestT) { val y = oy + dy * t; if (y >= f.y0 && y <= f.y1) { bestT = t; best = 20 + k } }
            t = (-b + sq) / a
            if (t > Eps && t < bestT) { val y = oy + dy * t; if (y >= f.y0 && y <= f.y1) { bestT = t; best = 20 + k } }
          }
        }
      } else if (dy != 0) {
        // 水平な円板 (円環): 高さ y0、半径 a〜b
        val t = (f.y0 - oy) / dy
        if (t > Eps && t < bestT) {
          val qx = ox + dx * t - TX; val qz = oz + dz * t - TZ
          val r2 = qx * qx + qz * qz
          if (r2 >= f.a * f.a && r2 <= f.b * f.b) { bestT = t; best = 20 + k }
        }
      }
      k += 1
    }
    hitT = bestT
    hitObj = best
  }

  /** 光源上の点まで遮るものがないか。ガラスやビールも遮るものとして扱う (透過した光は経路の側で拾う) */
  private def visible(ox: Double, oy: Double, oz: Double, dx: Double, dy: Double, dz: Double, dist: Double): Boolean = {
    intersect(ox, oy, oz, dx, dy, dz)
    !(hitObj >= 0 && hitObj != 11 && hitT < dist)
  }

  /**
   * 泡の側面から、グラスの壁越しに光源を狙う。壁を「屈折を無視して素通りできる薄いガラス」とみなし、
   * 表と裏の 2 回の反射で失われるぶん (約 8%) だけを差し引く。
   */
  private def directThroughThinGlass(px: Double, py: Double, pz: Double, nx: Double, ny: Double, nz: Double): Double = {
    ignoreFaces = true
    val g = direct(px, py, pz, nx, ny, nz)
    ignoreFaces = false
    g * 0.92
  }

  /** 拡散面 (法線 n) から見た面光源の直接光。光源上の点を一様に選ぶ */
  private def direct(px: Double, py: Double, pz: Double, nx: Double, ny: Double, nz: Double): Double = {
    val qx = LX0 + (LX1 - LX0) * rnd.nextDouble()
    val qz = LZ0 + (LZ1 - LZ0) * rnd.nextDouble()
    var wx = qx - px; var wy = BoxY - py; var wz = qz - pz
    val d2 = wx * wx + wy * wy + wz * wz
    val dist = Math.sqrt(d2)
    wx /= dist; wy /= dist; wz /= dist
    val cosS = nx * wx + ny * wy + nz * wz
    val cosL = wy // 光源の法線は (0, -1, 0)
    if (cosS > 0 && cosL > 0 && visible(px, py, pz, wx, wy, wz, dist - Eps)) Le * InvPi * cosS * cosL * lightArea / d2
    else 0.0
  }

  // 次の方向 (cosineDir と dielectric の結果)
  private var ndx = 0.0
  private var ndy = 0.0
  private var ndz = 0.0

  /** コサイン重み付きの半球サンプリング: 単位円板の点 (u, v) を持ち上げる */
  private def cosineDir(nx: Double, ny: Double, nz: Double): Unit = {
    var u = 0.0; var v = 0.0
    var r2 = 2.0
    while (r2 > 1.0) { u = rnd.nextDouble() * 2 - 1; v = rnd.nextDouble() * 2 - 1; r2 = u * u + v * v }
    val wl = Math.sqrt(1 - r2)
    // 法線 n を z 軸とする正規直交基底 (a, b, n)
    var ax = 0.0; var ay = 0.0; var az = 0.0
    if (Math.abs(nx) > 0.9) { ay = 1 } else { ax = 1 }
    var bx = ay * nz - az * ny; var by = az * nx - ax * nz; var bz = ax * ny - ay * nx
    val bl = 1.0 / Math.sqrt(bx * bx + by * by + bz * bz)
    bx *= bl; by *= bl; bz *= bl
    ax = ny * bz - nz * by; ay = nz * bx - nx * bz; az = nx * by - ny * bx
    ndx = ax * u + bx * v + nx * wl
    ndy = ay * u + by * v + ny * wl
    ndz = az * u + bz * v + nz * wl
  }

  /**
   * 屈折率 n1 の媒質から n2 の媒質への境目で、反射か屈折かを確率的に選ぶ。
   * n は入射側を向いた法線。フレネル反射率は Schlick 近似で、角度は屈折率の小さい側のものを使う。
   */
  private def dielectric(dx: Double, dy: Double, dz: Double, nx: Double, ny: Double, nz: Double, n1: Double, n2: Double): Unit = {
    val cosI = -(dx * nx + dy * ny + dz * nz)
    val eta = n1 / n2
    val k = 1 - eta * eta * (1 - cosI * cosI)
    val reflectProb =
      if (k < 0) 1.0 // 全反射
      else {
        val r0 = (n1 - n2) / (n1 + n2)
        val f0 = r0 * r0
        val c = if (n1 <= n2) cosI else Math.sqrt(k)
        val m = 1 - c
        f0 + (1 - f0) * m * m * m * m * m
      }
    if (rnd.nextDouble() < reflectProb) {
      ndx = dx + 2 * cosI * nx; ndy = dy + 2 * cosI * ny; ndz = dz + 2 * cosI * nz
    } else {
      val c = eta * cosI - Math.sqrt(k)
      ndx = eta * dx + c * nx; ndy = eta * dy + c * ny; ndz = eta * dz + c * nz
    }
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
    var specular = true // 直前で直接光を数えていない (鏡面・カメラなど) なら、光源に当たったときに放射を数える
    // 泡の側面で「薄いガラス越しの直接光」を数えた直後で、まだグラスの面しか通っていない。
    // この状態で光源に当たった光は、すでに数えたものなので二重に数えない
    var thinNee = false
    var depth = 0
    while (depth < maxDepth) {
      intersect(ox, oy, oz, dx, dy, dz)
      val t = hitT
      val obj = hitObj
      if (obj < 0 || obj == 13) return // 箱の外へ抜けた (外の世界は真っ暗)
      val px = ox + dx * t; val py = oy + dy * t; val pz = oz + dz * t

      // 天井の光源に当たった
      if (obj == 11 && px >= LX0 && px <= LX1 && pz >= LZ0 && pz <= LZ1) {
        if ((specular && !thinNee) || !nee) { lr += tr * Le; lg += tg * Le; lb += tb * Le }
        return
      }

      if (obj >= 20) {
        // ---- グラスの面
        val f = faces(obj - 20)
        var nx = 0.0; var ny = 0.0; var nz = 0.0
        if (f.kind == Cylinder) { val inv = f.sign / f.a; nx = (px - TX) * inv; nz = (pz - TZ) * inv }
        else ny = f.sign
        val fromFront = dx * nx + dy * ny + dz * nz < 0
        val from = if (fromFront) f.front else Math.max(0, f.back)
        // 通ってきた媒質での吸収
        if (from != Air) { tr *= MathX.exp(-sigR(from) * t); tg *= MathX.exp(-sigG(from) * t); tb *= MathX.exp(-sigB(from) * t) }
        if (!fromFront) { nx = -nx; ny = -ny; nz = -nz } // 法線を入射側へ向ける
        if (f.foam) {
          // 泡: 光を通さない白い拡散面。空気側からは普通に、ガラス側 (側面) からは薄いガラス越しに直接光を数える
          val cr = 0.93; val cg = 0.91; val cb = 0.85
          val viaGlass = from == Glass
          val didNee = nee && (from == Air || viaGlass)
          if (didNee) {
            val g = if (viaGlass) directThroughThinGlass(px, py, pz, nx, ny, nz) else direct(px, py, pz, nx, ny, nz)
            lr += tr * cr * g; lg += tg * cg * g; lb += tb * cb * g
          }
          tr *= cr; tg *= cg; tb *= cb
          cosineDir(nx, ny, nz)
          specular = !didNee
          thinNee = viaGlass && didNee
        } else {
          val to = if (fromFront) Math.max(0, f.back) else f.front
          dielectric(dx, dy, dz, nx, ny, nz, ior(from), ior(to))
          specular = true
        }
        dx = ndx; dy = ndy; dz = ndz
      } else {
        // ---- 箱の壁と球
        thinNee = false
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
            if (nee) { val g = direct(px, py, pz, nx, ny, nz); lr += tr * cr * g; lg += tg * cg * g; lb += tb * cb * g }
            tr *= cr; tg *= cg; tb *= cb
            cosineDir(nx, ny, nz)
            specular = !nee

          case 1 => // 鏡
            val dn = dx * nx + dy * ny + dz * nz
            ndx = dx - 2 * dn * nx; ndy = dy - 2 * dn * ny; ndz = dz - 2 * dn * nz
            tr *= cr; tg *= cg; tb *= cb
            specular = true

          case _ => // ガラス玉
            if (dx * nx + dy * ny + dz * nz > 0) {
              // 内側から外へ: 直前の区間 (長さ t) はガラスの中を通ってきた
              tr *= MathX.exp(-sigR(Glass) * t); tg *= MathX.exp(-sigG(Glass) * t); tb *= MathX.exp(-sigB(Glass) * t)
              dielectric(dx, dy, dz, -nx, -ny, -nz, ior(Glass), ior(Air))
            } else dielectric(dx, dy, dz, nx, ny, nz, ior(Air), ior(Glass))
            tr *= cr; tg *= cg; tb *= cb
            specular = true
        }
        dx = ndx; dy = ndy; dz = ndz
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

object PathTracer {
  // 媒質
  final val Air = 0
  final val Glass = 1
  final val Beer = 2

  // 面の形
  final val Cylinder = 0
  final val Disk = 1

  /**
   * グラスを作る面。
   *  - Cylinder: 半径 a の鉛直な円柱のうち、高さ y0〜y1 の部分
   *  - Disk: 高さ y0 の水平な円環 (半径 a〜b)
   *  sign は法線の向き (円柱: +1 外向き / -1 中心向き、円板: +1 上 / -1 下)。
   *  front は法線が向いている側の媒質、back はその反対側。foam は光を通さない泡の面。
   */
  final case class Face(kind: Int, a: Double, b: Double, y0: Double, y1: Double, sign: Int, front: Int, back: Int, foam: Boolean)
}
