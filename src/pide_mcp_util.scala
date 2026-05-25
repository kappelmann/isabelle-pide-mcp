/*  Title:      PIDE_MCP/pide_mcp_util.scala
    Author:     Kevin Kappelmann

Utility functions for the PIDE MCP server.
*/

package isabelle.pide.mcp

import isabelle._
import scala.language.unsafeNulls

object PIDE_MCP_Util {
  val theory_suffix: String = ".thy"
  val ml_suffix: String = ".ML"

  def strip_theory_suffix(path_str: String): String =
    path_str.stripSuffix(theory_suffix)

  def require_thy(path_str: String): Exn.Result[Unit] = Exn.capture {
    if (!path_str.endsWith(theory_suffix))
      error(s"Not a theory file: $path_str (must end with $theory_suffix)")
  }

  def require_thy_or_ml(path_str: String): Exn.Result[Unit] = Exn.capture {
    if (!path_str.endsWith(theory_suffix) && !path_str.endsWith(ml_suffix))
      error(s"Unsupported file type: $path_str (only $theory_suffix and $ml_suffix are supported)")
  }

  def require_valid_lines(
    start_line: Option[Int],
    end_line: Option[Int],
    total_lines: Int
  ): Exn.Result[Unit] = Exn.capture {
    start_line match {
      case Some(n) if n < 1 || n > total_lines =>
        error(s"start_line $n out of bounds (file has $total_lines lines)")
      case _ =>
    }
    end_line match {
      case Some(n) if n < 1 || n > total_lines =>
        error(s"end_line $n out of bounds (file has $total_lines lines)")
      case _ =>
    }
    (start_line, end_line) match {
      case (Some(s), Some(e)) if e < s => error(s"end_line $e < start_line $s")
      case _ =>
    }
  }

  def resolve_lines(start_line: Option[Int], end_line: Option[Int], total_lines: Int): Exn.Result[(Int, Int)] =
    Exn.capture {
      Exn.release(require_valid_lines(start_line, end_line, total_lines))
      (start_line.getOrElse(1), end_line.getOrElse(total_lines))
    }

  def node_defined(snap: Document.Snapshot): Boolean =
    snap.version.nodes.domain.contains(snap.node_name)

  def numbered_lines(text: String, start: Int, end: Int): List[JSON.Object.T] = {
    val lines = Line.Document(text).lines
    val start_idx = start - 1
    val end_idx = end.min(lines.length)
    lines.slice(start_idx, end_idx).zipWithIndex.map { case (l, i) =>
      JSON.Object("line_number" -> (start_idx + i + 1), "text" -> l.text)
    }.toList
  }

  def commands_in_range(
    snap: Document.Snapshot,
    doc: Line.Document,
    start_line: Int,
    end_line: Int
  ): List[(Command, Text.Offset)] = {
    val start_offset = doc.offset(Line.Position(line = (start_line - 1).max(0))).getOrElse(0)
    val end_offset = doc.offset(Line.Position(line = end_line)).getOrElse(Int.MaxValue)
    snap.node.command_iterator(Text.Range(start_offset, end_offset))
      .filter { case (cmd, _) => cmd.source.trim.nonEmpty }
      .toList
  }

  def xml_to_json(tree: XML.Tree): JSON.Object.T = tree match {
    case XML.Elem(Markup(name, props), body) =>
      val props_obj = JSON.Object(props: _*)
      val base = JSON.Object("name" -> name, "body" -> body.map(xml_to_json))
      if (props_obj.isEmpty) base else base + ("props" -> props_obj)
    case XML.Text(text) => JSON.Object("text" -> text)
  }
}
