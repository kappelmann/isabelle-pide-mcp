/*  Title:      MCP/src/Tools/tool_edit.scala
    Author:     Kevin Kappelmann
*/

package isabelle.pide.mcp

import isabelle._

object Tool_Edit {
  enum Edited_Lines { case none, each, range }

  val edit_description = "Edit matching text in a given range."

  val mode_arg = JSON_Schema.Arg.default("mode", "",
    JSON_Schema.Format.enumeration(PIDE_MCP_Edit.Mode.values), PIDE_MCP_Edit.Mode.replace)
  val text_arg = JSON_Schema.Arg.required(
    "text", "Text to write.", JSON_Schema.Format.string)
  val old_text_arg = JSON_Schema.Arg.required(
    "old_text", "Text to find. If empty, whole text in range is selected.",
    JSON_Schema.Format.string)
  val edit_all_arg = JSON_Schema.Arg.default(
    "edit_all", "Edit every match or a unique occurrence.", JSON_Schema.Format.bool,
    false)
  val edited_lines_arg = JSON_Schema.Arg.default(
    "edited_lines", "Return format.", JSON_Schema.Format.enumeration(Edited_Lines.values),
    Edited_Lines.range)

  val edit_args: List[JSON_Schema.Arg[?]] = List(PIDE_MCP_Tool_Schema.running_session_arg,
    PIDE_MCP_Tool_Schema.origin_arg, mode_arg, text_arg,
    PIDE_MCP_Tool_Schema.start_line_default_arg, PIDE_MCP_Tool_Schema.opt_end_line_arg,
    old_text_arg, edit_all_arg, edited_lines_arg)

  def read_update_edit(
    session: PIDE_MCP_Session,
    node_name: Document.Node.Name,
    args: JSON.Object.T,
    progress: Progress
  ): (List[(Int, Int)], JSON.Object.T) = {
    val text = session.pide_text(node_name.path, Line.normalize(text_arg.get(args)))
    val old_text = session.pide_text(node_name.path, Line.normalize(old_text_arg.get(args)))
    val edited_lines = PIDE_MCP_Edit.read_update_edit(session, mode_arg.get(args), node_name,
      text, PIDE_MCP_Tool_Schema.start_line_default_arg.get(args),
      PIDE_MCP_Tool_Schema.opt_end_line_arg.get(args), old_text,
      edit_all = edit_all_arg.get(args), progress = progress)
    val lines = edited_lines_arg.get(args) match {
      case Edited_Lines.none => None
      case Edited_Lines.range => Option.when(edited_lines.nonEmpty)(
        PIDE_MCP_Util.line_range(edited_lines.head._1, edited_lines.last._2))
      case Edited_Lines.each => proper_list(edited_lines.map(PIDE_MCP_Util.line_range))
    }
    val json = JSON_Object("edits" -> edited_lines.length) ++
      JSON.optional("edited_lines" -> lines) ++ JSON_Object.if_proper(edited_lines.isEmpty,
        JSON_Object("note" -> "The edit left the text unchanged."))
    (edited_lines, json)
  }
}

class Tool_Edit extends PIDE_MCP_Tool("edit") {
  def description: String =
    Tool_Edit.edit_description + " " + PIDE_MCP_Tool_Schema.range_perspective + " " +
    PIDE_MCP_Tool_Schema.no_processing_wait + " " +
    PIDE_MCP_Tool_Schema.implicit_reload_file

  def input_schema: JSON.Object.T = JSON_Schema.object_schema(Tool_Edit.edit_args)

  override def annotations: Option[JSON.Object.T] = Some(JSON_Object("destructiveHint" -> true))

  def handle(
    sessions: PIDE_MCP_Sessions,
    args: JSON.Object.T,
    progress: Progress
  ): PIDE_MCP_Tool_Result =
    PIDE_MCP_Tool_Result.result {
      val session = PIDE_MCP_Tool_Util.running_session_param(sessions, args)
      val node_name = PIDE_MCP_Tool_Util.origin_param(session, args)
      Tool_Edit.read_update_edit(session, node_name, args, progress)._2
    }
}

class Tools_Edit extends PIDE_MCP_Tools(new Tool_Edit)
