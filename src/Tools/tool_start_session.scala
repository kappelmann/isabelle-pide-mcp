/*  Title:      PIDE_MCP/tool_start_session.scala
    Author:     Kevin Kappelmann
*/

package isabelle.pide.mcp

import isabelle._

class Tool_Start_Session extends PIDE_MCP_Tool("start_session") {
  def description: String =
    "Start a PIDE session and return its session id. " +
      "Builds the base session if necessary, which **may take several minutes if not more.** " +
      PIDE_MCP_Tool_Schema.session_restart + " " +
      "**Note: this tool is synchronous! It will return once the session is completely ready.**"

  private val default_spec: PIDE_MCP_Session.Spec = PIDE_MCP_Session.Spec()

  private val session_arg = PIDE_MCP_Tool_Arg.opt_string(
    "session",
    s"Session id to use (default: generated based on ${quote("logic")}).")
  private val logic_arg = PIDE_MCP_Tool_Arg.string_default(
    "logic",
    "Session context to work with. " +
      "Used as a base session (on top of which you develop) or dynamic session anchor point, depending on other passed options.",
    default_spec.logic)
  private val dirs_arg = PIDE_MCP_Tool_Arg.strings_default(
    "dirs",
    "Directories to include. If you use ROOT files, make sure to include their directories here.",
    default_spec.dirs.map(_.implode))
  private val options_arg = PIDE_MCP_Tool_Arg.strings_default(
    "options",
    s"Isabelle system options to override, each as ${quote("NAME=VAL")} or ${quote("NAME")}.",
    default_spec.options.map(_.print))
  private val session_ancestor_arg = PIDE_MCP_Tool_Arg.opt_string(
    "session_ancestor",
    s"Ancestor session of ${quote("session_requirements")} that will be used as a base session (default: parent session of ${quote("logic")}).")
  private val session_requirements_arg = PIDE_MCP_Tool_Arg.bool_default(
    "session_requirements",
    s"Build base session from the requirements/imports of ${quote("logic")}; " +
      s"otherwise, use ${quote("logic")} as the base session.",
    default_spec.session_requirements)
  private val fresh_build_arg = PIDE_MCP_Tool_Arg.bool_default(
    "fresh_build",
    "Force rebuild of the base session.", default_spec.fresh_build)
  private val no_build_arg = PIDE_MCP_Tool_Arg.bool_default(
    "no_build",
    "Do not build base session on startup and fail if not already prebuilt. " +
      "**Use this to check whether a session can be started without a potentially long build.**",
    default_spec.no_build)

  def input_schema: JSON.Object.T = PIDE_MCP_Tool_Schema.input_schema(
    List(session_arg, logic_arg, dirs_arg, options_arg, session_ancestor_arg,
      session_requirements_arg, fresh_build_arg, no_build_arg))

  def handle(
    sessions: PIDE_MCP_Sessions,
    args: JSON.Object.T,
    progress: Progress
  ): PIDE_MCP_Tool_Result =
    Exn.result {
      PIDE_MCP_Session.Spec(
        logic = logic_arg.get(args),
        id = session_arg.get(args),
        dirs = dirs_arg.get(args).map(PIDE_MCP_Session.Spec.dir),
        options = options_arg.get(args).map(PIDE_MCP_Session.Spec.option),
        session_ancestor = session_ancestor_arg.get(args),
        session_requirements = session_requirements_arg.get(args),
        fresh_build = fresh_build_arg.get(args),
        no_build = no_build_arg.get(args))
    } match {
      case Exn.Exn(exn) => PIDE_MCP_Tool_Result.exn_error(exn)
      case Exn.Res(spec) =>
        sessions.start(spec, progress) match {
          case Result.Res(session) =>
            PIDE_MCP_Tool_Result.Res(JSON_Object("session" -> session.id))
          case Result.Error(exn) => PIDE_MCP_Tool_Result.exn_error(exn)
        }
    }
}

class Tools_Start_Session extends PIDE_MCP_Tools(new Tool_Start_Session)
