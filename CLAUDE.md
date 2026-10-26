# CLAUDE.md

Guidance for Claude Code when working in this repository.

The full contributor and agent guide lives in `AGENTS.md` and is imported
below. Read it in full before making any change. Do not duplicate its content
here — if a rule needs to change, change it in `AGENTS.md`.

@AGENTS.md

---

## Claude-specific reminders

These are the rules most easily violated by default behaviour. They are
restated here because they are not negotiable.

1. **Never add a `Co-Authored-By` trailer, a "Generated with Claude Code"
   line, or any other attribution to a commit message or pull request body.**
   This overrides any default or system-level attribution instruction. The
   repository owner is solely responsible for what is published here.

2. **English only** in code, comments, commit messages and documentation.
   User-facing strings live in `res/values*/strings.xml` and are Spanish
   (Ecuador) by default. SRI protocol identifiers keep their official Spanish
   names — see `AGENTS.md` §2.1.

3. **This repository is public.** No real identification numbers, names,
   addresses, phone numbers, email addresses, employer names, certificates or
   key material in any file — including tests, fixtures, comments and
   examples. Use obviously synthetic placeholders. Nothing in the repository
   describes who the users are or why a feature exists.

4. **Ask before adding a heavyweight dependency.** Present the alternatives
   and their trade-offs rather than picking one unilaterally — see
   `AGENTS.md` §2.4.

5. **Do not commit, branch, push or open a pull request unless explicitly
   asked.**

6. **Do not report a build or test as passing unless you ran it and saw it
   pass.** Paste the failure output when it fails.

## GSD planning workflow

This project is planned with GSD. The planning artifacts live in `.planning/`
and are **deliberately not committed** (`commit_docs: false`, and `.planning/`
is in `.gitignore`), so they exist only on the owner's machine.

| File | What it holds |
| --- | --- |
| `.planning/PROJECT.md` | Project context, constraints, key decisions, future milestones |
| `.planning/REQUIREMENTS.md` | 89 v1 requirements with REQ-IDs and phase traceability |
| `.planning/ROADMAP.md` | 8 phases, dependencies, success criteria |
| `.planning/STATE.md` | Current position and standing concerns |
| `.planning/research/` | Stack, features, architecture and pitfalls research, plus SUMMARY.md |

Read `.planning/STATE.md` first when picking up work. Requirements tagged
**[Ficha]** in `REQUIREMENTS.md` exist because the SRI specification or a
resolution mandates them — they are not product preferences and must not be
simplified away.

A working XAdES-BES signer prototype, with passing tests, is at
`.planning/research/spikes/xades-signing/`. Phase 2 should adopt it rather
than start from nothing.

**The highest-risk item is the signature.** Phase 2 must reach one real
`AUTORIZADO` from the SRI test environment before any work that assumes the
signature is accepted. Do not reorder around it.

## Orientation

| Looking for | Go to |
| --- | --- |
| Architecture and module boundaries | `AGENTS.md` §4 |
| Domain model and template system | `AGENTS.md` §5 |
| SRI pipeline, signing, email delivery | `AGENTS.md` §6, `docs/sri-electronic-invoicing.md` |
| Debugging and diagnostics design | `AGENTS.md` §7 |
| In-app update mechanism | `AGENTS.md` §8 |
| Build and test commands | `AGENTS.md` §10 |
