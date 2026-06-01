/*  Title:      PIDE_MCP/pide_mcp_util.scala
    Author:     Kevin Kappelmann

General utils for the PIDE MCP server.
*/

package isabelle.pide.mcp

import isabelle._

object PIDE_MCP_Util {
  val theory_suffix: String = ".thy"

  def strip_theory_suffix(path_str: String): String =
    path_str.stripSuffix(theory_suffix)

  private def require_valid_lines(
    start_line: Option[Int],
    end_line: Option[Int],
    total_lines: Int
  ): Exn.Result[Unit] = Exn.capture {
    for (s <- start_line if s < 1 || s > total_lines)
      error(s"start_line $s out of bounds (file has $total_lines lines)")
    for (e <- end_line if e < 1 || e > total_lines)
      error(s"end_line $e out of bounds (file has $total_lines lines)")
    for (s <- start_line; e <- end_line if e < s)
      error(s"end_line $e < start_line $s")
  }

  def resolve_lines(start_line: Option[Int], end_line: Option[Int], total_lines: Int): Exn.Result[(Int, Int)] =
    Exn.capture {
      Exn.release(require_valid_lines(start_line, end_line, total_lines))
      (start_line.getOrElse(1), end_line.getOrElse(total_lines))
    }

  def range(doc: Line.Document, start_line: Int, end_line: Int): Text.Range =
    Text.Range(
      doc.offset(Line.Position(line = start_line - 1)).getOrElse(0),
      doc.offset(Line.Position(line = end_line)).getOrElse(Int.MaxValue))

  def numbered_line(line: Int, text: String): String =
    s"${line}: ${text}"

  def numbered_lines(text: String, start: Int): String = {
    val lines = Line.Document(text).lines
    lines.zipWithIndex.map { case (l, i) =>
      numbered_line(start + i, l.text)
    }.mkString("\n")
  }

  def numbered_lines_range(text: String, start: Int, end: Int): String = {
    val lines = Line.Document(text).lines
    val start_idx = start - 1
    val end_idx = end.min(lines.length)
    numbered_lines(lines.slice(start_idx, end_idx).map(_.text).mkString("\n"), start)
  }

  def display_name(entry: Option[Name_Space.Entry], range: Text.Range, source: String): String =
    entry.map(_.name).filter(_.nonEmpty).getOrElse(range.substring(source))

  def node_defined(snapshot: Document.Snapshot, node_name: Document.Node.Name): Boolean =
    snapshot.version.nodes.domain.contains(node_name)

  def node_defined(snapshot: Document.Snapshot): Boolean =
    node_defined(snapshot, snapshot.node_name)

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

  def canonical_path(path: Path): Path = path.expand.canonical
}
