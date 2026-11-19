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

