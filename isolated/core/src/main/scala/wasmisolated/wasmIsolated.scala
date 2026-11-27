package wasmisolated

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import scala.quoted.*

/**
 * ブロックを WebAssembly にコンパイルし、JVM のヒープとは別の箱 (GraalWasm) の中で実行する。
 *
 * - ブロックの外から持ち込めるのは数値 (Int / Long / Float / Double) の値と、数値の配列 (Array[Byte] から Array[Double] まで)
 * - それ以外の値も、borer の Encoder と Decoder があれば持ち込める (CBOR のバイト列にして渡す)。
 *   その型は JVM と Wasm の両方でコンパイルされている必要がある (パスに shared というディレクトリを含むソースに置く)
 * - 配列や CBOR の値は Wasm の中へ写しが渡る。外の配列の要素を書き換えることはできない
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

    // Wasm の境界を越えられる型。スカラーは i32 / i64 / f32 / f64 に、配列は Wasm GC の配列になる
    val scalars = List("Int" -> TypeRepr.of[Int], "Long" -> TypeRepr.of[Long], "Float" -> TypeRepr.of[Float], "Double" -> TypeRepr.of[Double])
    val arrays = List(
      "Array[Byte]" -> TypeRepr.of[Array[Byte]], "Array[Short]" -> TypeRepr.of[Array[Short]],
      "Array[Int]" -> TypeRepr.of[Array[Int]], "Array[Long]" -> TypeRepr.of[Array[Long]],
      "Array[Float]" -> TypeRepr.of[Array[Float]], "Array[Double]" -> TypeRepr.of[Array[Double]])
    def typeName(t: TypeRepr): Option[String] = (scalars ++ arrays).collectFirst { case (n, r) if t.widen.dealias =:= r => n }

    /** borer の Encoder と Decoder がある型は、CBOR のバイト列 (Array[Byte]) にして境界を越える */
    def hasCodec(t: TypeRepr): Boolean =
      t.asType match
        case '[a] => Expr.summon[io.bullet.borer.Encoder[a]].isDefined && Expr.summon[io.bullet.borer.Decoder[a]].isDefined
    def typeCode(t: TypeRepr): String = t.widen.dealias.show(using Printer.TypeReprCode)

    /** 境界での受け渡し方。Plain は Wasm の型そのまま、Cbor はバイト列にする */
    enum Pass:
      case Plain(wasmType: String)
      case Cbor(tpe: TypeRepr)
    import Pass.*

    val resultPass: Pass =
      val t = TypeRepr.of[T]
      if t =:= TypeRepr.of[Unit] then Plain("Unit")
      else typeName(t).map(Plain(_)).orElse(Option.when(hasCodec(t))(Cbor(t.widen))).getOrElse(
        report.errorAndAbort(s"ブロックの値の型は、数値・数値の配列・Unit か、borer の Encoder と Decoder がある型にしてください (今は ${t.show})", term.pos))

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

    /**
     * JVM と Wasm の両方でコンパイルされるソース (パスに shared というディレクトリを含むもの) で定義されたものか。
     * そこの case class やその関数は、ブロックの中からそのまま使える
     */
    def isShared(s: Symbol): Boolean =
      val top = Iterator.iterate(s)(_.maybeOwner).takeWhile(o => !o.isNoSymbol && !o.isPackageDef).toList.lastOption
      top.flatMap(_.pos).flatMap(_.sourceFile.getJPath)
        .exists(p => (0 until p.getNameCount).exists(i => p.getName(i).toString == "shared"))

    // ---- ブロックの外から取り込む変数を集め、使えないものはエラーにする
    val captured = collection.mutable.LinkedHashMap.empty[Symbol, Pass]
    var failed = false
    def fail(msg: String, pos: Position): Unit = { report.error(msg, pos); failed = true }
    new TreeTraverser:
      override def traverseTree(tree: Tree)(owner: Symbol): Unit =
        tree match
          case t: This if !isInside(t.symbol) =>
            fail("ブロックの中では this (外のオブジェクト) を使えません", t.pos)
          case Assign(lhs, _) if lhs.symbol.exists && !isInside(lhs.symbol) =>
            fail(s"ブロックの外の変数 ${lhs.symbol.name} に代入することはできません", lhs.pos)
          // xs(i) = v は xs.update(i, v) になる。Wasm には写しが渡るので、書き換えても外の配列は変わらない
          case Apply(sel @ Select(qual: Ident, "update"), _) if !isInside(qual.symbol) && arrays.exists((_, r) => qual.tpe.widen.dealias =:= r) =>
            fail(s"ブロックの外の配列 ${qual.symbol.name} の要素は書き換えられません (Wasm には写しが渡ります)。ブロックの中で写してから書き換えてください", sel.pos)
          case id: Ident if id.symbol.isTerm && !id.symbol.isPackageDef && !isInside(id.symbol) && !isStdlib(id.symbol) && !isShared(id.symbol) =>
            val s = id.symbol
            if s.isValDef && !s.flags.is(Flags.Module) then
              typeName(id.tpe) match
                case Some(t) => captured.getOrElseUpdate(s, Plain(t))
                case None if captured.contains(s) || hasCodec(id.tpe.widen) => captured.getOrElseUpdate(s, Cbor(id.tpe.widen))
                case None =>
                  fail(s"ブロックの外から持ち込めるのは、数値・数値の配列か、borer の Encoder と Decoder がある型の値だけです: ${s.name} の型は ${id.tpe.widen.show}", id.pos)
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
    // Scala の型で書いた引数 (ハッシュ用) と、Wasm の境界での引数
    val signature = captured.map {
      case (s, Plain(t)) => s"${s.name}: $t"
      case (s, Cbor(t)) => s"${s.name}: ${typeCode(t)}"
    }.toList
    def wasmName(s: Symbol): String = captured(s) match
      case Plain(_) => s.name
      case Cbor(_) => s"${s.name}__cbor"
    val wasmParams = captured.map {
      case (s, Plain(t)) => s"${s.name}: $t"
      case (s, Cbor(_)) => s"${s.name}__cbor: Array[Byte]"
    }.toList
    val wasmResult = resultPass match
      case Plain(t) => t
      case Cbor(_) => "Array[Byte]"
    val resultCode = resultPass match
      case Plain(t) => t
      case Cbor(t) => typeCode(t)

    val lines = code.linesIterator.toList
    val indent = lines.tail.filter(_.trim.nonEmpty).map(_.takeWhile(_ == ' ').length).minOption.getOrElse(0)
    def bodyText(prefix: String) = (lines.head :: lines.tail.map(_.drop(indent))).map(prefix + _).mkString("\n")
    val decodes = captured.collect { case (s, Cbor(t)) =>
      s"    val ${s.name}: ${typeCode(t)} = io.bullet.borer.Cbor.decode(${s.name}__cbor).to[${typeCode(t)}].value\n"
    }.mkString
    val runBody = resultPass match
      case Plain(_) => decodes + bodyText("    ")
      case Cbor(t) =>
        s"""|${decodes}    val result__cbor: ${typeCode(t)} = {
            |${bodyText("      ")}
            |    }
            |    io.bullet.borer.Cbor.encode(result__cbor).toByteArray""".stripMargin
    val digest = MessageDigest.getInstance("SHA-256").digest(s"${signature.mkString(", ")}\n$resultCode\n$code".getBytes(UTF_8))
    val name = "blk_" + digest.take(6).map(b => f"${b & 0xff}%02x").mkString
    val obj = name.capitalize
    val origin = s"${term.pos.sourceFile.name}:${term.pos.startLine + 1}"
    val block =
      s"""|// $origin の wasmIsolated から生成 (編集しないこと)
          |package wasmisolated.blocks
          |
          |object $obj:
          |  def run(${wasmParams.mkString(", ")}): $wasmResult = {
          |$runBody
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
          |  def run(${wasmParams.mkString(", ")}): $wasmResult = wasmisolated.blocks.$obj.run(${captured.keys.map(wasmName).mkString(", ")})
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
    val args = Varargs(captured.toList.map {
      case (s, Plain(_)) => Ref(s).asExpr
      case (s, Cbor(t)) =>
        t.asType match
          case '[a] =>
            val enc = Expr.summon[io.bullet.borer.Encoder[a]].get
            '{ CborBridge.toBytes[a](${ Ref(s).asExprOf[a] })(using $enc) }
    })
    val read: Expr[org.graalvm.polyglot.Value => T] = (resultPass match
      case Cbor(_) =>
        val dec = Expr.summon[io.bullet.borer.Decoder[T]].get
        '{ (v: org.graalvm.polyglot.Value) => CborBridge.fromBytes[T](WasmRuntime.readArray[Byte](v))(using $dec) }
      case Plain(returnType) => returnType match
        case "Array[Byte]" => '{ WasmRuntime.readArray[Byte] }
        case "Array[Short]" => '{ WasmRuntime.readArray[Short] }
        case "Array[Int]" => '{ WasmRuntime.readArray[Int] }
        case "Array[Long]" => '{ WasmRuntime.readArray[Long] }
        case "Array[Float]" => '{ WasmRuntime.readArray[Float] }
        case "Array[Double]" => '{ WasmRuntime.readArray[Double] }
        case "Int" => '{ (v: org.graalvm.polyglot.Value) => v.asInt() }
        case "Long" => '{ (v: org.graalvm.polyglot.Value) => v.asLong() }
        case "Float" => '{ (v: org.graalvm.polyglot.Value) => v.asFloat() }
        case "Double" => '{ (v: org.graalvm.polyglot.Value) => v.asDouble() }
        case _ => '{ (v: org.graalvm.polyglot.Value) => () }
    ).asExprOf[org.graalvm.polyglot.Value => T]
    '{ WasmRuntime.call(${ Expr(name) }, $limits, $args*)($read) }
