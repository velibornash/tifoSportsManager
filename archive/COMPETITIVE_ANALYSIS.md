# TIFO Manager — Competitive Analysis: Sokker vs Hattrick vs Football Manager

**Date:** 2026-09-26 · **our-game columns re-verified 2026-10-01**
**Author:** product/architecture review
**Scope:** the UI Football game (`/dashboard.html`, `newLogic/`) compared against the three reference managers.
**Status of our game:** originally verified against git `9f44d4a` + uncommitted Sprint-2 WIP. **Re-verified against `7f8763a` on 2026-10-01, 220 commits later.** Only our-game cells were revised; the Sokker, Hattrick and Football Manager columns are unchanged. Substantive changes are marked **[re-verified]**. Full before/after detail: `experAudit01102026.md`.

**Legend:** ✅ implemented · 🟡 partial / stubbed · ❌ absent · ⬜ not applicable to us

---

## 1. Method and sources

| Source | How it was gathered |
|---|---|
| **Our game** | Read `sprintBacklog.md`, `sprintProgress.md`, `expertAudit.md` in full; then verified every load-bearing claim against the source tree. **[re-verified 2026-10-01]** Re-audited against `7f8763a`, 220 commits later: every claim in §3 and §7 re-checked in source, five claims re-verified by hand (§12), and the whole prior audit's finding list re-tested and scored (`experAudit01102026.md` §2). |
| **Sokker** (`sokker.org`) | `sokker.org` live site, the 18-chapter official `/rules`, the German national chapter FAQ (`sokker-deutschland.de/faq` — the only document that explains *mechanics* rather than *rules*), real 2026 match reports, Dev Diary #82–#86, forum threads. The Fandom wiki is ~14 years stale and was **not** relied on. |
| **Hattrick** (`hattrick.org`) | Official Manual/Help across regional mirrors, `wiki.hattrick.org`, Hattrick Developer Blog (~1M match dataset), the 2025/2026 arXiv Bayesian-network paper, community tools (Hattrick Organizer, HT-Ro, hattrick-youthclub), Reddit. |
| **Football Manager** | footballmanager.com feature pages, sports-interactive.com news, Steam, FM Scout, FMInside, FM forums, FM24/FM25/FM26 press. |

**Caveat carried forward:** several Sokker rule pages are internally inconsistent (rules page still says 5% sale tax; an Aug-2025 news post supersedes it with 8% + a profit-based component). Where that matters it is flagged inline.

---

## 2. TL;DR — the thesis

> **[re-verified 2026-10-01] The original thesis below was accurate on 2026-09-26 and no longer is.**
> It is kept because the reasoning still holds and because the correction is more instructive than a
> replacement. Read the correction first.

**~~We have the best match engine of the three, and roughly none of the management game.~~**

**We now have the best match engine of the three, and a management game that closes — but with no
stakes and no social layer.** In 220 commits the `earnings → wages → budget → squad → league
position → board trust` loop went from two nodes and no edges to all six with real edges.

That is not a comfortable position either, because it is a *different* uncomfortable position. The
original thesis was right about the cause and wrong about the remaining work:

- **Sokker** has a *worse* match engine than ours — its own help text says ratings play **no role**,
  and results are decided action-by-action by 8 skills plus tactics plus randomness. 40,000 clubs,
  38.5M matches, 22 years of live economy. **We are now roughly 60% of the way to Sokker's loop and
  0% of the way to its social layer.**
- **Hattrick** has an engine the community itself calls broken: *"you can still lose to someone who
  has literally zero chances of winning."* 14.7M registered accounts, a 29-year economy. **Its
  published wage formula and its auditable sector ratings are the two things we still copy from
  rather than compete with.**
- **Football Manager** has the deepest management simulation ever built and is widely hated for its
  UI and its grind. **Its Data Hub is the one capability where all three of us are weakest and FM is
  not.**

**What survives unchanged from the original argument, and is now the load-bearing part:** the match
engine was never the product. For two decades two companies have proven that a manager game lives
or dies on the management layer, and that a mediocre engine is entirely survivable if the loop is
deep enough. **We proved the loop closes; we have not yet proved it is *deep* enough to be
compelling.** Depth is not features — it is trade-offs (no training decision can be strictly
dominant), consequences (no decision can be free of consequence), and other people (a market with
one participant is a monologue).

**Our one structural advantage that neither browser competitor has, and it is unchanged:** both of
them must choose between a meaningful match view and meaningful numbers. Sokker chose the animation
and made its report deliberately uninformative (*"the report data is useless… only [the animation]
do you recognise everything you need"*). Hattrick chose the numbers and has no spatial match view at
all. We have a real spatial, tick-based, deterministic physics engine *and* can compute honest
analytics from the same simulation — meaning we can make the animation and the statistics **agree**,
and both be true. **This is now the only remaining structural advantage, because we no longer have
the "best engine, no game" asymmetry. It is also the advantage we have done least about** — §9.8,
and the largest single gap in the product. See §11.1.

**The three things that must happen, in order — [re-verified] two are done, and the order was wrong:**

1. ~~**Build the economy loop**~~ — **DONE.** `Player.earnings` drives a real wage bill,
   `Team.budget` moves on a nine-category weekly ledger for all ~14,880 clubs, FFP bands are shown
   to the player. (§9.2)
2. ~~**Replace the prose-string transfer market with a real auction**~~ — **DONE, 4 mechanics
   missing.** Real offer entities, 5-round negotiation, windows, contracts, loans, free agency,
   AI↔AI auction. Listing fee, sale tax, anti-daytrade and *a seller who can pick a bidder* are
   still absent. (§9.3)
3. **Wire the tactical editor to the match engine** — **NOT DONE, and now the only P0 from the
   original three.** Originally listed first; the economy shipped first instead. That ordering was
   defensible — building the loop first is what made the tactics gap legible — but the cost is that
   the loop now surrounds a match the manager cannot influence. **The owner confirms the engine is
   running on an identical copy of the editor's data pending the final test, so this is now a 2–3 day
   wiring job rather than a 5–8 day build.** (§9.1)

**And a fourth thing that was not on the list, because it did not exist yet:** the world grew from
310 clubs in one country to **48 countries ≈ 14,880 clubs** in five days, and the day-job framework,
game clock and cup draw that schedule it were written for 310 in one country. **Two of those defects
will corrupt a season permanently.** That is now ahead of all three items above. (§9.0)

---

## 3. What our game actually is today

### 3.1 Verified engine capability (the moat)

| Capability | State |
|---|---|
| Tick-based spatial simulation | 40 ticks/min, 3,600 ticks, 15-stage numbered pipeline explicit in `MatchOrchestrator.java:295-712` **[re-verified: the previously cited `114-368` was the constructor region, not the pipeline; the file is now 786 lines]** |
| Determinism | `SimulationRandom.seed(fixture.getId())` + thread-local `Random`; batch runs reproducible — **this is why calibration was possible at all** |
| Ball physics | Velocity-based, per-mode deceleration, lateral spin, goal-plane crossing, OOB bands |
| Goalkeeper | Bisector positioning, advance ramp, 1v1 rush, reach/chance model. 11 unit tests. Correctly solves both Sokker failure modes (keeper magnet, keeper never saves) |
| Duels | Skill-weighted logistic `pWin = 1/(1+e^(−0.18·gap))`, context-gated firing (a defender level with the carrier only contests at 0.08 cells), one presser per threat (`isClosestEligiblePresser`) **[re-verified — the proximity-only model is gone; 598 → 268 per match]** |
| Rules | Offside (second-to-last defender), VAR with frequency gates, discipline, restarts, throw-ins, corners, goal kicks |
| **Injuries** | **8 types**, fatigue-driven, selection weighted by fatigue so the tired player is both likelier to be picked and likelier to go down. `InjuryServiceTest`. **[re-verified — the prior expert audit's "quarantined, no generator" was stale]** |
| **Penalties** | **Full model, not just an award**: taker selection on `striker×1.2 + technique`, keeper commits to a read *before* the kick, conversion `BASE_SAVE_IF_READ 0.42 / IF_WRONG 0.06`, stall guard after 40 ticks. Validated over **4,000 seeded penalties at 70–82%** **[re-verified — previously "awarded but never taken"]** |
| Substitutions | 5 subs / 3 windows, injury + fatigue auto-sub, **a sent-off player is never replaced** |
| Real stoppage time | `StoppageClock` — real added time per half driven by named causes, and it **pauses the whole pipeline**, not just the clock |
| Replays | File-backed, bounded (200 entries / 14 days), `410 GONE` on expiry |
| Viewer | 2D canvas + 3D (three.js), LED scoreboard, click-to-inspect, event timeline, 0.25×–10×, keyboard, mobile ticker |
| Stats | Possession %, possession chains, pass-failure taxonomy, duels, interceptions, restarts, injuries, subs, per-player ratings |
| **Tests** | **864 `@Test` across 117 files, 24.4% of main LOC** (was 176 / 20 files / 3.8%) — see §3.1.1 |

Measured over 100 matches (a record, not a scorecard — statistical targets were deferred by owner on 2026-09-26): **[re-verified 2026-10-01]**

| Metric | Ours | Real PL |
|---|---:|---:|
| goals | 3.55 | 2.7 |
| shots | 35.7 | 25 |
| on-target % | 28% | 33% |
| pass accuracy | 85% | 80–86% |
| penalties / conversion | 0.24 / ~76% | ~0.27 / ~76% |
| fouls | 12.0 | 22 |
| duels | 268 | ~100 |
| corners | 6.4 | ~10 |

**[re-verified] Two corrections.** On-target % was 43.9% when this table was written and was the
§5 root cause of goal inflation; the Sprint-1 save-model fix moved it to 28% — now *below* the real
33% rather than above it, so the overshoot has inverted and is unexamined. Throw-ins, not on-target
%, are now the largest outlier at **77.0 against a real 35–45**, and nobody has looked at them.
Penalties are now **taken**, not just awarded: `PenaltyEngine` selects a taker, commits the keeper
to a read before the kick, and is validated over 4,000 seeded penalties at 70–82% conversion.

### 3.1.1 Tests — **[re-verified]**

| | 2026-09-26 | 2026-10-01 |
|---|---:|---:|
| Test files | 20 | **117** |
| Test LOC | 3,605 | **22,245** |
| **Test/main ratio** | **3.8%** | **24.4%** |
| `@Test` annotations | 176 | **864** |

`mvn test` duration (previously quoted as 1:10) is unverified; the last recorded green run is 26
commits behind HEAD. `SeasonService`, `TransferService`, `TrainingProgressionService` and
`YouthAcademyService` — the four highest-risk untested services named in the previous audit — are
all covered now.

### 3.2 Verified management state — **[re-verified 2026-10-01]**

| Layer | Depth | Note |
|---|---:|---|
| Match engine | **9/10** | Unchanged. Better than either browser competitor |
| Season, fixtures, pyramid, promotion/relegation + 2-leg playoff | **7/10** | **[re-verified]** 48 countries × 31 divisions × 10 clubs, ~14,880 clubs. Promotion ladder runs for **every** country. **A scheduled clock now exists** (`DayJob` framework, hourly, on in prod). **But** the scheduler has correctness defects that will corrupt a season — see §9.0 — so the score does not move |
| Squad/player model | **4/10** ↑ | **[re-verified]** 8 skills, talent, contracts with expiry, free agency, morale, per-match form. Still no retirement — the pool only grows |
| Tactics | **1/10** | **Unchanged, and the P0 below still stands.** Editor and data model exist and are the best part of the concept; still not wired to the engine. See §9.1 and its owner clarification |
| Training | **6/10** ↑ | **[re-verified]** Intensity `LIGHT/NORMAL/VERY_HARD` trading growth against injury risk, minute-proportional, per-club AI training, coaching staff reaches the engine. No per-player focus — **S4.1 was deleted by the owner** |
| Juniors | **5/10** ↑ | **[re-verified]** Talent now hidden behind a narrowing scouting range (`TalentRange`), real scouting network, `Country.youthRating` drives it. Current ability still shown raw; carryover juniors still freeze for a season |
| Transfers | **6/10** ↑↑ | **[re-verified]** Was ~15% of Sokker. Now: real offer entities, 5-round negotiation on fee/wage/length, transfer windows, contracts, loans, free agency, squad limits, AI↔AI auction. **Still missing listing fee, sale tax, anti-daytrade, work permits — and the seller still cannot choose which offer to accept** |
| Economy | **6/10** ↑↑ | **[re-verified]** Was 0. Ledger with 9 categories, wage bill from `Player.earnings` (13 call sites), gate, sponsorship, prize money, stadium build, pitch upkeep, FFP bands **shown to the player**. **Still missing debt/interest/bankruptcy and any board cash ceiling** |
| Meta layer (board/trust/morale) | **3/10** ↑ | **[re-verified]** Board trust 0–100 computed from FFP + standing + squad; morale is live and feeds both training growth and the match. **But `sackingReview` is a read-only boolean — you still cannot be sacked**, no end-of-season review, no retirement, no supporter mood |
| Cups, internationals, national teams | **3/10** ↑ | **[re-verified]** A cup that actually plays (256-team bracket), 3 populated international cup tiers, 96 national sides with elections. **But ~1 playable international fixture, the cup draw is deterministic by index not random, and continental entry for 47 of 48 countries is alphabetical** |
| Registration | **working** ↑ | **[re-verified]** `POST /auth/register`, admin approve/reject, frontend both ends. **The multiplayer gate is open** |

**The thesis of §2 no longer holds in its original form.** "We have the best match engine of the
three, and roughly none of the management game" was accurate on 2026-09-26. In 220 commits the
management layer went from absent to present: the `earnings → wages → budget → squad → league
position → board trust` loop that this document named as the single highest-leverage change now has
all six nodes and real edges.

**What that leaves, stated as a competitive position rather than a feature list:**

- **We have the loop, but not depth.** Every remaining training decision is now a real trade-off,
  but there is no decision with *consequence*: no sacking, no retirement, no failure state. A loop
  where nothing can go wrong is a spreadsheet with extra steps.
- **We have the plumbing, but not the social layer.** Registration works and a human market is
  possible, but with one player it is a monologue, and the four auction mechanics that make Sokker's
  market a *conversation* — listing fee, sale tax, anti-daytrade, and a seller who can pick a bidder
  — are all still absent.
- **We are last in the browser category on the one thing only we can do well.** Pre-match preview
  and honest post-match analytics (§9.8, §11.1) are the two rows where Hattrick is still ahead and
  we are behind, and they are the only two where the gap is a *choice* rather than a limitation.
- **And the world is now 48× bigger than the code that schedules it** (§9.0), which is ahead of
  every feature on the list.

Read that as one sentence: **the game got good, and the two things that would make it great are the
tactics wiring and the analytics layer — and both are now cheaper than they were in September.**

### 3.3 Product decisions already taken (and how they change this analysis)

Three owner decisions materially reshape the competitive picture, because they make us a **multiplayer human economy**, not an AI sandbox:

1. **AI clubs never buy or never sell.** Only real players trade. An admin can force-list. This means the transfer market is only as liquid as the human player count — with one player it is a monologue, and the auction UI must be built for 10+ players across multiple countries from day one. **[re-verified] Partly superseded in code** — see below.
2. ~~**Manual week advance, no schedulers.**~~ **[re-verified] SUPERSEDED.** There is now a day-precise game clock, an eleven-job `DayJob` framework on a weekly template, and a scheduler that fires on the hour. `game.clock.scheduler-enabled` is `true` in prod, `false` in dev. **The decision it was a workaround for has been made the other way, and it was the right call** — but it bought the world infrastructure and brought §9.0's defects with it.
3. **Statistical calibration is polish, not a gate.** Missing and broken mechanics are the priority. **[re-verified] This decision is doing more work than it was meant to.** The engine has not been recalibrated since 2026-09-26 and its largest remaining outlier — throw-ins at 77.0 against a real 35–45 — is now unexamined, while three genuine *mechanics* bugs (§9.0) are indistinguishable from tuning noise in the same tables.

Consequence: the `expertAudit.md` findings "AI↔AI transfers never happen" and "run finances for all 310 clubs" are **not defects** — they are invalidated requirements. AI clubs need only be plausible *on the pitch*.

**[re-verified 2026-10-01] Both have since been built anyway, and that turns out to be right.**
`TransferService:775-870` now runs a weekly auction in which every club with a reason bids, weighted
by `ClubNeedService.interest` — the commit message notes the previous version meant *"the market was
the player's alone."* And `SquadTrainingService.trainEveryClub` trains every club while
`WeeklyFinanceService` settles every club's ledger weekly.

Both are correct calls and neither contradicts the PO decision: the decision was about **AI clubs
buying and selling**, not about the world being alive. A market with only one buyer is a
monologue whether or not the AI are simulated, and a pyramid where the other 310 clubs never grow
or decline has no meaning for promotion. **What the PO decision still correctly rules out** is
allowing AI clubs to *initiate* human-facing offers — a real manager must never be outbid by a
dice roll. Keep that line, and note it is not what the code currently enforces.

---

## 4. Sokker (sokker.org) — the primary baseline

**What it is:** a browser MMOG running since 2004. Free to play, no pay-to-win by policy. You inherit a real-named club in your country of residence — you do not build one from scratch. Live state observed: **Season 79**. Claimed scale: **5,000+ leagues, 40,000+ clubs, 38.5M+ matches, 51 languages**.

### 4.1 Match engine — action-resolved, ratings-free

This is the defining fact and it is stated explicitly in the official FAQ:

> *"Bei Sokker basieren die Spiele ausschließlich auf den Spielerskills und deiner Taktik… **Diese Ratings spielen bei der Berechnung der Spiele keine Rolle.**"*

Every individual action resolves against player skills:

| Action | Skills |
|---|---|
| Running | Pace (with ball: Technique) |
| Duel | **Technique of carrier vs Defending of tackler** — frontal duels heavily favour the defender; from the side/behind favour the carrier |
| Choosing a pass | **Playmaking** — the passer slows while scanning and becomes vulnerable |
| Executing a pass | Passing; the receiver needs Technique to control it |
| Shooting | Striker (accuracy + power) |
| Save | Keeper — **shot and save are independent actions**; there is no striker-vs-keeper comparison |
| Header | **Height** (taller wins; tall keepers reach further) |
| Fatigue | **Stamina**, driven by *activity* not elapsed time. **Playmaking is the one skill that never degrades** |
| Following tactics | **Tactical Discipline** (= Experience + Team-work) |
| Acceleration | **BMI** — ideal 23.75; deviation slows acceleration and raises fatigue, but not max speed |

Height and weight are genuinely in the engine (confirmed by a Sept-2025 dev post). Pitch condition is real: poor condition → unpredictable bounces and **higher injury probability**. Weather affects attendance only. **Offside is modelled.**

**Viewing:** 3D is the headline feature. Live coverage takes ~20 minutes of real time, then remains replayable with pause/speed. Generated minute-by-minute text commentary. Watching *other* managers' matches is Plus-gated.

**Reported stats:** possession, "play in your half" (the rules explicitly warn that over 50% means your players were defending), shots, fouls, cards, offsides, and per-player marks with Off.%/Def.% columns. The marks are **descriptive only** — a keeper who never had to save gets a bad mark.

### 4.2 The tactical editor — Sokker's entire identity

- The pitch is a **5 × 7 grid = 35 cells**. For each of the 35 ball locations you set **all 10 outfielders' positions**. 10 × 35 = 350 placements per tactic.
- Players physically move around the pitch in the editor as you drag the ball, limited by their real pace and stamina — you can see whether your shape can physically get there.
- **No mentality slider. No tempo slider. No pressing slider. No formation style dropdown.** All nuance is grid geometry.
- Formations are **not bound to your labels**. A "4-4-2" can have four midfielders on the lowest row and two defenders up front.
- ~13 default tactics, max 100 saved. The tactic is a 35-character string (`tact=ABBBBDBFBF…`), copy/paste-able.
- Community verdict: the editor is *"probably the least-used tool"* (the auto-suggest Assistant) but the manual editor is *"the reason most long-term players stay."*

### 4.3 Lineup, roles, set pieces

- **11 starters + up to 5 subs. Hard role caps: 1 GK, 5 DEF, 5 MID, 3 ATT.** Break them and you **lose that week's training**.
- Instructions are **GK / DEF / MID / ATT**, not sliders. DEF clears aggressively and long; MID will eventually pass (and gets into more duels); ATT rarely releases the ball.
- **Corners: you cannot nominate a taker** — the nearest player takes it. You influence corners only through the edge cells of the grid.
- Free kicks: taker nominable, **but only in the opponent's half**. Penalties: nominable.
- **Max 3 substitutions**, permitted only when a player is yellowed, red, or slightly injured.
- Walkovers: opponent fields <10 or deletes tactics → **5-0**. Both walkover → 0-0 in a league, 1-0 in a cup.

### 4.4 Player model

**8 skills, 0.00–17.99**, named tiers 0 *tragic* → 11 *formidable* → 17 *divine* → 17+ *superdivine*. **Stamina caps at formidable [11]; everything else caps at superdivine.**

Hidden/derived: **Talent** (training-efficiency multiplier — the community's "Talent 4" shorthand means ~4 weeks per level), **Form**, **Tactical Discipline** (Experience + Team-work, where **Team-work resets to zero on every transfer**), Height, Weight/BMI, Value, Wage.

**There are no star ratings** — a deliberate design choice. The community invents weighted skill-sum formulas as a proxy.

Ageing: all players age 1 year off-season; **other skills only start falling at 30**; stamina decays continuously.

**No morale. No loyalty. No dressing room. No board expectations. No manager rating.** The only psychological layer is **supporter mood** (7 levels) and **supporter expectations** (9 levels, from *"totally outclassed"* to *"come to eat popcorn, relax and watch a festival of goals"*).

Discipline: yellow/red, 3-yellow accumulation, one-match ban. A **sacked player ceases to exist in the Sokker world** — not released to a pool.

### 4.5 Training

Runs every Thursday. Two layers: individual/advanced (which skill) + general (automatic, all skills).

- **Advanced training: max 10 players/week** (22 for pace, all for stamina, 2 for keeper).
- Skills split into **positional** (Keeper↔GK, Defending↔DEF, Playmaking↔MID, Striker↔ATT — full effect only in the matching slot) and **complementary** (Stamina, Pace, Technique, Passing — same effect in any slot).
- **Efficiency is proportional to minutes played in the assigned role.** Only players who played get training (except stamina). The UI shows a *Games* column and a projected *Eff.* column; "!" means no training.
- Only the **last** match's role counts.
- **Coaching:** head coach has 8 per-skill training ratings plus a **General Appraisal** (which mainly caps Team-work). Assistants only contribute General Appraisal. Youth coach sets graduation speed *and the accuracy of junior skill estimates*. **More than 3 assistants is a trap** — "if you hire more than 3, they will argue, resulting in training being less effective, or even lost."
- Benchmarks: talented 16yo below very good ≈ 2 weeks/level; from 19 ≈ 3; from 22 ≈ 4. **Pace trains ~25% slower.**

### 4.6 Juniors — the "sub junior school"

- **Max 30 places, 1,000 $ per place per week.** Every Saturday 0–6 arrive; you request admission and **if you have no free place you never know who you rejected**.
- Juniors show only: preferred position, age, an **estimated overall level (coach's estimate, accurate to ±2–3 levels)** and weeks remaining. **Individual skills are hidden until promotion.**
- **Graduation caps:** skills top out at *outstanding + subskill*, stamina at *very good*. No correlation between school level and final skill spread — a good graduate typically has **one** good skill and the rest poor.
- Junior league: one league per division, 14 rounds, random weekly pairing, top-half/top-half table split, no injuries, no cards, no goals recorded. Affects only supporter mood.
- Community verdict: *"a kind of lottery where you mainly know the cost but not the profit in advance."* 16-year-olds are the profitable class.

### 4.7 Transfers — pure auction, no windows, no agents, no loans, no free agents

This is the deepest auction economy in browser management and the centre of the game.

- **Minimum listing 1 $; listing fee 2.5% of the starting price. Auction runs 2 days.** Withdraw only while no bids have been placed. Highest bidder wins.
- **Two-step completion:** the winner must *take the player in* and the seller must *release him*. If neither acts for a week the transfer **auto-cancels and both sides pay 10% of the final price**.
- **Taxes: 8% base sale tax, plus a second component scaling with time at the club — charged only on the *profit*, not the full price** (effective 16.08.2025; an explicit anti-daytrading design). Plus 5% to the original club if the player wasn't originally yours and is ≤23.
- **Daytrade prevention: any player can refuse to be listed.** Refusal chance rises with few appearances for your club and with how many of your players you listed in the last 2 weeks. Exempt: your initial squad and academy graduates.
- **No contract length, no agents, no loans, no free agents, no transfer windows.** Wages are auto-derived from skills and **renegotiated at the start of every season**; the manager does not negotiate contracts.
- Up to **3 transfer advertisements per club** — advertising *unlocks skill visibility* even for unlisted players.
- The community maintains a **full historical transfers database** precisely because **there is no in-game fair-value model** — value is emergent from supply and demand. Timing is a real mechanic (Saturday mornings are flooded with fresh graduates; Mon/Tue evenings are the buyer's window).
- **PO decision already taken:** auction closes below reserve → player stays listed and can be re-listed. Reserve is a real negotiating position.

### 4.8 Club, finances, stadium

- **Weekly costs:** stadium maintenance 0.75 $/place/week; junior school 1,000 $/place/week.
- **Debt:** interest 4.5%; **max debt −250,000 $**; **bankruptcy at −500,000 $** with a 72-hour grace period after login, then a 14-day deletion.
- **Income:** weekly sponsorship (Saturday — varies with division prestige, opponent rank, supporter mood, weather, performance) + gate receipts (all to the home team) + **player sales, which the community regards as the most important and reliable income source in Sokker**. Season premium = 1.5× the Saturday sponsorship.
- **Supporter card: 125 $/season.**
- **Ticket prices settable 1–250 $ per stand.** Attendance is an explicit optimisation problem with a real demand curve — **the revenue-maximising price is not the max price.**
- **Stadium: 8 sectors, 4 stand types** (grass standing / terraces / benches / seats) + roof per sector. **Construction ETAs have a large random factor — 20%+ overruns are normal.**
- **Pitch: 6 grass tiers** (Garden 5,000 $ → Angel grass 125,000 $), maintenance packages at 50%/100%. **Pitch dimensions adjustable 64–78 m wide × 95–110 m long** — and **pitch size affects your tactics**, a genuine lever, not cosmetic.
- **Staff: head coach, up to ~3 assistants, youth coach.** ❌ No scout/recruitment department, no physio/medical, no board, no owner expectations, no club reputation stat, no manager rating.

### 4.9 Season and competitions

- **Season = 13 weeks** + 2-week off-season. **League pyramid: ×3 leagues per level down** (Div 1 = 1 league, Div 2 = 3, Div 3 = 9, Div 4 = 27). Each league = **12 teams, double round-robin, 22 matches**.
- Points 3/1/0. Tiebreak: points → GD → goals scored → wins → initial pre-round-1 position.
- Week 1 warm-up + national cup; weeks 2–12 two league matches (Wed + Sun); week 13 play-offs.
- **Promotion:** champion auto-promoted, bottom 3 auto-relegated, **2nd plays a Sunday play-off against the 7th–9th of the division above.**
- **National cup:** single elimination, largest power of two ≤4096 sized to the country, two pots by ranking points, **played at the weaker team's stadium**. Prize: QF 22,500 / SF 45,000 / runner-up 75,000 / champion 150,000.
- **Sokker Club Champions Cup: exactly 128 teams**, every league champion from a country with an NT, topped up by cup winners.
- **World ranking is points-based** and drives cup entry, cup draw seeding and the global leaderboard.
- **National teams: a Senior NT and a U21 NT per country, each with its own 60,000-capacity stadium. The coach is elected by users** (13 weeks tenure to stand, ~8-day election, ties to the earliest account, **an elected coach cannot resign**). Up to 40 players called up. **Owners can hide skills from the NT coach** — skills become visible only if transfer-listed or advertised. That is a deliberate information-asymmetry mechanic.
- **World Cup spans two seasons** — qualifying, then finals (32 teams, 8 groups of 4, top 2 advance, then R16/QF/SF/F, both semi-final losers enter a Bronze Cup).
- **Arcade matches (Plus):** any day except Fridays or official match days, **zero consequences** — no injuries, no cards, no training, no income, no ranking effect. Even injured and suspended players are selectable. A pure toy mode.

### 4.10 Community and social

SK-mail (manager-to-manager messaging), forums (international, per-country, per-division, Transfer / Transfer Star / Transfer Junior), guestbooks, user newspapers, a TV Studio with match betting, **associations**, friendly matches (6-day arrangement window, gate split 50/50; friendly-league income goes entirely to the home team), **friendly leagues** (Plus to found; founder picks date, participants, and whether there's a second leg; the founder may never abandon or delete it), and **Arcade**.

**Third-party tooling is officially welcomed** via a documented API (`sokker.org/apidoc.html`) and one blessed XML feed. The ecosystem is mature and is itself a moat: Sokker Organizer, oSokker, Apollo, SokkerViewer, Android Sokker Manager, **Sokker Architect** (open source), NT Database, **Transfers Database Online**, SETE (mobile tactics editor), plus 2026-vintage Chrome extensions for tactic copy-paste.

**Plus tier** is convenience/cosmetic/competitive-info only: emblem, guestbook, match kits, **training-effects monitoring and 10-week skill graphs**, statistics pages, country statistics, **unlimited viewing of all other managers' matches**, found friendly leagues, Arcade, Briefcase (shortlist of observed players/teams).

### 4.11 Sokker: strengths and weaknesses as cited by its own players

**Strengths**
1. The tactical editor is genuinely unique and is the retention reason.
2. The 3D viewer converts an opaque engine into something you can *read*. The FAQ's own punchline: *"the report data is useless… they don't replace the essential thing: watching the animation."*
3. **No ratings to meta-game** — tactical literacy, not spreadsheet optimisation, is the dominant skill.
4. Realistic per-action skill resolution, including duel geometry (frontal vs side vs behind) and activity-based fatigue.
5. Genuinely no pay-to-win, a 22-year economy, **no transfer windows** — which paradoxically makes the market *more* liquid.
6. Deliberately unhurried: two matches a week, one action per week, no timers to beat.

**Weaknesses**
1. **The learning curve is the #1 cause of churn.** A veteran mentor: of ~10 new managers he coached, *"nearly all of them just failed for the same reason… they bought the crap of the crap on the market."*
2. **The ageing/trainee treadmill.** Unlike Hattrick you cannot buy a 19-year-old and still have a sellable asset in a year. Your asset window is roughly 16→23.
3. **No medical staff and no injury treatment** — *"so something like doctors doesn't exist here, unfortunately."*
4. **Market friction:** no fair-value model, a 2-day auction window hostile to global time zones, player refusal to be listed, admins manually repricing deals, heavy bot/alert-script usage, transfer-list spam.
5. **Cheating and weak enforcement** — a documented, unresolved thread about a repeatedly-caught faker who was never banned.
6. **Bot teams strand players** (decades of complaint; partially fixed by a 2025 auto-transferlist feature). Bot teams also stop training their players.
7. **Rewards are trivially small relative to wage bills.** A user's numbers: *"I won the Turkish cup, the reward was almost half of the weekly salary of my players. I won the Turkish first league — with that money I could not buy a single defender. Played last 16 of the Champions League, no reward at all."*
8. **No board, no manager rating, no expectations beyond supporters.** A multi-year community campaign asks for board expectations; the complaint is that *"the football part has too little influence over the club money."*
9. **The report is low-information by design**, and the Assistant position tool is widely panned.
10. **Onboarding paradox:** a beginner guide explicitly tells new managers to *cheat back* by copying tactics off YouTube because the 13 default tactics are *"chaotic and ineffective."*
11. Aggregate sentiment is lukewarm: ~50–52% on OnlineSportManagers, 9th of the top 10 browser managers, behind Hattrick (66%).

---

## 5. Hattrick (hattrick.org)

**What it is:** a browser MMOG running since 1996, 29 years. Season 95/6 in 2026. Claimed scale: **14.75M registered accounts, 873M matches played, 156 countries, 53 languages**; ~2,400–11,300 concurrent depending on the hour. Free forever, no purchasable advantage; monetised via four Supporter tiers (Silver/Gold/Platinum/Diamond, Diamond manages up to 4 teams).

### 5.1 Match engine — sector-based probability, not a simulation

- Generates a **maximum of 15 normal attacks** (max 10 to one team), split into **5 exclusive + up to 5 shared**. For open attacks the midfield battle decides who gets the chance; for exclusive attacks **winning the midfield battle kills the attack**.
- Chance distribution by sector: **~35% centre, 25% left wing, 25% right wing, 15% set pieces.**
- **7 evaluation sectors:** Midfield, Left/Centre/Right Attack, Left/Centre/Right Defence, plus separate indirect set-piece ratings.
- **30 named skill levels × 4 sublevels.** `HatStats` = `3 × Midfield + Σ attack + Σ defence` (world record 651). Star ratings 0–5.
- **Published position × order contribution coefficients** (e.g. winger offensive = 100% WG + 29% PA in side attack, 30% PM in midfield) and **overcrowding penalties** (3 inner midfielders −17.5%).
- Report shows ratings as match averages plus a **Review Details tab with 5-minute interval ratings** (Supporter sees them live).

**Seven tactics:** Normal, Pressing, Counter-Attack, Attack in the Middle, Attack on Wings, Long Shots, Play Creatively. Each derives a **tactic skill level** (Pressing from total defending + stamina; Counter-Attack from defenders' passing×2 + defending; AiM/AoW from the sum of outfield passing) and **a tactic level is reported at kickoff and in the ratings.**

**Match-wide modifiers:** home advantage **×1.199**; Play it Cool **×0.839** but Team Spirit ×1.33; Match of the Season ×1.115; derbies ×1.115. **Overconfidence risk** scales with confidence and the standings gap — choosing Play it Cool triples it. **In-match dynamics:** a pullback reduces attack 15% and adds 10% to defence; **every extra goal of lead costs −6.25% attack / +5% defence, doubling per goal.**

**Specialities** (max one per player): Quick, Technical, Powerful, Head, Unpredictable, Resilient, Support — each driving **special events** (a Quick winger creates a rush event ~1 in 6 matches; a Powerful forward forces a second-chance shot; Technical defenders add a counter-attack trigger 1.7–3%). **Weather** shifts all skills ±5% and interacts with specialities.

**Man marking:** exactly one order per match, by a defender/wingback/IM onto a forward/winger/IM. Triggers after 5 minutes. **A marked player cannot contribute to tactics.** Powerful marker +10% defending; a Technical target −8% on their highest skill, an Unpredictable target +8%.

**Cards** are driven by Honesty and Aggressiveness. **3 accumulated bookings = a one-match ban**, counted across league, cup and qualifiers together.

**Experience and nerves:** in cup and qualifier matches only, the team with the **lower** total experience can become nervous; the gap sets the magnitude. **Formation experience / confusion:** low formation experience lets players get confused mid-match, dropping organisation to a named level; the half-time "extra briefing" partially recovers it. **4-4-2 is permanently "excellent."**

### 5.2 Tactics and lineup

- **11 starters + 7 subs** (a keeper, a defender, a wingback, an inner midfielder, a forward, a winger, plus one extra). Minimum 9 starters — below that, walkover.
- Each of the 5 lines has a *normal* order; 25 outfield slots each accept one of four **individual orders**: Normal, Defensive, Offensive, Towards wing/middle.
- **Do all 11 need roles? Yes, in practice** — a team where everyone gets "Defensive" is still officially a 4-4-2, *"albeit an extremely defensive one."*
- **No offside/onside tactical option; offside is not modelled in match play.**
- Captain must be in the XI (else players draw lots) — captain leadership + experience raise team experience. SP taker per match (cannot be your GK). An 11-name penalty-taker order list for shootouts.
- The lineup page has **five subpages** (Start, Lineup, Team Orders, Penalty Kicks, Control) and a **visual pitch with live sector ratings as you build**.

### 5.3 Player model, training, market

- **7 skills** (Goalkeeping, Defending, Playmaking, Passing, Winger, Scoring, Set Pieces), 30 levels × 4 sublevels.
- **Stamina, Form (0–5 in 0.5 steps with a hidden "background form" sub-level), TSI, Experience, Loyalty, Mother-club bonus, Leadership, Agreeability, Honesty, Aggressiveness, Speciality, Age.**
- **No sharpness attribute** — Form and Stamina are the functional analogues. **TSI formula officially undisclosed**; the community approximates `√form × √stamina × (weighted cubic skill sum)²`.
- **Loyalty:** max bonus **+1 skill level on all skills**, built over 3 seasons. **Mother club always takes 2% of a sale.**
- Ageing: skills start decaying from ~30; a season/player-year is 112 days; no maximum age.
- **12 training types** with per-type trainee caps (Defensive positions 22, Through passes 20, Shooting 20, Short passes 16…) and **intensity 0–5** trading training speed (+3.5%/level) against **injury risk (+2.5%/level)** and form retention.
- **Stamina must be trained every week** as a % of total training. **A player earns at most 90 minutes of training per week**, and the efficiency depends on the role and minutes of the *last* match. Worked example from the Manual: 40 min winger + 90 min forward = **no improvement**.
- **Coaching:** head coach skill 1–5 + separate leadership + offensive/defensive/neutral mentality. **Up to 5 staff total, only one of each specialist type except up to 3 assistants.** Each assistant skill level adds **+0.025 injuries per match** — a level-5 assistant lifts base risk from 0.4 to 0.525, three of them to 0.775. Medic: +20–100% recovery, −7.5–37.5% risk. Sports psychologist: +0.1 Team Spirit and +0.2 confidence per level. **Staff deteriorate**: after a season leadership decays, and once it hits "Disastrous" the *skill* starts decaying.
- **Two youth systems:** the legacy Youth Squad (weekly pulls, choose investment, promote one junior/week for 2,000 €) and the **Youth Academy** (own youth team in youth leagues, 11 weeks to fill, **max 3 scouts** at 10k/15k/20k € presenting one prospect per week each). Promotion requires **age ≥17 and ≥1 full season**; costs 2,000 €. **Dual training**: secondary gives 2/3 of primary; setting both the same wastes 20%. **Per-skill caps and individual ceilings** are exploitable.
- **Injuries:** +1 to +4 weeks. **Base injury risk = 0.4 per match per team** (not per player). Medic level and age dominate recovery — a 19yo needs ~3 weeks with no medic, ~1.5 with a level-5; a 29yo needs 6 / just under 3.
- **Wages are a published formula:** `250 + contribution(main skill) + ½ × others + set-pieces bonus`, +20% abroad, +10% for specialists. **Recalculated only on the player's birthday.** The base starts decreasing from 29.
- **Transfer market:** listing costs 1,000 $ and **reveals all skills to the entire game**. 72-hour auction, bids must rise ≥2% or 1,000, a bid in the last 3 minutes extends by 3. **Buy-back for 10 minutes.** Sum of active bids + first salary cannot exceed 200,000 debt. Seller keeps 81–91%. **Agent fee is a published table: 12% at 0 days → 2% at 16 days.** Previous-club money by matches played (0.25%–4%). **No loan system.** Market split into 8 zones. Autobidding exists.
- **Transfer Compare** shows what a similar player recently sold for.

### 5.4 Economy and stadium

- **Four stand types with published per-seat income and upkeep:** terrace 7 / 0.5, regular seat 10 / 0.7, under roof 19 / 1, **VIP lounge 35 / 2.5**. League home takes 100%; cup 67/33; last six rounds of cups split evenly at neutral venues.
- **Crowd attendance** depends on fan mood, fan-club size, the standings gap and **weather** (rain shifts buyers toward cheaper terraces; a balanced stand mix protects revenue in bad weather). Reports show **seats sold per stand type**.
- Construction: 10,000 per order + per seat (terrace 45, regular 75, roof 90, **VIP 300**). **There is no separate ticket-price slider** — pricing is expressed through the stand-type mix and supporter mood.
- **Interest 3.33%** on negative balances. **Line of credit 500,000; at 500,000 debt you get a bankruptcy warning and two weeks to get back inside the limit or the club is removed from the series system.**
- **The board limits working capital** and hoards the rest, releasing it at a measured rate (community table: level 0 → 15,000,000 max funds / 50,000 per week). A Financial Director raises both. *"Hoarding cash is penalised because the board hoards it too."*
- **Published league prize money** (Div I champion 2,000,000 → Div VI 175,000) and promotion bonuses (Level 2 automatic 500,000 → Level 7+ 175,000). Promoted clubs get a +10% supporter bonus, relegated −10%.

### 5.5 Competitions and world

- **Season = 16 weeks**: 14 league rounds + 1 qualifier week + 1 week with no league activity. Each series = 8 teams, double round-robin.
- **Promotion:** if a division is the same size as the one above, two promote directly and two play qualifiers. Ties on the table broken by **a coin toss**.
- **National Cup** with a draw regenerated weekly from surviving human teams then filled with bots; **guaranteed at least three cup games per season**. Ties after extra time → **penalty shootout**.
- **Hattrick Masters:** all league and cup winners, four weeks, **completely random draw, all matches on neutral grounds**. Cards don't matter (except reds), **Masters games give no training**, gate revenue split 50/50. Prizes 800,000 / 400,000 / 200,000 / 100,000 / 50,000.
- **World Cup:** 96 teams across two seasons, five group rounds then knockouts, plus a Nations Cup for non-qualifiers and U-20 tournaments.
- **Hattrick International:** a country-less league, ~10,920 teams, Supporter-gated.
- **Rankings:** **Power Rating** (global, computed across your last 14 competitive matches, **match outcomes are not a factor**), League Position Ranking, HatStats.

### 5.6 Hattrick: strengths and weaknesses as cited by its own players

**Strengths:** no pay-to-win by policy; genuinely multiplayer and asynchronous (*"if you spend 30 minutes a week to set your match orders and update your training plans, you will be able to compete"*) — **no daily login requirement**; deep interlocking long-term systems; **unusually auditable** (Team Analysis computes the exact rating consequence of any lineup change); exceptional longevity (dev survey: **96% intend to still play in a year**); club identity via logos/kits/stadium.

**Weaknesses:**
1. **The match engine is opaque and too random — the single most-cited flaw.** *"It is practically impossible to win the match for a team much weaker than the opponent, something that is one of the great attractions of real football."*
2. **The transparency paradox.** The developers' own 2021 survey found understanding of the engine **fell from 73% (2009) to 50%**: *"the more explicitly we tell you about how the game works, the less in control you will feel."* The #1 dev priority in 2009 (the match simulation) had **dropped to #3** by 2021.
3. **"The developers are digging their own grave, focusing on things like arena design and mustaches on players instead of their biggest flaw."**
4. **Slow grind and patience tax.** *"Its quite boring in the first 2-3 seasons, just training & competing with bots." "HT is a spreadsheet simulator with a football skin. It is about as lively as Excel, and that is by design."* Also: *"There's really no way to improve on Hattrick. Any significant change will push some players away."*
5. **Transfer market issues:** inflation, **no loan system, no contracts**, TSI being a poor proxy for price, the 50-player cap fighting multi-position squads, the "can't list more times than he played" rule, a 3% house edge on listing fees.
6. **No retirement and no hard age limit** — the all-time hat-trick record holder is a **93-year-old** with 277 hat-tricks.
7. Dated visuals; premium Supporter gates parts of the good experience (ladder, team analysis, live ratings, match replay, CHPP lineup setting).
8. **CHPP is frozen to new applications** — the community tool ecosystem can only age, not grow.

---

## 6. Football Manager (SI / SEGA)

Included because the user framed our game as "FM-like old school." FM is the **least** useful direct comparator — it is a premium single-player product with a 20-year grind and a 30-year content moat — but it defines the feature ceiling for the management layer.

**Current state:** FM24 (Nov 2023) is still the most-played; **FM25 was cancelled**; **FM26 (Nov 2025) is the current title** — first Unity engine, first two-digit title, first full Premier League licence, first women's football, and the **lowest Metacritic in series history**.

### 6.1 The feature ceiling

| Layer | What FM has that nobody else does |
|---|---|
| **Match engine** | Coexisting **2D and 3D** views; Hawk-Eye (Sony) skeletal volumetric data; Motion Matching; **FM26 dual in-possession / out-of-possession formations** with separate role sets; rewritten pass-risk AI; a **Visualiser** splitting the pitch into 3 zones × 9 squares showing how the shape shifts |
| **Player model** | **36 outfield attributes** (14 technical / 14 mental / 8 physical / 13 GK), **CA 1–200** and **PA 1–200**, 6 hidden attributes, 7 personality drivers, Media Handling, **~30 personalities**, 50+ traits |
| **Scouting** | Attributes appear as **ranges** that narrow with scouting; recruitment focuses; **loans used to force an exact read on a youngster** |
| **Staff** | 12+ roles, 25 staff attributes, **9 delegable responsibility areas** where delegation *scales with their attributes* |
| **Training** | Weekly schedules + units + **11 individual focus areas** + **9 tactical-familiarity sub-categories remembered across all tactics**; max 3 tactics at 60%/20%/20%; mentoring |
| **Set pieces** | A **Set Piece Coach** staff role; the creator answers four questions and the coach generates routines for every scenario, with four auto-assigned role categories (Aerial Threat, Box Threat, Recovery Defender, Creators) and a **priority list**; **Set Piece Familiarity** grows with training |
| **Data Hub** | **xG, xA, PPDA, Field Tilt, progressive passes, pressure attempts, sprints, pass maps, momentum, comparison tool, competition-relative reports** — and in FM26 these numbers **drive the advice your assistant gives you** |
| **Economy** | Sponsorship with **levels 1–5 and objectives**; facilities each 1–20 (training, youth, junior coaching, youth recruitment, scouting network, corporate, medical); stadium as a multi-decade progression |
| **Meta** | **Board Confidence 1–20 broken into Club Vision / Matches / Transfers / Tactics / Squad Management**; separate Supporter Confidence driven by a Supporter Profile; Board Requests; Club Dynamics (3 principles from 10); press conferences; Dynamic Manager Timeline; negotiation rounds with agents |

### 6.2 FM's actual strengths and weaknesses

**The defensible moat is not the match engine — it is the closed loop:** `data → roles → training → familiarity → scouting filters → transfer market → board expectations`, wrapped in a 20-year save-portable career. The match engine is the most *visible* differentiator to a casual observer, but FM's real defensibility is the depth of the integrated management simulation.

**Weaknesses, in the community's own words:**
1. **The UI is the #1 complaint of the modern era.** FM26's rebuild lost two decades of quality-of-life work: making a substitution then opening a player profile reverts all subs, menus with no back button, staff attribute spreadsheets removed. *"New UI is legitimately terrible… makes you click MORE to get to even less information."*
2. **Launch quality** — crashes, dead buttons, Touchline Shouts cut and only restored later.
3. **Opaque, undocumented systems** — CA/PA weighting, tactical familiarity, condition, the AI transfer valuation model. Players reverse-engineer them and then complain the game doesn't teach them.
4. **The grind.** Weekly schedule re-entry 30–40× a season ×3 for reserves and U18; press conferences *"dreadfully repetitive"* to the point players delegate them — but delegating can upset a star.
5. **Optimal play is cautious and editor-assisted.** Conservative tactic + heavy save editing is the dominant meta.
6. **RNG in youth intake and development** — the top source of *"why didn't my 17-year-old 165 PA become a world-beater?"*
7. **Reduced analytical tooling in FM26** — the loss of staff-attribute spreadsheets is widely resented.

**Old-school baseline (CM92 → FM2010), for the "old school manager" framing:** CM92 had **no match engine at all** — pure text results; EA reportedly rejected it for "not enough live action." CM3 introduced the first top-down 2D view. **FM2012 introduced FM Classic**, a stripped-back mode deliberately imitating the older transfer-and-tactics entries. The lesson: the old-school loop (transfers + tactics + a weekly decision) is a *valid, popular product shape* — it is not a lesser version of FM, it is a different game.

---

## 7. Side-by-side comparison

### 7.1 Match engine and viewing

| Capability | Sokker | Hattrick | FM | **Ours** |
|---|:--:|:--:|:--:|:--:|
| Spatial / ball-by-ball simulation | ✅ (3D) | ❌ | ✅ (2D+3D) | ✅ **2D+3D** |
| Deterministic, reproducible | ❌ | ✅ | ✅ (partly) | ✅ **seeded RNG, and every calibration is a committed seeded run — neither competitor can reproduce a result** |
| Ratings-free action resolution | ✅ **by design** | ❌ (ratings *are* the sim) | ❌ | ❌ (we expose stats) — **[re-verified] still ❌, and this is now a deliberate trade rather than an oversight: our analytics layer is the thing that makes exposing stats pay off** |
| Sector / contribution model exposed | ❌ | ✅ **fully published** | 🟡 | 🟡 decision scores only |
| Live tactical preview before kickoff | 🟡 (grid editor) | ✅ **sector ratings as you build** | ✅ **Visualiser, 9 zones** | ❌ **[re-verified] still ❌ — `MatchController/{id}/preview` returns `Map.of()`. This is the row where Hattrick is still the best in the browser category and we are last** |
| Offside modelled | ✅ | ❌ | ✅ | ✅ |
| Penalties | ✅ | ✅ (shootouts) | ✅ | ✅ **keeper commits to a read first, and the kick is actually taken — 4,000-penalty validation** **[re-verified ↑ from "keeper commits first", which understated it]** |
| Substitutions | ✅ 3, conditional | 🟡 position swap only | ✅ | ✅ **5/3 windows** |
| Conditional / rule-based subs | ❌ | ❌ | ❌ | 🟡 **engine + API built and unit-tested, but the rules list is constructed empty every match and the plan endpoint feeds nothing** **[re-verified ↓ from ✅ — the contract exists, the product does not]** |
| Real stoppage time | ✅ | 🟡 | ✅ | ✅ **`StoppageClock`, and it pauses the whole pipeline** |
| Set-piece choreography | 🟡 takers only | 🟡 SP number only | ✅ **routines + roles** | 🟡 takers stored, unread |
| Corners/throw-ins as geometry decisions | ✅ **grid edges** | ❌ | 🟡 | 🟡 **and our restarts are the least realistic thing in the engine — throw-ins 77.0 against a real 35–45 is the largest statistical outlier we have** |
| 3D match view | ✅ | ❌ | ✅ | ✅ |
| Injury model | ✅ duels, no medical staff | ✅ **0.4/team/match, medic levels** | ✅ natural fitness | ✅ **8 types, fatigue-driven, fatigue-weighted selection** **[re-verified]** — richer than Hattrick's, weaker on recovery (no medical staff) |
| Analytics from the same sim (xG, PPDA, tilt) | ❌ | 🟡 (ratings) | ✅ **Data Hub** | ❌ **biggest analytics gap** — **[re-verified]** still ❌. xG exists but is **pre-match only** (`ScheduleInsightService`), not computed from tick data. PPDA, field tilt, progressive passes, xA: all still absent |

### 7.2 Tactics — **[re-verified 2026-10-01: no change]**

| Capability | Sokker | Hattrick | FM | **Ours** |
|---|:--:|:--:|:--:|:--:|
| Ball-position grid editor | ✅ **5×7 = 35 cells × 10 players** | ❌ | 🟡 Visualiser (read-only) | 🟡 **built, disconnected** |
| Per-slot per-ball-state targets persisted | ✅ 350/tactic | ❌ | ❌ | 🟡 **506 in data, 0 in engine** |
| In-possession vs out-of-possession shape | ✅ | 🟡 orders only | ✅ **dual formations (FM26)** | ⬜ **one shape by owner decision** — **[re-verified]** ⚠️ a new `DefensiveShape` derivation now overrides the identical-pair data on every lookup, so the engine attacks and defends in *different* shapes with no data edit. **Needs an owner ruling, not a code change** |
| Role instructions | ✅ **GK/DEF/MID/ATT** | ✅ 4 orders/slot | ✅ 5-star roles, ~30 | 🟡 5 buckets |
| Lineup legality caps | ✅ **1/5/5/3, lose training if broken** | ✅ min 9 starters | ✅ squad registration | ✅ **25 senior / 8 youth, enforced in `canRegister`** **[re-verified ↑ from ❌]** |
| Formation actually applied by engine | ✅ **not bound to labels** | ✅ | ✅ | ❌ **hardcoded 4-4-2** — **[re-verified]** `TacticsRules:37` and `RealSquadFactory:252` both still hardcode it |
| Formation variety | ✅ **free geometry** | ✅ 5 lines × 4 orders | ✅ ~20 formations | 🟡 **9 in catalog, 1 applied** (was "10 in UI, 3 in code") |
| Mentality / tempo / pressing dials | ❌ **deliberately none** | ✅ 7 tactics | ✅ ~30 instructions | 🟡 fields exist, no UI, no consumer — **[re-verified]** zero readers in `src/main` |
| Man marking | ❌ | ✅ | 🟡 | ❌ |
| Pitch dimensions affect play | ✅ **64–78 m × 95–110 m** | ❌ | ❌ | ❌ |

### 7.3 Player, training, youth

| Capability | Sokker | Hattrick | FM | **Ours** |
|---|:--:|:--:|:--:|:--:|
| Skill count | 8 | 7 | 36 attributes | 8 |
| Skill ceiling | 17.99 named tiers | 30 × 4 sublevels | 1–20 + CA/PA 1–200 | 20.99 |
| Hidden talent / potential ceiling | ✅ Talent (multiplier) | ❌ | ✅ PA (fixed or banded) | 🟡 **talent now hidden behind a narrowing range for juniors; no separate potential-vs-ability concept** **[re-verified ↑ from "shown openly"]** |
| Scouting uncertainty | ✅ **±2–3 level estimate** | 🟡 | ✅ **attribute ranges** | ✅ **juniors: band narrows to ±1 at promotion** — **[re-verified ↑ from ❌]**. Squad players still exact |
| Scouting network / recruitment | ❌ | ✅ chief scout + network | ✅ full network | ✅ **`ScoutingService` + `ScoutAssignment`, driven by `Country.youthRating`** **[re-verified ↑ from ❌ — the dead hook is now the mechanism]** |
| Training intensity vs injury risk | ❌ | ✅ **0–5, +2.5%/level risk** | ✅ Normal/Half/Double | ✅ **3 tiers, 0 / 0.4% / 4.5%** **[re-verified ↑ from ❌]** |
| Individual training focus | 🟡 by position only | 🟡 by training type | ✅ **11 focus areas** | ❌ **— S4.1 deleted by owner, not a gap we closed** **[re-verified]** |
| Minute-proportional training | ✅ **90-min cap, last-match role** | ✅ **90-min cap, worked examples** | 🟡 | ✅ **minutes-weighted, continuous ratio** **[re-verified ↑ from ❌]**. Not a 90-min cap |
| Facilities affect growth | ❌ | 🟡 staff only | ✅ 1–20 each | 🟡 upkeep is a real ledger line, no build UI |
| Coaching staff affects growth | ✅ **8 per-skill ratings** | ✅ skill 1–5 + leadership | ✅ 10 coaching attributes | ✅ **`StaffMember` entity, per-skill coaching, and the head coach's `matchFactor` reaches the engine** **[re-verified ↑ from ❌ "fake, zero effect"]** |
| Ageing decline | ✅ **from 30** | ✅ **from ~30** | ✅ gradual | ✅ **from 29, hard cliff** |
| Retirement | ❌ | ❌ **93-year-old record holder** | ✅ | ❌ **players age forever** — **[re-verified] still the cheapest 1-day item on the list (feature #27)** |
| Junior/school intake | ✅ **30 places, 1000$/place/wk, 0–6/wk** | ✅ **two systems, 3 scouts** | ✅ academy quality-gated | 🟡 **10 cap, 1 intake/season** |
| Junior skill visibility | ✅ **hidden until promotion** | 🟡 | ✅ ranges | ✅ **talent hidden, current ability still raw** **[re-verified ↑ from ❌]** |
| Formation experience / confusion | ❌ | ✅ | 🟡 familiarity | ❌ |
| Team spirit | ❌ | ✅ | 🟡 | 🟡 **`Team.cohesion` — ±3% on pitch, +4% in training** **[re-verified ↑ from ❌]** |
| Morale / loyalty / dressing room | ❌ **deliberate** | 🟡 loyalty only | ✅ full | 🟡 **morale live and feeds growth; `form` now written per match; loyalty as a distinct axis still thin** **[re-verified ↑ from ❌ "`form` is a constant"]** |
| Injuries | ✅ duels, no medical staff | ✅ **0.4/team/match, medic levels** | ✅ natural fitness | ✅ **fatigue-driven, 8 types, selection weighted by fatigue** — **[re-verified] confirmed; the previous expert audit's "quarantined, no generator" was the stale claim** |
| Fatigue from activity not clock | ✅ | 🟡 | 🟡 | 🟡 ticks |
| Specialities + special events | ❌ | ✅ **7 types** | ✅ 50+ traits | ❌ |
| Experience / nerves | ✅ tactical discipline | ✅ **nerves in cups** | 🟡 | ❌ |

### 7.4 Transfers and contracts

| Capability | Sokker | Hattrick | FM | **Ours** |
|---|:--:|:--:|:--:|:--:|
| Auction with bidding | ✅ **2 days, proxy/max** | ✅ **72h, +2% min, anti-snipe** | 🟡 bidding | 🟡 **real `TransferOffer` rows and a weekly auction, but no proxy/max bids and no anti-snipe extension** **[re-verified ↑ from ❌]** |
| Listing fee | ✅ **2.5%** | ✅ 1,000 $ flat | ❌ | ❌ |
| Sale tax | ✅ **8% + profit-based time component** | ✅ agent 12%→2% table | 🟡 agent fees | 🟡 **agent fee 2–5% only; no sale tax, no profit-based component** **[re-verified ↑ from ❌]** |
| Anti-daytrade mechanism | ✅ **player can refuse to be listed** | ✅ mother club 2% | ❌ | 🟡 **player `objection` field exists in negotiation; no listing-refusal mechanic** **[re-verified ↑ from ❌]** |
| Transfer windows | ❌ **deliberately none** | ❌ **always open** | ✅ | ✅ **`TransferWindowService`, with free-agent / released / loan-recall exceptions** **[re-verified ↑ from ❌ "open 52 weeks"]** |
| Contracts with length/expiry | ❌ | ❌ | ✅ | ✅ **`PlayerContract` with length, wage, expiry → free agency** **[re-verified ↑ from ❌]** |
| Free agents | ❌ | ❌ | ✅ | 🟡 **signing a free agent works and bypasses the window, but the old listing entry point still throws `PLAYER_UNASSIGNED`** **[re-verified ↑ from ❌ "impossible by construction"]** |
| Loans | ❌ | ❌ | ✅ | 🟡 **`LoanService` fully built and wired to expiry, but has no REST endpoint — unreachable** **[re-verified ↑ from ❌]** |
| Work permits / foreign limits | ❌ | ❌ | ✅ | ❌ **built in full, then removed by the owner** (`a6394f9`, *"No foreigner limit for now"*) |
| Negotiation rounds | ❌ | ❌ | ✅ **3-way with agents** | ✅ **5 rounds, separate on fee / wage / contract length** **[re-verified ↑ from ❌ "one HTTP call + one dice roll"]** |
| **Seller chooses which offer to accept** | ✅ n/a (auction) | ✅ | ✅ | ❌ **still forced to take the highest** — the service method exists and is unreachable from HTTP |
| Transfer history | ✅ | ✅ | ✅ | ✅ **every offer round recorded, rejected offers kept, fee structure stored** **[re-verified ↑ from ❌]** |
| AI↔AI market | ✅ bots | ✅ bots | ✅ | ✅ **[re-verified ↑ from ❌ "by design"]** — built, weighted by club need. See §3.3 for why this does not contradict the PO decision |
| Market liquidity constraint | ✅ real | ✅ real | n/a | 🟡 **= human player count** |

### 7.5 Economy and club

| Capability | Sokker | Hattrick | FM | **Ours** |
|---|:--:|:--:|:--:|:--:|
| Weekly ledger | ✅ | ✅ | ✅ | ✅ **`FinanceLedgerEntry`, 9 categories, per club per week, idempotent** **[re-verified ↑ from ❌]** |
| Wage bill from player earnings | ✅ **published formula** | ✅ **published formula** | ✅ | ✅ **`earnings` now has 13 call sites and drives the wage bill** **[re-verified ↑ from ❌ "read by nothing"]** |
| Gate revenue | ✅ **all to home** | ✅ **stand-type split 67/33** | ✅ | ✅ **`AttendanceService` with ticket price, form, opposition, weather; booked weekly** **[re-verified ↑ from 🟡 "nothing calls it"]** |
| Sponsorship | ✅ weekly, mood-scaled | ✅ **two components + season incentive** | ✅ **levels + objectives** | ✅ **`Sponsor` entity with live per-season contracts** **[re-verified ↑ from ❌ "3 literal JS objects"]** |
| Prize money | ✅ **published table** | ✅ **published table** | ✅ | 🟡 **table implemented, `awardPrizeMoney` currently has no caller** **[re-verified ↑ from ❌]** |
| Debt / interest / bankruptcy | ✅ **4.5%, −250k/−500k** | ✅ **3.33%, 500k line** | ✅ soft | ❌ **still absent** — no debt entity, no interest accrual, no bankruptcy path |
| Board cash ceiling | ❌ | ✅ **board hoards, releases weekly** | ✅ budget requests | ❌ **still absent as a ceiling.** Board *trust* exists (0–100 from FFP + standing) but there is no hoarding/release mechanic |
| Stadium as an economy | ✅ **8 sectors, 4 stand types, demand curve** | ✅ **4 stand types, weather-sensitive** | ✅ capacity + pricing | ✅ **`StadiumBuildService`: seats, roofs, painting, expansion quotes, projected gate** **[re-verified ↑ from 🟡 "entity, empty service"]** |
| Pitch quality / tiers | ✅ **6 tiers, affects bounces + injuries** | ❌ | ❌ | 🟡 **upkeep is a real weekly ledger line; no tiers, and effect on bounces/injuries unverified** **[re-verified ↑ from "field, no effect"]** |
| Sponsorship/staff as entities | 🟡 staff only | ✅ | ✅ | ✅ **`StaffMember` + `StaffRole` + `StaffDirectoryController`; head coach reaches the engine** **[re-verified ↑ from ❌ "no `StaffMember` entity exists"]** |
| FFP shown to the player | ❌ hides it | ❌ hoards it | 🟡 | ✅ **banded 0.90 / 1.15 / 1.35 and displayed — better than all three** |

### 7.6 Meta, world, social

| Capability | Sokker | Hattrick | FM | **Ours** |
|---|:--:|:--:|:--:|:--:|
| **Board expectations + trust** | ❌ **multi-year community request** | 🟡 | ✅ **1–20, 5 facets** | 🟡 **trust 0–100 computed from FFP + standing + squad, shown to the player — but read-only, nothing acts on it** **[re-verified ↑ from ❌]** |
| **Manager can be sacked** | ❌ | 🟡 | ✅ | ❌ **`sackingReview` is a boolean surfaced on the finance page. No entity, no persistence, no end-of-season review** |
| Supporter mood / expectations | ✅ **7 + 9 levels** | ✅ mood | ✅ profile | ❌ **still not built — and now the cheapest available win in the meta layer** |
| Retirement | ❌ | ❌ | ✅ | ❌ |
| League pyramid depth | ✅ **×3 per level, 12-team leagues** | ✅ 8-team series | ✅ 58 leagues + 14 women's | 🟡 **[re-verified ↑ from "31 leagues, Serbia only"] 48 countries × 31 divisions × 10 clubs ≈ 14,880 clubs, promotion ladder for every country** |
| Promotion/relegation + playoffs | ✅ **2nd vs 7th–9th** | ✅ qualifiers | ✅ | ✅ **2-leg playoff** — ⚠️ **[re-verified] the player-facing playoff summary hardcodes table positions 9–10 while the code that moves clubs computes them dynamically, so the two can disagree** |
| Knockout cup | ✅ national + 128-team continental | ✅ national + Masters | ✅ | 🟡 **[re-verified ↑ from ❌ "created, never populated"] a 256-team national cup now draws and plays, and 3 international cup tiers are populated. But the draw pairs by sorted index, not randomly, and the cup is hardwired to the lowest-id club's country** |
| National teams | ✅ **user-elected coaches, skill hiding** | ✅ | ✅ | 🟡 **[re-verified ↑ from ❌ "placeholder pages"] 96 sides, elections, selector appointment, Elo from results. ~1 playable fixture, and the seeder gives up permanently when it cannot fill a field** |
| World ranking | ✅ points-based, drives cup entry | ✅ Power Rating | n/a | 🟡 **[re-verified ↑ from ❌] country Elo replayed from real results. But every league match is rated as a cup match, and continental entry for 47 of 48 countries is alphabetical** |
| Manager-to-manager messaging | ✅ SK-mail | ✅ MyHT | 🟡 | 🟡 chat + PM — **still no WebSocket client despite a STOMP config existing on the backend** |
| User-run leagues | ✅ friendly leagues | ✅ federations | ✅ online career | ❌ |
| Zero-consequence exhibition mode | ✅ **Arcade** | ✅ arena | 🟡 | ❌ |
| Third-party API / tooling | ✅ **officially blessed API + XML feed** | ✅ CHPP (frozen) | ✅ editor + FMSE | ❌ |
| Time commitment | ✅ **~30 min/week, no daily login** | ✅ **~30 min/week** | ❌ **daily, 30–40 inputs/season** | 🟄 manual click |

---

## 8. Where we are better

Stated plainly, because it is a short list and it is the whole strategic case. **[re-verified
2026-10-01]** Items 1–9 are the original list with corrections; 10–12 are new.

1. **The match engine is better than Sokker's and Hattrick's, and it is not close.** Sokker's own documentation says ratings play no role and results are decided action-by-action by 8 skills plus randomness. Hattrick's community says you can lose to a team with zero chances. We have real ball physics, a defensible goalkeeper, offside, VAR, discipline, a full penalty model, 8 injury types, real stoppage time, and a deterministic seeded RNG — **and we can prove it, because a batch run is reproducible.** Neither competitor can say that. **[re-verified] Stronger than when written: the penalty and injury models were the two rows where we were weakest and both are now real.**
2. **Determinism + calibration discipline is a development moat nobody else has.** Hattrick's engine is deliberately unexplainable ("the more explicitly we tell you, the less in control you will feel"). We have measured 100-match batches, recorded every calibration change with its before/after numbers, and deferred targets explicitly. That is why our engine is trustworthy. **[re-verified] One caveat worth stating: the discipline is a *capability*, not a guarantee — the engine has not been recalibrated since 2026-09-26 because calibration was correctly deprioritised, so the moat is currently idle. It is still the only such capability in the category, and §9.0 is what it should next be spent on.**
3. **The viewer is a real product.** LED scoreboard, 2D + 3D, click-to-inspect player cards, event timeline, 0.25×–10×, keyboard, mobile live ticker, VAR/half-time/FT overlays. Sokker's viewer is *good*; ours is arguably better engineered (O(1) snapshot index, batched DOM flush, 200-entry cap). Hattrick has no pitch view at all.
4. **Training reports are better than Sokker's.** Per-player, per-skill `before / after / decimalΔ / integerΔ` plus a full season×week matrix. Sokker shows a *Games* column and a projected *Eff.* column and hides the numbers; we show the actual decimals.
5. **The junior promotion reveal is the best single UX moment in the game**, and it is better than Sokker's, which shows nothing but an estimated level and a countdown. **[re-verified] Still true, and the academy around it is now deeper: talent is hidden behind a narrowing scouting range, so the reveal is no longer the only uncertainty mechanic we have.**
6. **The pass-failure taxonomy is football, not model artefact:** DEFLECT 22.2% / LOOSE_PICKUP 14.7% / INTERCEPT 14.7% / DUEL 9.1% / OFFSIDE 3.4% / OOB 2.0% / FOUL 0.6%. A strong signal the ball model is honest. **[re-verified] Unchanged, and still the cheapest credibility signal we own — it is a single diagnostic run.**
7. **Low time commitment.** Both browser competitors are explicitly built around ~30 minutes a week. **[re-verified] Still true, but the mechanism changed and the claim needs restating: we now have a *scheduled* clock (top of every hour) rather than a manual button, and the "we keep the manual advance" condition no longer holds.** The commitment is still ~30 min/week, and scheduling it is strictly better than making the player click — but see §11.4, because the scheduled advance is currently advanceable by any authenticated user, which is a different kind of problem from the one this row used to warn about.
8. **We have already thrown away 40,000 LOC of dead engine, and we can still make structural changes.** Sokker and Hattrick both carry decades of accumulated cruft (Hattrick's community notes *"there's really no way to improve on Hattrick — any significant change will push some players away"*). **[re-verified] This is now a bigger advantage than it was, and it is double-edged: the 220-commit sprint proved we *can* move fast, and it also produced ~14,880 clubs of world the scheduler was not built for (§9.0). The ability to restructure is real; the discipline to check scale is the thing that is missing.**
9. **Multi-sport is a genuine differentiator neither browser competitor has.** Basketball and American Football already exist as playable modes.
10. **[NEW 2026-10-01] A 48-country world with a live promotion ladder.** 96 national sides, elected selectors, three populated international cup tiers and a cup that actually plays. Neither Sokker (5,000 leagues) nor Hattrick (~10,920-team country-less league) has a *playable* international structure at anything like this depth of simulation, and neither lets you watch it tick by tick.
11. **[NEW 2026-10-01] The economy loop is closed and the FFP band is visible.** This was the item §2 named as the whole strategic case. It is built, it runs for every club, and we show the player their financial-health band where both competitors hide or hoard it. That is a better default, not a catch-up.
12. **[NEW 2026-10-01] 24.4% test coverage with regression tests pinned to fixes.** `TransferServicePriceGuardTest`, `PenaltyEngineTest` (4,000 seeded penalties), `TrainingProgressionIdempotencyTest`, `SubstitutionServiceTest`, `WeeklyFinanceServiceTest` — each written when its fix landed. Neither competitor can show you a test that proves their economy is not a dice roll, because theirs is one.

---

## 9. Where we are worse — prioritized

Ordered by *leverage per day of work*, not by how fun the feature is. This deliberately differs from the backlog ordering in one place, argued in §9.1.

### 9.0 🔴 P0 — **[NEW 2026-10-01]** The world is 48× bigger and the code that schedules it is not

**This section did not exist on 2026-09-26 and it now outranks everything below.** The world grew
from 310 clubs in one country to **48 countries × 31 divisions × 10 clubs ≈ 14,880 clubs** in five
days. The day-job framework, the game clock, the country rating and the cup draw were all written
for 310 clubs in one country. Four defects are reachable by a normal player and all corrupt the
season permanently:

1. **The `job_run` idempotency guard does not prevent double-apply.** `JobRunner.java:139-147` runs
   the job body, commits it, and *then* writes the guard row in a separate transaction — and reads
   the guard with no lock. Two concurrent clock advances both see no row, both execute the job, and
   the unique constraint only saves the duplicate *row*, not the duplicate **work**. The class
   javadoc claims the opposite.
2. **`FAILED` is terminal.** Four files promise an operator re-queue; `JobRunRepository` has no
   reset method. One transient throw permanently disables that job for that (season, week, day) —
   and `MatchdayJob` failing means **a whole matchday is never played and never retried**.
3. **Any authenticated user can advance the entire world.** `POST /api/game-clock/advance`,
   `/advance-to-hour` and `/api/jobs/run-due` require a JWT and nothing else. The admin restriction
   exists only in the browser, and the codebase already has the right maxim written down
   (`CountryController.java:210`: *"a hidden button is not a permission"*).
4. **The season counter is incremented in two places**, so **every rollover skips a season number**
   (12 → 14 → 16). Three-line fix, permanent corruption otherwise.

Plus, specific to the new world: the **cup draw has no randomness** (it pairs by sorted index, so
the strongest club is drawn against the weakest, forever, while three javadocs describe a shuffle);
the cup is **hardwired to whichever country owns the lowest-id club** and then gives up permanently;
**every league match is rated as a cup match**; and a `Double == Double` reference comparison means
**all ~14,880 clubs are rewritten on every matchday**.

Full detail with file:line evidence: `experAudit01102026.md` §4 and §7.

### 9.1 🔴 P0 — The tactical editor does not reach the engine (owner clarification applied)

**This is the finding that matters most for the product, and the owner's clarification reduces it
from a build to a wiring job.** The technical claim is unchanged and was re-verified in full on
2026-10-01: `TeamTacticsProfile` is read by no engine file, all 11 `new MatchOrchestrator(...)`
call sites use the no-arg constructor, `new TacticsRules()` still opens raw JDBC to a hardcoded
`jdbc:postgresql://localhost:5432/sokker_db` and reads team id 1, `FORMATION` is still
`"4-4-2"`, and `tactics_fallback.json` still has 1,012 rules with **506 identical / 0 different**
between the two possession contexts.

**But the editor is not inert in the engine.** The owner confirms the engine is already running on
an **identical copy of the editor's data** — deliberate, as a testing arrangement, to be connected
when the testing finishes. That answers the hard question the previous version of this section was
asking: **the data path is proven.** The engine reads 1,012 authored rules and positions players
from them every tick.

**So the remaining work is wiring and sanitisation, not construction, and the estimate drops from
5–8 days to 2–3:** read `TeamTacticsProfile` per team in `SimMatchService`; pass per-side
`TacticsRules` into `MatchOrchestrator`; delete the raw-JDBC path and the `FORMATION` constant;
make `FormationSlotCatalog` serve the chosen formation (it has **9 layouts**; the engine applies
**1**).

Three things the wiring must handle, all found on 2026-10-01 and none of them in the version above:

- **The bridge already exists and is unwired.** `TacticsBridge.fromRuntimeMap()` converts the
  runtime rule map into a `TacticRules` model and has **zero callers**;
  `NewLogicTacticsService.loadTacticRules(teamId, formation)` discards **both of its own arguments**
  and has zero callers. This is precisely the bridge this section asked for, written and left
  unwired — the same failure mode, now duplicated. Wire that one; do not write a third.
- **`DefensiveShape` may have silently superseded the 2026-09-26 possession-context ruling.** A new
  `tactics/DefensiveShape.java` derives the out-of-possession shape arithmetically, and
  `TacticsRules.desiredCellFromContext()` only falls back to an authored `OPPONENT_HAS_BALL` rule
  when it *differs* from its `WE_HAVE_BALL` twin — so, with all 506 pairs identical, **the derived
  block wins on every lookup.** The engine now attacks and defends in different shapes with no data
  edit. Defensible engineering, and `DefensiveShapeTest` exists — but it is a **third position** that
  neither the document nor the ruling describes. **This needs an owner ruling, not a code change.**
- **Three test fixtures still pass calendar years** where the game uses a season *number*
  (`WeeklyFinanceServiceTest`, `StaffSponsorServiceTest`, `PlayerContractServiceTest`). They are
  self-consistent under either reading, so they will **not** catch a regression when the tactical
  rules are re-keyed onto the season number. Fix them before the wiring, not after.

**Why it is still P0 after everything else that got built:**

1. **Sokker's entire product identity is this feature.** Their retention reason, per their own
   players, is the 35-cell free-form editor. Ours is *better on paper* (46 states vs 35, and a real
   physics engine reading it) and the user cannot influence the match.
2. **We already own it.** Data model, persistence, editor UI, fallback dataset, and — per the
   clarification — a *proven engine read path*. 2–3 days, not a greenfield build.
3. **It is a lie in the product.** A manager drags a shape, saves it, and nothing happens.
4. **It is the only P0 on this list that produces *football* decisions.** Everything else produces
   spreadsheet decisions. Our engine is the thing we are actually good at.
5. **Everything built in the last five days surrounds a match the user cannot steer.** Sprint 2's
   economy now works; it wraps a match simulation the manager has no input to.

**The tactical divergence to preserve:** Sokker and Hattrick both give you *no* mentality/tempo/pressing
sliders — Sokker because geometry is the whole game, Hattrick because it has 7 named tactics with
published levels. FM gives you ~30 instructions. We should ship **both**: wire the grid (Sokker's
soul) *and* give the 6 existing `Tactics` fields (`aggression/defenseLine/pressing/possession/counterAttack/ballControl`,
still with **zero readers in `src/main`** and no UI) real consumers. A manager who wants geometry
gets geometry; a manager who wants sliders gets sliders.

**Also still missing and cheap:** a **pre-match tactical preview** (Hattrick's sector ratings as you
build, or FM's Visualiser). `MatchController/{id}/preview:166-167` currently returns `Map.of()` — an
empty stub. **The backlog schedules "TACTICAL EDITOR REDESIGN" last; the wiring should not.**

### 9.2 🟢 DONE — Economy — **[re-verified 2026-10-01]**

**This P0 is closed and should be struck from the list.** `Team.budget` is no longer one `Double`
changed by a transfer fee. There is a `FinanceLedgerEntry` ledger with nine categories — gate,
broadcast, merchandising, prize, player wages, staff wages, sponsorship, facility upkeep, junior
upkeep — settled for **every club every week** with a per-(team, season, week) idempotency check.
`Player.earnings` went from *"read by nothing"* to **13 call sites**, including the wage bill.
Gate revenue, sponsorship, prize money, stadium build and pitch upkeep are all real. The Finances
page calls four real endpoints and renders an explicit *"not available"* panel on failure instead
of inventing numbers in the browser.

Two design calls worth keeping: the **FFP band is shown to the player** (0.90 / 1.15 / 1.35), which
neither Sokker nor Hattrick does, and `EconomyProfileService` makes the seed **deterministic** via an
id-derived offset, so a re-seed does not reshuffle a country.

**What remains:** debt / interest / bankruptcy, and any board cash ceiling. The irony this section
originally named still stands and is now more pointed — **we are starting to make Sokker's and
Hattrick's complaints rather than avoid them.** Sokker's users complain prize money is trivially
small against wage bills; Hattrick's biggest administrative complaint is that the board hoards your
money. We have the ledger those complaints are about, and we can fix both on day one by setting
prize money and a board release rate deliberately.

### 9.3 🟠 P0 → P1 — Transfers — **[re-verified 2026-10-01: largely rebuilt, 4 mechanics still missing]**

**The data model this section called the root problem is fixed.** `Transfer.interestedTeams` is no
longer a `Set<String>` of English prose; it is `TransferOffer` rows with a real buyer foreign key,
fee, wage, contract length, round, status, agent fee, expiry and a player-objection field. The
`"Partizan offered €450000"` parse is gone, and with it the prefix-dedupe bug, the
club-rename-orphans-offers bug, and the `isOfferEntry` string test.

`NegotiationService` runs **5 rounds** with separate negotiation on fee, wage and contract length.
`TransferWindowService` enforces windows with sensible exceptions. `PlayerContract` brings
expiry-into-free-agency. Squad registration is capped. AI↔AI now happens, weighted by club need.
Transfer history exists.

**What is still missing, and it is exactly the Sokker list this section recommended:**

1. **The seller still cannot choose which offer to accept.** `NegotiationService.acceptOffer(transferId, offerId)`
   exists and correctly rejects the rest — but no controller exposes an `offerId`. The service can
   do it; **the product cannot.** This is the cheapest fix in the whole transfer area and it is
   listed in §7.4 as a row where we score ❌ against all three competitors.
2. **No listing fee** (2 lines).
3. **No sale tax**, and specifically **no profit-based time-at-club component** — the anti-daytrade
   mechanism this section recommended and the cheapest one in the genre.
4. **No player-refuses-to-be-listed** mechanic, even though the `objection` field now exists to
   support it in negotiation.
5. **Loans have no REST endpoint.** 248 lines of `LoanService`, fully built, wired to expiry,
   **unreachable**. Same for `ConditionalSubstitutionRules` and `SubstitutionPlanController`.

**The two recommendations that still stand and were not adopted:** the listing fee as a % of
asking price, and the profit-based sale tax. Both are 2–5 lines each and both are the reason
Sokker's market is a *conversation* rather than a clearance sale.

**Blocker, still open and now larger:** an auction needs a trustworthy deadline. There is now a
day-precise game clock, so this is *closer* to solved than it was — but see §9.0, because the clock
advances for every authenticated user and the season counter is corrupted.

### 9.4 🟠 P1 — Training — **[re-verified 2026-10-01: the trade-off gap is closed]**

**"Training is strictly dominant and free" is no longer true, and that was the whole finding.**
`TrainingIntensity` gives three tiers (`LIGHT 0.75 / NORMAL 1.00 / VERY_HARD 1.35`) that trade a
growth multiplier against added fatigue and an injury roll of **0 / 0.4% / 4.5%** for 7–28 days
out. Training is minute-proportional via `TrainingPercent.percentFor`. Coaching staff is a real
`StaffMember` entity with per-skill coaching, and **the head coach's `matchFactor` reaches the
engine** as a per-match multiplier. `SquadTrainingService.trainEveryClub` trains every club, not
just the user's.

Against Hattrick: intensity with a published injury risk and per-type trainee caps — we have the
intensity and the risk, not the per-type caps. Against FM: 11 focus areas, Normal/Half/Double — we
have three tiers and **no individual focus, because S4.1 was deleted by the owner** (a deliberate
call, not an oversight, and the gap is real).

Three caveats:

- **The season is 12 weeks, not 19.** Every figure in the previous version of this section that
  referenced 19 training runs is stale.
- **Minute-proportional is not a 90-minute weekly cap.** It is a continuous ratio against a
  threshold. The Sokker rule this section recommended — *"only the last match's role counts"* plus a
  hard 90-minute cap — produces a sharper weekly optimisation problem and is still not implemented.
  **It remains the cheapest remaining training feature (feature #2 in the intake list).**
- **AI clubs train but never set priorities and never decline.** The default is the role primary
  skill, and aging decay lives inside the training service, so AI squads never decay either. **The
  pyramid is still static** — a promoted club's players never improve, so promotion has no long-term
  meaning. This is the §11.3 concern, still open.

**Also: a second, contradictory training engine is live.** `PlayerSkillProgressionService` (71
lines, hard cap **17**, and an **inverted** talent factor) is reachable through
`TrainingController:40, 48, 130`. It contradicts the real engine and should be deleted before it is
discovered by a user.

### 9.5 🟠 P1 — Meta layer — **[re-verified 2026-10-01: trust exists, stakes do not]**

Board trust is now a real model: FFP bands, a 0–100 score derived from financial health, league
standing, unhappy-player count and squad size, and five human-readable states. `MoraleService` is
live and wired to both training growth and the match, and **`Player.form` is now written per match**
— so the `formBoost` in `PlayerDTO:90` (±8.1 OVR) and `(form − 6.0) × 1.2` in
`MatchRatingCalculator:51` finally respond to a run instead of being constant offsets.

**But there are still no stakes, and the audit trail explains why:** `sackingReview` is a
**read-only boolean** with no entity, no persistence, no end-of-season review and no
replacement-manager flow. The class comment says *"Read-only by design"* — an honest scope note,
but it means **"Manager can be sacked" is still ❌**, and it is still the row where we score ❌
against FM.

Recommendation from the original section, unchanged and now more valuable: **supporter mood (7
levels) + supporter expectations (9 levels) first, board trust's consequences second.** Supporter
expectations are ~30 lines of enum plus one mood modifier and deliver most of the *"my team is
being humiliated"* feeling for a fraction of the cost. Given that board trust already computes but
cannot act, **supporter expectations are now the cheapest available win in the entire meta layer**,
and they are the layer Sokker's veterans defend and its community has asked for in parallel for
years.

**Still no retirement.** `PositionGrowthProfile` has age ceilings that stop improvement but never
decline a player, so the pool only grows. Feature #27, 1 day.

### 9.6 🟢 DONE — Registration — **[re-verified 2026-10-01]**

Closed end-to-end. `POST /auth/register` exists with correct 400/409 status distinction,
`RegistrationRequest` is a real entity, the admin approve/reject endpoint exists, `RegistrationService`
is called from both, and both frontend ends are wired. The queue endpoint has no frontend caller yet,
but the chat-driven flow works.

**The multiplayer gate is open.** A game whose differentiator is a human transfer market can now
admit a second human. This 1-day fix gated the entire multiplayer proposition and it is done.

### 9.7 🟡 P2 — The world — **[re-verified 2026-10-01: much bigger, newly fragile]**

**The specific complaints in this section are largely resolved:** the pyramid is no longer
Serbia-only (48 countries, ~14,880 clubs, promotion ladder for every country), `Kup Srbije` is
drawn and plays, and the national-team pages are not placeholders any more.

**What replaced them is worse for a while.** A 256-team cup that **draws by sorted index rather
than randomly** (so the strongest club is drawn against the weakest in every round, while three
javadocs describe a shuffle), hardwired to whichever country owns the lowest-id club and then
permanently empty. Continental entry for 47 of 48 countries decided **alphabetically**, because
per-division reputation is a function of tier alone. ~1 playable international fixture, with a
seeder that gives up permanently and then reports a successful matchday. Still no retirement.

Sokker's **×3 leagues per level** pyramid is still a better shape for a large world than our 5 tiers
of 10/16/32/64/128. But that is genuinely lower priority than §9.0 — a correct season is worth more
than a deep one.

### 9.8 🟠 P2 → **P1** — No analytics layer — **[re-verified 2026-10-01: re-prioritised UP, still the biggest single missed opportunity]**

**Re-prioritised, deliberately.** This was a P2 when we had no economy, because it was a
differentiator for a product that did not yet exist. **It is a P1 now, and the reason is §2: the
engine-versus-economy asymmetry is gone, so honest analytics is now the only capability where we are
uniquely able to *beat* both competitors rather than catch them.** Everything else on the list
brings us level; this one could put us ahead.

Unchanged and re-confirmed by grep: **PPDA, field tilt, progressive passes, xA and momentum do not
exist.** The xG that is surfaced comes from a **pre-match** model (`ScheduleInsightService`), not
from the tick data — which was the specific point of the original section, and it still holds. The
commit "Watch: the click populates the stats" added a **read** path over already-persisted
`TeamStats`, not an analytics layer.

FM's Data Hub is the reason FM feels like a modern football product, and in FM26 those numbers drive
the assistant's advice. **Nobody in the browser-manager category has this**, because both
competitors cannot compute it honestly: Hattrick has 7 sector ratings, Sokker has deliberately
nothing. Our engine is a real spatial simulation, so xG from shot location and defensive pressure at
the moment of the strike, PPDA, field tilt and progressive passes are all computable **from the same
tick data that drives the animation** — the numbers and the pictures cannot disagree.

**And our determinism is what makes it verifiable rather than merely different.** A batch run is
reproducible, so an xG figure can be recomputed and checked. Neither competitor can offer that,
because neither has a reproducible simulation to recompute it from.

**Pair it with the pre-match preview (§11.1 layer a).** The two together are the whole of §11.1's
recommendation, they are the only rows where we are last in the browser category, and the pre-match
preview is also what makes the tactical wiring (§9.1) visible to the player on day one.

### 9.9 🟡 P2 — Dead routes and broken display — **[re-verified 2026-10-01: 4 of 13 fixed]**

`results`, `training`, `topScorers` and `topAssists` are now wired to menu entries and check
`response.ok`. Three more work but are unreachable. **Six still point at `/demo/…` and still
`await response.json()` without checking `response.ok`**, so they crash into a generic "API Error"
card: `cup`, `international`, `friendlies`, `upcoming`, `playerStats`, `teamStats`.

Also unchanged: the permanently broken average-rating display on both player pages
(`fetchPlayerRatingSummary` called with 1 argument at 2 sites), and `loadPage` firing twice per
sidebar click — that one **is** fixed, `app.js` is deleted.

**`DummyDataController` is now the root of this whole area and is the thing to delete.** 281 lines,
18 routes, 100% hardcoded, 0 DB access, hardcodes team id 1, **untouched since 2026-07-30** — and it
is what the 6 dead routes call. Two of its call sites interpolate a *variable* team id into a route
that only maps `…/1/…`, so every club except id 1 silently gets an empty object.

---

## 10. What to add — intake list

Drawn from the three competitors, deduplicated, with our judgement on each. Effort is my estimate in
dev-days for this codebase. **[re-verified 2026-10-01: "done" marks are current; "open" means it was
re-checked in source and is genuinely still missing.]**

| | Feature | Why | Est | Status 10-01 |
|---|---|---|---:|---|
| **1** | **Wire the 46-cell grid to the engine** (§9.1) | Our identity gap | **2–3** | open — **estimate reduced from 5–8, engine read path already proven** |
| **2** | **Position-locked, minute-proportional training with a 90-min cap; "only the last match's role counts"** | Turns training into a weekly decision | 1–2 | open — intensity and minute-weighting done; the hard 90-min cap and last-match-role are not |
| **3** | **Player can refuse to be listed** | Cheapest anti-speculation mechanic in the genre | 1 | open — the `objection` field now exists to support it |
| **4** | **Supporter mood (7) + supporter expectations (9)** | ~30 lines, delivers most of the pressure feeling | 1–2 | open — **now the cheapest win in the meta layer, since board trust computes but cannot act** |
| **5** | **Listing fee as % of asking price** | Anti-spam/anti-dumping tax, 2 lines | 0.5 | open |
| **6** | **Lineup legality caps with a real consequence** | Prevents degenerate XIs | 1 | **done** — 25 senior / 8 youth enforced in `canRegister` |
| **7** | **Junior skill hiding + ±2–3 level scouting estimate** | Doubles academy depth | 1 | **done** — talent hidden behind a narrowing `TalentRange` |
| **8** | **Graduation caps** | Stops the academy flattening the economy | 0.5 | open |
| **9** | **3 transfer advertisements per club; advertising unlocks skill visibility** | Discovery without scouting | 1–2 | open — `scoutedUnlisted()` and `ClubNeedService` cover part of this |
| **10** | **Zero-consequence exhibition mode** ("Arcade") | Multiplies the value of 14,880 clubs | 1 | open |
| **11** | **Corners decided by grid geometry, not a nominated taker** | A *reason* for the editor to matter at set pieces | 0.5 | open — and now easier, given #1 |
| **12** | **Pitch dimensions as a tactical lever** | Makes the pitch real | 1–2 | open — upkeep is a real ledger line now |
| **13** | **A documented, blessed read API** | Both competitors gate their tool ecosystems on this | 1–2 | open — **`/api/**` is authenticated now, which is the precondition** |
| **14** | **World ranking points that drive cup entry and draw seeding** | Makes the world legible | 2–3 | **partly done** — country Elo exists and is deterministic. **But every league match is rated as a cup match, and 47 of 48 countries qualify continentally by alphabetical order.** The half that is missing is the half that matters |
| **15** | **Coach/scout market with per-skill training ratings** | Replaces fake staff | 3–5 | **partly done** — `StaffMember` exists and reaches the engine. **No market to hire from** |
| **16** | **In-game date + day counter on `/api/game-clock`** | Required for any auction window | 1 | **done** — day-precise clock with kickoff gating. ⚠️ But see §9.0: any user can advance it and the season counter skips |
| **17** | **Published, auditable tactical effect — pre-match preview** | Hattrick's single best idea | 3–5 | open — `MatchController/{id}/preview` returns `Map.of()` |
| **18** | **Named tactics with derived levels** | Gives the 6 slider fields real consumers | 3–4 | open — the 6 fields still have **zero readers** |
| **19** | **Specialities + special events** | Nearly free once actions resolve individually | 2–3 | open |
| **20** | **In-match dynamics: pullback when leading** | Makes protecting a lead a decision | 1–2 | open |
| **21** | **Weather + pitch condition** | Sokker already has pitch condition | 1–2 | open |
| **22** | **Man marking order** | The one genuinely novel Hattrick mechanic | 2–3 | open |
| **23** | **Experience + nerves in cup matches** | Free drama | 1 | open |
| **24** | **Formation experience + confusion** | A manager who saves a bizarre shape should pay | 2 | open |
| **25** | **Promotion/relegation qualifiers as a distinct phase** | Better drama structure | 2 | open — ⚠️ fix `buildPlayoffSummary` first (§9.0) |
| **26** | **Board cash ceiling with a weekly release rate** | Turns "spend it or lose it" into a real decision | 2–3 | open — **now cheap, because the ledger exists to model it** |
| **27** | **A retirement age** | Prevents an unbounded pool | 1 | open — still the cheapest 1-day item on this list |
| **28** | **Injury risk as a per-team-per-match budget** | Hattrick's calibration is better | 1 | open — ⚠️ we now generate **8 types**, which is richer; the question is frequency, not existence |
| **29** | **Individual training focus per player** | FM's headline | 4–6 | **cancelled** — S4.1 deleted by owner |
| **30** | **Training intensity vs injury/fatigue risk** | Already S4.2 | 2–3 | **done** |
| **31** | **Facilities 1–20** | Gives money somewhere to go | 3–4 | **partly** — upkeep is a real ledger line; no build UI |
| **32** | **Faceted board confidence + sacking** | FM's best meta-layer idea | 4–6 | **partly** — trust computes and displays; **sacking does not exist** |
| **33** | **Honest per-match analytics: xG, xA, PPDA, tilt** | The differentiator | 6–10 | open — **and now the largest single gap in the product** |
| **34** | **Scouting ranges that narrow with assignment** | Pairs with Sokker's estimate-accuracy idea | 4–6 | **done for juniors**; squad players still exact |
| **35** | **Set-piece routines with role categories** | FM's genuine tactical innovation | 4–6 | open |
| **36** | **Manager attribute drift + a Style Focus slider** | Gives the manager a build | 2 | open |
| **37** | **A `Responsibilities` delegation screen** | Attacks our worst risk (the grind) | 2–3 | open |
| **38** | **[NEW] Let the seller pick which offer to accept** | We score ❌ where all three competitors score ✅ | 0.5 | open — **the service method exists and is unreachable from HTTP** |
| **39** | **[NEW] Wire the existing STOMP backend into a chat client** | No WebSocket client in the SPA despite the server config | 1–2 | open — sending a message currently re-downloads the whole feed and the whole user list |

**Explicitly NOT recommended from FM:** unchanged — the inbox-as-primary-channel, press conferences,
the Dynamic Manager Timeline, and the weekly-schedule re-entry ritual. We have an explicit
competitive advantage in time commitment; do not give it away.

**And now, not recommended from ourselves:** the backlog's own `S8.x` presentation work should not
outrank §9.0. The world is 48× bigger than the code that schedules it, and two of the four §9.0 P0s
corrupt a season permanently.

### 10.4 Sequencing recommendation — **[re-verified 2026-10-01: both amendments are DONE]**

**Amendment 1 (tactics wiring before the economy) — not needed.** The economy shipped first and the
tactics wiring is still outstanding. In hindsight the ordering was defensible: building the economy
first is what made the tactics gap *legible* rather than hidden, and it is what proved the loop
closes. **But the argument for doing tactics now is stronger than it was**, because the estimate
fell from 5–8 days to 2–3 (§9.1) and there is a 48-country world waiting to be steered.

**Amendment 2 (registration to Sprint 0) — done.** Closed end-to-end (§9.6).

**Amendment 3 — [NEW] the day-job framework outranks all of it.** The world is 48× bigger than the
code that schedules it and two of the four §9.0 P0s corrupt a season permanently. This is not a
feature; it is a stop-the-bleeding item, and it is small: the guard ordering is a few lines, the
season double-increment is three, role-gating three endpoints is three.

**Amendment 4 — [NEW] fix the three stale test fixtures before the tactics wiring**, not after.
`WeeklyFinanceServiceTest`, `StaffSponsorServiceTest` and `PlayerContractServiceTest` pass calendar
years where the game uses a season *number*, and they are self-consistent under either reading — so
they will not catch a regression when the tactical rules are re-keyed.

Proposed order:

```
▶ §9.0 DAY-JOB + CLOCK CORRECTNESS (2-3d)           ← two P0s corrupt a season forever
▶ TACTICS WIRING (2-3d)                            ← identity; makes the engine steerable
S4  (5d)  training depth left: 90-min cap, last-match role, AI priorities
S5  (5d)  AI ageing/retirement, graduation caps
S3  (5d)  seller picks the offer; listing fee; sale tax
S8  (12d) presentation, cup draw randomness, internationals, infra
▶ FEATURE #33 analytics (6-10d)                     ← the largest single gap in the product
```

Note what dropped off: the economy (18d), the auction rebuild (15d) and registration (1d) are
**done**. That is ~34 days of the original 79–95-day plan already landed in five days, which is why
the remaining list is shorter and why the sequencing above is dominated by fixing rather than
building.

---

## 11. Strategic decisions for the owner

These are forks, not tasks. Each needs a decision before the relevant sprint starts.

### 11.1 Do we publish our engine's internals or keep them opaque?

This is the single most important design question in the document, and it is a genuine fork with no safe answer.

- **Sokker's bet:** ratings play no role; you cannot learn the engine from statistics; you must watch the animation. Retention is high, onboarding is brutal, and *"the report data is useless"* is their own words.
- **Hattrick's bet:** publish every coefficient and tactic level. Auditability is praised as a feature — and the engine is still called unplayably random. Their own survey found understanding *fell* from 73% to 50% as they explained more.
- **FM's bet:** rich data, and in FM26 the data drives the assistant's advice.

**Our unique position:** we can have the honest animation *and* honest numbers, and make them agree, because both come from the same deterministic simulation. Nobody else can do that.

**My recommendation:** publish, but in two layers. (a) A **pre-match preview** — what your shape and tactic will do, à la Hattrick's sector ratings or FM's Visualiser. (b) A **post-match analytics layer** — real xG/PPDA/field tilt, §9.8. Keep the *tick-by-tick* internals opaque. This gets the auditability benefit without the "spreadsheet simulator" failure mode, and it is a genuine competitive moat rather than a feature.

**[re-verified 2026-10-01] The recommendation is unchanged and is now the whole remaining strategic
case.** When this section was written we had a management game to build *around* a good engine. We
now have the management game, and the engine-versus-economy asymmetry that made §2 uncomfortable is
gone. What is left that neither browser competitor can do is exactly the two layers above:

- **Layer (a), the pre-match preview: still ❌** and it is the only row where **Hattrick is the best
  in the browser category and we are last.** We have the engine that could compute it and
  `MatchController/{id}/preview` returns an empty map. This is also the cheapest way to make the
  tactical wiring (§9.1) *visible* to the player on day one rather than invisible.
- **Layer (b), the post-match analytics: still ❌**, and it is now the largest single gap in the
  product (6–10 days).

**Neither Sokker nor Hattrick can do either of these honestly.** Sokker has chosen opacity by
design and its own help text says the report is useless. Hattrick publishes everything and is
regarded as a spreadsheet with a football skin. We are the only product whose numbers would come
from the same deterministic simulation the user just watched — and the determinism is what makes it
verifiable, because a batch run is reproducible and neither competitor can say that.

**This changes the priority order one more time.** Analytics was a P2 when we had no economy. It is
a P1 now, because it is the only remaining capability where we are uniquely able to beat both
competitors rather than catch them.

### 11.2 How much meta-layer pressure? — **[re-verified: half-built, and that is the problem]**

Sokker deliberately has none and its veterans defend that. Its community has been asking for board
expectations for years anyway. FM has a pressure loop so heavy it drives players to the editor and
the delegate button.

**[re-verified] We have half of FM's and neither of Sokker's.** `BoardExpectationService` computes a
0–100 trust score from FFP, league standing, unhappy players and squad size, and displays the FFP band
to the player. `MoraleService` is live and feeds both training growth and the match. **But
`sackingReview` is a read-only boolean with no entity, no persistence and no end-of-season review,
so nothing acts on the trust score.** And supporter mood — the cheap layer Sokker's veterans defend
and its community has asked for in parallel — is still not built.

The question was *"do we add FM's faceted board trust or Sokker's supporter expectations?"* **The
2026-10-01 answer is that we have already built the expensive one's arithmetic without its
consequences, and that is the worst of both worlds:** a number the player can see that nothing
responds to. That is worse than having no trust score at all, because it invites the expectation of
a consequence.

Recommendation, reordered: **supporter expectations first**, ~30 lines of enum plus one mood
modifier, and they deliver most of the *"my team is being humiliated"* feeling for a fraction of the
cost. **Then** make `sackingReview` mean something, or remove it from the UI until it does.

### 11.3 AI clubs: plausible on the pitch, frozen off it? — **[re-verified: decided in code, partly against the letter of the decision]**

The PO decision is that AI clubs never buy or sell. Two consequences needed explicit decisions:

1. **AI squads are frozen forever** — no growth, no decline, no ageing out, no retirement. The pyramid never evolves and promotion has no long-term meaning. Recommend: AI clubs **train and age** but do not trade.
2. **AI club finances.** If AI clubs have no economy, a promoted club is as rich as a champion. Recommend: run the ledger for all clubs so AI finances are *plausible*, but gate only the **buy/sell** actions behind `humanControlled`.

**[re-verified] Both landed, and the second went further than recommended.** Every club trains
(`SquadTrainingService.trainEveryClub`) and every club's ledger settles weekly. The AI↔AI market
**also** happened — `TransferService:775-870` runs a weekly auction weighted by `ClubNeedService.interest`.

**This is the right call and the reasoning matters:** the PO decision was about AI clubs
*outbidding a human manager*, not about the world being alive. Those are different things. But
**the code does not currently draw the line where the decision draws it** — there is no check that
an AI club cannot make an unsolicited approach to a human-owned player. That is the one gap in this
section that is a genuine risk rather than an improvement, and it should be a deliberate line in the
code rather than an accident.

**Still open from this section:** AI clubs train but never set priorities and never decline, so the
pyramid is still static in the way that matters. **A retirement age (feature #27, 1 day) is now the
highest-value item in the long game**, because without it neither the pyramid nor the transfer market
can ever turn over.

### 11.4 Do we want a live world or a solo career? — **[re-verified: partly answered, and partly broken]**

Our week advance is a button. Both competitors are asynchronous multiplayer worlds that advance on a
schedule, and Sokker's whole appeal is that other managers' decisions are the opposition. But a
global singleton clock that any player can advance is a correctness bug, not a design.

**[re-verified] The scheduled-advance half is built:** a day-precise game clock, a `DayJob` framework
with eleven jobs on a weekly template, and a scheduler that fires on the hour. The auction blocker
this section named is therefore no longer a blocker.

**The bug this section predicted is now real and worse than predicted.** Any authenticated user can
call `POST /api/game-clock/advance`, `/advance-to-hour` or `/api/jobs/run-due` — the admin check
exists only in the browser. And because the world is now 48 countries rather than one, "any player
advances the world" is a materially bigger statement than it was in September. **This needs a
decision, not a fix:** scheduled server-side advance only (matches both competitors, simplest), or
per-player deadlines (harder, and better for a competitive world).

Two more things the clock work surfaced that need deciding:

- **`GAME_ZONE` is declared and never used.** Game time is an `Instant` with a `currentDate` built in
  **UTC**, while `/api/server-time` reports **Belgrade** and the client formats **Belgrade**. Three
  definitions of game time, one used. Pick one before the auction depends on a deadline.
- **The in-game date is `now() + advanceOffsetSeconds`**, so it moves by however long you waited
  between clicks rather than by 7 days per Advance Week. The transfer auction's window is measured
  in in-game days, so this has to be a real date arithmetic, not an offset from the wall clock.

---

## 12. Evidence and verification notes

Two load-bearing claims were re-verified by hand rather than taken from the audit.

**1. Tactics never reach the engine.**
```
$ grep -rn "new TacticsRules|new MatchOrchestrator" . --include="*.java"
./sim/ui/ProposalMatchExporter.java:40        new MatchOrchestrator(state)
./sim/ui/ProposalViewerLauncher.java:64      new MatchOrchestrator(state)
./sim/SimMatchRunner.java:53                 new MatchOrchestrator(state)
./sim/ProposalBatchDiag.java:26              new MatchOrchestrator(state)
./sim/MatchSimulationLauncher.java:30        new MatchOrchestrator(state)
./sim/ProposalPhysicsDiagnostic.java:54      new MatchOrchestrator(state)
./sim/ProposalPassFailDiag.java:38           new MatchOrchestrator(state)
./sim/controller/ProposalMatchController.java:52  new MatchOrchestrator(state)
./sim/restarts/RestartManager.java:52        this(new TacticsRules())
./sim/engine/MatchSimulator.java:46          new MatchOrchestrator(state)
./sim/engine/MatchOrchestrator.java:181      this(state, new TacticsRules())
./sim/probe/TeamStrengthProbe.java:76        new MatchOrchestrator(state)

$ grep -rl "TeamTacticsProfile" . --include="*.java"
./repository/TeamTacticsProfileRepository.java
./util/DatabaseInitializer.java
./model/tactics/TeamTacticsProfile.java
./service/TacticsProfileBackupService.java
./service/TeamTacticsService.java
```
No engine file. All 12 orchestrator constructions use the no-arg form.

`sim/tactics/TacticsRules.java:37` — `public static final String FORMATION = "4-4-2";`
`sim/tactics/TacticsRules.java:45-49` — raw JDBC defaults to `jdbc:postgresql://localhost:5432/sokker_db`, team id 1.

**2. The 506 possession-context rules are identical — and that is intentional.**
```
$ python3 -c "...compare tactics_fallback.json by (slotKey, ballStateKey)..."
WE: 506  OP: 506  common: 506
identical targets: 506  different: 0
distinct ball states: 46
GK distinct targets across ball states: 2 of 46
```
`1012 entries = 11 slots × 46 ball states × 2 possession contexts`. Zero differ between
in-possession and out-of-possession, **by owner decision (2026-09-26)** — the cause is
`TeamTacticsService.mirrorWeHaveBallRules()`, which clones the `WE_HAVE_BALL` target onto the
`OPPONENT_HAS_BALL` rule on every save and load. Not a defect; recorded here so nobody
"discovers" it and re-becomes the decision.

Two factual observations that survive the ruling: 46 ball states = 42 reachable grid cells + 4
unreachable corner states, and the GK resolving to only 2 distinct targets follows from the
generated defaults mapping every ball state to the slot's anchor.

**3. [NEW 2026-10-01] Both greps re-run, 220 commits later — unchanged, with three additions.**
The `TeamTacticsProfile` file list is **byte-identical**. The orchestrator list gained
`ProposalSeasonDiag.java:57` and lost one prior site, so it is now 11 main sites, all no-arg.
`tactics_fallback.json` still has exactly 1012 entries and the counts above still reproduce.

Three things that were not there in September:

- `TacticsBridge.fromRuntimeMap()` — the bridge this document asked for, converting the runtime rule
  map into a `TacticRules` model, with **zero callers**. `NewLogicTacticsService.loadTacticRules(teamId, formation)`
  returns a default unconditionally, **discarding both of its own arguments**, also with zero callers.
  **Wire these; do not write a third bridge.**
- `tactics/DefensiveShape.java` — derives the out-of-possession shape arithmetically, and
  `TacticsRules.desiredCellFromContext()` prefers it whenever an authored `OPPONENT_HAS_BALL` rule
  matches its `WE_HAVE_BALL` twin. With all 506 pairs identical, **the derivation wins on every
  lookup**, so the engine now attacks and defends in different shapes with no data edit. Defensible,
  tested, and a **third position** the 2026-09-26 ruling did not describe. **Owner confirmation needed.**
- `FormationSlotCatalog` now holds **9 layouts** (was 3). Still 1 applied, because
  `anchorsFromCatalog()` passes the hardcoded `FORMATION`.

**4. [NEW] A correction to the previous expert audit, recorded so nobody re-breaks it.**
`expertAudit.md:584` stated the injury generator was quarantined and *"the live proposal engine
currently has NO injury generator at all."* That was true on 2026-09-26 and is false now:
`sim/engine/InjuryService.java` is the live generator, ported from the quarantined `engine_v1` class,
with 8 types and a fatigue-driven, fatigue-weighted selection. The ✅ in §7.3 of this document was
right about the feature. **Do not "re-fix" it.**

**5. [NEW] A correction to my own work, recorded because it is the same mistake twice.**
`dataFixSuggestions.md` (written earlier on 2026-10-01) claimed `ZoneLoadService.applyDailyRecovery()`
"never calls `save()`, so `RecoveryJob` writes zero rows and reports DONE." **That was wrong** — the
method is `@Transactional` over managed entities, so JPA dirty-checking flushes the change. This is
the second time this project has read "no explicit `save()`" as "no write." The file has been
corrected, and the real defects found in the same three lines (the computed recovery amount is
discarded in favour of a flat `+0.2`; morale has no upper clamp) are now recorded there instead.

**Corrections to `AGENTS.md` found along the way** (it is stale, and `sprintBacklog.md` S6.5 already schedules a rewrite): the `demo/service/` and `newLogic/sim/` package trees it documents as the product have been restructured; the `commonmanager/` routing is wrong; `pages.js` is 792 lines, not "5200+"; `/start-realistic-demo → RealisticMatchEngine` no longer exists; `CompetitionController` has 0 routes. **[re-verified 2026-10-01] All five still true, and the document has drifted further** — it now also names four test classes that do not exist, and the `AGENTS.md` rewrite is still unscheduled five days after `sprintProgress.md:199` flagged it as making S6.5 urgent.

---

## 13. One paragraph

*Updated 2026-10-01. The previous version said:* Sokker has spent 22 years building a management game on an engine worse than ours, and 40,000 clubs play it daily; Hattrick has spent 29 years building an economy on a spreadsheet, and 14.7 million accounts have played it. We had the engine they never built, and none of the game they built around it.

*That is no longer true.* In five days the loop closed: `Player.earnings` now drives a real wage bill, `Team.budget` moves on a nine-category weekly ledger for every one of ~14,880 clubs, contracts expire into free agency, a five-round negotiation replaced the dice roll, a scouting network hides junior talent behind a narrowing range, and a 48-country world with a live promotion ladder replaced one Serbian pyramid. **We now have both halves of what neither competitor has: an engine that is better than Sokker's and more honest than Hattrick's, and a management loop that actually closes.** What we still do not have is stakes or a social layer — you cannot be sacked, players never retire, and the auction lacks the four mechanics (listing fee, sale tax, anti-daytrade, a seller who can pick a bidder) that make Sokker's market a conversation rather than a clearance sale. And the tactics screen, the one feature that would make the engine matter to the person playing it, still writes to a table nothing reads — though the owner confirms the engine is running on an identical copy of that data pending the final test, which turns a 5–8 day build into a 2–3 day wiring job. **The next bottleneck is not a feature at all: the world grew 48× in five days and the code that schedules it did not, so two of its defects will corrupt a season permanently.** Fix the day-job guard, the clock's permissions and the double season increment, then wire the tactics, then build the analytics layer — and we have something neither of them can offer: a match where the animation and the statistics are the same truth.
