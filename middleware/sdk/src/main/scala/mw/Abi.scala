package mw

import java.nio.charset.StandardCharsets.UTF_8

import scala.scalajs.wasm
import scala.scalajs.wasm.annotation._

/**
 * ホストとプラグインの境目 (ABI)。数値しか越えられないので、バイト列は 8 バイトずつ Long に詰めて受け渡す。
 *
 * 1 回のリクエストの流れ:
 *   mw_begin()
 *   mw_put(field, word, n)…   ← 0: メソッド, 1: パスとクエリ, 2: ヘッダー, 3: 本文, 4: 接続元
 *   mw_on_request()           → 0: 続行, 1: ここで応答, 2: エラー
 *   (アプリが応答したら)
 *   mw_put(field, word, n)…   ← 6: 応答ヘッダー, 7: 応答本文
 *   mw_on_response(status)    → 0: 変更なし, 1: 変更あり, 2: エラー
 *
 * 結果は mw_flags / mw_status / mw_out_len(field) / mw_out_word(field, i) で読む。
 */
object Abi {
  // 入力のフィールド
  final val InMethod = 0
  final val InTarget = 1
  final val InHeaders = 2
  final val InBody = 3
  final val InRemote = 4
  final val InConfig = 5
  final val InRespHeaders = 6
  final val InRespBody = 7

  // 出力のフィールド
  final val OutTarget = 0
  final val OutReqHeaders = 1
  final val OutRespHeaders = 2
  final val OutRespBody = 3
  final val OutLog = 4
  final val OutError = 5

  // mw_flags のビット
  final val FlagTarget = 1
  final val FlagReqHeaders = 2
  final val FlagRespHeaders = 4
  final val FlagRespBody = 8

  private var middleware: Middleware = null

  /** プラグインの main から呼ぶ */
  def install(m: Middleware): Unit = middleware = m

  private val in = Array.fill(8)(new java.io.ByteArrayOutputStream)
  private val out = Array.fill(6)(Array.emptyByteArray)
  private var flags = 0
  private var status = 0
  private var lastRequest: Request = null
  private val logs = new java.lang.StringBuilder

  private def text(field: Int): String = new String(in(field).toByteArray, UTF_8)
  private def setOut(field: Int, bytes: Array[Byte]): Unit = out(field) = bytes

  private object HostCtx extends Ctx {
    def log(message: String): Unit = logs.append(message).append('\n')
    def nowMillis(): Double = Capabilities.nowMillis()
    def counterIncr(key: String, windowMs: Double): Int = Capabilities.counterIncr(Fnv.hash64(key), windowMs)
  }

  /** 例外はホストへメッセージが届かないので、ここで捕まえて OutError に書く */
  private def guard(body: => Int): Int = {
    val r =
      try body
      catch {
        case t: Throwable =>
          setOut(OutError, s"${t.getClass.getName}: ${t.getMessage}".getBytes(UTF_8))
          2
      }
    setOut(OutLog, logs.toString.getBytes(UTF_8))
    logs.setLength(0)
    r
  }

  @WasmExport("mw_abi_version") def abiVersion(): Int = 1

  /** 1: 要求の本文がほしい, 2: 応答の本文がほしい, 4: 応答のフックがある */
  @WasmExport("mw_wants")
  def wants(): Int =
    (if (middleware.needsRequestBody) 1 else 0) |
      (if (middleware.needsResponseBody) 2 else 0) |
      (if (middleware.hasResponseHook) 4 else 0)

  @WasmExport("mw_begin")
  def begin(): Unit = {
    var i = 0
    while (i < in.length) { in(i).reset(); i += 1 }
    i = 0
    while (i < out.length) { out(i) = Array.emptyByteArray; i += 1 }
    flags = 0
    status = 0
  }

  /** word の下位から n バイト (1〜8) を field に足す */
  @WasmExport("mw_put")
  def put(field: Int, word: Long, n: Int): Unit = {
    val buf = in(field)
    var k = 0
    while (k < n) { buf.write(((word >>> (8 * k)) & 0xff).toInt); k += 1 }
  }

  /** 1 回で最大 64 バイト送る。境界をまたぐ呼び出しの回数を減らすため */
  @WasmExport("mw_put64")
  def put64(field: Int, n: Int, w0: Long, w1: Long, w2: Long, w3: Long, w4: Long, w5: Long, w6: Long, w7: Long): Unit = {
    var left = n
    def take(w: Long): Unit = if (left > 0) { put(field, w, Math.min(8, left)); left -= 8 }
    take(w0); take(w1); take(w2); take(w3); take(w4); take(w5); take(w6); take(w7)
  }

  @WasmExport("mw_configure")
  def configure(): Int = guard {
    val config = Text.words(text(InConfig)).iterator.filter(_.contains("=")).map { kv =>
      val i = kv.indexOf('=')
      kv.substring(0, i) -> kv.substring(i + 1)
    }.toMap
    middleware.configure(config)
    0
  }

  @WasmExport("mw_on_request")
  def onRequest(): Int = guard {
    val req = Request(text(InMethod), text(InTarget), Headers.parse(text(InHeaders)), in(InBody).toByteArray, text(InRemote))
    lastRequest = req
    middleware.onRequest(req, HostCtx) match {
      case Action.Continue(next) =>
        lastRequest = next
        if (next.target != req.target) { setOut(OutTarget, next.target.getBytes(UTF_8)); flags |= FlagTarget }
        if (next.headers != req.headers) { setOut(OutReqHeaders, next.headers.render.getBytes(UTF_8)); flags |= FlagReqHeaders }
        0
      case Action.Respond(resp) =>
        status = resp.status
        setOut(OutRespHeaders, resp.headers.render.getBytes(UTF_8))
        setOut(OutRespBody, resp.body)
        flags |= FlagRespHeaders | FlagRespBody
        1
    }
  }

  @WasmExport("mw_on_response")
  def onResponse(st: Int): Int = guard {
    val resp = Response(st, Headers.parse(text(InRespHeaders)), in(InRespBody).toByteArray)
    val next = middleware.onResponse(lastRequest, resp, HostCtx)
    if (next eq resp) 0
    else {
      status = next.status
      if (next.headers != resp.headers) { setOut(OutRespHeaders, next.headers.render.getBytes(UTF_8)); flags |= FlagRespHeaders }
      if (!java.util.Arrays.equals(next.body, resp.body)) { setOut(OutRespBody, next.body); flags |= FlagRespBody }
      1
    }
  }

  @WasmExport("mw_flags") def outFlags(): Int = flags
  @WasmExport("mw_status") def outStatus(): Int = status
  @WasmExport("mw_out_len") def outLen(field: Int): Int = out(field).length

  /** out(field) の i 番目の 8 バイトをリトルエンディアンで返す */
  @WasmExport("mw_out_word")
  def outWord(field: Int, i: Int): Long = {
    val b = out(field)
    var w = 0L
    var k = 0
    val base = i * 8
    while (k < 8 && base + k < b.length) { w |= (b(base + k) & 0xffL) << (8 * k); k += 1 }
    w
  }
}

/** ホストが許可したときだけ使える機能。呼び出すコードがなければ、インポートとしてリンクされない */
object Capabilities {
  @WasmImport("host", "now_millis")
  def nowMillis(): Double = wasm.native

  @WasmImport("host", "counter_incr")
  def counterIncr(keyHash: Long, windowMs: Double): Int = wasm.native
}

/** 文字列のキーを 64 ビットに縮める (FNV-1a)。境界は数値しか越えられないため */
object Fnv {
  def hash64(s: String): Long = {
    var h = 0xcbf29ce484222325L
    val bytes = s.getBytes(UTF_8)
    var i = 0
    while (i < bytes.length) { h ^= (bytes(i) & 0xff); h *= 0x100000001b3L; i += 1 }
    h
  }
}
