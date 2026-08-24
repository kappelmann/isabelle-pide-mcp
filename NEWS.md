PIDE MCP NEWS -- history of user-relevant changes
=================================================

New in this PIDE MCP version
----------------------------

* Update get_state: include tracing, remove unprocessed command details, and fix wrong message line number attribution for blobs.
* Set visible perspective on last read or edited origin only. Dependencies are hidden. Use option pide_mcp_range_context to set number of lines around range that stay visible.
* Await stable snapshot after editing operations (without blocking other tools)
* Reworked loading and editing: closes several issues reported by Diego Marmsoler and Yong Kiam Tan (stuck process due to invalid imports and de-synchronisation of session and resources, edit race conditions, etc.)
* New tool unload: Remove ("clean") a theory and its dependents.
* New tool get_progress: show progress of theories and currently processed commands.
* list_loaded_theories now excludes non-theories.
* Add tool plugin system: users can freely add and remove (new) tools.
* The edit tool now matches substrings within a given range and can edit multiple occurrences at once. 
  This is in line with how most coding agents implement their edit tools.

New in PIDE MCP 2025-2
----------------------------

* Port of PIDE MCP c13a4bd3c018 compatible with Isabelle2025-2
* Includes all post PIDE MCP c13a4bd3c018 updates until the release of PIDE MCP 2026

New in PIDE MCP c13a4bd3c018
----------------------------

* Initial release compatible with Isabelle/c13a4bd3c018
