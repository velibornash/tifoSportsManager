# TIFO Manager — Competitive Analysis: Sokker vs Hattrick vs Football Manager

**Date:** 2026-09-26
**Author:** product/architecture review
**Scope:** the UI Football game (`/dashboard.html`, `newLogic/`) compared against the three reference managers.
**Status of our game:** verified against the code at git `9f44d4a` + uncommitted Sprint-2 WIP, not against `AGENTS.md` (which is stale in several load-bearing places).

**Legend:** ✅ implemented · 🟡 partial / stubbed · ❌ absent · ⬜ not applicable to us

---

## 1. Method and sources

| Source | How it was gathered |
|---|---|
| **Our game** | Read `sprintBacklog.md`, `sprintProgress.md`, `expertAudit.md` in full; then verified every load-bearing claim against the source tree. Two claims were independently re-verified by hand (see §12). |
| **Sokker** (`sokker.org`) | `sokker.org` live site, the 18-chapter official `/rules`, the German national chapter FAQ (`sokker-deutschland.de/faq` — the only document that explains *mechanics* rather than *rules*), real 2026 match reports, Dev Diary #82–#86, forum threads. The Fandom wiki is ~14 years stale and was **not** relied on. |
| **Hattrick** (`hattrick.org`) | Official Manual/Help across regional mirrors, `wiki.hattrick.org`, Hattrick Developer Blog (~1M match dataset), the 2025/2026 arXiv Bayesian-network paper, community tools (Hattrick Organizer, HT-Ro, hattrick-youthclub), Reddit. |
| **Football Manager** | footballmanager.com feature pages, sports-interactive.com news, Steam, FM Scout, FMInside, FM forums, FM24/FM25/FM26 press. |

**Caveat carried forward:** several Sokker rule pages are internally inconsistent (rules page still says 5% sale tax; an Aug-2025 news post supersedes it with 8% + a profit-based component). Where that matters it is flagged inline.

---

## 2. TL;DR — the thesis

**We have the best match engine of the three, and roughly none of the management game.**

That is not a comfortable position, because it is the inverse of how both browser competitors succeeded:

- **Sokker** has a *worse* match engine than ours — its own help text says ratings play **no role** in the calculation, and results are decided action-by-action by 8 skills plus tactics plus randomness. It has 40,000 clubs, 38.5M matches, 22 years of live economy.
- **Hattrick** has an engine the community itself calls broken: *"you can still lose to someone who has literally zero chances of winning."* It is a spreadsheet simulator. It has 14.7M registered accounts and a 29-year economy.
- **Football Manager** has the deepest management simulation ever built and is widely hated for its UI and its grind.

The conclusion the evidence forces: **the match engine was never the product.** For two decades two companies have proven that a manager game lives or dies on the management layer, and that a mediocre engine is entirely survivable if the loop is deep enough.

**Our one structural advantage that neither browser competitor has:** both of them must choose between a meaningful match view and meaningful numbers. Sokker chose the animation and made its report deliberately uninformative (*"the report data is useless… only [the animation] do you recognise everything you need"*). Hattrick chose the numbers and has no spatial match view at all. We have a real spatial, tick-based, deterministic physics engine *and* can compute honest analytics from the same simulation — meaning we can make the animation and the statistics **agree**, and both be true. That is the differentiator worth building the whole product around. See §11.1.

**The three things that must happen, in order:**

1. **Wire the tactical editor to the match engine.** Today it does nothing at all. Sokker's entire identity is this one feature and we already own 80% of the data model. (§9.1)
2. **Build the economy loop** — `Player.earnings` → wage bill → transfer budget → squad → league position → board trust → whether you keep your job. Sprint 2 in the backlog is correct and should not be reordered. (§9.2)
3. **Replace the prose-string transfer market with a real auction.** This is Sokker's social centre and the reason managers talk to each other. (§9.3)

---

## 3. What our game actually is today

### 3.1 Verified engine capability (the moat)

| Capability | State |
|---|---|
| Tick-based spatial simulation | 40 ticks/min, 3,600 ticks, per-tick pipeline explicit in `MatchOrchestrator.java:114-368` |
| Determinism | `SimulationRandom.seed(fixture.getId())` + thread-local `Random`; batch runs reproducible — **this is why calibration was possible at all** |
| Ball physics | Velocity-based, per-mode deceleration, lateral spin, goal-plane crossing, OOB bands |
| Goalkeeper | Bisector positioning, advance ramp, 1v1 rush, reach/chance model. 11 unit tests. Correctly solves both Sokker failure modes (keeper magnet, keeper never saves) |
| Duels | Skill-weighted with proper radii, one presser per threat (`isClosestEligiblePresser`) |
| Rules | Offside (second-to-last defender), VAR with frequency gates, discipline, penalties (keeper commits to a dive *before* the kick), restarts, throw-ins, corners, goal kicks |
| Substitutions | 5 subs / 3 windows, injury + fatigue auto-sub, **a sent-off player is never replaced** |
| Stoppage time | `StoppageClock` — real added time per half, driven by actual stoppage causes |
| Replays | File-backed, bounded (200 entries / 14 days), `410 GONE` on expiry |
| Viewer | 2D canvas + 3D (three.js), LED scoreboard, click-to-inspect, event timeline, 0.25×–10×, keyboard, mobile ticker |
| Stats | Possession %, possession chains, x-thought pass taxonomy, duels, interceptions, restarts, injuries, subs |
| Tests | 176 tests, `mvn test` in 1:10 |

Measured over 100 matches (a record, not a scorecard — statistical targets were deferred by owner on 2026-09-26):

| Metric | Ours | Real PL |
|---|---:|---:|
| goals | 3.55 | 2.7 |
| shots | 35.7 | 25 |
| on-target % | 28% | 33% |
| pass accuracy | 85% | 80–86% |
| penalties / conversion | 0.24 / 76.6% | ~0.27 / ~76% |
| fouls | 12.0 | 22 |
| duels | 268 | ~100 |
| corners | 6.4 | ~10 |

### 3.2 Verified management state (the problem)

| Layer | Depth | Note |
|---|---:|---|
| Match engine | **9/10** | Better than either browser competitor |
| Season, fixtures, pyramid, promotion/relegation + 2-leg playoff | **7/10** | 31 leagues / 5 tiers / 310 clubs, circle-method schedule, real rollovers |
| Squad/player model | **3/10** | 8 skills, 5 position buckets, talent, form (a constant), injuries, fatigue |
| Tactics | **1/10** | Editor exists, data model exists, **neither reaches the engine** |
| Training | **3/10** | Correct growth formula, design space of 2 knobs, zero trade-offs, staff has no effect |
| Juniors | **3/10** | Works, but no scouting, no uncertainty, no potential ceiling, position rolled at promotion |
| Transfers | **2/10** | ~15% of Sokker. Offers are English prose strings. Negotiation is one HTTP call + one dice roll |
| Economy | **0/10** | No ledger, no wages, no sponsors, no prize money, no gate revenue, no debt, no failure state |
| Meta layer (board/trust/morale) | **0/10** | Cannot be sacked. Nothing can go wrong |
| Cups, internationals, national teams | **0/10** | `Kup Srbije` is created and never populated. NT pages are placeholders |
| Registration | **broken** | `register.html` POSTs to `/auth/register`, which does not exist. Admin approval endpoint also does not exist |

### 3.3 Product decisions already taken (and how they change this analysis)

Three owner decisions materially reshape the competitive picture, because they make us a **multiplayer human economy**, not an AI sandbox:

1. **AI clubs never buy or never sell.** Only real players trade. An admin can force-list. This means the transfer market is only as liquid as the human player count — with one player it is a monologue, and the auction UI must be built for 10+ players across multiple countries from day one.
2. **Manual week advance, no schedulers.** Correct while we are the only player. Becomes deployment work.
3. **Statistical calibration is polish, not a gate.** Missing and broken mechanics are the priority.

Consequence: the `expertAudit.md` findings "AI↔AI transfers never happen" and "run finances for all 310 clubs" are **not defects** — they are invalidated requirements. AI clubs need only be plausible *on the pitch*.

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
| Deterministic, reproducible | ❌ | ✅ | ✅ (partly) | ✅ **seeded RNG** |
| Ratings-free action resolution | ✅ **by design** | ❌ (ratings *are* the sim) | ❌ | ❌ (we expose stats) |
| Sector / contribution model exposed | ❌ | ✅ **fully published** | 🟡 | 🟡 decision scores only |
| Live tactical preview before kickoff | 🟡 (grid editor) | ✅ **sector ratings as you build** | ✅ **Visualiser, 9 zones** | ❌ |
| Offside modelled | ✅ | ❌ | ✅ | ✅ |
| Penalties | ✅ | ✅ (shootouts) | ✅ | ✅ **keeper commits first** |
| Substitutions | ✅ 3, conditional | 🟡 position swap only | ✅ | ✅ **5/3 windows** |
| Conditional / rule-based subs | ❌ | ❌ | ❌ | ✅ **engine+API, no UI** |
| Real stoppage time | ✅ | 🟡 | ✅ | ✅ **`StoppageClock`** |
| Set-piece choreography | 🟡 takers only | 🟡 SP number only | ✅ **routines + roles** | 🟡 takers stored, unread |
| Corners/throw-ins as geometry decisions | ✅ **grid edges** | ❌ | 🟡 | 🟡 |
| 3D match view | ✅ | ❌ | ✅ | ✅ |
| Analytics from the same sim (xG, PPDA, tilt) | ❌ | 🟡 (ratings) | ✅ **Data Hub** | ❌ **biggest analytics gap** |

### 7.2 Tactics

| Capability | Sokker | Hattrick | FM | **Ours** |
|---|:--:|:--:|:--:|:--:|
| Ball-position grid editor | ✅ **5×7 = 35 cells × 10 players** | ❌ | 🟡 Visualiser (read-only) | 🟡 **built, disconnected** |
| Per-slot per-ball-state targets persisted | ✅ 350/tactic | ❌ | ❌ | 🟡 **506 in data, 0 in engine** |
| In-possession vs out-of-possession shape | ✅ | 🟡 orders only | ✅ **dual formations (FM26)** | ⬜ **one shape by owner decision** |
| Role instructions | ✅ **GK/DEF/MID/ATT** | ✅ 4 orders/slot | ✅ 5-star roles, ~30 | 🟡 5 buckets |
| Lineup legality caps | ✅ **1/5/5/3, lose training if broken** | ✅ min 9 starters | ✅ squad registration | ❌ |
| Formation actually applied by engine | ✅ **not bound to labels** | ✅ | ✅ | ❌ **hardcoded 4-4-2** |
| Formation variety | ✅ **free geometry** | ✅ 5 lines × 4 orders | ✅ ~20 formations | 🟡 10 in UI, 3 in code |
| Mentality / tempo / pressing dials | ❌ **deliberately none** | ✅ 7 tactics | ✅ ~30 instructions | 🟡 fields exist, no UI, no consumer |
| Man marking | ❌ | ✅ | 🟡 | ❌ |
| Pitch dimensions affect play | ✅ **64–78 m × 95–110 m** | ❌ | ❌ | ❌ |

### 7.3 Player, training, youth

| Capability | Sokker | Hattrick | FM | **Ours** |
|---|:--:|:--:|:--:|:--:|
| Skill count | 8 | 7 | 36 attributes | 8 |
| Skill ceiling | 17.99 named tiers | 30 × 4 sublevels | 1–20 + CA/PA 1–200 | 20.99 |
| Hidden talent / potential ceiling | ✅ Talent (multiplier) | ❌ | ✅ PA (fixed or banded) | 🟡 talent shown openly, no ceiling |
| Scouting uncertainty | ✅ **±2–3 level estimate** | 🟡 | ✅ **attribute ranges** | ❌ **exact value displayed** |
| Scouting network / recruitment | ❌ | ✅ chief scout + network | ✅ full network | ❌ (`Country.youthRating` is a dead hook) |
| Training intensity vs injury risk | ❌ | ✅ **0–5, +2.5%/level risk** | ✅ Normal/Half/Double | ❌ |
| Individual training focus | 🟡 by position only | 🟡 by training type | ✅ **11 focus areas** | ❌ |
| Minute-proportional training | ✅ **90-min cap, last-match role** | ✅ **90-min cap, worked examples** | 🟡 | ❌ **19 runs/season, role only** |
| Facilities affect growth | ❌ | 🟡 staff only | ✅ 1–20 each | ❌ |
| Coaching staff affects growth | ✅ **8 per-skill ratings** | ✅ skill 1–5 + leadership | ✅ 10 coaching attributes | ❌ **fake, zero effect** |
| Ageing decline | ✅ **from 30** | ✅ **from ~30** | ✅ gradual | ✅ **from 29, hard cliff** |
| Retirement | ❌ | ❌ **93-year-old record holder** | ✅ | ❌ **players age forever** |
| Junior/school intake | ✅ **30 places, 1000$/place/wk, 0–6/wk** | ✅ **two systems, 3 scouts** | ✅ academy quality-gated | 🟡 **10 cap, 1 intake/season** |
| Junior skill visibility | ✅ **hidden until promotion** | 🟡 | ✅ ranges | ❌ **raw value shown** |
| Formation experience / confusion | ❌ | ✅ | 🟡 familiarity | ❌ |
| Team spirit | ❌ | ✅ | 🟡 | ❌ |
| Morale / loyalty / dressing room | ❌ **deliberate** | 🟡 loyalty only | ✅ full | ❌ **`form` is a constant** |
| Injuries | ✅ duels, no medical staff | ✅ **0.4/team/match, medic levels** | ✅ natural fitness | ✅ **fatigue-driven, 8 types** |
| Fatigue from activity not clock | ✅ | 🟡 | 🟡 | 🟡 ticks |
| Specialities + special events | ❌ | ✅ **7 types** | ✅ 50+ traits | ❌ |
| Experience / nerves | ✅ tactical discipline | ✅ **nerves in cups** | 🟡 | ❌ |

### 7.4 Transfers and contracts

| Capability | Sokker | Hattrick | FM | **Ours** |
|---|:--:|:--:|:--:|:--:|
| Auction with bidding | ✅ **2 days, proxy/max** | ✅ **72h, +2% min, anti-snipe** | 🟡 bidding | ❌ |
| Listing fee | ✅ **2.5%** | ✅ 1,000 $ flat | ❌ | ❌ |
| Sale tax | ✅ **8% + profit-based time component** | ✅ agent 12%→2% table | 🟡 agent fees | ❌ |
| Anti-daytrade mechanism | ✅ **player can refuse to be listed** | ✅ mother club 2% | ❌ | ❌ |
| Transfer windows | ❌ **deliberately none** | ❌ **always open** | ✅ | ❌ **open 52 weeks** |
| Contracts with length/expiry | ❌ | ❌ | ✅ | ❌ |
| Free agents | ❌ | ❌ | ✅ | ❌ **impossible by construction** |
| Loans | ❌ | ❌ | ✅ | ❌ |
| Work permits / foreign limits | ❌ | ❌ | ✅ | ❌ |
| Negotiation rounds | ❌ | ❌ | ✅ **3-way with agents** | ❌ **one HTTP call + one dice roll** |
| **Seller chooses which offer to accept** | ✅ n/a (auction) | ✅ | ✅ | ❌ **forced to take the highest** |
| Transfer history | ✅ | ✅ | ✅ | ❌ only listedAt/completedAt |
| AI↔AI market | ✅ bots | ✅ bots | ✅ | ❌ **by design** |
| Market liquidity constraint | ✅ real | ✅ real | n/a | 🟡 **= human player count** |

### 7.5 Economy and club

| Capability | Sokker | Hattrick | FM | **Ours** |
|---|:--:|:--:|:--:|:--:|
| Weekly ledger | ✅ | ✅ | ✅ | ❌ |
| Wage bill from player earnings | ✅ **published formula** | ✅ **published formula** | ✅ | ❌ **`earnings` read by nothing** |
| Gate revenue | ✅ **all to home** | ✅ **stand-type split 67/33** | ✅ | 🟡 **service rewritten, nothing calls it** |
| Sponsorship | ✅ weekly, mood-scaled | ✅ **two components + season incentive** | ✅ **levels + objectives** | ❌ **3 literal JS objects** |
| Prize money | ✅ **published table** | ✅ **published table** | ✅ | ❌ |
| Debt / interest / bankruptcy | ✅ **4.5%, −250k/−500k** | ✅ **3.33%, 500k line** | ✅ soft | ❌ |
| Board cash ceiling | ❌ | ✅ **board hoards, releases weekly** | ✅ budget requests | ❌ |
| Stadium as an economy | ✅ **8 sectors, 4 stand types, demand curve** | ✅ **4 stand types, weather-sensitive** | ✅ capacity + pricing | 🟡 entity, empty service |
| Pitch quality / tiers | ✅ **6 tiers, affects bounces + injuries** | ❌ | ❌ | 🟡 field, no effect |
| Sponsorship/staff as entities | 🟡 staff only | ✅ | ✅ | ❌ **no `StaffMember` entity exists** |

### 7.6 Meta, world, social

| Capability | Sokker | Hattrick | FM | **Ours** |
|---|:--:|:--:|:--:|:--:|
| **Board expectations + trust** | ❌ **multi-year community request** | 🟡 | ✅ **1–20, 5 facets** | ❌ |
| **Manager can be sacked** | ❌ | 🟡 | ✅ | ❌ |
| Supporter mood / expectations | ✅ **7 + 9 levels** | ✅ mood | ✅ profile | ❌ |
| Retirement | ❌ | ❌ | ✅ | ❌ |
| League pyramid depth | ✅ **×3 per level, 12-team leagues** | ✅ 8-team series | ✅ 58 leagues + 14 women's | 🟡 **31 leagues, Serbia only** |
| Promotion/relegation + playoffs | ✅ **2nd vs 7th–9th** | ✅ qualifiers | ✅ | ✅ **2-leg playoff** |
| Knockout cup | ✅ national + 128-team continental | ✅ national + Masters | ✅ | ❌ **created, never populated** |
| National teams | ✅ **user-elected coaches, skill hiding** | ✅ | ✅ | ❌ placeholder pages |
| World ranking | ✅ points-based, drives cup entry | ✅ Power Rating | n/a | ❌ |
| Manager-to-manager messaging | ✅ SK-mail | ✅ MyHT | 🟡 | 🟡 chat + PM |
| User-run leagues | ✅ friendly leagues | ✅ federations | ✅ online career | ❌ |
| Zero-consequence exhibition mode | ✅ **Arcade** | ✅ arena | 🟡 | ❌ |
| Third-party API / tooling | ✅ **officially blessed API + XML feed** | ✅ CHPP (frozen) | ✅ editor + FMSE | ❌ |
| Time commitment | ✅ **~30 min/week, no daily login** | ✅ **~30 min/week** | ❌ **daily, 30–40 inputs/season** | 🟄 manual click |

---

## 8. Where we are better

Stated plainly, because it is a short list and it is the whole strategic case.

1. **The match engine is better than Sokker's and Hattrick's, and it is not close.** Sokker's own documentation says ratings play no role and results are decided action-by-action by 8 skills plus randomness. Hattrick's community says you can lose to a team with zero chances. We have real ball physics, a defensible goalkeeper, offside, VAR, discipline, real stoppage time, and a deterministic seeded RNG — **and we can prove it, because a batch run is reproducible.** Neither competitor can say that.
2. **Determinism + calibration discipline is a development moat nobody else has.** Hattrick's engine is deliberately unexplainable ("the more explicitly we tell you, the less in control you will feel"). We have measured 100-match batches, recorded every calibration change, and deferred targets explicitly. That is why our engine is trustworthy.
3. **The viewer is a real product.** LED scoreboard, 2D + 3D, click-to-inspect player cards, event timeline, 0.25×–10×, keyboard, mobile live ticker, VAR/half-time/FT overlays. Sokker's viewer is *good*; ours is arguably better engineered (O(1) snapshot index, batched DOM flush, 200-entry cap). Hattrick has no pitch view at all.
4. **Training reports are better than Sokker's.** Per-player, per-skill `before / after / decimalΔ / integerΔ` plus a full season×week matrix. Sokker shows a *Games* column and a projected *Eff.* column and hides the numbers; we show the actual decimals.
5. **The junior promotion reveal is the best single UX moment in the game**, and it is better than Sokker's, which shows nothing but an estimated level and a countdown.
6. **The pass-failure taxonomy is football, not model artefact:** DEFLECT 22.2% / LOOSE_PICKUP 14.7% / INTERCEPT 14.7% / DUEL 9.1% / OFFSIDE 3.4% / OOB 2.0% / FOUL 0.6%. A strong signal the ball model is honest.
7. **Zero-daily-login time commitment.** Both browser competitors are explicitly built around ~30 minutes a week. So are we, if we keep the manual week advance.
8. **We have already thrown away 40,000 LOC of dead engine.** Sokker and Hattrick both carry decades of accumulated cruft (Hattrick's community notes *"there's really no way to improve on Hattrick — any significant change will push some players away"*). We can still make structural changes.
9. **Multi-sport is a genuine differentiator neither browser competitor has.** Basketball and American Football already exist as playable modes.

---

## 9. Where we are worse — prioritized

Ordered by *leverage per day of work*, not by how fun the feature is. This deliberately differs from the backlog ordering in one place, argued in §9.1.

### 9.1 🔴 P0 — The tactical editor does nothing. At all.

**This is the finding that matters most, and it is worse than `expertAudit.md` records.**

The editor (`tactic-editor-view.js`, 311 lines) is a real tool: drag the ball onto a pitch zone, drag slot circles, localStorage drafts, version counter, five set-piece takers. It persists to `TeamTacticsProfile` via `TeamController GET/PUT /teams/{id}/tactics-editor`. `tactics_fallback.json` holds **1,012 rules = 11 slots × 46 ball states × 2 possession contexts**.

And then:

- **`TeamTacticsProfile` is read by nothing in the engine.** The only files that touch it are the repository, the initializer, the backup service and `TeamTacticsService`. No engine file.
- **All 12 `new MatchOrchestrator(...)` call sites** use the no-arg constructor, which is `this(state, new TacticsRules())`.
- `new TacticsRules()` opens **raw JDBC to a hardcoded `jdbc:postgresql://localhost:5432/sokker_db`** (a database name from a previous project), reads **team id 1**, and otherwise falls back to the bundled JSON. **Both teams share the same rules object.**
- **`FORMATION` is a hardcoded `public static final String FORMATION = "4-4-2"`.** The 10 formations in the UI resolve to 3 layouts in `FormationSlotCatalog`.

**Correction, owner ruling 2026-09-26 — the identical possession contexts are intentional, not a
defect.** `TeamTacticsService.mirrorWeHaveBallRules()` copies each `WE_HAVE_BALL` target onto the
matching `OPPONENT_HAS_BALL` rule on every save *and* every load, so all 506 slot × ball-state
pairs resolve to the same cell on purpose. **Do not "fix" this without a new owner decision.**
Two things follow from the decision, and they are the real observations:

- The tactical model is **one shape, not two.** Positioning does not change when possession
  changes. That is a legitimate simplification (Hattrick's orders are a partial version of it) but
  it is a design choice, and it means the editor gives the manager a *shape*, not a *tactic*.
- **The 46 "ball states" are 42 reachable cells + 4 dead ones.** `TacticsRules.ballStateKey()`
  only ever produces `CELL_r_c` on a 7×6 grid, so `ATTACK_LEFT_CORNER` and its three siblings are
  unreachable. Separately, the generated defaults map **every** ball state to the slot's formation
  anchor (`FormationSlotCatalog.buildDefaultRules`), so with default rules the shape does not react
  to the ball either. Whether *that* is intended has not been ruled on and is the open question.

**Why this is P0 and not "schedule it last":**

1. **Sokker's entire product identity is this feature.** Their retention reason, per their own players, is the 35-cell free-form editor. Ours is *better on paper* (46 states vs 35, and a real physics engine reading it) and **completely inert**.
2. **We already own ~80% of it.** The data model, the persistence, the editor UI and the fallback dataset all exist. The remaining work is a wiring and a sanitisation job, not a greenfield build.
3. **It is currently a lie in the product.** A manager drags a shape, saves it, and nothing happens. That is worse than not having the screen.
4. **It is the only P0 on this list that produces *football* decisions.** Everything else on this list produces spreadsheet decisions. Our engine is the thing we are actually good at, and right now the manager cannot influence it.
5. **It is the only P0 that makes an existing asset (the engine) more valuable.** Sprint 2's economy adds a spreadsheet around a match sim the user cannot steer.

**Note the tactical divergence to preserve:** Sokker and Hattrick both give you *no* mentality/tempo/pressing sliders — Sokker because geometry is the whole game, Hattrick because it has 7 named tactics with published levels. FM gives you ~30 instructions. We should ship **both**: wire the grid (Sokker's soul) *and* give the 6 existing `Tactics` fields (`aggression/defenseLine/pressing/possession/counterAttack/ballControl`, 3 of 5 currently unconsumed, all with no UI) real consumers. A manager who wants geometry gets geometry; a manager who wants sliders gets sliders.

**Estimated: 5–8 days.** Sanitise the 1,012 rules so the two possession contexts genuinely differ and the ball-response is real; read `TeamTacticsProfile` per team in `SimMatchService` and pass per-side `TacticsRules` into `MatchOrchestrator`; delete the raw-JDBC path and the `FORMATION` constant; make `FormationSlotCatalog` serve the chosen formation; give the 6 slider fields consumers and a UI; show a **pre-match tactical preview** (Hattrick's sector-ratings-as-you-build, or FM's Visualiser) so the manager can see what the shape does before kickoff.

**The backlog currently schedules "TACTICAL EDITOR REDESIGN" last with the note *"PLACEHOLDER ONLY… Owner will supply the brief."* That prioritisation looks wrong and should be revisited.**

### 9.2 🔴 P0 — There is no economy

`Team.budget` is one `Double`, only ever changed by a transfer fee. No ledger, no wages, no sponsors, no gate revenue, no prize money, no debt, no interest, no bankruptcy, no board, no failure state. The Finances page is **100% client-side fiction** — it fetches a hardcoded `budget: 1,200,000` that `DummyDataController` returns for *any* club, then computes a transfer budget, a wage budget and a monthly income in the browser, with a 6-month history fudged from `new Date()`.

`Player.earnings` is seeded with realistic values and **read by nothing.**

This is where both competitors are strongest and we are at zero. Note the irony worth naming: **Sokker's community complains that prize money is trivially small relative to wage bills, and Hattrick's biggest administrative complaint is that the board hoards your money.** Both are complaints about *tuning a loop that exists*. We do not have the loop.

Sprint 2 (S2.1–S2.6) in the backlog is correctly scoped and correctly prioritised. Two amendments:

- Per the PO decision that AI clubs never trade, S2.2's *"call it for every club"* and Sprint 3.6's *"enable AI↔AI transfers"* should be dropped as requirements. **But** the economy still needs to be *plausible on the pitch* for AI clubs — they need a wage bill and a gate so that a promoted club is not infinitely rich. Recommend: run the ledger for all clubs, but only allow **human** clubs to buy and sell.
- Port, do not rewrite. `footballtextmanager/CleanSheetService.applyRoundFinances:427-434` already has a wage bill (`earnings × 0.58`), sponsor income (`10000 + reputation × 180`) and gate income, and `CSClubMood` already models `boardConfidence/fanMood/mediaPressure/financialHealth`. The logic is proven; only the wiring is new.

### 9.3 🔴 P0 — The transfer market is a prose string and a dice roll

`Transfer.interestedTeams` is `Set<String>` containing `"Partizan offered €450000"`, parsed back with `indexOf(" offered €")` + `replaceAll("[^0-9.]","")`. A club named `Partizan United Youth` gets its offers wiped by a prefix-match dedupe. `isOfferAccepted` is one ratio-based probability clamped to [0.12, 0.82]. `direct-buy` on a listed player completes with **no acceptance check at all**. `acceptBestOffer` sorts descending and takes the first — **the seller cannot choose.**

Against Sokker: 2-day auction, 2.5% listing fee, 8% + profit-based sale tax, player-refuses-to-be-listed, proxy/max bids, anti-snipe extension, 3 advertisements per club, and a community that has built a **historical transfers database** around it. Sokker's auction is the reason managers talk to each other.

The PO has already decided the shape: **`TransferListing` + `TransferBid` entities** (which permanently kills the `Set<String>` encoding), proxy/max bids, capped anti-snipe extension, resolution, plus direct club-to-club agreement as a later task in the same sprint, and *"auction closes unsold below reserve → player stays listed and can be re-listed."*

**Blocker, already identified:** `GameClock` is a singleton row and `POST /simulation/week/advance` advances the entire world with no ownership check. An auction needs a trustworthy deadline. This must be resolved first.

**Recommended Sokker mechanics to adopt beyond the PO decision:**
- **Listing fee as a % of the asking price** — it is a real anti-spam and anti-dumping tax, and it is 2 lines of code.
- **Player can refuse to be listed** — refusal chance rises with few appearances for your club and with how many of your players you listed recently. This is the single cheapest anti-speculation mechanic in the genre and it is thematically perfect for a human-only market.
- **Profit-based sale tax scaled by time at the club.** Kills day-trading without needing transfer windows.

**Recommended Sokker mechanic to reject:** their 2-day auction window is acknowledged by their own community to be hostile to global time zones. Make the window an integer number of **in-game days**, driven by an extended `/api/game-clock` that exposes the in-game date — not wall-clock hours.

### 9.4 🟠 P1 — Training has no trade-offs

Correct growth formula (talent × age × level-resistance × advanced-2× × random), 19 runs/season, idempotency guard landed. Design space: **two knobs** — four role-bucket dropdowns and a 10-slot advanced list. No intensity, no cost, no training-induced fatigue, **no training-induced injury risk**, no per-player focus, no facilities, no mentoring.

Result: **training is strictly dominant and free.** The only decision is *which 10 of your 20–25 players get 2× growth* — one decision, not a system.

Against Hattrick: intensity 0–5 trading +3.5%/level of training speed against **+2.5%/level of injury risk**, with published per-type trainee caps. Against FM: 11 individual focus areas, Normal/Half/Double intensity, 9 tactical-familiarity sub-categories remembered across all tactics.

Sprint 4 is correctly scoped. **One thing it should copy from Sokker rather than FM:** minute-proportional, position-locked training with a **90-minute weekly cap** and *"only the last match's role counts."* It is a five-line rule that produces a genuine weekly optimisation problem and it is the mechanic that makes Sokker's training feel like management rather than administration.

### 9.5 🟠 P1 — No meta-layer, therefore no stakes

No `BoardExpectation`, no manager trust, **no way to be sacked**, no dressing room, no season goal setting, no press conferences, no retirement. `MoraleSystem.getConfidenceModifier()` has zero callers. **`Player.form` is a creation-time constant**, so the `formBoost` in `PlayerDTO:90` (±8.1 OVR) and `(form − 6.0) × 1.2` in `MatchRatingCalculator:51` are **constant offsets that never respond to a run** — a correctness smell in the rating formulas, not just a missing feature.

FM's answer is Board Confidence 1–20 split into Club Vision / Matches / Transfers / Tactics / Squad Management, with separate Supporter Confidence driven by a Supporter Profile — *"a lower-league club's fanbase skews fewer Fair Weather, so slow progress is forgiven."*

Sokker's answer is to deliberately have none, plus **supporter mood (7 levels) and supporter expectations (9 levels, from "totally outclassed" to "come to eat popcorn")** — and a multi-year community campaign asking for board expectations anyway.

**Recommendation:** take FM's faceted board trust (it is the better design and it produces legible feedback) and Sokker's **supporter expectations** as a separate, cheaper layer. Supporter expectations are ~30 lines of enum + one mood modifier and they deliver most of the "my team is being humiliated" feeling for a fraction of the cost. A `ClubMood` entity ported from `CSClubMood` gives us all four axes at once.

### 9.6 🟠 P1 — Registration is broken, so nobody can join

`register.html` + `register.js` POST `/auth/register`. **`UserController` has only `/login` and `/me`.** `RegistrationService` is fully written — reserve a non-human club, approve/reject, set `humanControlled` — and has **zero callers**. The admin Approve/Reject buttons in the community page POST `/admin/registration-requests/{id}/{action}`, which also does not exist.

**This is a 1-day fix that gates the entire multiplayer proposition.** A game whose differentiator is a human transfer market, and which cannot register a second human, currently has no market. It should be treated as P0 for that reason alone.

### 9.7 🟡 P2 — The world is one country and has no cup

- **31 leagues / 5 tiers / 310 clubs — all Serbian.** The other 8 seeded countries are rows with reputation and a `youthRating` that **nothing reads** (a purpose-built dead hook for the scouting feature in Sprint 5).
- **`Kup Srbije` is created with `teamsPerCompetition=64, hasSeeding=true` and never populated.** The fixture loop filters `type == LEAGUE`. The SPA's cup view calls a `DummyDataController` literal. There is no cup.
- **No national teams.** `country-view.js` renders *"…will be added here once backend endpoints are ready."*
- No retirement, so the player pool only grows and never turns over.

Sokker's **×3 leagues per level** pyramid and its 12-team leagues are a better shape for a 310-club world than our 5 tiers of 10/16/32. But this is genuinely lower priority than §9.1–9.6: a cup matters far less than a working transfer market between the leagues we already have.

### 9.8 🟡 P2 — No analytics layer (the missed opportunity, not just a gap)

We collect 20+ structured fields per event, possession %, and possession chains — and surface almost none of it. FM's Data Hub (xG, xA, PPDA, Field Tilt, progressive passes, momentum, pass maps) is **the** reason FM feels like a modern football product, and in FM26 those numbers **drive the advice your assistant gives you**, closing the loop.

**Nobody in the browser-manager category has this.** Hattrick has 7 sector ratings and HatStats. Sokker has *deliberately nothing*. Because our engine is a real spatial simulation, we can compute **genuine** xG (shot location, angle, defensive pressure at the moment of the strike), genuine PPDA, genuine field tilt, genuine progressive passes — all *from the same tick data that drives the animation*, so the numbers and the pictures cannot disagree.

This is the one place where the "worse UI than FM" tradeoff is worth paying: our per-match analytics will be **more honest** than FM's, because FM's come from a data provider while ours come from the simulation the user just watched.

### 9.9 🟡 P2 — 13 dead routes, 6 of which crash

`results, cup, international, friendlies, playerStats, teamStats, topScorers, topAssists, coaches, events, analytics, upcoming, training` — no menu entry, no action row. **6 crash into a generic "API Error" card** because they `await response.json()` without checking `response.ok`. Plus: a permanently broken average-rating display (`fetchPlayerRatingSummary` called with 1 arg on both player pages), `OFK Omladinac` hardcoded in a string compare, and a `loadPage` that fires twice per sidebar click.

Cheap to fix, and it matters disproportionately: **a broken page is worse than a missing page** for a first-time player, and Sokker's #1 weakness is onboarding.

---

## 10. What to add — intake list

Drawn from the three competitors, deduplicated, with our judgement on each. Effort is my estimate in dev-days for this codebase.

### 10.1 From Sokker (highest value per day)

| # | Feature | Why | Est |
|---|---|---|---:|
| 1 | **Wire the 46-cell grid to the engine** (§9.1) | Our identity gap; 80% built | 5–8 |
| 2 | **Position-locked, minute-proportional training with a 90-min weekly cap; "only the last match's role counts"** | Turns training from 2 knobs into a real weekly decision. ~1 day for the rule, the depth is Sprint 4 | 1–2 |
| 3 | **Player can refuse to be listed** | Cheapest anti-speculation mechanic in the genre; perfect for a human-only market | 1 |
| 4 | **Supporter mood (7) + supporter expectations (9)** | ~30 lines, delivers most of the pressure feeling | 1–2 |
| 5 | **Listing fee as % of asking price** | Anti-spam/anti-dumping tax, 2 lines | 0.5 |
| 6 | **Lineup legality caps (1 GK / 5 DEF / 5 MID / 3 ATT) with a real consequence** | Prevents degenerate XIs; Sokker's consequence is losing training, which is elegant | 1 |
| 7 | **Junior skill hiding + ±2–3 level scouting estimate** | Doubles academy depth for ~1 day. Currently we show the exact value | 1 |
| 8 | **Graduation caps** (junior skills top out at *outstanding*, stamina at *very good*) | Stops the academy from producing 18-skill 17-year-olds, which would flatten the whole economy | 0.5 |
| 9 | **3 transfer advertisements per club; advertising unlocks skill visibility** | Gives the market a discovery mechanic without scouting | 1–2 |
| 10 | **Zero-consequence exhibition mode** (Sokker's "Arcade") | Multiplies the value of 310 clubs for near-zero balance impact; also our best onboarding tool | 1 |
| 11 | **Corners decided by grid geometry, not a nominated taker** | A *reason* for the tactical editor to matter at set pieces | 0.5 |
| 12 | **Pitch dimensions (64–78 m × 95–110 m) as a tactical lever** | Cheap, and it makes the pitch real | 1–2 |
| 13 | **A documented, blessed read API** (`/apidoc`) | Sokker and Hattrick both gate their tool ecosystems on this. Ours costs one controller | 1–2 |
| 14 | **World ranking points that drive cup entry and draw seeding** | Makes the world legible and creates a reason to care about results | 2–3 |
| 15 | **Coach/scout market with per-skill training ratings** | Replaces fake staff with something that has a real effect on training | 3–5 |
| 16 | **In-game date + day counter on `/api/game-clock`** | Required for any auction window; explicitly identified as the blocker | 1 |

### 10.2 From Hattrick

| # | Feature | Why | Est |
|---|---|---|---:|
| 17 | **Published, auditable tactical effect** — show the manager, before kickoff, what their shape and tactic level will do | Hattrick's single best idea: *auditability*. It converts a black box into a decision. Pairs perfectly with §9.1 | 3–5 |
| 18 | **Named tactics with derived levels** (Pressing / Counter-Attack / Attack in the Middle / Attack on Wings / Long Shots) | Gives the 6 existing `Tactics` slider fields real consumers. Sokker has none; FM has ~30; Hattrick has 7 and they work | 3–4 |
| 19 | **Specialities + special events** (7 types) | Nearly free once the engine resolves actions individually — which ours already does. A winger with *Quick* gets a genuine dribble event | 2–3 |
| 20 | **In-match dynamics: pullback when leading, and a per-goal-of-lead swing** (`−6.25% attack / +5% defence`, doubling per goal) | Makes protecting a lead a *decision*. Sokker has no equivalent | 1–2 |
| 21 | **Weather + pitch condition affecting skills, bounces and injury probability** | Sokker already has pitch condition; ours has a `pitchCondition` field with no consumer | 1–2 |
| 22 | **Man marking order** | The one genuinely novel Hattrick mechanic; a real tactical tool | 2–3 |
| 23 | **Experience + nerves in cup matches** | Free drama, costs one stat and one modifier | 1 |
| 24 | **Formation experience + confusion** | Solves a real problem we will hit: a manager who saves a bizarre shape should pay for it | 2 |
| 25 | **Promotion/relegation qualifiers as a distinct phase** | We have a 2-leg playoff; Hattrick's 1st/2nd-up + 3rd/4th-into-qualifier is a better drama structure | 2 |
| 26 | **Board cash ceiling with a weekly release rate** | Turns "spend it or lose it" into a real annual decision. Aimed squarely at Sokker's "hoarding is penalised because the board hoards too" complaint — which we can fix on day one | 2–3 |
| 27 | **A retirement age** | Prevents an unbounded player pool. FM has it, Sokker and Hattrick famously do not (Hattrick's record holder is 93) | 1 |
| 28 | **Injury risk as a per-team-per-match budget, not per-player** | Hattrick's 0.4/team/match is better calibrated than a per-player roll | 1 |

### 10.3 From Football Manager

| # | Feature | Why | Est |
|---|---|---|---:|
| 29 | **Individual training focus per player** (11 areas) | Already S4.1, called "the headline feature". Agreed | 4–6 |
| 30 | **Training intensity vs injury/fatigue risk** | Already S4.2. Agreed | 2–3 |
| 31 | **Facilities 1–20** (training, youth, scouting, medical) | Already S4.4. Gives money somewhere to go | 3–4 |
| 32 | **Faceted board confidence (1–20 × 5 facets) + sacking** | Already S2.4. FM's best meta-layer idea | 4–6 |
| 33 | **Honest per-match analytics: xG, xA, PPDA, field tilt, progressive passes, momentum** | §9.8. The differentiator. Nobody in the browser category has it | 6–10 |
| 34 | **Scouting ranges that narrow with assignment** | Already S5.1/S5.2. Pairs with Sokker's estimate-accuracy idea | 4–6 |
| 35 | **Set-piece routines with role categories and a priority list** | FM's genuine tactical innovation; a Set Piece Coach staff role is the delivery mechanism | 4–6 |
| 36 | **Manager attribute drift + a Style Focus slider** | Cheap character; gives the manager a build | 2 |
| 37 | **A `Responsibilities` delegation screen** | Directly attacks our worst risk (grind) by letting a user hand away parts of the job. FM's key insight: delegation *scales with staff quality*, so it is not a downgrade | 2–3 |

**Explicitly NOT recommended from FM:** the inbox-as-primary-information-channel, press conferences, the Dynamic Manager Timeline, and the weekly-schedule re-entry ritual. All four are the most-cited reasons FM's saves stall in year 3–5, and all four are pure overhead for a game that should be playable in 30 minutes a week. We have an explicit competitive advantage in time commitment; do not give it away.

### 10.4 Sequencing recommendation

The backlog's critical path is `S0 → S1 → S2 → S4 → S3 → S5 → S8`, ~79–95 days total. **Two amendments:**

**Amendment 1 — insert tactics wiring immediately after Sprint 1, before Sprint 2.** Rationale in §9.1. Concretely: it is 5–8 days, it is the product's identity, and it is the only P0 that makes the engine (our actual moat) respond to the user. Everything else on this list surrounds a match the user cannot influence.

**Amendment 2 — move registration to Sprint 0.** It is a 1-day fix (`RegistrationService` is already written and has zero callers; only the controller and the two client calls are missing) and it gates the multiplayer market that is the product's entire economy premise.

Proposed order after that:

```
S0 (4d)  + registration (1d)                    ← multiplayer can begin
S1 (20d)                                         ← engine: done
▶ TACTICS WIRING (5-8d)                          ← identity; makes the engine steerable
S2 (18d) economy, wages, board, morale           ← the manager game
S3 (15d) auction market + contracts              ← the social centre
S4 (15d) training v2                             ← depth where Sokker is deepest
S5 (10d) juniors v2 + scouting                   ← the long game
S8 (12d) presentation, cup, internationals, infra
S6 (1d)  parallel
```

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

### 11.2 How much meta-layer pressure?

Sokker deliberately has none and its veterans defend that. Its community has been asking for board expectations for years anyway. FM has a pressure loop so heavy it drives players to the editor and the delegate button.

We have **none**, which means nothing can go wrong. The question is whether we add FM's faceted board trust (full pressure loop, sacking, real stakes) or Sokker's supporter expectations (light pressure, no sacking, ~30 lines).

My recommendation: **supporter expectations first, board trust second.** Ship the cheap layer, playtest it, then decide whether the expensive one is wanted. Board trust without an economy is meaningless, and without tactics it is unjust — a manager will be judged on tactics they cannot execute.

### 11.3 AI clubs: plausible on the pitch, frozen off it?

The PO decision is that AI clubs never buy or sell. That is correct for the market. But two consequences need explicit decisions:

1. **AI squads are frozen forever** — no growth, no decline, no ageing out, no retirement. The pyramid never evolves and promotion has no long-term meaning. Recommend: AI clubs **train and age** (S4.7) but do not trade. That preserves a living pyramid while keeping the market human.
2. **AI club finances.** If AI clubs have no economy, a promoted club is as rich as a champion. Recommend: run the ledger for all clubs so AI finances are *plausible*, but gate only the **buy/sell** actions behind `humanControlled`.

### 11.4 Do we want a live world or a solo career?

Our week advance is a button. Both competitors are asynchronous multiplayer worlds that advance on a schedule, and Sokker's whole appeal is that other managers' decisions are the opposition. But a global singleton clock that any player can advance is a correctness bug, not a design.

Decision needed before the auction: **scheduled advance (server-side job) vs. per-player deadlines.** A scheduled advance is simpler and matches both competitors. It is Sprint 8.6 work, and the auction cannot be built before it.

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
"discovers" it and re-breaks the decision.

Two factual observations that survive the ruling: 46 ball states = 42 reachable grid cells + 4
unreachable corner states, and the GK resolving to only 2 distinct targets follows from the
generated defaults mapping every ball state to the slot's anchor.

**Corrections to `AGENTS.md` found along the way** (it is stale, and `sprintBacklog.md` S6.5 already schedules a rewrite): the `demo/service/` and `newLogic/sim/` package trees it documents as the product have been restructured; the `commonmanager/` routing is wrong; `pages.js` is 792 lines, not "5200+"; `/start-realistic-demo → RealisticMatchEngine` no longer exists; `CompetitionController` has 0 routes.

---

## 13. One paragraph

Sokker has spent 22 years building a management game on an engine worse than ours, and 40,000 clubs play it daily; Hattrick has spent 29 years building an economy on a spreadsheet, and 14.7 million accounts have played it. Both spent that time on money, contracts, markets, boards and social pressure, and both are regarded by their own players as too *hard* to learn and too *grindy* to live in. We have the engine they never built, and none of the game they built around it — and our tactics screen, which is the one feature that could make the engine matter, currently writes to a table nothing reads. Fix that, then build the loop, and we have something neither of them can offer: a match where the animation and the statistics are the same truth.
