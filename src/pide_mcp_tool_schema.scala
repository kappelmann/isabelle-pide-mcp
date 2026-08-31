/*  Title:      PIDE_MCP/pide_mcp_tool_schema.scala
    Author:     Kevin Kappelmann

Shared JSON-Schema fragments for MCP tools.
*/

package isabelle.pide.mcp

import isabelle._

object PIDE_MCP_Tool_Schema {
  val implicit_load_file: String = "Implicitly loads the file if required."
  val implicit_reload_file: String = "Implicitly (re)loads the file."
  val range_visibility: String = "Sets the PIDE perspective on the range."
  val session_restart: String =
    "**Use it only when required**, e.g. when you have to restart a session due to " +
      "non-responsiveness, after changing the session's ROOT file, or when you have to change " +
      "the base session."

  val running_session_arg = PIDE_MCP_Tool_Arg.opt_string(
    "session", "Running session id (optional if exactly one session is running).")
  val running_sessions_arg = PIDE_MCP_Tool_Arg.opt_strings(
    "sessions", "Running session ids (default: all running sessions).")
  val origin_arg = PIDE_MCP_Tool_Arg.string(
    "origin",
    s"Session-qualified theory name (e.g. ${quote("HOL.Nat")}) " +
      s"or file path (e.g. ${quote("foo.ML")}).")
  val start_line_arg = PIDE_MCP_Tool_Arg.int(
    "start_line", "First line to include.", minimum = Some(1))
  val opt_start_line_arg = PIDE_MCP_Tool_Arg.opt_int(
    "start_line", "First line to include.",
    schema_default = Some(PIDE_MCP_Tool_Util.default_start_line), minimum = Some(1))
  val opt_end_line_arg = PIDE_MCP_Tool_Arg.opt_int(
    "end_line", "Last line to include (default: end of file).", minimum = Some(1))

  def input_schema(args: List[PIDE_MCP_Tool_Arg[?]]): JSON.Object.T =
    JSON_Object("type" -> "object", "properties" -> JSON_Object(args.map(_.entry))) ++
      JSON.optional("required" -> proper_list(args.filter(_.required).map(_.name)))
}
