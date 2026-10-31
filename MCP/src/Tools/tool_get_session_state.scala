/*  Title:      MCP/src/Tools/tool_get_session_state.scala
    Author:     Kevin Kappelmann
*/

package isabelle.pide.mcp

import isabelle._

import scala.collection.immutable.VectorMap

class Tool_Get_Session_State extends PIDE_MCP_Tool("get_session_state") {
  def description: String =
    "State of running sessions, information for their theories " +
    "(status, commands being processed,...), resource statistics,... " +
    "Sorrys are not listed."

  private val filter_origins_arg = JSON_Schema.Arg.default(
    "filter_origins",
    "Session id to theory origins whose information is returned. " +
    "Default for entry-less session: all dynamic theories.",
    JSON_Schema.Format.dictionary(
      JSON_Schema.Format.list(JSON_Schema.Format.string)),
    VectorMap.empty)
  private val include_cmds_processing_arg = JSON_Schema.Arg.default(
    "include_cmds_processing", "", JSON_Schema.Format.bool, true)
  private val include_thy_status_arg = JSON_Schema.Arg.default(
    "include_thy_status", "Status, progress, command counts.",
    JSON_Schema.Format.bool, true)
  private val list_loaded_thys_arg = JSON_Schema.Arg.default(
    "list_loaded_thys", "", JSON_Schema.Format.bool, false)
  private val list_session_dirs_arg = JSON_Schema.Arg.default(
    "list_session_dirs", "Directories from which a session resolves files.",
    JSON_Schema.Format.bool, false)
  private val list_options_arg = JSON_Schema.Arg.default(
    "list_options",
    "Isabelle session options. " +
    s"${quote("changed")} shows options that differ from their default. " +
    "true shows all.",
    PIDE_MCP_Session_State.List_Options.format, PIDE_MCP_Session_State.List_Options.`false`)
  private val include_statistics_arg = JSON_Schema.Arg.default(
    "include_statistics",
    "Each session's ML and the server's JVM statistics (heap, threads,...).",
    JSON_Schema.Format.bool, false)

  def input_schema: JSON.Object.T = JSON_Schema.object_schema(
    List(PIDE_MCP_Tool_Schema.running_sessions_arg, filter_origins_arg,
      include_cmds_processing_arg, include_thy_status_arg, list_loaded_thys_arg,
      list_session_dirs_arg, list_options_arg, include_statistics_arg))

  override def annotations: Option[JSON.Object.T] = Some(JSON_Object("readOnlyHint" -> true))

  def handle(
    sessions: PIDE_MCP_Sessions,
    args: JSON.Object.T,
    progress: Progress
  ): PIDE_MCP_Tool_Result =
    PIDE_MCP_Tool_Result.result {
      val selected_sessions = PIDE_MCP_Tool_Util.running_sessions_param(sessions, args)
      val filter_origins = filter_origins_arg.get(args)
      val selected_ids = selected_sessions.map(_.id)
      val unknown = filter_origins.keysIterator.filterNot(selected_ids.toSet).toList
      if (unknown.nonEmpty) {
        error(s"Session(s) ${commas_quote(unknown)} of filter_origins not contained in " +
          s"selection ${commas_quote(selected_ids)}.")
      }
      val state_args = PIDE_MCP_Session_State.Args(
        include_cmds_processing = include_cmds_processing_arg.get(args),
        include_thy_status = include_thy_status_arg.get(args),
        list_loaded_thys = list_loaded_thys_arg.get(args),
        list_session_dirs = list_session_dirs_arg.get(args),
        list_options = list_options_arg.get(args),
        include_statistics = include_statistics_arg.get(args))
      JSON_Object("sessions" -> selected_sessions.map(session =>
        PIDE_MCP_Session_State.json(session, filter_origins.get(session.id), state_args))) ++
      JSON_Object.if_proper(state_args.include_statistics,
        PIDE_MCP_Session_State.jvm_stats_json())
    }
}

class Tools_Get_Session_State extends PIDE_MCP_Tools(new Tool_Get_Session_State)
