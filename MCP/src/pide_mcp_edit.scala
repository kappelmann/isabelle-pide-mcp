/*  Title:      MCP/src/pide_mcp_edit.scala
    Author:     Kevin Kappelmann

Edits of loaded files with disk synchronisation.
*/

package isabelle.pide.mcp

import isabelle._

import scala.util.matching.Regex

object PIDE_MCP_Edit {
  enum Mode { case replace, prepend, append }

  def replaced_range(mode: Mode, offset: Text.Offset, old_length: Int): Text.Range =
    mode match {
      case Mode.replace => Text.Range(offset, offset + old_length)
      case Mode.prepend => Text.Range(offset)
      case Mode.append => Text.Range(offset + old_length)
    }

  def read_update_edit(
    session: PIDE_MCP_Session,
    mode: Mode,
    node_name: Document.Node.Name,
    new_text: String,
    start_line: Int,
    end_line: Option[Int],
    old_text: String,
    edit_all: Boolean,
    progress: Progress
  ): List[(Int, Int)] = {
    if (session.is_base_session_theory(node_name))
      error(s"Cannot edit base session theory ${quote(session.origin(node_name))}")
    PIDE_MCP_Util.check_ordered_lines(start_line, end_line)
    session.with_lock(progress) {
      val current_text = session.read_update(
        List(node_name -> None), hide_others = false, progress = progress)._1(node_name)
      val search_range = PIDE_MCP_Util.check_text_range(Line.Document(current_text),
        start_line, end_line)
      val range_text = search_range.substring(current_text)
      val actual_old_text = if (old_text.isEmpty) range_text else old_text
      val offsets = if (old_text.isEmpty) List(search_range.start)
        else {
          val occurrences = new Regex(Regex.quote(actual_old_text))
            .findAllMatchIn(range_text).map(_.start + search_range.start).toList
          if (occurrences.isEmpty)
            error("old_text not found in given range. The file may have changed.")
          if (!edit_all && occurrences.length > 1)
            error(s"Found ${occurrences.length} occurrences of old_text in given range. " +
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
      val edited_lines = replaced_ranges.zipWithIndex.map { case (range, i) =>
        val start = range.start + i * (new_text.length - range.length)
        val line_range = edited_doc.range(Text.Range(start, start + new_text.length))
        (line_range.start.line1, line_range.stop.line1)
      }
      progress.expose_interrupt()
      if (changed) session.write_file_content(node_name.path, computed_text)
      val (_, change) = session.read_update(
        List(node_name -> Some(edited_lines.map(_ -> Some(_)))),
        hide_others = true,
        // avoid disk <--> PIDE disagreement
        progress = new Uncancellable_Progress(progress))
      session.await_stable(change, progress)
      session.resolve_dependencies(List(node_name), progress)
      if (changed) edited_lines else Nil
    }
  }
}
