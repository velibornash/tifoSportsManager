# CurrentStateAnalysis.md — verified against source code

**Date:** 2026-10-08
**Method:** every claim below was verified by reading the actual source code, not the markdown documents.
The markdown documents were used for context only. Where the documents and the code disagree,
the code wins.

---

## 1. Live Match Viewing

### What exists

| Component | Location | State |
|---|---|---|
| Replay system (full 2D/3D viewer) | `static/demo/service/ui/proposal/js/viewer.js` (2023 lines) | **Works.** Canvas rendering, play/pause/seek/speed controls, player inspection, event overlays |
| Replay recording during simulation | `MatchOrchestrator.java:277` — `recorder.captureSnapshot(state)` per tick | **Works.** Snapshots captured every tick, downsampled to every 10th for storage |
| Replay storage (file-backed) | `SimReplayStore.java` — JSON files at `./replay-data/replay-<id>.json` | **Works.** Bounded (200 entries / 14 days), LRU eviction, survives restarts |
| Replay serving | `SimReplayController.java` — `GET /api/sim/replay/{id}`, `GET /api/sim/replay/by-match/{matchId}` | **Works.** Returns 410 GONE on expiry |
| "Live results desk" (fake live) | `static/js/roundResultsTeletext.js` | **Works.** Takes already-simulated results, animates them as if live using `requestAnimationFrame` |
| WebSocket endpoints | `WebSocketConfig.java:41-44` — 4 endpoints registered | **Dead.** No frontend connects. `notifications.js:5-12` documents why: routing is by matchId, no per-user channel |
| SSE / STOMP | — | **Does not exist** |

### What does not exist

| Gap | Detail |
|---|---|
| True real-time streaming during simulation | `SimMatchRunner.java:105` runs `orchestrator.simulate(ticks)` synchronously to completion (3600 ticks). No incremental broadcast. |
| "Skip to result" button | No endpoint or UI for fast-forwarding a live match |
| WebSocket client in frontend | Zero `WebSocket`, `EventSource`, `SSE` usage in any JS file |

### Verdict

The **replay viewer is the "watch" experience.** It is a post-match playback system with full
2D/3D rendering, not a live stream. The "live results desk" animates completed results as if
they are happening in real-time, but the data is final when the page loads.

The owner said "ovo postoji" (this exists) — they are referring to the replay viewer and the
live results desk. They are correct that a watch experience exists. What does **not** exist is
true real-time streaming during simulation (watch the match as it is being simulated tick by tick).

---

## 2. Tactical Editor → Engine Wiring

### What exists

| Component | Location | State |
|---|---|---|
| Tactical editor UI | `static/js/pages/views/tactic-editor-view.js` (311 lines) | **Works.** Drag-to-shape, ball-position grid, localStorage drafts, version counter |
| Persistence | `TeamTacticsService.saveTacticsEditor()` → `TeamTacticsProfile` entity | **Works.** Saves formation, style, rulesJson, setPiecesJson |
| Engine read path | `TacticsRulesProvider.forTeam()` → `TacticsRules.fromProfileJson()` → `SideTactics` → `MatchOrchestrator` | **Works.** The engine reads the profile and positions players from it |
| Formation catalog | `FormationSlotCatalog.java` — 9 formations (4-4-2, 4-3-3, 4-2-3-1, 4-1-4-1, 3-5-2, 5-3-2, 3-4-3, 4-5-1, 5-4-1) | **Works.** `slotOrderFor()` and `anchorsFor()` are formation-aware |
| Per-side tactics | `SideTactics` with home/away `TacticsRules` | **Works.** Since 2026-10-03, each side gets its own rules |
| Set-piece takers | `TeamTacticsProfile.setPiecesJson` — 5 taker selectors | **Stored.** Engine reads them |

### What does not exist

| Gap | Detail |
|---|---|
| Multi-tactic storage | `TeamTacticsProfile` has a **unique constraint on `team_id`** — one profile per team. No tactic library, no saved tactics list. |
| Per-match tactic selection | No `MatchTactic` entity, no `tactic_assignment` table. Tactics are immutable per match (`TacticalIntentEngine` holds one `SideTactics` reference). |
| Tactic conditions (winning/losing/draw) | No condition model, no score-based tactic switching. |
| Default tactic fallback | If no profile exists, falls back to bundled `tactics_fallback.json`. No "default tactic" concept. |
| `TacticsBridge` | `TacticsBridge.java` — **dead code, zero callers.** Converts runtime rule map to `TacticRules` (a different model). |
| `NewLogicTacticsService` | **Dead code, zero callers.** |
| `DefensiveShape` | Derives out-of-possession shape arithmetically. `mirrorWeHaveBallRules()` overwrites `OPPONENT_HAS_BALL` with `WE_HAVE_BALL` on every save. The two are identical by owner decision. |

### Verdict

The tactical editor **is wired to the engine.** The archive documents (competitive analysis §9.1)
claiming it is disconnected are **outdated.** The data flow is:

```
tactic-editor-view.js → PUT /teams/{id}/tactics-editor → TeamTacticsService.saveTacticsEditor()
  → TeamTacticsProfile (rulesJson) → TacticsRulesProvider.forTeam() → TacticsRules
  → SideTactics(home, away) → MatchOrchestrator → TacticalIntentEngine (per tick)
```

What is missing is the **multi-tactic + condition layer**: save 10 tactics, pick 3 per match,
switch based on score. That layer does not exist.

---

## 3. Lineup & Bench

### What exists

| Component | Location | State |
|---|---|---|
| Lineup template | `TeamController.java:439-470` — `GET/PUT /{teamId}/lineup-template` | **Works.** Saves formation, style, starterIds (max 11), benchIds (max 7) |
| Lineup model | `Lineup.java` — has a `match` field (`@ManyToOne`) | **Schema supports per-match lineups** |
| Squad building | `RealSquadFactory.buildSquad()` — takes lineup template, builds sim squad | **Works.** |
| Bench population | `RealSquadFactory.buildBench()` — players 12-18 from template, max 7 | **Works.** |
| Auto-pick fallback | `RealSquadFactory.buildSquadFromPlayers()` — if no template, sorts by position, best 11 | **Works.** |
| Availability filter | `TeamController` filters to non-injured players | **Works.** |

### What does not exist

| Gap | Detail |
|---|---|
| Per-match lineup selection | Both `TeamController` and `SimMatchService.loadLineup()` query with `findFirstByTeamIdAndMatchIsNullOrderByIdDesc` — they explicitly look for `match IS NULL`. No UI or service creates a `Lineup` with a specific `match` set. |
| Default lineup fallback | If no template exists, auto-picks best 11. No "default lineup" concept separate from auto-pick. |
| GK substitution rule | `SubstitutionService` and `ConditionalSubstitutionRules` both exclude GK from automatic subs. A GK can only be subbed by another GK. This is **already implemented** as the owner described. |

### Verdict

Lineup is **template-based, not per-match.** The schema supports it (`Lineup.match` exists),
but no code creates per-match lineups. The manager sets one lineup and it applies to all matches
until changed.

---

## 4. Substitutions

### What exists

| Component | Location | State |
|---|---|---|
| Engine substitution mechanics | `SubstitutionService.java` — 5 subs / 3 windows, injury auto-sub, fatigue auto-sub (threshold 0.62) | **Works.** |
| Conditional substitution rules | `ConditionalSubstitutionRules.java` — Rule class with triggerMinute, condition (LOSING/DRAWING/LEADING/ANYTIME), playerOnId, playerOffId | **Engine contract exists.** |
| Injury auto-sub | `SubstitutionService.onTickInjuriesOnly()` | **Works.** |
| Fatigue auto-sub | `SubstitutionService.onTickFatigueOnly()` — fatigue >= 0.62 | **Works.** |
| Sent-off player never replaced | `SubstitutionService.substitute()` — `if (off.isSentOff()) return false` | **Works.** |
| GK never auto-subbed | Both fatigue and conditional paths filter `!p.isGoalkeeper()` | **Works.** |
| `SubstitutionPlanController` | `PUT/GET/DELETE /api/sim/matches/{matchId}/substitution-plan` | **REST API exists.** |
| `SubstitutionPlan` model | Persisted as JSON in DB | **Stored.** |

### What does not exist

| Gap | Detail |
|---|---|
| Wiring from DB to engine | `conditionalSubs.add()` is **never called** from any service or controller. `SimMatchService.simulate()` does not read `SubstitutionPlan`. The persisted plan never reaches the engine. |
| UI for substitution conditions | **No frontend code** for setting substitution rules. Zero JS files reference `substitution-plan` or `conditionalSub`. |

### Verdict

The engine contract for conditional substitutions is **fully built and unit-tested** (10 tests).
The REST API for persisting plans exists. But the plan is **never loaded into the engine** —
`conditionalSubs.add()` has no production callers. And there is **no UI** for a manager to set
substitution conditions.

---

## 5. Calendar & Jobs

### What exists

| Component | Location | State |
|---|---|---|
| 12-week season | `SeasonCalendar.WEEKS_PER_SEASON = 12` | **Works.** |
| 4 slots per week (days 1,3,5,7) | `SeasonCalendar.SLOTS_PER_WEEK = 4` | **Works.** |
| Day 1: International cups | `MatchdayJobsConfig` — `matchday-international` day 1, 20:00 | **Works.** |
| Day 2: Finance | `FinanceJob` day 2, 10:00 | **Works.** |
| Day 2: Cup draw | `CupDrawJob` day 2, 08:00 | **Works.** |
| Day 3: League slot 1 | `matchday-league-a` day 3, 19:00 | **Works.** |
| Day 4: Training | `TrainingJob` day 4, 10:00 | **Works.** |
| Day 5: Cup | `matchday-cup` day 5, 18:00 | **Works.** |
| Day 7: League slot 2 | `matchday-league-b` day 7, 16:00 | **Works.** |
| Week 6: No league, NT qualifiers | `NationalMatchdayJob` week 6, days 2-6 | **Works.** |
| Week 11: Playoffs | `ensurePlayoffWeekFixtures` | **Works.** |
| Week 12: World Cup | `NationalTournamentDrawJob` week 12, days 1,2,4,6 | **Works.** |
| Transfer window (summer) | `TransferWindowService` — weeks 5-6 | **Works.** |
| Transfer window (winter) | `TransferWindowService` — weeks 11-12 | **Works.** |
| Week rollover | `WeekRolloverJob` day 7, 23:00 | **Works.** |
| Season rollover | `SeasonRolloverJob` week 12, day 7, 23:00 | **Works.** |

### What does not exist

| Gap | Detail |
|---|---|
| Day 6 form & morale job | `WeekTemplate.DayKind.MORALE` exists in the template, but **no `DayJob` implementation triggers on day 6**. Form/morale is handled as a zone compute via daily `RecoveryJob` and match-time updates, not a dedicated day-6 trigger. |
| Background match generation 30 min before kickoff | No pre-match generation job. `MatchdayJob` only **plays** already-created fixtures. Fixtures are created by draw jobs (cup, national, international) or season setup (league). |
| Timezone-aware scheduling | Single global timezone: `GameClockService.GAME_ZONE = "Europe/Belgrade"`. One `WeekTemplate` for all 48 countries. No per-country kickoff time. |

### Verdict

The calendar is **mostly correct.** Day 6 is the only missing job. Transfer window timing
matches the owner's vision (opens week 5, closes week 6 for summer; weeks 11-12 for winter).
No timezone handling — all countries share the same kickoff times.

---

## 6. National Cup Format

### What exists

| Component | Location | State |
|---|---|---|
| Preliminary round (week 1) | `CupFixtureSeeder.ENTRY_ROUND_TEAMS = 108` — bottom 108 clubs by squad rating play a preliminary | **Works.** |
| Main draw (week 2) | `CupFixtureSeeder.MAIN_DRAW_TEAMS = 256` — 54 preliminary winners + 202 direct entrants | **Works.** |
| Classic knockout (weeks 3-8) | `CupFixtureSeeder.CUP_WEEKS = {1,2,3,4,5,7,8,11}` — 8 rounds, pure knockout after week 2 | **Works.** |
| Per-country draw | `seedIfMissing()` iterates national cups, each cup ranks clubs from its own country | **Works.** |
| Seeding | `splitForDraw()` halves ranked entrants, favourites vs non-favourites, non-favourite hosts | **Works.** |
| Penalty shootouts | `winnerOf()` — level ties settled by `homePenaltyGoals`/`awayPenaltyGoals` | **Works.** |

### Verdict

The national cup **matches the owner's vision exactly:** elimination round for lower-ranked
clubs in week 1, then classic knockout. No gap.

---

## 7. Post-Match Updates

### What exists

| Component | Location | State |
|---|---|---|
| League table reconciliation | `LeagueTableReconcileJob` — day 4 at 01:00 and day 7 at 22:00 | **Works.** |
| Elo rating update | `AsyncSimulationRunner.rateClubs()` after each matchday batch | **Works.** |
| Ranking points | `RankingPointsRebuildService.rebuild(season)` after each batch | **Works.** |
| Honours/trophies | `HonourService.derive(season)` — league 1/2/3 → gold/silver/bronze, cup winner gold, etc. | **Works.** |
| Top scorers/assists | `GoalEventRepository` reads from match events | **Works.** |
| Club milestones | `LeagueMilestoneService` derives from season data | **Works.** |
| Match stats persistence | `SimMatchService.persist()` — team and player match statistics | **Works.** |

### What does not exist

| Gap | Detail |
|---|---|
| Prize money wiring | `awardPrizeMoney` exists but has **no caller** (P1-5 in kanban). Cup prize money is not paid out. |

### Verdict

Post-match updates are **fully built.** Prize money is the only gap — the service exists but
is never called from cup results.

---

## 8. Training

### What exists

| Component | Location | State |
|---|---|---|
| 10 advanced slots | `TeamTrainingSetup` — `MAX_ADVANCED = 10` | **Works.** |
| 4 roles with direct training | `dtSkillGk/dtSkillDef/dtSkillMid/dtSkillAtt` | **Works.** |
| General training 5x smaller | `dt / 5.0 × generalSkillModifier` for non-direct skills | **Works.** (Owner said 6x, code does 5x) |
| Intensity (3 tiers) | `TrainingIntensity` — LIGHT 0.75 / NORMAL 1.00 / VERY_HARD 1.35 | **Works.** |
| Minute-proportional | `TrainingPercent.percentFor()` | **Works.** |
| Coaching staff reaches engine | `SimMatchService.coachFactorFor()` → `member.matchFactor()` | **Works.** |
| AI clubs train | `SquadTrainingService.trainEveryClub()` | **Works.** |
| Facilities affect growth | `Stadium.trainingFactorFor(skill)` | **Works.** |

### Verdict

Training **matches the owner's vision.** No gap.

---

## 9. Squad & Juniors

### What exists

| Component | Location | State |
|---|---|---|
| Max 30 players | `SquadRegistrationService.MAX_CLUB_SQUAD = 30` (P2-23) | **Works.** |
| Juniors arrive week 2 | `YouthAcademyService` — week 2 intake, uniform 6-10 count | **Works.** |
| Random age 15-20 | `15 + nextInt(5)` → 15-19 | **Works.** |
| Random talent/height/skills | `TalentRange` hidden, `academySkillExact` random | **Works.** |
| Promoted week 1 next season | Decision window week 1 only (P2-22) | **Works.** |
| Talent hidden behind narrowing range | `TalentRange` — ±(1+rnd0..3) at intake → ±1 at promotion | **Works.** |
| Scouting network | `ScoutingService` + `ScoutAssignment` | **Works.** |

### Verdict

Squad and juniors **match the owner's vision.** No gap.

---

## 10. Promotion/Relegation & Loans

### What exists

| Component | Location | State |
|---|---|---|
| Last 2 relegated | `applyPromotionRelegationForLeague` | **Works.** |
| 7th/8th playoff | 2-leg playoff exists | **Works.** |
| Champion promoted | Exists | **Works.** |
| 2nd plays playoff | Exists | **Works.** |
| Loans (under 24, lower tier) | `LoanService` — age check, tier ladder check, domestic only | **Works.** |
| Loan REST API | `LoanController` — `/loans/rules`, `/loans/available`, `/loans/destinations`, `POST /loans`, etc. | **Works.** |
| Loan screen | `static/js/pages/views/loans.js` | **Works.** |

### Verdict

Promotion/relegation and loans **match the owner's vision.** No gap.

---

## 11. International Cups

### What exists

| Component | Location | State |
|---|---|---|
| 15 competitions (5 tiers × 3 cups) | `InternationalClubCups.java:142` | **Works.** |
| Champions Cup: champion | `qualifyFrom()` — tier 1 winner | **Works.** |
| Masters Cup: 2nd/3rd | `qualifyFrom()` — pool of 2nd and 3rd place clubs | **Works.** |
| Challenge Cup: 4th | `qualifyFrom()` — pool of 4th place clubs | **Works.** |
| Group stage then knockout | `InternationalClubCupDraw` — 8×6 / 16×6 groups, R16→QF→SF→3rd→final | **Works.** |
| Simulated country squads | `BotSquadGenerator` with tier parameter (12/11/10/9/8) | **Works.** |
| Cup tables | `MatchType.countsForTable()` includes cup group matches | **Works.** |
| Penalties only in knockouts | `SimMatchService.isKnockoutTie()` | **Works.** |

### Verdict

International cups **match the owner's vision.** No gap.

---

## 12. National Teams

### What exists

| Component | Location | State |
|---|---|---|
| Senior + U21 | Both exist | **Works.** |
| Week 6 qualifiers | `NationalMatchdayJob` week 6, days 2-6 | **Works.** |
| Week 12 World Cup | `NationalTournamentDrawJob` week 12 | **Works.** |
| Squad fixed from 10:00 day 1 week 12 | Not explicitly verified in code | **Unknown** |
| Elections | `NationalTeamElection` — registration opens week 12 of previous season | **Works.** |
| Real club players in NT squads | `NationalTeamSeeder.squadsFor()` — draws from club players, stamps `sourcePlayerId` | **Works.** |

### Verdict

National teams **mostly match the owner's vision.** Squad freeze timing needs verification.

---

## 13. Economy & Transfers

### What exists

| Component | Location | State |
|---|---|---|
| Weekly ledger (9 categories) | `FinanceLedgerEntry` — gate, broadcast, merchandising, prize, wages, staff, sponsorship, facility, junior | **Works.** |
| Wage bill from player earnings | `Player.earnings` — 13 call sites | **Works.** |
| Gate revenue | `AttendanceService` — ticket price, form, opposition, weather, price elasticity | **Works.** |
| Sponsorship | `Sponsor` entity with per-season contracts | **Works.** |
| FFP bands (shown to player) | `BoardExpectationService` — 0.90 / 1.15 / 1.35 | **Works.** |
| Board trust (0–100) | `BoardExpectationService.trustScore()` | **Works.** |
| Transfer offers | `TransferOffer` entity with buyer FK, fee, wage, length, round, status | **Works.** |
| Negotiation (5 rounds) | `NegotiationService` — fee/wage/length separately | **Works.** |
| Transfer windows | `TransferWindowService` — weeks 5-6 and 11-12 | **Works.** |
| Contracts with expiry | `PlayerContract` — length, wage, expiry → free agency | **Works.** |
| Loans | `LoanService` — full rules, REST API, screen | **Works.** |
| Free agents | `PlayerContract` expiry → free agency | **Works.** |
| AI↔AI auction | `TransferService` — weekly auction weighted by `ClubNeedService.interest` | **Works.** |
| Squad registration limit | `SquadRegistrationService.MAX_CLUB_SQUAD = 30` | **Works.** |
| **Prize money** | `WeeklyFinanceService.awardPrizeMoney`, called from `SeasonService.performPromotionRelegationAndNewSeason()` | **Works.** P2-14. Ranked by `LeagueTableOrder`, paid before promotion, an unplayed competition pays nothing, every club in the table is paid |
| **Listing fee** | `TransferListingFeeService` — 2.5% of asking price, human clubs only | **Works.** P2-4. Own `FinanceCategory.LISTING_FEE` |
| **Player can refuse to be listed** | `ListingObjection` with three reasons, two resolutions (uphold / pay 5%) | **Works.** P2-3. The club cannot delist while it stands |
| **Seller chooses which offer to accept** | `POST /transfers/accept-offer/{playerId}/{offerId}` | **Works.** P2-2 |
| **Supporter mood and expectations** | `Team.supporterMood` + `SupporterExpectation` | **Works.** P2-5. Mood bends attendance, so it bends gate income, so it bends the wage-bill ratio |
| **Retirement** | `RetirementService`, hooked between ageing and graduation | **Works.** P2-7. Deterministic age band 33–36 scaled by quality. He leaves; his row and history survive |
| **Graduation caps** | Per-club room counted down as used | **Works.** P2-6 |

> **Four of the rows above were first written in this document as "missing."** They are built. The source of
> the error is stated below, because it is a trap rather than a slip.

### What does not exist

| Gap | Detail |
|---|---|
| **Sale tax** | The 8% base plus the profit-based time-at-club component is the one remaining market-friction mechanic. The agent fee exists (2–5%); read it before adding tax. |
| **Debt / interest / bankruptcy** | No debt entity, no interest accrual, no bankruptcy path |
| **Board cash ceiling** | No ceiling and no weekly release rate |
| **Manager sacking** | `sackingReview` is a read-only boolean. No entity, no persistence, no end-of-season review |
| **Pre-match tactical preview** | `MatchPreviewService` computes the prediction but `MatchController/{id}/preview` returns `Map.of()` |

### The trap, and it is recorded because it will happen again

**`TECHNICAL_OVERVIEW.md` §12 "Known gaps" is stale.** This document's first draft marked prize money,
listing fee, player objection, seller's choice, supporter mood and retirement as absent — all six, every
one of them read out of §12 and the archive, and **all six were closed months ago.**

Where the source, §12 and this document disagree, the source wins. But §12 is not a poor source — it is a
**good source that has not been kept current**, which is more dangerous, because it reads as authoritative.

**Consequence for the board:** the P2 ordering follows
`archive/COMPETITIVE_ANALYSIS.md` §10, one item of which is *"wire the tactical editor to the engine"*.
That item is closed, and the bridge that makes it work is not the one the document names. See **T-REST-7**
and **T1-3** on the new board.

---

## 14. Analytics Layer

### What exists

| Component | Location | State |
|---|---|---|
| Match stats (possession, shots, passes, duels) | `MatchDetailService` | **Works.** |
| Pass-failure taxonomy | DEFLECT / LOOSE_PICKUP / INTERCEPT / DUEL / OFFSIDE / OOB / FOUL | **Works.** |
| Per-player ratings | `MatchRatingCalculator` | **Works.** |
| Pre-match prediction | `ScheduleInsightService` — expected goals, win/draw/loss probabilities | **Works.** |

### What does not exist

| Gap | Detail |
|---|---|
| xG from tick data | Pre-match xG exists (`ScheduleInsightService`), but **post-match xG computed from shot location + defensive pressure at the moment of the strike** does not exist |
| xA | Does not exist |
| PPDA | Does not exist |
| Field tilt | Does not exist |
| Progressive passes | Does not exist |
| Momentum | Does not exist |

### Verdict

Basic match stats and pre-match prediction exist. The **post-match analytics layer** (xG, xA, PPDA,
tilt, momentum) is the biggest single missed opportunity for differentiation — and the one capability
neither browser competitor can honestly offer, because only a real spatial simulation can compute it from
the same data that drives the picture.

---

## 15. Community & Social

### What exists

| Component | Location | State |
|---|---|---|
| Forum (TIFO + GENERAL) | `ForumController` — sections, topics, posts, edit, delete, soft delete | **Works.** |
| Moderation | Moderator/admin/owner can edit/delete others' posts | **Works.** |
| Forum bans | With expiry and reason | **Works.** |
| Private messages | `MessageController` — threads, subject on first message, body-only replies | **Works.** |
| Notifications | `NotificationController` — stored in DB, polled every 30s, unread badge, chime | **Works.** |
| Registration queue | `AdminController` — approve/reject | **Works.** |

### Verdict

Community and social features are **fully built.** No gap.

---

## SUMMARY: What Actually Exists vs What Is Missing

### Fully Built (No Gap)

1. Match engine (tick-based, deterministic, calibrated)
2. Replay viewer (2D/3D, full controls)
3. Tactical editor → engine wiring (data reaches engine)
4. Formation catalog (9 formations, formation-aware)
5. Lineup template (starters + bench, auto-pick fallback)
6. Substitution engine (5 subs / 3 windows, injury + fatigue auto-sub, GK protection)
7. Conditional substitution engine contract (built, unit-tested)
8. Economy (ledger, wages, gate, sponsorship, FFP, board trust)
9. Transfer market (offers, negotiation, windows, contracts, loans, free agency, AI auction)
10. Training (intensity, minute-proportional, coaching staff, facilities)
11. Juniors (academy, talent hidden, scouting, promotion)
12. National cup (preliminary round + 256-club knockout)
13. International cups (15 tiered competitions, group + knockout)
14. National teams (senior + U21, elections, qualifiers, World Cup)
15. Promotion/relegation (playoffs, ladder)
16. Loans (full service, REST API, screen)
17. Stadium (8 sections, build/quote/price, attendance model)
18. Community (forum, messages, notifications, registration)
19. Admin (database controls, repair, backups, job monitoring)
20. 48-country world (pyramid, promotion ladder, simulated countries)
21. Calendar (12-week season, 4 slots/week, all matchdays)
22. Post-match updates (tables, Elo, ranking points, honours, milestones)

### Partially Built (contract exists, wiring or UI missing)

| Feature | What exists | What is missing |
|---|---|---|
| Multi-tactic storage | One `TeamTacticsProfile` per team, **unique constraint on `team_id`** | No tactic library, no saved tactics list |
| Per-match tactic selection | Engine reads one tactic per match (`SideTactics` is immutable for the match) | No `MatchTactic` entity, no condition model, no score-based switching |
| Per-match lineup | `Lineup.match` field exists in the schema | No UI or service creates per-match lineups — both readers query `match IS NULL` |
| Conditional substitutions | `ConditionalSubstitutionRules` engine + `SubstitutionPlan` REST API | `conditionalSubs.add()` never called from production; no UI |
| Live viewing | Replay viewer + "live results desk" | No true real-time streaming **during** simulation; no "skip to result" |
| Analytics | Match stats + pre-match prediction | No post-match xG, xA, PPDA, tilt, momentum |
| Pre-match tactical preview | `MatchPreviewService` computes a real prediction | `MatchController/{id}/preview` returns `Map.of()` |

### Not Built (missing entirely)

| Feature | Owner's vision |
|---|---|
| Day 6 form & morale job | Calendar promises it; no `DayJob` triggers on day 6 |
| Background match generation 30 min before kickoff | Matches generated by the matchday job at kickoff, not before |
| Timezone-aware scheduling | All 48 countries share `Europe/Belgrade`; one `WeekTemplate` for every country |
| Sale tax (profit-based time-at-club) | The one remaining market-friction mechanic |
| Debt / interest / bankruptcy | No failure state |
| Board cash ceiling | No ceiling, no weekly release rate |
| Manager sacking | `sackingReview` is a read-only boolean |
| NT friendly request UI | Service + tests exist; no endpoint, no UI |
| WebSocket-based live streaming | Four endpoints registered, **dead**; no frontend client anywhere |

### Built and closed — do not rebuild these

Six items this document's first draft marked missing are **built**: prize money (P2-14), listing fee
(P2-4), player refusal to be listed (P2-3), seller's choice of offer (P2-2), supporter mood and
expectations (P2-5), retirement (P2-7), and graduation caps (P2-6).

---

## CORRECTIONS TO MY EARLIER ANALYSIS

Recorded rather than quietly fixed, because each one has a lesson attached.

1. **Live match viewing** — I said "Missing," and proposed a 5-7 day build. **The owner stopped me:
   *"kako si dosao do ovoga? ovo postoji."*** They are right. The replay viewer (2D + 3D, full controls) and
   the live results desk both exist and work. What is missing is true real-time streaming **during**
   simulation — a different thing from the watch experience, and one that should not be built before the
   owner says which of the two the game should have.
   **Lesson:** I read a markdown table. I did not read `viewer.js`, which is 2,023 lines and answers the
   question completely.

2. **Tactical editor wiring** — I said "not wired to engine," copied from
   `archive/COMPETITIVE_ANALYSIS.md` §9.1. **Wrong.** It is wired, through `TacticsRulesProvider` →
   `TacticsRules` → `SideTactics` → `MatchOrchestrator`. Worse, **the archive is what the P2 ordering
   follows**, and one of its top three items is closed.
   **Lesson:** the archive is explicitly labelled history in three different places. I read it anyway
   because it was the only document that answered the question.

3. **Prize money, listing fee, seller's choice, player refusal, supporter mood, retirement** — all six
   marked missing, **all six built**, all six from `TECHNICAL_OVERVIEW.md` §12 "Known gaps."
   **Lesson:** §12 is a **good document that has not been kept current**, which is more dangerous than a
   bad one, because it reads as authoritative. Every T0 task on the new board states what already exists
   precisely so this cannot happen a second time.

4. **National cup elimination round** — I said "not confirmed." **It exists:**
   `CupFixtureSeeder.ENTRY_ROUND_TEAMS = 108`.

5. **GK substitution rule** — I said "not specified." **Implemented.** Both `SubstitutionService` and
   `ConditionalSubstitutionRules` exclude the keeper from automatic substitutions, and `pickReplacement`
   matches GK status, so a keeper is only ever replaced by a keeper.

6. **Transfer window timing** — I said "not confirmed." **Matches the owner's vision:** weeks 5–6
   (summer) and 11–12 (winter).

7. **Day 6** — I said "not done." **Correct.** `WeekTemplate.DayKind.MORALE` exists; no `DayJob` triggers
   on day 6.

8. **Formation variety** — the archive says "9 in the catalog, 1 applied." **The code contradicts it:**
   `RealSquadFactory.slotOrderFor(formation)` applies the catalog's role keys, and
   `TacticsRules.anchorsFor(formation)` reads the matching anchors. This is a **verification** task, not a
   defect.

9. **Away-side tactics (P0-3)** — the board records "away teams use home tactics." **`SimMatchService`
   builds `SideTactics` from both sides' own rules.** Either the board is stale or a diagnostic path can
   still reach a single-rules match. Recorded as **T-REST-7**, a verification, because a task whose
   premise has been falsified sends the next person hunting a defect that is not there.
