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
    "list_tracked_theories" -> Tool_Def(
      description = "List theories currently loaded in the PIDE session.",
      input_schema = Map("type" -> "object", "properties" -> Map.empty),
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
      description = "Resolve the definition site of terms used at a given line via PIDE markup. Returns file path, line number, and source snippet for each resolved entity. Works for library definitions; for project-local entities use list_entities.",
      input_schema = Map("type" -> "object", "properties" -> Map(
        "path" -> Map("type" -> "string", "description" -> "Path to the .thy file containing the usage"),
        "line" -> Map("type" -> "integer", "description" -> "Line number of the usage", "minimum" -> 1),
        "term_name" -> Map("type" -> "string", "description" -> "Filter to a specific entity name")
      ), "required" -> List("path", "line")),
      annotations = Some(Map("readOnlyHint" -> true))
    ),
    "scratch" -> Tool_Def(
      description = "Evaluate Isabelle source text in a scratch theory context. Creates a new theory importing the specified parent. Returns theory_name and theory_path for incremental development: use edit_theory with the returned theory_path to extend the proof/development. Final changes should eventually be played back to the desired original theory.",
      input_schema = Map("type" -> "object", "properties" -> Map(
        "content" -> Map("type" -> "string", "description" -> "Isabelle source text (lemmas, definitions, find_theorems, sledgehammer, try, etc.)"),
        "imports" -> Map("type" -> "string", "description" -> "Parent theories (e.g. Main, Complex_Main, HOL-Library.Multiset)"),
        "timeout_secs" -> Map("type" -> "integer", "description" -> "Timeout in seconds for query execution",
          "default" -> PIDE_Session.default_timeout_secs)
      ), "required" -> List("content", "imports")),
      annotations = Some(Map("readOnlyHint" -> false, "destructiveHint" -> false))
    ),
    "check_theory" -> Tool_Def(
      description = "Re-check a theory via use_theories. Without a path, re-checks all tracked theories.",
      input_schema = Map("type" -> "object", "properties" -> Map(
        "path" -> Map("type" -> "string", "description" -> "Absolute path to the .thy file"),
        "timeout_secs" -> Map("type" -> "integer", "description" -> "Timeout in seconds for PIDE processing",
          "default" -> PIDE_Session.default_timeout_secs)
      )),
      annotations = Some(Map("readOnlyHint" -> true))
    )
  )
}
