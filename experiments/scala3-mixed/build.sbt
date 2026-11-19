import org.scalajs.linker.interface.ModuleKind

lazy val core = project
  .enablePlugins(ScalaJSPlugin)
  .settings(scalaVersion := "3.7.4")

lazy val wasm = project
  .enablePlugins(ScalaJSPlugin)
  .dependsOn(core)
  .settings(
    scalaVersion := "2.13.18",
    scalacOptions += "-Ytasty-reader",
    scalaJSLinkerConfig := {
      scalaJSLinkerConfig.value
        .withESFeatures(_.withUseWebAssembly(true))
        .withModuleKind(ModuleKind.WasmModule)
    },
  )
