/*  Title:      PIDE_MCP/tool_definitions.scala
    Author:     Kevin Kappelmann

Schema definitions for all MCP tools.
*/

package isabelle.pide.mcp

case class Tool_Def(description: String, input_schema: Map[String, Any],
  annotations: Option[Map[String, Boolean]] = None)

object Tool_Definitions
{
  val all: List[(String, Tool_Def)] = List(
    "list_loaded_theories" -> Tool_Def(
      description = "List theories currently loaded in the PIDE session, separated into static (from session build) and dynamic (loaded at runtime) and scratch theories (optional).",
      input_schema = Map("type" -> "object", "properties" -> Map(
        "include_scratch" -> Map("type" -> "boolean", "description" -> "Include temporary scratch theories",
          "default" -> false)
      )),
      annotations = Some(Map("readOnlyHint" -> true))
    ),
    "read_theory" -> Tool_Def(
      description = "Read an Isabelle theory file. Output lines are numbered.",
      input_schema = Map("type" -> "object", "properties" -> Map(
        "path" -> Map("type" -> "string", "description" -> "Absolute path to the .thy file"),
        "start_line" -> Map("type" -> "integer", "description" -> "Start line", "minimum" -> 1),
        "end_line" -> Map("type" -> "integer", "description" -> "End line (inclusive)", "minimum" -> 1)
      ), "required" -> List("path")),
      annotations = Some(Map("readOnlyHint" -> true))
    ),
    "read_ml_file" -> Tool_Def(
      description = "Read an Isabelle/ML source file. Output lines are numbered.",
      input_schema = Map("type" -> "object", "properties" -> Map(
        "path" -> Map("type" -> "string", "description" -> "Absolute path to the .ML file"),
        "start_line" -> Map("type" -> "integer", "description" -> "Start line", "minimum" -> 1),
        "end_line" -> Map("type" -> "integer", "description" -> "End line (inclusive)", "minimum" -> 1)
      ), "required" -> List("path")),
      annotations = Some(Map("readOnlyHint" -> true))
    ),
    "edit_theory" -> Tool_Def(
      description = "Edit an Isabelle theory file. Replaces lines start_line to end_line (inclusive) with the given content. Omitting start_line overwrites the entire file. The theory is re-checked by PIDE after each edit.",
      input_schema = Map("type" -> "object", "properties" -> Map(
        "path" -> Map("type" -> "string", "description" -> "Absolute path to the .thy file"),
        "content" -> Map("type" -> "string", "description" -> "Replacement text"),
        "start_line" -> Map("type" -> "integer", "description" -> "First line to replace", "minimum" -> 1),
        "end_line" -> Map("type" -> "integer", "description" -> "Last line to replace (inclusive, defaults to start_line)", "minimum" -> 1),
        "old_content" -> Map("type" -> "string", "description" -> "Expected content at target lines (mandatory for partial edits). Must match exactly or the edit is rejected."),
        "timeout_secs" -> Map("type" -> "integer", "description" -> "Timeout in seconds for PIDE processing",
          "default" -> PIDE_Session.default_timeout_secs)
      ), "required" -> List("path", "content")),
      annotations = Some(Map("destructiveHint" -> true))
    ),
    "edit_ml_file" -> Tool_Def(
      description = "Edit an Isabelle/ML source file. Replaces lines start_line to end_line (inclusive) with the given content. The parent theory (which loads this file via ML_file) is re-checked after each edit.",
      input_schema = Map("type" -> "object", "properties" -> Map(
        "path" -> Map("type" -> "string", "description" -> "Absolute path to the .ML file"),
        "content" -> Map("type" -> "string", "description" -> "Replacement text"),
        "parent_theory" -> Map("type" -> "string", "description" -> "Path to the .thy file that loads this ML file via ML_file"),
        "start_line" -> Map("type" -> "integer", "description" -> "First line to replace", "minimum" -> 1),
        "end_line" -> Map("type" -> "integer", "description" -> "Last line to replace (inclusive, defaults to start_line)", "minimum" -> 1),
        "old_content" -> Map("type" -> "string", "description" -> "Expected content at target lines (mandatory for partial edits). Must match exactly or the edit is rejected."),
        "timeout_secs" -> Map("type" -> "integer", "description" -> "Timeout in seconds for PIDE processing",
          "default" -> PIDE_Session.default_timeout_secs)
      ), "required" -> List("path", "content", "parent_theory")),
      annotations = Some(Map("destructiveHint" -> true))
    ),
    "get_state" -> Tool_Def(
      description = "Query the PIDE state at a given line: command source, processing status, goal state, error/warning messages, variables in scope, etc. Without a line number, returns diagnostics for the whole file.",
      input_schema = Map("type" -> "object", "properties" -> Map(
        "path" -> Map("type" -> "string", "description" -> "Absolute path to the .thy file"),
        "line" -> Map("type" -> "integer", "description" -> "Line number", "minimum" -> 1),
        "timeout_secs" -> Map("type" -> "integer", "description" -> "Timeout in seconds for loading theory",
          "default" -> PIDE_Session.default_timeout_secs)
      ), "required" -> List("path")),
      annotations = Some(Map("readOnlyHint" -> true))
    ),
    "list_entities" -> Tool_Def(
      description = "List named entities (lemmas, definitions, constants, etc.) defined in a theory, with line numbers.",
      input_schema = Map("type" -> "object", "properties" -> Map(
        "path" -> Map("type" -> "string", "description" -> "Absolute path to the .thy file")
      ), "required" -> List("path")),
      annotations = Some(Map("readOnlyHint" -> true))
    ),
    "find_definition" -> Tool_Def(
      description = "Resolve the definition site of terms used at a given line, returning its origin.",
      input_schema = Map("type" -> "object", "properties" -> Map(
        "path" -> Map("type" -> "string", "description" -> "Path to the .thy file containing the usage"),
        "line" -> Map("type" -> "integer", "description" -> "Line number of the usage", "minimum" -> 1),
        "term_name" -> Map("type" -> "string", "description" -> "Filter to a specific entity name")
      ), "required" -> List("path", "line")),
      annotations = Some(Map("readOnlyHint" -> true))
    ),
    "create_scratch" -> Tool_Def(
      description = "Create a temporary scratch theory file for exploration. The file is placed in a temporary directory and cleaned up when the session stops. Use read_theory, edit_theory, check_theory, and other tools to interact with it.",
      input_schema = Map("type" -> "object", "properties" -> Map(
        "name_suffix" -> Map("type" -> "string", "description" -> "Optional suffix for the theory name (auto-generated if omitted)")
      )),
      annotations = Some(Map("readOnlyHint" -> false, "destructiveHint" -> false))
    ),
    "check_theory" -> Tool_Def(
      description = "Re-check a theory via use_theories. Without a path, re-checks all loaded theories.",
      input_schema = Map("type" -> "object", "properties" -> Map(
        "path" -> Map("type" -> "string", "description" -> "Absolute path to the .thy file"),
        "timeout_secs" -> Map("type" -> "integer", "description" -> "Timeout in seconds for PIDE processing",
          "default" -> PIDE_Session.default_timeout_secs)
      )),
      annotations = Some(Map("readOnlyHint" -> true))
    )
  )
}
