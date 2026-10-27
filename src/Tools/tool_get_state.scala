/*  Title:      PIDE_MCP/tool_get_state.scala
    Author:     Kevin Kappelmann
*/

package isabelle.pide.mcp

import isabelle._

class Tool_Get_State extends PIDE_MCP_Tool("get_state") {
  def description: String =
    "Inspect the state of a (range in a) file: errors, warnings, goals, variables, etc. " +
    "Returns a summary (errors, warnings, commands still running,...) and, optionally, details (subgoals, types, markup,...) for commands in range. " +
    "The summary includes all commands, even the ones beyond the passed command limits. " +
    "**Use this frequently to check if you are making progress, what is left to be done, and if commands are still processing, potentially even looping.** " +
    "For non-theory files, the response has a single command derived from the command that loaded it in the respective theory. " +
    PIDE_MCP_Tool_Schema.implicit_load_file

  private val include_details_arg = PIDE_MCP_Tool_Arg.bool_default(
    "include_details",
    "Include command details, limited by the command limits.",
    false)
  private val include_goals_arg = PIDE_MCP_Tool_Arg.bool_default(
    "include_goals",
    "Include goals in details.",
    false)
  private val include_warnings_arg = PIDE_MCP_Tool_Arg.bool_default(
    "include_warnings",
    "Include warnings in summary and details.",
    false)
  private val include_infos_arg = PIDE_MCP_Tool_Arg.bool_default(
    "include_infos",
    "Include writeln, information, and tracing messages in details. This can get large, but often contains useful information. Avoid it for large ranges.",
    false)
  private val include_types_arg = PIDE_MCP_Tool_Arg.bool_default(
    "include_types",
    "Include types (Isabelle and ML) for variables and constants in details.", false)
  private val include_facts_arg = PIDE_MCP_Tool_Arg.bool_default(
    "include_facts",
    "Include facts/theorems used in range in details.", false)
  private val include_full_markup_arg = PIDE_MCP_Tool_Arg.bool_default(
    "include_full_markup",
    "Include full PIDE markup information in details. This gets very large - **use only sparingly and very targeted to get local details**.",
    false)
  private val problematic_commands_limit_arg = PIDE_MCP_Tool_Arg.opt_int(
    "problematic_commands_limit",
    "Maximum number of problematic commands to return in details. Omit to return all.",
    minimum = Some(0))
  private val unproblematic_commands_limit_arg = PIDE_MCP_Tool_Arg.opt_int(
    "unproblematic_commands_limit",
    "Maximum number of unproblematic commands to return in details. Omit to return all.",
    minimum = Some(0))
  private val snippet_lines_arg = PIDE_MCP_Tool_Arg.int_default(
    "snippet_lines",
    "Number of source lines shown per summary snippet. Use 0 to only show line ranges.",
    1, minimum = Some(0))

  def input_schema: JSON.Object.T = PIDE_MCP_Tool_Schema.input_schema(
    List(PIDE_MCP_Tool_Schema.running_session_arg, PIDE_MCP_Tool_Schema.origin_arg,
      PIDE_MCP_Tool_Schema.opt_start_line_arg, PIDE_MCP_Tool_Schema.opt_end_line_arg,
      include_details_arg, include_goals_arg, include_warnings_arg, include_infos_arg,
      include_types_arg, include_facts_arg, include_full_markup_arg,
      problematic_commands_limit_arg, unproblematic_commands_limit_arg, snippet_lines_arg))

  override def annotations: Option[JSON.Object.T] = Some(JSON_Object("readOnlyHint" -> true))

  def handle(
    sessions: PIDE_MCP_Sessions,
    args: JSON.Object.T,
    progress: Progress
  ): PIDE_MCP_Tool_Result =
    PIDE_MCP_Tool_Result.result {
      val session = PIDE_MCP_Tool_Util.running_session_param(sessions, args)
      val node_name = PIDE_MCP_Tool_Util.origin_param(session, args)
      val snapshot =
        PIDE_MCP_Tool_Util.require_loaded_origin_snapshot(session, node_name, progress)
      val opt_start_line = PIDE_MCP_Tool_Schema.opt_start_line_arg.get(args)
      val opt_end_line = PIDE_MCP_Tool_Schema.opt_end_line_arg.get(args)
      val doc = Line.Document(snapshot.node.source)
      val (start_line, end_line) =
        PIDE_MCP_Tool_Util.resolve_lines(opt_start_line, opt_end_line, doc.lines.length)
      val message_kinds =
        Option.when(include_goals_arg.get(args))(PIDE_MCP_Command.Message_Kind.goals).toList :::
        List(PIDE_MCP_Command.Message_Kind.errors) :::
        Option.when(include_warnings_arg.get(args))(PIDE_MCP_Command.Message_Kind.warnings).toList :::
        (if (include_infos_arg.get(args)) PIDE_MCP_Command.Message_Kind.info else Nil)
      val state_opts = PIDE_MCP_Command.State.Options(message_kinds,
        include_types_arg.get(args), include_facts_arg.get(args), include_full_markup_arg.get(args))
      val opts = PIDE_MCP_Command.States.Options(
        include_details_arg.get(args), problematic_commands_limit_arg.get(args),
        unproblematic_commands_limit_arg.get(args), snippet_lines_arg.get(args))
      val range = PIDE_MCP_Util.text_range(doc, start_line, end_line).get
      val states = PIDE_MCP_Command.States(snapshot, Some(range))
      PIDE_MCP_Command.States.json(states, doc, state_opts, opts)
    }
}

class Tools_Get_State extends PIDE_MCP_Tools(new Tool_Get_State)
