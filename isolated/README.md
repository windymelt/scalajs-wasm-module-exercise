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
sbt "demo/runMain bench.arrays"  # 配列の受け渡しの確認と計測
sbt "demo/runMain bench.cbor"    # case class や String などを CBOR (borer) で受け渡す確認と計測
```

JDK 21 で確かめた。GraalVM の JDK は要らない (GraalWasm は Maven の依存として入る)。

## しくみ

| 段階 | 誰が | 何をする |
|---|---|---|
| コンパイル時 | マクロ (`core/.../wasmIsolated.scala`) | ブロックの外から使っている変数を調べる。数値・数値の配列・borer の Encoder と Decoder がある型の値以外、外のメソッド・`this`・外の変数への代入・外の配列の要素の書き換えはエラーにする。ソースを 2 つ書き出し、呼び出しを `WasmRuntime.call("blk_<hash>", limits, n)` に置き換える |
| ビルド (1) | sbt の `blocks` プロジェクト (Scala.js 1.23.0、Scala 3.7.4) | `demo/target/wasm-blocks/blocks/` の、ブロックを Scala 3 のまま `def run(n: Long): Long` にしたソースをコンパイルする |
| ビルド (2) | sbt の `wasm` プロジェクト (Scala.js 1.23.0、Scala 2.13.18、`-Ytasty-reader`) | `demo/target/wasm-blocks/exports/` の、`@WasmExport` を付けて (1) を呼ぶだけの窓口をコンパイルし、`ModuleKind.WasmModule` で `main.wasm` にリンクして `demo` の resources に入れる |
| 実行時 | `WasmRuntime` (GraalVM Polyglot 25.6.5.1) | `main.wasm` を別ヒープ (`SandboxPolicy.ISOLATED`) で動かす。時間切れは `Context.interrupt`、メモリは `sandbox.MaxHeapMemory` で止める。止めたインスタンスは捨てて作り直す |

配列は、`wasm/src/main/scala/wasmisolated/exports/Alloc.scala` の `wasmIsolated_alloc_Int` などで Wasm 側に配列を作ってもらい、`WasmRuntime` が要素を書き込んでから引数に渡す (GraalWasm には JVM の `int[]` をそのまま渡せないため)。返ってきた Wasm の配列は `Value.as(int[].class)` で JVM の配列に写す。利用する人からは、普通の `Array[Int]` を渡して受け取るだけに見える。

```scala
def evens(xs: Array[Int]): Array[Int] =
  wasmIsolated:
    xs.filter(_ % 2 == 0).map(_ * 10)

evens(Array(1, 2, 3, 4, 5, 6))  // Array(20, 40, 60)
```

数値でも数値の配列でもない値は、[borer](https://github.com/sirthias/borer) で CBOR のバイト列にして `Array[Byte]` として渡す。マクロは JVM 側で `Encoder` と `Decoder` があるかを調べ、Wasm 側のブロックの前後に `Cbor.decode` と `Cbor.encode` を足す。`String`、`List`、`Option`、`Map` は borer が最初から扱える。case class は `derives Codec` を付け、JVM と Wasm の両方でコンパイルされる `shared/src/main/scala` に置く。

```scala
// shared/src/main/scala/shop/Order.scala
final case class Item(name: String, unitPrice: Int, quantity: Int) derives Codec
final case class Order(customer: String, items: List[Item], coupon: Option[String]) derives Codec
final case class Receipt(lines: List[String], total: Int, discount: Int) derives Codec

// demo/src/main/scala/Demo.scala
def checkout(order: Order): Receipt =
  wasmIsolated:
    import shop.Receipt // ブロックの中は呼び出し側の import を引き継がない
    val lines = order.items.map(i => s"${i.name} x${i.quantity} = ${i.unitPrice * i.quantity}")
    val subtotal = order.items.map(i => i.unitPrice * i.quantity).sum
    val discount = if order.coupon.contains("BEER10") then subtotal / 10 else 0
    Receipt(lines, subtotal - discount, discount)
```

このとき生成されるブロック:

```scala
object Blk_d4fc2c6084df:
  def run(order__cbor: Array[Byte]): Array[Byte] = {
    val order: shop.Order = io.bullet.borer.Cbor.decode(order__cbor).to[shop.Order].value
    val result__cbor: shop.Receipt = {
      import shop.Receipt
      ...
      Receipt(lines, subtotal - discount, discount)
    }
    io.bullet.borer.Cbor.encode(result__cbor).toByteArray
  }
```

ブロックの名前はソースと引数の型のハッシュなので、同じ中身のブロックは 1 つの関数にまとまる。

窓口だけを Scala 2.13 で書くのは、Scala 3 のコンパイラが `@WasmExport` を処理せず、出力が空になるため (`docs/HANDOFF.md`)。ブロック自体は Scala 3 でコンパイルするので、`if ... then`、`while ... do`、`wasmIsolated:` に続く字下げの構文、ブロックの中の `enum` や `extension` も使える (`sbt "demo/runMain bench.syntax"`)。

生成されるソースの例:

```scala
// blocks/blk_1a33082bde25.scala (Scala 3)
object Blk_1a33082bde25:
  def run(n: Long): Long = {
    def fib(k: Long): Long = if k < 2 then k else fib(k - 1) + fib(k - 2)
    fib(n)
  }

// exports/blk_1a33082bde25.scala (Scala 2.13)
object Blk_1a33082bde25 {
  @WasmExport("blk_1a33082bde25")
  def run(n: Long): Long = wasmisolated.blocks.Blk_1a33082bde25.run(n)
}
```

## 実測 (2026-11-26、クラウドの 4 コア VM、OpenJDK 21.0.13)

`sbt demo/run` の出力:

```
fib(32): 2178309
終わらない計算: 500 ms 以内に終わらなかったので止めました
メモリを使い果たす計算: ヒープの上限 32MB を超えたので止めました
深すぎる再帰: Wasm の実行が異常終了しました: call stack exhausted
配列の受け渡し: [20, 40, 60]
case class の受け渡し: Receipt(List(寿司 x2 = 3600, ビール x3 = 1800),4860,540)
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

配列の受け渡し (2026-11-27、ISOLATED、`sbt "demo/runMain bench.arrays"`):

| `Array[Int]` の要素数 | 持ち込む | 受け取る | 往復 (`xs.reverse`) |
|---|---|---|---|
| 1,000 | 2.6〜3.9 ms | 2.1〜3.0 ms | 5.3〜6.0 ms |
| 100,000 | 109〜122 ms | 115〜116 ms | 241〜243 ms |
| 1,000,000 | 1.10〜1.14 s | 1.18〜1.24 s | 2.35〜2.40 s |

- 1 要素あたり約 1.1〜1.2 µs。Java と別ヒープの間を 1 要素ずつ往復するため (`getArrayElement` と `as(int[].class)` は同じくらいの速さだった)。
- `Byte` / `Short` / `Int` / `Long` / `Float` / `Double` の 6 種類と空の配列で、往復した値が一致した。
CBOR (borer 1.19.0) での受け渡し (2026-11-27、ISOLATED、`sbt "demo/runMain bench.cbor"`、2 回目以降):

| `Order` の商品の数 (CBOR の大きさ) | JVM で合計 | 持ち込んで合計 | `Order` の往復 |
|---|---|---|---|
| 10 件 (406 バイト) | 0.02 ms | 2.6〜3.9 ms | 5.9〜6.8 ms |
| 1,000 件 (37,936 バイト) | 0.3〜0.6 ms | 68〜122 ms | 130〜135 ms |
| 10,000 件 (388,936 バイト) | 2.6〜2.7 ms | 452〜505 ms | 979〜1,006 ms |

- `String`、`List[Int]`、`Map[String, Int]`、case class の往復で、値が一致した。
- 時間の大半は、バイト列を 1 要素ずつ写す部分 (1 バイトあたり約 1.2 µs) だと考えているが、borer の処理と切り分けては測っていない (未確認)。
- borer をリンクすると main.wasm は 44.9 KB から 497 KB になった (ブロック 14 個)。
- 計測は同じ VM で何度か回し、ばらつきが大きかった。範囲で書いている。
- ブロックを Scala 3 でコンパイルする形に変えたあと (同じ日の 20:52 UTC) は、fib(35) が JVM 31〜38 ms、wasmIsolated (ISOLATED) 73〜96 ms、初回の呼び出し 351 ms だった。VM 全体が速くなっていたので、上の表とは直接比べられない。
- UNTRUSTED が遅いのは、必須になる `sandbox.MaxStackFrames` などの上限のためだと考えているが、原因は切り分けていない (未確認)。
- 比較: JDK 21 の `Thread.stop` は `UnsupportedOperationException` を投げ、`Thread.interrupt` では計算だけのループは止まらなかった (`Stop.java` で確認)。

## 制約

- ブロックの中の Scala は 3.7.4 でコンパイルされる。Scala.js 側の Scala 3 は、標準ライブラリの都合で 3.7 系より新しくできない (3.8 以降は `scala-library 3.x` になり、2.13 の窓口から読めない)。
- ブロックのソースは文字列のまま写すので、呼び出し側の `import` は引き継がない。ブロックの中で使うものはブロックの中で `import` する。
- 持ち込めるのは `Int` / `Long` / `Float` / `Double` の値、`Array[Byte]` から `Array[Double]` までの数値の配列、borer の `Encoder` と `Decoder` がある型の値。返せるのはそれと `Unit`。
- CBOR で渡す型と、ブロックの中から使う外の定義は、パスに `shared` というディレクトリを含むソースに置く (マクロはそれで「Wasm 側にもある」と判断している)。ほかの場所の型でもマクロは通るが、`blocks` のコンパイルでエラーになる。
- CBOR で渡す値は、境界を越えるたびに encode、写し、decode が起きる。大きな構造を何度も渡す使い方には向かない。
- 配列は Wasm の中へ写しが渡る。外の配列を `xs(i) = v` で書き換えるとコンパイルエラーにしているが、`xs.sortInPlace()` のようなメソッドでの書き換えは見逃す (書き換えても外の配列は変わらない)。
- 配列の写しは 1 要素あたり約 1 µs かかる。時間の上限には含めていない (止めるのはブロックの計算だけ)。メモリは Wasm 側のヒープの上限に含まれる。8 バイトずつ `Long` に詰めて要素数を減らすと速くなるかは、まだ確かめていない。
- `Math.sin` や `println` など JS に頼る標準ライブラリはリンクエラーになる。エラーは生成された `Blk_<hash>` から辿れる。
- ブロックを消したり書き換えたりしても、古い生成ファイルは `demo/clean` するまで残る。ふだんは使われない関数が増えるだけだが、古いブロックが Wasm にリンクできないもの (正規表現など) を使っていると、リンクエラーになる (実際に起きた)。そのときは `sbt demo/clean` する。
- 書き出し先は「呼び出し側のソースの `src` の隣の `target/wasm-blocks`」と決め打ちしている (`-Xmacro-settings` を読む API が Scala 3.10.1 でも experimental のため)。
- ブロック 1 回ごとに別スレッドのタイマーを登録している。呼び出しが細かいと、これが固定費の大半になる。
