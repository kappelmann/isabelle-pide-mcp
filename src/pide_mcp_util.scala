/*  Title:      PIDE_MCP/pide_mcp_util.scala
    Author:     Kevin Kappelmann

General utilities.
*/

package isabelle.pide.mcp

import isabelle._

object PIDE_MCP_Util {
  def capture_failures[A](args: IterableOnce[A])(run: A => Unit): Iterator[(A, Throwable)] =
    args.iterator.flatMap(arg =>
      Exn.capture(run(arg)) match {
        case Exn.Res(_) => None
        case Exn.Exn(exn) => Some((arg, exn))
      })

  // like Exn.release_first, but reports every error instead of only the first one
  def check_failures(exns: List[Throwable]): Unit =
    exns.filterNot(Exn.is_interrupt) match {
      case Nil => for (exn <- exns.headOption) throw exn
      case List(exn) => throw exn
      case failures => throw ERROR(cat_lines(failures.map(Exn.message)))
    }

  def text_range(doc: Line.Document, start_line: Int, end_line: Int): Option[Text.Range] =
    if (start_line - 1 > end_line) None
    else doc.text_range(Line.Range(Line.Position(start_line - 1), Line.Position(end_line)))

  def text_perspective(
    doc: Line.Document,
    line_ranges: List[(Int, Int)],
    context: Int
  ): Text.Perspective =
    Text.Perspective(line_ranges.map { case (start_line, end_line) =>
      text_range(doc, (start_line - context) max 1, (end_line + context) min doc.lines.length)
        .getOrElse(error(
          s"Bad line range $start_line..$end_line (file has ${doc.lines.length} lines)")) })

  def numbered_line(line: Int, text: String): String =
    s"${line}: ${text}"

  def numbered_lines(lines: IterableOnce[String], start_line: Int): String =
    lines.iterator.zipWithIndex.map { case (l, i) =>
      numbered_line(start_line + i, l) }.mkString("\n")

  def numbered_lines(text: String, start_line: Int): String =
    numbered_lines(Line.logical_lines(text), start_line)

  def numbered_lines_range(lines: List[String], start_line: Int, end_line: Int): String =
    numbered_lines(lines.slice(start_line - 1, end_line min lines.length), start_line)

  def numbered_lines_range(text: String, start_line: Int, end_line: Int): String =
    numbered_lines_range(Line.logical_lines(text), start_line, end_line)

  def print_process_result(process_result: Process_Result): String =
    process_result.print_rc + if_proper(process_result.err, s": ${process_result.err}")

  def path(s: String): Path = Path.explode(File.standard_path(s))

  def display_name(entry: Option[Name_Space.Entry], range: Text.Range, source: String): String =
    entry.map(_.name).filter(_.nonEmpty).getOrElse(range.substring(source))

  // a cleaned node remains in version.nodes but with empty source
  def is_loaded_dynamic(nodes: Document.Nodes, node_name: Document.Node.Name): Boolean = {
    val node = nodes(node_name)
    node.get_blob match {
      case Some(blob) => blob.source.nonEmpty
      case None => !node.is_empty
    }
  }

  def intersect_range(full: Text.Range, range: Option[Text.Range]): Text.Range =
    range.fold(full)(r => full.try_restrict(r).getOrElse(Text.Range.zero))

  def restrict_text_range(text: String, range: Option[Text.Range]): Text.Range =
    intersect_range(Text.Range.length(text), range)

  def elem_body_plain_text(elem: XML.Elem): String =
    Pretty.string_of(elem.body, pure = true)
}
