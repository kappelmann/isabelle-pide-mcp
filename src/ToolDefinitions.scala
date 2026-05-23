/*  Title:      PIDE_MCP/ToolDefinitions.scala
    Author:     Kevin Kappelmann

Schema definitions for all MCP tools.
*/

package isabelle.pide.mcp

case class ToolDef(description: String, inputSchema: Map[String, Any],
  annotations: Option[Map[String, Boolean]] = None)

object ToolDefinitions
{
  val all: List[(String, ToolDef)] = List(
    "list_tracked_theories" -> ToolDef(
      description = "List theories currently loaded in the PIDE session.",
      inputSchema = Map("type" -> "object", "properties" -> Map.empty),
      annotations = Some(Map(MCPConfig.readOnlyHint -> true))
    ),
    "read_theory" -> ToolDef(
      description = "Read an Isabelle theory file. Output lines are numbered.",
      inputSchema = Map("type" -> "object", "properties" -> Map(
        "path" -> Map("type" -> "string", "description" -> "Absolute path to the .thy file"),
        "start_line" -> Map("type" -> "integer", "description" -> "Start line (1-indexed)", "minimum" -> 1),
        "end_line" -> Map("type" -> "integer", "description" -> "End line (inclusive)", "minimum" -> 1)
      ), "required" -> List("path")),
      annotations = Some(Map(MCPConfig.readOnlyHint -> true))
    ),
    "read_ml_file" -> ToolDef(
      description = "Read an Isabelle/ML source file. Output lines are numbered.",
      inputSchema = Map("type" -> "object", "properties" -> Map(
        "path" -> Map("type" -> "string", "description" -> "Absolute path to the .ML file"),
        "start_line" -> Map("type" -> "integer", "description" -> "Start line (1-indexed)", "minimum" -> 1),
        "end_line" -> Map("type" -> "integer", "description" -> "End line (inclusive)", "minimum" -> 1)
      ), "required" -> List("path")),
      annotations = Some(Map(MCPConfig.readOnlyHint -> true))
    ),
    "edit_theory" -> ToolDef(
      description = "Edit an Isabelle theory file. Replaces lines start_line to end_line (inclusive) with the given content. Omitting start_line overwrites the entire file. The theory is re-checked by PIDE after each edit.",
      inputSchema = Map("type" -> "object", "properties" -> Map(
        "path" -> Map("type" -> "string", "description" -> "Absolute path to the .thy file"),
        "content" -> Map("type" -> "string", "description" -> "Replacement text"),
        "start_line" -> Map("type" -> "integer", "description" -> "First line to replace (1-indexed)", "minimum" -> 1),
        "end_line" -> Map("type" -> "integer", "description" -> "Last line to replace (inclusive, defaults to start_line)", "minimum" -> 1),
        "old_content" -> Map("type" -> "string", "description" -> "Expected content at target lines (mandatory for partial edits). Must match exactly or the edit is rejected."),
        "timeout_secs" -> Map("type" -> "integer", "description" -> "Timeout in seconds for PIDE processing",
          "default" -> PIDESession.default_timeout_secs)
      ), "required" -> List("path", "content")),
      annotations = Some(Map(MCPConfig.destructiveHint -> true))
    ),
    "edit_ml_file" -> ToolDef(
      description = "Edit an Isabelle/ML source file. Replaces lines start_line to end_line (inclusive) with the given content. The parent theory (which loads this file via ML_file) is re-checked after each edit.",
      inputSchema = Map("type" -> "object", "properties" -> Map(
        "path" -> Map("type" -> "string", "description" -> "Absolute path to the .ML file"),
        "content" -> Map("type" -> "string", "description" -> "Replacement text"),
        "parent_theory" -> Map("type" -> "string", "description" -> "Path to the .thy file that loads this ML file via ML_file"),
        "start_line" -> Map("type" -> "integer", "description" -> "First line to replace (1-indexed)", "minimum" -> 1),
        "end_line" -> Map("type" -> "integer", "description" -> "Last line to replace (inclusive, defaults to start_line)", "minimum" -> 1),
        "old_content" -> Map("type" -> "string", "description" -> "Expected content at target lines (mandatory for partial edits). Must match exactly or the edit is rejected."),
        "timeout_secs" -> Map("type" -> "integer", "description" -> "Timeout in seconds for PIDE processing",
          "default" -> PIDESession.default_timeout_secs)
      ), "required" -> List("path", "content", "parent_theory")),
      annotations = Some(Map(MCPConfig.destructiveHint -> true))
    ),
    "get_state" -> ToolDef(
      description = "Query the PIDE state at a given line: command source, processing status, goal state, error/warning messages, variables in scope, etc. Without a line number, returns diagnostics for the whole file.",
      inputSchema = Map("type" -> "object", "properties" -> Map(
        "path" -> Map("type" -> "string", "description" -> "Absolute path to the .thy file"),
        "line" -> Map("type" -> "integer", "description" -> "Line number (1-indexed)", "minimum" -> 1),
        "timeout_secs" -> Map("type" -> "integer", "description" -> "Timeout in seconds for loading theory",
          "default" -> PIDESession.default_timeout_secs)
      ), "required" -> List("path")),
      annotations = Some(Map(MCPConfig.readOnlyHint -> true))
    ),
    "list_entities" -> ToolDef(
      description = "List named entities (lemmas, definitions, constants, etc.) defined in a theory, with line numbers.",
      inputSchema = Map("type" -> "object", "properties" -> Map(
        "path" -> Map("type" -> "string", "description" -> "Absolute path to the .thy file")
      ), "required" -> List("path")),
      annotations = Some(Map(MCPConfig.readOnlyHint -> true))
    ),
    "find_definition" -> ToolDef(
      description = "Resolve the definition site of terms used at a given line via PIDE markup. Returns file path, line number, and source snippet for each resolved entity. Works for library definitions; for project-local entities use list_entities.",
      inputSchema = Map("type" -> "object", "properties" -> Map(
        "path" -> Map("type" -> "string", "description" -> "Path to the .thy file containing the usage"),
        "line" -> Map("type" -> "integer", "description" -> "Line number of the usage", "minimum" -> 1),
        "term_name" -> Map("type" -> "string", "description" -> "Filter to a specific entity name")
      ), "required" -> List("path", "line")),
      annotations = Some(Map(MCPConfig.readOnlyHint -> true))
    ),
    "scratch" -> ToolDef(
      description = "Evaluate Isabelle source text in a scratch theory context. Creates a new theory importing the specified parent. Returns theory_name and theory_path for incremental development: use edit_theory with the returned theory_path to extend the proof/development. Final changes should eventually be played back to the desired original theory.",
      inputSchema = Map("type" -> "object", "properties" -> Map(
        "content" -> Map("type" -> "string", "description" -> "Isabelle source text (lemmas, definitions, find_theorems, sledgehammer, try, etc.)"),
        "imports" -> Map("type" -> "string", "description" -> "Parent theories (e.g. Main, Complex_Main, HOL-Library.Multiset)"),
        "timeout_secs" -> Map("type" -> "integer", "description" -> "Timeout in seconds for query execution",
          "default" -> PIDESession.default_timeout_secs)
      ), "required" -> List("content", "imports")),
      annotations = Some(Map(MCPConfig.readOnlyHint -> false, MCPConfig.destructiveHint -> false))
    ),
    "check_theory" -> ToolDef(
      description = "Re-check a theory via use_theories. Without a path, re-checks all tracked theories.",
      inputSchema = Map("type" -> "object", "properties" -> Map(
        "path" -> Map("type" -> "string", "description" -> "Absolute path to the .thy file"),
        "timeout_secs" -> Map("type" -> "integer", "description" -> "Timeout in seconds for PIDE processing",
          "default" -> PIDESession.default_timeout_secs)
      )),
      annotations = Some(Map(MCPConfig.readOnlyHint -> true))
    )
  )
}
