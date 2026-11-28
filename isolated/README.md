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
sbt "demo/runMain bench.images"  # 画像処理を隔離する確認と計測
sbt "demo/runMain bench.imagesJvm" # 比べるために、細工した画像を JVM でそのまま処理する (1 分ほどかかる)
sbt "demo/runMain bench.convertFile in.png out.qoi 2"  # ファイルを変換する
sbt "demo/runMain bench.pngCheck /tmp/pngcases"  # DEFLATE と PNG の読み込みを別の実装と比べる (tools/png_cases.py で入力を作る)
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

## 画像処理を隔離する (2026-11-27)

`shared/src/main/scala/imaging/` に、外部のライブラリを使わずに書いた小さな画像処理を置いた。JVM と Wasm の両方でコンパイルされるので、同じコードを JVM でもブロックの中でも呼べる。

- `Png`: PNG の読み込み。インターレースなし、色の種類はすべて、ビット深度はグレーとパレットが 1/2/4/8、ほかが 8/16 (16 は上位 8 ビットを使う)。透明色の tRNS はパレットのときだけ読む
- `Inflate`: zlib と DEFLATE の展開。Scala.js の標準ライブラリには `java.util.zip` が無い (scalajs-javalib 1.23.0 の jar で確認) ので自前で書いた。ハフマン符号は 1 ビットずつ読んで引く (zlib に付属する puff.c と同じやりかた)
- `Qoi`: [QOI](https://qoiformat.org/) の読み書き。直前の画素の繰り返しや差で画素を短く書く、可逆圧縮の形式
- `Pnm`: PGM (P5) と PPM (P6) の読み込みと、PPM の書き出し
- `Ops`: 箱型のぼかしとグレースケール
- `Convert.grayBlur`: 先頭のバイトで形式 (PNG・QOI・PNM) を見分けて読み、ぼかしてグレースケールにし、QOI で返す

```scala
def grayBlurIsolated(input: Array[Byte], radius: Int)(using Limits): Array[Byte] =
  wasmIsolated { imaging.Convert.grayBlur(input, radius) }
```

`Pnm` には、ヘッダーを読むところに**わざとバグを残してある**。数字でない文字やファイルの終わりで先へ進まないので、ヘッダーが途中で切れたファイルを渡すと無限ループになる。デコーダーにありがちな種類のバグを、試すために入れたもの。

### 正しさ

- 自前の QOI で書いて読むと、元の画素に戻る。
- PyPI の `qoi` 0.8.0 で書いた 80×48 の QOI (アルファの違う行と乱れた画素を含む) を `bench.convertFile` で変換し、結果を `qoi` で読んで、numpy で計算したグレースケールと半径 2 のぼかしの値と比べた。半径 0 と 2 で全画素が一致した。
- `bench.pngCheck`: `java.util.zip.Deflater` で縮めたデータ 126 通り (0 バイトから 30 万バイト、乱数・文字の繰り返し・0 の並び、レベル 0/1/6/9 と FILTERED・HUFFMAN_ONLY) を自前の `Inflate` で展開し、すべて元に戻った。
- `bench.pngCheck`: `tools/png_cases.py` で作った PNG 24 個を読み、ほかの実装で求めた RGBA と全画素が一致した。pypng 0.20220715.0 で書いた色の種類とビット深度の組み合わせ 15 通り (パレットの tRNS を含む)、Pillow 13.0.0 で書いた 4 個 (行ごとに左・上・Paeth のフィルターが選ばれていた)、全行を同じフィルター (なし・左・上・平均・Paeth) にした 5 個。どれも wasmIsolated の結果と JVM の結果が一致した。
- JVM の ImageIO で書いた ARGB・RGB・パレット・1 ビットの PNG を読み、ImageIO の `getRGB` と全画素が一致した (ARGB の PNG を調べたところ、ImageIO はどの行もフィルターなしで書いていた)。
- 下の計測のすべての大きさで、wasmIsolated の結果と JVM の結果がバイト単位で一致した。

### 細工した画像 (`Limits(timeout = 2.seconds, maxHeap = "64MB")`)

| 入力 | wasmIsolated (3 回) | JVM でそのまま (`bench.imagesJvm`、ヒープの上限 3,586 MB) |
|---|---|---|
| 幅と高さが 16000×16000 で、中身は「繰り返し」の命令 64 個の QOI (86 バイト) | 29〜51 ms でメモリ上限により止まった | 止まらず、37.6〜49.5 秒 (2 回) かけて 4,129,055 バイトの画像を返した (画素の領域だけで 1 GB を確保する) |
| 真っ黒な 16000×16000 のグレースケールの PNG (248,907 バイト、展開すると 256 MB) | 838〜919 ms でメモリ上限により止まった | 止まらず、47.4 秒かけて 4,129,055 バイトの画像を返した |
| 2000×2000 の同じ作りの QOI (86 バイト) | 止まらず、944〜1,045 ms で 64,539 バイトを返した (上限の 64MB に収まった) | (測っていない) |
| 真っ黒な 2000×2000 の PNG (3,958 バイト、展開すると 4 MB) | 止まらず、794〜1,011 ms で 64,539 バイトを返した | (測っていない) |
| ヘッダーが途中で切れた PPM | 2,009〜2,010 ms で時間切れにより止まった | 別スレッドで動かし、`Thread.interrupt` の 1 秒後もスレッドは動いていた |

止めたあとの 64×64 の変換は、どれも正しく動いた (11〜33 ms)。

`Png` は、展開したデータの長さが幅と高さから決まる長さを超えたら読むのをやめる。それでも、幅と高さそのものが大きければ、その長さまでは展開してしまう。PNG の仕様は幅と高さに 2^31-1 までを許していて、どこまでを受け付けるかは読む側が決めることになる。

### 大きさごとの時間 (2026-11-27、クラウドの 4 コア VM、OpenJDK 21.0.13、ISOLATED)

ぼかしの半径は 2。JIT を温めてから各 7 回測った中央値を、3 回走らせた範囲で書いている。「うち受け渡し」は、入力を持ち込んで出力と同じ長さの配列を返すだけのブロックの時間。PNG の入力は JVM の ImageIO で書いた。

| 画像 | 入力 | 出力 | JVM | wasmIsolated | うち受け渡し |
|---|---|---|---|---|---|
| 16×16 QOI | 715 B | 393 B | 0.01 ms | 1.1 ms | 1.0 ms |
| 16×16 PNG | 337 B | 393 B | 0.24〜0.26 ms | 1.1〜1.2 ms | 0.7 ms |
| 16×16 PPM | 781 B | 393 B | 0.02 ms | 1.1〜1.2 ms | 1.1 ms |
| 32×32 QOI | 2,641 B | 1,205 B | 0.04 ms | 3.6〜3.8 ms | 3.5〜3.6 ms |
| 32×32 PNG | 964 B | 1,205 B | 0.72〜0.78 ms | 2.8〜2.9 ms | 2.1 ms |
| 32×32 PPM | 3,085 B | 1,205 B | 0.08 ms | 4.0〜4.1 ms | 3.6 ms |
| 64×64 QOI | 5,525 B | 3,499 B | 0.12〜0.13 ms | 6.8〜7.2 ms | 6.6〜6.9 ms |
| 64×64 PNG | 3,521 B | 3,499 B | 0.97〜1.04 ms | 6.9〜7.2 ms | 5.6〜6.1 ms |
| 64×64 PPM | 12,301 B | 3,499 B | 0.26〜0.28 ms | 12.2〜13.7 ms | 12.1〜12.4 ms |
| 128×128 QOI | 21,874 B | 12,001 B | 0.57〜0.59 ms | 29.6〜30.0 ms | 24.4〜26.2 ms |
| 128×128 PNG | 14,347 B | 12,001 B | 2.06〜3.39 ms | 21.3〜24.1 ms | 19.4〜19.8 ms |
| 128×128 PPM | 49,167 B | 12,001 B | 0.61〜1.02 ms | 44.0〜44.7 ms | 43.4〜45.7 ms |
| 256×256 QOI | 44,538 B | 29,280 B | 1.78〜1.86 ms | 56.2〜65.9 ms | 53.9〜55.2 ms |
| 256×256 PNG | 59,185 B | 29,280 B | 4.43〜4.50 ms | 73.1〜76.3 ms | 65.6〜67.9 ms |
| 256×256 PPM | 196,623 B | 29,280 B | 1.66〜1.72 ms | 166.3〜171.3 ms | 161.9〜166.7 ms |

- wasmIsolated の時間のほとんどは受け渡しで、1 バイトあたり約 0.7 µs (256×256 の PPM で、入力と出力の合計 225,903 バイトに 162〜167 ms)。
- PNG は JVM でもほかの形式より遅い。DEFLATE の展開でハフマン符号を 1 ビットずつ読むため。wasmIsolated から受け渡しを引いた差も、PNG がいちばん大きい (256×256 で 5〜10 ms ほど)。ただ、差はばらつきの範囲と重なるので、Wasm の中の処理時間として切り分けては言えない (未確認)。
- 入力が小さい形式 (QOI や PNG) ほど、受け渡しが短くて速い。
- PNG を入れると main.wasm は 547 KB (borer を含む)。

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
