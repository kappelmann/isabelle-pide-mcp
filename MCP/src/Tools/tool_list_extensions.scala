/*  Title:      MCP/src/Tools/tool_list_extensions.scala
    Author:     Kevin Kappelmann
*/

package isabelle.pide.mcp

import isabelle._

class Tool_List_Extensions extends PIDE_MCP_Tool("list_extensions") {
  def description: String = "List services and tools."
  private val include_details_arg = JSON_Schema.Arg.default(
    "include_details", "", JSON_Schema.Format.bool, false)
  def input_schema: JSON.Object.T = JSON_Schema.object_schema(List(include_details_arg))
  override def annotations: Option[JSON.Object.T] = Some(JSON_Object("readOnlyHint" -> true))

  def handle(
    sessions: PIDE_MCP_Sessions,
    args: JSON.Object.T,
    progress: Progress
  ): PIDE_MCP_Tool_Result =
    PIDE_MCP_Tool_Result.result {
      def entries(table: Map[String, PIDE_MCP_Service]): List[JSON.T] = {
        val services = table.values.toList.sortBy(_.name)
        if (include_details_arg.get(args)) services.map(_.json) else services.map(_.name)
      }
      JSON_Object(
        "services" -> entries(sessions.service_table),
        "tools" -> entries(sessions.tool_table))
    }
}

class Tools_List_Extensions extends PIDE_MCP_Tools(new Tool_List_Extensions)
