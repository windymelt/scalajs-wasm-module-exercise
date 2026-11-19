package img

import java.util.Arrays

/** 2 次元の安定流体ソルバー (Jos Stam, "Real-Time Fluid Dynamics for Games" の系統)。
 *
 *  - 速度場は半ラグランジュ法で移流し、圧力のポアソン方程式を Gauss-Seidel で解いて非圧縮にする
 *  - 渦度閉じ込め (vorticity confinement) で、数値拡散で消えがちな小さな渦を補う
 *  - 壁は閉じた箱。外周 1 セルを境界条件用に持つ
 *
 *  ホットループではオブジェクトを一切確保しない (wasmtime では確保と GC が V8 の 20 倍以上遅かったため)。
 */
final class Fluid(val nx: Int, val ny: Int) {
  private val sx = nx + 2
  private val size = sx * (ny + 2)
  @inline private def ix(i: Int, j: Int): Int = i + sx * j

  private var u = new Array[Double](size)
  private var v = new Array[Double](size)
  private var u0 = new Array[Double](size)
  private var v0 = new Array[Double](size)
  private val dye = Array.fill(3)(new Array[Double](size))
  private val dye0 = Array.fill(3)(new Array[Double](size))
  private val p = new Array[Double](size)
  private val div = new Array[Double](size)
  private val curl = new Array[Double](size)

  def clear(): Unit = {
    for (a <- Seq(u, v, u0, v0, p, div, curl)) Arrays.fill(a, 0.0)
    for (c <- 0 until 3) { Arrays.fill(dye(c), 0.0); Arrays.fill(dye0(c), 0.0) }
  }

  /** b = 1: 水平速度 (左右の壁で反転), b = 2: 垂直速度 (上下の壁で反転), b = 0: スカラー */
  private def setBnd(b: Int, x: Array[Double]): Unit = {
    var i = 1
    while (i <= nx) {
      x(ix(i, 0)) = if (b == 2) -x(ix(i, 1)) else x(ix(i, 1))
      x(ix(i, ny + 1)) = if (b == 2) -x(ix(i, ny)) else x(ix(i, ny))
      i += 1
    }
    var j = 1
    while (j <= ny) {
      x(ix(0, j)) = if (b == 1) -x(ix(1, j)) else x(ix(1, j))
      x(ix(nx + 1, j)) = if (b == 1) -x(ix(nx, j)) else x(ix(nx, j))
      j += 1
    }
    x(ix(0, 0)) = 0.5 * (x(ix(1, 0)) + x(ix(0, 1)))
    x(ix(0, ny + 1)) = 0.5 * (x(ix(1, ny + 1)) + x(ix(0, ny)))
    x(ix(nx + 1, 0)) = 0.5 * (x(ix(nx, 0)) + x(ix(nx + 1, 1)))
    x(ix(nx + 1, ny + 1)) = 0.5 * (x(ix(nx, ny + 1)) + x(ix(nx + 1, ny)))
  }

  /** 発散を取り除く。p は前フレームの解を初期値に使う (ウォームスタート) */
  private def project(iters: Int): Unit = {
    var j = 1
    while (j <= ny) {
      var i = 1
      while (i <= nx) {
        val k = ix(i, j)
        div(k) = -0.5 * (u(k + 1) - u(k - 1) + v(k + sx) - v(k - sx))
        i += 1
      }
      j += 1
    }
    setBnd(0, div)
    var it = 0
    while (it < iters) {
      j = 1
      while (j <= ny) {
        var i = 1
        var k = ix(1, j)
        while (i <= nx) {
          p(k) = (div(k) + p(k - 1) + p(k + 1) + p(k - sx) + p(k + sx)) * 0.25
          i += 1
          k += 1
        }
        j += 1
      }
      setBnd(0, p)
      it += 1
    }
    j = 1
    while (j <= ny) {
      var i = 1
      while (i <= nx) {
        val k = ix(i, j)
        u(k) -= 0.5 * (p(k + 1) - p(k - 1))
        v(k) -= 0.5 * (p(k + sx) - p(k - sx))
        i += 1
      }
      j += 1
    }
    setBnd(1, u)
    setBnd(2, v)
  }

  /** 半ラグランジュ移流: 各セルから速度を逆にたどり、出発点の値を双線形補間で拾う */
  private def advect(b: Int, d: Array[Double], d0: Array[Double], uu: Array[Double], vv: Array[Double], dt: Double): Unit = {
    val maxX = nx + 0.5
    val maxY = ny + 0.5
    var j = 1
    while (j <= ny) {
      var i = 1
      while (i <= nx) {
        val k = ix(i, j)
        val x = Math.min(maxX, Math.max(0.5, i - dt * uu(k)))
        val y = Math.min(maxY, Math.max(0.5, j - dt * vv(k)))
        val i0 = x.toInt
        val j0 = y.toInt
        val s1 = x - i0
        val t1 = y - j0
        val k00 = ix(i0, j0)
        d(k) = (1 - s1) * ((1 - t1) * d0(k00) + t1 * d0(k00 + sx)) +
          s1 * ((1 - t1) * d0(k00 + 1) + t1 * d0(k00 + 1 + sx))
        i += 1
      }
      j += 1
    }
    setBnd(b, d)
  }

  private def vorticity(eps: Double, dt: Double): Unit = {
    var j = 1
    while (j <= ny) {
      var i = 1
      while (i <= nx) {
        val k = ix(i, j)
        curl(k) = 0.5 * ((v(k + 1) - v(k - 1)) - (u(k + sx) - u(k - sx)))
        i += 1
      }
      j += 1
    }
    j = 2
    while (j < ny) {
      var i = 2
      while (i < nx) {
        val k = ix(i, j)
        // N = ∇|ω| / |∇|ω||, 力 = ε (N × ω)
        val gx = 0.5 * (Math.abs(curl(k + 1)) - Math.abs(curl(k - 1)))
        val gy = 0.5 * (Math.abs(curl(k + sx)) - Math.abs(curl(k - sx)))
        val len = Math.sqrt(gx * gx + gy * gy) + 1e-5
        val w = curl(k)
        u(k) += dt * eps * (gy / len) * w
        v(k) -= dt * eps * (gx / len) * w
        i += 1
      }
      j += 1
    }
  }

  /** damp: 速度に毎ステップ掛ける減衰係数 (注入し続けてもエネルギーが際限なく増えないように) */
  def step(dt: Double, iters: Int, vort: Double, fade: Double, damp: Double): Unit = {
    if (damp < 1.0) {
      var k = 0
      while (k < size) { u(k) *= damp; v(k) *= damp; k += 1 }
    }
    if (vort > 0) vorticity(vort, dt)
    project(iters)
    var t = u0; u0 = u; u = t
    t = v0; v0 = v; v = t
    advect(1, u, u0, u0, v0, dt)
    advect(2, v, v0, u0, v0, dt)
    project(iters)
    var c = 0
    while (c < 3) {
      val d0 = dye(c); dye(c) = dye0(c); dye0(c) = d0
      advect(0, dye(c), dye0(c), u, v, dt)
      val d = dye(c)
      var k = 0
      while (k < size) { d(k) *= fade; k += 1 }
      c += 1
    }
  }

  /** (x, y) を中心に、半径 radius のなめらかな山形で速度と色を足す。座標はセル単位 */
  def splat(x: Double, y: Double, dx: Double, dy: Double, r: Double, g: Double, b: Double, radius: Double): Unit = {
    val rad2 = radius * radius
    val i0 = Math.max(1, Math.floor(x - radius).toInt)
    val i1 = Math.min(nx, Math.ceil(x + radius).toInt)
    val j0 = Math.max(1, Math.floor(y - radius).toInt)
    val j1 = Math.min(ny, Math.ceil(y + radius).toInt)
    var j = j0
    while (j <= j1) {
      var i = i0
      while (i <= i1) {
        val d2 = (i - x) * (i - x) + (j - y) * (j - y)
        if (d2 < rad2) {
          val f = 1 - d2 / rad2
          val w = f * f
          val k = ix(i, j)
          u(k) += dx * w
          v(k) += dy * w
          dye(0)(k) += r * w
          dye(1)(k) += g * w
          dye(2)(k) += b * w
        }
        i += 1
      }
      j += 1
    }
  }

  /** 暗い背景に色素を加算し、c / (1 + c) で白飛びを抑える */
  def render(out: Array[Int], bg: Int): Unit = {
    val br = ((bg >> 16) & 0xff) / 255.0
    val bgg = ((bg >> 8) & 0xff) / 255.0
    val bb = (bg & 0xff) / 255.0
    val dr = dye(0); val dg = dye(1); val db = dye(2)
    var j = 1
    var o = 0
    while (j <= ny) {
      var i = 1
      var k = ix(1, j)
      while (i <= nx) {
        val r = Math.max(0.0, dr(k)); val g = Math.max(0.0, dg(k)); val b = Math.max(0.0, db(k))
        val cr = br + (1 - br) * (r / (1 + r))
        val cg = bgg + (1 - bgg) * (g / (1 + g))
        val cb = bb + (1 - bb) * (b / (1 + b))
        out(o) = ((cr * 255 + 0.5).toInt << 16) | ((cg * 255 + 0.5).toInt << 8) | (cb * 255 + 0.5).toInt
        o += 1
        i += 1
        k += 1
      }
      j += 1
    }
  }

  /** 運動エネルギーの合計 (デバッグ・計測用) */
  def energy: Double = {
    var e = 0.0
    var k = 0
    while (k < size) { e += u(k) * u(k) + v(k) * v(k); k += 1 }
    e
  }
}
