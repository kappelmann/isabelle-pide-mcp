/*  Title:      MCP/src/pide_mcp_markup.scala
    Author:     Kevin Kappelmann

Markup utilities.
*/

package isabelle.pide.mcp

import isabelle._

object PIDE_MCP_Markup {
  val ML_kind = "ML"

  // like select, but returns all covering markups (full markup stack) at each sub-range
  private def select_covering(
    snapshot: Document.Snapshot,
    range: Text.Range,
    elements: Markup.Elements
  ): List[Text.Info[List[Text.Info[XML.Elem]]]] =
    snapshot.cumulate(range, List.empty[Text.Info[XML.Elem]], elements,
      _ => { case (acc, info) => Some(info :: acc) })

  def bad(snapshot: Document.Snapshot, range: Text.Range): List[Text.Info[String]] =
    snapshot.select(range, Markup.Elements(Markup.BAD), _ => {
      case Text.Info(r, elem) => Some(Text.Info(r, PIDE_MCP_Util.elem_body_plain_text(elem)))
    }).map(_.info)

  def facts_json(snapshot: Document.Snapshot, range: Text.Range): List[String] = {
    val fact_kinds = Set(Markup.FACT, Markup.DYNAMIC_FACT, Markup.LITERAL_FACT)
    snapshot.select(range, Markup.Elements(Markup.ENTITY), _ => {
      case Text.Info(_, XML.Elem(Markup.Entity(entry), _)) if fact_kinds.contains(entry.kind) =>
        Some(entry.name)
      case _ => None
    }).map(_.info)
  }

  // this is hairy, but I think there is no good library function to obtain type information
  def types_json(snapshot: Document.Snapshot, range: Text.Range): List[JSON.Object.T] = {
    val term_kinds = Set(Markup.FREE, Markup.BOUND, Markup.VAR, Markup.SKOLEM, Markup.CONST)
    val elements = Markup.Elements(
      term_kinds.toSeq :+ Markup.TYPING :+ Markup.ENTITY :+ Markup.ML_TYPING: _*)

    def kind_and_type(at_range: List[Text.Info[XML.Elem]]): Option[(String, String)] =
      at_range.collectFirst { case Text.Info(_, e) if e.name == Markup.ML_TYPING =>
        (ML_kind, XML.content(e.body))
      }.orElse(for {
        kind_elem <- at_range.collectFirst {
          case Text.Info(_, e) if term_kinds.contains(e.name) => e }
        typing_elem <- at_range.collectFirst {
          case Text.Info(_, e) if e.name == Markup.TYPING => e }
      } yield (kind_elem.name, XML.content(typing_elem.body)))

    select_covering(snapshot, range, elements).flatMap {
      case Text.Info(r, infos @ (info :: _)) if info.range == r => // only keep leaf nodes
        val at_range = infos.filter(_.range == r)
        val name_at = PIDE_MCP_Util.display_name(
          at_range.collectFirst { case Text.Info(_, XML.Elem(Markup.Entity(entry), _)) => entry },
          r, snapshot.node.source)
        kind_and_type(at_range).map { case (kind, t) =>
          JSON_Object("name" -> name_at, "kind" -> kind, "type" -> t)
        }
      case _ => None
    }.distinct
  }

  def markup_json(
    snapshot: Document.Snapshot,
    range: Text.Range,
    elements: Markup.Elements
  ): List[JSON.Object.T] =
    select_covering(snapshot, range, elements).flatMap {
      case Text.Info(r, infos) =>
        infos.filter(_.range == r).map(i => PIDE_MCP_Util.xml_json(i.info))
    }
}
