/*  Title:      PIDE_MCP/tool_read.scala
    Author:     Kevin Kappelmann
*/

package isabelle.pide.mcp

import isabelle._

class Tool_Read extends PIDE_MCP_Tool("read") {
  def description: String =
    "Read line-numbered content. " +
    "Re-synchronises the file's disk content with the session. " +
    PIDE_MCP_Tool_Schema.range_perspective + " " +
    PIDE_MCP_Tool_Schema.no_processing_wait + " " +
    PIDE_MCP_Tool_Schema.implicit_reload_file

  def input_schema: JSON.Object.T = PIDE_MCP_Tool_Schema.input_schema(
    List(PIDE_MCP_Tool_Schema.running_session_arg, PIDE_MCP_Tool_Schema.origin_arg,
      PIDE_MCP_Tool_Schema.start_line_default_arg, PIDE_MCP_Tool_Schema.opt_end_line_arg))

  def handle(
    sessions: PIDE_MCP_Sessions,
    args: JSON.Object.T,
    progress: Progress
  ): PIDE_MCP_Tool_Result =
    PIDE_MCP_Tool_Result.result {
      val session = PIDE_MCP_Tool_Util.running_session_param(sessions, args)
      val node_name = PIDE_MCP_Tool_Util.origin_param(session, args)
      val start_line = PIDE_MCP_Tool_Schema.start_line_default_arg.get(args)
      val opt_end_line = PIDE_MCP_Tool_Schema.opt_end_line_arg.get(args)
      PIDE_MCP_Tool_Util.require_ordered_lines(start_line, opt_end_line)
      val text = session.read_update_resolve(node_name, List((start_line, opt_end_line)),
        await_stable_before_resolve = true, hide_others = true, progress = progress)
      val lines_count = Line.Document(text).lines.length
      val (_, end_line) = PIDE_MCP_Tool_Util.resolve_lines(start_line, opt_end_line, lines_count)
      PIDE_MCP_Util.numbered_lines_range(text, start_line, end_line)
    }
}

class Tools_Read extends PIDE_MCP_Tools(new Tool_Read)
