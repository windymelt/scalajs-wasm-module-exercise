import org.scalajs.linker.interface.ModuleKind

// wasmIsolated { ... } の試作
//
//   demo (Scala 3, JVM) ─ コンパイル時にマクロがブロックのソースを書き出す
//     ↓
//   wasm (Scala.js 2.13) ─ 書き出されたソースを main.wasm にリンクする
//     ↓
//   demo の resources に main.wasm を入れ、実行時に GraalWasm で動かす

val scala3 = "3.10.1"
val graalVersion = "25.6.5.1"

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
    ),
  )

/** wasmIsolated を使うアプリ */
lazy val demo = project
  .dependsOn(core)
  .settings(
    scalaVersion := scala3,
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

/** ブロックを Wasm にする Scala.js のプロジェクト。ソースはすべてマクロが書き出したもの */
lazy val wasm = project
  .enablePlugins(ScalaJSPlugin)
  .settings(
    scalaVersion := "2.13.18", // Scala 3 では WasmModule の出力が空になる
    scalacOptions ++= Seq("-Xsource:3", "-Wconf:any:s"),
    scalaJSLinkerConfig ~= (_.withESFeatures(_.withUseWebAssembly(true)).withModuleKind(ModuleKind.WasmModule)),
    Compile / sourceGenerators += Def.task {
      (LocalProject("demo") / Compile / compile).value // demo をコンパイルすると、マクロがソースを書き出す
      (blocksDir.value ** "*.scala").get
    }.taskValue,
  )
