package wasmisolated

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import scala.quoted.*

/**
 * ブロックを WebAssembly にコンパイルし、JVM のヒープとは別の箱 (GraalWasm) の中で実行する。
 *
 * - ブロックの外から持ち込めるのは数値 (Int / Long / Float / Double) の値だけ
 * - ブロックの中は Scala 3 で書ける (Scala.js の Scala 3.7 でコンパイルする)
 * - 時間とメモリの上限は、文脈にある Limits で決める
 */
inline def wasmIsolated[T](inline body: T)(using limits: Limits): T =
  ${ WasmIsolatedMacro.impl('body, 'limits) }

private object WasmIsolatedMacro:

  def impl[T: Type](body: Expr[T], limits: Expr[Limits])(using Quotes): Expr[T] =
    import quotes.reflect.*

    val term = body.asTerm match
      case Inlined(_, _, t) => t
      case t => t

    val boundary = List("Int" -> TypeRepr.of[Int], "Long" -> TypeRepr.of[Long], "Float" -> TypeRepr.of[Float], "Double" -> TypeRepr.of[Double])
    def typeName(t: TypeRepr): Option[String] = boundary.collectFirst { case (n, r) if t.widen.dealias =:= r => n }

    val returnType =
      if TypeRepr.of[T] =:= TypeRepr.of[Unit] then "Unit"
      else typeName(TypeRepr.of[T]).getOrElse(
        report.errorAndAbort(s"ブロックの値の型は Int / Long / Float / Double / Unit のどれかにしてください (今は ${TypeRepr.of[T].show})", term.pos))

    // ---- ブロックの中で定義されたもの
    val defined = collection.mutable.Set.empty[Symbol]
    new TreeTraverser:
      override def traverseTree(tree: Tree)(owner: Symbol): Unit =
        tree match
          case d: Definition => defined += d.symbol
          case b: Bind => defined += b.symbol
          case _ =>
        super.traverseTree(tree)(owner)
    .traverseTree(term)(Symbol.spliceOwner)

    /** ブロックの中で定義されたもの (またはその中身) か */
    def isInside(s: Symbol): Boolean =
      Iterator.iterate(s)(_.maybeOwner).takeWhile(o => !o.isNoSymbol && !o.isPackageDef).exists(defined)

    /** 標準ライブラリ (scala.* と java.lang.*) とコンパイラの組み込み (throw など) は Scala.js 側にもあるので、そのまま使える */
    def isStdlib(s: Symbol): Boolean =
      val n = s.fullName
      n.startsWith("scala.") || n.startsWith("java.lang.") || n.startsWith("<special-ops>") || n == "scala" || n == "java"

    // ---- ブロックの外から取り込む変数を集め、使えないものはエラーにする
    val captured = collection.mutable.LinkedHashMap.empty[Symbol, String]
    var failed = false
    def fail(msg: String, pos: Position): Unit = { report.error(msg, pos); failed = true }
    new TreeTraverser:
      override def traverseTree(tree: Tree)(owner: Symbol): Unit =
        tree match
          case t: This if !isInside(t.symbol) =>
            fail("ブロックの中では this (外のオブジェクト) を使えません", t.pos)
          case Assign(lhs, _) if lhs.symbol.exists && !isInside(lhs.symbol) =>
            fail(s"ブロックの外の変数 ${lhs.symbol.name} に代入することはできません", lhs.pos)
          case id: Ident if id.symbol.isTerm && !isInside(id.symbol) && !isStdlib(id.symbol) =>
            val s = id.symbol
            if s.isValDef && !s.flags.is(Flags.Module) then
              typeName(id.tpe) match
                case Some(t) => captured.getOrElseUpdate(s, t)
                case None =>
                  fail(s"ブロックの外から持ち込めるのは数値だけです: ${s.name} の型は ${id.tpe.widen.show}", id.pos)
            else
              fail(s"ブロックの外の ${s.name} は Wasm の中では使えません。ブロックの中で定義してください", id.pos)
          case _ =>
        super.traverseTree(tree)(owner)
    .traverseTree(term)(Symbol.spliceOwner)
    if failed then throw new scala.quoted.runtime.StopMacroExpansion

    val code = term.pos.sourceCode.getOrElse(report.errorAndAbort("ブロックのソースを読めません", term.pos))

    // ---- Scala.js 用のソースを 2 つ書き出す
    //   blocks/: ブロックを Scala 3 のまま関数にしたもの (Scala.js の Scala 3 でコンパイルする)
    //   exports/: それを @WasmExport で外に出す窓口 (Scala 3 は @WasmExport を処理しないので Scala 2.13 で書く)
    val params = captured.map((s, t) => s"${s.name}: $t").toList
    val lines = code.linesIterator.toList
    val indent = lines.tail.filter(_.trim.nonEmpty).map(_.takeWhile(_ == ' ').length).minOption.getOrElse(0)
    val bodyText = (lines.head :: lines.tail.map(_.drop(indent))).map("    " + _).mkString("\n")
    val digest = MessageDigest.getInstance("SHA-256").digest(s"${params.mkString(", ")}\n$returnType\n$code".getBytes(UTF_8))
    val name = "blk_" + digest.take(6).map(b => f"${b & 0xff}%02x").mkString
    val obj = name.capitalize
    val origin = s"${term.pos.sourceFile.name}:${term.pos.startLine + 1}"
    val block =
      s"""|// $origin の wasmIsolated から生成 (編集しないこと)
          |package wasmisolated.blocks
          |
          |object $obj:
          |  def run(${params.mkString(", ")}): $returnType = {
          |$bodyText
          |  }
          |""".stripMargin
    val exportSrc =
      s"""|// $origin の wasmIsolated から生成 (編集しないこと)
          |package wasmisolated.exports
          |
          |import scala.scalajs.wasm.annotation.WasmExport
          |
          |object $obj {
          |  @WasmExport("$name")
          |  def run(${params.mkString(", ")}): $returnType = wasmisolated.blocks.$obj.run(${captured.keys.map(_.name).mkString(", ")})
          |}
          |""".stripMargin

    // 書き出し先は、呼び出し側のプロジェクトの target/wasm-blocks (src/main/scala/... の src の隣)
    val outDir = term.pos.sourceFile.getJPath
      .flatMap(p => Iterator.iterate(p.toAbsolutePath)(_.getParent).takeWhile(_ != null).find(_.getFileName.toString == "src"))
      .map(_.getParent.resolve("target").resolve("wasm-blocks"))
      .getOrElse(report.errorAndAbort("ソースの場所から書き出し先を決められません (src ディレクトリの下に置いてください)"))
    def write(sub: String, text: String): Unit =
      val file = outDir.resolve(sub).resolve(s"$name.scala")
      Files.createDirectories(file.getParent)
      if !Files.exists(file) || Files.readString(file) != text then Files.writeString(file, text)
    write("blocks", block)
    write("exports", exportSrc)

    // ---- 呼び出しに置き換える
    val args = Varargs(captured.keys.toList.map(s => Ref(s).asExpr))
    val read: Expr[org.graalvm.polyglot.Value => T] = (returnType match
      case "Int" => '{ (v: org.graalvm.polyglot.Value) => v.asInt() }
      case "Long" => '{ (v: org.graalvm.polyglot.Value) => v.asLong() }
      case "Float" => '{ (v: org.graalvm.polyglot.Value) => v.asFloat() }
      case "Double" => '{ (v: org.graalvm.polyglot.Value) => v.asDouble() }
      case _ => '{ (v: org.graalvm.polyglot.Value) => () }
    ).asExprOf[org.graalvm.polyglot.Value => T]
    '{ WasmRuntime.call(${ Expr(name) }, $limits, $args*)($read) }
