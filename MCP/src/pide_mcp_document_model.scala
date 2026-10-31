/*  Title:      MCP/src/pide_mcp_document_model.scala
    Author:     Kevin Kappelmann
*/

package isabelle.pide.mcp

import isabelle._

object PIDE_MCP_Document_Model {
  def node_perspective(node: Document.Node): Document.Node.Perspective_Text.T =
    Document.Node.Perspective(
      node.perspective.required, node.text_perspective, node.perspective.overlays)

  object Replacement {
    def apply(old_text: String, new_text: String): Replacement = {
      val prefix = old_text.iterator.zip(new_text.iterator).takeWhile(_ == _).length
      val (old_rest, new_rest) = (old_text.drop(prefix), new_text.drop(prefix))
      val suffix = old_rest.reverseIterator.zip(new_rest.reverseIterator).takeWhile(_ == _).length
      Replacement(prefix, old_rest.dropRight(suffix), new_rest.dropRight(suffix))
    }
  }

  sealed case class Replacement(start: Text.Offset, old_text: String, new_text: String) {
    def edits: List[Text.Edit] = Text.Edit.replace(start, old_text, new_text)

    def restrict(perspective: Text.Perspective): Text.Perspective = {
      val before = Text.Range(0, start)
      val after = Text.Range(start + old_text.length, Text.Range.full.stop)
      val shift = new_text.length - old_text.length
      Text.Perspective(perspective.ranges.flatMap(range =>
        range.try_restrict(before).toList :::
        range.try_restrict(after).map(_ + shift).toList).filterNot(_.is_singularity))
    }
  }

  def hide_edits(
    nodes: Document.Nodes,
    keep: Set[Document.Node.Name]
  ): Iterator[Document.Edit_Text] =
    nodes.iterator.collect {
      case (name, node) if !keep(name) && !node.text_perspective.is_empty =>
        name -> node_perspective(node).copy(visible = Text.Perspective.empty)
    }

  def unload_edits(
    session: Document.Session,
    node_name: Document.Node.Name,
    node: Document.Node
  ): List[Document.Edit_Text] = {
    val model = PIDE_MCP_Document_Model(session, node_name, node, "",
      Text.Edit.removes(0, node.source), Text.Perspective.empty)
    model.node_edits(
      Resources.Thy.empty, model.pending_edits, Document.Node.Perspective_Text.empty)
  }

  def make(
    session: Document.Session,
    node_name: Document.Node.Name,
    node: Document.Node,
    text: String,
    visible_lines: Option[List[(Int, Option[Int])]],
    range_context: Int
  ): PIDE_MCP_Document_Model = {
    val replacement = Replacement(node.source, text)
    val text_perspective = visible_lines match {
      case None => replacement.restrict(node.text_perspective)
      case Some(visible_lines) =>
        val doc = Line.Document(text)
        val line_ranges = visible_lines.map { case (start_line, opt_end_line) =>
          (start_line, opt_end_line.getOrElse(doc.lines.length)) }
        PIDE_MCP_Util.check_text_perspective(doc, line_ranges, range_context)
    }
    PIDE_MCP_Document_Model(session, node_name, node, text, replacement.edits, text_perspective)
  }
}

sealed case class PIDE_MCP_Document_Model(
  session: Document.Session,
  node_name: Document.Node.Name,
  node: Document.Node,
  text: String,
  pending_edits: List[Text.Edit],
  text_perspective: Text.Perspective
) extends Document.Model {
  def node_required: Boolean = text.nonEmpty
  def untyped_data: AnyRef = File_Format.registry.parse_data(node_name, text)

  def is_stable: Boolean = pending_edits.isEmpty

  def get_text(range: Text.Range): Option[String] = range.try_substring(text)

  def get_blob: Option[Document.Blobs.Item] =
    if (is_theory) None
    else Some(Document.Blobs.Item(
      Bytes(Symbol.encode(text)), text, Symbol.Text_Chunk(text), changed = !is_stable))

  def get_thy(): Resources.Thy = {
    val thy0 = session.resources.special_thy(node_name).getOrElse(
      session.resources.check_thy(node_name, Scan.char_reader(text)))
    thy0.eval_conditions(session.conditions)
  }

  def node_perspective: Document.Node.Perspective_Text.T =
    if (is_theory)
      PIDE_MCP_Document_Model.node_perspective(node).copy(
        required = node_required, visible = text_perspective)
    else Document.Node.Perspective_Text.empty

  def edits: List[Document.Edit_Text] =
    if (is_stable && node.edit_perspective == node_perspective) Nil
    else node_edits(get_thy(), pending_edits, node_perspective)
}
