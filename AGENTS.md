# AGENTS.md

Guidance for AI agents (and for Warp) working in this repository.

**Rewritten 2026-10-03**, when the board was split into `P0` / `P1` / `P2` and twelve superseded documents
moved to `archive/`. This file is now short on purpose. Anything historical belongs in `archive/`, not
here.

---

## 📌 Where the state of this project actually lives

**Read these three files, in this order.**

| File | What it is |
|---|---|
| **[`kanban.md`](kanban.md)** | **The board.** Every open task, as `P0` correctness/security/owner-decisions, `P1` performance, `P2` features. Each task states the defect, where it is, and **exit criteria as a checklist**. |
| **[`kanbanProgress.md`](kanbanProgress.md)** | **The append-only log.** One entry per task, newest first, each carrying its commit. What was measured, and what did not work. |
| **[`TECHNICAL_OVERVIEW.md`](TECHNICAL_OVERVIEW.md)** | What the system actually is today, written against the source. |

`archive/` holds four generations of superseded planning. **It is history, never the truth.** If a file
there disagrees with the code, the code is right — report the document, don't work around it. Its
`README.md` lists what moved and why.

---

## 🔴 Working rules

> 1. **Answer in English. Always.** The owner writes in Serbian; every reply, commit, message and code
>    comment is in English. This is not a preference to be revisited each session.
> 2. **Behave as a professional senior fullstack developer.** Clean, simple code, no overengineering. If
>    you have doubt, **ask rather than going in circles.** If you do not use something, do not build a
>    helper for it.
> 3. **Testing:** short, clear, professional. No fiddling, no fake `[x]` checkboxes.
> 4. **Feature thinking:** think as a Product Owner *and* a football coach/analyst — what the game
>    realistically needs, not what is technically interesting.
> 5. **SOLID and OOP** — one responsibility per class, readable.
> 6. **Commit to `main` after each task**, then update `kanban.md` and append to `kanbanProgress.md`.
>    Stage only the files that task intended to change.
> 7. **A green status is not evidence.** See below — this codebase has failed that way repeatedly.

### The three rules that keep mattering

**A job is not done until it has been seen to change data.** Not logged as done — observed in the database
afterwards. Never hand back a half-built world.

**A test that cannot fail proves less than no test.** When you write a guard, **break the code deliberately
and watch it fail.** Three tests in this repository were green while measuring nothing before they were
fixed.

**A baseline is only comparable to a baseline measured the same way.** The Maven `[ERROR]` summary prints at
the *end* of a run, so a killed run reports a different set of failures than one allowed to finish.

---

## Build & run

```bash
export JAVA_HOME=/Users/velja/Library/Java/JavaVirtualMachines/corretto-21.0.12/Contents/Home
export PATH="$JAVA_HOME/bin:/usr/local/bin:$PATH"   # the agent shell does not inherit these
```

| | |
|---|---|
| Build | `mvn clean package -DskipTests` |
| Run | `./run-app.sh` — **never** `mvn spring-boot:run` directly |
| One class | `mvn test -Dtest=RatingEngineTest` |
| One method | `mvn test -Dtest=RatingEngineTest#theSeedIsTheOwnersScale` |
| Database | PostgreSQL `sokker_db`, user `postgres`, password `stojke` |
| Login | `velibor@example.com` / `A12345!` |

### 🔴 Never start the app from a shell without `--app.open-browser=false`

`BrowserLauncher` opens `http://localhost:8080/login.html` on every startup. Correct when the owner runs
`main()` from the IDE, wrong for any shell start. **The application cannot tell the two apart** — the
default stays `true` for the IDE and **every shell start must opt out**. Do not "fix" this by flipping the
default off; it was briefly opt-in and the owner's IDE start then did nothing, silently.

### Boot writes nothing

No seeding, no backfills, no repair. World building happens on admin buttons (`Reset DB`, `Initialize DB`,
`Repair world`, `Re-seed national teams`, `Seed other nations`, `Re-draw the cup`).
`ensureBaselineDataOnStartup()` has no caller — do not add one without a decision.

---

## Testing

**154 test classes. Last recorded full run: 992 tests, 29 red, ~2 h 52 m — and it needs the app on
`:8080`.** Three Playwright classes live in `newLogic/service/`, so nothing in the tree hints a full run
needs a server: **they wait rather than fail, and hang the entire run.** Never start one on the way to
something else.

Playwright needs `mvn exec:java -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args="install"` once,
a running app, and `setHeadless(true)` for CI.

**The suite's red list and what each failure means is in `archive/kanbanProgress.md`.**

---

## Traps that have cost real time

- **`Team.reputation` is the economy's 0–100 scale, not an Elo.** Club Elo lives in `eloRating` /
  `eloPreviousRating` / `eloDelta`. **`Country.reputation` is a different scale on a column of the same
  name.**
- **A season is twelve weeks counted from 1.** There is no calendar year anywhere. Code or tests passing
  2024/2025/2026 as a season value are wrong, even if self-consistent and passing.
- **`PyramidBuilder` never sets `Team.type`.** A club is anything whose `competition` is a LEAGUE.
- **Use `authFetch`** for every authenticated frontend call and **always check `response.ok`** — several
  loaders `await response.json()` without it, so a 404 becomes a generic "API Error" card.
  `escapeHtml` lives in `ui/escape.js` and is the only copy.
- **`WE_HAVE_BALL` and `OPPONENT_HAS_BALL` are identical on purpose.** `mirrorWeHaveBallRules` is an
  owner decision, not a defect, and `DefensiveShape` is load-bearing. Do not "fix" the mirroring.
- **`demo/service/` is frozen.** Reference only — do not extend, do not port from it.

---

## Scale

48 countries × 31 divisions × 10 clubs ≈ **14,880 clubs**. The world grew ~48× in five days and the code
that schedules it was written for one country. **This is why `kanban.md` has a P1 category**, and why
three hot tables currently have no usable index (`match` and `match_tick_states` have primary keys only;
`player_zone_load`'s unique index leads with `player_id`, so `findByMatchId` cannot use it).

---

## Related documents

| File | What it is |
|---|---|
| [`kanban.md`](kanban.md) · [`kanbanProgress.md`](kanbanProgress.md) | **The board and its log. Read first.** |
| [`TECHNICAL_OVERVIEW.md`](TECHNICAL_OVERVIEW.md) | What the system is today |
| [`archive/`](archive/) | Superseded planning, audits and per-mode notes — **history only** |
| `archive/COMPETITIVE_ANALYSIS.md` | The product roadmap; its §10 ordering is what `P2` follows |
| `archive/experAudit01102026.md` | The 2026-10-01 audit; its Appendix B lists what it did **not** do |
| `archive/kanbanProgress.md` | The full 29-red list and why a full run takes ~3 hours |