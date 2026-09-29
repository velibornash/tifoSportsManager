# 📋 kanban.md

**Derived from [`sprintBacklog.md`](sprintBacklog.md).** Everything still open that was in the
backlog is here, organised as tasks rather than as a sprint log. Where a task came from a numbered
sprint item (S1.4, S8.1, …) that is kept so the two files can be cross-checked.

**History lives in [`sprintProgress.md`](sprintProgress.md).** That is the running log of what was
attempted, what broke and why. This file holds no history — if something is here, it is not done.

Last updated **2026-09-29**.

---

## How to read this

| Column | Meaning |
|---|---|
| **📋 Backlog** | Known work, not started or partially done. No active effort. |
| **🔄 In progress** | Being worked on right now. One at a time. |
| **👀 Review** | Built, needs the owner to look at it and confirm it is right. |
| **✅ Done** | Finished and verified. Kept briefly so the next session can see what not to redo. |

Nothing moves to ✅ on the strength of a passing test alone if the owner has not seen it running.
The one exception is work with a number attached — a count either meets its target or it does not.

---

## 🔄 In progress

*Nothing. The last session closed out everything it started.*

---

## 👀 Review

The owner rebuilt and restarted these; they have not been looked at in the running app yet.

| Task | What to check | From |
|---|---|---|
| World page | 48 countries listed, all clickable, competitions shown disabled, human-player count populated | `b80b4a0` |
| World integrity in Admin | Country / side / squad counts on page load; the three repair buttons | `b80b4a0` |
| Training under Club | Training and Training Reports in the Club submenu, no top-level Training button | static edit |
| World in the header | Between Serbia and Community | static edit |

**Known to need a hard reload:** the sidebar changes are static HTML, so a cached
`dashboard.html` will still show the old top-level Training button.

---

## 📋 Backlog

### World, countries and internationals

| Task | State | Note |
|---|---|---|
| Verify the 48-country world in the running app | pending | 48 countries / 96 sides / 48 squads proven in tests and in the boot log, **not yet confirmed in the running app** |
| **Elo ratings** | not started | Every country starts at 1500 and nothing moves it. `RatingEngine` computes without persisting. The World page rating column is real but flat until this lands. |
| **Online-user presence** | not started | The World page shows registered accounts, not people online. There is no session registry or last-seen column. Do not label the number "online" until this exists. |
| **Champions Cup / Masters Cup / Challenge Cup** | not started | International club competitions. Champions = winners, Masters = 2nd and 3rd, Challenge = 4th. Each needs a record, a draw and a per-tier league link. |
| **NT Qualifiers + World Cup (senior)** | mechanism only | `InternationalFixtureSeeder` draws senior sides already. Only the competition records and formats are missing. |
| **U-21 Qualifiers + U-21 World Cup** | not started | Separate competitions from the senior ones, with their own qualification phases — not tabs on one competition. |
| Admin: activate a country | not started | The activation panel was asked for. `CountryState` is `ACTIVE`/`SIMULATED` and the World page already keys off it, so the page needs no change. |
| Bot league tier standards | not started | Tier 1 at average skill 12, then 11, 10 by tier. Bot squads are all skill 12 today. |
| `Player.rating` = skill × 8 | wrong | A skill-12 bot reads as rating 96. Conversion needs calibrating. |

### The day/hour engine

| Task | State | Note |
|---|---|---|
| **Zone-based morale and daily recovery** | model only | `Zone`, `PlayerZoneLoad` and `RecoveryJob` exist, but the match engine never writes `lastPlayedAt` or the load table, so recovery correctly reports zero. Needs the engine to feed it. |
| **Simulate-all is week-based** | not started | Should be day- and hour-accurate, and must include cup ties. |
| **Cup ties in the schedule view** | not started | The schedule shows the week template, not the actual day's ties. All the data exists. |
| Penalty shootouts | not started | Penalties are awarded but never taken. Also blocks cup progression: a level knockout tie has no winner, so round 2 cannot be drawn. |

### Match engine realism

| Task | State | Sprint |
|---|---|---|
| **Goal inflation chain** — 4 fixes: on-target probability, aim clustering at the posts, goal mouth width 9 m → 7.4 m, `POST_RADIUS` | not started | S1.1 |
| **Restart inversion** — 40 goal kicks / 19 throw-ins / 5 corners is backwards | not started | S1.2 |
| **Corner skew** — 0.6 HOME / 4.4 AWAY | not started | S1.3 |
| **Duel count 598/match → ~120** — interposition requirement, lane-strict reception, context-dependent radius | not started | S1.4 |
| **Bounded chase multiplier + press radius** | not started | S1.5 |
| **Injury model into the proposal engine** | not started | S1.6 |
| **Box fouls under-produced** | deferred 2026-09-26 | S1.7c |
| **T1 engine defect** — a 1-1 with 38-4 shots and 9.5-0.9 xG. Needs a real event dump before it can be diagnosed | not started | — |

### Clubs, academy and transfer market

| Task | State | Sprint |
|---|---|---|
| **AI demand model and a live market** | partly built 2026-09-26 | S3.6 |
| **Transfer activity feed** | seeder only | — |
| **AI clubs train** | partly built 2026-09-27 | S4.7 |
| **Facilities** items 4–5 | backlogged | S4.4 |
| **Scouting** — the reports the network feeds | deferred by owner | S5.1 |
| **Academy structure** — 5 of 8 done | 3 outstanding | S5.3 |
| **Junior pathways** — 2 of 5 done | 3 outstanding | S5.4 |
| **Scouting produces intel, never signings** | not started | — |
| **Club can end a season with no youth pipeline** — shutting the school in week 12 dumps the intake | not started | — |
| **Intake is a purchase, so graduation collides with money** | not started | — |
| **Loan out** — wiring on a service that already exists | not started | S5.4 |
| ~~S3.5 work permits~~ | removed by owner | — |
| ~~S4.1 individual training focus~~ | removed by owner 2026-09-28 | — |

### Codebase and frontend debt

| Task | State | Sprint |
|---|---|---|
| **Regression test that every fixture uses the proposal engine** | not started | S7.1 |
| **Make AI-vs-AI fixtures inspectable** | not started | S7.2 |
| **13 routed-but-unreachable pages** — all 13 have a router case and **no menu entry**. Navigation only; the router is done | not started | S8.1 |
| **League table: three comparators, one implementation** — `MatchStatisticEngine:231-306`, `CountryController:109-112`, `SeasonService:745-753`. Also `ensureEntriesForSeasonCompetition` deletes and rebuilds all entries on membership drift | not started | S8.4 |
| **Remaining frontend debt** | not started | S8.3 |
| **Presentation and realism content** | not started | S8.5 |
| **Freeze `demo/service/` as a reference module** | partial | S6.2 |
| **Documentation rewrite** | mostly done 2026-09-27 | S6.5 |
| **Deployment infrastructure** | deferred until the instance goes up | S8.6 |

---

## ✅ Done this session

Kept so the next session does not redo them.

| Done | Verified by |
|---|---|
| **Legacy 9-country list removed** — the hard-coded list that created `HRV`/`DEU`/`GBR` on every reset, which is why the world kept showing 51 countries | 48 countries, no legacy rows |
| **Catalogue runs on the bootstrap path** — without it a fresh install built a pyramid in a world with no countries | 45 test errors → 0 |
| **`WorldIntegrityService`** — reports and repairs; runs after every boot step; `GET /admin/world-integrity`, `POST …/repair` | 48 countries / 96 sides / 48 squads / no legacy rows |
| **Boot seeding is no longer one transaction** — the catalogue and national sides run in `REQUIRES_NEW`, so a failure cannot mark the boot rollback-only and throw away the world | commit no longer throws `UnexpectedRollbackException` |
| **Initialize DB builds the whole world** — it called the pyramid step directly, so Reset + Initialize left 1 country and 0 national teams | now goes through the boot entry point |
| **Every country starts at 1500** | `STARTING_RATING` |
| **Scouting band moved to 1400/200** — the old 40/60 band clamped every country to zero reach on a 1500-based world | `ScoutingReachTest` |
| **National teams, cup, coaching, and page routing** | 722 tests green |

---

## 🔴 Standing rules

**Never hand back a half-built world.** Every seeding path must end in a world that passes the
integrity check. Two sessions were lost to this: one where a caught exception rolled the whole boot
back, one where Initialize DB never ran the world seeders at all. `ensureBaselineDataOnStartup()` is
the single entry point — anything that builds the world goes through it.

**A successful job must be shown to have changed data.** Not logged as done — actually observed in
the database afterwards.

**Verify against a live database, not the shape of the log.** The log said `healthy=true` in a boot
that then rolled back everything.
