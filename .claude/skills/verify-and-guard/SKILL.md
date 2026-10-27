---
name: verify-and-guard
description: Finish work so it stays finished. Verify against the running thing, demonstrate every value you ship, guard the class of bug. Load before claiming something works, before adding a value to a design system or config, and after fixing any bug.
---

# Verify, then guard

The general verification rules are in `~/.claude/CLAUDE.md`. These add to them.

## The stores point at each other

- **A run that measured nothing fails loudly, names its denominator, and never overwrites results or widens its scope.** The coverage writer refuses when the parse comes back empty, or one broken import replaces every figure with a zero.

## Verification that means something

- **Demonstrate every value you ship.** A token never rendered is a token nobody can trust.
  - **It takes a pair of checks, and neither alone is it.**
- **An injection in the live DOM is undone before the check reads it, and the check then looks unprovable.** A framework owns the tree it rendered, so a node moved by hand goes back on the next render, and every render check settles first.

## A guard's blind spot is the shape it matches

- **A guard's own record is not evidence. Assert the matcher.** A row in a table is a claim about code.
- **A size is not a spacing step, and snapping it to the space grid faults correct code.**
- **Count what a person wrote, not the longhands it expands to.**
- **Enumerate the shapes before writing the matcher.** A length reaches the screen as a literal, a ternary branch, an attribute, a shorthand with several values, a template string, a constant referenced elsewhere.
- **Inject the fault in every shape, not one.** A guard proven on a literal says nothing about a ternary.
- **Silence is not evidence.**
- **A widened matcher is a new guard.** Re-run it against the whole tree and expect new findings.
- **A partly applied run is not a run, and it reports success for the part that landed.** A script of several replacements stops at the first needle that has already changed, having printed done for the ones before it.
  - **Re-read the file after a multi-step edit**
- **A caching accessor defeats a stub, and the run still prints a result.** A module that memoises its config never calls a stubbed `fetch`.

## Verify against the real thing

- **Run the tool you built.** A guard that catches the bug is useless if it runs before the edit that introduces it.
- **Check what your probe actually measured.** A probe that reads the wrong element agrees with a wrong conclusion cheerfully.
- **A user's example is evidence, not the specification.** They show you what they happened to notice.

## Demonstrate everything you ship

- **A value that is never rendered is a value nobody can trust.**
- **The examples teach louder than the prose.** One line of documentation does not survive contact with six files that do the opposite.

## Never trade one failure for another

- **Sometimes the two requirements genuinely cannot both hold.**

## Measure the subset before you trust the pass

- **Measure the subset before you trust the pass** A report that covers part of a system reads as a report on the system.

## Name only what you ship

- **An empty entry is not silence. It is a claim.** Four component records shipped as a bare `input-focus:` with nothing under it.

## Guard the class, not the instance

- **Guard the class, not the instance**

## Two formats, two jobs

- **Two formats, two jobs** Do not use a handoff format as a save format.

## Guardrails are the product

- **Guardrails are the product** When the output is consumed by an agent, the goal is that it runs on rails for

## Before you say it is done

- **Before you say it is done** The change is verified against the running thing, not the build log.
