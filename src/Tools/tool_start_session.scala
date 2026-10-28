/*  Title:      PIDE_MCP/tool_start_session.scala
    Author:     Kevin Kappelmann
*/

package isabelle.pide.mcp

import isabelle._

class Tool_Start_Session extends PIDE_MCP_Tool("start_session") {
  def description: String =
    "Start a session and return its id. " +
    "Builds the base session if necessary, which may take significant time. " +
    PIDE_MCP_Tool_Schema.session_restart + " " +
    "This tool returns once the session is completely ready."

  private val default_spec: PIDE_MCP_Session.Spec = PIDE_MCP_Session.Spec()

  private val session_arg = PIDE_MCP_Tool_Arg.opt_string(
    "session", s"Session id to use. Default: generated based on ${quote("logic")}.")
  private val logic_arg = PIDE_MCP_Tool_Arg.string_default(
    "logic",
    "Session context to work with. " +
    "Used as a base session or dynamic session anchor point, depending on other options.",
    default_spec.logic)
  private val dirs_arg = PIDE_MCP_Tool_Arg.strings_default(
    "dirs", "Included directories.", default_spec.dirs.map(_.implode))
  private val options_arg = PIDE_MCP_Tool_Arg.strings_default(
    "options",
    s"System options to override, each as ${quote("NAME=VAL")} or ${quote("NAME")}.",
    default_spec.options.map(_.print))
  private val session_ancestor_arg = PIDE_MCP_Tool_Arg.opt_string(
    "session_ancestor",
    s"Ancestor session of ${quote("session_requirements")} that will be used as a base session. " +
    s"Default: parent session of ${quote("logic")}.")
  private val session_requirements_arg = PIDE_MCP_Tool_Arg.bool_default(
    "session_requirements",
    s"Build base session from requirements/imports of ${quote("logic")}; " +
    s"otherwise, use ${quote("logic")} as base session.",
    default_spec.session_requirements)
  private val fresh_build_arg = PIDE_MCP_Tool_Arg.bool_default(
    "fresh_build", "Force rebuild of base session.", default_spec.fresh_build)
  private val no_build_arg = PIDE_MCP_Tool_Arg.bool_default(
    "no_build", "Do not build base session on startup and fail if not prebuilt.",
    default_spec.no_build)

  def input_schema: JSON.Object.T = PIDE_MCP_Tool_Schema.input_schema(
    List(session_arg, logic_arg, dirs_arg, options_arg, session_ancestor_arg,
      session_requirements_arg, fresh_build_arg, no_build_arg))

  def handle(
    sessions: PIDE_MCP_Sessions,
    args: JSON.Object.T,
    progress: Progress
  ): PIDE_MCP_Tool_Result =
    PIDE_MCP_Tool_Result.result {
      val spec = PIDE_MCP_Session.Spec(
        logic = logic_arg.get(args),
        id = session_arg.get(args),
        dirs = dirs_arg.get(args).map(PIDE_MCP_Session.Spec.dir),
        options = options_arg.get(args).map(PIDE_MCP_Session.Spec.option),
        session_ancestor = session_ancestor_arg.get(args),
        session_requirements = session_requirements_arg.get(args),
        fresh_build = fresh_build_arg.get(args),
        no_build = no_build_arg.get(args))
      val session = Result.release(sessions.start_session(spec, progress))
      JSON_Object("session" -> session.id)
    }
}

class Tools_Start_Session extends PIDE_MCP_Tools(new Tool_Start_Session)
