---
name: isabelle
description: Working with the Isabelle proof assistant. Use this whenever working with theory files (*.thy) or ML files (*.ML) or when you are dealing with Isabelle in the general sense.
---

## Principles
In priority order:
1. Correctness: no errors or inconsistencies.
2. Faithfulness: definitions and statements capture the intended meaning.
3. Completeness: all relevant definitions, theorems, and proofs are done.
4. Maintainability: code is structured and amenable to future changes.
5. Readability: clear and easy to understand for human Isabelle users.

## Workflow
- Understand the mathematics informally first. Plan the structure, order of definitions and theorems, and key lemmas.
- Work top-down: state definitions and lemmas with sorry and check that they suffice for the main results before proving them. Likewise within proofs: sorry larger subproofs first.
- Don't sorry steps you can prove right away and only sorry statements you are convinced are true.
- When stuck, isolate problems with sorry.
- Work incrementally: don't add too much before checking, so errors and non-termination stay easy to locate.
- Before defining a concept or proving a likely existing fact, search existing libraries: the session's directories, the Isabelle distribution, the AFP. If unsure early on, ask the user for hints.
- For experiments that would re-check much of a large theory, or to compare alternatives, use a scratch theory importing the theory you work on, then copy results back. Never import scratch theories from the main development.

## Definitions and statements
- Prove basic properties of definitions first (e.g. intro, elim, dest, simp rules) and use them instead of unfolding definitions.
- Don't weaken statements unless clearly justified. Generalisations are fine if they instantiate to the desired result.

## Closing goals
- If a goal does not close with an obvious guess:
  - If standard automation (simp, auto, blast, force, fastforce,...) should solve it and you know which facts to pass, use try0 (usually <5s) with those facts, e.g. `using <facts> try0 simp: <rules> intro: <rules>`.
  - Otherwise, e.g. if you do not know the needed lemmas or the proof likely needs metis or smt, use sledgehammer (usually <20s).
- From the suggested proofs, pick the most robust: methods with good default behaviour (simp, auto, fastforce,...) over explicit ones (metis, rule) over smt, unless they are much slower.
- Don't keep on guessing proofs for easy steps (particularly metis calls): sledgehammer typically finds them in seconds. Turn hard steps into a structured proof or a separate lemma.
- Run only a few sledgehammers in parallel; they are expensive.
- Proof methods usually finish within 5s. If one is running 10+s, suspect non-termination and change it.

## Search
- Unlike text search, find_theorems and find_consts also find generated facts (e.g. from datatype).
- find_theorems: use ?x or _ for variables (a free x matches only itself). intro, elim, dest, solves refer to the current goal. Limit results with find_theorems (<n>).
- find_consts "<type>" finds constants whose type contains the pattern; `strict: "<type>"` requires the whole type to match.
- find_theorems, find_consts, and sledgehammer only see transitively imported theories. For other ones, import them if cheap (base session or small dependency); otherwise search their sources as text.

## Theorem tagging
- Tag theorems (e.g. simp, intro, elim, dest) wisely, e.g. simp rules must produce good normal forms and not loop.
- Tag fundamental theorems of new definitions, unless they are rarely used; then pass the rules explicitly.

## Style
- Prefer Isar proofs: use `proof -` or proof <method>, never an implicit method. `apply` scripts are fine for exploration (then turn them into Isar) and where they are standard style (e.g. program logics).
- Don't be overly verbose: proofs with many low-level steps are brittle and hard to follow. Let automation handle them.
- Prefer structured statements: implicit quantification, ⋀ and `for` over ∀; `obtains` over ∃ in conclusions; Isar's `and` over ∧.
- Use Isabelle's structuring mechanisms where appropriate:
  - sessions (ROOT) for larger developments, so stable parts are built once and reused
  - separate theories instead of large ones
  - locales and type classes for shared parameters and assumptions
  - bundles for notation and rules that should only be active locally
- Import theories by session-qualified name (see ROOT files for session names) if possible, not by absolute path.
- Document theories concisely.
- Use descriptive lemma names and follow conventions (e.g. I, E, D, _iff suffixes).
