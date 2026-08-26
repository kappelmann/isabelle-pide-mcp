/*  Title:      PIDE_MCP/tool_get_state.scala
    Author:     Kevin Kappelmann
*/

package isabelle.pide.mcp

import isabelle._

class Tool_Get_State extends PIDE_MCP_Tool("get_state") {
  def description: String =
    "Inspect the state of a (range in a) file: goals, variables, errors, warnings, etc. "
      + "Returns a summary (errors, warnings, commands still running, total timing,...) and details for all commands in range. "
      + "**Use this frequently to check if you are making progress, what is left to be done, and importantly, if certain commands are still processing, potentially even looping.** "
      + "For non-theory files, the response has a single command entry derived from the command that loaded it in the respective theory. "
      + PIDE_MCP_Tool_Schema.implicit_load_file

  def input_schema: JSON.Object.T =
    JSON_Object("type" -> "object", "properties" -> JSON_Object(
      PIDE_MCP_Tool_Schema.origin_prop,
      PIDE_MCP_Tool_Schema.opt_start_line_prop,
      PIDE_MCP_Tool_Schema.opt_end_line_prop,
      "include_types" -> JSON_Object("type" -> "boolean",
        "description" -> "Include types (Isabelle and ML) for variables and constants.",
        "default" -> false),
      "include_facts" -> JSON_Object("type" -> "boolean",
        "description" -> "Include facts/theorems used in range.",
        "default" -> false),
      "include_infos" -> JSON_Object("type" -> "boolean",
        "description" -> "Include writeln and information output in the state. This can get large, but often contains useful information. Avoid using it for large ranges if possible.",
        "default" -> false),
      "include_full_markup" -> JSON_Object("type" -> "boolean",
        "description" -> "Include full PIDE markup information. This gets very large - **use only sparingly and very targeted to get local details**.",
        "default" -> false),
      "commands_limit" -> JSON_Object("type" -> "integer",
        "description" -> ("Maximum number of commands to return. Omit to return all commands. "
          + "**Set to 0 whenever you only want to get summary information (e.g. to see if there are any errors, warnings, etc.).** "
          + "Note that the returned summary will count all commands, even the truncated ones. "
          + "Warning: for large ranges, there are thousands of commands that may flood your context. Set the commands limit to 0 if you do not need details."))
    ), "required" -> List("origin"))

  override def annotations: Option[JSON.Object.T] = Some(JSON_Object("readOnlyHint" -> true))

  def handle(args: JSON.Object.T): Exn.Result[JSON.T] = Exn.capture {
    val node_name = Exn.release(PIDE_MCP_Tool_Util.origin_param(session, args))
    val snapshot = PIDE_MCP_Tool_Util.require_loaded_origin_snapshot(session, node_name)
    val opt_start_line = JSON.int(args, "start_line")
    val opt_end_line = JSON.int(args, "end_line")
    val doc = Line.Document(snapshot.node.source)
    val (start_line, end_line) =
      Exn.release(PIDE_MCP_Tool_Util.resolve_lines(opt_start_line, opt_end_line, doc.lines.length))
    val include_types = JSON.bool(args, "include_types").getOrElse(false)
    val include_facts = JSON.bool(args, "include_facts").getOrElse(false)
    val include_infos = JSON.bool(args, "include_infos").getOrElse(false)
    val include_full_markup = JSON.bool(args, "include_full_markup").getOrElse(false)
    val opt_limit = JSON.int(args, "commands_limit")

    val range = PIDE_MCP_Util.text_range(doc, start_line, end_line).get
    val entries = PIDE_MCP_Commands.state_entries(snapshot, Some(range))
    val opts = PIDE_MCP_Commands.State_Options(include_types, include_facts, include_infos, include_full_markup)
    PIDE_MCP_Commands.state_json(entries, doc, opts, opt_limit)
  }
}

class Tools_Get_State extends PIDE_MCP_Tools(new Tool_Get_State)
