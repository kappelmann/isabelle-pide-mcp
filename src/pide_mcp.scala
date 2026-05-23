/*  Title:      PIDE_MCP/pide_mcp.scala
    Author:     Kevin Kappelmann

Isabelle tool entry point for the PIDE MCP server.
*/

package isabelle.pide.mcp

import isabelle._
import scala.language.unsafeNulls


object PIDE_MCP
{
  val isabelle_tool = Isabelle_Tool("pide_mcp", "Isabelle PIDE MCP server", Scala_Project.here,
    { args =>
      var session_dirs: List[Path] = Nil
      var logic = "HOL"
      var options = Options.init()
      var verbose = false

      val getopts = Getopts("""
Usage: isabelle pide_mcp [OPTIONS]

  Options are:
    -d DIR       include session directory
    -l NAME      logic session name (default: HOL)
    -o OPTION    override Isabelle system OPTION (via NAME=VAL or NAME)
    -v           verbose output on stderr

  Start an MCP (Model Context Protocol) server over stdin/stdout,
  backed by an embedded Isabelle PIDE session using the specified
  logic image.
""",
        "d:" -> (arg => session_dirs = session_dirs ::: List(Path.explode(arg))),
        "l:" -> (arg => logic = arg),
        "o:" -> (arg => options = options + arg),
        "v" -> (_ => verbose = true)
      )

      val more_args = getopts(args)
      if (more_args.nonEmpty) getopts.usage()

      val pide_session = new PIDE_Session(logic, session_dirs, options)

      try {
        System.err.nn.println(s"Starting Isabelle session: $logic ...")
        pide_session.start()
        System.err.nn.println("Session started. MCP server listening on stdin/stdout.")

        val server = new MCP_Server(pide_session)
        server.run()
      } catch {
        case ex: Exception =>
          System.err.nn.println(s"Error: ${ex.getMessage}")
          ex.printStackTrace(System.err.nn)
          sys.exit(1)
      } finally {
        pide_session.stop()
      }
    })
}

class PIDE_MCP_Tool extends Isabelle_Scala_Tools(PIDE_MCP.isabelle_tool)
