/*  Title:      MCP/src/Tools/tool_stop_session.scala
    Author:     Kevin Kappelmann
*/

package isabelle.pide.mcp

import isabelle._

class Tool_Stop_Session extends PIDE_MCP_Tool("stop_session") {
  def description: String = "Stop sessions. " +
    PIDE_MCP_Tool_Schema.session_restart

  private val sessions_arg = PIDE_MCP_Tool_Arg.opt_strings(
    "sessions", "Stoppable session ids. Default: all stoppable sessions.")

  def input_schema: JSON.Object.T =
    PIDE_MCP_Tool_Schema.input_schema(List(sessions_arg))

  override def annotations: Option[JSON.Object.T] = Some(JSON_Object("destructiveHint" -> true))

  def handle(
    sessions: PIDE_MCP_Sessions,
    args: JSON.Object.T,
    progress: Progress
  ): PIDE_MCP_Tool_Result =
    PIDE_MCP_Tool_Result.result {
      val stopped = Result.release(sessions.stop_sessions(sessions_arg.get(args), progress))
      JSON_Object("stopped" -> stopped, "remaining" -> sessions.state_json())
    }
}

class Tools_Stop_Session extends PIDE_MCP_Tools(new Tool_Stop_Session)
