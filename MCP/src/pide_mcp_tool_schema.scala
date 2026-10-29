/*  Title:      MCP/src/pide_mcp_tool_schema.scala
    Author:     Kevin Kappelmann

Shared JSON-Schema fragments for MCP tools.
*/

package isabelle.pide.mcp

import isabelle._

object PIDE_MCP_Tool_Schema {
  val implicit_load_file: String = "Implicitly loads file if required."
  val implicit_reload_file: String = "Implicitly (re)loads file."
  val range_perspective: String = "Moves PIDE perspective to the range."
  val no_processing_wait: String = "Does not wait for commands to finish."
  val session_restart: String =
    "Use only when required: unresponsiveness or ROOT or base session updates."

  val running_session_arg = PIDE_MCP_Tool_Arg.opt_string(
    "session", "Running session id. Default: unique running session.")
  val running_sessions_arg = PIDE_MCP_Tool_Arg.opt_strings(
    "sessions", "Running session ids. Default: all running sessions.")
  val origin_arg = PIDE_MCP_Tool_Arg.string(
    "origin",
    s"Session-qualified theory name (e.g. ${quote("HOL.Nat")}) or file path.")
  val start_line_arg = PIDE_MCP_Tool_Arg.int(
    "start_line", "", minimum = Some(1))
  val start_line_default_arg = PIDE_MCP_Tool_Arg.int_default(
    "start_line", "", PIDE_MCP_Tool_Util.default_start_line, minimum = Some(1))
  val opt_end_line_arg = PIDE_MCP_Tool_Arg.opt_int(
    "end_line", "Default: end of file.", minimum = Some(1))

  def input_schema(args: List[PIDE_MCP_Tool_Arg[?]]): JSON.Object.T =
    JSON_Object("type" -> "object", "properties" -> JSON_Object(args.map(_.entry))) ++
    JSON.optional("required" -> proper_list(args.filter(_.required).map(_.name)))
}
