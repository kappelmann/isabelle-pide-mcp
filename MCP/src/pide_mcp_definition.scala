/*  Title:      MCP/src/pide_mcp_definition.scala
    Author:     Kevin Kappelmann
*/

package isabelle.pide.mcp

import isabelle._

import scala.collection.mutable
import scala.collection.immutable.VectorMap

object PIDE_MCP_Definition {
  def def_label(name_space_entry: Name_Space.Entry): String =
    if (Long_Name.qualifier(name_space_entry.def_label) == Markup.COMMAND) ""
    else name_space_entry.def_label

  def unresolved(
    name_space_entry: Name_Space.Entry,
    name: String,
    origin: String,
    line: Int,
    msg: String
  ): PIDE_MCP_Definition = PIDE_MCP_Definition(name, name_space_entry.kind,
    def_label(name_space_entry), origin = Some(origin), line = Some(line),
    note = Some(s"The definition entry's source file ${quote(origin)} " +
      s"could not be resolved: $msg"))

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
    def_label(name_space_entry), origin = Some(node_origin), line = Some(line),
    snippet = Option.when(snippet_lines > 0)(
      PIDE_MCP_Util.numbered_lines_range(context.lines(node_name), line, line + snippet_lines - 1)))

  def resolve(
    context: Context,
    name_space_entry: Name_Space.Entry,
    name: String,
    snippet_lines: Int,
    filter_origins: Option[Set[String]],
    def_entry_not_loaded: String
  ): Option[PIDE_MCP_Definition] = {
    val session = context.session
    def resolve_at(origin: String, line: Int): Option[PIDE_MCP_Definition] =
      try {
        val node_name = session.node_name(origin)
        val node_origin = session.origin(node_name)
        Option.when(filter_origins.forall(_.contains(node_origin)))(
          resolved(context, name_space_entry, name, node_name, node_origin, line, snippet_lines))
      }
      catch { case ERROR(msg) => Some(unresolved(name_space_entry, name, origin, line, msg)) }
    name_space_entry.properties match {
      case Position.Item_Def_File(def_file, def_line, _) => resolve_at(def_file, def_line)
      case Position.Item_Def_Id(def_id, def_range) =>
        context.snapshot.find_command_position(def_id, def_range.start) match {
          case Some(pos) => resolve_at(pos.name, pos.line1)
          case None => Some(PIDE_MCP_Definition(name, name_space_entry.kind,
            def_label(name_space_entry), note = Some(def_entry_not_loaded)))
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
    filter_origins: Option[Set[String]],
    def_entry_not_loaded: String
  ): List[PIDE_MCP_Definition] = {
    val restricted = PIDE_MCP_Util.restrict_text_range(snapshot.node.source, range)
    val context = new Context(session, snapshot)
    snapshot.cumulate(restricted, List.empty[PIDE_MCP_Definition],
      Markup.Elements(Markup.ENTITY), _ => {
      case (definitions, Text.Info(r, XML.Elem(Markup.Entity(name_space_entry), _)))
      if kinds.contains(name_space_entry.kind) =>
        val name = PIDE_MCP_Util.display_name(Some(name_space_entry), r, snapshot.node.source)
        resolve(context, name_space_entry, name, snippet_lines,
          filter_origins, def_entry_not_loaded).map(_ :: definitions)
      case _ => None
    }).flatMap(_.info)
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
      val sites = entries.sortBy(entry => (entry.line, entry.name, entry.kind))
        .foldRight(List.empty[List[PIDE_MCP_Definition]]) {
          case (entry, (site @ next :: rest) :: sites) if next.line == entry.line =>
            (entry :: (if (next.name == entry.name) rest else site)) :: sites
          case (entry, sites) => List(entry) :: sites
        }
      origin -> sites.map(site =>
        JSON_Object.flatten(
          site.head.line.map("line" -> _),
          site.head.snippet.map("snippet" -> _)) +
        ("entities" -> site.map(_.json -- List("origin", "line", "snippet"))))
    }
  }
}

sealed case class PIDE_MCP_Definition(
  name: String,
  kind: String,
  def_label: String,
  origin: Option[String] = None,
  line: Option[Int] = None,
  snippet: Option[String] = None,
  note: Option[String] = None
) {
  def json: JSON.Object.T =
    JSON_Object.flatten(
      Some("name" -> name),
      Some("kind" -> kind),
      Option.when(def_label.nonEmpty)("def_label" -> def_label),
      origin.map("origin" -> _),
      line.map("line" -> _),
      snippet.map("snippet" -> _),
      note.map("note" -> _))
}
