import org.scalajs.linker.interface.ModuleKind

ThisBuild / scalaVersion := "2.13.18"

lazy val wasmModule = Def.setting {
  scalaJSLinkerConfig.value
    .withESFeatures(_.withUseWebAssembly(true))
    .withModuleKind(ModuleKind.WasmModule)
}

lazy val root = project.in(file("."))
  .enablePlugins(ScalaJSPlugin)
  .settings(
    name := "sjs-wasm-play",
    scalaJSLinkerConfig := wasmModule.value,
  )

/** ブラウザ用: 画像生成コード (img/) だけを含む、インポートなしのモジュール */
lazy val web = project.in(file("web"))
  .enablePlugins(ScalaJSPlugin)
  .settings(
    name := "sjs-wasm-web",
    Compile / unmanagedSourceDirectories += (ThisBuild / baseDirectory).value / "src" / "main" / "scala" / "img",
    scalaJSLinkerConfig := wasmModule.value,
  )

/** Scheme 処理系: 数学関数は img/MathX を流用する */
lazy val scheme = project.in(file("scheme"))
  .enablePlugins(ScalaJSPlugin)
  .settings(
    name := "sjs-wasm-scheme",
    Compile / unmanagedSourceDirectories += (ThisBuild / baseDirectory).value / "src" / "main" / "scala" / "img",
    scalaJSLinkerConfig := wasmModule.value,
  )

/** インパルス応答で音を変換するエフェクター: 数学関数は img/MathX を流用する */
lazy val audio = project.in(file("audio"))
  .enablePlugins(ScalaJSPlugin)
  .settings(
    name := "sjs-wasm-audio",
    Compile / unmanagedSourceDirectories += (ThisBuild / baseDirectory).value / "src" / "main" / "scala" / "img",
    scalaJSLinkerConfig := wasmModule.value,
  )

// ---- Wasm プラグインで差し替えられる HTTP ミドルウェア ----

/** プラグイン用 SDK (Scala.js) */
lazy val mwSdk = project.in(file("middleware/sdk"))
  .enablePlugins(ScalaJSPlugin)
  .settings(name := "mw-sdk", scalaJSLinkerConfig := wasmModule.value)

def mwPlugin(id: String): Project = Project(s"mw-$id", file(s"middleware/plugins/$id"))
  .enablePlugins(ScalaJSPlugin)
  .dependsOn(mwSdk)
  .settings(
    scalaJSUseMainModuleInitializer := true,
    scalaJSLinkerConfig := wasmModule.value,
  )

lazy val mwWaf = mwPlugin("waf")
lazy val mwRateLimit = mwPlugin("ratelimit")
lazy val mwAuth = mwPlugin("auth")
lazy val mwSecurityHeaders = mwPlugin("security-headers")
lazy val mwSignature = mwPlugin("signature")
lazy val mwEvil = mwPlugin("evil")
lazy val mwGreedy = mwPlugin("greedy")

lazy val mwPlugins = Seq(mwWaf, mwRateLimit, mwAuth, mwSecurityHeaders, mwSignature, mwEvil, mwGreedy)

val graalVersion = "25.5.5.0.3"

/** ミドルウェアを動かす HTTP サーバー (JVM、GraalWasm) */
lazy val mwHost = project.in(file("middleware/host"))
  .settings(
    name := "mw-host",
    scalaVersion := "3.3.7",
    libraryDependencies ++= Seq(
      "org.graalvm.polyglot" % "polyglot" % graalVersion,
      ("org.graalvm.polyglot" % "wasm" % graalVersion).pomOnly(),
      ("org.graalvm.polyglot" % "wasm-isolate-community" % graalVersion).pomOnly(),
    ),
    fork := true,
    run / baseDirectory := (ThisBuild / baseDirectory).value / "middleware" / "host",
    outputStrategy := Some(StdoutOutput),
  )
