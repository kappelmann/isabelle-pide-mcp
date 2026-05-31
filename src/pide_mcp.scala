/*  Title:      PIDE_MCP/pide_mcp.scala
    Author:     Kevin Kappelmann

Entry point for the PIDE MCP server.
*/

package isabelle.pide.mcp

import isabelle._

object PIDE_MCP {
  val isabelle_tool = Isabelle_Tool("pide_mcp", "Isabelle PIDE MCP server", Scala_Project.here,
    { args =>
      var session_dirs: List[Path] = Nil
      var logic = "HOL"
      var options = Options.init()
      var log_path: Option[Path] = None
      var verbose = false
      var session_ancestor: Option[String] = None
      var session_requirements = false
      var no_build = false
      var fresh_build = false

      val getopts = Getopts("""
Usage: isabelle pide_mcp [OPTIONS]

  Options are:
    -A NAME      ancestor session for option -R (default: parent)
    -R NAME      build image with requirements from other sessions
    -d DIR       include session directory
    -f           fresh build
    -L FILE      logging on FILE (default: console stderr)
    -l NAME      logic session name (default: HOL)
    -n           no build of session image on startup
    -o OPTION    override Isabelle system OPTION (via NAME=VAL or NAME)
    -s           system build mode for session image (system_heaps=true)
    -u           user build mode for session image (system_heaps=false)
    -v           verbose

  Start an MCP (Model Context Protocol) server over stdin/stdout,
  backed by an embedded Isabelle PIDE session using the specified
  logic image.
""",
        "A:" -> (arg => session_ancestor = Some(arg)),
        "R:" -> (arg => { logic = arg; session_requirements = true }),
        "d:" -> (arg => session_dirs = session_dirs ::: List(Path.explode(arg))),
        "f" -> (_ => fresh_build = true),
        "L:" -> (arg => log_path = Some(Path.explode(File.standard_path(arg)))),
        "l:" -> (arg => logic = arg),
        "n" -> (_ => no_build = true),
        "o:" -> (arg => options = options + arg),
        "s" -> (_ => options = options + "system_heaps=true"),
        "u" -> (_ => options = options + "system_heaps=false"),
        "v" -> (_ => verbose = true)
      )

      val more_args = getopts(args)
      if (more_args.nonEmpty) getopts.usage()

      val log = Logger.make_file(log_path, default = Logger.console)
      val build_progress = new Console_Progress
      val pide_session = new PIDE_MCP_Session(logic, log, session_dirs, options,
        session_ancestor = session_ancestor, session_requirements = session_requirements,
        no_build = no_build, fresh_build = fresh_build)

      try {
        log("Starting Isabelle PIDE session...")
        pide_session.start(build_progress)
        log("Session started. Now starting MCP server listening on stdin/stdout.")

        val server = new PIDE_MCP_Server(pide_session, log, verbose)
        server.run()
      } catch {
        case ex: Exception =>
          log.error_message("PIDE MCP error: " + Exn.print(ex))
          sys.exit(1)
      } finally {
        log("Stopping Isabelle PIDE MCP session...")
        pide_session.stop()
      }
    })
}

class PIDE_MCP_Tool extends Isabelle_Scala_Tools(PIDE_MCP.isabelle_tool)
