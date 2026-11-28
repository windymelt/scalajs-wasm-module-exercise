package wasmisolated

import scala.concurrent.duration.*

/** wasmIsolated のブロック 1 回あたりの上限 */
final case class Limits(timeout: FiniteDuration = 2.seconds, maxHeap: String = "64MB")

object Limits:
  /** 文脈に Limits がなければ、これを使う */
  given default: Limits = Limits()

/** ブロックを途中で止めた、または失敗した */
sealed abstract class IsolationFailure(message: String) extends RuntimeException(message)
final class TimedOut(val limit: FiniteDuration) extends IsolationFailure(s"${limit.toMillis} ms 以内に終わらなかったので止めました")
final class OutOfMemory(val limit: String) extends IsolationFailure(s"ヒープの上限 $limit を超えたので止めました")
final class Crashed(detail: String) extends IsolationFailure(s"Wasm の実行が異常終了しました: $detail")
