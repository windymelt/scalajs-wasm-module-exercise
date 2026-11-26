# wasmIsolated { ... } の試作

Scala 3 のブロックをコンパイル時に WebAssembly にし、実行時は JVM のヒープとは別の箱 (GraalWasm) で動かすマクロ。
時間とメモリに上限を付けられ、超えたらブロックだけを止めて、JVM はそのまま動き続ける。

```scala
given Limits = Limits(timeout = 500.millis, maxHeap = "32MB")

val n = 32L
val r = wasmIsolated {
  def fib(k: Long): Long = if (k < 2) k else fib(k - 1) + fib(k - 2)
  fib(n)   // 外の n は値として持ち込まれる
}
```

## 動かしかた

```sh
cd isolated
sbt demo/run                     # デモ (計算・時間切れ・メモリ切れ・深すぎる再帰)
sbt "demo/runMain bench.measure" # 計測
sbt "demo/runMain bench.errors"  # マクロが出すコンパイルエラーの一覧
```

JDK 21 で確かめた。GraalVM の JDK は要らない (GraalWasm は Maven の依存として入る)。

## しくみ

| 段階 | 誰が | 何をする |
|---|---|---|
| コンパイル時 | マクロ (`core/.../wasmIsolated.scala`) | ブロックの外から使っている変数を調べる。数値以外・外のメソッド・`this`・外の変数への代入はエラーにする。ブロックのソースを Scala.js 用の `@WasmExport` 付き関数として `demo/target/wasm-blocks/blk_<hash>.scala` に書き出し、呼び出しを `WasmRuntime.call("blk_<hash>", limits, n)` に置き換える |
| ビルド | sbt の `wasm` プロジェクト (Scala 2.13.18、Scala.js 1.23.0) | 書き出されたソースを `ModuleKind.WasmModule` で `main.wasm` にリンクし、`demo` の resources に入れる |
| 実行時 | `WasmRuntime` (GraalVM Polyglot 25.6.5.1) | `main.wasm` を別ヒープ (`SandboxPolicy.ISOLATED`) で動かす。時間切れは `Context.interrupt`、メモリは `sandbox.MaxHeapMemory` で止める。止めたインスタンスは捨てて作り直す |

ブロックの名前はソースと引数の型のハッシュなので、同じ中身のブロックは 1 つの関数にまとまる。

## 実測 (2026-11-26、クラウドの 4 コア VM、OpenJDK 21.0.13)

`sbt demo/run` の出力:

```
fib(32): 2178309
終わらない計算: 500 ms 以内に終わらなかったので止めました
メモリを使い果たす計算: ヒープの上限 32MB を超えたので止めました
深すぎる再帰: Wasm の実行が異常終了しました: call stack exhausted
そのあとの fib(20): 6765
```

| 測ったこと | 結果 |
|---|---|
| fib(35)、JVM (同じ Scala のコード) | 50〜70 ms |
| fib(35)、wasmIsolated (ISOLATED、2 回目以降) | 60〜140 ms |
| fib(35)、wasmIsolated (UNTRUSTED、2 回目以降) | 160〜620 ms |
| fib(35)、同じ main.wasm を Node.js 22 で | 63 ms |
| 初回の呼び出し (別ヒープとインスタンスを作る) | 約 600 ms |
| 小さな呼び出し (`a + b`) 1 回 | 約 15〜20 µs (タイマーの登録を含む) |
| 上限 100 ms / 500 ms / 1 s の暴走が止まるまで | 125〜130 ms / 505 ms / 1,005 ms |
| `clean` からデモの実行まで (sbt の起動を除く) | 16 s |
| main.wasm の大きさ (ブロック 5 個) | 44.9 KB |

- 計測は同じ VM で何度か回し、ばらつきが大きかった。範囲で書いている。
- UNTRUSTED が遅いのは、必須になる `sandbox.MaxStackFrames` などの上限のためだと考えているが、原因は切り分けていない (未確認)。
- 比較: JDK 21 の `Thread.stop` は `UnsupportedOperationException` を投げ、`Thread.interrupt` では計算だけのループは止まらなかった (`Stop.java` で確認)。

## 制約

- ブロックの中は Scala 2.13 で通る書き方にする (`-Xsource:3` は付けてある)。`if ... then` や字下げの構文は使えない。
- 持ち込めるのは `Int` / `Long` / `Float` / `Double` の値、返せるのはそれと `Unit`。
- `Math.sin` や `println` など JS に頼る標準ライブラリはリンクエラーになる。エラーは生成された `Blk_<hash>` から辿れる。
- ブロックを消しても、生成済みのファイルは `demo/clean` するまで残る (害はなく、使われない関数が増えるだけ)。
- 書き出し先は「呼び出し側のソースの `src` の隣の `target/wasm-blocks`」と決め打ちしている (`-Xmacro-settings` を読む API が Scala 3.10.1 でも experimental のため)。
- ブロック 1 回ごとに別スレッドのタイマーを登録している。呼び出しが細かいと、これが固定費の大半になる。
