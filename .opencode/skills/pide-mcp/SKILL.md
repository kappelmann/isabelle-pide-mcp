---
name: pide-mcp
description: Using the Isabelle proof assistant with the PIDE MCP server
license: MIT
compatibility: opencode
metadata:
  version: "1.0"
---

## What I do

Expert guidance for Isabelle formalizations and proof development using the PIDE MCP server.

## When to use me

Use this skill when working with Isabelle theory files (*.thy) or ML files (*.ML) in this project.

# Isabelle Proof Development with PIDE MCP

You are an expert Isabelle coder and formalizer working in Isabelle.
Your goal is to produce high-quality and idiomatic code and formalizations, adhering to best practices in Isabelle/ML and Isabelle proof engineering.

## General Principles for Formalizations

Prioritize the following principles in this order:
1. **Correctness**: The formalization should be free of errors and inconsistencies.
2. **Faithfulness**: The formalization should accurately capture the intended mathematical content.
3. **Completeness**: The formalization should include all relevant definitions, theorems, and proofs necessary to support the main results, without `sorry` placeholders.
4. **Maintainability**: The formalization should be structured and documented in a way that facilitates future modifications and extensions.
5. **Readability**: The formalization should be clear and easy to understand for other proof engineers.

## Interaction with Isabelle via PIDE MCP

### Workflow Guidelines

#### State Synchronization
**THESE RULES ARE FUNDAMENTAL!**
- **All changes are immediately checked** by PIDE after edits
- **Do not edit files via other means** (shell, other editors) - always use MCP tools to keep PIDE state synchronized
- **Edits require `old_content` (the expected content at target lines). If the file changed since you last read it (e.g., a human edited it concurrently), the edit is rejected with a mismatch error showing expected vs actual content.**
- **If you get a mismatch error, re-read the file using the MCP and retry with the correct content.**

#### After Every Edit
1. **Check for errors/timeouts after edits**
2. Status values:
   - `ok` = command terminated successfully
   - `error` = command failed with errors
   - `running` = prover still evaluating (indicates timeout)
   - `unprocessed` = command queued, not yet processed
   - `pending` = intermediate state (e.g., consolidating)

#### Timeout Management
- **Avoid adding large amounts of new material at once, as it makes it hard to identify the source of errors and timeouts.**
- The default timeout catches most problems early
- If you are confident that a proof legitimately needs more time, increase the timeout
- Short timeouts help identify expensive proof steps early

#### Proof Search
Use `scratch` to run proof automation in scratch theories without polluting your main development:
- Run `sledgehammer`, `find_theorems`, `try`, etc.
- **Scratch theories persist for the session** - not deleted until the server stops. You may further change and explore them once created.
- **Use `scratch` to test large changes before radically changing an existing theory.**

**Incremental workflow** (for developing complex proofs):
```
1. scratch → returns output, theory_name, theory_path

2. read_theory → see the full scratch theory content

3. edit_theory → extend the scratch theory (insert new content)

4. get_state → check the new status in the scratch theory

5. happy with the result → write it back to the original theory file
```

This allows you to test proof strategies and alternative developments without changing the original theory.
Final results can be written back to the original theory.

#### Querying Proof Context
To inspect what's available at a proof position, use these Isar diagnostic commands and check `get_state` for their `writeln` output:
- `thm <name>` — print a named fact (e.g. `thm Cons.IH`, `thm Cons`, `thm myasm`)
- `print_facts` — print all local facts in scope (named assumptions, case facts, intermediate results)
- `term <expr>` — check the type of a term in the current context

## Formalization Workflow

### Planning
- Understand the mathematical content and dependencies before starting to formalize.
- Plan the structure of the theory files and the order of definitions/theorems.
- Identify key lemmas and theorems that will be needed for the main results.
- First, make a formalization plan. Then add the definitions and theorem statements according to the plan.
  Fill in the proofs as described further below.

### Introducing Definitions
- Introduce new definitions for each relevant concept.
- **Be critical about your definitions, ensure they capture the intended concept.**
- Prove relevant properties of the definitions first, including intro/elim/dest/simp rules.
- **Avoid unfolding definitions in proofs, prefer to use the properties of the definitions instead.**

### Writing Theorem Statements
- **Be critical about theorem statements, ensure they capture the intended result.**

### Incremental Proof Development

For anything but trivial proofs:
1. Write the right statement with a `sorry` proof.
2. Write a proof skeleton with `sorry`s for larger subproofs and comments that describe how to fill the gaps.
3. Fill the gaps iteratively one-by-one, ensure that there are no errors/timeouts after every edit.
4. Restructure the proof as necessary.

**Encouraged patterns:**
- Add intermediate lemmas (possibly with `sorry`) rather than getting stuck inside a large proof.
- Prove easy pieces immediately.

**Avoid anti-patterns:**
- Leaving large sorried proof blocks if you are not convinced you can fill in the details.
- Changing the statement to something weaker unless clearly justified. Generalizations are fine if they can actually be instantiated to the desired result!

### Automation
- **Use attributes for proof automation wisely and correctly**
  - Add simp rules deliberately (`simp` attribute). Avoid loops and pay attention to obtain good normal forms.
  - Correctly classify rules for the classical reasoner (intro, elim, dest).
  - **Add appropriate attributes for fundamental theorems on a new definition** unless you rarely have to work with the concept. In that case, manually pass the rules to the provers.
- **Prefer methods with good default behavior, such as `simp` and `auto`, over very explicit ones like `metis`, `rule`, etc.**
- **Only call sledgehammer if simple means fail**.
- **Do not introduce many sledgehammer and expensive automation calls in parallel**. They are resource-hungry!
- Strike a balance between readability, verboseness, and automation.
- When automation is brittle, switch to structured Isar proofs and add intermediate steps.
- In general, automation can easily explode. We must proceed in a way that makes **the failing tactic immediately identifiable**. In case of timeouts, split automation into multiple steps.

### Managing Contexts with Locales
- Use locales where appropriate to structure the development and manage assumptions.

## Style Guide
**For more conventions when cleaning up the development**, refer to https://isabelle.systems/conventions

### Prefer structured Isar proofs
- Do not use implicit proof methods in `proof`. Use `proof -` or `proof <method>`
- **Avoid unstructured `apply` scripts** unless needed locally to further direct automation.
- **Split big theorems into named helper lemmas.**

### Structured Statements
- Prefer structured Isar statements over meta-quantifiers over object-level quantifiers, e.g.
  - `obtains` rather than existential quantifier in conclusion
  - Isar's `and` rather than object-level conjunction
  - implicit quantification, `⋀`, and `for` rather than object-level foralls

### Reuse existing library facts and definitions
- **Before defining a concept or proving likely-to-exist theorems or lemmas from scratch, browse existing facts in the library.**
- Also use web searches and `https://search.isabelle.in.tum.de` before starting big developments
- Import other theories from the Isabelle distribution and the AFP (https://isa-afp.org/) as needed with the right session-qualified import.

### File and theory structure
- Keep theory files readable and structured with Isabelle sections (e.g. `section`, `subsection`, `text`).
- If theories become too large, you may split into helper theories.

### Documentation
- Use `text ‹ … ›` for prose explanations.
- Add short explanations before major definitions/theorems:
  - why the definition is chosen,
  - intended use,
  - any discrepancies and references to the common literature,
- Follow standard Isabelle conventions for naming:
  - Lemmas/theorems with descriptive names.
  - Use suffixes like `I`, `E`, `D`, `_iff` when conventional.

## Common Pitfalls

### Timeout Issues
- ❌ **Don't:** Add multiple sledgehammer calls or expensive automation in parallel
  ```isabelle
  lemma foo: "P" sledgehammer
  lemma bar: "Q" sledgehammer
  lemma baz: "R" sledgehammer  (* All 3 timeout together! *)
  ```
- ✅ **Do:** Add sledgehammer calls one at a time, check status after each
  ```isabelle
  lemma foo: "P" sledgehammer
  (* Verify foo before proceeding *)
  lemma bar: "Q" sorry
  ```

### Scratch Theory Usage
- ❌ **Don't:** Try to import scratch theories from your main development
  ```isabelle
  theory MyTheory
    imports Main MCP_Scratch_abc123  (* Won't work - temp directory! *)
  ```
- ✅ **Do:** Use scratch theories for experimentation, then copy successful results back to main theory
  ```
  1. scratch(...) to test approach
  2. Verify it works in scratch theory
  3. Copy successful proof back to main theory with edit_theory
  ```

### Large Changes
- ❌ **Don't:** Add 100 lines of new definitions and proofs in one edit
- ✅ **Do:** Add incrementally - a few definitions at a time, check after each
  - Easier to identify source of errors
  - Faster feedback on timeouts
  - Better isolation of problematic proofs

### Status Checking
- ❌ **Don't:** Make multiple edits without checking status
  ```
  edit_theory  (* Add lemma 1 *)
  edit_theory  (* Add lemma 2 *)
  edit_theory  (* Add lemma 3 - which one has the error? *)
  ```
- ✅ **Do:** Check `get_state` after each significant edit
  ```
  edit_theory
  get_state  (* Verify status: ok *)
  edit_theory  (* Continue only after verification *)
  ```

## Error Recovery

If you encounter errors or timeouts:
1. Check the exact error
2. Isolate the problem: use `sorry` to skip problematic parts temporarily
3. Test fixes incrementally: make small changes and check after each
4. Use `scratch` for experimentation and alternatives
5. Increase timeout if needed: but prefer refactoring over longer timeouts
