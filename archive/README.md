# archive/ — superseded documentation

Everything here is **history**. It is kept because it records decisions, measurements and dead ends that
would otherwise be re-derived at cost, and because several documents contradict each other on purpose.

**Nothing in this folder is the truth.** If a file here disagrees with `../kanban.md`,
`../kanbanProgress.md`, `../TECHNICAL_OVERVIEW.md` or the code, **the code is right and this folder is
wrong.** Do not work from anything in here, and do not "fix" code to satisfy a document in here.

## What moved here, and when

Moved on **2026-10-03**, when the board was split into a `P0` / `P1` / `P2` structure. Until then the root
held four generations of planning documents, several of them actively misleading.

| File | Why it is here |
|---|---|
| `kanban.md` | The previous board. Mixed clusters, an owner-decisions section that contradicted itself, and a task list where done work sat next to open work. Superseded by the root `kanban.md`. **Its measurements are still cited** — where a number appears in both, the newer one wins. |
| `kanbanProgress.md` | The append-only log for that board, with every measurement, every mutation test and every dead end. **The single most valuable document in this folder.** Read it before proposing a change that resembles anything in it. |
| `TECHNICAL_OVERVIEW.md` | Described an architecture that no longer exists. Replaced by the root `TECHNICAL_OVERVIEW.md`. |
| `experAudit01102026.md` | The 2026-10-01 audit. Its §0 retracts three earlier claims and its Appendix B lists what it did not do. Still the best single statement of what is *not* known. |
| `expertAudit.md` | Superseded by `experAudit01102026.md`. |
| `dataFixSuggestions.md` | **§1.1 is wrong** and settled — recovery is committed and the missing `save()` was never a lost write. §1.2–1.5 are unverified. The document was produced by reading source, and three of its findings had a configuration where the claim "passed" while measuring nothing. |
| `ENGINE.md` | Short description of the live match path. Accurate when written; superseded by `TECHNICAL_OVERVIEW.md`. |
| `COMPETITIVE_ANALYSIS.md` | The product roadmap, 39 items, in a deliberate order. **Its §10 intake list is still the ordering used by `P2` in the root board.** The per-mode and per-sport material is out of date. |
| `BASKETBALL_PROGRESS.md` | Per-mode agent notes for another sport. Not re-verified since the `newLogic` split. |
| `sprintBacklog.md` | History. Claims five stub services were deleted; they exist. Prints "Sprint 5 ◀ CURRENT" twice. |
| `sprintProgress.md` | History. Same generation as `sprintBacklog.md`. |
| `presek03102026.md` | A 2026-10-03 snapshot that was never referenced by any other document. |

## The two rules this folder exists to enforce

1. **A green status is not evidence.** The recurring failure in this codebase is code that reports success
   while doing nothing. Three separate bugs could only be found by opening the app.
2. **A baseline is only comparable to a baseline measured the same way.** The `[ERROR]` summary prints at
   the *end* of a run, so a killed run reports a different set of failures than one allowed to finish.

Both cost real time before they were written down. That is why they are here and not only in the log.