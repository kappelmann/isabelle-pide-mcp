/*  Title:      PIDE_MCP/pide_mcp.scala
    Author:     Kevin Kappelmann

Entry point for PIDE MCP.
*/

package isabelle.pide.mcp

import isabelle._

object PIDE_MCP {
  def progress_threshold(options: Options): Time =
    options.seconds("pide_mcp_progress_threshold")
  def tool_names(options: Options): String =
    options.string("pide_mcp_tools")
  def await_option_sessions(options: Options): Boolean =
    options.bool("pide_mcp_await_option_sessions")
  def exit_on_failed_option_sessions(options: Options): Boolean =
    options.bool("pide_mcp_exit_on_failed_option_sessions")

  val isabelle_tool = Isabelle_Tool("pide_mcp", "Isabelle PIDE MCP server", Scala_Project.here,
    { args =>
      var log_path: Option[Path] = None
      var session_specs: List[PIDE_MCP_Session.Spec] = Nil
      var verbose = false
      var log_messages = false
      val session_spec = new PIDE_MCP_Session.Spec.Builder

      val getopts = Getopts("""
Usage: isabelle pide_mcp [OPTIONS]

  Options are:
    -L FILE                log on FILE (next to console stderr)
    -S "SESSION_OPTIONS"   start PIDE session with the given options ("" for defaults)
    -v                     verbose
    -w                     log MCP requests and responses
    plus any session options, starting a PIDE session.
    Passed Isabelle system options are inherited by the sessions given via -S.

  """ + PIDE_MCP_Session.Spec.usage + """
  Start an MCP (Model Context Protocol) server over stdin/stdout that manages
  headless PIDE sessions.
""",
        List(
          "L:" -> (arg => log_path = Some(PIDE_MCP_Util.path(arg))),
          "S:" -> (arg =>
            session_specs = session_specs ::: List(PIDE_MCP_Session.Spec.parse(arg))),
          "v" -> (_ => verbose = true),
          "w" -> (_ => log_messages = true))
          ::: session_spec.option_specs: _*)

      val more_args = getopts(args)
      if (more_args.nonEmpty) getopts.usage()

      val base_options: List[Options.Spec] = session_spec.spec.map(_.options).getOrElse(Nil)
      val specs =
        (if (session_spec.has_session_option) session_spec.spec.toList else Nil) :::
          session_specs.map(spec => spec.copy(options = base_options ::: spec.options))
      val options = Options.init(specs = base_options)
      val threshold = progress_threshold(options)
      val progress = log_path match {
          case None => new Console_Progress(
            verbose = verbose, threshold = threshold, detailed = false, stderr = true)
          case Some(path) => new Console_File_Progress(path,
            verbose = verbose, threshold = threshold, detailed = false, stderr = true)
        }
      val log = Logger.make_progress(progress)
      val tool_table = PIDE_MCP_Tool_Util.make_tool_table(tool_names(options))
      val sessions = new PIDE_MCP_Sessions(tool_table, log, options)
      val server = new PIDE_MCP_Server(sessions, log, progress, log_messages)
      def start_sessions(progress: Progress): Unit = {
        log("Starting PIDE sessions...")
        val exit_on_failed = exit_on_failed_option_sessions(options)
        for (spec <- specs) {
          Exn.result { Result.release(sessions.start(spec, progress)) } match {
            case Exn.Res(_) =>
            case Exn.Exn(exn) =>
              if (exit_on_failed) throw exn
              log(s"Failed to start PIDE session: ${Exn.message(exn)}")
          }
        }
      }
      val await = await_option_sessions(options)
      val result = Exn.capture {
        if (await) progress.interrupt_handler { start_sessions(progress) }
        log("Starting MCP server listening on stdin/stdout...")
        server.run(if (await) _ => () else start_sessions)
      }
      result match {
        case Exn.Exn(exn) =>
          Exn.capture { log(s"PIDE MCP error: ${Exn.print(exn)}") }
        case _ =>
      }
      val stop_log_result = Exn.capture { log("Stopping PIDE sessions...") }
      val stop_result = Exn.capture {
        Result.release(sessions.stop_running(
          progress = new Uncancellable_Progress(progress)))
        ()
      }
      Exn.release_first(List(result, stop_log_result, stop_result))
    })
}

class PIDE_MCP_Application extends Isabelle_Scala_Tools(PIDE_MCP.isabelle_tool)
