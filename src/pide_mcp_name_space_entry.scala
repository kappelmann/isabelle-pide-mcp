/*  Title:      PIDE_MCP/pide_mcp_name_space_entry.scala
    Author:     Kevin Kappelmann

Name space entry utilities for the PIDE MCP server.
*/

package isabelle.pide.mcp

import isabelle._


object PIDE_MCP_Name_Space_Entry {

  private def mk_definition_json(
    name: String, kind: String, origin: Option[String] = None,
    line: Option[Int] = None, source: Option[String] = None,
    snippet_lines: Int = 0, note: Option[String] = None
  ): JSON.Object.T = {
    val entries: List[(String, JSON.T)] = List(
      Some("name" -> name),
      Some("kind" -> kind),
      origin.map("origin" -> _),
      line.map("line" -> _),
      (source, line) match {
        case (Some(text), Some(l)) if snippet_lines > 0 =>
          Some("source_snippet" -> PIDE_MCP_Util.numbered_lines_range(text, l, l + snippet_lines - 1))
        case _ => None
      },
      note.map("note" -> _)).flatten
    JSON.Object(entries: _*)
  }

  private def source_definition_json(
    session: PIDE_MCP_Session,
    entry: Name_Space.Entry,
    name: String,
    node_name: Document.Node.Name,
    line: Int,
    snippet_lines: Int,
    filter_origins: Set[String]
  ): Exn.Result[Option[JSON.Object.T]] = Exn.capture {
    val origin = session.origin(node_name)
    if (filter_origins.nonEmpty && !filter_origins.contains(origin)) None
    else {
      val source = if (snippet_lines > 0) Some(Exn.release(session.read(node_name))) else None
      Some(mk_definition_json(name, entry.kind, origin = Some(origin),
        line = Some(line), source = source, snippet_lines = snippet_lines))
    }
  }

  def definition_json(
    session: PIDE_MCP_Session,
    snapshot: Document.Snapshot,
    entry: Name_Space.Entry,
    name: String,
    snippet_lines: Int,
    filter_origins: Set[String],
    def_entry_not_loaded: String
  ): Exn.Result[Option[JSON.Object.T]] = Exn.capture {
    def resolve_entry(origin_str: String, line: Int): Option[JSON.Object.T] = {
      session.node_name(origin_str) match {
        case Exn.Res(node_name) =>
          Exn.release(source_definition_json(session, entry, name, node_name, line, snippet_lines, filter_origins))
        case Exn.Exn(ex) =>
          Some(mk_definition_json(name, entry.kind, origin = Some(origin_str),
            line = Some(line), note = Some("The definition entry's source file " + origin_str
              + " could not be resolved: " + Exn.message(ex))))
      }
    }
    entry.properties match {
      case Position.Item_Def_File(def_file, def_line, _) => resolve_entry(def_file, def_line)
      case Position.Item_Def_Id(def_id, def_range) =>
        snapshot.find_command_position(def_id, def_range.start) match {
          case Some(pos) => resolve_entry(pos.name, pos.line1)
          case None => Some(mk_definition_json(name, entry.kind, note = Some(def_entry_not_loaded)))
        }
      case _ => None
    }
  }
}
