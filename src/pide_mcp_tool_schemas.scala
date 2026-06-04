/*  Title:      PIDE_MCP/pide_mcp_tool_schemas.scala
    Author:     Kevin Kappelmann

Schema definitions for all PIDE MCP tools.
*/

package isabelle.pide.mcp

import isabelle._


sealed case class Tool_Def(description: String, input_schema: JSON.Object.T,
  annotations: Option[JSON.Object.T] = None)

object PIDE_MCP_Tool_Schemas {
  val create_file: String = "create_file"
  val create_scratch: String = "create_scratch"
  val edit: String = "edit"
  val find_entities: String = "find_entities"
  val get_state: String = "get_state"
  val list_loaded_theories: String = "list_loaded_theories"
  val list_session_directories: String = "list_session_directories"
  val read: String = "read"

  private val implicit_load_file = "Implicitly loads the file if required."
  private val implicit_reload_file = "Implicitly (re)loads the file."

  private val origin_prop =
    "origin" -> JSON.Object("type" -> "string",
      "description" -> "Session-qualified theory name (e.g. \"HOL.Nat\") or file path (e.g. \"foo.ML\")")
  private val start_line_prop =
    "start_line" -> JSON.Object("type" -> "integer",
      "description" -> "First line to include.", "minimum" -> 1)
  private val start_line_opt_prop =
    "start_line" -> JSON.Object("type" -> "integer",
      "description" -> "First line to include.", "minimum" -> 1, "default" -> 1)
  private val end_line_opt_prop =
    "end_line" -> JSON.Object("type" -> "integer",
      "description" -> "Last line to include (default: end of file).", "minimum" -> 1)

  val all: List[(String, Tool_Def)] = List(
    create_file -> Tool_Def(
      description = "Create an empty file at the given path. "
        + "Creates missing parent directories if necessary. "
        + "If the file already exists, it does nothing. "
        + "**Do not use this for temporary files/exploration. Use create_scratch instead.**",
      input_schema = JSON.Object("type" -> "object", "properties" -> JSON.Object(
        "path" -> JSON.Object("type" -> "string",
          "description" -> "File path to create (e.g. \"./Algebra/algebra_simp.ML\" or \"/path/to/My_Theory.thy\")")
      ), "required" -> List("path"))
    ),
    create_scratch -> Tool_Def(
      description = "Create a temporary file for experimentation that does not interfere with user accessible files. "
        + "Use this whenever you think you need to do iterative developments or when you want to find and explore theorems, syntax, concepts, commands, ML code, etc. Write back final results to files accessible to the user. "
        + "Temporary files are cleaned up when the MCP session ends.",
      input_schema = JSON.Object("type" -> "object", "properties" -> JSON.Object(
        "name_suffix" -> JSON.Object("type" -> "string",
          "description" -> "Label to identify the scratch file (auto-generated if omitted)"),
        "extension" -> JSON.Object("type" -> "string",
          "description" -> "File extension (typically \".thy\" or \".ML\")")
      ))
    ),
    edit -> Tool_Def(
      description = "Edit a file by replacing a line range. "
        + "Omit both start_line and end_line for a full-file replace. "
        + "Give start_line only to replace from there to the end. "
        + "Give both to replace exactly that range. "
        + "Note: base session files are static and cannot be edited. "
        + implicit_reload_file,
      input_schema = JSON.Object("type" -> "object", "properties" -> JSON.Object(
        origin_prop,
        "text" -> JSON.Object("type" -> "string", "description" -> "New text to write"),
        start_line_opt_prop,
        end_line_opt_prop,
        "old_text" -> JSON.Object("type" -> "string",
          "description" -> "Current text at the target lines. **Must match the current content of those lines modulo trailing whitespace, or the edit is rejected.**")
      ), "required" -> List("origin", "text", "old_text")),
      annotations = Some(JSON.Object("destructiveHint" -> true))
    ),
    find_entities -> Tool_Def(
      description = "Look up what entities (constants, theorems, commands, methods, ML terms,...) in a file are defined where and how, i.e. find their origin with preview snippets. "
        + "Useful to learn more about concepts that you are uncertain about or for which you need more information (e.g. the actual theorem statement) and to study the content of a file. To get all entities defined in a file, select the file with full range and also pass it in filter_origins."
        + implicit_load_file
        + " Implicitly (re)loads theories containing source snippets if required.",
      input_schema = JSON.Object("type" -> "object", "properties" -> JSON.Object(
        origin_prop,
        start_line_prop,
        end_line_opt_prop,
        "snippet_lines" -> JSON.Object("type" -> "integer",
          "description" -> "Number of context lines per definition source snippet (use 0 to omit).",
          "minimum" -> 0, "default" -> 3),
        "filter_origins" -> JSON.Object("type" -> "array", "items" -> JSON.Object("type" -> "string"),
          "description" -> "List of origins (session-qualified theory names or file paths). If provided, only returns entities whose definition originates from one of these. Use this if you want to explore the entities defined by a given origin.")
      ), "required" -> List("origin", "start_line")),
      annotations = Some(JSON.Object("readOnlyHint" -> true))
    ),
    get_state -> Tool_Def(
      description = "Inspect the state of a (range in a) file: goals, variables, errors, warnings, etc. "
        + "Returns a summary (number of errors, warnings, number of commands running, finished, etc., total timing,...) and details for all commands selected. "
        + "**Use this frequently to check if you are making progress, what is left to be done, and importantly, if certain commands are still processing, potentially even looping.** "
        + "For files, the response has a single command entry derived from the command that loaded it in the respective theory. "
        + implicit_load_file,
      input_schema = JSON.Object("type" -> "object", "properties" -> JSON.Object(
        origin_prop,
        start_line_opt_prop,
        end_line_opt_prop,
        "include_types" -> JSON.Object("type" -> "boolean",
          "description" -> "Include types (Isabelle and ML) for variables and constants.",
          "default" -> false),
        "include_facts" -> JSON.Object("type" -> "boolean",
          "description" -> "Include facts/theorems used in range.",
          "default" -> false),
        "include_infos" -> JSON.Object("type" -> "boolean",
          "description" -> "Include writeln and information output in the state. This can get large, but often contains useful information. Avoid using it for large ranges if possible.",
          "default" -> false),
        "include_full_markup" -> JSON.Object("type" -> "boolean",
          "description" -> "Include full PIDE markup information. This gets very large - **use only sparingly and very targeted to get local details**.",
          "default" -> false),
        "commands_limit" -> JSON.Object("type" -> "integer",
          "description" -> ("Maximum number of commands to return. Set to 0 if you only want to get summary statistics. "
            + "Note that the returned summary statistics will still include all commands, even the truncated ones."),
          "default" -> PIDE_MCP_Tool_Handlers.commands_limit),
      ), "required" -> List("origin")),
      annotations = Some(JSON.Object("readOnlyHint" -> true))
    ),
    list_loaded_theories -> Tool_Def(
      description = "List all loaded theories from the session.",
      input_schema = JSON.Object("type" -> "object", "properties" -> JSON.Object(
        "include_scratch" -> JSON.Object("type" -> "boolean",
          "description" -> "Include scratch theories",
          "default" -> false)
      )),
      annotations = Some(JSON.Object("readOnlyHint" -> true))
    ),
    list_session_directories -> Tool_Def(
      description = "List all directories from which Isabelle attempts to load files. "
        + "This includes directories specified via the -d option and component directories with ROOT and ROOTS files. "
        + "Use this when you want to learn what libraries you have access to and where they are located. "
        + "Note that session names, which you have to use for session-qualified loading of (library) theories, are stored in the ROOT files of these directories.",
      input_schema = JSON.Object("type" -> "object", "properties" -> JSON.Object.empty),
      annotations = Some(JSON.Object("readOnlyHint" -> true))
    ),
    read -> Tool_Def(
      description = "Read line-numbered content (for a given range). Use this to get the file's content. Also use it to re-synchronise the file's disk content with PIDE if you experience discrepancies between the PIDE state and the external disk state (e.g. due to external edits). " + implicit_reload_file,
      input_schema = JSON.Object("type" -> "object", "properties" -> JSON.Object(
        origin_prop,
        start_line_opt_prop,
        end_line_opt_prop
      ), "required" -> List("origin")),
      annotations = Some(JSON.Object("readOnlyHint" -> true))
    )
  )
}
