/*  Title:      MCP/src/Tools/tool_start_session.scala
    Author:     Kevin Kappelmann
*/

package isabelle.pide.mcp

import isabelle._

class Tool_Start_Session extends PIDE_MCP_Tool("start_session") {
  def description: String =
    "Start a session. Returns its id once ready. " +
    "Builds the base session if needed (may take long). " +
    PIDE_MCP_Tool_Schema.session_restart

  private val default_spec: PIDE_MCP_Session_Spec = PIDE_MCP_Session_Spec()

  private val session_arg = JSON_Schema.Arg.optional(
    "session", s"Session id to use. Default: generated based on ${quote("logic")}.",
    JSON_Schema.Format.string)
  private val logic_arg = JSON_Schema.Arg.default(
    "logic", "Session to work with.", JSON_Schema.Format.string, default_spec.logic)
  private val dirs_arg = JSON_Schema.Arg.default(
    "dirs", "Included directories.",
    JSON_Schema.Format.list(JSON_Schema.Format.string),
    default_spec.dirs.map(_.implode))
  private val options_arg = JSON_Schema.Arg.default(
    "options",
    s"System options to override, each as ${quote("NAME=VAL")} or ${quote("NAME")}.",
    JSON_Schema.Format.list(JSON_Schema.Format.string),
    default_spec.options.map(_.print))
  private val session_ancestor_arg = JSON_Schema.Arg.optional(
    "session_ancestor",
    s"Session that the ${quote("session_requirements")} base session extends. " +
    s"Default: parent of ${quote("logic")}.",
    JSON_Schema.Format.string)
  private val session_requirements_arg = JSON_Schema.Arg.default(
    "session_requirements",
    s"Build the base session only from what ${quote("logic")} needs from other sessions, " +
    s"so its theories stay editable. Otherwise, ${quote("logic")} is the base session.",
    JSON_Schema.Format.bool, default_spec.session_requirements)
  private val fresh_build_arg = JSON_Schema.Arg.default(
    "fresh_build", "Force rebuild of base session.", JSON_Schema.Format.bool,
    default_spec.fresh_build)
  private val no_build_arg = JSON_Schema.Arg.default(
    "no_build", "Do not build base session on startup and fail if not prebuilt.",
    JSON_Schema.Format.bool, default_spec.no_build)

  def input_schema: JSON.Object.T = JSON_Schema.object_schema(
    List(session_arg, logic_arg, dirs_arg, options_arg, session_ancestor_arg,
      session_requirements_arg, fresh_build_arg, no_build_arg))

  def handle(
    sessions: PIDE_MCP_Sessions,
    args: JSON.Object.T,
    progress: Progress
  ): PIDE_MCP_Tool_Result =
    PIDE_MCP_Tool_Result.result {
      val spec = PIDE_MCP_Session_Spec(
        logic = logic_arg.get(args),
        id = session_arg.get(args),
        dirs = dirs_arg.get(args).map(PIDE_MCP_Session_Spec.dir),
        options = options_arg.get(args).map(PIDE_MCP_Session_Spec.option),
        session_ancestor = session_ancestor_arg.get(args),
        session_requirements = session_requirements_arg.get(args),
        fresh_build = fresh_build_arg.get(args),
        no_build = no_build_arg.get(args))
      val session = Result.release(sessions.start_session(spec, progress))
      JSON_Object("session" -> session.id)
    }
}

class Tools_Start_Session extends PIDE_MCP_Tools(new Tool_Start_Session)
