/*  Title:      PIDE_MCP/tool_get_state.scala
    Author:     Kevin Kappelmann
*/

package isabelle.pide.mcp

import isabelle._

object Tool_Get_State {
  val detail_format = {
    val details = PIDE_MCP_Command.State.Detail.values.toList
    PIDE_MCP_Tool_Arg.Format(
      JSON_Object("type" -> "string", "enum" -> details.map(_.toString)),
      json => JSON.Value.String.unapply(json).flatMap(detail => details.find(_.toString == detail)),
      _.toString)
  }
}

class Tool_Get_State extends PIDE_MCP_Tool("get_state") {
  def description: String =
    "Summary (errors, commands being processed,...) and optional details " +
    "(subgoals, types, markup,...) for commands. " +
    "Limits do not affect counts. " +
    "For a non-theory file, the state is that of the command loading it. " +
    PIDE_MCP_Tool_Schema.implicit_load_file

  private val include_summary_warnings_arg = PIDE_MCP_Tool_Arg.bool_default(
    "include_summary_warnings", "", false)
  private val summary_problematic_limit_arg = PIDE_MCP_Tool_Arg.opt_int(
    "summary_problematic_limit",
    "Problematic occurrences to return. Default: all.",
    minimum = Some(0))
  private val summary_snippet_lines_arg = PIDE_MCP_Tool_Arg.int_default(
    "summary_snippet_lines", "Number of source lines shown. Use 0 to only show line ranges.",
    PIDE_MCP_Command.States.default_snippet_lines, minimum = Some(0))
  private val details_arg = PIDE_MCP_Tool_Arg.default(
    "details",
    s"Details to return. infos = ${commas(PIDE_MCP_Command.Message_Kind.info.map(_.toString))}.",
    PIDE_MCP_Tool_Arg.Format.list(Tool_Get_State.detail_format), Nil)
  private val details_problematic_limit_arg = PIDE_MCP_Tool_Arg.opt_int(
    "details_problematic_limit",
    "Problematic commands to return. Default: all.",
    minimum = Some(0))
  private val details_unproblematic_limit_arg = PIDE_MCP_Tool_Arg.opt_int(
    "details_unproblematic_limit",
    "Unproblematic commands to return. Default: all.",
    minimum = Some(0))

  def input_schema: JSON.Object.T = PIDE_MCP_Tool_Schema.input_schema(
    List(PIDE_MCP_Tool_Schema.running_session_arg, PIDE_MCP_Tool_Schema.origin_arg,
      PIDE_MCP_Tool_Schema.start_line_default_arg, PIDE_MCP_Tool_Schema.opt_end_line_arg,
      include_summary_warnings_arg, summary_problematic_limit_arg, summary_snippet_lines_arg,
      details_arg, details_problematic_limit_arg, details_unproblematic_limit_arg))

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
      val opt_end_line = PIDE_MCP_Tool_Schema.opt_end_line_arg.get(args)
      val doc = Line.Document(snapshot.node.source)
      val (start_line, end_line) = PIDE_MCP_Tool_Util.resolve_lines(
        PIDE_MCP_Tool_Schema.start_line_default_arg.get(args), opt_end_line, doc.lines.length)
      val details = details_arg.get(args).toSet
      val summary_message_kinds = PIDE_MCP_Command.Message_Kind.errors ::
        Option.when(include_summary_warnings_arg.get(args))(
          PIDE_MCP_Command.Message_Kind.warnings).toList
      val opts = PIDE_MCP_Command.States.Options(summary_message_kinds,
        details_problematic_limit_arg.get(args),
        details_unproblematic_limit_arg.get(args),
        summary_problematic_limit_arg.get(args), summary_snippet_lines_arg.get(args))
      val range = PIDE_MCP_Util.text_range(doc, start_line, end_line).get
      val states = PIDE_MCP_Command.States(snapshot, Some(range))
      PIDE_MCP_Command.States.json(states, doc, details, opts)
    }
}

class Tools_Get_State extends PIDE_MCP_Tools(new Tool_Get_State)
