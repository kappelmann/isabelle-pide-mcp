/*  Title:      PIDE_MCP/pide_mcp_util.scala
    Author:     Kevin Kappelmann

General utilities.
*/

package isabelle.pide.mcp

import isabelle._

object PIDE_MCP_Util {
  val theory_suffix: String = ".thy"

  def strip_theory_suffix(path_str: String): String =
    path_str.stripSuffix(theory_suffix)

  def range(doc: Line.Document, start_line: Int, end_line: Int): Text.Range =
    Text.Range(
      doc.offset(Line.Position(line = start_line - 1)).getOrElse(0),
      doc.offset(Line.Position(line = end_line)).getOrElse(Int.MaxValue))

  def numbered_line(line: Int, text: String): String =
    s"${line}: ${text}"

  def numbered_lines(lines: List[String], start: Int): String =
    lines.zipWithIndex.map { case (l, i) => numbered_line(start + i, l) }.mkString("\n")

  def numbered_lines(text: String, start: Int): String =
    numbered_lines(Line.Document(text).lines.map(_.text), start)

  def numbered_lines_range(lines: List[String], start: Int, end: Int): String =
    numbered_lines(lines.slice(start - 1, end.min(lines.length)), start)

  def numbered_lines_range(text: String, start: Int, end: Int): String =
    numbered_lines_range(Line.Document(text).lines.map(_.text), start, end)

  def canonical_path(path: Path): Path = path.expand.canonical

  def display_name(entry: Option[Name_Space.Entry], range: Text.Range, source: String): String =
    entry.map(_.name).filter(_.nonEmpty).getOrElse(range.substring(source))

  // a cleaned theory remains in version.nodes but with empty source
  def is_loaded_theory(snapshot: Document.Snapshot, node_name: Document.Node.Name): Boolean =
    snapshot.version.nodes(node_name).source.nonEmpty

  def find_loading_command(snapshot: Document.Snapshot, file_name: Document.Node.Name): Option[Command] =
    snapshot.version.nodes.iterator.flatMap { case (_, node) =>
      node.load_commands.find(_.blobs_names.contains(file_name))
    }.nextOption()

  def is_file_loaded(snapshot: Document.Snapshot, file_name: Document.Node.Name): Boolean =
    find_loading_command(snapshot, file_name).isDefined

  def restrict_source_range(snapshot: Document.Snapshot, range: Option[Text.Range]): Text.Range = {
    val full = Text.Range.length(snapshot.node.source)
    range.fold(full)(r => full.try_restrict(r).getOrElse(Text.Range.zero))
  }

  def intersect_range(full: Text.Range, range: Option[Text.Range]): Text.Range =
    range.flatMap(full.try_restrict).getOrElse(full)

  def result_in_range(elem: XML.Tree, offset: Text.Offset, range: Text.Range): Boolean = {
    val props = elem match {
      case e: XML.Elem => e.markup.properties
      case _ => Nil
    }
    Position.Range.unapply(props) match {
      case Some(r) => (r + offset).overlaps(range)
      case None =>
        Position.Offset.unapply(props).forall(s => range.contains(s + offset))
    }
  }

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
