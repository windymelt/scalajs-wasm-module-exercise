package mwhost

import java.io.OutputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.time.Duration
import java.util.concurrent.{ArrayBlockingQueue, ScheduledExecutorService, TimeUnit}
import java.util.concurrent.atomic.{AtomicInteger, AtomicLong}

import org.graalvm.polyglot.*
import org.graalvm.polyglot.io.ByteSequence

/** プラグインを動かすサンドボックスの強さ */
enum Sandbox:
  /** JVM のヒープの中で動かす (GraalVM の CONSTRAINED)。時間制限は interrupt で行う */
  case Constrained
  /** 別のヒープ (ポリグロットアイソレート) で動かし、メモリ・CPU 時間・スタックに上限をかける (UNTRUSTED) */
  case Untrusted

final case class PluginSpec(name: String, path: Path, grants: Set[String], config: String)

final case class HostRequest(method: String, target: String, headers: Vector[(String, String)], body: Array[Byte], remote: String)
final case class HostResponse(status: Int, headers: Vector[(String, String)], body: Array[Byte])

/** プラグインが失敗した。理由はホストの利用者向けの文 */
final class PluginFailure(val plugin: String, val reason: String, val kind: String) extends RuntimeException(s"[$plugin] $reason")

object Headers:
  def render(hs: Vector[(String, String)]): String = hs.map((k, v) => s"$k: $v\r\n").mkString
  def parse(block: String): Vector[(String, String)] =
    block.split("\r\n").iterator.filter(_.nonEmpty).flatMap { line =>
      val i = line.indexOf(':')
      if i <= 0 then None else Some(line.substring(0, i).trim -> line.substring(i + 1).trim)
    }.toVector

final class WasmPlugin(val spec: PluginSpec, sandbox: Sandbox, poolSize: Int, timeoutMs: Long, scheduler: ScheduledExecutorService):
  private val bytes = Files.readAllBytes(spec.path)
  val info: WasmInspect.Info = WasmInspect(bytes)

  // ---- 権限の確認: 許可されていないインポートがあれば、読み込まない
  locally {
    val denied = info.imports.filterNot { (module, field) =>
      module == "host" && Capabilities.byField.get(field).exists(spec.grants.contains)
    }
    if denied.nonEmpty then
      val list = denied.map((m, f) => s"$m.$f").mkString(", ")
      throw new PluginFailure(spec.name, s"許可されていない機能を要求しているので読み込みません: $list", "denied")
    val unknown = spec.grants -- Capabilities.all.keySet
    if unknown.nonEmpty then throw new PluginFailure(spec.name, s"存在しない権限が設定されています: ${unknown.mkString(", ")}", "config")
  }

  /** 実際に使っている権限 (許可しても使っていないものは含まない) */
  val usedGrants: List[String] = info.imports.flatMap((_, f) => Capabilities.byField.get(f)).distinct

  private val source = Source.newBuilder("wasm", ByteSequence.create(bytes), spec.name).build()

  /**
   * プラグインごとに Engine を 1 つ作り、インスタンス (Context) はそれを共有する。
   * 解析・コンパイル済みのコードを使い回せるうえ、UNTRUSTED では隔離ヒープ (アイソレート) も 1 つで済む。
   */
  private val engine: Engine =
    val b = Engine.newBuilder("wasm")
      .out(OutputStream.nullOutputStream()).err(OutputStream.nullOutputStream())
      .option("engine.WarnInterpreterOnly", "false")
    sandbox match
      case Sandbox.Constrained => b.sandbox(SandboxPolicy.CONSTRAINED).build()
      case Sandbox.Untrusted => b.sandbox(SandboxPolicy.UNTRUSTED).option("engine.MaxIsolateMemory", "256MB").build()

  final class Instance:
    val context: Context =
      val b = Context.newBuilder("wasm").engine(engine)
        .out(OutputStream.nullOutputStream()).err(OutputStream.nullOutputStream())
      sandbox match
        case Sandbox.Constrained => b.sandbox(SandboxPolicy.CONSTRAINED).build()
        case Sandbox.Untrusted =>
          b.sandbox(SandboxPolicy.UNTRUSTED)
            .option("sandbox.MaxHeapMemory", "64MB")
            .option("sandbox.MaxCPUTime", "1h") // インスタンスが生きている間の合計。1 回ごとの制限は interrupt で別にかける
            .option("sandbox.MaxStackFrames", "1000")
            .option("sandbox.MaxThreads", "1")
            .option("sandbox.MaxASTDepth", "10000")
            .option("sandbox.MaxOutputStreamSize", "0B")
            .option("sandbox.MaxErrorStreamSize", "0B")
            .build()

    private val exports = context.eval(source).newInstance(Capabilities.imports(spec.name, spec.grants)).getMember("exports")
    private def f(name: String): Value = exports.getMember(name)
    private val fBegin = f("mw_begin")
    private val fPut = f("mw_put")
    private val fPut64 = f("mw_put64")
    private val fConfigure = f("mw_configure")
    private val fOnRequest = f("mw_on_request")
    private val fOnResponse = f("mw_on_response")
    private val fFlags = f("mw_flags")
    private val fStatus = f("mw_status")
    private val fOutLen = f("mw_out_len")
    private val fOutWord = f("mw_out_word")
    val wants: Int = f("mw_wants").execute().asInt()
    var uses = 0

    require(f("mw_abi_version").execute().asInt() == 1, "ABI のバージョンが合いません")
    configure()

    /** 8 バイトずつ Long に詰め、1 回の呼び出しで最大 64 バイト送る */
    def put(field: Int, data: Array[Byte]): Unit =
      def word(at: Int): java.lang.Long =
        var w = 0L
        var k = 0
        while k < 8 && at + k < data.length do { w |= (data(at + k) & 0xffL) << (8 * k); k += 1 }
        java.lang.Long.valueOf(w)
      var i = 0
      while i < data.length do
        val n = math.min(64, data.length - i)
        if WasmPlugin.batched then
          fPut64.execute(Integer.valueOf(field), Integer.valueOf(n),
            word(i), word(i + 8), word(i + 16), word(i + 24), word(i + 32), word(i + 40), word(i + 48), word(i + 56))
        else
          var j = 0
          while j < n do { fPut.execute(Integer.valueOf(field), word(i + j), Integer.valueOf(math.min(8, n - j))); j += 8 }
        i += 64

    def read(field: Int): Array[Byte] =
      val len = fOutLen.execute(Integer.valueOf(field)).asInt()
      val out = new Array[Byte](len)
      var i = 0
      while i * 8 < len do
        val w = fOutWord.execute(Integer.valueOf(field), Integer.valueOf(i)).asLong()
        var k = 0
        while k < 8 && i * 8 + k < len do { out(i * 8 + k) = ((w >>> (8 * k)) & 0xff).toByte; k += 1 }
        i += 1
      out

    def text(field: Int): String = new String(read(field), UTF_8)

    private def configure(): Unit =
      fBegin.execute()
      put(5, spec.config.getBytes(UTF_8))
      if fConfigure.execute().asInt() != 0 then throw new PluginFailure(spec.name, s"設定に失敗しました: ${text(5)}", "config")

    def onRequest(req: HostRequest): (Int, Int) =
      fBegin.execute()
      put(0, req.method.getBytes(UTF_8))
      put(1, req.target.getBytes(UTF_8))
      put(2, Headers.render(req.headers).getBytes(UTF_8))
      if (wants & 1) != 0 then put(3, req.body)
      put(4, req.remote.getBytes(UTF_8))
      val r = fOnRequest.execute().asInt()
      (r, fFlags.execute().asInt())

    def onResponse(resp: HostResponse): (Int, Int) =
      fBegin.execute()
      put(6, Headers.render(resp.headers).getBytes(UTF_8))
      if (wants & 2) != 0 then put(7, resp.body)
      val r = fOnResponse.execute(Integer.valueOf(resp.status)).asInt()
      (r, fFlags.execute().asInt())

    def status: Int = fStatus.execute().asInt()
    def close(): Unit = try context.close(true) catch case _: Throwable => ()

  // ---- インスタンスのプール (コンテキストは同時に 1 スレッドからしか使えないため)
  private val pool = new ArrayBlockingQueue[Instance](poolSize)
  for _ <- 0 until poolSize do pool.put(new Instance)

  val calls = new AtomicLong()
  val nanos = new AtomicLong()
  val failures = new AtomicInteger()
  val recycled = new AtomicInteger()

  def borrow(): Instance = pool.take()

  /** 失敗したインスタンスは捨てて作り直す */
  def release(inst: Instance, broken: Boolean): Unit =
    if broken || inst.uses > 50000 then
      inst.close()
      recycled.incrementAndGet()
      pool.put(new Instance)
    else pool.put(inst)

  /** 1 回の呼び出しに時間制限をかける。超えたら interrupt で止める */
  def timed[T](inst: Instance)(body: => T): T =
    inst.uses += 1
    val start = System.nanoTime()
    val watchdog = scheduler.schedule(
      (() => try inst.context.interrupt(Duration.ofSeconds(2)) catch case _: Throwable => inst.close()): Runnable,
      timeoutMs, TimeUnit.MILLISECONDS)
    try body
    catch
      case e: PolyglotException =>
        failures.incrementAndGet()
        val (kind, reason) =
          if e.isInterrupted || (e.isCancelled && !e.isResourceExhausted) then "timeout" -> s"${timeoutMs} ms 以内に終わらなかったので止めました"
          else if e.isResourceExhausted then "resource" -> s"資源の上限を超えたので止めました (${e.getMessage.linesIterator.nextOption().getOrElse("")})"
          else "crash" -> s"Wasm の実行が異常終了しました (${Option(e.getMessage).getOrElse(e.getClass.getSimpleName)})"
        throw new PluginFailure(spec.name, reason, kind)
    finally
      watchdog.cancel(false)
      calls.incrementAndGet()
      nanos.addAndGet(System.nanoTime() - start)

  def close(): Unit =
    var i = pool.poll()
    while i != null do { i.close(); i = pool.poll() }
    engine.close(true)

object WasmPlugin:
  /** false にすると、8 バイトずつ送る (比較用) */
  @volatile var batched: Boolean = !java.lang.Boolean.getBoolean("mw.unbatched")
