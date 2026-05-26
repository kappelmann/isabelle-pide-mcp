# Summary

## Goal
- Build and maintain an Isabelle PIDE MCP server (`isabelle_pide_mcp`) providing interactive proof development tools via the Model Context Protocol.

## Constraints & Preferences
- `.thy` / `.ML` only for Isabelle source files — suffix constants `PIDE_MCP_Util.theory_suffix` / `.ml_suffix`
- `load` is private; only `load_theory(name, reload=true)` and `load_file(name)` are public
- `load_theory` with `reload=false` checks `loaded_theories` first and skips if already loaded (no disk reload)
- `load_snapshot` uses `reload=false` — only `read` and `edit` reload from disk
- `loaded_theories` uses `history.tip.version.join` to include in-progress theories
- `start_line` optional -> defaults to line 1 via `PIDE_MCP_Util.resolve_lines`; `end_line` optional -> defaults to end of file (consistent across all handlers)
- Error-possible utility functions return `Exn.Result[...]` explicitly
- Namespace: `PIDE_MCP_Util` for shared infrastructure, `PIDE_MCP_Tool_Handlers` for tool-specific config
- Schemas use `"default" -> value` for defaults representable in JSON Schema; dynamic defaults (end-of-file) described in description text only — no duplication
- All other constraints unchanged (no `Either`, `Exn.Result` throughout, `Exn.capture` + `error()`, no timeouts, etc.)

## Key Decisions
- `PIDE_MCP_Util.resolve_lines` validates + resolves defaults in one call, returns `Exn.Result[(Int, Int)]`
- Handlers return `JSON.T` (any JSON value) — `get_state` returns a flat object with summary fields + `commands` list; `read`, `find_entities` return bare lists; `edit` returns `{status, description, file_content}`
- `edit_load_file` returns `(String, Boolean)` — the Boolean signals whether a load was triggered
- Schema properties defined as shared `vals` (`path_prop`, `start_line_prop`, etc.) to eliminate duplication
- Helper methods placed above their callers for readability (Scala allows forward refs, but code reads top-to-bottom)
- `submit_incremental_edit` uses `Text.Perspective.empty` (no visible commands) — avoids triggering `reparse_spans` in `text_edit`
- `edit_load_file` calls `_session.update` directly for `.thy` files (no `load_theory` fallback); `load_file` is used for `.ML` files
- `definition_entry` takes `origin_theories: Option[List[String]]` — resolves `def_file` once via `source_file`, checks against precomputed `origin_set`; entries with `Item_Def_Id` pass through
- `find_command_position` returns `Option[Line.Node_Position]` — uses `pos.line1` (1-based) for consistency with `Item_Def_File.def_line`

## Tools (6 total)
- **`create_scratch`** — temporary theory for experimentation; `name_suffix`, `imports`
- **`edit`** — full-file edit with old_content matching; implicit reload
- **`find_entities`** — look up entity definitions (constants, theorems, commands, methods, ...) in a range; optional `origin_theories` list filters results by definition file
- **`get_state`** — inspect theory state; returns a flat object with summary fields followed by the `commands` list. The summary includes `errors` (count), `warnings` (count), `commands_finished`, `commands_failed`, `commands_running`, `commands_unprocessed`, `commands_canceled` (all five states always present), and `total_timing_ms` (sum across commands). An AI agent can use these to quickly assess the health of a range: high `unprocessed` suggests waiting, `failed` means broken commands to investigate, `running` means still processing, and `total_timing_ms` flags expensive commands (potential loops). Options: `include_types`, `include_facts`, `include_infos`, `include_full_markup`
- **`list_loaded_theories`** — static, dynamic, and optional scratch theories
- **`read`** — read line-numbered file content; implicit reload

## Critical Context
- `Document.State.snapshot` uses `recent_stable` (most recent change where both version AND all commands are assigned). Snapshot `node.source` does NOT apply pending edits — reads from stable version's node directly.
- `Nodes.apply(name)` auto-creates `Node.empty` via `Nodes.init(graph, name).get_node(name)` — NEVER throws, but `source` is `""` for missing nodes. `Nodes.domain` contains only explicitly added nodes.
- `session.update` -> `manager.send_wait` -> `handle_raw_edits` creates a `Future.promise[Version]` and sends to async `change_parser`. `send_wait` blocks only until the history entry is created, NOT until version is assigned.
- After `load_theory`: `history.tip.version` is unfulfilled promise; `recent_stable` is previous change (no new node); `snap.node.source` is `""`. Waiting for `tip.version.join` only helps when directly accessing the tip version.
- `session.read` does `File.read(name.path)` — works for existing files even when snapshot is stale. Correct pattern for getting source text: `File.read(name.path)`.
- `submit_incremental_edit` uses `Text.Perspective.empty` — `text_edit` for `Perspective` with empty perspective computes `is_visible = Set.empty`, so `commands1 = commands` (no `reparse_spans`). Only perspective metadata is updated.

## Relevant Files
- `src/pide_mcp.scala`: Isabelle tool entry point (`isabelle pide_mcp`)
- `src/pide_mcp_server.scala`: JSON-RPC loop; `PIDE_MCP_Server` + `PIDE_MCP_Config`
- `src/pide_mcp_session.scala`: `PIDE_MCP_Session` — session lifecycle, `edit_load_file` (returns changed flag), `compute_file_edit`
- `src/pide_mcp_tool_handlers.scala`: 6 tool handlers; all return `Exn.Result[JSON.T]`; helpers placed above their callers
- `src/pide_mcp_tool_schemas.scala`: 6 tool schemas; shared `path_prop`, `thy_path_prop`, `start_line_prop`, `start_line_opt_prop`, `end_line_opt_prop`, `implicit_load_theory`, `implicit_reload_file`
- `src/pide_mcp_util.scala`: `resolve_lines` (validates + resolves defaults), `numbered_lines`/`numbered_lines_range` (returns `List[JSON.Object.T]` with `line_number` and `text`)
- `test/quick_test.py`: 16 fast tests
- `test/test_runner.py`: integration tests (6/10 pass; 4 ML timing-related failures)
- `test/find_entities_stress.py`: stress tests for entity lookups
