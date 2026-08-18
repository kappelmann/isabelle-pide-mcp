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
    old_text: String,
    new_text: String
  ): String = {
    mode match {
      case Edit_Replace =>
        full_text.slice(0, offset) + new_text + full_text.slice(offset + old_text.length, full_text.length)
      case Edit_Prepend =>
        full_text.slice(0, offset) + new_text + full_text.slice(offset, full_text.length)
      case Edit_Append =>
        full_text.slice(0, offset + old_text.length) + new_text + full_text.slice(offset + old_text.length, full_text.length)
    }
  }

  def read_update_edit(
    session: PIDE_MCP_Session,
    mode: Edit_Mode,
    node_name: Document.Node.Name,
    new_text: String,
    start_line: Option[Int],
    end_line: Option[Int],
    old_text: String,
    edit_all: Boolean
  ): Exn.Result[(String, Int)] = Exn.capture {
    if (session.is_base_session_theory(node_name))
      error("Cannot edit base session theory " + session.origin(node_name))
    session.synchronized {
      val current_text = Exn.release(session.read_update_resolve(node_name))
      val doc = Line.Document(current_text)
      val (s, e) = Exn.release(PIDE_MCP_Tool_Util.resolve_lines(start_line, end_line, doc.lines.length))
      val edit_range = PIDE_MCP_Util.range(doc, s, e)
      val range_text = edit_range.substring(current_text)
      val actual_old_text = if (old_text.isEmpty) range_text else old_text
      val offsets = (if (old_text.isEmpty) List(edit_range.start)
        else {
          val occurrences = new Regex(Regex.quote(actual_old_text))
            .findAllMatchIn(range_text).map(_.start + edit_range.start).toList
          if (occurrences.isEmpty) error("old_text not found in the given range.")
          if (!edit_all && occurrences.length > 1)
            error(s"Found ${occurrences.length} occurrences of old_text in the given range. Expected exactly 1. "
              + "Provide more context (larger old_text), restrict the range, or use edit_all.")
          if (edit_all) occurrences else List(occurrences.head)
        }).reverse
      val computed_text = offsets.foldLeft(current_text) { (text, offset) =>
        apply_edit(mode, text, offset, actual_old_text, new_text)
      }
      if (computed_text != current_text) {
        Exn.release(session.write_file_content(node_name.path, computed_text))
        (Exn.release(session.read_update_resolve(node_name)), offsets.length)
      }
      else (computed_text, 0)
    }
  }
}

class Tool_Edit extends PIDE_MCP_Tool("edit") {
  def description: String =
    "Edit a file by either replacing, prepending to, or appending to a matching old text in a given range. "
      + "Note: base session files are static and cannot be edited. "
      + PIDE_MCP_Tool_Schema.implicit_reload_file

  def input_schema: JSON.Object.T =
    JSON.Object("type" -> "object", "properties" -> JSON.Object(
      PIDE_MCP_Tool_Schema.origin_prop,
      "mode" -> JSON.Object("type" -> "string",
        "enum" -> List("replace", "prepend", "append"),
        "description" -> "Edit mode",
        "default" -> "replace"),
      "text" -> JSON.Object("type" -> "string", "description" -> "New text to write"),
      PIDE_MCP_Tool_Schema.start_line_opt_prop,
      PIDE_MCP_Tool_Schema.end_line_opt_prop,
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
    val text = JSON.string(params, "text").getOrElse(error("Missing text parameter"))
    val old_text = JSON.string(params, "old_text").getOrElse(error("Missing old_text parameter"))
    val edit_all = JSON.bool(params, "edit_all").getOrElse(false)
    val start_line = JSON.int(params, "start_line")
    val end_line = JSON.int(params, "end_line")
    val (new_text, count) = Exn.release(Tool_Edit.read_update_edit(
      session, mode, node_name, text, start_line, end_line, old_text, edit_all = edit_all))
    val (status, description) = if (count > 0) {
        session.await_stable_snapshot()
        ("written", s"Edited $count occurrence(s)")
      } else ("unchanged", "Unchanged - did you replace the text by itself?")
    JSON.Object("status" -> status, "description" -> description)
  }
}

class Tools_Edit extends PIDE_MCP_Tools(new Tool_Edit)
