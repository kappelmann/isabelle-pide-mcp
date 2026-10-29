/*  Title:      MCP/src/pide_mcp_tool.scala
    Author:     Maximilian Schäffeler, Kevin Kappelmann
*/

package isabelle.pide.mcp

import isabelle._

object PIDE_MCP_Tool {
  val kind: String = "tool"

  def make_table(
    include_names: String = PIDE_MCP_Service.all_option,
    exclude_names: String = ""
  ): Map[String, PIDE_MCP_Tool] =
    PIDE_MCP_Service.make_table(kind,
      Isabelle_System.make_services(classOf[PIDE_MCP_Tools]).flatMap(_.entries),
      include_names, exclude_names)
}

abstract class PIDE_MCP_Tool(name: String) extends PIDE_MCP_Service(name) {
  override def kind: String = PIDE_MCP_Tool.kind

  def input_schema: JSON.Object.T
  def annotations: Option[JSON.Object.T] = None

  override def json: JSON.Object.T =
    super.json ++ JSON_Object("inputSchema" -> input_schema) ++
    JSON.optional("annotations" -> annotations)

  def handle(
    sessions: PIDE_MCP_Sessions,
    args: JSON.Object.T,
    progress: Progress
  ): PIDE_MCP_Tool_Result
}

class PIDE_MCP_Tools(tools: PIDE_MCP_Tool*) extends Isabelle_System.Service {
  def entries: List[PIDE_MCP_Tool] = tools.toList
}
