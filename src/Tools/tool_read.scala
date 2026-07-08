/*  Title:      PIDE_MCP/tool_read.scala
    Author:     Kevin Kappelmann
*/

package isabelle.pide.mcp

import isabelle._

class Tool_Read extends PIDE_MCP_Tool("read") {
  def description: String =
    "Read line-numbered content for a given range. Use this to get the file's content. Also use it to re-synchronise the file's disk content with the PIDE session if you experience discrepancies between the PIDE state and the external disk state (e.g. due to external edits). " + PIDE_MCP_Tool_Schema.implicit_reload_file

  def input_schema: JSON.Object.T =
    JSON.Object("type" -> "object", "properties" -> JSON.Object(
      PIDE_MCP_Tool_Schema.origin_prop,
      PIDE_MCP_Tool_Schema.start_line_opt_prop,
      PIDE_MCP_Tool_Schema.end_line_opt_prop
    ), "required" -> List("origin"))

  override def annotations: Option[JSON.Object.T] = Some(JSON.Object("readOnlyHint" -> true))

  def handle(params: JSON.Object.T): Exn.Result[JSON.T] = Exn.capture {
    val node_name = Exn.release(PIDE_MCP_Tool_Util.origin_param(session, params))
    val text = Exn.release(session.read_load(node_name))
    val start_line = JSON.int(params, "start_line")
    val end_line = JSON.int(params, "end_line")
    val lines_count = Line.Document(text).lines.length
    val (s, e) = Exn.release(PIDE_MCP_Tool_Util.resolve_lines(start_line, end_line, lines_count))
    PIDE_MCP_Util.numbered_lines_range(text, s, e)
  }
}

class Tools_Read extends PIDE_MCP_Tools(new Tool_Read)
