/*  Title:      MCP/src/Tools/tool_get_state.scala
    Author:     Kevin Kappelmann
*/

package isabelle.pide.mcp

import isabelle._

object Tool_Get_State {
  private def get_state_delay(options: Options): Time =
    options.seconds("pide_mcp_get_state_delay")

  enum Stop_On {
    case any_failed, all_terminated
    def stop(statuses: List[Document_Status.Command_Status]): Boolean =
      statuses.forall(_.is_terminated) || this == any_failed && statuses.exists(_.is_failed)
  }
  val stop_on_arg = JSON_Schema.Arg.default("stop_on", "",
    JSON_Schema.Format.enumeration(Stop_On.values), Stop_On.any_failed)

  val include_summary_warnings_arg = JSON_Schema.Arg.default(
    "include_summary_warnings", "", JSON_Schema.Format.bool, false)
  val summary_problematic_limit_arg = JSON_Schema.Arg.optional(
    "summary_problematic_limit",
    "Problematic occurrences to return. Default: all.",
    JSON_Schema.Format.int(minimum = Some(0)))
  val summary_snippet_lines_arg = JSON_Schema.Arg.default(
    "summary_snippet_lines", "Number of source lines shown (0 for line ranges only).",
    JSON_Schema.Format.int(minimum = Some(0)), 1)
  val details_arg = JSON_Schema.Arg.default(
    "details",
    s"Details to return. infos = ${commas(PIDE_MCP_Message.Message_Kind.info.map(_.toString))}.",
    JSON_Schema.Format.list(JSON_Schema.Format.enumeration(PIDE_MCP_Command.State.Detail.values)),
    Nil)
  val details_problematic_limit_arg = JSON_Schema.Arg.optional(
    "details_problematic_limit",
    "Problematic commands to return. Default: all.",
    JSON_Schema.Format.int(minimum = Some(0)))
  val details_unproblematic_limit_arg = JSON_Schema.Arg.optional(
    "details_unproblematic_limit",
    "Unproblematic commands to return. Default: all.",
    JSON_Schema.Format.int(minimum = Some(0)))

  def timeout_arg(default: Int) = JSON_Schema.Arg.default(
    "timeout", "Seconds to wait.",
    JSON_Schema.Format.int(minimum = Some(0)), default)

  def state_description(which_commands: String): String =
    "Summary (errors, commands being processed,...) and optional details " +
    s"(subgoals, types, markup,...) of $which_commands. Limits do not affect counts. " +
    "Commands edited during request are omitted. " +
    "For a non-theory file, the state is that of the command loading it."

  val render_args: List[JSON_Schema.Arg[?]] = List(include_summary_warnings_arg,
    summary_problematic_limit_arg, summary_snippet_lines_arg, details_arg,
    details_problematic_limit_arg, details_unproblematic_limit_arg)

  def state_args(timeout_arg: JSON_Schema.Arg[Int]) =
    render_args ::: List(timeout_arg, stop_on_arg)

  def states_json(
    source: String,
    states: Iterator[PIDE_MCP_Command.State],
    args: JSON.Object.T
  ): JSON.Object.T = {
    val summary_message_kinds = PIDE_MCP_Message.Message_Kind.errors ::
      Option.when(include_summary_warnings_arg.get(args))(
        PIDE_MCP_Message.Message_Kind.warnings).toList
    val states_args = PIDE_MCP_Command.States.Args(summary_message_kinds,
      details_problematic_limit_arg.get(args),
      details_unproblematic_limit_arg.get(args),
      summary_problematic_limit_arg.get(args), summary_snippet_lines_arg.get(args))
    PIDE_MCP_Command.States.json(states, Line.Document(source),
      details_arg.get(args).toSet, states_args)
  }

  def state(
    session: PIDE_MCP_Session,
    node_name: Document.Node.Name,
    start_line: Int,
    opt_end_line: Option[Int],
    timeout: Time,
    args: JSON.Object.T,
    progress: Progress
  ): JSON.Object.T = {
    session.await_stable(session.sync_dependencies_resolve(List(node_name), progress), progress)
    val snapshot0 = PIDE_MCP_Tool_Util.require_loaded_origin_snapshot(session, node_name, progress)
    val range = Some(PIDE_MCP_Util.check_text_range(
      Line.Document(snapshot0.node.source), start_line, opt_end_line))
    val (snapshot, states, edited, timed_out) =
      if (timeout.is_zero) (snapshot0, PIDE_MCP_Command.States(snapshot0, range), 0, false)
      else
        PIDE_MCP_Command.await_states(session, snapshot0, range, stop_on_arg.get(args).stop,
          Some(timeout), get_state_delay(session.options), progress)
    states_json(snapshot.node.source, states, args) ++
    JSON_Object.flatten(Option.when(edited > 0)("cmds_edited_omitted" -> edited),
      Option.when(timed_out)("timed_out" -> true))
  }
}

class Tool_Get_State extends PIDE_MCP_Tool("get_state") {
  def description: String =
    Tool_Get_State.state_description("commands in range") + " " +
    PIDE_MCP_Tool_Schema.implicit_load_file

  private val timeout_arg = Tool_Get_State.timeout_arg(0)

  def input_schema: JSON.Object.T = JSON_Schema.object_schema(
    List(PIDE_MCP_Tool_Schema.running_session_arg, PIDE_MCP_Tool_Schema.origin_arg,
      PIDE_MCP_Tool_Schema.start_line_default_arg, PIDE_MCP_Tool_Schema.opt_end_line_arg) :::
    Tool_Get_State.state_args(timeout_arg))

  override def annotations: Option[JSON.Object.T] = Some(JSON_Object("readOnlyHint" -> true))

  def handle(
    sessions: PIDE_MCP_Sessions,
    args: JSON.Object.T,
    progress: Progress
  ): PIDE_MCP_Tool_Result =
    PIDE_MCP_Tool_Result.result {
      val session = PIDE_MCP_Tool_Util.running_session_param(sessions, args)
      val node_name = PIDE_MCP_Tool_Util.origin_param(session, args)
      Tool_Get_State.state(session, node_name,
        PIDE_MCP_Tool_Schema.start_line_default_arg.get(args),
        PIDE_MCP_Tool_Schema.opt_end_line_arg.get(args),
        Time.seconds(timeout_arg.get(args)), args, progress)
    }
}

class Tools_Get_State extends PIDE_MCP_Tools(new Tool_Get_State)
