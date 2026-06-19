---
name: pide-mcp
description: Using the Isabelle proof assistant with the PIDE MCP server. Use this skill whenever working with Isabelle theory files (*.thy) or ML files (*.ML) or when you are dealing with Isabelle in the general sense and have access to the PIDE MCP.
compatibility: claude-code
metadata:
  version: "1.0"
---

## What I Do

Guidance for Isabelle formalizations and proof development using the PIDE MCP server.

## When To Use Me

Use this skill when working with Isabelle theory files (*.thy) or ML files (*.ML) via the PIDE MCP.

## Quick Reference

Operational rules (each rule is elaborated in the sections below):

- **After every edit, call `get_state`** to see status, errors, warnings.
- **Restrict by `start_line` / `end_line`** when querying - avoids analysing data for hundreds of finished commands in large theories.
- **`get_state` takes flags** (`include_types`, `include_facts`,...) - pass them when you need them.
- **`sorry` is reported as `commands_bad`, not as `commands_errors`.** A clean theory has 0 bad commands AND 0 errors AND 0 failures.
- **If `> 0` commands are running for more than ~30s, suspect a loop** - restructure rather than wait.
- **Edits**: `old_text` must match the existing lines modulo trailing whitespace.
- **`create_scratch`**: use for exploration and alternatives. (Typically) use the same imports on scratch theories as the ones you use for the development that you are creating a scratch theory for.

## Interaction with Isabelle via PIDE MCP

### State Synchronization
- **All changes are immediately checked** by PIDE after edits.
- **Do not edit files via other means** (shell, other editors) - always use MCP tools to keep PIDE state synchronized. Reading via other tools (e.g. grep) for unloaded theories for exploration (e.g. grepping the AFP) is OK.
- **If you experience discrepancies between the PIDE state and the file's disk content, use read on the affected file to re-synchronise the content.** Such discrepancies might happen, for example, when a human user is working on the same file as you or when you didn't edit a file via PIDE but with primitive file operations.
- **Avoid absolute file path imports for theories. Use session-qualified theory names for sessions available to you.** See `list_session_directories` on how to find session qualifiers.
- When you are told to synchronise, just re-read the file.
- **Edits require `old_text` (the expected content at target lines). If the file changed since you last read it (e.g., a human edited it concurrently), the edit is rejected with a mismatch error showing expected vs actual content. The comparison is modulo trailing whitespace, so minor newline differences are tolerated.**

### After Every Edit
1. **Check for errors/unfinished/successful commands after edits with `get_state`**.
2. **`sorry`s are included as "bad" commands. `commands_errors` counts actual failures. A "clean" state requires both to be 0.**

### Time Management
- **Avoid adding large amounts of new material at once, as it makes it hard to identify the source of errors and nontermination.**
- **Proof methods typically terminate in less than 5 seconds.**
- If commands take longer, be suspicious! Only if you are very confident that a proof legitimately needs more time wait a bit longer. Short waits typically let you move faster!
- **If `get_state` shows `> 0` running commands for more than ~30 seconds, suspect non-termination** (a loop or overly expensive computation). Restructure rather than wait.

### Scratch Theories
- **Use `create_scratch` to test large changes in a scratch theory before radically changing an existing theory.**
- **Scratch theories persist for the session** - they are not deleted until the server stops. You may further change and explore them once created.
- Use the scratch theories to try proofs and search tools without polluting your main development. Exception: if it is just a brief exploration (e.g. calling `sledgehammer` or `find_theorems` on the currently open goals), just do it in the same theory.
- For scratch theories, pass the imports matching your needs. Typically, it is the same imports as the ones you use for the development that you are creating a scratch theory for.

**Incremental workflow** (for developing complex proofs):
```
1. create_scratch → scratch theory_path

2. edit → extend the scratch theory (insert new content)

3. get_state → check the new status in the scratch theory

4. happy with the result → write it back to the original file
```

This allows you to test proof strategies and alternative developments without changing the original file.
Final results can be written back to the original file.

### Querying (Proof) Context
- **`get_state` returns structured information at any command position, including status (check it after edits), subgoals, all prover messages (errors, warnings, writelns, .etc), type information for terms,...**
- **`get_state` accepts flags to opt into richer data**, like `include_types` (for Isabelle and ML), `include_facts` (theorems used in range), etc.
- **Restrict by `start_line` / `end_line`** when you only care about a specific lemma or error - it is less verbose than scanning the whole file. But don't forget that local changes may have global effects.
- **`find_entities` lets you explore defined entities (theorems, definitions, methods, ML terms, etc.)**.
Moreover, you can use Isar commands if necessary, for example:
- **`print_facts` - print all facts of the current context (named assumptions, case facts, intermediate results, locale assumptions, etc.)**
- `find_theorems`
- `find_consts`

### Importing existing library, facts, and definitions
- **Use `list_session_directories` to get all available session directories, often containing useful library material.**
- Import theories from these sessions using the right **session-qualified import**.
- **To get session qualifiers, grep the ROOT files in the session directories.**

### Common Pitfalls

#### Scratch Theory Usage
- ❌ **Don't:** Try to import scratch theories from your main development
- ✅ **Do:** Use scratch theories for experimentation, then copy successful results back to main theory
  ```
  1. create_scratch to test approach
  2. Verify it works in scratch theory
  3. Copy successful proof back to main theory with edit
  ```

#### Large Changes
- ❌ **Don't:** Add 100 lines of new definitions and proofs in one edit
- ✅ **Do:** Add incrementally - a few definitions at a time, check after each
  - Easier to identify source of errors
  - Faster feedback on timeouts
  - Better isolation of problematic proofs

#### Status Checking
- ❌ **Don't:** Make multiple edits without checking status
  ```
  edit  (* Add lemma 1 *)
  edit  (* Add lemma 2 *)
  edit  (* Add lemma 3 - which one has the error? *)
  ```
- ✅ **Do:** Check `get_state` after each significant edit
  ```
  edit
  get_state  (* Verify status: ok *)
  edit  (* Continue only after verification *)
  ```

### Error Recovery

If you encounter errors or nondetermination:
1. Check the exact error
2. Isolate the problem: use `sorry` to skip problematic parts temporarily
3. Make incremental fixes: make small changes and check after each
4. Use `create_scratch` for experimentation and alternatives
5. Increase wait for termination if needed, but prefer refactoring over long waits!

