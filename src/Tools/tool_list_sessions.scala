/*  Title:      PIDE_MCP/tool_list_sessions.scala
    Author:     Kevin Kappelmann
*/

package isabelle.pide.mcp

import isabelle._

class Tool_List_Sessions extends PIDE_MCP_Tool("list_sessions") {
  def description: String = "List all sessions."
  def input_schema: JSON.Object.T = PIDE_MCP_Tool_Schema.input_schema(Nil)
  override def annotations: Option[JSON.Object.T] = Some(JSON_Object("readOnlyHint" -> true))

  def handle(
    sessions: PIDE_MCP_Sessions,
    args: JSON.Object.T,
    progress: Progress
  ): PIDE_MCP_Tool_Result =
    PIDE_MCP_Tool_Result.result { sessions.state_json() }
}

class Tools_List_Sessions extends PIDE_MCP_Tools(new Tool_List_Sessions)
