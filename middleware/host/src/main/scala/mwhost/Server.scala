package mwhost

import java.net.InetSocketAddress
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse as JHttpResponse}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.util.concurrent.{Executors, ScheduledExecutorService}
import java.util.concurrent.atomic.AtomicInteger

import com.sun.net.httpserver.{HttpExchange, HttpServer}

import scala.jdk.CollectionConverters.*
import scala.util.Using

/** 設定ファイルの 1 行: 名前 | .wasm | 許可する権限 | 設定 */
object Config:
  def load(file: Path): List[PluginSpec] =
    val base = file.toAbsolutePath.getParent
    Files.readAllLines(file).asScala.toList.map(_.trim).filter(l => l.nonEmpty && !l.startsWith("#")).map { line =>
      line.split("\\|", -1).map(_.trim).toList match
        case name :: wasm :: grants :: config :: Nil =>
          PluginSpec(name, base.resolve(wasm), grants.split("[,\\s]+").filter(_.nonEmpty).toSet, config)
        case _ => sys.error(s"設定の行の形が正しくありません: $line")
    }

/** プラグインを玉ねぎのように重ねる。要求は上から順に、応答は逆順にたどる */
final class Pipeline(val plugins: List[WasmPlugin], app: HostRequest => HostResponse):
  private def failure(f: PluginFailure): HostResponse =
    val status = if f.kind == "timeout" || f.kind == "resource" then 503 else 502
    val body = s"""{"error":"middleware failure","plugin":"${f.plugin}","kind":"${f.kind}"}"""
    HostResponse(status, Vector("Content-Type" -> "application/json; charset=utf-8"), body.getBytes(UTF_8))

  private def logs(p: WasmPlugin, inst: p.Instance): Unit =
    inst.text(4).linesIterator.filter(_.nonEmpty).foreach(l => println(s"  [${p.spec.name}] $l"))

  def handle(req0: HostRequest): HostResponse =
    // (プラグイン, インスタンス, 壊れたか) を借りた順に積む
    val borrowed = scala.collection.mutable.ArrayBuffer.empty[(WasmPlugin, Any, Boolean)]
    var req = req0
    var shortCircuit: Option[HostResponse] = None
    try
      val it = plugins.iterator
      while shortCircuit.isEmpty && it.hasNext do
        val p = it.next()
        val inst = p.borrow()
        borrowed += ((p, inst, false))
        try
          val (r, flags) = p.timed(inst)(inst.onRequest(req))
          logs(p, inst)
          r match
            case 0 =>
              if (flags & 1) != 0 then req = req.copy(target = inst.text(0))
              if (flags & 2) != 0 then req = req.copy(headers = Headers.parse(inst.text(1)))
            case 1 =>
              shortCircuit = Some(HostResponse(inst.status, Headers.parse(inst.text(2)), inst.read(3)))
            case _ =>
              println(s"  [${p.spec.name}] プラグインの例外: ${inst.text(5)}")
              p.failures.incrementAndGet()
              shortCircuit = Some(failure(new PluginFailure(p.spec.name, inst.text(5), "exception")))
        catch
          case f: PluginFailure =>
            println(s"  ${f.getMessage}")
            borrowed(borrowed.size - 1) = (p, inst, true)
            shortCircuit = Some(failure(f))

      // 応答: 打ち切ったプラグインより手前のものだけ、逆順にたどる
      val passed = if shortCircuit.isDefined then borrowed.size - 1 else borrowed.size
      var resp = shortCircuit.getOrElse(app(req))
      var i = passed - 1
      while i >= 0 do
        val (p, any, broken) = borrowed(i)
        val inst = any.asInstanceOf[p.Instance]
        if !broken && (inst.wants & 4) != 0 then
          try
            val (r, flags) = p.timed(inst)(inst.onResponse(resp))
            logs(p, inst)
            if r == 1 then
              resp = resp.copy(
                status = inst.status,
                headers = if (flags & 4) != 0 then Headers.parse(inst.text(2)) else resp.headers,
                body = if (flags & 8) != 0 then inst.read(3) else resp.body,
              )
            else if r == 2 then
              p.failures.incrementAndGet()
              resp = failure(new PluginFailure(p.spec.name, inst.text(5), "exception"))
          catch
            case f: PluginFailure =>
              println(s"  ${f.getMessage}")
              borrowed(i) = (p, inst, true)
              resp = failure(f)
        i -= 1
      resp
    finally
      borrowed.foreach((p, inst, broken) => p.release(inst.asInstanceOf[p.Instance], broken))

/** ミドルウェアの向こう側にいるアプリ */
object App:
  private def json(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")

  def apply(pipeline: => Pipeline)(req: HostRequest): HostResponse =
    def text(status: Int, body: String) = HostResponse(status, Vector("Content-Type" -> "text/plain; charset=utf-8", "Server" -> "JDK-HttpServer", "X-Powered-By" -> "Scala 3"), body.getBytes(UTF_8))
    def jsonResp(status: Int, body: String) = HostResponse(status, Vector("Content-Type" -> "application/json; charset=utf-8", "Server" -> "JDK-HttpServer"), body.getBytes(UTF_8))
    val path = req.target.takeWhile(_ != '?')
    val query = req.target.dropWhile(_ != '?').drop(1)
    path match
      case "/" => text(200, "こんにちは。ここは Scala.js の Wasm ミドルウェアの向こう側にあるアプリです。\n")
      case "/hello" =>
        val name = query.split("&").collectFirst { case s"name=$v" => java.net.URLDecoder.decode(v, UTF_8) }.getOrElse("world")
        text(200, s"Hello, $name!\n")
      case "/echo" =>
        val hs = req.headers.map((k, v) => s""""${json(k)}":"${json(v)}"""").mkString(",")
        jsonResp(200, s"""{"method":"${req.method}","target":"${json(req.target)}","headers":{$hs}}""")
      case "/admin/stats" =>
        val user = req.headers.collectFirst { case (k, v) if k.equalsIgnoreCase("X-Authenticated-User") => v }.getOrElse("?")
        val ps = pipeline.plugins.map { p =>
          val avg = if p.calls.get == 0 then 0.0 else p.nanos.get / 1000.0 / p.calls.get
          f"""{"name":"${p.spec.name}","calls":${p.calls.get},"avgMicros":$avg%.1f,"failures":${p.failures.get},"recycled":${p.recycled.get}}"""
        }.mkString(",")
        jsonResp(200, s"""{"user":"$user","plugins":[$ps]}""")
      case p if p.startsWith("/evil/") => text(200, "evil のプラグインを無事に通り抜けました。\n")
      case _ => text(404, "見つかりません。\n")

object Main:
  def main(args: Array[String]): Unit =
    // JDK の HttpServer は既定で Nagle が有効で、遅延 ACK と噛み合うと 1 応答に約 40 ms かかる
    System.setProperty("sun.net.httpserver.nodelay", "true")
    run(args)

  private def run(args: Array[String]): Unit = args.toList match
    case "serve" :: rest => serve(rest)
    case "inspect" :: file :: Nil => inspect(Path.of(file))
    case "bench" :: url :: n :: conc :: Nil => bench(url, n.toInt, conc.toInt)
    case _ =>
      println("使い方: serve [--config server.conf] [--port 8080] [--sandbox untrusted|constrained] [--pool 2] [--timeout-ms 200]")
      println("        inspect <plugin.wasm>")
      println("        bench <url> <requests> <concurrency>")

  private def inspect(file: Path): Unit =
    val info = WasmInspect(Files.readAllBytes(file))
    println(s"$file (${Files.size(file) / 1024} KB)")
    println(s"  要求する機能: ${if info.imports.isEmpty then "なし" else info.imports.map((m, f) => s"$m.$f" + Capabilities.byField.get(f).fold(" (ホストにない機能)")(g => s" (権限 $g)")).mkString(", ")}")
    println(s"  エクスポート: ${info.exports.mkString(", ")}")

  private def serve(args: List[String]): Unit =
    def opt(name: String, default: String) = args.sliding(2).collectFirst { case List(`name`, v) => v }.getOrElse(default)
    val config = Path.of(opt("--config", "server.conf"))
    val port = opt("--port", "8080").toInt
    val sandbox = if opt("--sandbox", "untrusted") == "constrained" then Sandbox.Constrained else Sandbox.Untrusted
    val poolSize = opt("--pool", "2").toInt
    val timeoutMs = opt("--timeout-ms", "200").toLong
    val scheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(1)

    println(s"サンドボックス: $sandbox, プール: $poolSize, 時間制限: $timeoutMs ms")
    val t0 = System.nanoTime()
    val plugins =
      try Config.load(config).map { spec =>
        val t = System.nanoTime()
        val p = new WasmPlugin(spec, sandbox, poolSize, timeoutMs, scheduler)
        println(f"  読み込み: ${spec.name}%-17s ${Files.size(spec.path) / 1024}%4d KB  権限: ${if p.usedGrants.isEmpty then "なし" else p.usedGrants.mkString(", ")}%-8s ${(System.nanoTime() - t) / 1e6}%.0f ms")
        p
      }
      catch
        case f: PluginFailure =>
          println(s"起動を中止します: ${f.getMessage}")
          sys.exit(1)
    println(f"プラグイン ${plugins.size} 個を ${(System.nanoTime() - t0) / 1e9}%.1f 秒で読み込みました")

    lazy val pipeline: Pipeline = new Pipeline(plugins, App(pipeline))
    val server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 64)
    server.setExecutor(Executors.newFixedThreadPool(8))
    server.createContext("/", (ex: HttpExchange) =>
      try
        val req = HostRequest(
          ex.getRequestMethod,
          ex.getRequestURI.getRawPath + Option(ex.getRequestURI.getRawQuery).fold("")("?" + _),
          ex.getRequestHeaders.asScala.toVector.flatMap((k, vs) => vs.asScala.map(k -> _)),
          ex.getRequestBody.readNBytes(1 << 20),
          ex.getRemoteAddress.getAddress.getHostAddress,
        )
        val resp = pipeline.handle(req)
        resp.headers.foreach((k, v) => ex.getResponseHeaders.add(k, v))
        ex.sendResponseHeaders(resp.status, if resp.body.isEmpty then -1 else resp.body.length)
        if resp.body.nonEmpty then Using.resource(ex.getResponseBody)(_.write(resp.body))
      catch case e: Throwable => e.printStackTrace()
      finally ex.close()
    )
    server.start()
    println(s"http://127.0.0.1:$port/ で待ち受けています")

  private def bench(url: String, n: Int, concurrency: Int): Unit =
    val client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
    val done = new AtomicInteger()
    val statuses = new java.util.concurrent.ConcurrentHashMap[Int, AtomicInteger]()
    val pool = Executors.newFixedThreadPool(concurrency)
    val t0 = System.nanoTime()
    val futures = (0 until n).map { _ =>
      pool.submit((() =>
        val r = client.send(HttpRequest.newBuilder(URI.create(url)).build(), JHttpResponse.BodyHandlers.discarding())
        statuses.computeIfAbsent(r.statusCode, _ => new AtomicInteger()).incrementAndGet()
        done.incrementAndGet()
      ): Runnable)
    }
    futures.foreach(_.get())
    val sec = (System.nanoTime() - t0) / 1e9
    pool.shutdown()
    println(f"$n リクエスト / 同時 $concurrency: ${sec}%.2f 秒, ${n / sec}%.0f req/s, 状態: ${statuses.asScala.map((k, v) => s"$k×${v.get}").mkString(", ")}")
