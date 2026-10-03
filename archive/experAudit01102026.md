# TIFO Football Manager — Expert Audit, 2nd Edition

**Date:** 2026-10-01
**Baseline audited against:** `expertAudit.md` dated 2026-09-26 (commit `9f44d4a` era)
**Commits since that audit:** **220** (`git rev-list --since=2026-09-25 HEAD | wc -l`)
**HEAD at time of writing:** `7f8763a` — *"Record the confirmed Oracle seed, and the finding in dataFixSuggestions that needs checking"*
**Scope:** UI Football only — the SPA at `/dashboard.html`, backend `newLogic/` + `commonmanager/`.
Excluded: `footballtextmanager/`, `americanfootballmanager/`, `basketballmanager/`.

**Method:** every load-bearing claim in the previous audit was re-verified against source, not
against the progress logs. New findings were hunted in the code that landed since. Claims the
previous audit got wrong are corrected in place and marked.

**Two corrections to my own prior work are recorded in §0** — one of them is in a document I
wrote today, and it is the same class of error this project has made before.

---

## 0. Corrections to previous audits

Recording these first because two of them change what a reader should do next.

### 0.1 ⚠️ I was wrong yesterday, in `dataFixSuggestions.md` §1.1

`dataFixSuggestions.md` (written 2026-10-01, earlier today) claimed:

> `ZoneLoadService.applyDailyRecovery()` … **never calls `playerRepository.save(player)`** — so
> `RecoveryJob` writes zero rows and still records `job_run` status `DONE`.

**That is wrong, and I have corrected the file.** The method is `@Transactional` and
`players.findByLastPlayedAtIsNotNull()` returns **managed** entities, so JPA dirty checking flushes
the `setMorale()` change at commit. The write lands.

This is the **second** time this project has read "no explicit `save()`" as "no write" — the
first was the `REQUIRES_NEW` backfill recorded in `sprintProgress.md` as *"A test that passed while
proving nothing."* Hibernate flushes managed state. Absence of `save()` is not absence of a write.
The lesson is already written in the file I got wrong.

The correction did surface two **real** defects in the same three lines, which I had missed by
reading too fast in the other direction: the computed recovery amount is discarded (flat `+0.2`
regardless of minutes played, contradicting the class's own javadoc), and there is no upper clamp
on morale. Both are in the corrected §1.1 of `dataFixSuggestions.md`.

### 0.2 The previous audit's §3.7 / §5.5 injury finding was stale, and the competitive analysis was right

`expertAudit.md:584` said the injury generator was *quarantined* and *"the live proposal engine
currently has NO injury generator at all."* `competitive_analysis.md:438` scored Injuries
**✅ "fatigue-driven, 8 types."**

**Both were describing different moments. The competitive analysis was right about the feature;
the audit was right about the date.** `sim/engine/InjuryService.java` is the live generator today:
8 types (`KNOCK, HAMSTRING, ANKLE, KNEE, GROIN, CALF, FRACTURE, CONCUSSION`), fatigue-driven
(`BASE_CHANCE_PER_TICK 0.000045`, `FATIGUE_THRESHOLD 0.5`, `FATIGUE_SLOPE 0.00022`), selection
weighted by fatigue, ticks 8×40–88×40, constructed at `MatchOrchestrator:200` and ticked at `:652`.
Its own header records that it was ported from the quarantined `engine_v1` generator because the
live engine *"had been producing zero injuries."* Covered by `InjuryServiceTest`.

**Do not "re-fix" this.** The rule from the previous audit stands, restated: verify deadness by
walking a caller chain to an HTTP endpoint, never by reading a commit message or a doc.

### 0.3 Two more corrections worth carrying

- `expertAudit.md:844` claims `NewMatchSimulatorTest` and `NewMatchControllerTest` exist. **They
  do not, and never have.** Four other classes named in the same `AGENTS.md` Testing Layout block
  (`TifoBackendIntegrationTest`, `TifoE2ETest`, `TifoUITest`, `ZoxReplayServiceTest`) are also
  absent. The doc describes a suite that was replaced wholesale.
- `expertAudit.md:339` said `AERIAL_DUEL_RADIUS` and `SHOT_BLOCK_RADIUS` are *"computed but
  unreachable."* There is still **no Z axis** and crosses are still never contested in the air —
  that part holds. But the previous audit's §5.1 "goal mouth is 10 m, not 14 m" correction stands,
  and `GoalPhysical.POST_RADIUS` is still `0.03` cells = 0.3 m against a real 6 cm post, with a
  comment that still says `0.42 m`.

---

## 1. Executive verdict — what changed in five days

The previous audit opened with:

> *You have a **genuinely excellent match engine bolted onto a non-existent management game**.*

**That is no longer accurate.** In 220 commits the management layer went from absent to present, and
the test suite went from a rounding error to a fifth of the codebase. The engine is no longer
alone.

| Dimension | 2026-09-26 | 2026-10-01 | Movement |
|---|---|---|---|
| Economy | **0/10** — one `Double`, seeded to 0.0 | **~6/10** — ledger, wages, gate, sponsorship, prize money, staff, stadium, FFP, board trust | **+6** |
| Transfers | **2/10** — prose strings, one dice roll | **~6/10** — real offers, multi-round negotiation, windows, contracts, loans, free agents, auction | **+4** |
| Training | **3/10** — 2 knobs, no trade-offs | **~6/10** — intensity vs injury, minute-proportional, coaching staff reaches the engine | **+3** |
| Meta layer | **0/10** — cannot be sacked | **~3/10** — board trust computed, morale live, no sacking, no retirement | **+3** |
| Juniors | **3/10** — raw value shown | **~5/10** — talent hidden behind a narrowing range, scouting network live | **+2** |
| World | **310 clubs, Serbia only** | **48 countries × 310 clubs, 96 national sides, 3 populated international cup tiers, a cup that actually plays** | large |
| Test/main ratio | **3.8%** (20 files / 3,605 LOC) | **24.4%** (117 files / 22,245 LOC) | **×6.4** |
| P0 exploits | 3 live | **0** | ✅ |
| P0 security hole | `/api/**` public | **closed** | ✅ |

**The single most important change is not a feature. It is that four of the previous audit's P0
exploits are now covered by regression tests that were written when the fix landed.** `€1 → any
player`, infinite training, fatigue that never recovered, penalties never taken, and a
transfer-list soft-lock are all fixed, and all five fixes are pinned by named test classes
(`TransferServicePriceGuardTest` 10, `TrainingProgressionIdempotencyTest` 8, `PenaltyEngineTest` 8,
`SubstitutionServiceTest` 13, `WeeklyFinanceServiceTest` 9). That is the difference between a fix
and a fix that survives.

**The new problem is scale.** The world went from 310 clubs to **~14,880** in five days. A large
fraction of the new code was written for 310 and is now asked to handle 48×. That is where this
audit's P0s live — not in the management layer any more, but in the day-job framework, the game
clock, and the persistence layer.

**The other new problem is that the documentation has now diverged from the code in the opposite
direction.** `PROPOSAL_SEASON_REPORT.md` and `PROPOSAL_CURRENT_STATE.md` are three calibration
generations behind; `competitive_analysis.md` rates Economy 0/10 and Transfers 2/10 when both are
substantially built; `AGENTS.md` still documents four engine classes that do not exist. **You now
have a codebase that is materially better than its description.** See §13.

---

## 2. Scorecard — every finding from the 2026-09-26 audit

`FIXED` = code and tests both. `PARTIAL` = built but the wiring or the consequence is missing.
`OPEN` = unchanged. `REGRESSED` = worse than described.

| # | Previous finding | Verdict | Evidence |
|---|---|---|---|
| **P0 — blocking defects** ||||
| 3.1 | No economy; `Team.budget = 0.0` for all 310 | **FIXED** | `FinanceLedgerEntry` + `FinanceCategory`; `WeeklyFinanceService.applyWeeklyFinances` writes 9 lines (gate, broadcast, merchandising, prize, player wages, staff wages, sponsorship, facility upkeep, junior upkeep); called per club per week at `SeasonService:529-530`; `FinanceJob` day 2 h10; `EconomyProfileService:34-39` seeds per-tier budgets 3M–8M down to 3k–12k and reputation 72–94 down to 22–30, so **clubs are no longer all reputation 50**; `Player.getEarnings()` now has **13 call sites**; `club-management.js:43-52` calls four real endpoints and renders an explicit "not available" panel instead of inventing numbers |
| 3.2 | Free players for €1 | **FIXED** | `TransferService.resolveAgreedPrice:697-727` — null→floor, non-finite→`INVALID_PRICE`, `<=0`→`INVALID_PRICE`, below-floor→`PRICE_BELOW_ASKING`; used by both buy paths; second guard in `completeTransfer:575-581`; `TransferServicePriceGuardTest` 10 tests |
| 3.3 | Infinite free training | **FIXED** | `TrainingProgressionService:129-135` throws `TRAINING_ALREADY_RUN` on an existing `(team, season, week)` report unless `force`; `runWeeklyTrainingIfDue:101-121` returns the stored report so week-advance still works; `TrainingIntensityService` now charges fatigue; `TrainingProgressionIdempotencyTest` 8 tests |
| 3.4 | Sub-integer training invisible | **STILL FIXED** | `Skills.getRatingScore:128-143` reads `getExact`; 8 tests in `SkillsExactRatingTest` verified green today |
| 3.5 | Fatigue never recovers; UI text false | **FIXED** | `SeasonService.recoverFatigueForWeek:562-596` — 22/week scaled by age; called from advance-week `:418` **and** `WeekRolloverJob:88`; `SimMatchService.persistMatchCondition:411-416` now persists the engine's 0–1 fatigue as 0–100, so there is something to recover; `medical-view.js:95` text is now true |
| 3.6 | Penalties awarded but never taken | **FIXED** | `sim/engine/PenaltyEngine.java` (372 lines) — taker selection scoring `striker*1.2 + technique` excluding GK, keeper read `BASE_READ = 0.33` with `wrongSide` excluding the taker's own side, a **committed** dive 0.30–0.65 cells, conversion with `BASE_SAVE_IF_READ 0.42` / `BASE_SAVE_IF_WRONG 0.06`; all three channels now emitted; `MatchOrchestrator.checkPenaltyStall:107-145` forces the kick after 40 ticks; `PenaltyEngineTest` runs **4,000 seeded penalties** asserting 70–82% |
| 3.7 | No substitutions, ever | **PARTIAL** | Bench populated (`SimMatchService:102-107` → `RealSquadFactory.buildBench`, 7 reserves); `MAX_SUBSTITUTIONS = 5`, `MAX_SUB_WINDOWS = 3`; `SubstitutionService:58` `if (off.isSentOff()) return false;`; `FATIGUE_SUB_THRESHOLD = 0.62`; 13 tests. **But** `ConditionalSubstitutionRules` is constructed with an empty list every match (`MatchOrchestrator:199`) and `SubstitutionPlanController` persists JSON nothing reads. See §5.1 |
| 3.8 | Transfer-list soft-lock | **FIXED** | `hasPricedOffer:650-652` asks the offer records, not a string test; three escape routes (`withdrawInterest` by team **id**, `clearAllInterest`, admin `forceUnlist`); wired to the UI. **Untested** — no `removeFromTransferList` coverage in `src/test` |
| **P1** ||||
| 4 | Four engines, one live | **RESOLVED (was already)** | Previous audit resolved this on 2026-09-26. The name fossils are gone: `/start-realistic-demo` removed from the permit list, no controller anywhere, `demo.js:634` POSTs `/simulation/current-round/prepare` |
| 5 | Calibration (goals 5.4, 598 duels, 40.7 GKs) | **FIXED** | See §6. Current: goals 3.55, shots 35.7, SOT% 28, pass 85%, duels **268** (from 598), goal kicks 21.9 (from 40.7), corners 6.4, fouls 12.0, reds 0.15. The restart inversion, the goal-mouth comment, the save model and the duel firing rule were all fixed |
| 5.3 | Restart inversion | **FIXED** | `ActionExecutor:385-390` — `power = sqrt(2 × GROUND_DECEL × range)` clamped, launched **grounded**. `GROUND_DECEL 0.08` / `AIR_DECEL 0.03`. Goal kicks 35.9 → 21.9 |
| 5.4 | Corner skew 0.6 vs 4.4 | **STILL OPEN** | `sprintBacklog.md:694` admits *"no diagnostic was ever written."* `PitchEnvironment.oobRestartType:53-64` still decides by **row only**, no column awareness. See §7.3 |
| 5.5 | 598 duels | **FIXED** | `DuelEngine:74-83` `contestsTheBall()` — a defender level with or behind the carrier only contests at `SIDE_ON_TIE_RADIUS = 0.08`; `DUEL_SKILL_SENSITIVITY = 0.18` logistic `pWin = 1/(1+e^(−0.18·gap))` replaces deterministic noise; one contest per tick; 598 → 268 |
| 6 | Training 3/10 | **LARGELY FIXED** | See §5.2. Intensity `LIGHT/NORMAL/VERY_HARD` with injury chance 0 / 0.4% / 4.5%; `TrainingPercent.percentFor` weights **minutes played**; head coach reaches the engine at `SimMatchService:161` (`coachFactorFor`); `SquadTrainingService.trainEveryClub` trains **all** clubs. Two caveats in §5.2 |
| 7 | Juniors 3/10 | **PARTIALLY FIXED** | `ScoutingService` (251 L) + `ScoutAssignment` + `ScoutingController`; `Country.youthRating` is **read in 4 places** (the dead hook is closed); talent hidden behind `TalentRange` narrowing to ±1 at promotion. **But** `academySkillExact` is still shown raw, and carryover juniors are **still frozen** (`YouthAcademyService:102-107`) |
| 8 | Transfers 2/10 | **LARGELY REBUILT** | See §5.3. `Set<String>` prose gone; `TransferOffer` entity; `NegotiationService` 5 rounds on fee/wage/length; `TransferWindowService`; `PlayerContract`; `Loan`; free agents. **But** the seller still cannot choose, and there is no listing fee, no sale tax, no anti-daytrade, no work permits |
| 9.1 | Morale inert | **FIXED** | `MoraleService` (220 L) is live: `SimMatchService:374` `applyMatch` per match on minutes/goals/assists/rating/result; `moraleModifier` feeds growth at `TrainingProgressionService:494` |
| 9.3 | `Player.form` a constant | **FIXED** | `MoraleService:126` `setForm` per match, driven by what the player did. The `formBoost` in `PlayerDTO:90` and `(form − 6.0) × 1.2` in `MatchRatingCalculator:51` now respond to a run |
| 9.4 | No meta-layer | **PARTIAL** | `BoardExpectationService` — FFP bands, trust 0–100 from 60, `TRUST_SACKING_REVIEW = 20`. **But** `sackingReview` is a **read-only boolean** surfaced at `FinanceController:124`; no `BoardExpectation` entity, no persistence, no end-of-season review. **Cannot be sacked.** No retirement. See §5.4 |
| **P2** ||||
| 10.1 | 5 empty stub services | **OPEN — all 5** | `CompetitionController` 7 L / 0 routes; `StadiumController` 8 L / 0 routes (alongside a *live* `StadiumSettingsController` with 7 routes); `LeagueService` 9 L / 0 callers; `TrainingService` 21 L / 0 callers **and holds an un-injected `playerRepository` that would NPE**; `StadiumService` 7 L / 0 callers. `NewLogicTacticsService` now has 0 callers *on top of* the `new FormationSlotCatalog()` defect. **Nothing in this group was touched in 220 commits** |
| 10.1 | `DummyDataController` 100% hardcoded | **OPEN — untouched** | 281 lines, 18 routes, last modified 2026-07-30. Still 0 DB access, still hardcodes team id 1. 8 live frontend call sites across 5 files |
| 10.1 | `MatchPersistenceService` dead | **OPEN** | 402 lines, 0 callers. Notably now load-bearing for a *test's* allow-list (`ProposalEngineIsTheOnlyFixtureProducerTest:98`) while having no production call site |
| 10.1 | `MatchEventRepository.save()` is a no-op | **OPEN** | Still a `ConcurrentHashMap` whose `save()` returns its argument. Match events live only in `match.eventJson`; the 52 classes in `newLogic/model/event/` have no table |
| 10.2 | 13 dead frontend routes | **PARTIAL** | 4 wired and working (`results`, `training`, `topScorers`, `topAssists`); 3 real-but-unreachable; **6 still point at `/demo/…` and still crash on a missing `response.ok`** (`pages.js:948, 956`, `fixture-view.js:44, 257`) |
| 10.2 | `fetchPlayerRatingSummary` 1-arg call | **OPEN** | `utils.js:382` has no default for the 2nd param; `player-view.js:610` and `league-view.js:453` still pass 1 arg. Average rating is still permanently `—` on both player pages. The correct pattern (`fetchPlayerTransferStatus`, a closure over `authFetch`) sits in the same file |
| 11.1 | `/api/**` in `permitAll` | **FIXED** | Permit list is now a short, deliberate set. `/teams/**`, `/players/**`, `/matches/**`, `/training/**`, `/countries/**`, `/api/**`, `/proposal/api/**` all removed. Two documented loopholes remain — see §8 |
| 11.1 | Unvalidated `sortBy` ×3 | **OPEN** | `PlayerController:48-53`, `TeamController:95`, `LineupController:27` |
| 11.1 | Raw entity create, no auth ×2 | **OPEN** | `TeamController:103-106` and `PlayerController:43-46` both `return xRepository.save(x);` — no `@Valid`, no `@AuthenticationPrincipal`, no ownership check, raw entity echoed back |
| 11.1 | `LineupController` raw `RuntimeException` | **OPEN** | `:35`, `:41` → 500 with a leaked message. Zero tests reference `LineupController` |
| 11.2 | Duplicate sidebar handlers | **FIXED** | `app.js` deleted; `#clubSidebar` removed from every page; `sidebar.js:22-37` records why |
| 11.2 | `escapeHtml` ×3 | **PARTIAL** | 5 copies → 3. `ui/escape.js:18` is canonical, but `htmlEscape` is still defined **byte-identically** at `utils.js:4` and `pages-renderers.js:4-11`, and `pages-renderers.js` does not import it |
| 11.2 | `authFetch` ×2 | **FIXED** | `clock.js:1-11` is now a 10-line comment naming the defect plus an import |
| 11.3 | `ensureEntriesForSeasonCompetition` wipes data | **FIXED — genuinely good** | `SeasonService:162-220` now applies a **difference**: early-return when sets match, delete only orphans, create only missing, explicitly not recreating existing rows so the record survives |
| 11.3 | Two week-advance implementations | **PARTIAL** | The *request path* is unified onto the job framework (`SimulationController:269-274` documents it; commit `84dccca`). **But `AdvanceWeekAsyncService` is still there, 186 lines, 0 callers, and it is the only remaining caller of the legacy inline `advanceWeekAndHandleSeasonTransition`** with a hardcoded `findById(1L)`. See §4.5 |
| 11.3 | Team name collision / `setCompetition` reassignment | **FIXED** | Global id set at `DatabaseInitializer:1176` with a javadoc naming the bug; `addTeamToLeague:1295-1305` **refuses to move a club** and logs; `PyramidBuilder.clubName:343-348` now emits `GER FirstA FC01` and the old form *threw* on a duplicate `findByName` |
| 11.3 | `Team.competition` still authoritative | **OPEN** | Still written by three seeders, still moved by `SeasonService:764`, still what `UserController:167-171` reports to the SPA. `CompetitionEntry` is written alongside but is not the source of truth |
| 11.3 | `PromotionRule` seeded, never read | **PARTIAL** | `PromotionRuleRepository` is now injected (`PyramidBuilder:58,67,75`, `DatabaseInitializer:135`) but every call is `save()`. `getPromotionSpots|getRelegationSpots|getHasPlayoff|getHasSeeding` return **zero reads**. `PyramidBuilder:360-366` documents the hole itself |
| 11.3 | `buildPlayoffSummary` hardcodes 9–10 | **OPEN — and worse** | `SeasonService:910-918` uses literal indices 8 and 9 while `applyPromotionRelegationForLeague:800-822` computes dynamically from `teamsPerCompetition` and `movementSlots`. The apply-side became dynamic; the summary did not. See §4.6 |
| 11.4 | Test coverage 3.8% | **FIXED — largest single win** | 20 → **117** files, 3,605 → **22,245** LOC, 176 → **864** `@Test`. `SeasonService`, `TransferService`, `TrainingProgressionService`, `YouthAcademyService` all now covered. **Caveat in §3** |
| 12 | Registration broken | **FIXED end-to-end** | `POST /auth/register` (`UserController:52`), `RegistrationRequest` entity, `POST /admin/registration-requests/{id}/{action}` (`AdminController:74`), queue endpoint, `RegistrationService` now called, `register.js:100` and `community.js:184` wired. **The multiplayer gate is open** |
| 12 | `Country.youthRating` dead | **FIXED** | 4 read sites + surfaced in `country-view.js:597` |
| 12 | `GameClock.currentPhase` never written | **OPEN** | `SeasonPhase` enum and the field are untouched; `APIController:265` still hardcodes `phase = "Season in progress"`. Now also **semantically redundant** — `GameDay` + `GameHour` + kickoff gating replaced it |
| 13 | Documentation is a liability | **WORSE** | See §13 |

**Tally: 24 FIXED · 8 PARTIAL · 22 OPEN · 2 regressions · 1 clarified.**

**Nothing in the "empty stub services" group was touched in 220 commits.** The management layer got
a great deal of attention and the long tail of 7-line files did not.

---

## 3. The test suite — the biggest change, with one honest caveat

| Metric | 2026-09-26 | 2026-10-01 |
|---|---:|---:|
| Test files | 20 | **117** |
| Test LOC | 3,605 | **22,245** |
| Main LOC | 94,165 | 91,289 |
| **Test/main ratio** | **3.8%** | **24.4%** |
| `@Test` annotations | 176 | **864** |

Named suites that pin the fixes from §2: `TransferServicePriceGuardTest` (10),
`TrainingProgressionIdempotencyTest` (8), `PenaltyEngineTest` (8), `SubstitutionServiceTest` (13),
`WeeklyFinanceServiceTest` (9), `SkillsExactRatingTest` (8), `ZoneLoadRecoveryTest` (5),
`PromotionLadderTest`, `SeasonShapeTest`, `GameClockWeekAdvanceTest`, `LeagueTableOrderTest`,
`YouthAcademyGraduationTest`, `JuniorSchoolServiceTest`, `JuniorDecisionWindowTest`,
`AcademyQualityTest`, `JuniorDevelopmentTest`, `ScoutingServiceTest`, `ScoutingReachTest`,
`TransferCompletionTest`, `TransferFeeServiceTest`, `TransferWindowServiceTest`,
`OmladinacTransferJourneyTest`, `ConditionalSubstitutionRulesTest`,
`GoalkeeperEngineTest` (11), `RestartTakerArrivalTest`, `SimReplayFidelityTest`,
`DisciplineAndRestartTest`, `TacticsPossessionContextTest`, `SimTeamFactoryMirrorTest`,
`RealSquadFactoryTest`, `RealSquadSimulationSmokeTest`, `SimMatchPersistWiringTest`,
`InjuryServiceTest`, `StoppageClockTest`, `GoalCelebrationAndClockTest`,
`ProposalViewerMatchIdentityTest`, `HeadCoachReachesTheEngineTest`, `ApiExceptionStatusTest`,
`ApiExceptionStatusTest`, `KickoffHalfLineTest`, `DefensiveShapeTest`, `CountryActivationTest`,
`TrainingViewNoShadowedDeclarationsTest`, `ProposalEngineIsTheOnlyFixtureProducerTest`,
`ReservedWordColumnTest`, `SimTeamFactoryMirrorTest`.

Also worth naming, because they are the kind of test that prevents a whole class of regression:
`ProposalEngineIsTheOnlyFixtureProducerTest` (asserts no code path produces a fixture except the
proposal engine), `TrainingViewNoShadowedDeclarationsTest` (the indentation-scoped duplicate-
declaration guard that found the shadowed `render`/`renderGraph`/`openPlayerGraph` in
`training-view.js`), `DefensiveShapeTest`, and `ApiExceptionStatusTest`.

**The caveat, stated plainly:** the `791 green, exit 0` figure is from commit `941cf5e`, which is
**26 commits behind HEAD**, and **11 of those 26 touched `src/test`**. The 864 `@Test` count is
larger than 791. **The current suite has no recorded green run.** I did not execute `mvn` for this
audit. Run `mvn test` before trusting any number above, and update `sprintBacklog.md:16` and
`kanban.md` when you have it.

Two structural gaps remain in the coverage, and they are the same gaps as before:

- **Zero tests for `LineupController`, `PlayerController`, `TeamController`, `UserController`,
  `AdminController`, `CommunityController`, `DummyDataController`, `CompetitionController`,
  `StadiumController`.** The entire §5/§8 security surface is untested. Only 3 tests exercise a
  controller at all (`ApiExceptionStatusTest`, `ProposalViewerMatchIdentityTest`,
  `SubstitutionPlanControllerTest`).
- **Season fixtures use calendar years.** `WeeklyFinanceServiceTest`, `StaffSponsorServiceTest`,
  `PlayerContractServiceTest` pass 2024/2025/2026 into `seasonYear`/`expirySeason`. The project
  deleted `BASE_SEASON_YEAR` and moved to a season *number*; these tests are self-consistent under
  either scheme, so **they do not pin the semantics at all.** No test would fail if someone
  reintroduced calendar-year arithmetic.

---

## 4. 🔴 P0 — New: the day-job framework will double-apply and cannot recover

This is the most serious new section, because the framework is new, it is the backbone of
scheduled play, and its central safety claim is false.

### 4.1 The `job_run` guard row is written *after* the job body, so it does not prevent double-apply

`newLogic/jobs/JobRunner.java:139-147`:

```java
requiresNew.executeWithoutResult(status -> job.run(...));      // 140-141  body commits
record.setStatus(DONE);
requiresNew.executeWithoutResult(status -> runs.save(record));  // 145     guard row, separately
```

`runOnce` reads the guard with **no lock** at `:116`, then runs the body. Two concurrent
`runDue` calls — two users clicking Advance Day, or the scheduler tick racing a button — both see
no row, **both execute the job**, and only the second `save` trips
`uk_job_run_season_week_day_key` (`JobRun:35-37`).

**The class javadoc claims the opposite.** `JobRunner.java:26-28`: *"two concurrent runner
invocations cannot both decide the job is outstanding."* The decision is made **and acted on**
before the constraint exists. The unique constraint saves the *row*; it does not un-apply the
second `FinanceJob`, which is **a double wage bill for every club in the world**.

There is also a crash window: a JVM death between `:141` and `:145` leaves the work committed and
the guard absent, so the job re-runs in full on the next boot — exactly what the design says it
prevents. The correct shape is insert-`PENDING`-then-flip-to-`DONE`, which is used nowhere.

### 4.2 `FAILED` is terminal and there is no re-queue path anywhere

`JobRunner.java:124-130` returns immediately on a `FAILED` row. Four files promise an operator
re-queue — `JobRun:30-31, 46`, `JobRunner:32-33`, `GameClockScheduler:21`, `APIController:79-80` —
and `JobRunRepository` has **no** delete or reset method and no caller that could. `/api/jobs/run-due`
is documented as *"for the case where a job failed and has been re-queued."* **It can never re-run
a failed job.**

Consequence: **one transient throw permanently disables that job for that (season, week, day).**
`FinanceJob` is safe — `SeasonService:532-535` catches per club. `MatchdayJob` is not: it throws
if `competitions.findAll()` or `fixture.getCompetition().getId()` NPEs, and one such failure means
**the whole matchday is never played and never retried**, while a `job_run` row says `FAILED` and
nothing tells the owner.

### 4.3 `advanceWeek()` runs 168 independent transactions, each of which can fire jobs

`GameClockService.advanceHours:230-232` is `@Transactional` and calls `advanceHour()` at `:237` —
a **self-invocation**, so the proxy is bypassed and no transaction opens. `advanceHour` is itself
`@Transactional` (`:139`) and is likewise self-invoked, so its annotation is inert too.
`advanceWeek()` (`:212`) is therefore **168 separate transactions**, each committing
`clocks.save(clock)` and each calling `jobRunner.runDue` in between.

`JobRunner.java:56-60` writes a long comment identifying exactly this trap. It is then walked into
one layer up.

A crash or timeout at step 100 of 168 leaves the clock at week+5-days with 100 hours of jobs
applied and 68 not, with no reconciliation pass.

### 4.4 Any authenticated user can advance the entire world

`SecurityConfig.java:113-114` permit-alls `/api/server-time` and `/api/game-clock` — the GET only,
since the permitAll pattern is an exact path. Everything else falls to `.anyRequest().authenticated()`
at `:140` **with no role requirement.** Therefore:

- `POST /api/game-clock/advance` (`APIController:59-68`) — any registered manager advances all 48 countries.
- `POST /api/game-clock/advance-to-hour` (`:71-74`) — same.
- `POST /api/jobs/run-due` (`:83-92`) — same.

The admin restriction exists **only in the browser**: `dashboard.js:205-207` wraps the three
buttons in `isAdminUser()`. The codebase already has the correct maxim written down —
`CountryController.java:210`: *"The client hides the button, but a hidden button is not a
permission."* The clock endpoints break that rule.

`amount` is also unvalidated and unbounded: `advanceHours(amount)` and `advanceHours(amount * 24)`
at `:64-65`. `amount = 2147483647` runs a 2.1-billion-iteration loop, each iteration doing a
`findById` + `save` + a 10-job `runDue` scan. `amount * 24` overflows negative above 89,478,485 —
so the failure mode differs by value, and nothing bounds it either way.

### 4.5 The season counter is incremented twice, so every rollover skips a season

`WEEKS_PER_SEASON = 12` (`SeasonCalendar:56`); `SeasonRolloverJob.week() = 12`; the 168-hour walk
does pass through `(12, 7, 23)`, so **the old "the rollover can never fire" claim is stale — it is
reachable.**

The new defect: `advanceHour` wraps the season at `GameClockService:152-155`
(`week > 12 → week = 1; season += 1`) **and** `performPromotionRelegationAndNewSeason`
independently does `clock.setCurrentSeason(getCurrentSeason() + 1)` (`SeasonService:605`).

1. step to `(12, 7, 23)` → `advanceHour` saves → `afterMove` → job runs
2. `SeasonRolloverJob` → `SeasonService:604-608` sets season 13, week 1, saves
3. next `advanceHour`: `hour 23→0`, `day 7→8 > 7`, `week 12→13 > 12` → `week = 1`, **`season = 14`**

**Season numbers skip one per rollover: 12 → 14 → 16.** `SeasonService.getOrCreateClock:83-91`
only rescues `currentSeason > 1000` or `< 1`, so the skip is permanent.

### 4.6 `buildPlayoffSummary` can now disagree with what was actually applied

Carried over from §2 and **worse than the previous audit found.** `SeasonService:910-918` names
relegation via literal indices `top.get(8)` and `top.get(9)`. `applyPromotionRelegationForLeague`
computes the same thing dynamically from `expectedTeams` and `movementSlots` (`:800-802`, loops
`:816-822`). For any league that is not exactly 10 clubs with 2 child leagues, the player-facing
summary names the wrong clubs. The apply-side became dynamic in this window; the summary did not,
so the divergence window is **wider** than when the previous audit flagged it.

### 4.7 Two live week-advance implementations, both doing the same work

`GameClockService.advanceWeek()` drives the job framework. `SeasonService.advanceWeekAndHandleSeasonTransition`
(`:410-469`) does the week's work **inline** and never touches the clock. It is still reachable via
`AdvanceWeekAsyncService:87-88` — 186 lines, a live `@Service` bean, **0 callers**, holding a
hardcoded `competitionRepository.findById(1L)` at `:59` and a Serbia-only loop
`seasonService.getSerbianLeaguesInOrder()` at `:114`.

Five operations appear in **both** paths: `recoverFatigueForWeek`,
`settleWeeklyFinancesForAllClubs`, `expirePlayerContracts`, `loans.closeFinishedLoans`,
`transfers.simulateWeeklyMarketActivity`. The job version also does youth intake; the legacy
version also does injuries. Wiring the orphan back would double-apply the week.

**Fix: delete `AdvanceWeekAsyncService`.** It has no callers, it is the sole holder of the
hardcoded league-1 assumption, and it is the only thing keeping the legacy inline week alive.

### 4.8 `MatchdayJob` runs one query per competition, and there are 16 cup competitions

`MatchdayJob.java:91-98` calls `fixtures.findUnplayedOnDay(seasonYear, weekNumber, day)` **inside**
the `flatMap` — once per matching competition, returning the identical set every time; only the
`.filter` differs. `CompetitionType.CUP` now matches the national cup **plus 15 international
club cups** (`InternationalClubCups.cups()` = 5 tiers × 3), so a cup matchday issues **16 identical
full-table queries**. This is the worst query pattern in the framework and it worsens with every
tier added.

---

## 5. 🟠 P1 — Partially-fixed features, and what is still missing

### 5.1 Substitutions: the engine half is real, the manager half is fiction

Verified true from the previous audit's list: bench populated in the live path (7 reserves), 5 subs
/ 3 windows, injury + fatigue auto-sub (`FATIGUE_SUB_THRESHOLD = 0.62`), and a sent-off player is
explicitly never replaced (`SubstitutionService:58` — with a comment recording that this check
*previously* wrongly blocked the red-card replacement and left teams at ten).

Three gaps:

1. **`ConditionalSubstitutionRules` is constructed with an empty list every match**
   (`MatchOrchestrator:199`). `rules.add` is only ever called from its own `add()`. Nothing in
   `src/main` calls it. So "if losing at 60, bring on X" is dead in the live path — 10 unit tests
   green, zero production callers.
2. **`SubstitutionPlanController` persists JSON nothing reads.** `SimMatchService` has no reference
   to `SubstitutionPlan` or its repository; no JS calls the endpoint. A saved plan never reaches
   the orchestrator.
3. **Substitution counts are collected and never surfaced.** `ProposalStatsCollector:227`
   increments `TeamAcc.substitutions` and `SUBSTITUTION` events are recorded, but `substitutions`
   appears in no outcome, no report and no diagnostic. **There is no way to measure real
   substitution frequency**, and no test asserts that a real `SimMatchRunner.run(..., bench)`
   match produces one.

### 5.2 Training: real depth now, three caveats

What landed: intensity `LIGHT(0.75) / NORMAL(1.00) / VERY_HARD(1.35)` with growth multiplier,
added fatigue and an injury roll of 0 / 0.4% / 4.5% (`TrainingIntensity:46-54`); minute-proportional
training via `TrainingPercent.percentFor(player, coach, skill, minutes)`; coaching staff that
reaches the engine (`SimMatchService:161 coachFactorFor` → `member.matchFactor()` as a per-match
multiplier, pinned by `HeadCoachReachesTheEngineTest`); `SquadTrainingService.trainEveryClub` trains
every club, not just the user's.

Caveats:

1. **The season is 12 weeks, not 19.** The previous audit said training fires 19× per season;
   `SeasonCalendar.WEEKS_PER_SEASON = 12`. Everything downstream of that figure is stale.
2. **AI clubs train but never set priorities and never decline.** The default is the role primary
   skill (`sprintBacklog.md:1255-1280`, items 3–5 open). Aging decay lives inside
   `TrainingProgressionService`, so AI squads never decay either — so **the pyramid is still
   static**, which is the §11.3 of `competitive_analysis.md` concern, still open.
3. **The legacy second engine is still live.** `PlayerSkillProgressionService` (71 lines,
   `baseGrowth = 0.1`, hard cap **17**, and an **inverted** talent factor) is wired to
   `TrainingController:40, 48, 130`. The previous audit called it dead with 0 live callers. **It is
   not dead — it is reachable through a controller.** It contradicts the live engine. `S4.1`
   individual focus was *deleted* by the owner, so the gap is real and the two engines now disagree
   about the cap.

### 5.3 Transfers: rebuilt, but four competitive mechanics are still absent and one is still broken

Landed: `TransferOffer` entity with a real buyer FK, fee, wage, contract length, round, status,
agent fee, expiry and `playerObjection`; `NegotiationService` (448 lines) with `MAX_ROUNDS = 5` and
separate negotiation on fee / wage / length; `TransferWindowService` with `Kind` including
`FREE_AGENT`, `RELEASED`, `LOAN_RECALL`; `PlayerContract` with expiry into free agency;
`LoanService` (248 lines); squad registration limits (`MAX_SENIOR_SQUAD 25`, `MAX_YOUTH_SQUAD 8`);
AI↔AI now happens, weighted by `ClubNeedService.interest`; transfer history exists (rejected offers
are recorded, not cleared).

Still absent or broken:

1. **The seller still cannot choose which offer to accept.** `NegotiationService.acceptOffer(transferId, offerId)`
   exists and correctly rejects the rest — but `TransferController` exposes only
   `/accept-offer/{playerId}` → `TransferService.acceptBestOffer:447-468` → `.max(comparingDouble(fee))`.
   **There is no `offerId` in any controller.** The service can do it; the product cannot.
2. **No listing fee, no sale tax, no anti-daytrade.** Not partial — absent.
3. **No work permits.** Built in full (`WorkPermit`, `WorkPermitService`, a 218-line test,
   `Competition.foreignPlayerLimit`, a gate in `PlayerContractService.sign`), then **removed by the
   owner** in `a6394f9` — *"No foreigner limit for now."* Correctly recorded in `kanban.md:178`.
4. **Loans have no REST endpoint.** `LoanService` is fully built and wired into `SeasonService`
   expiry, but **no controller references it.** The feature is unreachable.
5. **`PlayerSkillProgressionService`-style dead weight in the transfer path:** `isOfferAccepted`
   is still one clamped probability (`TransferService:932-950`) — but it is now only reachable on
   the unlisted direct-buy path, which is correct, since there is no offer thread to accept from.

### 5.4 The meta-layer: trust is computed, sacking does not exist

`BoardExpectationService` is a real model: FFP bands (`COMFORTABLE 0.90 / STRAINED 1.15 /
CRITICAL 1.35`, deliberately forgiving and **shown to the player** rather than hidden), trust 0–100
from a start of 60, `trustScore(health, standing, unhappy, squadSize)`, and a `BoardMood` record
with five human-readable states. The FFP band is a good design choice — Hattrick and FM both hide
it, and showing it turns a punishment into a decision.

But:

- `TRUST_SACKING_REVIEW = 20.0` has **exactly three references, all inside that one class.**
  `FinanceController:124` merely *reports* `sackingReview`. **No `BoardExpectation` entity, no
  persistence, no end-of-season review, no replacement-manager flow, no trust decay across seasons.**
  The class comment says *"Read-only by design"* (`:64-67`) — which is a legitimate scope decision
  and a legitimate thing to be honest about in a comment, but it means **"Manager can be sacked" is
  still ❌**, and `competitive_analysis.md` is right to keep that row ❌ even though the trust score
  underneath it now exists.
- **Still no retirement.** No `findByAgeGreaterThan`, no retirement code anywhere. `PositionGrowthProfile`
  has age ceilings that stop improvement but never decline a player. **The player pool only grows.**
- **Supporter mood/expectations — the cheap 30-line layer `competitive_analysis.md:608` recommended —
  is still not built.** Given that board trust is read-only, this is now the cheapest available
  win in the entire meta layer.

### 5.5 Juniors: talent is hidden, current ability is not; carryover juniors are still frozen

Fixed: `ScoutingService` + `ScoutAssignment` + `ScoutingController`; `Country.youthRating` read in
4 places and surfaced in `country-view.js:597`; **talent now hidden behind `TalentRange`**, a
narrowing band (`±(1+rnd0..3)` at intake → `±1` at promotion) with `talentExact` revealed only when
`canSeeTalent` **and** status is `PROMOTED`/`TRANSFER_LISTED`. `TalentRange`'s doc records the leak
that was closed. This is the Sokker mechanic, implemented properly.

Still open: `academySkillExact` is still populated and displayed raw
(`YouthAcademyService:805`) — so we hide the ceiling and show the floor, which is arguably worse
for academy management than showing both. And **`progressActiveJuniorsWeekly:102-107` still sets
`delta = 0` for any junior whose `arrivalSeasonNumber < seasonNumber`** — a pending 19-year-old
wonderkid develops nothing for a whole season. Also: no separate potential vs current-ability
concept (`talent` is a growth *rate*, `academySkillExact` clamps at 20.99 regardless), and junior
training is still fully decoupled from senior training.

---

## 6. Engine calibration — what the numbers actually are

**Neither `expertAudit.md` §5 nor `PROPOSAL_SEASON_REPORT.md` nor `PROPOSAL_CURRENT_STATE.md`
contains a current figure.** All three predate the Sprint 1 recalibration. The live record moved to
`sprintProgress.md` / `sprintBacklog.md`.

| Metric | Audit 09-26 (25 matches) | **Current** (`sprintProgress.md:2477-2491`, 100 matches) | Real PL | Verdict |
|---|---:|---:|---:|---|
| **Goals** | 5.4 | **3.55** | 2.7 | ⚠️ high, accepted by owner |
| Shots | 29.4 | **35.7** | 25 | ⚠️ 1.4× |
| **On-target %** | 43.9% | **28%** | 33% | ✅ **inverted, now low** |
| Saves | 9.8 | — | 3–4 | — |
| Pass accuracy | 77.9% | **85%** | 80–86% | ✅ |
| **Duels won** | 598.5 | **268** | ~100 | ⚠️ 2.7×, deferred |
| Interceptions | 36.1 | **27.7** | 12–16 | ⚠️ |
| **Goal kicks** | 40.7 | **21.9** | 12–15 | ⚠️ 1.5× |
| **Corners** | 5.0 (0.6/4.4) | **6.4** | ~10 | ⚠️ low, **skew unmeasured** |
| Throw-ins | 18.7 | **77.0** | 35–45 | ❌ **2× too many** |
| Fouls | 29.6 | **12.0** | 22 | ⚠️ low |
| Yellow cards | 5.0 | 2.3 | 4–5 | ⚠️ |
| Red cards | 1.0 | **0.15** | 0.2 | ✅ |
| Penalties | 0.2 (never taken) | **0.24, and taken** | 0.27 | ✅ **fixed** |
| Possession | 49.4/50.6 | 48.7/51.3 | 50/50 | ✅ |
| Nil-draws | 0/200 | 6% | ~6% | ✅ |

The chain that produced this: `S0.6` re-baselined (flagging the old audit as stale) → `S1.0a`
50-match baseline → a save-model fix (saves 14.4→7.1, saves/SOT 1.16→**0.56**, conversion
51.5%→**30%**) → a restart fix (goal kicks 35.9→21.9) → a press fix (duels 373→268) → a penalty
fix (0.07→0.24).

**The calibration discipline is genuinely excellent and worth naming as such:** every change is
measured over ≥50 matches with a committed seed, one calibration per commit, and the H/A split is
printed specifically because it is the mirror-bug detector. That is why the six-defect HOME/AWAY
asymmetry of September was found and killed. It is a development moat neither Sokker nor Hattrick
has, because their engines are not reproducible.

**On-target % is now the mirror image of the old problem** — 28% against a real 33%, where it was
43.9%. The save fix overshot in the other direction. And **throw-ins at 77.0 against a real 35–45
is the largest single remaining outlier** and nobody has looked at it.

**Owner decision, 2026-09-26** (`sprintProgress.md:2474`): statistics are no longer benchmarked
against Premier League figures; S1.1c (shot volume) and S1.3 (corner skew) are **deferred, not
abandoned**. Treat every ⚠️ in that table as accepted-pending-revisit, not as a gate.

---

## 7. 🟡 P1 — New defects in the world/scheduling layer

These all postdate both previous audits.

### 7.1 The national cup draw has **no randomness at all** — and three javadocs say it does

`CupFixtureSeeder.java:62` defines `DRAW_SEED` with the comment *"A fixed seed so the draw is the
same on every boot."* `random` is declared at `:98`, seeded at `:105`, assigned at `:115` — and
**never read.**

`drawRound:394-436` sorts clubs by strength (`:396`), splits in half (`:398`), then pairs
`favourites.get(i)` with `nonFavourites.get(i)` **by list index** (`:412-414`).

> After sorting descending by average squad rating, **the strongest club is drawn against the
> weakest, the 2nd strongest against the 2nd weakest**, in every round of every cup, forever.

Three separate javadocs (`:361-366`, `:368-385`, and `:372-374`'s *"pair one from the top half
against one from the bottom half **at random**"*) describe a draw the code does not perform.
`Collections.shuffle` **is** used — in `InternationalClubCupDraw.buildKnockouts:293-294` — which is
why the international cups *are* random and the national cup is not.

Commit `3ea6dd9` ("Fix the first draw after seeding, which was a constant") fixed the RNG
**seeding** in `SimulationRandom`, not this. The deterministic pairing predates it and survives it.

### 7.2 The cup is hardwired to whichever country owns the lowest-id club — then gives up forever

`CupFixtureSeeder.rankedClubs:178-190` takes the country from **the first club the repository
returns** (`findClubTeamsForOperations` orders by `id`) and discards everything else. With 14,880
clubs across 48 countries that is a Serbia dependency expressed as a `continue` statement.

If the lowest-id club belongs to a 10-club country — a reset seeding countries in a different order,
or any country activated earlier — `ranked.size()` is 10, and `seedIfMissing` returns at `:138-142`
with one `log.warn`. Because the idempotency guard at `:130-134` is `existing > 0` on round-1
fixtures, **the cup is then permanently empty for the life of the database.**

Related: `ENTRY_ROUND_TEAMS = 108` / `MAIN_DRAW_TEAMS = 256` only closes because the country has
exactly 310 clubs. **Any country under 256 clubs has no cup at all.**

### 7.3 Corner skew was never diagnosed and cannot currently be

Carried from the previous audit, still open, now with a stated current total of 6.4.
`sprintBacklog.md:694`: *"no diagnostic was ever written."* `kanban.md:158`: *"not started … measure
it again before acting."* `PitchEnvironment.oobRestartType:53-64` still decides the restart by
**row only**, with no column awareness. The H/A split is now *printed* by `ProposalBatchDiag:83-86`,
but that is a counter split, not a corner-origin diagnostic. Both prime suspects
(`PitchEnvironment:53-64`, `GoalkeeperEngine:93`) are unchanged.

### 7.4 The international fixture seeder gives up permanently, and then reports success

`InternationalFixtureSeeder:66-103` persists the `Competition` row at **line 75** and only then
checks `entrants.size() < 2` at **line 99**. The guard at `:67` is *"does any `INTERNATIONAL`
competition exist."* So with 0 or 1 squad-bearing senior side — the documented current state
(`:33-37`) — the first boot creates the competition, draws nothing, returns, and **can never run
again.**

`MatchdayJob("matchday-international")` then finds the competition, finds no fixtures, logs at
`debug`, and **is marked DONE.** So day 1 hour 20 of every week records a successful international
matchday that played nothing.

### 7.5 The international and cup fixtures are dated 2026, so the zone-recovery window will never match

`InternationalFixtureSeeder:49` and `CupFixtureSeeder:56, 71-74` hardcode `SEASON_START =
LocalDate.of(2026, 7, 1)`. Neither reads `GameClock.currentDate`, and neither adds a season offset.
So every season's cup round 1 gets the **same** `matchDate` as season 1's.

`ZoneLoadService.recoveryFor` / `applyDailyRecovery` window on `currentDate − 2 days` (`:69`, `:127`).
Once the clock passes 2026-07-06, **no cup or international fixture ever falls inside the recovery
window** — zone loads are written and never read, `findLoadsPlayedSince` returns nothing, and
`RecoveryJob` logs "0 player(s) recovered" forever.

### 7.6 `NationalTeamSeeder.squadsFor` is not idempotent — 25 duplicate players per side per boot

`NationalTeamSeeder:149-193` has **no** "does this side already have players" check. Compare
`BotSquadGenerator.ensureSquad:71-77`, which has exactly that check and a comment saying
*"a second call must not add a second set of 25."*

`seedIfMissing` calls `squadsFor` on **both** paths — create (`:121, :136`) **and** already-exists-top-up
(`:110, :127`) — so for any country that has clubs, **every call adds 25 more duplicate player rows.**

It is called from five places, several on **every boot**: `DatabaseInitializer:226`, `:351`,
`WorldIntegrityService:107` (inside `repair()`, itself called at `DatabaseInitializer:366`),
`WorldRepairService:42` and `:52`. With 96 national sides that is up to 2,400 duplicate player rows
per pass, in the world's largest table. Each call also costs `teams.findClubTeamsForOperations()` —
**all ~14,880 clubs** — at `:151`, per country per side: 96 full scans of the club table per
`seedIfMissing`.

### 7.7 Every league match in the game is being rated as a cup match

`ClubRatingService.valueFor:189-196`:

```java
if (match.getCompetition() == null) return MatchValue.LEAGUE;
return match.getCompetition().getScope() == CompetitionScope.INTERNATIONAL
        ? MatchValue.INTERNATIONAL : MatchValue.CUP;
```

There is no `CompetitionType` check. A `CompetitionType.LEAGUE` fixture with a non-international
scope — **i.e. every league match in the game** — falls through to `MatchValue.CUP`.
`MatchValue.LEAGUE` is reachable only when the competition is `null`. So the club ladder is driven
entirely by cup weights and the "league" tier of the Elo weighting is unreachable.

### 7.8 `sameValue` compares boxed `Double`s by reference, so all ~14,880 clubs are rewritten every run

`ClubRatingService:255-257`:

```java
return stored != null && stored == round(computed);   // Double == Double
```

`round` returns a **newly autoboxed** `Double` each call. `Double == Double` is **reference**
equality, and `Double.valueOf` only caches −128..127 while club ratings live at 1100–1500. So this
is **always false** — `changed` at `:209-211` is always true, and `teams.save(club)` runs for every
club on every `recomputeDurably()`, the exact opposite of the comment at `:216-218` ("only stale
rows move" is what the rounding is *for*).

Combined with `AsyncSimulationRunner.rateClubs():84-92`, which runs in a `finally` after **every**
matchday batch, **every matchday ends with a full-world replay plus 14,880 UPDATEs.**

### 7.9 Continental qualification for 47 of 48 countries is decided alphabetically

`RatingEngine.reputationFor(tier)` takes **only the tier** (`PyramidBuilder:250-257`). Every one of
the ten clubs in a division gets an **identical** reputation. `fillStaticDivision:277` then sorts
by reputation descending `.thenComparing(Team::getName)`.

So the league table of a 46-country simulated world is **alphabetical** — and
`InternationalClubCups.qualifiedFor:238-239` reads that table to decide continental entry, while
`poolAt(...).sort(reputationOf)` (`:270`) is a comparator over identical values.
**Champions / Masters / Challenge qualification for every non-Serbian country is alphabetical.**

### 7.10 The international club cups can never progress past their first knockout round

`InternationalClubCupDraw.buildKnockouts:289-304`:

```java
while (alive.size() > 2 && round <= ROUND_SEMI_FINAL) {
    ...
    return new DrawResult(...);   // line 303 — UNCONDITIONAL
}
```

The `while` **always returns on its first iteration.** `ROUND_QUARTER_FINAL` (7) and
`ROUND_SEMI_FINAL` (8) are **unreachable**. `ROUND_FINAL` (`:307`) is only reached when
`alive.size() == 2` — but `alive` is `qualifiers`, the group-stage output, which is never reduced.
`ROUND_THIRD_PLACE` is never referenced anywhere in the class.

Compounding: `ensureGroupStage` and `ensureKnockouts` have **no callers at all** (the only
`InternationalClubCups` call is `ensureCompetitionsDurably()`), so the 15 competitions exist with
zero fixtures — which is exactly what `ClubRatingService:57-62` says. And a small field
(`< MIN_FIELD_FOR_GROUPS`) returns from `buildGroupStage` promising *"it starts at the knockout"* —
but `buildKnockouts:271-273` then finds no group fixtures and returns. **The promised knockout is
never drawn.**

### 7.11 `MatchFormat` was written for a problem that is still unsolved, and has no callers

`SimMatchService:222` settles any `CompetitionType.CUP` match that ends level from the spot, and
`winnerOf:326-346` correctly reads `homePenaltyGoals`/`awayPenaltyGoals` (commit `2a9cf8f`). Good.

But `isKnockoutTie:457-460` is `type == CUP`, with the comment *"A group stage would need a real
flag."* **The international club cups are `type = CUP` with a group stage.** The day
`ensureGroupStage` is wired, every level group match is settled by a shootout.

`MatchFormat` (`MatchFormat.java:20-59`) exists precisely to carry `twoLegs()` / `canEndLevel()` /
`goesToPenalties()` — and `grep MatchFormat` returns only its own file and `CompetitionType`.
**Zero callers.** The abstraction was written for this problem, left unused, and the problem is
unsolved.

### 7.12 Elo is deterministic and correct — but has no floor, and the replay is unbounded

Stated plainly because this is a case where the *right* answer is a short one: the Elo itself is
fine. `NationalRatingService.recompute:101-111` seeds every side at 1500 and replays only real
results, ordered by `matchDate, id` (`:97`); `ClubRatingService` re-derives from the seed each time
rather than incrementing. **No double-count, no oscillation, order-deterministic.** §7.7 and §7.8
are real defects; the Elo arithmetic is not.

Two structural limits: `seedFor:142-147` is `clubStartRating(tier, tier)` with **no cross-tier
floor**, so a tier-5 club that wins its division weekly can out-rank a tier-1 club and the ladder
stops being a pyramid. And `matches.findPlayedClubMatchesInOrder()` (`MatchRepository:56-62`,
three `LEFT JOIN FETCH`es) loads **every played club match in the world** on every invocation, with
no season or date bound.

### 7.13 `GAME_ZONE` is declared and never used — there are three timezones of record

`GameClockService:59` declares `GAME_ZONE = Europe/Belgrade` with a comment that it *"must match
the zone clock.js formats in, or the header and the API would disagree."* **It is dead.**
`gameTime()` returns an `Instant` and `currentDate` is built with `ZoneOffset.UTC` (`:165`), while
`/api/server-time` reports Belgrade (`APIController:40-42`). Three definitions of game time, one
used.

And because `currentDate = now() + advanceOffsetSeconds`, the in-game date moves by **however long
the owner waited between clicks**, not by 7 days per Advance Week. Two owners clicking a week
apart get different `currentDate` from the same 168 advances. `ZoneLoadService.inGameNow():181-186`
reads exactly this field, so the recovery window is a function of wall-clock elapsed time.

### 7.14 Small but real: internationals kick off 45 minutes early, and day 6 has no job

`MatchdayJobsConfig:30-31` registers hour `20`; `WeekTemplate.DayKind.INTERNATIONAL` is
`LocalTime.of(20, 45)` (`:43`); `GameDay.kickoffHour():65-67` reports `20`. So
`APIController.watchStatus:244-249` opens "Watch your match" **45 minutes before the fixture's own
kickoff**, while `InternationalFixtureSeeder:47-48, 118-120` writes the fixture at 20:45.

Separately: **day 6 (MORALE) has no job at all.** The owner's schedule names it *"form and morale"*
and nothing implements it.

### 7.15 `PresenceRegistry`: unbounded maps, a self-invoked `REQUIRES_NEW`, and two sources of truth

- `lastRequestByUser` / `lastWriteByUser` (`:54-55`) are `ConcurrentHashMap`s with **no eviction**;
  the only clear is `forget():141-144`, documented as test-only. A deleted account stays forever
  and `tracked():147-149` reports a monotonically growing number.
- **`@Transactional(REQUIRES_NEW)` on `writeLastSeen` is silently inert** — `markSeen` calls it at
  `:75` by self-invocation, bypassing the proxy. This is **the same mistake `JobRunner:56-60`
  documents at length**, repeated.
- The javadoc says the in-memory map is the live truth (`:32-33`), but `onlineCount():102-104`
  counts the **database column**. After a restart the map is empty while the column still says
  online for up to 5 minutes.

---

## 8. 🟠 P1 — Security, corrected

`/api/**` is no longer public. That was the previous audit's worst finding and it is genuinely
closed. The permit list is now short and deliberate:

```
/  /.well-known/**  /favicon.ico
/css/**  /js/**  /images/**  /uploads/**  /audio/**
/demo/service/ui/**
/commonmanager/css/**  /commonmanager/js/**     ← assets only, NOT /commonmanager/api/**
/basketballmanager/**  /americanfootballmanager/**
/auth/**
/countries/catalog                             ← names/codes/seeded flag only
/api/server-time  /api/game-clock              ← the GET only
/home.html  /login.html  /register.html  /tifo.html  /simulateAllResults.html
/*.html                                        ← page shells only
```

then `/admin/** → ADMIN|OWNER|DEV` and `anyRequest().authenticated()`.

Four things remain, in ascending order of how much they matter:

1. **Any authenticated user can advance the world** (§4.4). The most serious, because it is a
   *new* exposure created by fixing the old one.
2. **`GET /countries/catalog` is `permitAll` and does `teamRepository.findAll()`**
   (`CountryController:163`) — an **unauthenticated full 14,880-row load** with each team's EAGER
   `Country.clubs` and two EAGER `@OneToOne`. The endpoint was narrowed to "names/codes/seeded flag
   only" in the intent, and the teams query defeats that.
3. **`GET /countries/teams/{teamId}/players` returns raw `Player` JPA entities**
   (`CountryController:781-783`) for any `teamId`, with no ownership or country check — it leaks
   every rival's squad including skills, contracts and injuries, and will throw
   `LazyInitializationException` on the `team`/`skills` proxies outside a session. The sibling
   `TeamController.getPlayers:176-193` returns a proper `PlayerDTO`.
4. **`GET /countries/{iso}/cup/fixture/{fixtureId}` discards its own authorization check**
   (`CountryController:309-314` calls `requireCountry(isoCode)` and throws the result away, then
   loads by id alone). Any authenticated user can read any country's tie by guessing an id.
5. Also: `GET /countries/leagues/{id}/schedule` (`:669`) performs a fixture-generating **write** on
   a GET. And `GET /api/jobs` + `/api/jobs/runs` (`APIController:103-127`) are world-readable with
   no role check.

Still-open from the previous audit, unchanged: unvalidated `sortBy` into `Sort.by()` at
`PlayerController:48-53`, `TeamController:95`, `LineupController:27`; raw entity create with no
validation and no auth at `TeamController:103-106` and `PlayerController:43-46`; `LineupController`
raw `RuntimeException` at `:35, :41` → 500 with a leaked message.

---

## 9. 🟡 P2 — Scale: what will not survive 48 × 310 ≈ 14,880 clubs

The world grew **48×** in five days. A large fraction of the new code was written for 310 clubs.
This is the section most likely to bite first, and none of it is in either previous audit.

**Whole-table loads in loops or on request paths:**

| Site | What it loads |
|---|---|
| `SeasonService:577` + `:594` | `playerRepository.findAll()` then `saveAll` — **every player in the world**, weekly, inside the week-rollover transaction |
| `SeasonService:525` | `teamRepository.findAll()` — all clubs, each settled in its own `REQUIRES_NEW` |
| `SeasonService:1027` | `competitionRepository.findAll()` inside `allLeagueCompetitionsByCountry()`, itself called at `:616` and `:635` **inside the season rollover** |
| `NationalRatingService:239` | `countries.findAll()` inside a per-team lookup, **4× per international match** |
| `NationalTeamSeeder:151` | `teams.findClubTeamsForOperations()` per country per side — 96 scans per `seedIfMissing` |
| `CupFixtureSeeder:180` + `:218` | all clubs, then one `players.findByTeamId` per club |
| `CountryController:163` | `teamRepository.findAll()` — **and this one is `permitAll`** |
| `SimulationController:235` | `matchFixtureRepository.findAll()` on every Advance Week |
| `PlayerZoneLoadRepository:24-26` | `findLoadsPlayedSince` — **no `LIMIT`, no pagination, no aggregation** |
| `ClubRatingService:117` → `MatchRepository:56-62` | every played club match in the world, three `JOIN FETCH`es, per recompute |
| `InternationalClubCups.summarise` (via `CountryController:151`) | 15 cups × `divisionsInTier` (`findAll`) + one `entries.findBySeasonCompetition` per division ⇒ **~11,000 queries per `GET /countries/world`** |

**The zone-load projection is the one to size carefully.** `ZoneLoadRecorder.record` writes up to
**22 players × 9 zones = 198 rows per match**, one `loads.save()` at a time inside the loop
(`ZoneLoadRecorder:156`), plus `players.save()` per player at `:160`, plus a redundant
`playerRepository.findById` per player at `SimMatchService:517-520`. At 14,880 clubs, one league
round per game day is ~7,440 matches × up to 198 rows ≈ **1.47 M zone-load rows per game day** — and
`applyDailyRecovery` loads all of them in **one query** into a `HashMap`, once a day, inside one
transaction.

The commit that added that query (`cc8d6da`) says it replaced *"16,354 round trips … 42 minutes."*
**It replaced round trips with an out-of-memory result set three orders of magnitude larger.** Both
statements are true; the trade was not obviously worth it at 310 clubs and is wrong at 14,880.

**Day jobs that iterate the whole world every game day:** `RecoveryJob` (all zone loads + all
players who ever played), `FinanceJob` (all clubs, each its own transaction). And because
`isDue` is `hour >= job.hour()`, the daily jobs are **evaluated 24 times each** per game day —
11 jobs × 24 = 264 guard lookups, plus a `gameClockRepository.save()` on a *read* at
`SeasonService:98` on each of the 168 steps.

**Unbounded in-memory maps:** `PresenceRegistry:54-55` (no eviction), `AdvanceWeekAsyncService:29`
(dead code, but live), `SimReplayStore` (one entry per simulated match), `AsyncSimulationRunner:27-28`
(`AtomicInteger` singletons shared across batches, so two batches cannot be told apart in the status
endpoint).

Full remediation — ID strategy, the nine missing indexes, the N+1s, the seeding rewrite — is in
`dataFixSuggestions.md`. §9 here is the scale-specific subset; the two documents overlap on
`CountryController` and `MatchdayJob` and neither contradicts the other.

---

## 10. 🟡 P2 — Unchanged debt, restated so the board is honest

Not new, not fixed, and not touched in 220 commits. Listed because the previous audit's version is
now 5 days stale and a reader needs to know these were not looked at again.

| Item | State |
|---|---|
| 5 empty stub services/controllers | `CompetitionController` (0 routes), `StadiumController` (0 routes, alongside a *live* `StadiumSettingsController` with 7), `LeagueService` (0 callers), `TrainingService` (0 callers, **holds an un-injected `playerRepository`**), `StadiumService` (0 callers) |
| `NewLogicTacticsService` | `new FormationSlotCatalog()` instead of injection, **and** 0 callers — a defect on a dead class |
| `DummyDataController` | 281 L, 18 routes, 0 DB access, hardcodes team id 1, untouched since 2026-07-30. **8 live frontend call sites across 5 files.** Two of them interpolate a *variable* team id into a route that only maps `…/1/…`, so every club except id 1 silently gets `{}` |
| `TeamController` `/coaches`, `/juniors`, `/formations` | hardcoded, **ignore `teamId`** entirely |
| `MatchPersistenceService` | 402 L, 0 callers, and now load-bearing for a test's allow-list |
| `MatchEventRepository.save()` | a no-op; events live only in `match.eventJson` |
| 6 dead frontend routes | `cup`, `international`, `friendlies`, `upcoming`, `playerStats`, `teamStats` — all point at `/demo/…` and all still `await response.json()` without `response.ok` |
| `fetchPlayerRatingSummary` | still 1-arg at 2 call sites; average rating still permanently `—` |
| `htmlEscape` | still defined byte-identically at `utils.js:4` and `pages-renderers.js:4-11` |
| `GameClock.currentPhase` / `SeasonPhase` | dead in both directions; `APIController:265` still hardcodes `"Season in progress"` |
| `Team.competition` | still the authoritative club→league mapping, still moved by season transition |
| `PromotionRule` | injected but write-only; the rules are decorative |
| `buildPlayoffSummary` | hardcodes 9–10 while the apply path is dynamic (§4.6) |
| `SeasonCompetition` | **no unique constraint** on `(competition_id, season_year)`; `findByCompetitionAndSeasonYear` returning `Optional` is the only guard, and that is a read-then-write race |
| `PlayerSkillProgressionService` | **live**, reachable via `TrainingController:40, 48, 130`, cap 17, inverted talent factor, contradicts the real engine |
| `LoanService` | 248 L, fully built, **no REST endpoint** |
| `ConditionalSubstitutionRules` + `SubstitutionPlanController` | built, unit-tested, unwired |
| `viewer3d.js` | now two **divergent** copies (872 L vs 958 L) — escalated from "a copy" to "a fork" |
| Orphaned files still present | `navigation.js` (55 L), `features/training.js` (13 L), `main.js` (8 L), `css/style.css` (233 L). **Deleted:** `realisticDemoLegacy.js` (2,777 L), its HTML, `cleanSheet.js` + HTML, `dasboardBackup.css`, `emptyStateHtml` |
| `Player.squadNumber`, `Team.juniorCoachSkill` | no UI, no setter; `juniorCoachSkill` is still a random 35–85 roll with no way to change it |

---

## 11. ✅ What is genuinely well done — updated

Credit where it is due, because the distribution of effort has changed and the new work deserves
naming.

| Area | Why |
|---|---|
| **Regression tests pinned to fixes** | `TransferServicePriceGuardTest`, `TrainingProgressionIdempotencyTest`, `PenaltyEngineTest`, `SubstitutionServiceTest`, `WeeklyFinanceServiceTest`, `ZoneLoadRecoveryTest`, `SkillsExactRatingTest` — each written when its fix landed. This is the difference between a fix and a fix that survives, and it is the single biggest quality change in the window |
| **Test coverage 3.8% → 24.4%** | 20 → 117 files. `SeasonService`, `TransferService`, `TrainingProgressionService` and `YouthAcademyService` — the four services the previous audit named as the highest-risk untested code — are all covered now |
| **The economy loop is closed** | `earnings` → `WeeklyFinanceService` wage bill → budget → `TransferBudgetService` → `BoardExpectationService` → `MoraleService`. Nine ledger categories, per club, per week, with an idempotency check. And the FFP band is **shown to the player** rather than hidden — a better call than either competitor makes |
| **`EconomyProfileService` made the seed deterministic** | Per-tier budget and reputation bands with an id-derived offset (`:81-91`) so a re-seed does not reshuffle a country. That is the kind of detail that makes a world reproducible |
| **Junior talent hiding** | `TalentRange` with a narrowing band and reveal gated on `canSeeTalent` **and** status. This is Sokker's mechanic, implemented properly, and `TalentRange`'s doc records the leak it closed |
| **Scouting as a real network** | `ScoutingService` + `ScoutAssignment` + `ScoutingController`, with `Country.youthRating` (the dead hook the previous audit named) now driving a `countryFactor` |
| **`PenaltyEngine`** | Taker selection, a keeper who *commits* to a read before the kick, a `wrongSide` guard added because a fresh uniform roll had inflated reads to 58%, and a stall guard for a wedge that was *observed in production*. Validated by 4,000 seeded penalties. **Do not touch it** |
| **`GoalkeeperEngine`** | Unchanged and still correct. **Do not touch it** |
| **`ensureEntriesForSeasonCompetition` rewrite** | `:162-220` now applies a difference instead of delete-and-rebuild. This directly fixes a data-loss path the previous audit flagged as "would wipe table data mid-season" |
| **The `ensureCompetitionsDurably` pattern** | A GET no longer issues an INSERT. Small, and exactly right |
| **The id-based team placement set** | `DatabaseInitializer:1176` with a javadoc naming the precise bug it fixes, plus `addTeamToLeague` now *refusing* to move a club and logging why. The previous audit's "~44% name collision" is structurally gone |
| **The cup idempotency-by-fixed-seed approach** | The *intent* (§7.1) is right — deterministic draws. The execution pairs by index instead of shuffling, which defeats it. Worth keeping the intent |
| **The commit-message discipline** | The 220 commit messages are a genuine audit trail: they name the bug, the reason, and often the retraction. Several are worth reading as documentation |
| **World scale itself** | 48 countries × 31 divisions × 10 clubs, 96 national sides, elections, a promotion ladder for every country, three populated international cup tiers. Five days ago this was Serbia-only |

---

## 12. The tactical editor — a clarification, not a finding

`competitive_analysis.md` §9.1 rates this the **#1 P0** and devotes a section to it. The
investigation for this audit confirmed **every technical sub-claim** and the verdict:

- `TeamTacticsProfile` is read by **no engine file**. The file list is byte-identical to
  `competitive_analysis.md` §12.
- All 11 main `new MatchOrchestrator(...)` call sites use the no-arg constructor.
- `new TacticsRules()` still opens raw JDBC to a hardcoded
  `jdbc:postgresql://localhost:5432/sokker_db` and reads team id 1 (`TacticsRules:45-49`).
- `TacticsRules.FORMATION` is still `public static final String FORMATION = "4-4-2"` (`:37`).
- `tactics_fallback.json` has **1012 entries = 11 × 46 × 2**, with **506 identical / 0 different**
  between the two possession contexts.
- `ballStateKey():222-226` still returns only `CELL_r_c` on a 7×6 grid, so `ATTACK_LEFT_CORNER` and
  three siblings are still unreachable.
- `FormationSlotCatalog.buildDefaultRules:185-195` still maps **every** ball state to the slot's
  formation anchor — so with default rules the shape does not react to the ball.
- `FormationSlotCatalog` now has **9 layouts** (was 3), and `RealSquadFactory:252` also hardcodes
  `"4-4-2"`. **9 in the catalog, 1 applied.**
- No pre-match preview. `MatchController/{id}/preview:166-167` returns `Map.of()` — an empty stub.
- The 6 `Tactics` slider fields: `getAggression|getDefenseLine|getPressing|getCounterAttack|getBallControl|getPossession`
  return **zero hits** in `src/main`. Still dead, now 5 weeks older.

**The owner's clarification, recorded so this audit does not misstate it:** the tactical editor is
**not yet wired from the app to the engine, but the engine is already running on an identical copy
of the editor's data** — that is deliberate, as a testing arrangement, and it will be connected
when the testing finishes.

**That is a materially different situation from what §9.1 describes, and it changes the estimate
downward.** The previous analysis assumed the engine was inert and priced the fix at 5–8 days of
sanitisation plus wiring. In fact:

- **The data path is proven.** The engine already reads a 1,012-rule tactical dataset and positions
  players from it every tick. The hard problem — can the engine consume authored rules at all — is
  answered.
- **What remains is a wiring and sanitisation job, not a build.** Read `TeamTacticsProfile` per
  team in `SimMatchService`; pass per-side `TacticsRules` into `MatchOrchestrator`; delete the raw
  JDBC path and the `FORMATION` constant; make `FormationSlotCatalog` serve the chosen formation.
- **Estimate: 2–3 days**, not 5–8. The 5–8 figure was for a greenfield integration.

**Three things found during this audit that the wiring must handle, and which are new since
2026-09-26:**

1. **A second, unwired bridge already exists.** `TacticsBridge.fromRuntimeMap()` converts
   `TeamTacticsService.getRuntimeRuleMap()` into a `TacticRules` model — and has **zero callers**
   in `src/main` or `src/test`. `NewLogicTacticsService.loadTacticRules(teamId, formation)` returns
   `TacticRules.createDefault(slotKeys)` **unconditionally, discarding both of its own arguments**,
   and has zero callers. **This is exactly the bridge §9.1 asked for, written and left unwired** —
   the same failure mode, now duplicated. Wire this one; do not write a third.
2. **`DefensiveShape` may have silently superseded the 2026-09-26 possession-context ruling.**
   A new `tactics/DefensiveShape.java` derives the out-of-possession shape arithmetically (drop
   0.16 / compact 0.80 / shift-toward-ball 0.18), and `TacticsRules.desiredCellFromContext()`
   consults it and only uses an authored `OPPONENT_HAS_BALL` rule when that rule *differs* from
   its `WE_HAVE_BALL` twin. Since all 506 shipped pairs are identical, **the derived block wins on
   every single lookup.** The engine now defends in a different shape than it attacks — with no
   data edit. This is defensible engineering (the class comment argues it well and `DefensiveShapeTest`
   exists), but it is a **third position** that neither the document nor the ruling describes, and
   the data is still in the mirrored state the ruling mandated. **This needs an explicit owner
   confirmation, not a code change** — per your own rule, ask rather than guess.
3. **`WeeklyFinanceServiceTest`, `StaffSponsorServiceTest` and `PlayerContractServiceTest` still
   pass calendar years** (2024/2025/2026) as `seasonYear`/`expirySeason`. The project moved to a
   season *number*. These tests are self-consistent under either reading, so **they will not catch
   a regression when the tactical rules are re-keyed onto the season number.** Fix the fixtures
   before the wiring, not after.

---

## 13. Documentation is now the largest single liability

The previous audit said *"the documentation is now larger and more confident than the code it
describes."* After 220 commits the position has **inverted** — and that is a different, more
dangerous problem, because a stale doc that is too pessimistic gets ignored, while one that is too
optimistic gets trusted.

| Document | Drift |
|---|---|
| `AGENTS.md` (92 KB) | Documents `RealisticMatchEngine`, `SimulationService`, `RuntimeSaveToDB`, `RoundSimulationAsyncService`, `/api/v2/match/**`, `cleanSheet/` and `old/` as the primary path — **none exist in `src/main/java`**. Lists `CompetitionController` as a real prefix (0 routes). Says `pages.js` is "5200+ lines". Names four test classes that do not exist. Describes the `demo/` Swing architecture as the live product. **Already flagged in `sprintProgress.md:199` as making S6.5 urgent; still unaddressed 5 days later** |
| `PROPOSAL_SEASON_REPORT.md` | Reports the 7.10 state (goals 5.2, pass accuracy 77.9%, interceptions 36, corners 0.8/4.3, duals 601.2 = tackles 601.2). **Three calibration generations behind** (§6) |
| `PROPOSAL_CURRENT_STATE.md` | Last written 2026-09-25. Still lists "P0 HOME/AWAY asymmetry: OPEN" and "Shot volume calibration: OPEN" — both solved. Contains the halftime paragraph twice and the two P0-asymmetry paragraphs with **opposite statuses in the same section** |
| `PROPOSAL_PROGRESS.md` | Accurate but ends at 2026-09-26 02:15. The live calibration record moved to `sprintProgress.md` |
| `sprintBacklog.md` | Says "722 passing" (last recorded run: 791) and **"Sprint 5 ◀ CURRENT"** twice, while its own CURRENT STATE block and `kanban.md` show S7/S8 landed. `:3377` still says *"There is no zone model in the codebase"* — the zone model shipped and has a writer. `:119` still lists `RecoveryJob` as "deliberately NOT built" — it has run daily since |
| `competitive_analysis.md` | Rates **Economy 0/10** and **Transfers 2/10**; both are now ~6/10. Says "League pyramid depth: 31 leagues, Serbia only" and "Knockout cup: created, never populated" — both wrong now. Says **176 tests** — there are 864 `@Test`. Cites `MatchOrchestrator:114-368` for the tick pipeline; the file is 786 lines and `tick()` is at `:295-712` |
| `kanban.md` / `kanbanProgress.md` | **The most accurate document set.** One entry per commit with the hash. This is where the real state lives |

**Recommendation: consolidate on `kanban.md` + `kanbanProgress.md` and treat the rest as
historical.** The per-sprint status tables and the `PROPOSAL_*` calibration docs are now three
sources of truth for the same numbers and they disagree. `AGENTS.md` is the one to fix first,
because it is the file an agent reads first and it points at four classes that do not exist.

---

## 14. The one-line summary, and what changed in it

**2026-09-26:** *"The engine is the product. The management layer is the roadmap."*

**2026-10-01:** the management layer shipped. The engine is still the best thing here, but it is no
longer alone, and the loop `earnings → wages → budget → squad → league position → board trust` now
has all six nodes and real edges. The previous audit's single highest-leverage sentence was:

> *The single highest-leverage change is not a feature — it is making **`Player.earnings` mean
> something** and **giving `Team.budget` a reason to change on its own.** Right now that loop has
> two nodes and no edges.*

**That sentence is now written in code.** `Player.earnings` has 13 call sites.
`Team.budget` moves on a weekly ledger for every club.

**What is now the highest-leverage change, and it is a different kind of problem:** the world grew
48× in five days and the code that schedules it — the day-job framework, the game clock, the
country rating, the cup draw — was written for 310 clubs and one country. Specifically, in order:

1. **The `job_run` guard does not prevent double-apply** (§4.1-4.3), and **any authenticated user
   can advance the world** (§4.4). These are the two findings I would fix before anything else,
   because both are reachable by a normal player and both corrupt the season permanently.
2. **The season counter skips one per rollover** (§4.5) — a 3-line fix that will silently corrupt
   every future season number.
3. **The national cup draw is deterministic-by-index, not random** (§7.1), and the cup is
   hardwired to the lowest-id club's country and then gives up forever (§7.2).
4. **Every league match is being rated as a cup match** (§7.7), and **all ~14,880 clubs are
   rewritten on every matchday** because a `Double` comparison uses `==` (§7.8).
5. **Delete `AdvanceWeekAsyncService`** (§4.7) — 186 orphaned lines and the last holder of the
   legacy inline week.

None of these is a missing feature. All of them are the cost of a 48× world, and all of them are
cheaper to fix now than after the second season rolls over.

---

## Appendix A — Reference index (new and changed findings)

| Concern | File:line |
|---|---|
| **Job guard written after the body** | `newLogic/jobs/JobRunner.java:139-147` (claim at `:26-28`) |
| **FAILED is terminal, no re-queue** | `JobRunner.java:124-130`; `JobRunRepository` (no reset method) |
| **`advanceWeek` = 168 transactions** | `GameClockService.java:230-232, 237, 212` |
| **Any user can advance the world** | `APIController.java:59-68, 71-74, 83-92`; `SecurityConfig.java:113-114, 140` |
| **Season incremented twice** | `GameClockService.java:152-155` + `SeasonService.java:605` |
| **Playoff summary vs applied** | `SeasonService.java:910-918` vs `:800-822` |
| Orphaned legacy week path | `service/AdvanceWeekAsyncService.java:59, 87-88, 114` |
| `MatchdayJob` query per competition | `jobs/impl/MatchdayJob.java:91-98` |
| Conditional subs unwired | `MatchOrchestrator.java:199`; `SubstitutionPlanController` |
| Substitutions never surfaced | `ProposalStatsCollector.java:227` |
| Legacy training engine **live** | `PlayerSkillProgressionService`; `TrainingController.java:40, 48, 130` |
| Loans have no endpoint | `service/LoanService.java` (no controller references) |
| Seller cannot choose an offer | `TransferService.java:447-468`; `NegotiationService.acceptOffer(transferId, offerId)` unreachable |
| Sacking is a read-only boolean | `BoardExpectationService.java:43, 64-67, 239-256`; `FinanceController.java:124` |
| No retirement | `newLogic` (nothing); `PlayerRepository` has no `findByAgeGreaterThan` |
| Carryover juniors frozen | `YouthAcademyService.java:102-107` |
| `academySkillExact` still raw | `YouthAcademyService.java:805` |
| **Cup draw has no randomness** | `CupFixtureSeeder.java:394-436` (javadocs `:361-366, 368-385`) |
| **Cup hardwired to lowest-id country** | `CupFixtureSeeder.java:178-190, 138-142` |
| **Internationals seeder gives up forever** | `InternationalFixtureSeeder.java:66-103` (save at `:75`, guard at `:67`, exit at `:99`) |
| **Fixtures dated 2026, zone window never matches** | `InternationalFixtureSeeder.java:49`; `CupFixtureSeeder.java:56, 71-74`; `ZoneLoadService.java:69, 127` |
| **NT seeder not idempotent** | `NationalTeamSeeder.java:110, 121, 127, 136, 149-193`; contrast `BotSquadGenerator.java:71-77` |
| **League matches rated as cup** | `ClubRatingService.java:189-196` |
| **`Double == Double` rewrites the world** | `ClubRatingService.java:255-257` (comment at `:216-218`) |
| **Continental entry is alphabetical** | `PyramidBuilder.java:250-257, 277`; `InternationalClubCups.java:238-239, 270` |
| **International cup knockouts always return** | `InternationalClubCupDraw.java:289-304` (return at `:303`) |
| Group stages never drawn | `InternationalClubCupDraw.java:166-174, 271-273`; `ensureGroupStage`/`ensureKnockouts` 0 callers |
| `MatchFormat` unused | `MatchFormat.java:20-59` — 0 callers |
| Group draws settled by shootout | `SimMatchService.java:457-460` |
| Elo: no cross-tier floor, unbounded replay | `ClubRatingService.java:142-147`; `MatchRepository.java:56-62` |
| **`GAME_ZONE` dead, 3 timezones** | `GameClockService.java:59, 76-79, 165`; `APIController.java:40-42` |
| `PresenceRegistry` issues | `PresenceRegistry.java:54-55, 75, 102-104, 141-144` |
| Internationals kick off 45 min early | `MatchdayJobsConfig.java:30-31`; `WeekTemplate.java:43`; `GameDay.java:65-67` |
| Day 6 has no job | `newLogic/jobs/impl/` (no job with `day() == 6`) |
| **Zone recovery: amount discarded, no clamp** | `ZoneLoadService.java:151-155` vs `recoveryFor:81` |
| Zone loads unbounded query | `PlayerZoneLoadRepository.java:24-26`; `ZoneLoadRecorder.java:156, 160` |
| Zone loads per match = up to 198 | `PlayerZoneLoad.java:27-29` (unique constraint) |
| `permitAll` + `findAll` | `SecurityConfig.java:113`; `CountryController.java:163` |
| Raw entities, any team | `CountryController.java:781-783` |
| Auth check discarded | `CountryController.java:309-314` |
| Write on a GET | `CountryController.java:669` |
| World stats read a nonexistent map key | `CountryController.java:151` (`currentSeason` never put) |
| Tactics: bridge written, unwired | `TacticsBridge.fromRuntimeMap()` 0 callers; `NewLogicTacticsService.loadTacticRules` discards args |
| Tactics: `DefensiveShape` supersedes the ruling | `tactics/DefensiveShape.java`; `TacticsRules.desiredCellFromContext()` |
| Tactics: raw JDBC to a foreign DB | `TacticsRules.java:45-49` |
| Tactics: `FORMATION` constant | `TacticsRules.java:37`; `RealSquadFactory.java:252` |
| Tactics: 4 unreachable ball states | `TacticsRules.java:222-226` |
| Tactics: defaults ignore the ball | `FormationSlotCatalog.java:185-195` |
| Empty preview endpoint | `MatchController.java:166-167` |
| 6 `Tactics` sliders dead | `model/tactics/Tactics.java` — 0 readers |
| `viewer3d.js` fork | `demo/service/ui/js/viewer3d.js` (872 L) vs `.../proposal/js/viewer3d.js` (958 L) |
| Calendar-year test fixtures | `WeeklyFinanceServiceTest`, `StaffSponsorServiceTest`, `PlayerContractServiceTest` |

## Appendix B — What I did not do

- **I did not run `mvn test`.** The 864 `@Test` count is an annotation count, not an executed-test
  count. The last recorded green run is 26 commits behind HEAD. Run the suite before trusting any
  number in §3.
- **I did not run any diagnostic** (`ProposalBatchDiag`, `ProposalSeasonDiag`, `TeamStrengthProbe`).
  Every engine figure in §6 is quoted from `sprintProgress.md:2477-2491`, which is the most recent
  recorded run I could find. It is 100 matches, seed recorded, and is the right source — but I
  have not reproduced it.
- **I did not query the database.** Row counts (14,880 clubs, ~7,440 matches per game day, 1.47 M
  zone-load rows) are derived from the seeding constants: `DIVISIONS_PER_TIER = {1,2,4,8,16}`,
  `CLUBS_PER_DIVISION = 10`, 48 countries. They should be confirmed against the live schema, and
  note that `ddl-auto=update` means it has drifted from the annotations.
- **I did not test the frontend in a browser.** The 6 dead routes, the two `htmlEscape` copies and
  the `fetchPlayerRatingSummary` calls are read from source.
- **I did not touch the multiplayer/sokker side.** `competitive_analysis.md`'s Sokker, Hattrick
  and FM sections are unchanged and were not re-verified — only the "Ours" column was.
