---
name: isabelle-proof-development
description: Developing proofs in the Isabelle proof assistant. Use this skill whenever you are working on proofs and exploring concepts (theorems, constants, etc.) in Isabelle.
compatibility: claude-code
metadata:
  version: "1.0"
---

## What I Do

Guide proof search, automation, and concept search in the Isabelle proof assistant.

## When To Use Me

Whenever you need to:
- Think about automation and theorem tagging.
- Close a goal that you could not one-shot.
- Decide which automation to use (`simp` vs `auto` vs `blast` vs `sledgehammer` vs ...).
- Search for existing theorems and constants.

## Quick Reference

- Use automation tags (`simp`, `intro`, `elim`, etc.) on theorems, but be wise about them.
- When a goal doesn't close with an obvious guess, **your default move is to use `try0`**. When `try0` fails, **use `sledgehammer`** (poll its output!). 
- When automation is brittle or explodes (non-determination): switch to structured Isar proofs and add intermediate steps.
- When unsure whether a lemma exists or what lemmas exist in general, **use `find_theorems`** to search in all transitively imported theories.  
- When unsure whether a constant exists or what constants exist in general, **use `find_consts`** to search in all transitively imported theories.  
- When searching for concepts in other theories, import the theory if it is from the base session or it is a small dependency; otherwise use grep (since it avoids building the dependency).

IMPORTANT:
- **`try0`, `find_theorems`, and `find_consts` are very cheap and can be used frequently.**
- **`sledgehammer` should be used whenever `try0` failed. It is almost always cheaper than guessing a proof in several attempts.**
  Don't guess proofs and proof method invocations (particularly using `metis`) that you could not one-shot: `sledgehammer` typically finds these in seconds.
- **Prefer methods with good default behavior, such as `simp` and `auto`, over very explicit ones like `metis`, `rule`, etc.**

## Theorem Tagging

- Use automation tags (`simp`, `intro`, `elim`, etc.) on theorems, but be wise about them.
- For `simp` theorems: avoid loops (possibly involving other simp rules) and pay attention to obtain good normal forms.
- Correctly classify rules for the classical reasoner (intro, elim, dest).
- **Add appropriate attributes for fundamental theorems on a new definition** unless you rarely have to work with the concept. In that case, manually pass the rules to the provers.

## How to use (Proof) Search Commands

1. Insert the command at the right position: typically, after the goal opens.
2. Poll the state/output at the command position. Respect the maximum timeouts mentioned in this skill file.
3. When the command returns the needed output, take it. Then remove the command.
4. If the command doesn't return results after the maximum timeout, also remove the command.

## Default Proof Search Order and Timeouts

**Order for closing a goal that you couldn't prove in one-shot/immediately:**
1. `try0`: runs cheap built-in methods (`simp`, `auto`, `blast`, `force`, ...). Usually finishes in **<5 seconds**. This is your first move.
2. `sledgehammer`: uses fact selection, built-in methods, and external provers. Usually finishes in **<30 seconds**. 
   Use after `try0` failed and always before trying to generate a proof that you could not one-shot.

## `try0`

You have to pass facts explicitly to `try0`:
```isabelle
using <some theorems> try0 simp: <some simp theorems> intro: <some intro rules> ...
```
Poll until you hit the maximum timeout mentioned in this file and pick the fastest returned proof.

## `sledgehammer`

- Poll sledgehammer after 5 seconds. Then again after 10 seconds. Repeat until you hit the maximum timeout mentioned in this file.
- Pick the **fastest reconstruction with the simplest method**: prefer `auto`, `fastforce`, etc. over `metis` and `metis` over `smt`. 
- **Don't use too many parallel sledgehammers**. Instead, insert just a few (<=5) and iterate.
- `sledgehammer` has built-in fact selection: it often finds the right lemmas. 
- **Only transitively imported theorems can be queried by sledgehammer.** If you suspect some currently non-imported theory contains useful material, you first have to import it.
  It is much quicker to let sledgehammer find the right lemmas than to search for them on your own.

## `find_theorems`

Searches loaded theorems.
Syntax: `find_theorems [<n>] <criterion> <criterion> ...`

| Criterion | Syntax | Matches |
| --- | --- | --- |
| Pattern | `"<term>"` | Theorems containing a matching subterm. **Default criterion.** |
| Name | `name: <ident>` | Theorems whose name contains `<ident>`. |
| Intro / Elim / Dest | `intro` / `elim` / `dest` | Rules applicable to the *current goal*. |
| Solves | `solves` | Theorems that directly close the current goal. |
| Simp | `simp: "<pattern>"` | Simp rules rewriting the given pattern. |

Each criterion can be negated, e.g. `-name: foo`, `-"rev (rev _) = _"`, etc.
**Patterns are matched.** Use `?xs` (schematic) or `_` (wildcard) for variables that can be instantiated. Examples:

```isabelle
find_theorems "rev (rev ?xs) = ?xs"      (* ✅ *)
find_theorems "rev (rev _) = _"          (* ✅ *)
find_theorems "rev (rev xs) = xs"        (* ❌ xs is a free variable; returns nothing *)
find_theorems name: rev "?xs @ _"        (* ✅ combined criteria *)
find_theorems intro                      (* ✅ intro rules for current goal *)
find_theorems 5 "rev (rev ?xs) = ?xs"    (* ✅ cap at 5 results *)
```

## `find_consts`

Searches loaded constants.
Syntax: `find_consts <criterion> <criterion> ...`

| Criterion | Syntax | Matches |
| --- | --- | --- |
| Type | `"<type pattern>"` | Constants whose type contains a matching subtype. **Default criterion.** |
| Strict type | `strict: "<type pattern>"` | Constants whose type matches |
| Name | `name: <ident>` | Constants whose name contains `<ident>`. |

**Polymorphic types `'a` are matched (they can be instantiated)**. Examples:

```isabelle
find_consts "nat"                    (* any constant whose type contains `nat` *)
find_consts strict: "nat"            (* constants of type exactly `nat` *)
find_consts "'a list ⇒ 'b list"      (* also finds `nat list ⇒ bool list` *)
find_consts name: rev                (* by name *)
find_consts name: list "_ ⇒ nat"     (* combined criteria *)
```

## Limitations of `find_theorems` and `find_consts` and when to use `grep`

**Only transitively imported theories can be queried by these commands.**
When searching for concepts in other theories, import the theory if it is from the base session or it is a small dependency; otherwise, use `grep` (since it avoids building the dependency).

