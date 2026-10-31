/*  Title:      MCP/src/pide_mcp_session_spec.scala
    Author:     Kevin Kappelmann

Specifications of PIDE sessions.
*/

package isabelle.pide.mcp

import isabelle._

object PIDE_MCP_Session_Spec {
  def default_logic(): String = Isabelle_System.default_logic()

  val usage: String = """Session options are:

    -A NAME      ancestor session for option -R (default: parent)
    -R NAME      build image with requirements from other sessions
    -d DIR       include session directory
    -f           fresh build
    -l NAME      logic session name (default ISABELLE_LOGIC=""" + quote(default_logic()) + """)
    -n           no build of session image on startup
    -o OPTION    override Isabelle system OPTION (via NAME=VAL or NAME)
    -z ID        PIDE MCP session id
"""
  private object Parsers extends Scan.Parsers {
    val blanks: Parser[String] = many(character(Symbol.is_ascii_blank))
    val word: Parser[String] = quoted("\"") ^^ (s => quoted_content("\"", s))
      | many1(sym => sym != "\"" && !character(Symbol.is_ascii_blank)(sym))
    val words: Parser[List[String]] = blanks ~> rep(word <~ blanks)
  }

  def dir(s: String): Path = PIDE_MCP_Util.path(s)
  def option(s: String): Options.Spec = Options.Spec.make(s)

  class Builder {
    private var current: Option[PIDE_MCP_Session_Spec] = None
    private var session_option = false
    def spec: Option[PIDE_MCP_Session_Spec] = current
    def has_session_option: Boolean = session_option
    private def update(f: PIDE_MCP_Session_Spec => PIDE_MCP_Session_Spec): Unit =
      current = Some(f(current.getOrElse(PIDE_MCP_Session_Spec())))
    private def update_session(f: PIDE_MCP_Session_Spec => PIDE_MCP_Session_Spec): Unit =
      { session_option = true; update(f) }
    val option_specs: List[(String, String => Unit)] =
      List(
        "A:" -> (arg => update_session(_.copy(session_ancestor = Some(arg)))),
        "R:" -> (arg => update_session(_.copy(logic = arg, session_requirements = true))),
        "d:" -> (arg =>
          update_session(spec => spec.copy(dirs = spec.dirs ::: List(dir(arg))))),
        "f" -> (_ => update_session(_.copy(fresh_build = true))),
        "l:" -> (arg => update_session(_.copy(logic = arg))),
        "n" -> (_ => update_session(_.copy(no_build = true))),
        "o:" -> (arg => update(spec => spec.copy(
          options = spec.options ::: List(option(arg))))),
        "z:" -> (arg => update_session(_.copy(id = Some(arg)))))
  }

  def parse(spec: String): PIDE_MCP_Session_Spec = {
    val words = Parsers.parseAll(Parsers.words, spec) match {
      case Parsers.Success(res, _) => res
      case bad => cat_error(bad.toString, usage)
    }
    val builder = new Builder
    val more_args = Getopts(usage, builder.option_specs: _*)(words, true)
    if (more_args.nonEmpty) cat_error("Bad session options: " + quote(spec), usage)
    builder.spec.getOrElse(PIDE_MCP_Session_Spec())
  }
}

sealed case class PIDE_MCP_Session_Spec(
  session_ancestor: Option[String] = None,
  session_requirements: Boolean = false,
  dirs: List[Path] = Nil,
  fresh_build: Boolean = false,
  no_build: Boolean = false,
  logic: String = PIDE_MCP_Session_Spec.default_logic(),
  options: Options.Update = Nil,
  id: Option[String] = None
)
