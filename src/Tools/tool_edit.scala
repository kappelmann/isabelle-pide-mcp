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
    val names: List[String] = values.toList.map(_.toString)
    val format = PIDE_MCP_Tool_Arg.Format(
      JSON_Object("type" -> "string", "enum" -> names),
      json => JSON.Value.String.unapply(json).flatMap(mode =>
        values.find(_.toString == mode)), _.toString)
  }

  def apply_edit(
    mode: Edit_Mode,
    full_text: String,
    offset: Int,
    old_length: Int,
    new_text: String
  ): String = {
    mode match {
      case Edit_Mode.replace =>
        full_text.slice(0, offset) + new_text + full_text.slice(offset + old_length, full_text.length)
      case Edit_Mode.prepend =>
        full_text.slice(0, offset) + new_text + full_text.slice(offset, full_text.length)
      case Edit_Mode.append =>
        full_text.slice(0, offset + old_length) + new_text + full_text.slice(offset + old_length, full_text.length)
    }
  }

  def edited_text_ranges(
    mode: Edit_Mode,
    offsets: List[Text.Offset],
    old_length: Int,
    new_length: Int
  ): List[Text.Range] = {
    val shift = if (mode == Edit_Mode.replace) new_length - old_length else new_length
    val insert_offset = if (mode == Edit_Mode.append) old_length else 0
    offsets.zipWithIndex.map { case (offset, i) =>
      val start = offset + i * shift + insert_offset
      Text.Range(start, start + new_length)
    }
  }

  def read_update_edit(
    session: PIDE_MCP_Session,
    mode: Edit_Mode,
    node_name: Document.Node.Name,
    new_text: String,
    opt_start_line: Option[Int],
    opt_end_line: Option[Int],
    old_text: String,
    edit_all: Boolean,
    progress: Progress = new Progress
  ): (String, Int) = {
    if (session.is_base_session_theory(node_name))
      error(s"Cannot edit base session theory ${quote(session.origin(node_name))}")
    PIDE_MCP_Tool_Util.require_ordered_lines(opt_start_line, opt_end_line)
    session.with_lock(progress) {
      val current_text = session.read_update(
        List(node_name -> Nil), hide_others = false, progress = progress)(node_name)
      val doc = Line.Document(current_text)
      val (start_line, end_line) =
        PIDE_MCP_Tool_Util.resolve_lines(opt_start_line, opt_end_line, doc.lines.length)
      val edit_range = PIDE_MCP_Util.text_range(doc, start_line, end_line).get
      val range_text = edit_range.substring(current_text)
      val actual_old_text = if (old_text.isEmpty) range_text else old_text
      val offsets = if (old_text.isEmpty) List(edit_range.start)
        else {
          val occurrences = new Regex(Regex.quote(actual_old_text))
            .findAllMatchIn(range_text).map(_.start + edit_range.start).toList
          if (occurrences.isEmpty) error("old_text not found in the given range")
          if (!edit_all && occurrences.length > 1)
            error(s"Found ${occurrences.length} occurrences of old_text in the given range. Expected exactly 1. " +
              "Provide more context (larger old_text), restrict the range, or use edit_all.")
          if (edit_all) occurrences else List(occurrences.head)
        }
      val computed_text = offsets.reverseIterator.foldLeft(current_text) { (text, offset) =>
        apply_edit(mode, text, offset, actual_old_text.length, new_text)
      }
      val changed = computed_text != current_text
      val edited_doc = Line.Document(computed_text)
      val visible_lines =
        edited_text_ranges(mode, offsets, actual_old_text.length, new_text.length).map { range =>
          val line_range = edited_doc.range(range)
          (line_range.start.line1, Some(line_range.stop.line1))
        }
      progress.expose_interrupt()
      if (changed) session.write_file_content(node_name.path, computed_text)
      val text = session.read_update(
        List(node_name -> visible_lines), hide_others = true,
        // avoid disk <--> PIDE disagreement
        progress = new Uncancellable_Progress(progress))(node_name)
      session.await_stable_snapshot(progress)
      session.resolve_dependencies(progress)
      (text, if (changed) offsets.length else 0)
    }
  }
}

class Tool_Edit extends PIDE_MCP_Tool("edit") {
  def description: String =
    "Edit a file by either replacing, prepending to, or appending to a matching old text in a given range. " +
      "Returns once Isabelle has processed the edit, which takes longer if the origin's dependencies were not loaded yet. " +
      "Note: base session files are static and cannot be edited. " +
      PIDE_MCP_Tool_Schema.range_visibility + " " +
      PIDE_MCP_Tool_Schema.implicit_reload_file

  private val mode_arg = PIDE_MCP_Tool_Arg.default(
    "mode", "Edit mode",
    Tool_Edit.Edit_Mode.format, Tool_Edit.Edit_Mode.default)
  private val text_arg = PIDE_MCP_Tool_Arg.string("text", "New text to write")
  private val old_text_arg = PIDE_MCP_Tool_Arg.string(
    "old_text",
    "Text to find as a substring within the given range. If old_text is empty, the whole text in range is selected instead.")
  private val edit_all_arg = PIDE_MCP_Tool_Arg.bool_default(
    "edit_all",
    "Edit every match or just a unique occurrence.", false)

  def input_schema: JSON.Object.T = PIDE_MCP_Tool_Schema.input_schema(
    List(PIDE_MCP_Tool_Schema.running_session_arg, PIDE_MCP_Tool_Schema.origin_arg, mode_arg,
      text_arg, PIDE_MCP_Tool_Schema.opt_start_line_arg,
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
      val old_text = Line.normalize(old_text_arg.get(args))
      val edit_all = edit_all_arg.get(args)
      val opt_start_line = PIDE_MCP_Tool_Schema.opt_start_line_arg.get(args)
      val opt_end_line = PIDE_MCP_Tool_Schema.opt_end_line_arg.get(args)
      val (_, count) = Tool_Edit.read_update_edit(
        session, mode, node_name, text, opt_start_line, opt_end_line, old_text,
        edit_all = edit_all, progress = progress)
      val (status, description) = if (count > 0) ("written", s"Edited $count occurrence(s)")
        else ("unchanged", "Unchanged - did you replace the text by itself?")
      JSON_Object("status" -> status, "description" -> description)
    }
}

class Tools_Edit extends PIDE_MCP_Tools(new Tool_Edit)
