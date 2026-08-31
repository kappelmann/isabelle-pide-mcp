/*  Title:      PIDE_MCP/pide_mcp_definition.scala
    Author:     Kevin Kappelmann
*/

package isabelle.pide.mcp

import isabelle._

import scala.collection.mutable
import scala.collection.immutable.VectorMap

object PIDE_MCP_Definition {
  def unresolved(
    name_space_entry: Name_Space.Entry,
    name: String,
    origin: String,
    line: Int,
    exn: Throwable
  ): PIDE_MCP_Definition = PIDE_MCP_Definition(name, name_space_entry.kind,
    name_space_entry.def_label, origin = Some(origin), line = Some(line),
    note = Some(s"The definition entry's source file ${quote(origin)} " +
      s"could not be resolved: ${Exn.message(exn)}"))

  class Context(val session: PIDE_MCP_Session, val snapshot: Document.Snapshot) {
    private val cache = mutable.Map.empty[Document.Node.Name, List[String]]
    def lines(node_name: Document.Node.Name): List[String] =
      cache.getOrElseUpdate(node_name,
        Line.logical_lines(session.node_source(snapshot, node_name)))
  }

  def resolved(
    context: Context,
    name_space_entry: Name_Space.Entry,
    name: String,
    node_name: Document.Node.Name,
    node_origin: String,
    line: Int,
    snippet_lines: Int
  ): PIDE_MCP_Definition = PIDE_MCP_Definition(name, name_space_entry.kind,
    name_space_entry.def_label, origin = Some(node_origin), line = Some(line),
    source_snippet = Option.when(snippet_lines > 0)(
      PIDE_MCP_Util.numbered_lines_range(context.lines(node_name), line, line + snippet_lines - 1)))

  def apply(
    context: Context,
    name_space_entry: Name_Space.Entry,
    name: String,
    snippet_lines: Int,
    filter_origins: Set[String],
    def_entry_not_loaded: String
  ): Option[PIDE_MCP_Definition] = {
    val session = context.session
    def make(origin: String, line: Int): Option[PIDE_MCP_Definition] =
      Exn.result {
        val node_name = session.node_name(origin)
        val node_origin = session.origin(node_name)
        Option.when(filter_origins.isEmpty || filter_origins.contains(node_origin))(
          resolved(context, name_space_entry, name, node_name, node_origin, line, snippet_lines))
      } match {
        case Exn.Res(definition) => definition
        case Exn.Exn(exn) => Some(unresolved(name_space_entry, name, origin, line, exn))
      }
    name_space_entry.properties match {
      case Position.Item_Def_File(def_file, def_line, _) => make(def_file, def_line)
      case Position.Item_Def_Id(def_id, def_range) =>
        context.snapshot.find_command_position(def_id, def_range.start) match {
          case Some(pos) => make(pos.name, pos.line1)
          case None => Some(PIDE_MCP_Definition(name, name_space_entry.kind,
            name_space_entry.def_label, note = Some(def_entry_not_loaded)))
        }
      case _ => None
    }
  }

  def definitions(
    session: PIDE_MCP_Session,
    snapshot: Document.Snapshot,
    range: Option[Text.Range],
    snippet_lines: Int,
    kinds: List[String],
    filter_origins: Set[String],
    def_entry_not_loaded: String
  ): List[PIDE_MCP_Definition] = {
    val restricted = PIDE_MCP_Util.restrict_text_range(snapshot.node.source, range)
    snapshot.select(restricted, Markup.Elements(Markup.ENTITY), _ => {
      case Text.Info(r, XML.Elem(Markup.Entity(name_space_entry), _))
      if kinds.contains(name_space_entry.kind) =>
        val name = PIDE_MCP_Util.display_name(Some(name_space_entry), r, snapshot.node.source)
        val context = new Context(session, snapshot)
        PIDE_MCP_Definition(context, name_space_entry, name, snippet_lines,
          filter_origins, def_entry_not_loaded)
      case _ => None
    }).map(_.info)
  }

  def definitions_json(
    definitions: List[PIDE_MCP_Definition]
  ): Map[String, List[JSON.Object.T]] = {
    val grouped = definitions.foldLeft(VectorMap.empty[String, List[PIDE_MCP_Definition]]) {
      case (groups, definition) =>
        val origin = definition.origin.getOrElse("")
        groups.updated(origin, definition :: groups.getOrElse(origin, Nil))
    }
    grouped.map { case (origin, entries) =>
      origin -> entries.sortBy(entry => (entry.line, entry.name, entry.kind))
        .distinctBy(entry => (entry.line, entry.name)).map(_.json)
    }
  }
}

sealed case class PIDE_MCP_Definition(
  name: String,
  kind: String,
  def_label: String,
  origin: Option[String] = None,
  line: Option[Int] = None,
  source_snippet: Option[String] = None,
  note: Option[String] = None
) {
  def json: JSON.Object.T =
    JSON_Object.flatten(
      Some("name" -> name),
      Some("kind" -> kind),
      Option.when(def_label.nonEmpty)("def_label" -> def_label),
      origin.map("origin" -> _),
      line.map("line" -> _),
      source_snippet.map("source_snippet" -> _),
      note.map("note" -> _))
}
