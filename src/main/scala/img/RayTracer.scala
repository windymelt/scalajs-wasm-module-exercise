package img

import java.util.Random

final case class V(x: Double, y: Double, z: Double) {
  def +(o: V): V = V(x + o.x, y + o.y, z + o.z)
  def -(o: V): V = V(x - o.x, y - o.y, z - o.z)
  def *(s: Double): V = V(x * s, y * s, z * s)
  def mul(o: V): V = V(x * o.x, y * o.y, z * o.z)
  def dot(o: V): Double = x * o.x + y * o.y + z * o.z
  def cross(o: V): V = V(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
  def length: Double = Math.sqrt(this.dot(this))
  def norm: V = this * (1.0 / length)
}

object V {
  val Zero: V = V(0, 0, 0)
  val One: V = V(1, 1, 1)
}

/** shininessLog2: 鏡面反射の指数を 2^n で持つ (pow が使えないので二乗の繰り返しで計算) */
final case class Material(color: V, reflect: Double = 0.0, checker: Option[V] = None, shininessLog2: Int = 5)

sealed trait Shape { def mat: Material }
final case class Sphere(center: V, radius: Double, mat: Material) extends Shape
final case class Floor(y: Double, mat: Material) extends Shape

final case class Hit(shape: Shape, t: Double)

/** カメラは target を中心に半径 Orbit で周回する */
final case class RtParams(
    w: Int,
    h: Int,
    spp: Int,
    seed: Int,
    yawDeg: Double = 0.0,
    eyeHeight: Double = 1.7,
    lightDeg: Double = -38.7,
)

object RayTracer {
  private[img] final val Eps = 1e-4
  private[img] final val MaxDepth = 5
  private[img] final val Orbit = 5.6
  private[img] val Target = V(0.0, 0.65, 0.0)

  private[img] val scene: List[Shape] = List(
    Floor(0.0, Material(V(0.92, 0.9, 0.86), reflect = 0.12, checker = Some(V(0.12, 0.13, 0.16)))),
    Sphere(V(0.0, 1.0, 0.0), 1.0, Material(V(0.95, 0.95, 0.97), reflect = 0.85, shininessLog2 = 7)),
    Sphere(V(-2.1, 0.7, 0.5), 0.7, Material(V(0.88, 0.22, 0.2), reflect = 0.08)),
    Sphere(V(2.0, 0.65, -0.1), 0.65, Material(V(0.98, 0.72, 0.18), reflect = 0.25, shininessLog2 = 6)),
    Sphere(V(0.95, 0.35, -1.45), 0.35, Material(V(0.2, 0.5, 0.95), reflect = 0.05)),
    Sphere(V(-0.9, 0.3, -1.65), 0.3, Material(V(0.3, 0.82, 0.42))),
  )

  def render(w: Int, h: Int, spp: Int, seed: Int): Array[Int] = {
    val job = new RtJob(RtParams(w, h, spp, seed))
    val out = new Array[Int](w * h)
    var y = 0
    while (y < h) { job.renderRow(y, out, y * w); y += 1 }
    out
  }
}

final class RtJob(p: RtParams) {
  import RayTracer._

  private val rnd = new Random(p.seed.toLong)
  private val yaw = p.yawDeg * MathX.Pi / 180
  private val eye = V(MathX.sin(yaw) * Orbit, p.eyeHeight, -MathX.cos(yaw) * Orbit)
  private val fwd = (Target - eye).norm
  private val right = V(0, 1, 0).cross(fwd).norm
  private val up = fwd.cross(right)
  private val tanHalf = MathX.tan(MathX.Pi * 45 / 360) // 画角 45°
  private val aspect = p.w.toDouble / p.h
  private val lightYaw = p.lightDeg * MathX.Pi / 180
  private val light = V(MathX.sin(lightYaw) * 6.4, 7.0, -MathX.cos(lightYaw) * 6.4)
  private val lightRadius = 0.9

  private def intersect(s: Shape, o: V, d: V): Double = s match {
    case Sphere(c, r, _) =>
      val oc = o - c
      val b = oc.dot(d)
      val disc = b * b - (oc.dot(oc) - r * r)
      if (disc < 0) -1
      else {
        val sq = Math.sqrt(disc)
        val t1 = -b - sq
        if (t1 > Eps) t1 else { val t2 = -b + sq; if (t2 > Eps) t2 else -1 }
      }
    case Floor(y, _) =>
      if (Math.abs(d.y) < 1e-9) -1
      else { val t = (y - o.y) / d.y; if (t > Eps) t else -1 }
  }

  private def normalAt(s: Shape, p: V): V = s match {
    case Sphere(c, _, _) => (p - c).norm
    case Floor(_, _)     => V(0, 1, 0)
  }

  private def nearest(o: V, d: V): Option[Hit] =
    scene.foldLeft(Option.empty[Hit]) { (best, s) =>
      val t = intersect(s, o, d)
      if (t > 0 && best.forall(t < _.t)) Some(Hit(s, t)) else best
    }

  private def sky(d: V): V = {
    val t = 0.5 * (d.y + 1)
    V.One * (1 - t) + V(0.42, 0.62, 1.0) * t
  }

  /** x^(2^n) */
  private def powPow2(x: Double, n: Int): Double = {
    var r = x
    var i = 0
    while (i < n) { r *= r; i += 1 }
    r
  }

  private def trace(o: V, d: V, depth: Int): V = nearest(o, d) match {
    case None => sky(d)
    case Some(Hit(s, t)) =>
      val p = o + d * t
      val n = normalAt(s, p)
      val m = s.mat
      val base = m.checker match {
        case Some(alt) if ((Math.floor(p.x).toInt + Math.floor(p.z).toInt) & 1) == 0 => alt
        case _                                                                      => m.color
      }
      // ソフトシャドウ: 光源の位置をサンプルごとに揺らす
      val jitter = V(rnd.nextDouble() - 0.5, rnd.nextDouble() - 0.5, rnd.nextDouble() - 0.5) * (2 * lightRadius)
      val toLight = light + jitter - p
      val distL = toLight.length
      val l = toLight * (1 / distL)
      val visible = nearest(p + n * Eps, l) match {
        case Some(Hit(_, tt)) if tt < distL => 0.0
        case _                              => 1.0
      }
      val diffuse = Math.max(0.0, n.dot(l)) * visible
      val h = (l - d).norm
      val specular = powPow2(Math.max(0.0, n.dot(h)), m.shininessLog2) * visible
      val local = base * (0.12 + 0.88 * diffuse) + V.One * (0.6 * specular)
      if (m.reflect > 0 && depth < MaxDepth) {
        val r = (d - n * (2 * d.dot(n))).norm
        local * (1 - m.reflect) + trace(p + n * Eps, r, depth + 1).mul(base * 0.3 + V.One * 0.7) * m.reflect
      } else local
  }

  private def toRgb(c: V): Int = {
    def ch(v: Double): Int = {
      val g = Math.sqrt(Math.max(0.0, Math.min(1.0, v))) // ガンマ 2.0
      (g * 255 + 0.5).toInt
    }
    (ch(c.x) << 16) | (ch(c.y) << 8) | ch(c.z)
  }

  def renderRow(y: Int, out: Array[Int], offset: Int): Unit = {
    var x = 0
    while (x < p.w) {
      var acc = V.Zero
      var s = 0
      while (s < p.spp) {
        val u = ((x + rnd.nextDouble()) / p.w * 2 - 1) * tanHalf * aspect
        val v = (1 - (y + rnd.nextDouble()) / p.h * 2) * tanHalf
        acc = acc + trace(eye, (fwd + right * u + up * v).norm, 0)
        s += 1
      }
      out(offset + x) = toRgb(acc * (1.0 / p.spp))
      x += 1
    }
  }
}
