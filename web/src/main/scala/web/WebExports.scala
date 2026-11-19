package web

import scala.scalajs.wasm.annotation._

import img._

/** ブラウザ向け API。
 *  UI を止めないよう 1 行ずつ描かせ、画素は px(i) で 1 つずつ吸い出す
 *  (配列は境界でコピーされ、JS からは中身を読めないため)。
 */
object WebExports {
  private var width = 0
  private var buf: Array[Int] = Array.emptyIntArray
  private var row: (Int, Array[Int], Int) => Unit = (_, _, _) => ()

  private def start(w: Int, h: Int)(f: (Int, Array[Int], Int) => Unit): Unit = {
    width = w
    if (buf.length != w * h) buf = new Array[Int](w * h)
    row = f
  }

  @WasmExport("rtSetup")
  def rtSetup(w: Int, h: Int, spp: Int, seed: Int, yawDeg: Double, eyeHeight: Double, lightDeg: Double): Unit = {
    val job = new RtJob(RtParams(w, h, spp, seed, yawDeg, eyeHeight, lightDeg))
    start(w, h)(job.renderRow)
  }

  @WasmExport("juliaSetup")
  def juliaSetup(w: Int, h: Int, cr: Double, ci: Double, zoom: Double, cx: Double, cy: Double): Unit = {
    val job = new JuliaJob(w, h, cr, ci, zoom, cx, cy)
    start(w, h)(job.renderRow)
  }

  @WasmExport("mandelSetup")
  def mandelSetup(w: Int, h: Int, ink: Int, paper: Int): Unit = {
    val job = new MandelJob(w, h, ink, paper)
    start(w, h)(job.renderRow)
  }

  @WasmExport("renderRow")
  def renderRow(y: Int): Unit = row(y, buf, y * width)

  /** 0xRRGGBB */
  @WasmExport("px")
  def px(i: Int): Int = buf(i)

  // ---- 流体シミュレーション (静止画とは別のバッファを持つ)
  private var fluid: Fluid = new Fluid(1, 1)
  private var fluidBuf: Array[Int] = Array.emptyIntArray

  @WasmExport("fluidInit")
  def fluidInit(nx: Int, ny: Int): Unit = {
    fluid = new Fluid(nx, ny)
    fluidBuf = new Array[Int](nx * ny)
  }

  @WasmExport("fluidClear")
  def fluidClear(): Unit = fluid.clear()

  @WasmExport("fluidSplat")
  def fluidSplat(x: Double, y: Double, dx: Double, dy: Double, r: Double, g: Double, b: Double, radius: Double): Unit =
    fluid.splat(x, y, dx, dy, r, g, b, radius)

  @WasmExport("fluidStep")
  def fluidStep(dt: Double, iters: Int, vort: Double, fade: Double, damp: Double): Unit =
    fluid.step(dt, iters, vort, fade, damp)

  @WasmExport("fluidRender")
  def fluidRender(bg: Int): Unit = fluid.render(fluidBuf, bg)

  @WasmExport("fluidPx")
  def fluidPx(i: Int): Int = fluidBuf(i)

  // ---- パストレーシング (累積バッファを Scala 側に持ち、1 行ずつサンプルを足す)
  private var pt: PathTracer = new PathTracer(1, 1, 0, 1, true)

  @WasmExport("ptSetup")
  def ptSetup(w: Int, h: Int, seed: Int, maxDepth: Int, nee: Int): Unit =
    pt = new PathTracer(w, h, seed, maxDepth, nee != 0)

  @WasmExport("ptRenderRow")
  def ptRenderRow(y: Int): Unit = pt.renderRow(y)

  @WasmExport("ptPx")
  def ptPx(i: Int): Int = pt.pixel(i)

  @WasmExport("ptSpp")
  def ptSpp(y: Int): Int = pt.spp(y)

  /** これまでに飛ばした光線の本数 (影の判定を含む) */
  @WasmExport("ptRays")
  def ptRays(): Double = pt.rays.toDouble

  @WasmExport("fluidEnergy")
  def fluidEnergy(): Double = fluid.energy
}
