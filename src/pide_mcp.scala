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

      val getopts = Getopts("""
Usage: isabelle pide_mcp [OPTIONS]

  Options are:
    -d DIR       include session directory
    -L FILE      logging on FILE (default: console stderr)
    -l NAME      logic session name (default: HOL)
    -o OPTION    override Isabelle system OPTION (via NAME=VAL or NAME)
    -v           verbose

  Start an MCP (Model Context Protocol) server over stdin/stdout,
  backed by an embedded Isabelle PIDE session using the specified
  logic image.
""",
        "d:" -> (arg => session_dirs = session_dirs ::: List(Path.explode(arg))),
        "L:" -> (arg => log_path = Some(Path.explode(File.standard_path(arg)))),
        "l:" -> (arg => logic = arg),
        "o:" -> (arg => options = options + arg),
        "v" -> (_ => verbose = true)
      )

      val more_args = getopts(args)
      if (more_args.nonEmpty) getopts.usage()

      val log: Logger = log_path match {
        case Some(p) => new File_Logger(p)
        case None => new System_Logger()
      }
      val build_progress = new Console_Progress
      val pide_session = new PIDE_MCP_Session(logic, log, session_dirs, options)

      try {
        log("Starting Isabelle PIDE session with base session: " + logic + " ...")
        pide_session.start(build_progress)
        log("Session started. Now starting MCP server listening on stdin/stdout.")

        val server = new PIDE_MCP_Server(pide_session, log, verbose)
        server.run()
      } catch {
        case ex: Exception =>
          log("PIDE MCP error: " + Exn.print(ex))
          sys.exit(1)
      } finally {
        log("Stopping Isabelle PIDE MCP session...")
        pide_session.stop()
      }
    })
}

class PIDE_MCP_Tool extends Isabelle_Scala_Tools(PIDE_MCP.isabelle_tool)
