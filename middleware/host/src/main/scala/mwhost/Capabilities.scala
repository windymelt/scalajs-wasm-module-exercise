package mwhost

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

import org.graalvm.polyglot.Value
import org.graalvm.polyglot.proxy.{ProxyExecutable, ProxyObject}

import scala.jdk.CollectionConverters.*

/**
 * ホストがプラグインに貸し出す機能。設定で許可したものだけをインポートとして渡す。
 * 許可されていない機能をインポートするプラグインは、インスタンス化できない。
 */
object Capabilities:
  /** 権限の名前 → (インポート名, 実装) */
  final case class Capability(field: String, description: String, impl: String => ProxyExecutable)

  private def fn(f: Seq[Value] => Any): ProxyExecutable = new ProxyExecutable:
    override def execute(args: Value*): AnyRef = f(args).asInstanceOf[AnyRef]

  // 固定時間窓のカウンター。キーは (プラグイン名, キーのハッシュ, 窓の番号)
  private val counters = new ConcurrentHashMap[(String, Long, Long), AtomicInteger]()
  @volatile private var lastSweep = 0L

  private def counterIncr(plugin: String, keyHash: Long, windowMs: Double): Int =
    val now = System.currentTimeMillis()
    val window = (now / math.max(1.0, windowMs)).toLong
    if now - lastSweep > 60000 then // 古い窓を片付ける
      lastSweep = now
      counters.keySet.removeIf(k => k._3 < window - 1)
    counters.computeIfAbsent((plugin, keyHash, window), _ => new AtomicInteger()).incrementAndGet()

  val all: Map[String, Capability] = Map(
    "now" -> Capability("now_millis", "現在時刻 (ミリ秒) を読む", _ => fn(_ => java.lang.Double.valueOf(System.currentTimeMillis().toDouble))),
    "counter" -> Capability("counter_incr", "全インスタンスで共有するカウンターを増やす", plugin =>
      fn(a => Integer.valueOf(counterIncr(plugin, a(0).asLong(), a(1).asDouble())))),
  )

  /** フィールド名 → 権限の名前 */
  val byField: Map[String, String] = all.map((name, c) => c.field -> name)

  def imports(plugin: String, grants: Set[String]): ProxyObject =
    val host = grants.toList.map(g => all(g).field -> (all(g).impl(plugin): AnyRef)).toMap
    ProxyObject.fromMap(Map[String, AnyRef]("host" -> ProxyObject.fromMap(host.asJava)).asJava)
