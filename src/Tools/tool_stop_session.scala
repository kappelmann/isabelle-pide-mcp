/*  Title:      PIDE_MCP/tool_stop_session.scala
    Author:     Kevin Kappelmann
*/

package isabelle.pide.mcp

import isabelle._

class Tool_Stop_Session extends PIDE_MCP_Tool("stop_session") {
  def description: String = "Stop PIDE sessions. Files on disk are kept, unless some tool decides to cleanup its auxiliary/scratch files. " +
    PIDE_MCP_Tool_Schema.session_restart

  def input_schema: JSON.Object.T =
    PIDE_MCP_Tool_Schema.input_schema(List(PIDE_MCP_Tool_Schema.running_sessions_arg))

  override def annotations: Option[JSON.Object.T] = Some(JSON_Object("destructiveHint" -> true))

  def handle(
    sessions: PIDE_MCP_Sessions,
    args: JSON.Object.T,
    progress: Progress
  ): PIDE_MCP_Tool_Result =
    Exn.result { PIDE_MCP_Tool_Schema.running_sessions_arg.get(args) } match {
      case Exn.Exn(exn) => PIDE_MCP_Tool_Result.exn_error(exn)
      case Exn.Res(session_ids) =>
        sessions.stop_running(session_ids, progress) match {
          case Result.Res(stopped) => PIDE_MCP_Tool_Result.Res(JSON_Object(
            "stopped" -> stopped, "running" -> sessions.all_running().map(_.id)))
          case Result.Error(exn) => PIDE_MCP_Tool_Result.exn_error(exn)
        }
    }
}

class Tools_Stop_Session extends PIDE_MCP_Tools(new Tool_Stop_Session)
