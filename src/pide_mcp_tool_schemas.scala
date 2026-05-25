/*  Title:      PIDE_MCP/pide_mcp_tool_schemas.scala
    Author:     Kevin Kappelmann

Schema definitions for all PIDE MCP tools.
*/

package isabelle.pide.mcp

case class Tool_Def(description: String, input_schema: Map[String, Any],
  annotations: Option[Map[String, Boolean]] = None)

object PIDE_MCP_Tool_Schemas {
  val create_scratch: String = "create_scratch"
  val edit: String = "edit"
  val find_origins: String = "find_origins"
  val get_state: String = "get_state"
  val list_loaded_theories: String = "list_loaded_theories"
  val read: String = "read"

  private val implicit_load_theory = "Implicitly loads the theory if required."
  private val implicit_reload_file = "Implicitly (re)loads the file."

  private val path_prop =
    "path" -> Map[String, Any]("type" -> "string", "description" -> "Path to the file")
  private val thy_path_prop =
    "path" -> Map[String, Any]("type" -> "string",
      "description" -> s"Path to the ${PIDE_MCP_Util.theory_suffix} file")
  private val start_line_prop =
    "start_line" -> Map[String, Any]("type" -> "integer",
      "description" -> "First line to include.", "minimum" -> 1)
  private val start_line_opt_prop =
    "start_line" -> Map[String, Any]("type" -> "integer",
      "description" -> "First line to include.", "minimum" -> 1, "default" -> 1)
  private val end_line_opt_prop =
    "end_line" -> Map[String, Any]("type" -> "integer",
      "description" -> "Last line to include (default: end of file).", "minimum" -> 1)

  val all: List[(String, Tool_Def)] = List(
    create_scratch -> Tool_Def(
      description = "Create a temporary theory for experimentation that does not interfere with user accessible files. "
        + "Use this whenever you think you need to do iterative developments or when you want to find and explore theorems, syntax, concepts, commands, ML code, etc. Write back final results to files accessible to the user."
        + "Temporary files are cleaned up when the MCP session ends.",
      input_schema = Map("type" -> "object", "properties" -> Map(
        "name_suffix" -> Map("type" -> "string",
          "description" -> "Label to identify this scratch theory (auto-generated if omitted)"),
        "imports" -> Map("type" -> "array", "items" -> Map("type" -> "string"),
          "description" -> "Isabelle theories to import",
          "default" -> PIDE_MCP_Tool_Handlers.default_imports)
      ))
    ),
    edit -> Tool_Def(
      description = "Edit a file. " + implicit_reload_file,
      input_schema = Map("type" -> "object", "properties" -> Map(
        path_prop,
        "content" -> Map("type" -> "string", "description" -> "New text to write"),
        start_line_opt_prop,
        end_line_opt_prop,
        "old_content" -> Map("type" -> "string",
          "description" -> "Current text at the target lines. Must match exactly or the edit is rejected.")
      ), "required" -> List("path", "content", "old_content")),
      annotations = Some(Map("destructiveHint" -> true))
    ),
    find_origins -> Tool_Def(
      description = "Look up where entities (constants, theorems, commands, methods,...) are defined, i.e. find their origin. "
        + "Useful to learn more about concepts that you are uncertain about. "
        + implicit_load_theory,
      input_schema = Map("type" -> "object", "properties" -> Map(
        thy_path_prop,
        start_line_prop,
        end_line_opt_prop
      ), "required" -> List("path", "start_line")),
      annotations = Some(Map("readOnlyHint" -> true))
    ),
    get_state -> Tool_Def(
      description = "Inspect the state of a (range in a) theory: goals, variables, errors, warnings, etc. "
        + "**Use this frequently to check if you are making progress, what is left to be done, and importantly, if certain commands are still processing, potentially even looping.** "
        + implicit_load_theory,
      input_schema = Map("type" -> "object", "properties" -> Map(
        thy_path_prop,
        start_line_opt_prop,
        end_line_opt_prop
      ), "required" -> List("path")),
      annotations = Some(Map("readOnlyHint" -> true))
    ),
    list_loaded_theories -> Tool_Def(
      description = "List all loaded theories from the session.",
      input_schema = Map("type" -> "object", "properties" -> Map(
        "include_scratch" -> Map("type" -> "boolean",
          "description" -> "Include scratch theories",
          "default" -> false)
      )),
      annotations = Some(Map("readOnlyHint" -> true))
    ),
    read -> Tool_Def(
      description = "Read line-numbered content (for a given range). " + implicit_reload_file,
      input_schema = Map("type" -> "object", "properties" -> Map(
        path_prop,
        start_line_opt_prop,
        end_line_opt_prop
      ), "required" -> List("path")),
      annotations = Some(Map("readOnlyHint" -> true))
    )
  )
}
