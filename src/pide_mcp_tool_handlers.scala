/*  Title:      PIDE_MCP/pide_mcp_tool_handlers.scala
    Author:     Kevin Kappelmann

Implementation of all PIDE MCP tool handlers.
*/

package isabelle.pide.mcp

import isabelle._

class PIDE_MCP_Tool_Handlers(session: PIDE_MCP_Session) {
  private val retry_soon_message = "Please retry soon."

  def handle(name: String, params: JSON.Object.T): Exn.Result[JSON.T] = {
    name match {
      case PIDE_MCP_Tool_Schemas.create_file => handle_create_file(params)
      case PIDE_MCP_Tool_Schemas.create_scratch => handle_create_scratch(params)
      case PIDE_MCP_Tool_Schemas.edit => handle_edit(params)
      case PIDE_MCP_Tool_Schemas.find_entities => handle_find_entities(params)
      case PIDE_MCP_Tool_Schemas.get_state => handle_get_state(params)
      case PIDE_MCP_Tool_Schemas.list_loaded_theories => handle_list_loaded_theories(params)
      case PIDE_MCP_Tool_Schemas.list_session_directories => handle_list_session_directories(params)
      case PIDE_MCP_Tool_Schemas.read => handle_read(params)
      case _ => Exn.capture(error(s"Unknown tool: $name"))
    }
  }

  private def require_loaded_file_snapshot(node_name: Document.Node.Name): Document.Snapshot = {
    val snapshot =
      session.node_snapshot(node_name) match {
        case Exn.Res(snapshot) => snapshot
        case Exn.Exn(_) =>
          Exn.release(session.load(node_name))
          error(s"The origin ${session.origin(node_name)} was not previously loaded and has now been queued for loading. "
            + "The requested information was thus not ready yet. "
            + retry_soon_message)
      }
    if (!node_name.is_theory && !PIDE_MCP_Util.is_file_loaded(snapshot, node_name))
      error(s"File ${session.origin(node_name)} is not loaded by any theory. Load the containing theory first.")
    snapshot
  }

  private def origin_param(params: JSON.Object.T): Exn.Result[Document.Node.Name] = Exn.capture {
    val origin = JSON.string(params, "origin").getOrElse(error("Missing origin parameter"))
    Exn.release(session.node_name(origin))
  }

  private def handle_create_file(params: JSON.Object.T): Exn.Result[JSON.T] =
    Exn.capture {
      val file_path = JSON.string(params, "path").getOrElse(error("Missing path parameter"))
      val created = Exn.release(session.create_file(Path.explode(file_path)))
      if (created) "File created" else "File already exists"
    }

  private def handle_create_scratch(params: JSON.Object.T): Exn.Result[JSON.T] =
    Exn.capture {
      val name_suffix = JSON.string(params, "name_suffix")
      val extension = JSON.string(params, "extension")
      val path = Exn.release(session.create_scratch_theory(name_suffix, extension))
      JSON.Object("path" -> path.implode)
    }

  private def handle_edit(params: JSON.Object.T): Exn.Result[JSON.T] =
    Exn.capture {
      val node_name = Exn.release(origin_param(params))
      val text = JSON.string(params, "text").getOrElse(error("Missing text parameter"))
      val old_text = JSON.string(params, "old_text").getOrElse(error("Missing old_text parameter"))
      val start_line = JSON.int(params, "start_line")
      val end_line = JSON.int(params, "end_line")
      val (new_text, written) = Exn.release(session.read_edit(node_name, text, start_line, end_line, old_text))
      val (status, description) = if (written) ("written", "Changes written")
        else ("unchanged", "Unchanged - did you replace the text by itself?")
      val file_content = PIDE_MCP_Util.numbered_lines(new_text, 1)
      JSON.Object("status" -> status, "description" -> description, "file_content" -> file_content)
    }

  private val definition_kinds =
    List(Markup.AXIOM, Markup.FACT, Markup.DYNAMIC_FACT, Markup.LITERAL_FACT,
      Markup.CONSTANT, Markup.TYPE_NAME,
      Markup.THEORY, Markup.SESSION, Markup.CLASS, Markup.LOCALE,
      Markup.COMMAND, Markup.CASE, Markup.BUNDLE,
      Markup.METHOD, Markup.ATTRIBUTE,
      Markup.ML_ANTIQUOTATION, Markup.ML_DEF,
      Markup.DOCUMENT_ANTIQUOTATION, Markup.DOCUMENT_ANTIQUOTATION_OPTION)

  private def handle_find_entities(params: JSON.Object.T): Exn.Result[JSON.T] =
    Exn.capture {
      val node_name = Exn.release(origin_param(params))
      val snapshot = require_loaded_file_snapshot(node_name)
      val start_line = JSON.int(params, "start_line").getOrElse(error("Missing or invalid start_line"))
      val end_line_opt = JSON.int(params, "end_line")
      val snippet_lines = JSON.int(params, "snippet_lines").getOrElse(PIDE_MCP_Tool_Handlers.snippet_preview_lines)
      val doc = Line.Document(snapshot.node.source)
      val (s, end_line) = Exn.release(PIDE_MCP_Util.resolve_lines(Some(start_line), end_line_opt, doc.lines.length))
      val filter_origins = JSON.strings(params, "filter_origins").getOrElse(Nil)
        .map(s => session.origin(Exn.release(session.node_name(s)))).toSet
      val range = PIDE_MCP_Util.range(doc, s, end_line)
      Exn.release(PIDE_MCP_Commands.definitions_json(session, snapshot, Some(range), snippet_lines, filter_origins,
        definition_kinds, "The definition entry has not been loaded yet. " + retry_soon_message))
    }

  private def handle_get_state(params: JSON.Object.T): Exn.Result[JSON.T] =
    Exn.capture {
      val node_name = Exn.release(origin_param(params))
      val snapshot = require_loaded_file_snapshot(node_name)
      val start_line = JSON.int(params, "start_line")
      val end_line = JSON.int(params, "end_line")
      val doc = Line.Document(snapshot.node.source)
      val (s, e) = Exn.release(PIDE_MCP_Util.resolve_lines(start_line, end_line, doc.lines.length))
      val include_types = JSON.bool(params, "include_types").getOrElse(false)
      val include_facts = JSON.bool(params, "include_facts").getOrElse(false)
      val include_infos = JSON.bool(params, "include_infos").getOrElse(false)
      val include_full_markup = JSON.bool(params, "include_full_markup").getOrElse(false)
      val limit = JSON.int(params, "commands_limit").getOrElse(PIDE_MCP_Tool_Handlers.commands_limit)

      val range = PIDE_MCP_Util.range(doc, s, e)
      val entries =
        (if (node_name.is_theory) {
          if (session.is_base_session_theory(node_name))
            Exn.release(PIDE_MCP_Commands.state_entries_theory_base_session(snapshot, Some(range)))
          else PIDE_MCP_Commands.state_entries_theory_dynamic(snapshot, Some(range))
        } else PIDE_MCP_Commands.state_entry_file(snapshot, Some(range)).iterator)
        .take(limit).toList
      val opts = PIDE_MCP_Commands.State_Options(include_types, include_facts, include_infos, include_full_markup)
      val command_states = PIDE_MCP_Commands.state_entries_json(snapshot, entries, doc, opts)
      val summary = PIDE_MCP_Commands.state_summary_json(snapshot, entries.iterator)
      val command_count_keys = PIDE_MCP_Commands.Status.all.toSet + "bad"
      JSON.Object(
        summary.toList.map { case (k, v) =>
          if (command_count_keys.contains(k)) ("commands_" + k, v) else (k, v)
        } :+ ("commands" -> command_states): _*)
    }

  private def handle_list_loaded_theories(params: JSON.Object.T): Exn.Result[JSON.T] =
    Exn.capture {
      val include_scratch = JSON.bool(params, "include_scratch").getOrElse(false)
      val (base_session, dynamic, scratch) = session.loaded_theories()
      def to_entry(node_name: Document.Node.Name) = JSON.Object("origin" -> session.origin(node_name))
      val result = JSON.Object(
        "dynamic" -> dynamic.map(to_entry),
        "base_session" -> base_session.map(to_entry)
      )
      if (include_scratch) result + ("scratch" -> scratch.map(to_entry))
      else result
    }

  private def handle_list_session_directories(params: JSON.Object.T): Exn.Result[JSON.T] =
    Exn.capture {
      session.session_directories.map(_.implode)
    }

  private def handle_read(params: JSON.Object.T): Exn.Result[JSON.T] =
    Exn.capture {
      val node_name = Exn.release(origin_param(params))
      val text = Exn.release(session.read(node_name))
      val start_line = JSON.int(params, "start_line")
      val end_line = JSON.int(params, "end_line")
      val lines_count = Line.Document(text).lines.length
      val (s, e) = Exn.release(PIDE_MCP_Util.resolve_lines(start_line, end_line, lines_count))
      PIDE_MCP_Util.numbered_lines_range(text, s, e)
    }

}

object PIDE_MCP_Tool_Handlers {
  val snippet_preview_lines: Int = 3
  val commands_limit: Int = 500
}
