import org.scalajs.linker.interface.ModuleKind

// wasmIsolated { ... } の試作
//
//   demo (Scala 3, JVM)      ─ コンパイル時にマクロがブロックのソースを書き出す
//     ↓
//   blocks (Scala.js、Scala 3) ─ ブロックを Scala 3 のままコンパイルする
//     ↓
//   wasm (Scala.js、Scala 2.13) ─ @WasmExport の窓口をコンパイルし、main.wasm にリンクする
//     ↓
//   demo の resources に main.wasm を入れ、実行時に GraalWasm で動かす

val scala3 = "3.10.1"
/** Scala.js 側の Scala 3。3.8 以降は標準ライブラリが scala-library 3.x になり、2.13 から読めないので 3.7 系にする */
val scala3ForWasm = "3.7.4"
val graalVersion = "25.6.5.1"
/** CBOR のコーデック。JVM と Scala.js の両方に出ていて、Scala 3.3 系でビルドされているので 3.7.4 からも読める */
val borerVersion = "1.19.0"

/** JVM と Wasm の両方でコンパイルするソース (ブロックとやりとりする case class など) */
lazy val sharedSources = settingKey[File]("JVM と Wasm の両方でコンパイルするソース")
ThisBuild / sharedSources := (ThisBuild / baseDirectory).value / "shared" / "src" / "main" / "scala"

/** マクロがブロックのソースを書き出す場所。マクロは呼び出し側の <プロジェクト>/target/wasm-blocks に書くので、それに合わせる */
lazy val blocksDir = settingKey[File]("wasmIsolated のブロックの書き出し先")
ThisBuild / blocksDir := (LocalProject("demo") / target).value / "wasm-blocks"

/** マクロと実行時ライブラリ */
lazy val core = project
  .settings(
    scalaVersion := scala3,
    libraryDependencies ++= Seq(
      "org.graalvm.polyglot" % "polyglot" % graalVersion,
      ("org.graalvm.polyglot" % "wasm" % graalVersion).pomOnly(),
      // SandboxPolicy.UNTRUSTED (別ヒープで動かし、JIT も効く) に必要
      ("org.graalvm.polyglot" % "wasm-isolate-community" % graalVersion).pomOnly(),
      // 数値でも数値の配列でもない値は、CBOR のバイト列にして境界を越える
      "io.bullet" %% "borer-core" % borerVersion,
    ),
  )

/** wasmIsolated を使うアプリ */
lazy val demo = project
  .dependsOn(core)
  .settings(
    scalaVersion := scala3,
    libraryDependencies += "io.bullet" %% "borer-derivation" % borerVersion,
    Compile / unmanagedSourceDirectories += sharedSources.value,
    // wasm プロジェクトがリンクした main.wasm を、クラスパスの wasm-isolated/main.wasm に置く
    Compile / resourceGenerators += Def.task {
      val linked = (LocalProject("wasm") / Compile / fullLinkJS / scalaJSLinkerOutputDirectory).value / "main.wasm"
      (LocalProject("wasm") / Compile / fullLinkJS).value
      val dst = (Compile / resourceManaged).value / "wasm-isolated" / "main.wasm"
      IO.copyFile(linked, dst)
      Seq(dst)
    }.taskValue,
    Compile / run / mainClass := Some("demo"),
    fork := true,
    javaOptions ++= Seq("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8"),
    outputStrategy := Some(StdoutOutput),
  )

/** ブロックを Scala 3 のままコンパイルする Scala.js のプロジェクト。ソースはすべてマクロが書き出したもの */
lazy val blocks = project
  .enablePlugins(ScalaJSPlugin)
  .settings(
    scalaVersion := scala3ForWasm,
    scalacOptions += "-Wconf:any:s",
    libraryDependencies ++= Seq(
      "io.bullet" %%% "borer-core" % borerVersion,
      "io.bullet" %%% "borer-derivation" % borerVersion,
    ),
    Compile / unmanagedSourceDirectories += sharedSources.value,
    Compile / sourceGenerators += Def.task {
      (LocalProject("demo") / Compile / compile).value // demo をコンパイルすると、マクロがソースを書き出す
      (blocksDir.value / "blocks" ** "*.scala").get
    }.taskValue,
  )

/** ブロックを @WasmExport で外に出し、main.wasm にリンクする。Scala 3 は @WasmExport を処理しないので Scala 2.13 で書く */
lazy val wasm = project
  .enablePlugins(ScalaJSPlugin)
  .dependsOn(blocks)
  .settings(
    scalaVersion := "2.13.18",
    scalacOptions += "-Ytasty-reader", // Scala 3 でコンパイルした blocks を読む
    scalaJSLinkerConfig ~= (_.withESFeatures(_.withUseWebAssembly(true)).withModuleKind(ModuleKind.WasmModule)),
    Compile / sourceGenerators += Def.task {
      (LocalProject("demo") / Compile / compile).value
      (blocksDir.value / "exports" ** "*.scala").get
    }.taskValue,
  )
