/*  Title:      PIDE_MCP/tool_edit.scala
    Author:     Kevin Kappelmann
*/

package isabelle.pide.mcp

import isabelle._
import scala.util.matching.Regex

object Tool_Edit {
  sealed trait Edit_Mode
  case object Edit_Replace extends Edit_Mode
  case object Edit_Prepend extends Edit_Mode
  case object Edit_Append extends Edit_Mode

  object Edit_Mode {
    def parse(s: String): Exn.Result[Edit_Mode] = Exn.capture {
      s match {
        case "replace" => Edit_Replace
        case "prepend" => Edit_Prepend
        case "append" => Edit_Append
        case _ => error("Invalid edit mode: " + s)
      }
    }
  }

  def apply_edit(
    mode: Edit_Mode,
    full_text: String,
    offset: Int,
    old_length: Int,
    new_text: String
  ): String = {
    mode match {
      case Edit_Replace =>
        full_text.slice(0, offset) + new_text + full_text.slice(offset + old_length, full_text.length)
      case Edit_Prepend =>
        full_text.slice(0, offset) + new_text + full_text.slice(offset, full_text.length)
      case Edit_Append =>
        full_text.slice(0, offset + old_length) + new_text + full_text.slice(offset + old_length, full_text.length)
    }
  }

  def edited_text_ranges(
    mode: Edit_Mode,
    offsets: List[Text.Offset],
    old_length: Int,
    new_length: Int
  ): List[Text.Range] = {
    val shift = if (mode == Edit_Replace) new_length - old_length else new_length
    val insert_offset = if (mode == Edit_Append) old_length else 0
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
    edit_all: Boolean
  ): Exn.Result[(String, Int)] = Exn.capture {
    if (session.is_base_session_theory(node_name))
      error("Cannot edit base session theory " + session.origin(node_name))
    Exn.release(PIDE_MCP_Tool_Util.require_ordered_lines(opt_start_line, opt_end_line))
    session.synchronized {
      val current_text = Exn.release(
        session.read_update(List(node_name -> Nil), hide_others = false))(node_name)
      val doc = Line.Document(current_text)
      val (start_line, end_line) = Exn.release(
        PIDE_MCP_Tool_Util.resolve_lines(opt_start_line, opt_end_line, doc.lines.length))
      val edit_range = PIDE_MCP_Util.text_range(doc, start_line, end_line).get
      val range_text = edit_range.substring(current_text)
      val actual_old_text = if (old_text.isEmpty) range_text else old_text
      val offsets = if (old_text.isEmpty) List(edit_range.start)
        else {
          val occurrences = new Regex(Regex.quote(actual_old_text))
            .findAllMatchIn(range_text).map(_.start + edit_range.start).toList
          if (occurrences.isEmpty) error("old_text not found in the given range.")
          if (!edit_all && occurrences.length > 1)
            error(s"Found ${occurrences.length} occurrences of old_text in the given range. Expected exactly 1. "
              + "Provide more context (larger old_text), restrict the range, or use edit_all.")
          if (edit_all) occurrences else List(occurrences.head)
        }
      val computed_text = offsets.reverseIterator.foldLeft(current_text) { (text, offset) =>
        apply_edit(mode, text, offset, actual_old_text.length, new_text)
      }
      val changed = computed_text != current_text
      if (changed) Exn.release(session.write_file_content(node_name.path, computed_text))
      val edited_doc = Line.Document(computed_text)
      val visible_lines =
        edited_text_ranges(mode, offsets, actual_old_text.length, new_text.length).map { range =>
          val line_range = edited_doc.range(range)
          (line_range.start.line1, Some(line_range.stop.line1))
        }
      val text = Exn.release(session.read_update_resolve(
        node_name, visible_lines, await_stable_before_resolve = true, hide_others = true))
      (text, if (changed) offsets.length else 0)
    }
  }
}

class Tool_Edit extends PIDE_MCP_Tool("edit") {
  def description: String =
    "Edit a file by either replacing, prepending to, or appending to a matching old text in a given range. "
      + "Returns once Isabelle has processed the edit, which takes longer if the origin's dependencies were not loaded yet. "
      + "Note: base session files are static and cannot be edited. "
      + PIDE_MCP_Tool_Schema.range_visibility + " "
      + PIDE_MCP_Tool_Schema.implicit_reload_file

  def input_schema: JSON.Object.T =
    JSON.Object("type" -> "object", "properties" -> JSON.Object(
      PIDE_MCP_Tool_Schema.origin_prop,
      "mode" -> JSON.Object("type" -> "string",
        "enum" -> List("replace", "prepend", "append"),
        "description" -> "Edit mode",
        "default" -> "replace"),
      "text" -> JSON.Object("type" -> "string", "description" -> "New text to write"),
      PIDE_MCP_Tool_Schema.opt_start_line_prop,
      PIDE_MCP_Tool_Schema.opt_end_line_prop,
      "old_text" -> JSON.Object("type" -> "string",
        "description" -> "Text to find as a substring within the given range. If old_text is empty, the whole text in range is selected instead."),
      "edit_all" -> JSON.Object("type" -> "boolean",
        "description" -> "Edit every match or just a unique occurrence.",
        "default" -> false)
    ), "required" -> List("origin", "text", "old_text"))

  override def annotations: Option[JSON.Object.T] = Some(JSON.Object("destructiveHint" -> true))

  def handle(params: JSON.Object.T): Exn.Result[JSON.T] = Exn.capture {
    val node_name = Exn.release(PIDE_MCP_Tool_Util.origin_param(session, params))
    val mode = Exn.release(Tool_Edit.Edit_Mode.parse(
      JSON.string(params, "mode").getOrElse("replace")))
    val text = Line.normalize(JSON.string(params, "text").getOrElse(error("Missing text parameter")))
    val old_text = Line.normalize(
      JSON.string(params, "old_text").getOrElse(error("Missing old_text parameter")))
    val edit_all = JSON.bool(params, "edit_all").getOrElse(false)
    val opt_start_line = JSON.int(params, "start_line")
    val opt_end_line = JSON.int(params, "end_line")
    val (new_text, count) = Exn.release(Tool_Edit.read_update_edit(
      session, mode, node_name, text, opt_start_line, opt_end_line, old_text, edit_all = edit_all))
    val (status, description) = if (count > 0) ("written", s"Edited $count occurrence(s)")
      else ("unchanged", "Unchanged - did you replace the text by itself?")
    JSON.Object("status" -> status, "description" -> description)
  }
}

class Tools_Edit extends PIDE_MCP_Tools(new Tool_Edit)
