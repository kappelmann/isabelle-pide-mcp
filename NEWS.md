PIDE MCP NEWS -- history of user-relevant changes
=================================================

New in this PIDE MCP version
----------------------------

* Update to Isabelle version as specified in [ISABELLE\_VERSION](./ISABELLE_VERSION)
* Failing tool calls now return a result with `isError` instead of a JSON-RPC error. INCOMPATIBILITY.
* Tool results are now returned as `structuredContent` with `content=[]`. INCOMPATIBILITY.
* New options `pide_mcp_await_option_sessions` and `pide_mcp_exit_on_failed_option_sessions` control whether sessions given as command-line options are prepared before the MCP server starts
  and whether the MCP server stops if one such sessions fails to start.
* Command-line option `-L` now logs to the file in addition to the console. INCOMPATIBILITY.
* New command-line option `-w` logs MCP requests and responses while `-v` no longer does. INCOMPATIBILITY.
* Support of `notifications/cancelled` for long-running tool calls.
* Long-running operations now report progress, configurable with options `pide_mcp_session_progress_delay` 
  and `pide_mcp_progress_threshold`.
* New option `pide_mcp_tools` to enable/disable tools at startup time.
* New tool `get_session_state` shows session information, status, progress, and resource statistics, superseding tools `list_loaded_theories` and `list_session_directories`. INCOMPATIBILITY.
  Runtime statistics are reported as averages and maxima over a sampling window,
  configurable via option `pide_mcp_session_statistics_limit`.
* New command-line option `-S` starts further sessions on startup.
  Isabelle system options given outside `-S` are inherited by the sessions given via `-S`.
  An invocation with only Isabelle system options does not start a session.
  Use `-S ""` to force a session start in such cases. INCOMPATIBILITY.
* Support for multi session handling and new tools `start_session`, `stop_session`, and `list_sessions`.
  Existing tools now take an optional session id, which may be omitted when exactly one session runs.
  `PIDE_MCP_Tool`s now receive several sessions. INCOMPATIBILITY.
* Update `get_state`: include tracing, remove unprocessed command details, and fix wrong message line number attribution for blobs.
* Set visible perspective on last read or edited origin only. Dependencies are hidden. Use option `pide_mcp_range_context` to adjust number of lines around range that stay visible.
* Await stable snapshot after editing operations (without blocking other tools)
* Reworked loading and editing: closes several issues reported by Diego Marmsoler and Yong Kiam Tan (stuck process due to invalid imports and de-synchronisation of session and resources, edit race conditions, etc.)
* New tool `unload`: Remove ("clean") a theory and its dependents.
* Add tool plugin system: users can freely add and remove (new) tools.
* The `edit` tool now matches substrings within a given range and can edit multiple occurrences at once.
  This is in line with how most coding agents implement their edit tools.

New in PIDE MCP 2025-2
----------------------------

* Port of PIDE MCP c13a4bd3c018 compatible with Isabelle2025-2
* Includes all post PIDE MCP c13a4bd3c018 updates until the release of PIDE MCP 2026

New in PIDE MCP c13a4bd3c018
----------------------------

* Initial release compatible with Isabelle/c13a4bd3c018
