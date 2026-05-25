/*  Title:      PIDE_MCP/mcp_tools.scala
    Author:     Kevin Kappelmann

Implementation of all PIDE MCP tool handlers.
*/

package isabelle.pide.mcp

import isabelle._
import scala.language.unsafeNulls

class PIDE_MCP_Tool_Handlers(val session: PIDE_MCP_Session) {
  private val retry_soon_message: String = "Please retry soon."

  private def path_param(params: JSON.Object.T): Exn.Result[String] = Exn.capture {
    JSON.string(params, "path") getOrElse error("Missing path parameter")
  }

  private def load_snapshot(path_str: String): Exn.Result[Document.Snapshot] = Exn.capture {
    Exn.release(PIDE_MCP_Util.require_thy(path_str))
    val path = Path.explode(path_str).expand
    val name = session.node_name(path)
    Exn.release(session.load_theory(name, reload = false))
    session.snapshot(name)
  }

  private def require_snapshot_node_defined(path_str: String, snap: Document.Snapshot): Unit =
    if (!PIDE_MCP_Util.node_defined(snap))
      error("The file " + path_str + " was not previously loaded and has now been queued for loading. "
        + "The requested information thus was not ready yet. "
        + retry_soon_message)

  private def command_markup(
    snap: Document.Snapshot,
    cmd: Command,
    offset: Text.Offset,
    elements: Markup.Elements
  ): List[Text.Info[List[XML.Elem]]] = {
    val text_range = cmd.core_range + offset
    snap.cumulate(text_range, List.empty[XML.Elem], elements,
      _ => { case (acc, Text.Info(_, m)) => Some(m :: acc) }
    )
  }

  private def command_markup_json(
    snap: Document.Snapshot,
    cmd: Command,
    offset: Text.Offset,
    elements: Markup.Elements
  ): List[JSON.Object.T] = {
    command_markup(snap, cmd, offset, elements).flatMap { case Text.Info(_, elems) =>
      elems.map(elem => PIDE_MCP_Util.xml_to_json(elem))
    }
  }

  private def command_status_str(snap: Document.Snapshot, cmd: Command): String = {
    val status = session.command_status(snap, cmd)
    if (status.is_failed) "failed"
    else if (status.is_canceled) "canceled"
    else if (status.is_running) "running"
    else if (status.is_finished) "finished"
    else if (status.is_unprocessed) "unprocessed"
    else error("Unexpected command status: " + status)
  }

  private def command_state_json(
    snap: Document.Snapshot,
    cmd: Command,
    offset: Text.Offset
  ): JSON.Object.T = {
    val results = snap.command_results(cmd)
    val markup = command_markup_json(snap, cmd, offset, Markup.Elements.full)
    val source_text = cmd.source.trim
    val source_lines_count = Line.Document(source_text).lines.length
    JSON.Object(
      "status" -> command_status_str(snap, cmd),
      "source" -> PIDE_MCP_Util.numbered_lines(source_text, 1, source_lines_count),
      "results" -> results.iterator.map { case (id, elem) =>
        JSON.Object("id" -> id) ++ PIDE_MCP_Util.xml_to_json(elem)
      }.toList,
      "markup" -> markup)
  }

  def handle(name: String, params: JSON.Object.T): Exn.Result[JSON.T] = {
    name match {
      case PIDE_MCP_Tool_Schemas.create_scratch => handle_create_scratch(params)
      case PIDE_MCP_Tool_Schemas.edit => handle_edit(params)
      case PIDE_MCP_Tool_Schemas.find_origins => handle_find_origins(params)
      case PIDE_MCP_Tool_Schemas.get_state => handle_get_state(params)
      case PIDE_MCP_Tool_Schemas.list_loaded_theories => handle_list_loaded_theories(params)
      case PIDE_MCP_Tool_Schemas.read => handle_read(params)
      case _ => Exn.Exn(ERROR(s"Unknown tool: $name"))
    }
  }

  private def handle_create_scratch(params: JSON.Object.T): Exn.Result[JSON.T] =
    Exn.capture {
      val name_suffix = JSON.string(params, "name_suffix")
      val imports = JSON.strings(params, "imports").getOrElse(PIDE_MCP_Tool_Handlers.default_imports)
      val theory_path = Exn.release(session.create_scratch_theory(name_suffix = name_suffix, imports = imports))
      val theory_name = PIDE_MCP_Util.strip_theory_suffix(theory_path.base.implode)
      Map("theory_name" -> theory_name, "theory_path" -> theory_path.implode)
    }

  private def handle_edit(params: JSON.Object.T): Exn.Result[JSON.T] =
    Exn.capture {
      val path_str = Exn.release(path_param(params))
      val content = JSON.string(params, "content") getOrElse error("Missing content parameter")
      val old_content = JSON.string(params, "old_content") getOrElse error("Missing old_content parameter")
      val start_line = JSON.int(params, "start_line")
      val end_line = JSON.int(params, "end_line")
      val path = Path.explode(path_str).expand
      val (new_text, changed) = Exn.release(session.edit_load_file(path, content, start_line, end_line, old_content))
      val status = if (changed) "written" else "unchanged"
      val description = if (changed) "File content changed"
        else "Edit did not modify the file content (old_content matches current content)"
      val file_lines = PIDE_MCP_Util.numbered_lines(new_text, 1, Line.Document(new_text).lines.length)
      Map("status" -> status, "description" -> description, "file_content" -> file_lines)
    }

  private def mk_definition_entry(
    name: String, kind: String, file: Option[String] = None, line: Option[Int] = None,
    source: Option[String] = None, note: Option[String] = None
  ): JSON.Object.T = {
    val base = JSON.Object("name" -> name, "kind" -> kind)
    val with_pos = (file, line) match {
      case (Some(f), Some(l)) => base + ("file" -> f) + ("line" -> l)
      case _ => base
    }
    val with_source = (source, line) match {
      case (Some(text), Some(l)) =>
        val snippet_end = l + PIDE_MCP_Tool_Handlers.snippet_preview_lines - 1
        with_pos + ("source_snippet" -> PIDE_MCP_Util.numbered_lines(text, l, snippet_end))
      case _ => with_pos
    }
    note match {
      case Some(n) => with_source + ("note" -> n)
      case None => with_source
    }
  }

  private def definition_entry(
    snap: Document.Snapshot,
    entry: Name_Space.Entry
  ): Exn.Result[Option[JSON.Object.T]] = Exn.capture {
    entry.properties match {
      case Position.Item_Def_File(def_file, def_line, _) =>
        val entry_result = session.session.store.source_file(def_file) match {
          case Some(resolved) =>
            val text = File.read(Path.explode(resolved))
            mk_definition_entry(entry.name, entry.kind,
              file = Some(resolved), line = Some(def_line), source = Some(text))
          case None =>
            mk_definition_entry(entry.name, entry.kind,
              note = Some("The definition entry's source file " + def_file
                + " could not be resolved yet. " + retry_soon_message))
        }
        Some(entry_result)
      case Position.Item_Def_Id(def_id, def_range) =>
        val entry_result = snap.find_command_position(def_id, def_range.start) match {
          case Some(pos) =>
            val pos_path = Path.explode(pos.name).expand
            val text = Exn.release(session.read(session.node_name(Path.explode(pos.name))))
            mk_definition_entry(entry.name, entry.kind,
              file = Some(pos_path.implode), line = Some(pos.line1), source = Some(text))
          case None =>
            mk_definition_entry(entry.name, entry.kind,
              note = Some("The definition entry has not been loaded yet. " + retry_soon_message))
        }
        Some(entry_result)
      case _ => None
    }
  }

  private def handle_find_origins(params: JSON.Object.T): Exn.Result[JSON.T] =
    Exn.capture {
      val path_str = Exn.release(path_param(params))
      val snap = Exn.release(load_snapshot(path_str))
      require_snapshot_node_defined(path_str, snap)
      val doc = Line.Document(snap.node.source)
      val start_line = JSON.int(params, "start_line") getOrElse error("Missing or invalid start_line")
      val end_line_opt = JSON.int(params, "end_line")
      val (s, end_line) = Exn.release(PIDE_MCP_Util.resolve_lines(Some(start_line), end_line_opt, doc.lines.length))
      val cmds = PIDE_MCP_Util.commands_in_range(snap, doc, s, end_line)
      val markup = cmds.flatMap { case (cmd, offset) => command_markup(snap, cmd, offset, Markup.Elements(Markup.ENTITY)) }
      markup.flatMap { case Text.Info(_, elems) =>
        elems.collect { case XML.Elem(Markup.Entity(entry), _) => entry }
      }.distinctBy(identity).flatMap(entry => Exn.release(definition_entry(snap, entry)))
    }

  private def handle_get_state(params: JSON.Object.T): Exn.Result[JSON.T] =
    Exn.capture {
      val path_str = Exn.release(path_param(params))
      val snap = Exn.release(load_snapshot(path_str))
      require_snapshot_node_defined(path_str, snap)
      val doc = Line.Document(snap.node.source)
      val start_line = JSON.int(params, "start_line")
      val end_line = JSON.int(params, "end_line")
      val (s, e) = Exn.release(PIDE_MCP_Util.resolve_lines(start_line, end_line, doc.lines.length))
      val cmds = PIDE_MCP_Util.commands_in_range(snap, doc, s, e)
      cmds.map { case (cmd, offset) => command_state_json(snap, cmd, offset) }
    }

  private def handle_list_loaded_theories(params: JSON.Object.T): Exn.Result[JSON.T] =
    Exn.capture {
      val include_scratch = JSON.bool(params, "include_scratch").getOrElse(false)
      val (static, dynamic, scratch) = session.loaded_theories
      def to_entry(name: Document.Node.Name) = Map("path" -> name.node, "theory" -> name.theory)
      val result = Map(
        "static" -> static.map(to_entry),
        "dynamic" -> dynamic.map(to_entry)
      )
      if (include_scratch) result + ("scratch" -> scratch.map(to_entry))
      else result
    }

  private def handle_read(params: JSON.Object.T): Exn.Result[JSON.T] =
    Exn.capture {
      val path_str = Exn.release(path_param(params))
      val abs_path = Path.explode(path_str).expand
      val name = session.node_name(abs_path)
      val text = Exn.release(session.read(name))
      val lines_count = Line.Document(text).lines.length
      val start_line = JSON.int(params, "start_line")
      val end_line = JSON.int(params, "end_line")
      val (s, e) = Exn.release(PIDE_MCP_Util.resolve_lines(start_line, end_line, lines_count))
      PIDE_MCP_Util.numbered_lines(text, s, e)
    }

}

object PIDE_MCP_Tool_Handlers {
  val default_imports: List[String] = List("Main")
  val snippet_preview_lines: Int = 15
}
