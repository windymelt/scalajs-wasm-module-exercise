# 引き継ぎメモ: Scala.js 1.23 `ModuleKind.WasmModule` の知見

このリポジトリで Scala.js 1.23.0 の `WasmModule` を試して得た知見を、次に作業する AI や人向けにまとめたもの。
数値はすべて 2026-11-18〜19 にこのリポジトリのコードで実測した値。測っていないことは「未確認」と書く。

## 0. まず読むべき結論

1. `WasmModule` は JS の接着コードを含まない素の `.wasm` を出す。インポートは `@WasmImport` で宣言したものだけ。
2. **Scala 3 では使えない** (2026-11-18 時点の最新 3.10.1 と nightly も)。Scala 2.13.18 以降を使う。
3. 境界を越えるのは `Int` / `Long` / `Float` / `Double` と数値の配列だけ。**配列は越えるたびにコピーされ、JS からは中身を読めない**。データは Scala 側にバッファを持ち、スカラー値で 1 つずつ (または 8 バイトずつ `Long` に詰めて) 受け渡す。
4. `Math.sin` / `cos` / `exp` / `log` / `pow` / `round`、`println`、正規表現、`Random.nextGaussian` はリンクできない。自前実装 (`src/main/scala/img/MathX.scala`) を使う。
5. JS 以外の Wasm ランタイムでは、**オブジェクトの確保が非常に重い**。ホットループでは `new` をしない書き方 (`Array[Double]` の使い回し、フィールド経由の受け渡し) にする。
6. GraalWasm (GraalVM Polyglot 25.5) でそのまま動く。Community 版でも別ヒープの隔離 (`SandboxPolicy.UNTRUSTED`) が使える。

## 1. ビルド設定

```scala
// project/plugins.sbt
addSbtPlugin("org.scala-js" % "sbt-scalajs" % "1.23.0")

// build.sbt
ThisBuild / scalaVersion := "2.13.18"   // 2.13.17 以前は「scala-library 2.13.18 が依存に入る」エラーになる
scalaJSLinkerConfig := scalaJSLinkerConfig.value
  .withESFeatures(_.withUseWebAssembly(true))   // withExperimentalUseWebAssembly は 1.22 から非推奨
  .withModuleKind(ModuleKind.WasmModule)
```

- 出力は `target/scala-2.13/<name>-opt/main.wasm` (fullLinkJS) / `-fastopt/` (fastLinkJS)。
- `scalaJSUseMainModuleInitializer := true` が使える。`main` はインスタンス化のときに実行される (GraalWasm で確認)。プラグインの自己登録に使える。
- ライブラリ (別の sbt プロジェクト) に書いた `@WasmExport` も、依存する側のモジュールにエクスポートされる (`middleware/sdk` で確認)。
- 使っていない `@WasmImport` は不要コード削除で消え、インポートに現れない。インポート一覧がそのまま「実際に使う機能」になる。

### Scala 3 が使えない理由

Scala 3 は Scala.js のバックエンドをコンパイラ jar に同梱している (`dotty.tools.sjs.ir`)。同梱の IR は 3.3.7 で 1.12、3.10.1 と 3.10.2-RC1 nightly (2026-11-15) で 1.22 で、`WasmExport` を処理するコードがない。未対応のコンパイラはアノテーションを黙って無視するので、**エラーも警告も出ずに出力が空になる**。

回避策 (`experiments/scala3-mixed/`): ロジックを Scala 3、境界だけを Scala 2.13 (`-Ytasty-reader`) で書く。ただし Scala 3.8 以降は標準ライブラリが `scala-library 3.x` になり 2.13 から使えないので、**ロジック側は 3.7.x まで**。

## 2. 境界 (エクスポート / インポート) の型

| Scala | Wasm | 備考 |
|---|---|---|
| `Int` / `Long` / `Float` / `Double` | `i32` / `i64` / `f32` / `f64` | JS では `Long` は `BigInt` |
| `Array[Byte/Short/Int/Long/Float/Double]` | `(ref (array (mut …)))` | null 不可。毎回コピー。JS から `.length` も読めない |
| `Unit` | 戻り値なし | 戻り値の型としてのみ |
| `Boolean` / `Char` / 単体の `Byte` / `String` | — | コンパイルエラー |

- Scaladoc に明記: "Arrays are always copied at the boundary. They are never passed by reference."
- 実用パターン: `inPush(b)` / `outAt(i)` のように Scala 側のバッファを 1 要素ずつ読み書きする。25 万画素を `px(i)` で読んで数 ms (V8)。
- 境界をまたぐ呼び出しの固定費: GraalWasm で 0.54 µs (CONSTRAINED) / 1.50 µs (UNTRUSTED)。8 バイト単位を 64 バイト単位にまとめても、ミドルウェアではほぼ速くならなかった。

## 3. リンクできるもの・できないもの (実測)

| リンクできる | リンクエラー |
|---|---|
| `Math.sqrt` `floor` `ceil` `abs` `min` `max` | `Math.sin` `cos` `tan` `pow` `exp` `log` `atan2` `round` |
| `String.toDouble`、`Double.toString`、`BigInt`、`format`、`mutable.HashMap`、`java.util.Random(seed)`、`String.getBytes(UTF_8)`、`new String(bytes, UTF_8)` | `println` (`System.out`)、正規表現 (`String.split` や `replaceAll` も正規表現を使う)、`java.util.stream`、`Random.nextGaussian` (内部で `Math.log`)、`java.util.zip` (存在しない) |

- リンクエラーのメッセージは呼び出しの連鎖を表示するので、どこで JS に触れたかわかる。ただし文言は `exported to JavaScript with @JSExport` のままで不正確。
- 未確認だが避けた: `String.toLowerCase`、`equalsIgnoreCase` (ASCII 版を自作した)。
- Scala.js では `1.0.toString` が `"1"` になる (JS と同じ)。表示に `.0` が必要なら補う。
- 自作の `MathX` の精度は JS の `Math` 比で相対誤差 10⁻¹³ 程度。`sin` を `cos(x - π/2)` で作ると `sin(0)` が `6e-17` になるので、直接テイラー展開する。

## 4. ランタイムごとの挙動と性能

### Node.js 22 (V8)
- ドキュメントは Node 25 以上を推奨するが、22 で問題なく動いた。
- `--experimental-wasm-type-reflection` を付けると `WebAssembly.Module.exports(m)` に型が出る。

### wasmtime 50 (Python バインディング `wasmtime==50.0.1`)
- GC と例外処理を含めて動く。C API が GC の参照型を表示できないので、配列を含む関数の型は表示できない。
- ブラウザで GitHub のリリースは取れない環境だったので PyPI のバインディングを使った。

| 2,000 万回ループ | V8 | wasmtime | 比 |
|---|---|---|---|
| 演算のみ | 59 ms | 37 ms | 0.6 倍 |
| GC 配列の読み書き | 53 ms | 124 ms | 2.3 倍 |
| 毎回オブジェクトを確保 | 340 ms | 5,502 ms | 16 倍 |

実際のプログラムでも同じ傾向: ジュリア集合 2.2 倍、流体 (確保なし) 3 倍、レイトレーサー (ケースクラス多用) 18 倍、Scheme インタプリタ 26 倍。

### GraalWasm (GraalVM Polyglot 25.5.5.0.3)
- `org.graalvm.polyglot:polyglot` + `org.graalvm.polyglot:wasm` (POM のみなので sbt では `.pomOnly()`)。JDK 21 で動く。
- `wasm.GC`、`wasm.Exceptions`、`wasm.TypedFunctionReferences`、`wasm.TailCalls` は既定で有効。
- API: `ctx.eval(source)` はモジュールを返す → `module.newInstance(imports)` → `.getMember("exports")`。インポートは `ProxyObject.fromMap(Map("host" -> ProxyObject.fromMap(...)))`。`ProxyExecutable` は可変長引数なので Scala のラムダから自動変換できない (無名クラスで書く)。
- **普通の OpenJDK では JVM 内の GraalWasm は JIT が効かない** (インタプリタ)。ジュリア集合 640×400 が 10.6 秒。
- `SandboxPolicy.UNTRUSTED` は `org.graalvm.polyglot:wasm-isolate-community` を足すと使える。Native Image の別ヒープで動き、JIT が効く (同じ処理が 170 ms、Node.js は 184 ms)。`ISOLATED` 以上は `sandbox.MaxCPUTime` が必須。
- 上限: `sandbox.MaxHeapMemory` で 400 MB 確保を止めた (`isResourceExhausted`)。`sandbox.MaxCPUTime` は**コンテキストの生涯の合計**なので、リクエストごとの制限には `Context.interrupt(Duration)` を別に使う (300 ms で停止し、その後もコンテキストは使えた)。
- プラグイン内の Scala の例外は `PolyglotException(isGuestException=true)` で届くが、**メッセージは null**。メッセージが欲しければゲスト側で捕まえて自分で渡す。
- 別ヒープはコンテキストごとに作ると 1 個約 160 MB。**Engine をプラグインごとに 1 つ作り、コンテキストで共有する**と大幅に減る (48 コンテキストで 7.7 GB → 1.5 GB)。

## 5. 書き方の指針

- **ホットループで確保しない**: ケースクラスのベクトル演算はやめ、`Double` のローカル変数で書く。交差判定などの結果はフィールドで返す (`img/PathTracer.scala`、`img/Fluid.scala`)。
- **Wasm 関数のローカル変数を増やさない**: 巨大な `match` を 1 つの関数に書くと、1 段あたりのスタックが太くなる。Scheme の `eval` を細いループ + 特殊形式ごとのメソッドに分けたら、届く再帰の深さが 1,000〜2,000 段から約 5,700 段に伸び、2 割速くなった。
- スタックが溢れると JS では `RangeError`。Scala の例外としては捕まえられないが、インスタンスはその後も使える。
- 浮動小数点の結果はエンジン間で決定的: レイトレーサーの PNG が V8 と wasmtime でバイト一致、流体の運動エネルギーも一致。

## 6. ブラウザ (claude.ai の Artifact) で動かすとき

- `.wasm` は Artifact の `files` でページと一緒に配信し、`fetch("main.wasm")` → `WebAssembly.compile` で読める (CSP で拒否されなかった)。
- ページの公開時は `<head>` が自動で付くので、ローカルで試すときは `<meta charset="utf-8">` を付けた雛形で包まないと日本語が化ける。
- 公開済みのページ: Renderer (レイトレーサー、ジュリア集合、流体、パストレーシング)、Scheme、Convolver。HTML は `pages/` にある。

## 7. このリポジトリの地図

| 場所 | 中身 |
|---|---|
| `src/main/scala/` | 最初の実験 (型の調査、文字列の受け渡し、ベンチマーク `Bench.scala`) |
| `src/main/scala/img/` | `MathX`、PNG エンコーダー (Deflate 自作)、レイトレーサー、ジュリア集合、流体 (Stable Fluids)、パストレーサー (色付きガラス、ビール入りのグラス) |
| `web/` | ブラウザ用の窓口 (インポートなし) |
| `scheme/` | Scheme 処理系 (末尾呼び出し最適化、BigInt、準クォート、ステップ上限) |
| `audio/` | インパルス応答の畳み込みエフェクター |
| `middleware/` | GraalWasm で差し込む HTTP ミドルウェア (SDK、プラグイン 7 個、ホスト) |
| `hosts/node/`、`hosts/python/` | Node.js と wasmtime から動かすスクリプト (数値検証を含む) |
| `experiments/scala3-mixed/` | Scala 3 + 2.13 の回避策 |
| `pages/` | 公開したページの HTML (`scripts/build-pages.sh` で `.wasm` を置く) |

よく使うコマンド:

```sh
sbt root/fullLinkJS web/fullLinkJS scheme/fullLinkJS audio/fullLinkJS
./scripts/build-pages.sh && python3 -m http.server -d pages 8000
./middleware/build-plugins.sh && sbt "mwHost/run serve --config server.conf"
node hosts/node/audio.mjs audio/target/scala-2.13/sjs-wasm-audio-opt/main.wasm   # 畳み込みと T60 の検証
```

## 8. ドメインごとの知見

- **パストレーシング**: NEE のあり・なしで表示上の平均が倍違って見えたが、ガンマ前の値では 4,096 サンプルで一致 (表示の平方根とノイズの組み合わせ)。光源を狙う影の判定でガラスを遮蔽物として扱うと、ガラス越しにしか見えない面 (泡の側面) がノイズまみれになる。薄いガラスの近似で直し、二重計上しないよう経路側の寄与を除いた。
- **媒質の境目**: グラスは面ごとに表裏の媒質を持たせると、「今どの媒質にいるか」を追わずに屈折と吸収を計算できる。
- **残響の合成**: 時間とともに強める 1 次ローパスはノイズの分散を `a/(2-a)` 倍に縮め、T60 が 12〜18% 短くなる。割り戻して補正したら指定どおりになった。
- **FFT 畳み込み**: 実数信号 2 本を実部と虚部に入れて 1 回の複素 FFT にまとめ、共役対称性で分離すると FFT は計 2 回で済む。直接計算との差は Float32 の精度内。
- **HTTP サーバー**: JDK の `HttpServer` は Nagle と遅延 ACK で 1 応答約 40 ms 待つ。`sun.net.httpserver.nodelay=true` で 180 → 3,664 req/s。
- **ミドルウェア**: 玉ねぎ型なので、すべての応答に付けたいヘッダーは先頭のプラグインで付ける。`onRequest` 1 回のコストの大半 (197 µs 中 約 170 µs) は SDK がリクエストを文字列とコレクションに組み立てる処理。

## 9. 未確認・残っている課題

- Custom Descriptors (`withExperimentalUseCustomDescriptors`): Node 22 も wasmtime 50 も未対応で試せていない。
- 普通の JVM で GraalWasm の JIT を有効にする構成 (JVMCI + Graal コンパイラ) は未検証。
- ミドルウェアの SDK を遅延パース (使われたヘッダーだけ読む) にして速くする案は未着手。
- wasmtime の GC 実装 (コレクター) の切り替えは、Python バインディングから設定が見当たらず未検証。
- 実ブラウザでの音の再生は、ヘッドレス環境のため耳では確認していない。
