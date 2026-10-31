/*  Title:      MCP/src/pide_mcp_util.scala
    Author:     Kevin Kappelmann

General utilities.
*/

package isabelle.pide.mcp

import isabelle._

object PIDE_MCP_Util {
  def capture_failures[A](args: IterableOnce[A], run: A => Unit): Iterator[(A, Throwable)] =
    args.iterator.flatMap(arg =>
      Exn.capture(run(arg)) match {
        case Exn.Res(_) => None
        case Exn.Exn(exn) => Some((arg, exn))
      })

  // like Exn.release_first, but reports every error instead of only the first one
  def failure(exn: Throwable, more: List[Throwable]): Throwable =
    (exn :: more).filterNot(Exn.is_interrupt) match {
      case Nil => exn
      case List(exn1) => exn1
      case failures => ERROR(cat_lines(failures.map(Exn.message)))
    }

  def check_failures(exns: List[Throwable]): Unit =
    for (exn <- exns.headOption) throw failure(exn, exns.tail)

  def text_range(doc: Line.Document, start_line: Int, end_line: Int): Option[Text.Range] =
    if (start_line - 1 > end_line) None
    else doc.text_range(Line.Range(Line.Position(start_line - 1), Line.Position(end_line)))

  def check_ordered_lines(start_line: Int, end_line: Option[Int]): Unit =
    for (stop <- end_line if stop < start_line) error(s"end_line $stop < start_line $start_line")

  def check_lines(start_line: Int, end_line: Option[Int], total_lines: Int): (Int, Int) = {
    check_ordered_lines(start_line, end_line)
    if (start_line < 1 || start_line > (total_lines max 1))
      error(s"start_line $start_line out of bounds (file has $total_lines lines)")
    for (stop <- end_line if stop < 1 || stop > total_lines)
      error(s"end_line $stop out of bounds (file has $total_lines lines)")
    (start_line, end_line.getOrElse(total_lines))
  }

  def check_text_range(doc: Line.Document, start_line: Int, end_line: Option[Int]): Text.Range = {
    val (first_line, last_line) = check_lines(start_line, end_line, doc.lines.length)
    text_range(doc, first_line, last_line).get
  }

  def check_text_perspective(
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

  def line_range(start_line: Int, end_line: Int): String =
    if (start_line == end_line) start_line.toString else s"$start_line-$end_line"

  def numbered_lines(lines: IterableOnce[String], start_line: Int): String =
    lines.iterator.zipWithIndex.map { case (l, i) =>
      numbered_line(start_line + i, l) }.mkString("\n")

  def numbered_lines(text: String, start_line: Int): String =
    numbered_lines(Line.logical_lines(text), start_line)

  def numbered_lines_range(lines: List[String], start_line: Int, end_line: Int): String =
    numbered_lines(lines.slice(start_line - 1, end_line min lines.length), start_line)

  def numbered_lines_range(text: String, start_line: Int, end_line: Int): String =
    numbered_lines_range(Line.logical_lines(text), start_line, end_line)

  def snippet(doc: Line.Document, range: Text.Range, snippet_lines: Int): String = {
    val start = doc.position(range.start).line1
    val (shown, rest) = Line.logical_lines(range.substring(doc.text).stripLineEnd)
      .splitAt(snippet_lines)
    val omitted = Option.when(rest.nonEmpty)(
      line_range(start + shown.length, start + shown.length + rest.length - 1) + ": ...")
    cat_lines(proper_string(numbered_lines(shown, start)).toList ::: omitted.toList)
  }

  def print_process_result(process_result: Process_Result): String =
    process_result.print_rc + if_proper(process_result.err, s": ${process_result.err}")

  def path(s: String): Path = Path.explode(File.standard_path(s))

  def display_name(entry: Option[Name_Space.Entry], range: Text.Range, source: String): String =
    entry.map(_.name).filter(_.nonEmpty).getOrElse(range.substring(source))

  // a cleaned node remains in version.nodes but with empty source
  def is_loaded_dynamic(node: Document.Node): Boolean =
    node.get_blob match {
      case Some(blob) => blob.source.nonEmpty
      case None => !node.is_empty
    }

  def intersect_range(full: Text.Range, range: Option[Text.Range]): Text.Range =
    range.fold(full)(r => full.try_restrict(r).getOrElse(Text.Range.zero))

  def restrict_text_range(text: String, range: Option[Text.Range]): Text.Range =
    intersect_range(Text.Range.length(text), range)

  def restrict_ranges(ranges: Iterable[Text.Range], range: Text.Range): List[Text.Range] =
    ranges.iterator.filter(_.overlaps(range)).map(_.restrict(range)).toList

  def elem_body_plain_text(elem: XML.Elem): String =
    Pretty.string_of(elem.body, pure = true)

  def xml_json(tree: XML.Tree): JSON.Object.T =
    tree match {
      case XML.Elem(Markup(name, props), body) =>
        val props_obj = JSON_Object(props)
        val base = JSON_Object("name" -> name, "body" -> body.map(xml_json))
        if (props_obj.isEmpty) base else base + ("props" -> props_obj)
      case XML.Text(text) => JSON_Object("text" -> text)
    }
}
