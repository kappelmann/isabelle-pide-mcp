/*  Title:      PIDE_MCP/tool_edit.scala
    Author:     Kevin Kappelmann
*/

package isabelle.pide.mcp

import isabelle._

import scala.util.matching.Regex

object Tool_Edit {
  enum Edit_Mode { case replace, prepend, append }

  object Edit_Mode {
    val default: Edit_Mode = replace
    val format = PIDE_MCP_Tool_Arg.Format(
      JSON_Object("type" -> "string", "enum" -> values.toList.map(_.toString)),
      json => JSON.Value.String.unapply(json).flatMap(mode =>
        values.find(_.toString == mode)), _.toString)
  }

  def replaced_range(mode: Edit_Mode, offset: Text.Offset, old_length: Int): Text.Range =
    mode match {
      case Edit_Mode.replace => Text.Range(offset, offset + old_length)
      case Edit_Mode.prepend => Text.Range(offset)
      case Edit_Mode.append => Text.Range(offset + old_length)
    }

  def read_update_edit(
    session: PIDE_MCP_Session,
    mode: Edit_Mode,
    node_name: Document.Node.Name,
    new_text: String,
    start_line: Int,
    end_line: Option[Int],
    old_text: String,
    edit_all: Boolean,
    progress: Progress
  ): Int = {
    if (session.is_base_session_theory(node_name))
      error(s"Cannot edit base session theory ${quote(session.origin(node_name))}")
    PIDE_MCP_Tool_Util.require_ordered_lines(start_line, end_line)
    session.with_lock(progress) {
      val current_text = session.read_update(
        List(node_name -> Nil), hide_others = false, progress = progress)(node_name)
      val doc = Line.Document(current_text)
      val (first_line, last_line) =
        PIDE_MCP_Tool_Util.resolve_lines(start_line, end_line, doc.lines.length)
      val search_range = PIDE_MCP_Util.text_range(doc, first_line, last_line).get
      val range_text = search_range.substring(current_text)
      val actual_old_text = if (old_text.isEmpty) range_text else old_text
      val offsets = if (old_text.isEmpty) List(search_range.start)
        else {
          val occurrences = new Regex(Regex.quote(actual_old_text))
            .findAllMatchIn(range_text).map(_.start + search_range.start).toList
          if (occurrences.isEmpty) error("old_text not found in the given range")
          if (!edit_all && occurrences.length > 1)
            error(s"Found ${occurrences.length} occurrences of old_text in the given range. " +
              "Expected exactly 1. " +
              "Provide more context (larger old_text), restrict the range, or use edit_all.")
          if (edit_all) occurrences else List(occurrences.head)
        }
      val replaced_ranges = offsets.map(replaced_range(mode, _, actual_old_text.length))
      val computed_text = replaced_ranges.reverseIterator.foldLeft(current_text) { (text, range) =>
        text.take(range.start) + new_text + text.drop(range.stop)
      }
      val changed = computed_text != current_text
      val edited_doc = Line.Document(computed_text)
      val visible_lines =
        replaced_ranges.zipWithIndex.map { case (range, i) =>
          val start = range.start + i * (new_text.length - range.length)
          val line_range = edited_doc.range(Text.Range(start, start + new_text.length))
          (line_range.start.line1, Some(line_range.stop.line1))
        }
      progress.expose_interrupt()
      if (changed) session.write_file_content(node_name.path, computed_text)
      session.read_update(
        List(node_name -> visible_lines), hide_others = true,
        // avoid disk <--> PIDE disagreement
        progress = new Uncancellable_Progress(progress))
      session.await_stable_snapshot(progress)
      session.resolve_dependencies(List(node_name), progress)
      if (changed) offsets.length else 0
    }
  }
}

class Tool_Edit extends PIDE_MCP_Tool("edit") {
  def description: String =
    "Edit the text matching old_text in a given range. " +
    PIDE_MCP_Tool_Schema.range_visibility + " " +
    PIDE_MCP_Tool_Schema.no_processing_wait + " " +
    PIDE_MCP_Tool_Schema.implicit_reload_file

  private val mode_arg = PIDE_MCP_Tool_Arg.default(
    "mode", "", Tool_Edit.Edit_Mode.format, Tool_Edit.Edit_Mode.default)
  private val text_arg = PIDE_MCP_Tool_Arg.string("text", "Text to write.")
  private val old_text_arg = PIDE_MCP_Tool_Arg.string(
    "old_text", "Text to find. On empty old_text, the whole text in range is selected.")
  private val edit_all_arg = PIDE_MCP_Tool_Arg.bool_default(
    "edit_all", "Edit every match or a unique occurrence.", false)

  def input_schema: JSON.Object.T = PIDE_MCP_Tool_Schema.input_schema(
    List(PIDE_MCP_Tool_Schema.running_session_arg, PIDE_MCP_Tool_Schema.origin_arg, mode_arg,
      text_arg, PIDE_MCP_Tool_Schema.start_line_default_arg,
      PIDE_MCP_Tool_Schema.opt_end_line_arg, old_text_arg, edit_all_arg))

  override def annotations: Option[JSON.Object.T] = Some(JSON_Object("destructiveHint" -> true))

  def handle(
    sessions: PIDE_MCP_Sessions,
    args: JSON.Object.T,
    progress: Progress
  ): PIDE_MCP_Tool_Result =
    PIDE_MCP_Tool_Result.result {
      val session = PIDE_MCP_Tool_Util.running_session_param(sessions, args)
      val node_name = PIDE_MCP_Tool_Util.origin_param(session, args)
      val mode = mode_arg.get(args)
      val text = Line.normalize(text_arg.get(args))
      val old_text =
        PIDE_MCP_Util.decode_file_text(node_name.path, Line.normalize(old_text_arg.get(args)))
      val edit_all = edit_all_arg.get(args)
      val start_line = PIDE_MCP_Tool_Schema.start_line_default_arg.get(args)
      val opt_end_line = PIDE_MCP_Tool_Schema.opt_end_line_arg.get(args)
      val count = Tool_Edit.read_update_edit(
        session, mode, node_name, text, start_line, opt_end_line, old_text,
        edit_all = edit_all, progress = progress)
      JSON_Object("edits" -> count) ++
      JSON_Object.if_proper(count == 0, JSON_Object("note" -> "The edit left the text unchanged."))
    }
}

class Tools_Edit extends PIDE_MCP_Tools(new Tool_Edit)
