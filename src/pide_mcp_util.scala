/*  Title:      PIDE_MCP/pide_mcp_util.scala
    Author:     Kevin Kappelmann

General utilities.
*/

package isabelle.pide.mcp

import isabelle._

object PIDE_MCP_Util {
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

  def numbered_lines(lines: List[String], start_line: Int): String =
    lines.zipWithIndex.map { case (l, i) => numbered_line(start_line + i, l) }.mkString("\n")

  def numbered_lines(text: String, start_line: Int): String =
    numbered_lines(Line.logical_lines(text), start_line)

  def numbered_lines_range(lines: List[String], start_line: Int, end_line: Int): String =
    numbered_lines(lines.slice(start_line - 1, end_line min lines.length), start_line)

  def numbered_lines_range(text: String, start_line: Int, end_line: Int): String =
    numbered_lines_range(Line.logical_lines(text), start_line, end_line)

  def canonical_path(path: Path): Path = path.expand.canonical

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

  def xml_to_json(tree: XML.Tree): JSON.Object.T = tree match {
    case XML.Elem(Markup(name, props), body) =>
      val props_obj = JSON.Object(props: _*)
      val base = JSON.Object("name" -> name, "body" -> body.map(xml_to_json))
      if (props_obj.isEmpty) base else base + ("props" -> props_obj)
    case XML.Text(text) => JSON.Object("text" -> text)
  }

  def elem_body_plain_text(elem: XML.Elem): String =
    Pretty.string_of(elem.body, pure = true)
}
