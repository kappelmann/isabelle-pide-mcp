/*  Title:      PIDE_MCP/tool_read.scala
    Author:     Kevin Kappelmann
*/

package isabelle.pide.mcp

import isabelle._

class Tool_Read extends PIDE_MCP_Tool("read") {
  def description: String =
    "Read line-numbered content for a given range. Use this to get the file's content. "
      + "Also use it to re-synchronise the file's disk content with the PIDE session if you experience discrepancies between the PIDE state and the external disk state (e.g. due to external edits). "
      + "Returns once Isabelle has processed the read, which takes longer if the origin's dependencies were not loaded yet. "
      + PIDE_MCP_Tool_Schema.range_visibility + " "
      + PIDE_MCP_Tool_Schema.implicit_reload_file

  def input_schema: JSON.Object.T =
    JSON_Object("type" -> "object", "properties" -> JSON_Object(
      PIDE_MCP_Tool_Schema.origin_prop,
      PIDE_MCP_Tool_Schema.opt_start_line_prop,
      PIDE_MCP_Tool_Schema.opt_end_line_prop
    ), "required" -> List("origin"))

  override def annotations: Option[JSON.Object.T] = Some(JSON_Object("readOnlyHint" -> true))

  def handle(args: JSON.Object.T): Exn.Result[JSON.T] = Exn.capture {
    val node_name = Exn.release(PIDE_MCP_Tool_Util.origin_param(session, args))
    val opt_start_line = JSON.int(args, "start_line")
    val opt_end_line = JSON.int(args, "end_line")
    Exn.release(PIDE_MCP_Tool_Util.require_ordered_lines(opt_start_line, opt_end_line))
    val text = Exn.release(session.read_update_resolve(
      node_name, List((opt_start_line.getOrElse(1), opt_end_line)),
      await_stable_before_resolve = true, hide_others = true))
    val lines_count = Line.Document(text).lines.length
    val (start_line, end_line) =
      Exn.release(PIDE_MCP_Tool_Util.resolve_lines(opt_start_line, opt_end_line, lines_count))
    PIDE_MCP_Util.numbered_lines_range(text, start_line, end_line)
  }
}

class Tools_Read extends PIDE_MCP_Tools(new Tool_Read)
