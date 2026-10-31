/*  Title:      MCP/src/pide_mcp_tool_schema.scala
    Author:     Kevin Kappelmann

Shared JSON-Schema fragments for MCP tools.
*/

package isabelle.pide.mcp

import isabelle._

object PIDE_MCP_Tool_Schema {
  val implicit_load_file: String = "Loads file if needed."
  val implicit_reload_file: String = "(Re)loads file."
  val range_perspective: String = "Moves PIDE perspective to range."
  val no_processing_wait: String = "Does not wait for commands to terminate."
  val session_restart: String = "Use only when required: " +
    "unresponsiveness or ROOT or base session updates."

  val running_session_arg = JSON_Schema.Arg.optional(
    "session", "Running session id. Default: unique running one.",
    JSON_Schema.Format.string)
  val running_sessions_arg = JSON_Schema.Arg.optional(
    "sessions", "Running session ids. Default: all running ones.",
    JSON_Schema.Format.list(JSON_Schema.Format.string))
  val origin_arg = JSON_Schema.Arg.required("origin",
    s"Session-qualified theory (e.g. ${quote("HOL.Nat")}) or file path.",
    JSON_Schema.Format.string)
  val start_line_arg = JSON_Schema.Arg.required(
    "start_line", "", JSON_Schema.Format.int(minimum = Some(1)))
  val start_line_default_arg = JSON_Schema.Arg.default(
    "start_line", "", JSON_Schema.Format.int(minimum = Some(1)), 1)
  val opt_end_line_arg = JSON_Schema.Arg.optional(
    "end_line", "Default: end of file.", JSON_Schema.Format.int(minimum = Some(1)))
}
