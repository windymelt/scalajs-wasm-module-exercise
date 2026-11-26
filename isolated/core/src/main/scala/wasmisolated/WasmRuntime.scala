package wasmisolated

import java.io.OutputStream
import java.time.Duration
import java.util.concurrent.{Executors, TimeUnit}

import org.graalvm.polyglot.*
import org.graalvm.polyglot.io.ByteSequence

/** マクロが置き換えた呼び出しを受けて、main.wasm の関数を GraalWasm で実行する */
object WasmRuntime:

  private lazy val source: Source =
    val in = getClass.getResourceAsStream("/wasm-isolated/main.wasm")
    require(in != null, "クラスパスに wasm-isolated/main.wasm がありません")
    try Source.newBuilder("wasm", ByteSequence.create(in.readAllBytes()), "main.wasm").build()
    finally in.close()

  /**
   * SandboxPolicy.ISOLATED: Wasm は JVM のヒープとは別のヒープ (ポリグロットアイソレート) で動き、JIT も効く。
   * UNTRUSTED にするとスタックの段数などの上限も必須になり、再帰が遅くなる (fib(35) で JVM の 3〜11 倍。ISOLATED は 1.2〜2.8 倍)。
   * 比べるときは -DwasmIsolated.policy=UNTRUSTED で切り替える。
   *
   * Engine を 1 つだけ作ってコンテキストで共有すると、コンパイル済みのコードと別ヒープを使い回せる。
   */
  private val policy = SandboxPolicy.valueOf(sys.props.getOrElse("wasmIsolated.policy", "ISOLATED"))
  private lazy val engine: Engine =
    Engine.newBuilder("wasm")
      .sandbox(policy)
      .out(OutputStream.nullOutputStream()).err(OutputStream.nullOutputStream())
      .option("engine.MaxIsolateMemory", "512MB")
      .build()

  /** 時間切れのブロックを止めるためのタイマー */
  private val watchdog = Executors.newSingleThreadScheduledExecutor { r =>
    val t = new Thread(r, "wasmIsolated-watchdog"); t.setDaemon(true); t
  }

  /** インスタンス 1 つ = コンテキスト 1 つ。失敗したら捨てて作り直す */
  private final class Instance(val limits: Limits):
    val context: Context =
      val b = Context.newBuilder("wasm").engine(engine)
        .sandbox(policy)
        .out(OutputStream.nullOutputStream()).err(OutputStream.nullOutputStream())
        .option("sandbox.MaxHeapMemory", limits.maxHeap)
        .option("sandbox.MaxCPUTime", "1h") // コンテキストの生涯の合計。1 回ごとの上限は interrupt でかける
      if policy == SandboxPolicy.UNTRUSTED then
        b.option("sandbox.MaxStackFrames", "10000")
          .option("sandbox.MaxThreads", "1")
          .option("sandbox.MaxASTDepth", "10000")
          .option("sandbox.MaxOutputStreamSize", "0B")
          .option("sandbox.MaxErrorStreamSize", "0B")
      b.build()
    val exports: Value = context.eval(source).newInstance().getMember("exports")

  private var cached: Option[Instance] = None

  private def instanceFor(limits: Limits): Instance =
    cached.filter(_.limits == limits).getOrElse {
      cached.foreach(_.context.close(true))
      val i = new Instance(limits)
      cached = Some(i)
      i
    }

  /** export された関数 name を呼び、戻り値を read で JVM の値にする (コンテキストを閉じる前に読む必要がある) */
  def call[T](name: String, limits: Limits, args: Any*)(read: Value => T): T = synchronized {
    val inst = instanceFor(limits)
    val f = inst.exports.getMember(name)
    require(f != null, s"main.wasm に $name がありません (sbt でリンクし直してください)")
    val timer = watchdog.schedule(
      (() => inst.context.interrupt(Duration.ofSeconds(5))): Runnable,
      limits.timeout.toMillis, TimeUnit.MILLISECONDS)
    try
      read(f.execute(args.map(_.asInstanceOf[AnyRef])*))
    catch
      case e: PolyglotException =>
        cached.foreach(_.context.close(true)) // 途中で止めたインスタンスは中の状態が壊れているかもしれないので捨てる
        cached = None
        if e.isResourceExhausted then throw new OutOfMemory(limits.maxHeap)
        else if e.isInterrupted || e.isCancelled then throw new TimedOut(limits.timeout)
        else throw new Crashed(Option(e.getMessage).getOrElse("(理由不明)"))
    finally timer.cancel(false)
  }
