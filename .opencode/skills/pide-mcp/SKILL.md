---
name: pide-mcp
description: Using the Isabelle proof assistant with PIDE MCP. Use this whenever working with theory files (*.thy) or ML files (*.ML) or when you are dealing with Isabelle in the general sense and have access to the PIDE MCP.
---

PIDE MCP's tool set is customisable: tools named here may be unavailable. Also load skills of PIDE MCP tools.

## Sessions
- Each session has its own base session, directories, options, and state.
- A session has a limited number of worker threads. Commands may be unprocessed only because all workers are busy, e.g. with commands in other theories: check get_session_state.
- Prefer unloading a stuck theory over restarting a session.

## Edits and synchronisation
- read and edit synchronise disk and PIDE state: after external disk edits or when asked to synchronise, use read.
- Edits re-check all later commands, also in dependent theories: check beyond the edited range.
- PIDE MCP uses Unicode for Isabelle symbols (e.g. ∀ for \<forall>) internally and follows option pide_mcp_unicode_symbols_disk for disk operations, configurable on session start. Non-PIDE Isabelle tools may require ASCII files: recode with `isabelle symbols`.

## Checking state
- Check your changes: get_state for local information (errors, goals, sorrys in a file), get_session_state for global information (all theories' status, processing commands).
- processing means a command has not finished yet, not that it succeeded.
- A theory status ok means all commands finished without errors. It may still contain sorrys (reported as bad, not as errors), proofs aborted with oops (not reported), and warnings.
- Errors appear in two forms: error messages and failed commands. A failed command might not have an error message.
- Request only information you need and set limits: details for a whole theory flood your context.
- Only commands in the PIDE perspective run automatically tried tools (e.g. quickcheck, solve_direct).
- Isar diagnostic commands (try0, sledgehammer, print_facts, find_theorems,...) work: their output appears under infos in get_state (poll while processing).
