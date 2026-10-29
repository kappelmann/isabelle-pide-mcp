---
name: isabelle-ml
description: Developing and exploring Isabelle/ML code. Use this skill whenever you are working on ML files (*.ML) or ML blocks in Isabelle theories.
---

## Principles
- Above all, use the distribution's library and follow its naming, jargon, and code and documentation style, also when commenting and talking to others.
- Code must be correct, efficient, intelligible, modular, and maintainable.
- Use comments sparingly, comparable to the distribution.

## References
Before a big new project, review the relevant conventions and library thoroughly; for small changes, the local context is usually enough.
Consult these in order, until you have a clear answer. All are in Isabelle's home directory:
1. Documentation:
   - NEWS: Concise summary of important changes.
   - src/Doc/Implementation: Isabelle/ML.
   - src/Doc/Isar_Ref: Isar and its main commands.
   - Other documents in src/Doc (e.g. Functions, Datatypes, Locales) for expert knowledge.
2. ML sources: Look at implementations and their usage sites. The latter often reveal invariants callers must preserve.
3. Mercurial history: Explains why code looks the way it does and who is responsible. Commits often contain useful context.

Cite these sources, including commits and authors, when explaining code. Only speculate about intent if they give no clear answer, and say so.

## Jargon (non-exhaustive)
| Term | Meaning |
|------|---------|
| context (Proof.context, Context.generic) | Immutable record containing state and data |
| theory | Top-level background context |
| local theory | Proof context of a (named) target |
| morphism | Bundle of structure-preserving maps between contexts |
| attribute | thm * context transformation, used via [<attribute>] |
| tactic | thm -> thm Seq.seq function on proof states |
| method | Context-aware tactic registered as an Isar proof method |
| schematic | Containing instantiable variables ?x (aka meta variables) |
| oracle | Dynamically introduced axiom, bypassing the kernel |
| antiquotation (@{name ...}, \<^name>‹...›) | Compile-time splice |
