/*  Title:      Symbols/src/symbols.scala
    Author:     Kevin Kappelmann

Encode or decode Isabelle symbols in files.
*/

package isabelle.symbols

import isabelle._

object Symbols {
  // cf. Isabelle/VSCode extension languages
  def supports_isabelle_symbols(path: Path): Boolean = {
    val name = path.file_name
    File.is_thy(name) || File.is_ML(name) || name.endsWith(".sml") || name.endsWith(".sig")
  }

  def recode_file(unicode_symbols: Boolean, path: Path): Unit = {
    val text = File.read(path)
    val text1 = Symbol.output(unicode_symbols, text)
    if (text1 != text) File.write(path, text1)
  }

  val isabelle_tool = Isabelle_Tool("symbols", "encode or decode Isabelle symbols in files",
    Scala_Project.here,
    { args =>
      var unicode_symbols: Option[Boolean] = None

      val getopts = Getopts("""
Usage: isabelle symbols [OPTIONS] FILES

  Options are:
    -d           decode Isabelle symbols to Unicode
    -e           encode Isabelle symbols as ASCII

  Encode or decode Isabelle symbols in the given files.
""",
        "d" -> (_ => unicode_symbols = Some(true)),
        "e" -> (_ => unicode_symbols = Some(false)))

      val files = getopts(args)
      if (unicode_symbols.isEmpty || files.isEmpty) getopts.usage()

      val progress = new Console_Progress()
      for (file <- files) {
        val path = Path.explode(file)
        if (supports_isabelle_symbols(path)) recode_file(unicode_symbols.get, path)
        else progress.echo_warning("Skipped file not supporting Isabelle symbols " + path)
      }
    })
}

class Tools extends Isabelle_Scala_Tools(Symbols.isabelle_tool)
