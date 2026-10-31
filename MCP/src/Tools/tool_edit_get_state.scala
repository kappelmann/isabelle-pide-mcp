/*  Title:      MCP/src/Tools/tool_edit_get_state.scala
    Author:     Kevin Kappelmann
*/

package isabelle.pide.mcp

import isabelle._

class Tool_Edit_Get_State extends PIDE_MCP_Tool("edit_get_state") {
  def description: String =
    Tool_Edit.edit_description + " " + Tool_Get_State.state_description("edited commands") + " " +
    PIDE_MCP_Tool_Schema.range_perspective + " " + PIDE_MCP_Tool_Schema.implicit_reload_file

  private val timeout_arg = Tool_Get_State.timeout_arg(5)

  def input_schema: JSON.Object.T = JSON_Schema.object_schema(
    Tool_Edit.edit_args ::: Tool_Get_State.state_args(timeout_arg))

  override def annotations: Option[JSON.Object.T] = Some(JSON_Object("destructiveHint" -> true))

  def handle(
    sessions: PIDE_MCP_Sessions,
    args: JSON.Object.T,
    progress: Progress
  ): PIDE_MCP_Tool_Result =
    PIDE_MCP_Tool_Result.result {
      val session = PIDE_MCP_Tool_Util.running_session_param(sessions, args)
      val node_name = PIDE_MCP_Tool_Util.origin_param(session, args)
      val (edited_lines, json) = Tool_Edit.read_update_edit(session, node_name, args, progress)
      json ++ JSON.optional("state" -> Option.when(edited_lines.nonEmpty)(
        Tool_Get_State.state(session, node_name, edited_lines.head._1,
          Some(edited_lines.last._2), Time.seconds(timeout_arg.get(args)), args,
          progress)))
    }
}

class Tools_Edit_Get_State extends PIDE_MCP_Tools(new Tool_Edit_Get_State)
