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

  val origin_prop =
    "origin" -> JSON_Object("type" -> "string",
      "description" -> "Session-qualified theory name (e.g. \"HOL.Nat\") or file path (e.g. \"foo.ML\").")
  val start_line_prop =
    "start_line" -> JSON_Object("type" -> "integer",
      "description" -> "First line to include.", "minimum" -> 1)
  val opt_start_line_prop =
    "start_line" -> JSON_Object("type" -> "integer",
      "description" -> "First line to include.", "minimum" -> 1, "default" -> 1)
  val opt_end_line_prop =
    "end_line" -> JSON_Object("type" -> "integer",
      "description" -> "Last line to include (default: end of file).", "minimum" -> 1)
}
