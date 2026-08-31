/*  Title:      PIDE_MCP/pide_mcp_tool.scala
    Author:     Maximilian Schäffeler, Kevin Kappelmann
*/

package isabelle.pide.mcp

import isabelle._

object PIDE_MCP_Tool {
  val max_name_length: Int = 128
  def valid_name(name: String): Boolean =
    name.nonEmpty && name.length <= max_name_length &&
      name.forall(c => Symbol.is_ascii_letter(c) || Symbol.is_ascii_digit(c) || "_-.".contains(c))
}

abstract class PIDE_MCP_Tool(val name: String) {
  if (!PIDE_MCP_Tool.valid_name(name)) error(s"Bad PIDE_MCP tool name: ${quote(name)}")

  def description: String
  def input_schema: JSON.Object.T
  def annotations: Option[JSON.Object.T] = None

  // called after each session start
  def start(
    sessions: PIDE_MCP_Sessions,
    session: PIDE_MCP_Session,
    progress: Progress
  ): Unit = ()
  def handle(
    sessions: PIDE_MCP_Sessions,
    args: JSON.Object.T,
    progress: Progress
  ): PIDE_MCP_Tool_Result
  // called before each session stop
  // note: the tool's start might not have been called yet
  def stop(
    sessions: PIDE_MCP_Sessions,
    session: PIDE_MCP_Session,
    progress: Progress
  ): Unit = ()
}

class PIDE_MCP_Tools(tools: PIDE_MCP_Tool*) extends Isabelle_System.Service {
  def entries: List[PIDE_MCP_Tool] = tools.toList
}
