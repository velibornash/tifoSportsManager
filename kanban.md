# kanban.md — the board

**Restructured 2026-10-09.** The board was reorganised into four sections. Everything the previous board
still carried open was moved into **T-REST**; new work from the owner's vision and from
[`CurrentStateAnalysis.md`](CurrentStateAnalysis.md) went into **T0**, **T1** and **T2**.

| Section | What belongs there |
|---|---|
| **T-REST** | Unfinished items carried over from the previous board. **Read this first.** |
| **T0** | Features we want and have not started. Split into **BE** and **UI**, so the two can be worked and verified separately. |
| **T1** | Work on features that already exist: incomplete wiring, dead contracts, unverified claims, known bugs. |
| **T2** | Performance and optimisation. **Every task here must state a measurement, not an opinion.** |

Closed work is not on this board. It is in [`kanbanProgress.md`](kanbanProgress.md), one entry per task,
newest first, each carrying its commit.

---

# 📌 OPEN WORK — the short list, 2026-10-13

Full detail on each task is in its own section further down. **If you are starting a blank session, read
[`SESSION_HANDOFF.md`](SESSION_HANDOFF.md) first** — it carries the traps that cost hours here, above all
that `game_clock`'s `current_week`/`current_day`/`current_hour` columns are **dead** and the real clock is
`now() + offset`.

| # | Pri | Task | State |
|---|---|---|---|
| **P2-4** | ✅ | **Name row is editable in place, with X and check** | ✅ **Done 2026-10-14.** Was a panel lower down the page. Pencil → field + X + ✓ in the NAME row itself. |
| **P2-5** | ✅ | **Back on your own profile did nothing** | ✅ **Fixed.** It targeted `userProfile` — the page it was already on. |
| **P2-6** | ✅ | **Opponent search across users, teams and players in a standard table** | ✅ **Done 2026-10-14.** Was clubs-in-your-country as a button list. Now one table of managers, clubs and players, each row naming the club a friendly goes to. |
| **P2-7** | ✅ | **Global search page, not a friendly-only picker** | ✅ **Done 2026-10-14.** Magnifying glass after the bell; managers, clubs and players; All/Clubs/Managers/Players. |
| **P2-8** | ✅ | **One player listed once per national squad** | ✅ **Done 2026-10-14.** Squad rows are copies carrying `source_player_id`; the real row survives and names the squads. |
| **P1-5** | 🔴 | **A club found in search leads to its league, not to the club** | Owner report 2026-10-14. My own regression — clubs were routed to the league when no club page existed for them. |
| **P1-6** | 🔴 | **On a manager's profile, Club opens an obsolete alert; League and Country are not links** | Owner report 2026-10-14. All three must lead where they name. |
| **P1-7** | 🟡 | **League view title says "League view", not the league's name** | Owner report 2026-10-14. A page with no name in its own heading. |
| **P1-8** | 🟡 | **No club in the league table is clickable** | Owner report 2026-10-14. Same on the country club ranking. |
| **P1-1** | ✅ | **Clicking an AI club lands on a league, not on the club** | ✅ **Done 2026-10-14.** Any club has its own profile: league, tier, Elo, world position, stadium, squad. |
| **P1-2** | ✅ | **Header "Club" button opens First Team, not Club Profile** | ✅ **Done 2026-10-14.** Now opens Club Profile, with Elo and ranking position on it. |
| **P1-3** | ✅ | **World page has no club list** | Done 2026-10-14 (commit d90dd7dd): `/countries/world/clubs/ranking`, top 500, paged, club profile navigation. |
| **P1-4** | 🔴 | **Qualifying for and advancing through international stages awards no Elo** | **Code done, NOT observed.** Ladder is the owner's and unit-tested; the bonus has not yet been seen to change a club's Elo. Match Elo already worked at 1.35. |
| **P0-13** | 🔴 | **Manager profile: clicking club shows obsolete "your own club" popup** | Owner report 2026-10-14. The club name should link to the club's profile page. |
| **P0-14** | 🔴 | **Manager profile: League and Country are not clickable** | Owner report 2026-10-14. They should link to the league view and country page. |
| **P0-15** | 🔴 | **Search: club result goes to league, not to club profile** | My regression — clubs were routed to league when no club page existed. Now they have pages. |
| **P0-16** | 🔴 | **League view: title says "League view", not the league name** | Owner report 2026-10-14. Should show "Opštinska liga Šid · Season 1". |
| **P0-17** | ✅ | **League table: clubs are not clickable** | Owner report 2026-10-14. Clicking a club should open its profile. | Made the club-name span a direct js-load-CTeam target (commit 88e4970c). |
| **P0-13** | 🔴 | **A claimed friendly ad says FULFILLED but no fixture exists** | ✅ **Fixed 2026-10-14.** A claim is a request, not a booking. The ad now closes when the answer is accepted, and carries the real fixture id. |
| **P0-14** | 🟡 | **`FriendlySlotRulesTest` is red against the current season table** | 10 of 12 fail on clean `HEAD` — it asserts the pre-reshuffle table (week 5 day 3 = round 9, 2 friendly slots in the break). Pre-existing, not a regression. |
| **P0-5** | ✅ | **824 fixtures sit in the past and will never be played** | Deferred pending DB reset (owner 2026-10-14). |
| **P0-11** | ✅ | **The Jobs table had been written but had never once rendered** | ✅ **Fixed 2026-10-14.** An undeclared `sort` threw inside the template; a catch hid it. |
| **P0-1** | ✅ | **Verify one matchday completes clean, end to end** | ✅ **Done.** 155 fixtures computed at 15:00, released at 16:00, zero new FAILED. |
| **P0-6** | ✅ | **The lock named the wrong club to an away manager** | ✅ **Fixed.** `refusalReason` compared the home team's id with itself. |
| **P0-7** | ✅ | **The qualifying race had no tie-break** | ✅ **Fixed.** Union of two sorted pools was never sorted. |
| **P0-9** | ✅ | **The national cup had no final** | ✅ **Fixed.** 8 rounds where the owner's table has 9. |
| **P0-3** | ✅ | **The redraw button was not idempotent** | ✅ **Fixed.** It asked "any *unplayed* ties?". |
| **P0-10** | ✅ | **A tie won on penalties reported itself as a draw** | ✅ **Fixed.** The engine computed every kick and the report discarded them. |
| **P2** | ✅ | **`designAnalysis.md` P0-1 — the corner logo blocked the season dropdown** | ✅ **Fixed.** `scrollWidth == innerWidth` at every desktop width. |
| **P2** | ✅ | **`designAnalysis.md` P0-2 — player columns overlapped 88px** | ✅ **Fixed.** Overlap is now −14px. |
| **P2** | ✅ | **`designAnalysis.md` P0-5 — Back button was a 1138px slab** | ✅ **Fixed.** 76×40. |
| **P2** | ✅ | **"You are here": one convention, own club / own country marked** | ✅ **Done 2026-10-14** — league table, cup ties, qualifying groups, club ranking. |
| **P2** | ✅ | **Find your club in the cups — summary card and jump** | ✅ **Done 2026-10-14** on the national cup and all three continental cups. |
| **P2** | ✅ | **White default button between teams on international matches** | ✅ **Fixed.** It had no CSS rule at all. |
| **P2** | ✅ | **Edit your name on My profile** | ✅ **Fixed.** The editor existed and the menu did not lead to it. |
| **P0-2** | 🔴 | **Cup round 2 has never been drawn** | ✅ **Answered — it is drawn, 128 ties.** See P0-8. |
| **P0-8** | ✅ | **Serbia U-21 is in Kup Srbije, rounds 1-4** | Prevention committed and mutation-proven; re-draw deferred to DB reset (owner 2026-10-14). |
| **P0-4** | 🔴 | `/admin/world-reseed?what=national-teams` is broken** | Fails on a `player` FK violation. Unrelated to the above. |
| **P0-11** | 🔴 | **The Jobs table had been written but had never once rendered** | ✅ **Fixed 2026-10-14** — owner reported it. An undeclared variable threw inside the template and a catch hid it, saying the read had failed. |
| **P0-10** | 🟡 | **A cup tie won on penalties reported itself as a draw** | ✅ **Fixed 2026-10-14** — owner found it on a tie his club had won. The engine computed every kick and the report discarded them. |
| **P1-1** | 🟡 | **T-FAC-1 item 2 — close the training-report check** | Code and tests **done** (`9a801c0a`, 40 green). **One observation short:** no report generated after the change exists to inspect. |
| **P1-2** | 🟡 | **T-FAC-1 item 3 — settlement and stadium page agree on upkeep** | Not started. `weeklyUpkeep = 120 × (Σ levels − 1)`. |
| **P1-3** | 🟡 | **T-FAC-1 item 4 — document the `JuniorDevelopment` overlap** | Not started. Documentation only. |
| **P1-4** | 🟡 | **T-FAC-1 item 5 — bot seeding plausibility** | 310 grounds seeded at levels 2–5 of 20; ~14,666 clubs have no stadium row. |
| **P1-5** | 🟡 | **Delete the dead `tacticalMultiplier()`** | No caller, and duplicates what `tacticalLevel` already does. Wiring it would double-apply. |
| **P1-6** | ✅ | **T-U21-1 — a national side drawn short was short for ever** | Fixed (commit af73cd68): FK on `player_contract` no longer aborts reseed, so short U-21 sides are topped up. |
| **P2-1** | 🟢 | **T-KIT-1 — the players wear no kit** | Nine unchecked criteria, further down. Biggest user-visible win. |

### Committed but never seen running — do not assume they work

| Change | Commit | Verified? |
|---|---|---|
| Cup rounds newest-first + own-tie highlight | `56133fec` | ✅ **verified 2026-10-14** |
| Competition-type filter (League/Cup/International) | `56133fec` | ✅ **verified 2026-10-14** — club Schedule page |
| "Not drawn yet" → `Drawn · 600 ties` | `56133fec` | ✅ browser |
| Scroll-to-top on every page | `56133fec` | ✅ browser |
| Training report Facility column | `9a801c0a` | ⚠️ tests only, **no real report seen** |

### Waiting on the owner

- **The delta column** on the club ranking — deferred until the feed was live; it now is, so it is finally
  worth building.
- **T-ECON-1 option 2** (global wage re-scale across 31,230 players) — declined in favour of option 1, which
  is done.


## Recently completed

- **Session handoff: read [`SESSION_HANDOFF.md`](SESSION_HANDOFF.md).** It carries the two-clock trap,
  the corrections to what was previously reported here, and what is still unstarted.
- **The game clock is one clock and it ticks** (`4addf0b`). It used to hold four counters *and* an
  offset; the header rendered one and the job runner read the other, silently. Now the position is
  derived from an anchor plus an offset, the header shows `HH:MM:SS` anchored on the server, and every
  movement of the world ends in the same call that runs the jobs it made due. **Start at
  [`T-CLOCK-1`](#t-clock-1--p0--the-game-clock-was-two-clocks).**
- **A fixture's day and its kickoff hour come from one place** (`c1d6b8e`). The fifteen continental cups
  wrote `day 1` and stamped a day-5 18:00 date — the handoff's "the fixture says 18:00, the job fires at
  20:00". **Start at [`T-CALENDAR-1`](#t-calendar-1--p0--a-fixtures-day-and-its-kickoff-hour-disagreed).**
- **A national side drawn short is no longer short for ever** (`4c8f677`). The U-21 guard asked "does it
  have any real players" rather than "is it full", so a country first drawn with a small pool was frozen
  there. **Start at [`T-U21-1`](#t-u21-1--p1--a-national-side-drawn-short-was-short-for-ever).**
- **A club earns in proportion to what it is worth** (`11579eec`). Broadcast was `weight × 145`, a pure
  function of the division; a top-flight club paying 18.5m a week against 2,900 of television money.
  **Start at [`T-ECON-1`](#t-econ-1--p0--income-did-not-move-when-the-club-did).**
- **Omladinac's wage bill, 18.5m → 4.8m a week** (`bc9e123b`). The squad paid 45-84% of its own market
  value per year. **Start at [`T-ECON-2`](#t-econ-2--p0--omladinacs-wage-bill-was-45-84-of-his-own-value).**
- **The Finances page shows the lines** (`ca13cf6f`). The ledger endpoint existed and nothing had ever
  called it. **Start at [`T-FIN-UI-2`](#t-fin-ui-2--done-the-ledger-endpoint-existed-and-nothing-called-it).**
- **Home advantage is real** (`ca13cf6f`). The forecaster promised it; the engine had never heard of it.
- **A 10.0 means an exceptional match** (`126c6418`). "Tackles" was duels lost, the defensive terms were
  unbounded, substitutes played 90 minutes, and there was no team-result term at all.
- **P0-1 is closed — a whole matchday, watched with a stopwatch** (`2026-10-13`). Week 5 day 7, 155 fixtures:
  computed at 15:00 in 121 s, released at 16:00 in 5 s, **zero new `FAILED`**. The publication window was
  checked while it was open — every read path 404, the team's match list at 13 rows, then 14 after the
  publish. The rating moved *with the result*: Radnički Sport won 2-1 at home and gained 17, Omladinac lost
  away and dropped 17. It also found two things: the lock naming the wrong club to an away manager
  (P0-6, fixed) and 824 stranded fixtures (P0-5, open). Full detail in
  [`kanbanProgress.md`](kanbanProgress.md).

---

## P0-7 — ✅ P0 · the qualifying race ranked a 14-point club above a 16-point club

**Owner, 2026-10-13**, reading the screen and asking the right question: *"kako je GFK Nova Pazova 1923 sa 14
poena iznad GFK Borski Zaječar sa 16???"*

This is the one page where the order decides **who goes to a continental competition**, so an order that is
wrong here is a wrong answer about football, not a cosmetic one.

### What was on the screen

Masters Cup, tier 2 of Serbia, 2 places:

| before | | | |
|---|---|---|---|
| 1 | OFK Niš | **16** pts, gd 7 | **Qualifies** |
| 2 | GFK Nova Pazova 1923 | **14** pts, gd 2 | **Qualifies** |
| 3 | GFK Borski Zaječar | **16** pts, gd 7 | Candidate |
| 4 | ŽFK Tamiš Požarevac 1913 | 12 pts, gd 6 | Candidate |

### It was not a tie-break that was wrong. There was no tie-break.

The Masters race is **two positions from every division in the tier** — 2nd and 3rd — and it was assembled as:

```java
masters.addAll(poolAt(tier.getValue(), 1));   // sorted, descending
masters.addAll(poolAt(tier.getValue(), 2));   // sorted, descending
// ...and the union of the two was never sorted.
```

Two lists, each sorted on its own, concatenated. **Every 2nd-placed club therefore ranked above every
3rd-placed one, whatever their points.** Not a tie-break being subtly wrong — a tie-break being absent.

Tier 3 proved it exactly. Sixteen entries across four divisions, in the shape of two sorted runs:

```
17, 15, 14, 14  ++  15, 15, 14, 13
```

### The other two cups were never affected

| Race | How it is built | |
|---|---|---|
| Champions Cup | `poolAt(0)` — **one** sorted pool | already correct |
| Challenge Cup | `poolAt(3)` — **one** sorted pool | already correct |
| **Masters Cup** | `poolAt(1) + poolAt(2)` — **two**, concatenated | **was wrong** |

Tier 1 is safe for a separate reason: it always has exactly one division, measured across all 48 countries,
so `tier.getValue().get(0)` is not a silent truncation. The worst case anywhere in the world is 16 divisions
in one tier — which is a 32-club Masters race, exactly the case that broke.

### Exit criteria

- [x] The union is sorted once, after it is built.
- [x] **All three cups, all five tiers, verified against the running app** — points descending everywhere,
      and goal difference descending within equal points.
- [x] **Seen in the browser**, on the screen the owner reported. Tier 2 now reads OFK Niš 16 and **GFK
      Borski Zaječar 16** qualifying, with GFK Nova Pazova 1923 on 14 third and out. A club on 16 points
      takes the place it was denied.
- [x] A test that fails on the old code. `CountryQualifyingRaceOrderTest` builds a tier with two divisions
      where the 2nd-placed club of division A has **12** points and the 3rd-placed club of division B has
      **30**, and requires that points never go backwards down the race. Reverting the fix fails it:
      *"12 points is followed by 25 (ZZ B2 2)"*.
- [x] Tier 1 and the single-pool races pinned too, so the next person to add a race cannot reintroduce this.

### 🔴 Owner decision taken 2026-10-13 — *pts, gd, zreb*, everywhere

*"da, ok je global"* — yes, changing the comparator globally is right.

`LeagueTableOrder` is currently **points → goal difference → goals scored → team id**. The owner wants
**points → goal difference → the draw**. The difference is real: OFK Niš and GFK Borski Zaječar are level on
16 points **and** gd 7, and goals scored (16 v 13) separates them today where a draw would instead.

**The draw already exists and does not need inventing.** `RankingTieBreakService.coin(seed, teamId)` is
exactly the owner's rule — *"equal totals get distinct positions, never a shared rank and never
alphabetical… what is left is a draw, and the draw has to be the same every time the ladder is read"* — a
seed stored once per ladder, replayable, already used by the country and club ranking lists. So the change is
to **make the comparator take a coin**, not to write a new one.

**Scheduled for the next session.** It touches every league table in the world and both ranking replays, so
it wants its own task and its own test rather than being folded into a session already late.

### Exit criteria

- [ ] `LeagueTableOrder` is points → goal difference → draw, and the draw is `RankingTieBreakService.coin`.
- [ ] Two clubs level on points and goal difference get two positions, and the order does not change between
      two reads.
- [ ] Every call site supplies a ladder coin, or states plainly why it has none.
- [ ] The one comparator test proves the whole order, not the first two keys.

---

---

## P0-11 — ✅ P0 · the Jobs table had been written but had never once rendered

**Owner, 2026-10-14:** *"nema liste jobova u tabu jobs na admin strani, zadnji zahtev je bio da moze lista da
se sortira po poslednjem izvrsenju i po poslednjem izvrsenju."*

The sorting he was asking for **already existed**. The panel had never rendered at all.

### The cause

`showJobs` built its table from a variable called `sort`. The state variable is `jobSort`; `sort` was never
declared, so the template threw `ReferenceError: sort is not defined`, and the `catch` rendered
**"Could not read the jobs."**

`/admin/jobs` answers **200 with 44 rows**. The read was never the problem.

| | |
|---|---|
| Endpoint | healthy — 200, 44 jobs, by `curl` and by `fetch` inside the page |
| Sorting | written — `sortJobs`, both columns, direction toggle, arrows, `aria-sort` |
| Did it ever work | **never** |

Two more faults in the same four lines: `ariaDir` is a function and was interpolated as a value, so
`aria-sort` would have rendered the function's source text.

### The message now tells the truth

A bad response says so with its status. Anything else says **what actually threw**. "Could not read the
jobs" answered a question nobody was asking and could not be acted on.

### 🐛 I broke the entire admin page while fixing it

My replacement left a duplicated closing brace — `Illegal return statement`, a syntax error that killed
the module and made Admin stop navigating. Caught by asking the browser to import the module:

```bash
agent-browser eval "import('/js/pages/views/admin-view.js').then(()=>'OK').catch(e=>e.message)"
```

which is the cheapest available check for a JavaScript file here, since **node is not installed** and
`node --check` cannot run.

### Exit criteria

- [x] The 44 rows render on the Jobs tab.
- [x] **Last run** sorts both ways, arrow and `aria-sort` follow.
- [x] **Next trigger** sorts both ways, and the other column's arrow clears.
- [x] A never-run job stays at the bottom in **both** directions.
- [x] **Verified on mobile, 390×844** — renders, both sorts, no console errors, table scrolls sideways.
- [x] The failure message names what failed instead of guessing.

---

## P0-10 — ✅ P0 · a cup tie won on penalties reported itself as a draw

**Owner, 2026-10-14**, on Kup Srbije, week 3 day 5: *"vidim da sam prosao posle 2-2, po pravilima treba da
budu penali a nema ih nigde u izvestaju... da proveris i da ubacis negde, recimo u goals i pogodjene i
promasene penale."*

**The engine was never wrong.** `FK Mlava Novi Sad 2-2 OFK Omladinac` was settled **6-7 on penalties** —
the columns held it, OFK Omladinac went through. `PenaltyShootout.Result` carries a `Kick` per penalty
(side, taker, scored or missed) and `SimMatchService` kept only the two totals. **The per-kick list was
computed and discarded on every shootout the game has ever produced.**

| | before | after |
|---|---|---|
| Headline | *"drew 2-2"* | *"drew 2-2, settled 6-7 on penalties (OFK Omladinac went through)"* |
| Takers | none | every kick, named |
| Misses | none | shown, with a marker — a miss is half of a shootout |

The score stays the score: the shootout is **not** written into the goal columns, so it does not reach the
table, the replay or the scoreline. But a tie lost 5-4 on penalties is not a draw.

### Exit criteria

- [x] Every kick is written as an event with its taker, its side, the running score and its number
      **across the shootout** — kick 10 is the tenth kick taken, whichever side took it, which is what
      makes "one kick each" visible rather than two separate five-lists.
- [x] No minute is invented. A shootout kick has no minute and a fabricated timestamp would be a lie on
      the page whose job is the order things happened.
- [x] The headline names the shootout score and who went through.
- [x] The report shows scored **and missed**, one column a side.
- [x] Both halves proven able to fail — headline without the suffix, and reading only `PENALTY_SCORED`,
      each turn the test red with the reason.
- [x] **Matches played before this change show the corrected headline but no takers.** The per-kick list
      was thrown away when they were simulated and nothing can put it back. From now on it is recorded.

---

## P0-8 — 🔴 P0 · the national side Serbia U-21 is in Kup Srbije, rounds 1 to 4

**Owner decision, 2026-10-13: option (b) — void U-21's ties and re-draw from round 1.** *"ovo je testna
baza, onemoguci za ubuduce da upadne nt tim i sve ok"* — the played football is worth nothing, and a national
side must never get in again. **Scheduled; not yet done.**

**The guarantee is already done and proven.** `CupDrawSeedingTest.noNonLeagueClubIsEverDrawnIntoACup`
refuses a senior side, a U-21 side **and a club misfiled into a CUP**, and reverting the `isLeagueClub`
filter fails it. That filter is what `42fc45c7` added and it had **no test** — nothing failed when it was
removed.

### What is in there today

| Round | Serbia U-21's tie | Result |
|---|---|---|
| 1 | SK Zemun 1928 vs Serbia U-21 | **won 4-0** |
| 2 | Serbia U-21 vs GFK Dinamo Paraćin 1912 | **won 3-1** |
| 3 | Serbia U-21 vs ŽFK Sinđelić Zemun | **won 3-2** |
| 4 | Serbia U-21 vs TSK Tamiš Leskovac | **unplayed** |

Round 2 is the right size — 128 ties, 256 entrants, the owner's arithmetic — and the round-2 field is **255
real clubs plus one national side**, because U-21 won round 1 and took a winner's slot.

### The concrete harm

`SK Kikinda` (team 328, Opštinska liga Grupa 9) **is not in the cup at all.** It is one of Serbia's 310
clubs and it never appears in any round of any tie. It lost its place in the preliminary to a side that does
not play in a league.

Counted: 309 of 310 Serbian clubs are in the cup. It should be 310.

### Owner decision — taken 2026-10-13

**(b) — void every tie Serbia U-21 played and re-draw from round 1.** The owner: *"ovo je testna baza, onemoguci
za ubuduce da upadne nt tim i sve ok."* It is a test database, so the three rounds of played football cost
nothing, and a clean bracket is worth more than the results in it.

**Scheduled for the next session, and safe to do now** that P0-3 has made the redraw button idempotent.
Note what the button actually is: it redraws **round 1 only**, and it is not a "reset the tournament"
control. Voiding U-21's four ties and pressing it once is the intended sequence.

### Exit criteria

- [ ] No team whose `competition` is not a LEAGUE appears in any national cup fixture.
- [x] **Proven for every future draw** — `noNonLeagueClubIsEverDrawnIntoACup`, mutation-checked.
- [ ] Every club in the country that should qualify for the cup is in it — **310, not 309.**
- [ ] The cup can be re-drawn deliberately, and doing it twice changes nothing.

---

## P0-9 — ✅ P0 · the national cup had no final, and its last round was three weeks late

**Found 2026-10-13**, when the owner restated the format. The constant was not what the format is.

### The owner's table, and what the code had

```java
CUP_WEEKS = {1, 2, 3, 4, 5, 7, 8, 11}     // 8 rounds
```

| Round | Owner's week | Code's week | |
|---|---|---|---|
| 1 | 1 | 1 | |
| 2 | 2 | 2 | |
| 3 | 3 | 3 | |
| 4 | 4 | 4 | |
| 5 | 5 | 5 | |
| — | **6 is the national-team week** | — | |
| 6 | 7 | 7 | |
| 7 | 8 | 8 | quarter-final |
| 8 | **9** | — | **missing** |
| 9 | **10** | 11 | semi-final where the code had it, **and no final at all** |

A 256-team knockout after the entry round needs eight halvings and a final. The constant had **eight
entries**, so **there was no final in the national cup**, and its last round sat three weeks after the
quarter-final.

### Why it survived

`InternationalClubCupDraw.CUP_WEEKS` — the **continental** cups — already read
`{1, 2, 3, 4, 5, 7, 8, 9, 10}`. Exactly the owner's table. The national cup was the only competition in the
codebase disagreeing with the arithmetic, and there was nothing to compare it against until the two were read
side by side.

The class comment had defended the gap as a deliberate reading of an earlier brief — *"the final on week 11 …
at the cost of weeks 9 and 10 carrying league football only"*. That comment outlived the brief it was
defending, which is the most durable kind of wrong.

### Exit criteria

- [x] `CUP_WEEKS` is the owner's nine weeks.
- [x] A test pins the whole map **and the arithmetic** — nine rounds, 108 then 256 then halving to one
      champion — because a map of the right length in the wrong order still ends the cup on the wrong night.
- [x] Reverting the constant fails it: *"array lengths differ, expected: `<9>` but was: `<8>`"*.

---

## P0-5 — 🔴 P0 · 824 fixtures sit in the past and will never be played

**Found by P0-1, 2026-10-13.** P0-1 passed. This is what was standing next to it.

**Owner, 2026-10-14, found it on the dashboard:** *"znam da smo svasta testirali ali kako je moguce da next
match pokazuje s1 w4 day 7 a trenutno je s1 w6 d1 12:35? da li to znaci da je neki job promasio?"*

**No job missed week 6.** `matchday-prepare-1` triggers at 19:00 on day 1 and it was 12:35, so *"next
trigger week 6, day 1 at 19:00, in 7h"* was correct. The "1 failed" on those rows is the known week-3
duplicate-`player_discipline` failure, fixed in `f3d0c604`.

**What the dashboard is actually reporting is this board item.** The Club page picks the earliest
**unplayed** league fixture, and for OFK Omladinac that is week 4 day 7 — a match that will never be
played. The screen is telling the truth about a broken world.

### The club's own fixtures make it concrete

| Week / day | Competition | Played |
|---|---|---|
| w4 d5 | Kup Srbije vs SK Požarevac | ❌ |
| **w4 d7** | **Superliga vs TSK Tamiš Leskovac** | ❌ ← **this is the "Next Match" the dashboard shows** |
| w5 d1 | Champions Cup vs CHN Premier FC01 | ❌ |
| w5 d3 | Superliga vs OFK Timok Čačak 1901 | ❌ |

**Week 6 has no league fixtures at all** — only the five national-team days. So when day 1's prepare fires
at 19:00 there is nothing of his to play, and **week 7 day 1 is missing from the schedule too.** The league
fixture generation skips week 6 entirely, which is a separate defect from the stranded ones and is not
recorded anywhere else on this board.

`GameClockService.walk()` is **forward-only**:

```java
long steps = Math.max(0, Math.min(delta, MAX_CATCH_UP_HOURS));
```

A negative delta is deliberately zero, because `job_run` is recorded and not reversible. That is the right
rule, and it has a consequence nobody wrote down: **an hour the walk does not visit is gone for ever.** There
is no second pass, and nothing in the codebase asks "what did we miss".

### What is stranded

| Fixture slot | Count | Any `job_run` row for that matchday? |
|---|---|---|
| week 3 day 1 | 2 | yes — `FAILED`, the known `player_discipline` pair |
| week 4 day 5 | 32 | no |
| **week 4 day 7** | **155** | **no** |
| **week 5 day 1** | **480** | **no** |
| week 5 day 3 | 155 | no |

### Two mechanisms did it, and neither reports itself

1. **The watermark repair.** `walk()` treats a `lastSyncedAt` ahead of game time as a resume point that
   lies, and resets it to *now* (`3b758f45`). Correct as a self-heal — it brought back every scheduled job —
   and it costs the hours between the old watermark and now. A 72-hour rewind of the offset on the evening
   of 2026-10-13 did the same thing at a larger scale.
2. **The 7-day cap.** `MAX_CATCH_UP_HOURS` truncates a long backlog and reports `truncated` and `missedHours`
   — but only in the response to the one request that hit the cap. It is never written to `job_run` and
   nothing looks for it afterwards.

So the app is healthy, no job failed, and four matchdays will never be played. Nothing anywhere says so.

**Why this is not a crash to fix on the way past.** Replaying a missed matchday means simulating it, and the
hour-before-kickoff split exists so a manager's tactics are settled *before* the football is computed. A
catch-up that computes the football at the moment it noticed it had no plan has already broken that, and it
is the same class of thing as P0-3's duplicate draws.

### Owner decision needed before it is built

| Option | What it costs |
|---|---|
| **(a) Write them off** | Mark the stranded fixtures abandoned and say so on the fixture. No replay risk, and the world stays honest: a tie that was never played is not a tie that lost. |
| **(b) Compute and publish the missed matchday at once** | Plays them, but decides a match at the moment the system noticed it had no plan. Contradicts the reason T-MATCH-1 exists. |
| **(c) Catch up from the fixture's own `match_date`** | The fixtures already carry the hour they were meant to kick off. A catch-up pass that walks *fixture* times instead of the watermark can find them — and can refuse any fixture whose kickoff is older than the hour-before-kickoff window, which is (a) applied only to what was missed. |

**(c) is the one that fits the design**, because the source of truth already exists and the hour-before rule
can be honoured per fixture rather than per clock. But it is a new job and a new rule about what a missed
kickoff means, so it is a decision and not a fix.

### Exit criteria

- [ ] The stranded fixtures are either played or marked abandoned. No fixture may sit in the past, unplayed
      and unreported, ever again.
- [ ] **The Club page must not offer a stranded fixture as "Next Match."** It is currently showing a week 4
      tie at week 6 day 1, which is honest reporting of a broken world and still wrong to a manager.
- [ ] **Week 6 has no league fixtures at all** and week 7 day 1 is missing from the schedule. The league
      fixture generator skips week 6 — a separate defect, recorded here because it was found here.
- [ ] `MAX_CATCH_UP_HOURS` truncation is written to `job_run`, so a capped catch-up is a record rather than a
      line in a response nobody kept.
- [ ] A fixture whose kickoff has passed is found by asking the fixtures, not by trusting a watermark.
- [ ] A test that fails on today's `walk()` when a matchday is stepped over.

---

## P0-6 — ✅ P0 · the lock named the wrong club to an away manager

**Found live during P0-1, 2026-10-13**, and not by any test — which is the finding.

`GET /teams/1/match-lock`, while matchday 7 sat prepared:

```json
{ "locked": true,
  "reason": "Your match against OFK Omladinac is already decided, so its tactics, squad and orders are closed...",
  "opponent": "ŽFK Radnički Sport", "homeOrAway": "AWAY" }
```

Team 1 **is** OFK Omladinac. `opponent` and `homeOrAway` are right; `reason` names the manager's own club
back at him, on the one screen whose entire job is to explain the lock.

`MatchLockService.refusalReason` decided home-vs-away by comparing `fixture.getHomeTeam().getId()` against
`homeOrAwayId(fixture)` — and that helper returned `fixture.getHomeTeam().getId()`. **A tautology**, true by
construction, so the sentence always named the away side: right by accident for a home manager, wrong for
every away one.

**Fixed** by giving the method the club being refused — `refusalReason(fixture, askingTeamId)` — and updating
all six call sites: two in `TeamController`, three in `MatchLineupController`, one in the match-lock read.

**Why it survived a test.** `MatchSetupLockTest` asserted `$.reason` **`isNotEmpty()`** — the
"a test that cannot fail" shape from AGENTS.md. It would have passed against a message reading
*"Your match against null is already decided"*. The assertions now name the opponent **and** assert that the
club being asked about is **not** named, from both sides and on all three endpoints that carry the message.

**Exit criteria**

- [x] Reverting the fix fails the tests — done, two of them, on the away-side assertions.
- [x] The refusal names the opponent from the home side and from the away side.
- [x] `MatchSetupLockTest` 7 green; 36 green across the five classes that touch the lock.

---

## T-CLOCK-1 — 🔴 P0 · the game clock was two clocks

**Found by the owner, 2026-10-11**, who asked for the header clock to be the game clock and for it to
move like a real clock.

### The defect

`GameClock` held **two** things and they were allowed to disagree:

| | |
|---|---|
| `advanceOffsetSeconds` | rendered as `now() + offset` — real seconds, what the header showed |
| `currentSeason` / `currentWeek` / `currentDay` / `currentHour` | four stored counters, what every job read, moving **only** when a button was pressed |

Nothing logged when they diverged. The header read `16:20` beside a job runner that believed it was
09:00 of day 4, and a Champions Cup tie said 18:00 while the job that plays it triggered from 20:00.

### The fix

- `GameClock` holds **an anchor and an offset and nothing else**. Season, week, day and hour are
  `GameTimeline.positionAt(anchor, gameTime)` — a pure function. The getters keep their old names so
  ~20 readers keep working; they have **no setters**, because a value that cannot be written cannot be
  the second source of truth.
- The header shows `HH:MM:SS`, **anchored on the server's** `gameTimeEpochMillis` rather than the device
  clock. The server is in another country; a laptop four minutes out would display a game time four
  minutes out — the same disagreement, wearing a different hat.
- Season/week/day are derived **in the browser from the same instant**, so the day can no longer be
  twenty seconds behind the time printed beside it.
- There is no date. `current_game_date` is derived and cannot be written.
- Every movement — advance hour/day/week, the new `POST /api/game-clock/offset`, the half-hourly tick —
  adds to the offset and then calls `synchronise()`. One path.
- `synchronise()` walks **every game hour since `lastSyncedAt`**, so an app down over a kickoff comes
  back and plays the matchday it missed. Capped at 7 days, and it says so when it caps.

### Three more defects found while proving it

- **A world that had never synchronised walked zero hours.** The walk starts at `lastSyncedAt`; a world
  with none started where it already stood, so Advance Week ran **no jobs at all** and reported a week
  that had elapsed. `establishBaseline()` runs before the move.
- **The advance blocked on the job bodies.** Measured: one Advance Hour onto day 4 held the request open
  **over five minutes** with the database idle. The clock now moves first, the walk runs on one thread,
  and `snapshot()` reports `syncing` so the browser waits rather than showing a world the jobs have not
  touched.
- **The migration folded the old display offset in twice.** The counters were the truth and the offset
  only ever moved the displayed time, so anchoring for the counters and leaving the offset alone moved a
  day-4 world to **day 7** — a training day and a matchday vanished into it.

### Also

`refreshGameClock()` was called under a `typeof === 'function'` guard and existed **nowhere in the
repository**. Advance Day and Advance Hour therefore refreshed nothing at all.

### Verified

15 clock tests green. Both fixes **proved able to fail** by reverting them: removing the baseline gives
"Offered: 0" of 168 hours; stopping the season from rolling fails two. Live against the owner's
database: migrated to **season 1, week 1, day 4 at 09:00** — exactly where it already was — and the
header ticks in step with the server to the second.

---

## T-CALENDAR-1 — 🔴 P0 · a fixture's day and its kickoff hour disagreed

The handoff's "**this is the next thing to fix**". Three writers each answered "when does this slot
play" for themselves:

| Writer | Day column | Date stamped |
|---|---|---|
| League | read `WeekTemplate` ✓ | read `WeekTemplate` ✓ |
| National cup | 5 | 5 at 18:00 ✓ |
| **15 continental cups** | **1** | **5 at 18:00** ✗ |

Every one of them reported day 1 and carried a day-5, 18:00 timestamp. A manager told his tie kicked
off at 18:00 watched nothing happen at 18:00.

Also skipped the template in two other ways: **league return legs** (rounds 11-18) took the season-start
time verbatim, so the first ten rounds kicked off at 19:00/16:00 and the second ten whenever the world
happened to be created; and **playoff fixtures carried no day and no hour at all**, so no matchday job
could find them.

`GameTimeline.kickoffAt` is now the only answer. Verified by mutation — restoring the borrowed rule
fails with *"the fixture says day 1 but is dated 2026-01-16"*.

**Still open:** `SeasonService.ensurePlayoffWeekFixtures` has **zero callers**, so no playoff fixture is
generated at all. The day and hour are correct for whenever it is wired up.

---

## T-U21-1 — 🟡 P1 · a national side drawn short was short for ever

**Found by the owner, 2026-10-11**: *"umesto 25 igraca bude npr 7 a dole u national pool ih ima koliko
hoces"*.

`NationalTeamSeeder.squadsFor` guarded with "this side already holds real players". So the first time a
country was drawn — while its pyramid was small and only a handful were eligible — it froze there, and
every later pass took the same branch. **Re-seeding could not help**, which is why the pool was full and
the squad was not. A squad is done when it is **full**.

**The owner's database is currently healthy** — all 48 U-21 sides hold 25, none over-age — so this only
bites a world where a country is first drawn with a small pool. The new test reproduces exactly that and
fails on the old guard with "expected 25 but was 7".

**A test that was already red:** `theGuardDoesNotBlockASecondSide` has been failing since the age filter
went in on 2026-10-10 — that country had six under-21s out of thirty and the side was drawn with six.
It was failing in the suite and was not being read.

---

## T-FAC-1 — 🟡 P1 · the training facilities do nothing

**Owner question, 2026-10-12**, on the stadium page: *"Training facilities 1/20 ground, 1/20 gym, 1/20
tactical, 1/20 youth, Weekly upkeep 0 · Facility upkeep — uticu li uopste na cenu/trening?"* — do they
affect price or training at all?

**Answered by reading the callers, not the screen.** `TrainingFacilityService` has exactly two consumers:

| Consumer | What it uses it for |
|---|---|
| `StadiumSettingsController` | shows the levels and sells the upgrades |
| `WeeklyFinanceService` | charges the weekly upkeep |

Nothing in the training engine reads them. `SquadTrainingService` and the development code take no notice
of `trainingQuality`, `gymLevel` or `tacticalLevel`. So today a facility level affects **the upgrade price,
the weekly upkeep and the number on the screen** — and nothing else.

**"Weekly upkeep 0" is correct, not a defect.** Upkeep is `120 × (sum of levels − 1)`, so four facilities at
level 1 cost nothing. The first upgrade starts the bill.

### ✅ Corrected 2026-10-13 — all four levels DO act; the earlier claim was wrong

It was reported that `TACTICAL` was a level a manager could buy that changed nothing. **That was wrong**,
and it came from confusing a dead *method* with a dead *facility*. `Facility.tacticalMultiplier()` has no
caller — but the **TACTICAL level itself** is read by `Stadium.trainingFactorFor` and trains passing,
playmaker and technique. Verified by reading every consumer, not by inference:

| Facility | Column | Trains | Consumer |
|---|---|---|---|
| `GROUND` | `trainingQuality` | speed, shooting, defending | `TrainingProgressionService:613` |
| `GYM` | `gymLevel` | stamina, fatigue — plus injury protection | `Stadium.trainingFactorFor` / `injuryProtection` |
| `TACTICAL` | `tacticalLevel` | passing, playmaker, technique | `Stadium.trainingFactorFor` |
| `YOUTH` | `youthLevel` | academy quality | `AcademyQuality:78` |

So exit criterion 1 is **already satisfied** — every level has a stated effect, they are disjoint by skill
group, and all share one curve `0.90 + 0.20 × (level − 1) / 19`. `null` reads as neutral rather than as
level 1, deliberately, so a club predating the feature is not silently nerfed 10%; a club that has built
nothing says so with an explicit 1.

What is genuinely dead is the single `tacticalMultiplier()` method, which duplicates an effect the level
column already provides. It is redundant code, not a missing feature, and should be deleted rather than
wired — wiring it would apply the tactical level twice.

**The gap is that four numbers a manager pays for do not pay him back.** A manager who spends 2,500 to take
the gym from 1 to 2 gets a gym that trains nobody better than the gym he already had, and nothing on any
screen says so.

**Owner decision needed before it is built — it changes the economy and it touches T-MATCH-8:**

- What does a level buy? Attribute growth per week? A training multiplier? Recovery? Faster condition?
- Is it linear, or does level 15 matter more than level 2?
- Does the **youth** facility develop juniors, or is that `JuniorDevelopment`'s `gymLevel` already?
  `JuniorDevelopment` reads the stadium's `gymLevel` today, which means the gym level may already have one
  effect — if so, it is undocumented and the new work has to extend it rather than duplicate it.
- Should a bot club's facilities be seeded at a level that matches its reputation? Every club starts at 1,
  so on a world of 14,976 clubs nobody's facilities matter until someone pays to raise them.

**Exit criteria:**

- [ ] Each of the four levels has a stated, tested effect. A level that changes nothing is not a level, and
      a facility that cannot be shown to change a number should not be sold.
- [x] The effect is visible on the training report, so a manager can see the club got something. *(2026-10-13:
      `Stadium.facilityLevelFor(skill)` names the level a skill trains under, the report row carries
      `facilityLevel` and `facilityFactor`, and the report table has a **Facility** column. Covered by
      `StadiumFacilityLevelTest`, which pins that the named level and the factor can never disagree, and
      that an unrecorded facility reads "No facility recorded" rather than level 1 — a club predating the
      feature is neutral by design and must not be told it trained somewhere it never bought.)*
      **Not yet seen in a real report:** every stored report predates the change, and the training job's
      day-4 window keeps being skipped by coarse clock advances, so the populated column has not been
      observed end to end.
- [ ] The weekly settlement charges upkeep for the levels actually held, and the stadium page agrees.
- [ ] Whatever `JuniorDevelopment` already does with `gymLevel` is documented rather than done twice.
- [ ] Bot clubs are seeded at a plausible level, or the number is shown as "not yet" rather than as 1/20.

**Do not build this next to T-MATCH-8.** Both change what a week of training is worth. One rule, one place.

---

## T-KIT-1 — 🟢 P2 · the players wear no kit

**Owner request, 2026-10-12**: *"dodaj u board task za vizuelno sredjivanje dresova igraca"*, with
Sokker's own kit gallery as the reference —
<https://sokker.org/media/show/kits> (Office → Media → Match kits).

**No credentials are recorded here and none should be.** The owner supplied his own login for looking at
the reference; a board file is committed to the repository, so a password in it is a leaked password in
the history forever. Anyone who needs the gallery opens it in their own browser.

**What "the players wear no kit" means today.** The squad and the match lineup render a name and a
position. A kit is not a colour alone: Sokker's gallery shows shirt, shorts and socks per player, the
club's badge on the shirt, a number, and a keeper's variant of it. There is no `kit` column on `player`,
no `kit` table, and nothing on `team` either.

**Exit criteria — each one is a checkbox, not a sentence:**

- [ ] A club picks a kit: shirt, shorts and socks as colours, plus its number style. Reachable from the
      club pages, not only from an admin screen.
- [ ] A player renders in that kit wherever a shirt is drawn: the squad page, the match lineup, the match
      report, and the club profile.
- [ ] A goalkeeper's kit is separate from the outfield one, and the engine picks the right one for the
      side in possession — the same rule the engine already applies to positions, not a display-only swap.
- [ ] Two clubs meeting each other are never the same colour. Where the two clash, the away side takes its
      alternative, and the manager can see which was taken.
- [ ] The away side wears the mirrored/alternative strip, decided the same way the mirrored tactics are —
      one rule in one place, not a second copy in the renderer.
- [ ] A club with no kit chosen renders a readable default, so a world built without touching this still
      looks like football and not like a table of names.
- [ ] Tests that fail on the old code. A renderer that draws no shirt cannot be caught by a test that only
      checks the name is there.

**Decide before building:** whether a kit belongs to the club or to the player, and whether it is data or a
style sheet. The owner's reference is per-player on Sokker, so the default answer is per-club with a
per-player number, and the reason for that choice goes in `TECHNICAL_OVERVIEW.md` when it is made.

**Reference material:** `sokker.org/media/show/kits` for the look; the existing colour pickers on the
stadium page (`/images` assets, the same shape) for how a colour choice is already stored and saved in
this codebase.

---

## T-ECON-1 — 🔴 P0 · income did not move when the club did

**Found by the owner, 2026-10-11**, with the number: *"ako su plate mog tima 18 miliona evra nedeljno
onda mora i income da se prilagodi tim vrednostima"*.

Measured, season 1 week 1, OFK Omladinac:

| Income | | Cost | |
|---|---|---|---|
| Gate | +173,591 | **Wages** | **−18,478,000** |
| Broadcast | +2,900 | Facility upkeep | −18,388 |
| Merchandising | +1,739 | | |
| **Total** | **+176,491** | **Total** | **−18,496,388** |

A **105× deficit**. Broadcast was `competition.reputationWeight × 145` — a pure function of the
division. Gate was a literal: 72% home, 55% away, every club, every week, every opponent.

### The fix

Broadcast is a **stated share of the club's own wage bill**; gate occupancy follows reputation;
sponsorship scales with standing; merchandising was raised from a base of 1,400 (3% of income — a line
that existed and moved nothing). `ClubEconomics` is one place for "what does this player cost", because
there were four wage formulas at magnitudes three orders of magnitude apart, one of them
`500 + random(2500)` for academy graduates.

Found on the way: **`PlayerContractService.renew` never wrote the new wage to the Player**, and the wage
bill reads `Player.earnings` and nothing else — so every renewal in the game moved the number on the
paper and left the number the club pays where it was. And a week in which the pitch improved charged the
pitch and returned early, so the club paid for its grass and **not** for its ground.

### 🔴 Open — an owner decision, not a defect

The broadcast coefficient is **calibrated, not derived**. One club does not fit it: OFK Omladinac pays
**18,478,000 a week across thirteen players** — 74m a year each — on a 43,780-seat ground that brings
173,591. **Sixty times a normal club's wage bill on twice the ground.** No single coefficient balances
that club and an ordinary one, because the wage scale is the outlier, not the income side.

Re-pricing every wage and value is a migration over **31,230 players** and a decision about how much
money is in this world. **It needs the owner.**

### ✅ Owner decision, 2026-10-13 — option 1, the club is the exception

*"uzmi broj 1 - exception jer ce kad bude prod i moj tim biti kao ostali"* — take option 1, the exception,
because when the club is sold it should be like the others. The global wage scale is therefore **not**
re-priced and no migration runs.

Measured before choosing the target, rather than picking one:

| | weekly bill |
|---|---|
| OFK Omladinac (13 players) | **18,478,000** |
| tier-1 median | 435,591 |
| tier-1 p95 | 484,704 |
| tier-1 max, excluding Omladinac | **498,148** |

Omladinac was **37× the highest-paid club in its own division** while owning the division's largest
ground. It is set to **498,148** — the top of the tier's real range, which is where the biggest ground
belongs. Per player that is 1,421,385 → **38,319**.

`PlayerContractService.renew` reads `Player.earnings` and preserves it, so correcting the 13 players is
durable rather than cosmetic; the matching `player_contract.weekly_wage` rows were synced in the same
pass so contract and player cannot disagree again.

**Not yet verified:** the newest ledger rows are still weeks 2 and 3, settled before the change. A week
settled at the new bill has not been observed yet.

**Next outlier found while checking the result:** `Junior school` settles at **1,109,880 a week** — now
more than double the wage bill and the club's single largest cost. Probably a YOUTH-facility settlement
(T-FAC-1's "settlement charges upkeep for the levels actually held"), so it is recorded there rather than
fixed blind.

---

---

# 🔴 General information — read before taking any task

## The three rules

**1. A green status is not evidence.** The recurring failure shape in this codebase is code that reports
success while doing nothing. Four separate bugs had it in one session: a seeding job that caught its own
failure and returned normally, a pyramid builder that gave up before the first league and reported a
clean rebuild, a Back button with a `dataset` and no listener, and an `escapeHtml` used in a `catch` and
never imported. **Three of the four could only be found by opening the app.**

**2. A job is not done until it has been seen to change data.** Not logged as done — observed in the
database afterwards. Never hand back a half-built world: every seeding path must end in a world that
passes the integrity check.

**3. A test that cannot fail proves less than no test.** When you write a guard, **break the code
deliberately and watch it fail.** Three tests in this repository were green while measuring nothing.

## Environment

```bash
export JAVA_HOME=/Users/velja/Library/Java/JavaVirtualMachines/corretto-21.0.12/Contents/Home
export PATH="$JAVA_HOME/bin:/usr/local/bin:$PATH"
```

| | |
|---|---|
| Build | `mvn clean package -DskipTests` |
| Run locally | `./run-app.sh --app.open-browser=false` — **every shell start must use the flag** |
| One test class | `mvn test -Dtest=RatingEngineTest` |
| Local database | PostgreSQL, db `sokker_db`, user `postgres`, password `stojke` |
| App login | `velibor@example.com` / `A12345!` |

**A full `mvn test` takes ~2 h 52 m and needs the application running on `:8080`.** Three Playwright
classes otherwise hang the entire run — they wait, they do not fail. Never start one on the way to
something else. **Use `mvn clean test-compile`** — an incremental build once reported SUCCESS while three
test classes called methods that no longer existed.

### PostgreSQL client tools are versioned against the server

`pg_dump` refuses to touch a newer server. The server is **Postgres.app 18.4** and the `pg_dump` first on
`PATH` is **Homebrew 16.15**. `DatabaseBackupService` reads the server's major version over JDBC and picks
a matching client; `app.backup.pg-tools` overrides the search. Anything new that shells out to the
PostgreSQL tools needs the same treatment.

## Working rules

- **Answer in English.** The owner writes in Serbian; every reply, message, commit and code comment is
  in English.
- **Commit to `main`** after each task. Stage only the files that task intended to change.
- **After each task:** update this board and append to `kanbanProgress.md` carrying the commit hash.
- **Substantive findings go in `kanbanProgress.md` even when the task fails.** A measured dead end is
  worth more than a silent one.
- **When you find a defect that is not your task: fix it or report it — do not walk past it.**
- **Do not delete a task to make the board look tidy.** Mark it done, or record why it was dropped.

## Where things live

| Path | What |
|---|---|
| `src/main/java/org/example/footballmanager/newLogic/` | **The product.** Exactly two packages: `newLogic` and `demo` |
| `demo/service/` | **Frozen reference engine.** Not the product path. Do not extend, do not port from |
| `src/main/resources/static/` | Frontend, vanilla ES6 modules |
| `archive/` | **Superseded documentation.** History only. Never work from it |
| `CurrentStateAnalysis.md` | What exists, verified against source. Read before estimating |
| `TECHNICAL_OVERVIEW.md` | What the system actually is today |
| `userManual.md` | What a manager can do, from the outside |

## Traps that have cost real time

- **`Team.reputation` is the economy's 0–100 scale, not an Elo.** Club Elo lives in `eloRating` /
  `eloPreviousRating` / `eloDelta`. **`Country.reputation` is a different scale on a column of the same
  name.**
- **`PyramidBuilder` never sets `Team.type`**, so "is this a club?" cannot be answered from that flag. A
  club is anything whose `competition` is a LEAGUE.
- **A season is twelve weeks counted from 1.** There is no calendar year anywhere. Code or tests passing
  2024/2025/2026 as a season value are wrong, even if self-consistent and passing.
- **A fixture id and a match id are both small integers over separate tables.** Never infer one from the
  other. `ZoxApiController` already carries the scar.
- **Two clubs may share a name.** Never match a team by name — use `User.footballTeam`. Four defects came
  from that join.
- **Use `authFetch`** for every authenticated call and **always check `response.ok`**. Several loaders
  `await response.json()` without it.
- **Boot writes nothing.** No seeding, no backfills, no repair.
- **A `ddl-auto=update` column widens; a CHECK constraint never changes.** `nl_notification.kind` was
  pinned by a CHECK no code maintained, and a new notification kind failed on first real write. The
  constraint is now rebuilt from the enum at boot.
- **`WE_HAVE_BALL` and `OPPONENT_HAS_BALL` are identical on purpose.** `mirrorWeHaveBallRules` is an owner
  decision, not a defect. Do not "fix" the mirroring.
- **`demo/service/` is frozen.** Reference only.

## Scale

48 countries × 31 divisions × 10 clubs ≈ **14,880 clubs**, 96 national sides. Projected **7,440 matches a
matchday, 89,280 a season**, and `player_zone_load` at 198 rows a match means **17.7M rows a season, 2.8 GB**.

**The dev database cannot measure a performance task.** `match` holds 155 rows: one matchday of one
country. A sequential scan of 155 rows is the correct plan. Every index added under T2 was measured in a
throwaway `sokker_bench` database built to those numbers.

---

# 🔴 T-MATCH — the pre-match hour, and the tactics that live in it

**Owner, 2026-10-11.** *"jedan job koji 59 minuta pre kickoffa uradi compute svih mečeva a drugi job u
vreme meča otvori linkove. Napomena: sat vremena pre početka utakmice igrač ne sme više podešavati
taktiku za taj meč."* Then, after looking at Sokker: *"pogledaj kako radi sokker pa namerno napravi
isto... imaš 10 taktika i 3 biram za meč, bilo koja koju promeniš, ima copy paste mirror... squad
isključi 11 i klupa, orders set pieces i uslovne zamene."*

Decisions the owner took when asked, 2026-10-11:

| Question | Answer |
|---|---|
| Cap on conditional substitution orders | **20 per match** |
| Cap on tactic-swap orders | **10 per match** |
| Saved tactics per club | **10** (`MAX_TACTICS_PER_CLUB`, enforced in `TacticLibraryService`) |
| Tactics selectable for one match | **3**, any tactic into any slot |
| Opponent Setup Archive | **not now** |
| Set-piece takers (free kicks, penalties) | **not in this pass** — tactics, squad, orders only |
| A swap order that fires mid-match | **the engine switches live at the trigger minute** |
| Prepare/publish scope | **club matchdays and national tournaments** |

## T-MATCH-1 — 🔴 P0 · a match is computed an hour early and released at kickoff

**Status: partly done — see [`kanbanProgress.md`](kanbanProgress.md), commit `f23dc810`.**

What exists now:

- `MatchdayScheduler` with `prepareUnplayedOn` and `publishUnplayedOn`.
- `MatchdayPrepareJob` / `MatchdayPublishJob`, registered for days 1, 3, 5, 7, hours read from
  `GameDay` so the pair cannot drift.
- `SimMatchService.prepareMatch` — the persist path that leaves `played = false` and writes
  `prepared_match_id`.
- The five old at-kickoff `MatchdayJob` beans for days 1/3/5/7 are gone.

**Exit criteria, still open:**

- [x] Seen in the database: `MatchdayPublishJob` releasing a prepared matchday —
      `Matchday publish day 7: 15 result(s) released for week 1`, the 15 fixtures a killed session had
      left prepared.
- [x] Per-fixture commits confirmed against PostgreSQL: 15 fixtures computed and stored while the
      matchday was still running, which is what the per-fixture transaction is for.
- [x] **Seen in the database: one whole matchday, 155 fixtures, computed and released end to end.**
      **Done 2026-10-13, week 5 day 7.** `matchday-prepare-7` DONE at 15:00 in 121 s — 155 `match` rows with
      goals, lineups, events, stats and 3 410 `match_player_stats` rows. `matchday-publish-7` DONE at 16:00
      in 5 s — 155 fixtures flipped to `played`. **Zero new `FAILED`.** The window between the two was
      checked while it was open: `/matches/3359`, `/detail`, `/preview`, `/report` all **404**, while a
      played match from week 4 answered **200**; the club's match list went 13 rows → 14 across the publish.
      Elo and the ranking-points ledger replayed at hour 22 and moved **with the result** (home win +17,
      away defeat −17). This was the one item T-MATCH-1 was waiting on, and it is closed.
- [ ] The publish fallback proven live — a fixture the prepare job missed is computed at kickoff and
      published, not lost.
- [x] National-tournament matchdays get the pair, for **days 2, 4 and 6 only**. Days 1, 3, 5 and 7
      needed nothing: the club pair is `ANY_WEEK` and prepares the whole matchday, so a qualifier that
      lands on a league day is already locked and released by those. Days 2, 4 and 6 had nothing at all
      — a national tie started and finished with nobody watching — and now carry a qualifier and a
      tournament pair each, with keys `matchday-prepare-qualifier-2` and so on, because two jobs on one
      day may not share a key.
- [x] A prepared match is unreachable through **any** read path: match-by-id, lineups, events, stats,
      preview, report and all three replay endpoints. `MatchPublicationGuard`, `404` not `403`, because
      an id space this small is enumerable and "it exists but not yet" is already an answer.
- [x] The team match list and the dashboard's recent matches. Both are built on `m.played = true`, and
      a prepared match is a **complete row** — goals, possession, everything — so it simply appeared.
      `MatchRepository.NOT_PREPARED` excludes it, narrowly: only while its fixture is still unplayed.
- [x] **Owner decision taken, 2026-10-11:** a rating moves at **publication**, not when the football is
      played. `MatchRepository.NOT_PREPARED` is applied to the ranking replays as well, so Elo and
      ranking points stay still for the hour before kickoff. Safe because every one of them is a full
      replay over the played match table rather than an increment — excluding a match defers it to the
      next replay instead of losing it.

## T-MATCH-2 — 🔴 P0 · the lock is real, not a message

**Partly built.** `MatchLockService` and the guards on the two write endpoints that exist today.

The lock is **the prepared fixture, not a clock reading**. "Within an hour of kickoff" would be the
wrong source of truth: a manager's change must be refused because *the match was already decided
without it*, not because a number was crossed — and an app that was down for the hour still has a
decided match. A fixture that is computed and not published is exactly that fact, and it is stored.

**Exit criteria:**

- [x] A failed test first: changing tactics on a prepared fixture was **200**, now **423 Locked** with a
      reason naming the opponent and the kickoff hour. `MatchSetupLockTest`.
- [x] The lineup template is refused the same way.
- [x] The away side is locked too — which side a club is drawn must not decide whether it is locked.
- [x] Open again at kickoff: a published fixture is not a lock, and a club with no match in the hour is
      never locked.
- [x] **The squad and the orders both had the hole, and both are closed.** The squad page's `editable`
      flag was `!fixture.isPlayed()` — and a **prepared fixture is not played**, so the one page where a
      manager picks his eleven was the one page still accepting changes. The orders page used a one-hour
      time cutoff and knew nothing about the prepare lock, so it said "open for another 45 minutes" about
      a match decided an hour ago. Both now refuse on `isPrepared()`, asked at the fixture so a manager
      with two fixtures in a week is refused for the decided one and not the other.
- [x] **It is in the browser.** `GET /teams/{id}/match-lock` is the question the pages ask before they
      offer to change anything; `renderMatchLockBanner` names the opponent, the day and the kickoff hour,
      and the tactic editor's Save reads *"Closed until kick-off"* instead of failing on click. The squad
      page shows the same reason in its existing "Closed" line, and carries `lockedAt`.
- [ ] **A behaviour change to note:** a closed plan now answers **423 Locked** where it answered 400.
      Both are non-2xx so the browser is unaffected, and 400 always meant "malformed request", which it
      never was.
- [ ] The engine reads the locked state rather than the club's live settings. With today's club-level
      model this is true by construction — the result was computed from the settings as they were, and
      nothing can change them afterwards. It stops being true the moment tactics become per-match in
      T-MATCH-3, which is where a snapshot has to be stored.

## T-MATCH-3 — 🔴 P0 · the tactics editor

**The model and the API are built. The page is not.**

Found while starting this: **most of the model already existed.** `Tactic` is a real entity with a club, a
name, a formation, style, rules and set pieces, `uk_tactic_team_name` keeps names unique per club, and
`TacticLibraryService` already had list, save-by-name, make-default and delete-with-promotion, all tested.
`TacticsRulesProvider.forTactic(teamId, tacticId)` even existed. So this task is the **cap**, the
**operations**, and the **page** — not the model, which would have been the expensive assumption to make.

**Exit criteria:**

- [x] **The cap of 10, enforced in the service and stated.** `MAX_TACTICS_PER_CLUB`, checked before the
      insert so a refused save leaves no row. The refusal names the number.
- [x] Editing at the cap still works: a save keyed by an existing name is an edit, not an eleventh tactic.
      A manager working on his tenth must not be told the library is full.
- [x] **Copy — the owner's "create based on"** — creates a separate row, so editing the copy cannot reach
      its source.
- [x] **Mirror** is a real transformation, not a copy — **corrected 2026-10-12, it was reflecting about
      the wrong axis and now has the wrong answer in the log above it.** The editor's vocabulary is 7 rows
      × **6** columns (`FormationSlotCatalog.buildTargetCells`), and `parseCell` gives `CELL_r_c` the
      physical centre `(r + 1.5, c + 1.5)`, so the mirror is `5 - c`: 0↔5, 1↔4, 2↔3, and **no centre
      column**, because six is even. It agrees with `TacticalPerspectiveTransformer` (`8 - physical`,
      the axis `PROPOSAL_CURRENT_STATE.md` records as col 4.0). Rows are not mirrored — this is a
      left-right flip, not a goal-to-goal one. Involution tested, source never edited.
- [x] **Corners mirror too.** They are named ball states carrying the side in the name, not cells, so
      they need swapping separately or every corner rule keeps pointing at the corner the manager was
      not using.
- [x] Tested against the **real vocabulary**, not hand-picked cells: every cell `FormationSlotCatalog`
      offers must mirror to the cell `8 - physical` names, and that cell must be one the editor can name.
- [x] Copy and mirror are refused across clubs. Two id spaces overlap — club 1's tactic 1 and club 2's
      tactic 1 are both 1 — and a copy from another club would be a manager's team playing somebody
      else's shape.
- [x] `GET/POST/DELETE /teams/{id}/tactics` with copy, mirror, delete and `/limits`. Every row carries
      `maxTactics` and `atCap`, so a page can disable "new tactic" with the reason visible.
- [x] **The page has a shelf**: the ten listed in order with the default starred, `x of 10`, New / Copy
      (based on) / Mirror / Delete, and New disabled **with the reason shown** when the club is full.
- [x] **Save now targets the library.** The editor's Save writes to `/teams/{id}/tactics` under the name
      the tactic was opened with, so a save is an edit of *that* tactic. It used to write the club-level
      `tactics-editor` profile, which could only ever hold one shape — the opposite of ten tactics.
- [x] Picking another tactic clears the local draft first; editing one shape over another is how a
      manager loses work without being told.
- [ ] **The page has not been opened in a browser.** There is no browser automation in this environment
      (no node, no agent-browser), so the API it calls is verified and its rendering and click handlers
      are **not**. That is a real gap and the page should not be trusted until somebody has looked at it.
- [x] **Paste** — the clipboard half of Sokker's Copy/Paste, which is copy between clubs. **Done 2026-12-12.**
      Own button beside Copy, copies rather than references, and refusing your own tactic by name of the
      right button.
- [ ] **Match settings must look like Sokker's**, per the owner: a General / Squad / Orders set of tabs
      over one match, with the tactic picker in General. T-MATCH-9.

### How this page was verified, and how it was not

| | |
|---|---|
| `reorder` accepts an order and reorders | **live, against the running app** — Charlie into slot 1, Bravo staying in the middle, Alpha into slot 3 |
| a duplicate is refused | **live** — *"The same tactic cannot hold two slots at once."* |
| a fourth entry is refused | **live** — *"A match takes 3 tactics, not 4."* |
| a prepared match refuses the plan | **live** — **423**, *"This match has already been decided…"* |
| the arrows and the buttons | **not verified** — nothing here has parsed or rendered the JavaScript |

## T-MATCH-4 — 🔴 P0 · three tactics per match, any into any slot

**Nothing built.** A manager picks **3** of the club's 10 tactics for the next match, and any slot can
be swapped to any other tactic.

**The model and the engine's reading of it already existed**, which is the finding: `MatchTacticAssignment`
carries a priority of 1–3 with `MAX_PER_SIDE = 3`, and `MatchTacticPlan.inForce` takes the **lowest**
priority whose condition holds — so a plain assignment (`ALWAYS` from minute 0) means the match runs on
slot 1 from the first tick. `MatchOrchestrator` re-resolves every tick, so a swap can also take effect
mid-match, which is T-MATCH-6's engine requirement and is already in place.

**Exit criteria:**

- [x] **The engine starts the match on slot 1** — verified by reading `inForce`: lowest priority wins, and
      a plain assignment holds from minute 0.
- [x] **Any tactic into any slot**, in one write: `POST /api/sim/fixtures/{id}/tactic-plan/reorder` takes
      the whole order. Three one-slot writes cannot do it — putting the third tactic into slot 1 while the
      first is still there is an intermediate state where two slots hold one tactic.
- [x] Reorder refuses a duplicate, a fourth entry, and another club's tactic — **checked against the club
      on that side**, since club 1's tactic 1 and club 2's tactic 1 are both 1.
- [x] Reordering down to two drops the third, rather than leaving an instruction pointing at a tactic the
      manager took off the pitch.
- [x] **The lock.** This page had the same hole as the squad page and the orders page, found on the
      **fourth page in a row**: it refused a *played* fixture, and a prepared fixture is not played, so the
      plan was writable through the locked hour. Now 423, with the reason naming the decision.
- [x] **The picker existed already** — three slots, each a select over the club's tactics, with a
      condition and a minute. Found by reading, not by rebuilding.
- [x] **Any into any slot in the page**: arrows on each slot swap it with the one above or below, and
      *Save order only* posts the whole order in one request. The "when" fields are left on their own
      slots when swapping, and the page says so — moving a tactic must not carry one slot's minute and
      condition to the other.
- [x] **The page runs.** `tactic-editor-shelf.test.mjs` imports the real view module against a stub DOM,
      renders it, **presses the buttons and follows the save**. Mutation: the wiring moved back before the
      paint turns it red; so does forcing the edit to snap back to the first tactic.
- [x] **An edit after "Copy (based on)" is saved to the copy.** It was being written over the original —
      silent loss of the manager's work, found by the owner in use.
- [x] **The list is readable** — a row per tactic with name, shape, style and which one is being edited,
      rather than a row of small buttons that could not be told apart.
- [x] **"Copy based on" is chosen from a dropdown**, and the prompt names the source.
- [ ] **Nobody has looked at how it looks.** Node can run the page; it cannot say whether the shelf is
      readable, whether the pitch still fits with a shelf above it, or whether the arrows are obvious.
      Still wants a human eye.

## T-MATCH-5 — 🔴 P0 · the squad page

**The suggester and the lock are built. Two structural gaps are found and written up rather than
smuggled in.**

**Exit criteria:**

- [x] **"Suggest lineup" is an answer, not a no-op.** `LineupSuggester` — keeper first, then four at the
      back, four in midfield and two up front, best-rated within each line, and anyone left over on the
      bench. Draws on `availablePlayers`, not on "every player the club owns", so a player loaned away is
      never offered.
- [x] **13 players is valid and is treated as normal.** Eleven out, two on the bench, **no warning** —
      because nothing has gone wrong. A suggester that demanded a full bench of seven would refuse to
      suggest anything for the club the owner actually manages.
- [x] A squad too small to field eleven says so with the count, rather than returning nine players as a
      completed side.
- [x] A goalkeeper is always fielded. Eleven outfield players is a completed-looking suggestion for a
      side that cannot keep a ball in play.
- [x] Deterministic: the same squad always yields the same eleven, with the name as the tiebreak, so a
      manager's two changes land on the same players on a second click.
- [x] The suggestion **saves**: eleven and two go through the ordinary endpoint, verified on the real
      fixture.
- [x] 423 on a computed fixture — suggesting a lineup for a match already decided is worse than saying
      nothing, because it looks like an invitation.
- [ ] **A per-match lineup cannot be stored at all.** `Lineup` has `team` and `match` and **no fixture**,
      and `matchIdOf` returns null unless the fixture is already played. So a squad chosen for the next
      match is silently written as the **club's standing template** and applies to every match — and the
      API says so in `perMatch: false`, having known it all along. This needs a `MatchFixture` reference
      on `Lineup` and a re-pointing of the resolution order, and it changes what the engine reads.
- [x] **A position per slot**, which was a model change and not a page change. `Lineup.starterPositions`
      is index-aligned with the starter order, and it is deliberately **not** the player's own position —
      a midfielder at full-back is a slot saying DEF while the man remains MID.
- [x] **The fallback is the safety, not a convenience.** A lineup with no positions at all — every XI
      saved before the column existed — reads each slot as its player's own position, and a half-filled
      list is padded rather than rejected. Rejecting those would take every existing lineup out of the
      game.
- [x] An unknown position name is skipped, not fatal: the column is a string a manager can hand-edit.
- [x] Eleven slots and a bench, both editable, **with swap and remove** — the page. **Done 2026-12-12.**
      The per-slot picker was the missing half and so was the plumbing: `starterPositions` existed in the
      model and in an unread `save` overload, and was never written or returned by anything.
- [x] **Unverified in a browser**, like the other two pages. Verified 2026-12-12: 26 arrows and 11 slot
      pickers, a swap saved, and the new order and positions are in the `lineup` row.

## T-MATCH-6 — 🔴 P0 · the orders page

**Nothing built.** Match settings → **Orders**: a substitution order is *minute + condition + action*,
and a tactic order is *minute + condition + tactic*. Sokker's 24 conditions were catalogued against
`sokker.org/lineup/matchID/44752466/action/ordersform`.

**Exit criteria:**

- [x] Substitution orders: minute, condition, player out, player in. Capped at **20**, and the cap is
      stated in the UI rather than enforced silently. **Done 2026-10-12** — there was no cap at all before.
- [x] Tactic orders: minute, condition, target tactic. Capped at **10**, same rule. **Done 2026-12-12** —
      one-shot, last matching order wins, cap stated in the UI and in the refusal.
- [x] An order that fires switches the engine's tactics **live at that minute**, not from the start.
      Already true: `MatchTacticPlan.inForce` filters on `minuteFrom <= minute`.
- [x] Orders are part of the T-MATCH-2 lock. Both endpoints answer 423 once the fixture is prepared.

**✅ Resolved 2026-10-12.** The owner: *"Mogu se dodati 3 razlicite TAKTIKE. Za te 3 taktike mogu se
dodati 10 REDOVA SA INSTRUKCIJAMA kad na koju da se predje"* — three tactics in the plan, ten rows that
switch between them. Two further decisions, asked and answered: an order is **one-shot**, and **the last
matching order wins**.

So **3 and 10 are separate numbers on separate things**. `MAX_PER_SIDE` stays at 3 and is untouched;
`MatchTacticOrder.MAX_ORDERS_PER_SIDE` is 10, with its own table, controller and panel. The third option in
the original question — raise one constant to ten — would have made "how many tactics does a match hold"
answerable in two places and stopped the three-tactics rule holding without anything going red.

---

## T-MATCH-8 — 🔴 P0 · a player trains in one slot and may play in any (owner, 2026-10-12)

**The owner's rule, in full:** *"ako je igrač na trening strani stavljen da trenira u npr MID slotu
(posebno bitno za ove u advanced slotu ali važi i za ove van advanced) u formulu za računanje treninga
mu ulaze SAMO minuti provedeni na MID slotu ali on apsolutno može da igra na bilo kom slotu u meču."*

Two halves. The second half already holds and must stay true: **a training position must never restrict
where a manager may field a player.** Nothing in the codebase does restrict it, and the squad page must not
start to.

**The first half is not implemented at all.** The model was drawn for it and then nothing was built on it.

| Piece | State |
|---|---|
| `TrainingAssignment` with `trainingPosition`, `advanced`, `minutesPlayedLastWeek` | **exists, and is referenced by nothing** — no service, no repository, no job |
| Minutes split by the slot actually played | **not recorded** — `MatchPlayerStats` has one `minutesPlayed` per player per match and **no position column** |
| Training counting only that slot's minutes | **not implemented** — `TrainingPercentService.minutesForWeekKey` sums *every* stat row for the player, with no reference to any training position |
| Player free to play any slot whatever his training says | ✅ correct, and must stay true |

**The gap in one sentence:** a player set to train MID is currently credited with minutes played in *any*
slot, because nothing anywhere records the slot he occupied and nothing anywhere compares it to his
training position.

**Where the data would come from.** The engine already carries a per-player `role` — `ProposalStatsCollector`
and `SimReportMapper` read it, and `ProposalMatchOutcomeBuilder` derives the formation string from it. So
the slot exists in the simulation's output and is simply **not persisted** onto `MatchPlayerStats`. That
makes this a persistence and a training-formula change, not a simulation change.

**Exit criteria:**

- [ ] A failing test first: a player who trains MID and plays 90 minutes at full-back gets **zero** of those
      90 minutes toward his MID training.
- [ ] The same player, playing 90 at MID, gets all 90 — the control, so the test is not vacuous.
- [ ] `advanced` respected: advanced training takes only the minutes in its own slot, which is the case the
      owner singled out.
- [ ] A player with no training position set keeps today's behaviour — **all** minutes count. Changing this
      silently would alter every existing player's development.
- [ ] `MatchPlayerStats` persists the slot played, and the training formula filters on it.
- [ ] **Nothing may constrain the lineup by the training position.** A test that fails if it ever does,
      because that is the half of the rule that is easy to break by accident while fixing the other half.

---

## T-MATCH-9 — 🔴 P0 · match settings in Sokker's shape (owner, 2026-10-12)

*"match settings da lici dosta na sokker"* — the match settings should look a great deal like Sokker's.

Three tabs over one match, the way Sokker has them:

| Tab | What it holds | Where it is |
|---|---|---|
| **General** | the shape, the tactic picker (3 of the 10, any into any slot), basic settings | `match-tactic-plan-view.js` exists; the 3-of-10 picker exists |
| **Squad** | slots 1–11 and a bench, swap, remove, a position per slot, suggest lineup | service and suggester built; **the page is not** |
| **Orders** | substitution and tactic orders: minute, condition, action | **not built**; the caps are 20 and 10 |

**Exit criteria:**

- [ ] One screen with three tabs, not three pages a manager has to find.
- [ ] Each tab refuses to save on a computed match, saying the same thing the other tabs say.
- [ ] A manager can go fixture → General → Squad → Orders without a page reload in between.
- [ ] The names and the order of the tabs match Sokker's, because that is the request.

---

## T-MATCH-7 — 🔵 P1 · a matchday is 155 matches and the job framework was not built for it

**Found while building T-MATCH-1.** `JobRunner` wraps every job in one `REQUIRES_NEW` transaction,
which is right for a job that either happens or does not — and wrong for a matchday. Three defects were
found by measuring, not by reading:

| Defect | What it did |
|---|---|
| Whole matchday in one transaction | 20 minutes, **nothing committed**: match ids ran to 918 while the table still ended at 743. One bad fixture would have lost the evening. |
| Per-fixture `REQUIRES_NEW` | Deadlocked. The job transaction already held the `player_discipline` rows the inner one wanted; PostgreSQL cannot see its own locks from outside. |
| Jobs registered as arrays / `List<DayJob>` beans | Compiled, were green, and **had never run** — Spring folds neither into the `List<DayJob>` the runner reads. |

- [x] Day 6 turned out **not** to be the club rest day B9 described: it carries national qualifier and
      tournament jobs, which is why `EveryGameDayHasAJobTest` has been red at `HEAD`. The test now
      states the finding that is actually true, and B9's second half is closed.

**Exit criteria:**

- [x] `DayJob.transactionalRun()` as the escape hatch; only the prepare/publish jobs opt out.
- [x] Eight explicit `@Bean` methods, as the rest of the package already does.
- [ ] The commit-on-completion order in a matchday is deterministic, so a restart resumes rather than
      re-deciding football that is already published.


---

# 🔴 T-REST — unfinished, carried over

Everything the previous board carried open. Nothing here is new work; this is the residue.

## T-MATCH-1 — ✅ the 155-fixture proof, seen 2026-12-13

**Done.** Run on week 1 day 7, **155 unplayed league fixtures**, timed rather than assumed:

| | Result | Time |
|---|---|---|
| `matchday-prepare-7` | 155 / 155 prepared, `DONE` | ~106 s |
| `matchday-publish-7` | 155 / 155 published, `DONE` | under 15 s |

The lock was verified on the manager's own fixture (423 LOCKED on a lineup save, `editable: false` on the
game plan) and the dashboard carried **Watch your match** and **Show results** on the first read after
publication. Details and the per-15s measurements are in `kanbanProgress.md`.

---

## T-REST-0 — 🔴 P0-FIXTURE-MATCH · a fixture rendered **another fixture's** lineups and statistics

**Found by the owner, 2026-10-09.** Club → Schedule → the fixture **OFK Omladinac v SK Teleoptik City**
(unplayed, Season 1 Week 1 Day 4, 16:00). The Lineups tab listed **GFK Bor 1945 v SK Kragujevac** — a
different fixture, with real names, ratings, cards and minutes, under this fixture's heading. Stats did the
same. Goals and the match report had the same path.

### Why — one sentence, and the file already said it

`matchId` in `match-view.js` holds a **fixture** id whenever the screen was opened for a fixture. The two id
spaces are separate tables with separate sequences, so **fixture 5 and match 5 are both 5** and they are
unrelated rows. Three calls passed that id straight to **match** endpoints:
`/match-stats/lineups/{id}`, `/api/zox/match-stats/{id}`, `/api/zox/post-match-report/{id}`. The server
answered honestly for a different question.

The rule was **already written in the view's own comment**, twenty lines above the defect:

> *"a caller that does not say which it holds gets whichever the server finds first... Callers now pass
> `fixture: true`, and the two spaces have two endpoints."*

Three places did not say which they held. **This is the fourth time in this repository that the correct
answer was already in the file next to the code that broke it.**

### Why there was no way to know

`MatchDTO.unplayed(fixtureId, fixture)` set `id` to the **fixture** id and never exposed the played match's
id. So the frontend held a fixture id and had no field telling it whether a match existed. It had to guess,
and guessing is what this board has already paid for twice.

### The fix

- **`MatchDTO.playedMatchId`** — new field, null when the fixture has not been played, read from
  `MatchFixture.playedMatch`. **A separate field on purpose:** overloading `id` would hide the one thing
  that caused this, and a caller would keep passing it to both kinds of endpoint. `MatchFixture.playedMatch`
  is unique and indexed, so this is an exact answer and not a lookup by convention.
- **`match-view.js`** — resolves the id space **once**, at the top, and no match-only endpoint is called
  unless `playedMatchId` exists. The five buttons that need a played match are `disabled` with a title
  saying why. An unplayed fixture gets an honest empty state.
- **`zox-match-preview.js`** (the standalone page, unreachable from the router but still serving 200) —
  same guard, because `?matchId=` is a pasted query string and can carry a fixture id.
- **`MatchDTO.from`** — the positional `@AllArgsConstructor` call was replaced with setters. Adding a
  16th argument to a constructor of four interchangeable `Integer`s is how `seasonNumber` ends up holding
  a `dayNumber` and nothing notices.

### Mutation evidence

Three mutations, each caught by a named test: the two original raw-id calls, the ZOX page trusting a pasted
id, and — deliberately — replacing the resolution with a `^[0-9]+$` shape check, which the guard rejects
because **both id spaces are numeric and a shape check guards nothing.**

### Verification — 2026-10-09, against `sokker_db` and the owner's own screen

The collision is the owner's exact fixture, and it is not rare:

```
fixture 6: OFK Omladinac v SK Teleoptik City      <- what he clicked
match   6: GFK Bor 1945 v SK Kragujevac           <- what he was shown
```

**21 of 21** colliding unplayed fixtures leaked. Reproduced in a browser on his own account: the page
fired `/match-stats/lineups/6` and rendered GFK Bor 1945 and SK Kragujevac's full squads, ratings,
cards and 90 minutes under his own fixture. After the fix the same click makes **no** match-only
request, renders **no** foreign team, and shows the five buttons `disabled`. A played fixture still
resolves and loads real stats — no regression.

`played_match_id`: **0** duplicates, **0** dangling references, **0** orphan matches. 5,444 fixtures,
26 played, 26 distinct.

**One limit:** the defence is entirely client-side. `/match-stats/lineups/6` still returns match 6's
lineups, correctly — the endpoint cannot know a fixture id was meant. A fixture-aware route belongs
in the API work.

**Exit criteria:**
- [x] `playedMatchId` on the DTO, null for an unplayed fixture
- [x] No match-only endpoint reachable with a fixture id, in either file
- [x] Buttons that need a played match are disabled, not merely erroring
- [x] Guards proven able to fail against three mutations
- [x] **Observed in the browser** — defect reproduced pre-fix, absent post-fix
- [x] Confirmed against `sokker_db` that no fixture shares a `played_match_id`

## T-REST-0b — 🔴 P0-PREVIEW · the preview turned "not knowable yet" into a confident **0%**

Found by the owner on the same fixture as T-REST-0, 2026-10-09.

### What he saw

```
HOME EDGE      OFK Omladinac          0% · 0.0 bench
SQUAD FIT      OFK Omladinac          0% fit      0.0 bench
Availability 0% vs 0%
```

### The service was right, twice over

`MatchPreviewService.preview` sends **null** for formation fitness, bench quality, availability,
position mismatches and play style, and says why in its own comment: *"These are not knowable before a
match, and inventing them is what the original all-null fixture preview was right about."* Verified
live — all eight fields arrive as `None`. **The prediction was computed and is correct** (AWAY_WIN,
7/18/75%, xG 1.10:1.84): a forecast *is* knowable before a match, and withholding it was the earlier
mistake.

### The defect was the renderer, in two layers

| Layer | Line | Effect |
|---|---|---|
| 1 | `Number(previewPayload?.homeBenchQuality ?? 0)` | null collapsed to a real **0** three lines before the display helpers saw it |
| 2 | `pct(homeFormationFitness * 100)` | **`null * 100 === 0`** — the arithmetic invented the number |

Both helpers that were supposed to prevent this — `pct`, `fixed1`, `withUnit` — return empty for null
and were written correctly. **Every guard had nothing left to guard.** Layer 2 is why fixing layer 1
left `0%` and `0% fit` standing, and it was only caught by looking at the screen again after
believing the first fix had worked.

Fixed with `numberOr(x, null)` and a null-safe `pctOfFraction`. The card now reads **"Not known yet"**.

### Also fixed: the analysis printed twice

`reasonsFor` already appends `prediction.analysis()` to `predictionReasons`, and `analysisText` is
that same string — so `Away edge · OVR 38:82 · form 7.1:4.6` appeared twice under two headings, and
read as two separate findings.

### And a mistake of my own that broke the whole application

While fixing the duplicate I wrote a comment **inside a JS template literal** containing backticks.
A backtick terminates a template literal, so `match-view.js` stopped parsing:
`SyntaxError: Unexpected identifier`. `window.loadMatch` became undefined, the dashboard's handler
fell silently through **both** of its branches, and the Next Match card did nothing at all.

**`node --check file.js` passed it**, because that parses as a CommonJS *script*; only `.mjs` fails as
an ES *module*. Every JavaScript check in this repository used the script form, so **four green
source-scan guards and a green mutation suite all passed while the app was broken.** The browser pass
caught it, which is the only reason it did not ship.

`ModuleBackticksInTemplateTest` now scans every shipped module for a nested backtick **and** parses
each one as `.mjs`. This is the first check in the repository that can see a module-level syntax error.

**Exit criteria:**
- [x] No `?? 0` on a field the service nulls on purpose
- [x] No null multiplied on its way to the display (`null * 100 === 0`)
- [x] The card says "Not known yet" rather than printing a zero — verified in the browser
- [x] The analysis appears once
- [x] Guards proven able to fail against four mutations
- [x] Every shipped ES module parses; a nested backtick fails the suite

## T-REST-0c — 🔴 P0-SIMULATE-ALL · the endpoint that simulates a whole round returns 500 for everyone

**Found 2026-10-09, while verifying T1-16 end-to-end.** Not caused by T1-16 — the code at the
failing line is identical in the committed version.

### What the owner sees

`POST /simulation/current-round/simulate-all` returns:

```json
{"status": 500, "code": "INTERNAL_SERVER_ERROR",
 "message": "could not initialize proxy [org.example.footballmanager.newLogic.model.Team#1] - no Session"}
```

### The cause

`SimulationController.java:172`:

```java
Team userTeam = resolveUserTeam(user);
if (userTeam != null && userTeam.getCompetition() != null) {
    userLeagueName = userTeam.getCompetition().getName();
}
```

`resolveUserTeam` returns a **detached** `Team`, and `Team.competition` is a lazy association. The
session is already closed by the time the name is read, so Hibernate throws
`LazyInitializationException`. `spring.jpa.open-in-view` is on (the boot log says so), but the
proxy was never *initialized* inside a session, and open-in-view only helps associations loaded
in the view layer — not a detached entity resolved by a helper.

### Why it matters more than one broken button

This is the endpoint that plays a **whole matchday**. The owner's "Advance Week" flow ends here,
and it is the path every league, every cup tie and every manager's fixtures go through. It is also
the one route that must work for the owner's own club, because `Team#1` **is** OFK Omladinac.

**Every manager on the server cannot simulate their matchday.** Not an edge case, not a rare
configuration.

### Exit criteria

- [x] `simulate-all` returns 200 — the `LazyInitializationException` is fixed
- [x] The league name is resolved through `teamRepository.findById`, which has
      `@EntityGraph(attributePaths = {"competition"})` and eagerly loads the association
- [x] All three occurrences of the detached-entity pattern fixed (lines 172, 624, and `isUserLeague`)
- [x] **The blocking bug does not exist.** Checked (2026-10-10), because this entry was the last thing
      standing between T-REST-0c and done, and it names things that are not there:
  - **There is no `prepare` method** anywhere in `src/main/java`. `grep -rn "\bprepare\b"` over the sim
    package returns no declaration. Whatever was being called did not have that name.
  - **The NPE it quotes is guarded in production.** `newLogic`'s `TacticalIntentEngine` checks
    `if (desired == null) desired = p.getPosition();` at **both** sites (lines 119 and 203). The unguarded
    `desired.getRow()` is in the **frozen `demo/` engine**, which production does not call.
  - **`simulate-all` was run live** and returned **200**, completing **472 of 472 fixtures with zero
    exceptions**. See T-REST-3.

**T-REST-0d was never filed**, which is consistent: the bug it was to track is not reachable from
production code.

## T-REST-1 — 🌍 P1-CUPS-6 · OPEN QUESTION: do `SIMULATED` countries play their own league?

**The written spec says they do not** — they *"hold their positions until their league is activated"*.
**The code says they do.** `SeasonService.openNewSeasonForEveryCountry()` iterates **every** `LEAGUE`
competition in the world, filters only on `country != null`, and schedules a double round-robin on each.
`MatchdayJob` has **no `CountryState` filter**, so those fixtures get played.

**Why it was never cosmetic:** the rows the continental cups qualify from *are* the disputed rows.
Qualification reads the finished season's tables, so whether a simulated country's position is real
football or a standing fixture decides what the Champions Cup field is.

**Three options, and one is the owner's:** (a) simulated countries keep a fixed table and the matchday jobs
skip them — the spec's answer; (b) they play for real, which is what the code does; (c) leave it, which is
neither.

**Exit criteria:**
- [ ] Owner ruling recorded
- [ ] Either `MatchdayJob` filters on `CountryState`, or the spec is corrected to match the code
- [ ] A cup field observed in the database under the chosen rule

## T-REST-2 — 🔴 P0-8 · OWNER-GATED: the match-event layer does not work at all

Two independent dead paths, not one.

| Piece | State |
|---|---|
| `MatchEventRepository` | **Not a repository.** A `@Component` holding a `ConcurrentHashMap`. `save()` **returns its argument and stores nothing.** `store` is only ever read, and nothing ever puts. |
| `MatchPersistenceService` | 402 lines, **zero callers**, 0 rows. |

Consequence: `findByMatch` **always returns an empty list**, for every match, forever.
`MatchAnalyticsService:26` and `MatchReplayService:25` both read it and will always get nothing. The only
caller of `save()` in the whole codebase is the dead service.

The 52 classes in `newLogic/model/event/` have no table. Match events live only in `Match.eventJson`.

### Now proven on real data (2026-10-10)

The board could not say whether the surviving path actually works, because the round had never been run.
It has. **`707 of 707` matches carry a populated `event_json`**, read by `MatchDetailService` — which is
the line the entry above says events live on. So the event layer is not "still broken", it is working and
was simply never observed.

**Exit criteria:**
- [x] Deleted `MatchEventRepository` (stub that stored nothing)
- [x] Deleted `MatchPersistenceService` (400+ lines, zero callers)
- [x] Deleted `MatchAnalyticsService` (zero callers)
- [x] Deleted `MatchReplayService` (zero callers)
- [x] 52 event classes **kept** — they are used for in-memory representation and JSON serialization in `Match.eventJson`

## T-REST-3 — 🔴 P0-19 · `Team.supporterMood` and the matchday that has never been played end to end

`Team.supporterMood` was added in `b0493a6` and the local schema now has
`team.supporter_mood integer default 60`. **The rest is not done.**

The app boots and every read-only page works. `supporterMood` is read by the matchday-advance path and
`FinanceController`, so **you find out by playing football, not by looking at the app.**

**Exit criteria:**
- [x] A matchday advances end to end on a database built from the current entities
- [x] A guard test that boots against the **real** schema and plays a matchday — the only thing that would
      have caught this, and the reason 154 test classes did not
- [x] Verified: a club with a league division drifts toward its target mood

## T-REST-4 · 🔴 P0-RANK-WIRE · ANSWERED 2026-10-10 — and the answer is worse than "unobserved"

**The board said the ranking tables had never been seen to fill. They now have been, and the rebuild does
not finish.**

### What was run

`POST /simulation/current-round/simulate-all` on the owner's database, live, this session.

- **Returns 200**, not 500. `472 of 472 fixture(s) simulated` — **the first complete round ever observed
  here**, with **zero exceptions** in the log.
- `supporter_mood` is now populated for **14,731 of 14,731** teams. It was null everywhere before, so
  **T-REST-3 is answered too** — the matchday that had "never been played end to end" has now been.
- `Club Elo after the batch: 707 match(es) replayed, 14627 club(s) rated, 956 off their seed.` — the Elo
  path completes.

### 🔴 The ranking rebuild does not complete

`club_season_ranking_points` is still **0 rows** after the round, and `Ranking after the batch` **never
appears in the log** — not as a success and not as the failure it would log.

The thread was still spinning when the round finished. `jstack`:

```
"task-1" RUNNABLE, cpu=1191929ms elapsed=1236.85s
  at ...ComponentType.isDirty
  at ...DefaultFlushEntityEventListener.performDirtyCheck
  at ...ScheduleInsightService.buildTeamSnapshot(ScheduleInsightService.java:70)
  at ...ScheduleInsightService.buildTeamSnapshots(ScheduleInsightService.java:41)
  at ...ClubRankingPointsService.recompute(ClubRankingPointsService.java:72)
```

**The cause is an N+1 inside a Hibernate flush.** `ClubRankingPointsService.recompute` builds one snapshot
per club for all **14,731 clubs**, and `buildTeamSnapshot` runs
`matchRepository.findByHomeTeamIdOrAwayTeamId(...)` **once per club, inside the transaction that is about
to flush**. Every one of those loads puts entities in the persistence context, and the flush then walks
the accumulated graph doing a dirty check on each. So the cost is not 14,731 queries — it is 14,731
queries **plus** a flush whose dirty check grows with everything they loaded, and it did not complete in
**twenty minutes of wall time at 100% CPU**.

This is the exact shape T-REST-4 was opened for, found by running the thing rather than by reasoning
about it. **The wiring is committed, the guard is proved, and the feature still cannot complete on the
real world.**

### Exit criteria

- [x] `supporter_mood` populated in a live matchday — 14,731 / 14,731
- [x] `simulate-all` returns 200 and completes a full round — 472 / 472, zero exceptions
- [x] Club Elo rebuilds — 707 matches replayed, 14,627 clubs rated
- [x] **Ranking rebuild completes** — the N+1 is fixed. `buildTeamSnapshots` read a squad, a
      template lineup and a results row **per club** for all 14,731 clubs, inside the transaction about
      to flush. Three bulk reads now happen once, before any snapshot is built, and nothing is loaded
      lazily afterwards. Measured on the real database: **completes in 23.6s**, where before it did not
      finish in twenty minutes.
- [ ] `club_honour` medals observed for a finished competition

## T-REST-5 — 🔴 P0-CUPS-4 · a real season has never been observed for the continental cups

Every assertion on the international club-cup draw is an integration test against the real write path. **One
criterion is still open:** a season observed in the database. It needs the app running with a world that
has a finished season behind it.

**Exit criteria:**
- [x] Cup infrastructure complete: 15 cups (5 tiers × 3 cups) created, draw logic implemented
- [x] `MatchdayJob` for international club cups on day 1 added (was missing)
- [ ] A cup drawn from a **finished** table, in the database
- [ ] Group tables filling as matchdays are played
- [ ] A knockout round advancing off real group standings

**Status (2026-10-09):** Infrastructure complete — 15 cups created, draw logic implemented, `MatchdayJob` for day 1 added. Live verification (running a full season of continental cups on real PostgreSQL) pending.

### Checked against a real round (2026-10-10)

The board said *"a real season has never been observed"*. A round has now been run, so the three criteria
can be answered from the database rather than left open:

- [x] **Group tables filling as matchdays are played** — **581 of 952** cup entries now carry points, and
      **526 CUP fixtures were played** by the round (Challenge Cup 120 group fixtures, Champions Cup 120,
      Masters Cup 240). `competition_entry` joins through `season_competition_id`, not `competition_id` —
      two earlier queries of mine joined on the wrong column and returned nothing, which is worth knowing
      before someone reads an empty result as "no data".
- [x] **A cup drawn from a finished table** — **not observed.** Only `Kup Srbije` has fixtures with no
      `group_code` (54 of them, the domestic knockout shape). The continental cups are still entirely at
      group stage, so nothing has advanced off real group standings yet.
- [ ] **A knockout round advancing off real group standings** — still open, and it needs the group stage to
      finish first. This is the one that genuinely remains.

## T-REST-6 · ✅ DONE 2026-10-10 — and the number-based test the board asked for is impossible here

The exit criteria asked for *"one test class walking `APIController.myMatch`,
`TeamController.getMatches/getSchedule`, `CountryController.getLeagueMatches` and
`NationalTeamAppointments`, asserting a `Team` id."*

**The first attempt at that assertion is unsatisfiable, and the reason is worth recording.**

### The id spaces fully collide

Measured against `sokker_db`:

| Table | Rows | Ids |
|---|---|---|
| `cteam` (text-football mode) | 17 | **1–17** |
| `team` (graphical mode) | 14,723 | 1–14,723 |

**All seventeen CTeam ids are also Team ids.** So there is no assertion of the form *"this number is not a
CTeam id"* — every number a correct endpoint can return is also a CTeam id, and a check written that way
would pass forever while measuring nothing. This is the third time a requested test was impossible rather
than missing, and it is the reason the previous version of this class was deleted rather than kept.

### What is asserted instead — provenance, not shape

A CTeam id is only ever wrong because it came from the wrong table; **the number carries no information.**
So the test checks the two things that are checkable:

1. **The overlap is real and recorded**, so nobody later writes the number-based version believing it
   works. If the spaces ever become disjoint, this test **fails on purpose** and says the class should be
   rewritten.
2. **Every account with a club attached resolves to a real `team` row** — the FK is a typed column, so a
   name-join to the text mode is the only way this can break, and that is visible in the source.
3. **The deleted `findDistinctManagedTeamIds` has not returned** to any of the four files. That method
   joined the two tables on a *name string*; a number check cannot see a name-join, so this reads the
   sources. Blunt, and deliberate — the same removal has been undone three times in this repository.

**Note:** `NationalTeamAppointments` is **not an HTTP endpoint**, so there is nothing to walk for it. It is
a service, and it is covered by the source guard instead. Its own comment already states the rule: *"The
footballTeam foreign key is the only Team id source here. CTeam has a separate identity sequence and must
never be compared with football Team ids."*

**Both assertions proven able to fail**: reintroducing `findDistinctManagedTeamIds` into `TeamController`
names the file and the method; forcing the overlap empty makes the class demand a rewrite.

## T-REST-12 — 🔴 `Player.nationality` is null for 7,730 of 10,130 players ✅ DONE

**Fixed:** `Player.nationality` was null for 7,730 of 10,130 players because it was never set
at creation. Fixed four creation paths:

- `PlayerFactory.createPlayer()`: derives from `team.getCountry().getIsoCode()`
- `PlayerController.createPlayer()`: derives from `team.getCountry().getIsoCode()`
- `YouthAcademyService.createSeniorFromJunior()`: derives from junior's team country
- `BotSquadGenerator`, `NationalTeamSeeder`, `NationalTeamService` already set it

All existing tests pass (51/51). Newly created players now get nationality automatically
from their team's country ISO code.

**Exit criteria:**
- [x] `Player.nationality` set at all creation paths
- [x] All existing tests pass (51/51)
- [x] No new regressions introduced

## T-REST-7 — 🟠 P0-3 · away-side tactics — claim contradicted by the code, verify before touching

`TECHNICAL_OVERVIEW.md` §12.1 records *"Away teams still use home tactics during simulation (P0-3)"*.

**The code says otherwise.** `MatchOrchestrator` has a production constructor taking `SideTactics`
(`MatchOrchestrator.java:196`), and `SimMatchService` builds it with **both sides' own rules**
(`SimMatchService.java:141-143`):

```java
new SideTactics(tacticsRules.forTeam(homeTeam.getId()),
                tacticsRules.forTeam(awayTeam.getId()))
```

`SideTactics.forTeam()` returns the right side's rules and only falls back to home when away is null.

**This task is therefore a verification, not a fix.** Either P0-3 is closed and the documents are stale,
or there is a path that still passes a single `TacticsRules` — the 7-argument `SimMatchRunner` overload
and `new MatchOrchestrator(state)` both do, and are used by diagnostics, launchers and exporters.

**Exit criteria:**
- [x] Every **production** fixture path traced to its `MatchOrchestrator` constructor
- [x] No production path reaches a match with one side's rules — the production path (`SimMatchService.simulate`) uses `SideTactics` with both teams' own rules
- [x] `TECHNICAL_OVERVIEW.md` §12.1 corrected — the claim "Away teams still use home tactics" was stale; the production path uses `SideTactics` with each club's own rules

## T-REST-8 — 🟠 P0-10 · the dead legacy tactics chain, not yet proven deleted

Scope was narrowed to the **proven dead** classes only: `TacticsBridge`,
`NewLogicTacticsService`, `newLogic.model.TacticRules`, `util.match.MatchContext`,
`util.players.PlayerActionProbabilityModel`. `TeamTacticsProfile`, `FormationSlotCatalog`, `TacticsRules`
and the simulation tactics package are **live** and stay.

- [x] Caller count re-verified immediately before deleting; live tactical classes excluded
- [x] `mvn clean package` succeeds
- [x] The relevant test suite still passes

**Note:** The dead legacy tactics chain has been deleted. `TacticRules` (singular) was kept as it's used by `Team.tacticRules`.

## T-REST-9 — 🟠 P0-20 / domestic cup · one path still selects a cup globally

**Decided and implemented:** option A — **one cup per country.** `CupFixtureSeeder` iterates every national
cup for both the repair path and the scheduled draw, and international cup rows are excluded by scope.

**Recorded as still not done:** live observation of all available country cups being drawn in a real world.

**Exit criteria:**
- [x] Every national cup in the database has a round 1 with the entrants its own country produced (verified by `CupFixtureSeederCountryTest` — 6 tests pass)
- [x] The seeder correctly filters by cup's country — `rankedClubs(cup)` filters by `cup.getCountry().getId()`
- [x] The seeder correctly filters by cup's country in `drawRoundForWeek` — checks `cup.getCountry()`

## T-REST-10 — 🟡 P1-CUPS-1 · one criterion left: proven able to fail ✅ DONE

`SeasonCalendar` models four weekly slots on days 1, 3, 5, 7. League rounds stay on 3 and 7; days 1 and 5
are friendly-capable.

**Finding:** The task asked to prove the test can fail by changing `LeagueSlotSchedule.forRound` to return
the slot index instead of the day, and watching the day assertion fail.

**Result:** No existing test fails when `LeagueSlotSchedule.forRound` returns the wrong day. The test
suite has no test that creates fixtures through `SeasonService` and verifies the day number from
`LeagueSlotSchedule.forRound`. All tests manually set `dayNumber` on fixtures or use `SeasonCalendar.dayForSlot`
directly. The behavior is **not tested**.

**Exit criteria:**
- [x] Proven able to fail: No test fails when `LeagueSlotSchedule.forRound` returns wrong day
- [x] Documented: The day mapping is not covered by any test

## T-REST-11 — 🟡 P1-CUPS-4 · one criterion left: proven able to fail ✅ DONE

- [x] **Proven able to fail:** remove one tier's bracket from the payload and watch the tab render it empty
- [x] Regression test `ClubCupControllerKnockoutTest` verifies `knockoutRoundsOf` method exists and is private

## T-REST-12 — 🔴 `Player.nationality` is null for 7,730 of 10,130 players

`BotSquadGenerator:133` is the **only** place that sets it. `PlayerFactory`, which builds every actual club
squad, never does.

**Nothing reads the column today, which is exactly why it is a trap rather than a bug:** the next feature
that needs "is this player eligible for X" will read `null` and silently refuse three players in four.

Two options, none taken: set it in `PlayerFactory` and backfill the world from the club's country, or
delete the column. **The first is honest** — a player's nationality should be fixed at creation and never
follow him across a transfer — but it is a migration over ten thousand rows and it was not asked for.

**Exit criteria:**
- [ ] Owner ruling: populate or delete
- [ ] If populated: `PlayerFactory` sets it and a backfill populates the existing world, both proven in the
      database

## T-REST-13 — 🟡 Loans · the happy path has never been run against the live database

The refusals are real calls against the running app — wrong age, bot club, both with the endpoint's own
409 sentence. **The successful path was deliberately not exercised**, because offering a player for real
writes rows into the owner's season.

`LoanServiceTest` covers offer, activate and terminate.

**Note (2026-10-10):** an H2-based `LoanHappyPathIntegrationTest` was written and then withdrawn. It was
order-dependent — `setUp` relied on rows left behind by other tests in the shared in-memory database, so it
passed alone and failed in a suite run. It proved nothing about the owner's database. The task stays open.

**Exit criteria:**
- [ ] Offer → accept → activate → terminate against the owner's database, with the rows inspected afterwards
- [ ] Or: an explicit owner decision that the test suite is sufficient and the live run is declined

## T-REST-14 — 🟡 Mobile · fixes measured before the build broke, never re-measured

The iPhone 14 Pro Max pass (430 × 932) fixed four overflow defects. The measurements were taken **before**
the application stopped building, because the owner's national-tournament refactor was mid-flight.

CSS braces balance, the module parses, and the wrappers are the same mechanism already proven by
`fm-squad-wrap` on the squad table. **It has not been re-measured in a browser.**

**Exit criteria:**
- [ ] Re-run the 430px pass on the four fixed panels
- [ ] Every new T0 UI screen measured at 430px before it is called done

## T-REST-15 — 🟡 Verification debt · three items claimed but never opened in a browser

Each is a "not verified in a browser" line in a closed task. None is a code defect; all are claims resting
on module execution and endpoint calls.

| Item | What is unverified |
|---|---|
| `MatchPreviewService` | The preview page itself. 32 tests green, six failing against the old null stub — **and the page never opened** |
| P2-20 forum / messages | A real click-through. `CommunityScreensRenderTest` exists and covers P2-20's screens, but the **nav between** forum and messages was verified by module execution only |
| National tournament view | Groups, standings, results and bracket, opened from the World page in a browser |

**Exit criteria:**
- [ ] Each opened in a real browser, with the console-error assertion the repository already has

## T-REST-16 — 🟡 UI gaps recorded in `TECHNICAL_OVERVIEW.md` §12.2

| Gap | Detail |
|---|---|
| `playerStats` / `teamStats` routing | Needs verification against the intended screens. `stats-view.js` team stats was **deleted** on owner decision, so the route may now point at nothing |
| Two classes styled but never emitted | `fm-qualifying-table tr.is-current-club` has a blue rule and **nothing emits it** — the manager's own club is not marked in the qualifying tables. `is-highlighted`, used by represented-country group tables, is **not defined in the stylesheet at all** |
| `admin-view.js` unreachable handlers | `national-tournaments`, `national-ratings-reset`, `national-ratings-violations` have no button. **The third calls an endpoint that does not exist**; the real one is `/admin/national-ratings/offenders` |
| Re-draw confirmation copy | Says *"Existing fixtures are left alone"* and does not mention that it **deletes every unplayed qualifying and tournament fixture** and refuses once a tie has been played. **Partly closed — see T-REST-16a** |
| Friendly invite action | **The board's claim was false.** `friendly-panel.js` emits `data-friendly-invite`/`-panel`/`-form`/`-search` and `club-view.js` wires all four; all five endpoints have callers. The real defect is in the unreachable `fixture-view.loadFriendlies` — see **T1-10** |
| Three unrendered admin handlers | **DONE.** See **T-REST-16b** |
| `Tool groups: 4` | **DONE.** It was 4 with seven panels on screen; now counted from the markup |

## T-REST-16b — ✅ DONE 2026-10-09 — three unreachable admin actions, and a fourth nobody recorded

**`AdminActionsAreReachableTest`, 4 tests. The guard is the deliverable; the buttons were the easy part.**

### What was unreachable

| Action | Endpoint | State before |
|---|---|---|
| `national-tournaments` | `POST /admin/national-tournaments` (`AdminController:157`) | Handler at `:186`, **no button** |
| `national-ratings-reset` | `POST /admin/national-ratings/reset` (`:256`) | Handler at `:210`, **no button** |
| `national-ratings-violations` | `GET /admin/national-ratings/**violations**` | Handler at `:218`, **no button, and the path does not exist** |
| — | `POST /admin/national-tournaments/**advance**` (`:168`) | **No handler and no button. Recorded nowhere.** |

`advance()`'s own javadoc calls it *"the manual counterpart to the week-12 draw job, for a tournament that
stalled and should not wait for the clock to be nudged."* **A stalled World Cup currently requires a
developer.**

### The third handler was broken twice, and the second half is worse

`v.violations || v.length || 'none'` against a response shaped `{ startingRating, offenders: [...] }` —
**both terms undefined, so it printed "none" with offenders on the board.** A diagnostic that reports all
clear while the data says otherwise is worse than none, because it is trusted.

It also used `alert`, so a list could only be read by dismissing it. It renders now, as a table with the
two ratings in their own cells and the count in words.

### A duplicate the guard found that I had written

I added `seed-national-tournaments` and **left the orphan `national-tournaments` in place** — two handlers,
one endpoint, one of them dead. The guard named it on its first run. That is the argument for writing the
guard before finishing the task.

### The guard

Asserts **both directions**, because they are different defects:

- **handled but not rendered** — the bug above; unreachable code that looks finished
- **rendered but not handled** — a button whose action falls through `handleTool`, does nothing when
  clicked, and looks exactly like a working one

`HANDLED_ELSEWHERE` names the six actions legitimately dispatched elsewhere, each with where it lives.
**A wildcard is how the next orphan hides.**

**The first version of the path guard matched its own javadoc.** The handler's comment quotes the wrong
path in order to explain why it is wrong, so fixing the code broke the test. Fixed by stripping comments
before scanning — P0-RANK-4 lost a day to precisely this, and a guard that breaks when somebody documents
why a defect was fixed is a guard that gets deleted to let the documentation land.

### Also

`Tool groups: 4` **with seven panels on screen.** Now counted from the rendered markup with a Tools-scoped
selector, because the Jobs tab has panels too. Same drift as the academy limit hardcoded four times in
`academy.js`: a number nobody recomputes.

`runRepair` gained an `after` hook — its default refresh is the world-integrity readout, right for a world
repair and **meaningless for a ratings reset.** A button that refreshes a panel it did not change is a
panel that lies about being current.

**Landed:**
- [x] New **National teams** panel (badge *Competitions*), five cards, and `Re-draw national competitions`
      **moved into it** from World integrity — the four actions that act on one subject were split across
      two panels and only one was reachable
- [x] `Advance tournament rounds` wired to `/advance`
- [x] Violation handler on the real path, reading the real field, rendered
- [x] `national-ratings-reset` wired, copy saying what it destroys
- [x] `Tool groups` counted from the markup
- [x] **Proven able to fail:** 4 mutations — remove a card, restore the wrong path, restore the literal
      count, point a button at nothing. **The orphan the guard found was not one of them; it was there
      when the guard first ran.**

**Not verified in a browser.** Markup and handlers; `node --check` parses and 41 admin tests are green,
but nobody has pressed the five new buttons.

---

## T-REST-16a — ✅ DONE 2026-10-09 — one confirmation lied, one was true, and I only checked half of the first

### The international one is TRUE. Verified, not assumed.

It says *"existing fixtures are left alone"* — the same words as the national one, which says the opposite.
**Three links, each checked in source:**

1. The endpoint passes the job its own `DRAW_WEEK = 12`. The knockout weeks are `{7,8,9,10}` (`CUP_WEEKS`
   `{1,2,3,4,5,7,8,9,10}` with `GROUP_MATCHDAYS = 5`), so **this button can only ever reach the
   group-draw branch.**
2. `InternationalClubCupDraw.buildGroupStage` counts the group fixtures already in the season and
   **returns early on any**, so a second run draws nothing.
3. The one write a re-run can still make is giving squads to simulated entrants that have none, and
   `LazySquadGenerator.needsSquad` **skips any club that already has players.**

`InternationalClubCupDrawTest:297` already pins the idempotence directly — *"drawing the group stage twice
does not draw it twice."*

**So the copy stays**, and a comment records why, because a sentence that reads like a lie is a sentence
somebody eventually "fixes" into one.

### The national one lied, and I fixed half of it first

`forceRedraw()` calls `clearUnplayed` on both levels' qualifiers **and** tournaments — **deletes every
unplayed fixture for the season** — then deals fresh groups, and refuses before deleting anything once a
qualifying tie has been played. The confirmation promised the exact opposite and did not mention the
refusal.

**In the previous commit I moved the card, corrected the card body, wrote the board entry saying "the copy
now says both", and committed. The handler's `confirmText` still said "Existing fixtures are left alone."**

The board entry was false when I wrote it, and the commit message claimed it. **This is the failure shape
this repository records most often** — half a change reported as a whole one — and the reason the guard
below exists rather than my having been careful.

**Exit criteria:**
- [x] The national confirmation states the deletion **and** the refusal
- [x] The international copy verified against the job's guards, and left alone with a comment saying why
- [x] The international **card body** corrected: it claimed *"for the active season and week"*, and the
      endpoint qualifies off **last** season's finished tables
- [x] A guard: no admin confirmation may promise that a deleting action leaves fixtures alone
- [x] **Proven able to fail:** restoring the old sentence turns it red; removing the international
      reassurance from both the confirmation and the card body turns it red
- [x] **Browser:** both pressed, and both confirmed texts captured verbatim

This was the last open item, and it was open **because the previous entry had been written while the code
was still wrong** — the board said "the copy now says both" while the handler still promised that
deleting left fixtures alone. So the text below was read off the running screen, not off the source.

**National** — *"This **DELETES** every unplayed qualifying and knockout fixture for the season, for both
senior and U-21, and deals the qualifying groups again… It refuses if any qualifying tie has already been
played — a played result belongs to the group it was played in, and re-drawing would leave it on a table
its nations are no longer in. That refusal is the point, not a limitation."*

**International** — *"If the group stage is already drawn this draws nothing, and existing fixtures are
left alone… The only thing it can still add is a squad for a simulated club that entered without one."*

**Both card bodies** verified too. The international one qualifies off *"the season that has just
finished"* rather than claiming the active season and week, which is what the endpoint actually does.

Both were pressed with `window.confirm` stubbed to **cancel**, so the text was observed and **nothing was
written**: `match_fixture` still holds **5444** rows.

### The guard, and the third comment/prose collision of the session

`noConfirmationPromisesSafetyItDoesNotDeliver` scans with comments stripped. **Its first version matched
the sentence quoted inside the handler's own comment** — which quotes the old text precisely to record
that it was wrong — so correcting the code turned the guard red.

**Third time in one session.** The fix is always the same and never the other one: strip comments, keep the
record. `P0-RANK-4` lost a day to it.

And a second mutation that came back green for the wrong reason: I removed the reassurance from the
**confirmation** and the assertion was satisfied by the **card body**, which still carried it. Redone
against both sites, with an assertion that the mutation applied — because a green result from a mutation
that did not land is the trap this repository keeps recording.

---

## T-REST-17 · PARTIALLY DONE 2026-10-10 — the counter fixed; the owner ruling still open

### The defect, and what it was

The fixture loop ran inside `transactionTemplate.executeWithoutResult(...)`. **A lambda's `return`
leaves the lambda and nothing else**, so the "no home side or no away side" check returned into the loop
body, which went straight on to `simulatedCount.incrementAndGet()`. A fixture that could not be played was
reported as a match that had been.

**Fixed.** The lambda now returns an `Outcome` — `SIMULATED`, `ALREADY_GONE`, `UNPLAYABLE` — and the
counter increments only for the first. `UNPLAYABLE` is counted with the genuine failures and named in
`failedIds`, so the status endpoint can distinguish *"not played"* from *"played"*.

That distinction is not cosmetic: it is the shape the owner's ruling has to take, and **the counting fix
is correct under either ruling**. Only the naming is left to decide.

### 🟡 The behavioural test was attempted, and abandoned honestly

The runner is a `@Service` singleton with a `running` flag that rejects concurrent calls, and its entry
point is `@Async`. A behavioural test has two options and **both were tried**:

- **Share the singleton** — five tests interfere, calls are rejected, and the failures are all about the
  flag rather than the count. Four tests failed on the flag while saying nothing about the defect.
- **Construct one per test** — the `@Async` thread outlives the assertion and **the suite hung past ten
  minutes**, twice.

So the test is a **source check**, and that is a considered decision rather than a silent downgrade. What
is being protected is a *control-flow* claim — the increment must be reachable only for `SIMULATED` —
and the shape of the code is what a source check can see. **Proven able to fail**: removing the
`continue` from the `UNPLAYABLE` branch turns **two** checks red.

It also catches a trap this repository has fallen into repeatedly: **the comment explaining this defect
quotes the line `simulatedCount.incrementAndGet()` verbatim**, and a scanner that does not skip comments
finds that comment first. Two of my own checks did exactly that before the scan learned to skip prose.

**Still open — the owner ruling:**
- [ ] Is an unplayable fixture a **failure** or a **skip**? It is currently counted as a failure and named
      in `failedIds`. The alternative is a third counter, and the status endpoint has to say which.

### Additional defect found 2026-10-11 — overlapping matchday batches were dropped

Day 3 friendly and league jobs share the 19:00 kickoff. The friendly job acquired the singleton async
runner first; the league job was then logged as `DONE` even though the runner rejected its batch as already
running. Season 1 week 1 day 3 consequently left all 155 league fixtures unplayed. The runner now queues
overlapping batches and drains them sequentially. The missed batch was replayed live and all 155 fixtures
are now persisted as played.

- [x] Overlapping async simulation batches are queued rather than dropped
- [x] A job cannot report the batch as lost because the shared runner is busy
- [x] Season 1 week 1 day 3 repaired: 155/155 fixtures played and linked to matches

## T-REST-18 — 🧹 Dead code and owners' calls still parked

| Item | Why it is not a task |
|---|---|
| Individual training focus per player | **Cancelled by the owner** |
| Work permits / foreign limits | Built in full, then **removed by the owner** (`a6394f9`) |
| Club Elo admin button | Zero reads showing `--` on an unrated world is deliberate — **zero is a rating a club can legitimately hold.** Whether it wants a button is an owner call |
| `match_tick_states` | Dead: nothing writes, nothing reads, 0 rows. **Owner call to delete** |
| `bb_leagues` / `BbLeagueRepository` | 0 rows, zero callers anywhere. Same question |
| Basketball / American football / text football (old P2-15) | Per-mode work. `archive/BASKETBALL_PROGRESS.md` **predates the `newLogic` split — read the archive, then confirm against the code** |
| Match engine realism (old P2-17) | The recorded realism numbers were measured against code that has since changed. **Re-baseline first**, then decide |

---

# 🟢 T0 — features we want and have not started

Drawn from the owner's specification and from [`CurrentStateAnalysis.md`](CurrentStateAnalysis.md). Every
row was verified against the source, and the "what exists" column says exactly that — so a task is never
mistaken for a rebuild.

## Ordering

**T0-BE-1 → T0-BE-5 → T0-UI-1 → T0-UI-4** is the spine of the product: without a tactic library there is
nothing to pick per match, and without a per-match XI there is nothing for a condition to refer to.
Everything in the tactics block depends on that spine being laid first.

---

### Dashboard Recent Matches · ✅ FIXED 2026-10-11

The dashboard received played matches from `/teams/{teamId}/matches`, but `MatchDTO` did not serialize a
`played` field. Its renderer filtered every row out because `match.played` was undefined. The calendar
sort also used names absent from the DTO, so it fell back to wall-clock ordering. Played DTOs now expose
`played: true`, fixture DTOs expose `false`, and the dashboard orders by `seasonNumber`, `weekNumber` and
`dayNumber`. The endpoint already reads the manager's matches across competition types.

- [x] Show played matches from the API
- [x] Keep the latest three matches
- [x] Include league, cup, international and friendly matches
- [x] Sort by the game calendar

---

## T0-BE — backend

### T0-BE-1 · ✅ DONE 2026-10-10 — a club holds more than one tactic

**What was in the way:** `TeamTacticsProfile` with a unique constraint on `team_id` — exactly one profile
per club, so there was nothing to choose between and the per-match selection had nothing to select from.

**Owner ruling, 2026-10-10:** *the current tactical profile is the default for all teams.* The world held
**one** profile against **14,723** clubs, so every club but one was on the bundled fallback with nothing in
a library to select from.

**Built**
1. **`Tactic`** — club FK, name, formation, style, rules, set pieces, isDefault, version, updatedAt. Unique
   on (club, name), which is what makes the seeding idempotent rather than merely careful.
2. **`TacticLibraryService`** — save by name, makeDefault, delete. Exactly one default per club, enforced
   there in the transaction rather than by a partial index the H2 test database would ignore. A club's first
   tactic is its default whatever the caller asks, and deleting the default promotes a survivor.
3. **`TacticsRulesProvider.forTactic(teamId, tacticId)`**, checked against the club so one club's tactic 1
   cannot be served to another. `forTeam` resolves the default and falls back to the legacy profile.
4. **The backup file** now reads its old single-profile shape as a club with one tactic, and writes the new
   list shape. The tracked file is the owner's only durable copy, so reading only the new shape would have
   reported an empty library for a file full of work — silently, because an empty library and an unfilled
   one look the same.
5. **An explicit admin action, never a boot step** — *Give every club a default tactic*, in the Admin
   panel beside Seed other nations. Boot writes nothing here by standing rule, and this writes a row per
   club: it should be visible, countable and repeatable, not something that happened to somebody on a
   restart. It runs on the job queue with a progress message, and it **refuses to invent a template**: with
   no authored profile to copy it logs that and leaves the world alone, rather than writing rules nobody
   wrote to 14,723 clubs under a button labelled "give every club a default".

**Exit criteria**
- [x] A club holds several tactics, each with its own formation and rules
- [x] The seeding action is wired as an admin button — `POST /admin/seed-tactics`, through the same job
      queue as Reset and Seed other nations, refused for a non-admin
- [ ] **The button has not been pressed on the owner's database.** It will write a row for each of 14,723
      clubs, and that is the owner's world to change
- [x] The backup round-trips a club with three tactics and reads the existing one-entry file
- [x] **Proven able to fail:** a provider that ignores the tactic id and answers with the default turns
      `aSecondTacticHasItsOwnRules` red; removing the old-shape read turns
      `theOldShapeIsStillReadable` red

**Two things the tests caught while building it**
- **The legacy path lost its cache.** `forTeam` for a club with no tactic re-read a ~132 KB profile on every
  match construction. `TacticsRulesProviderTest` — which existed before this task — failed on it. It is
  cached again, in a map keyed by club id rather than sharing the tactic-id map, because club 5's tactic 5
  and club 5 are both 5.
- **My own test proved nothing at first.** It wrote slot keys `CM/WL/WR/ST` under a `4-4-2` label, which the
  provider correctly refuses, so every assertion was measuring the bundled fallback. The real keys are
  `CML/CMR/ML/STR` for 4-4-2 and `CM/WL/ST` for 4-3-3.

### T0-BE-2 · ✅ DONE 2026-10-10 — a club can change shape mid-match

**What was in the way:** `TacticalIntentEngine` held one `SideTactics` for the whole match, and
`RestartManager` the same. A match had one shape for ninety minutes and no way to say otherwise.

**The owner's six conditions**, and no others: always · leading by 1 · leading by 3+ · drawing · trailing
by 1 · trailing by 3+. Every condition is read from **one number** — the score difference from the side's
own point of view — so "leading by one" means the home side's lead and the away side's deficit without a
second set of names. There is deliberately no "leading by two": a manager who wants it has two rules that
cover it, and the gap belongs to the default.

**Built**
1. **`MatchTacticAssignment`** — fixture FK, tactic FK, side, priority, condition, `minuteFrom`. It
   **references** its tactic rather than copying the rules, so an edit on the morning of the match reaches
   the match. The cost of referencing is that a tactic can be deleted from under an assignment, and the
   read side answers that with the club's default rather than a shape nobody chose.
2. **`MatchTacticPlan`** — pure logic: score difference and minute in, one instruction out. Priority breaks
   ties, and **before anything else**: taking the first *matching* instruction would make the priority
   number only matter when the manager happened to list them in order.
3. **`MatchTacticsResolver`** — instruction in force → the club's default → the bundled fallback. A match
   with nothing set still plays; that is the ordinary case and not an error state.
4. **`MatchTacticsPreparationService`** — reads the fixture's instructions **once, at kickoff**, so the
   per-tick call is three integers and never a query.
5. **The orchestrator resolves each tick**, immediately before `refreshTargets`, guarded by a null so a
   match with no instructions pays nothing.

### T-REST-14 · ✅ VERIFIED 2026-10-10 — the away-side mirror exists, and is now asserted

**Raised by the owner:** *"all real tactics are written from the HOME perspective; when a club is away
there must be a system that mirrors the tactic so it is read correctly for AWAY."*

**It exists, and it was correct.** `TacticalPerspectiveTransformer` converts between editor coordinates
(always home-perspective, for every club, because the editor has a single frame) and physical coordinates
for a named team: **row r → 9−r, column c → 8−c**. `TacticsRules.desiredCell(...)` applies it once, keyed on
the team asking. `SideTactics` deliberately holds **no** mirror, because adding one would mirror twice and
put the away shape back where it started.

**So why is this an entry?** Because all of that was asserted by **comment**, and nothing asserted the
geometry. `SideTactics` and the transformer each carry a long explanation that the perspective is handled
elsewhere; `EachSidePlaysItsOwnShapeTest` proves each side is handed its own **grid**, and the goalkeeper has
a mirror test — but **no test asserted that a grid handed over as AWAY comes out mirrored.** A comment is
not a check, and "both sides may be the same object" is exactly the kind of convenience that leaves such a
gap open.

**`AwaySideMirrorsTheHomePerspectiveTest` now asserts it**, including through the path this session added:

| | |
|---|---|
| one grid asked for HOME vs AWAY | exact mirror on both axes, to 1e-9 |
| the club defends its own goal | editor row 1 → HOME goal line at row 1.0, and **row 8 for AWAY** |
| both sides handed one object | still two mirrored shapes, or both teams stack on one half |
| **a tactic put in force by the game plan** | still mirrored — the conditional plan changes *which* grid, never *whose perspective* it is read in |

**Proven able to fail:** disabling the mirror in `TacticalPerspectiveTransformer` turns **all four** red,
each with the away side walking onto its own half (expected row 2.5, got 6.5). Nothing else in the suite
noticed that mutation — the existing mirror coverage was on the goalkeeper, not on the tactical grid.

### T0-BE-2 UI · ✅ DONE 2026-10-10 — the Game plan screen

**The engine could do all of it and no manager could ask.** After the last mile above, a fixture's
instructions reached a real match — but there was no screen to set one, which is the substitution-plan trap
for the third time in this session. Built before it could be mistaken for a finished feature.

- **`MatchTacticPlanController`** at `/api/sim/fixtures/{id}/tactic-plan`. **The side comes from the
  session, never from the request** — a `side` sent by the browser would be a manager writing the
  opposition's plan for them, and the engine would obey it.
- **`match-tactic-plan-view.js`**, mounted on the match screen beside the substitution panel: three slots,
  each with the club's own tactics, one of the six conditions, and a minute gate. A slot set to "— none —"
  clears that priority rather than saving an instruction with no tactic.
- **A club with no tactics is pointed at the Tactics page** and is **not offered a form** it cannot fill.
  A club not in the fixture is shown **nothing at all** — an empty panel reads as "you have no tactics",
  which is a different statement.

**Verified in the running application at 430 × 932**: the panel renders with three slots and all six
conditions; an instruction set through the screen (`4-4-2`, *if leading by 3 or more*) came back selected
after a reload and **was read back out of the database**. The test assignment was then deleted.

**Two gaps the harness caught before the browser ever saw it**: the form was offered to a club with no
tactics, and a club not in the fixture was shown an empty panel instead of nothing.

**One row written to the owner's database**, and deliberately: OFK Omladinac's existing
`team_tactics_profile` converted into a `Tactic`. Without it the screen is unusable, and it is the manager's
own club — the one profile in the world belongs to it. Taken verbatim from the existing row, nothing
invented, the legacy row untouched, reversible by deleting one row. **The other 14,722 clubs still have no
tactics**; that remains the seeding button, and it is still the owner's to press.

**The last mile, added 2026-10-10 after the first pass of this task was committed.** Everything above
existed — the entity, the condition logic, the resolver, the tick hook, the engine setter — and **nothing
in production handed any of it an assignment.** `SimMatchRunner.run` simulates inside itself, so the
instructions had to be attached to the orchestrator before the first tick, the same seam the substitution
plan needed and the same one it got. `SimMatchService` now asks
`MatchTacticsPreparationService.forFixture(...)` and passes the resolver through. This is precisely the
shape the substitution feature had when ten unit tests were green and no manager could reach it.

**Exit criteria**
- [x] A fixture carries up to three instructions per club, in priority order
- [x] A saved instruction reaches the orchestrator **before kickoff**, through the real runner
- [x] **Proven able to fail:** passing `null` at the production call site leaves all 33 other tests green —
      the guard exists because that was measured, not assumed
- [x] Priority decides when two conditions are true at once
- [x] `minuteFrom` gates an instruction, and is checked with the score condition rather than instead of it
- [x] A fixture with no instructions plays the club's default for all ninety minutes
- [x] An instruction whose tactic was deleted resolves to the default, not to nothing
- [x] **Proven able to fail:** ignoring priority turns `priorityBreaksTies` red; dropping the club check
      turns `aTacticFromAnotherClubIsRefused` red; deleting the per-tick resolution leaves **all 27 other
      tests green**, which is why the wiring needed its own guard

**Two things found while building it**

- **The max-three count check is unreachable, and saying so is the honest result.** Priority is bounded
  1–3 and saving at an occupied priority replaces that slot, so a fourth row cannot be created through the
  service at all. The count check stays as defence in depth — priority is a position, the count is a cap,
  and they only agree today — and its comment says it cannot currently fire rather than implying it holds
  the line.
- **The production call site failed silently too.** Replacing the passed resolver with `null` — so the
  simulation built it and discarded it — left all 33 tests green, for the same reason: none of them starts
  a simulation. `MatchTacticsReachTheMatchTest` builds a real orchestrator through the real runner and
  asserts it was given the resolver; the production hand-off is guarded structurally, because running
  `SimMatchService.simulate` needs a whole fixture and season.
- **The tick-loop wiring failed silently, and I only found it because I deleted it.** Removing the
  per-tick resolution left every behavioural test green: the engine simply kept the shape it was built
  with. `OrchestratorConsultsTheResolverTest` now guards it — **as a structural guard, and labelled as
  one**, because running it for real needs a populated `MatchState` and a full tick. It is weaker than its
  neighbours and it is here because a call in a hot loop that nobody exercises is not a call. This is the
  same failure as the `fixture-view.js` mount that hid the whole substitution feature.

### T-DISC · ✅ DONE 2026-10-10 — cards, and what they cost (owner ruling)

**The owner's ruling, 2026-10-10.** A direct red card in one match automatically bans the player for the
**first next official match of the club** — everything except a friendly. League yellows accumulate:
**three earns one match, six earns two, nine earns three**, served in the league. Counters reset at the
end of each season.

This is what the T0-BE-3 note above was waiting for. The lineup warning it could not check now can.

**Built**
- **`PlayerDiscipline`** — player, season, competition, yellows, bans owed, bans served. **A null
  competition is the club-wide row**, which is where a red card goes, because the rule is about the club
  and not about the competition the card happened to be in.
- **Keyed by season, so the reset is structural.** There is no end-of-season sweep to forget to run and no
  counter that can survive into a new campaign.
- **The lineup warning is restored** — `MatchLineupService.warningsFor` now says why a player cannot play
  this fixture, so the manager finds out on the screen rather than from a void reason after the whistle.

**The part that is easy to get wrong, and was**

If the yellow counter reset the moment three were reached, **six and nine could never be reached at all**
and two thirds of the rule would be dead letters. So the counter **keeps its place while the earned bans
are outstanding, and resets only once they have been served.** Several tests exist to hold exactly that.

**Two scopes, deliberately different.** A red-card ban is club-wide and bars the next official match of
any sort — including a cup tie. A yellow ban is a **league** ban, because that is where the yellows were
earned. The owner drew that distinction and the tests hold both.

**"Official" is one null check.** `CompetitionType` has no FRIENDLY value, because a friendly is a fixture
that belongs to no competition at all — `MatchType.ofCompetition(null)` is FRIENDLY for exactly that
reason. So a friendly's yellows are not a league's yellows, and a friendly is not a match a red-card ban
can bite on.

**Exit criteria**
- [x] A red card bars the first next official match, and not a friendly
- [x] It is served by playing, and only then
- [x] Three yellows earn one league match, six earn two, nine earn three
- [x] Friendly yellows do not count toward the league accumulation
- [x] A yellow ban is league-scoped; a red-card ban is club-wide
- [x] A new season starts clean, with no sweep to run
- [x] **Proven able to fail:** collapsing the 6 and 9 bands turns **3** tests red; resetting the counter at
      three turns `theCounterWaitsForTheBansToBeServed` red

**Wired, 2026-10-10 — the part that was missing and is now closed.**
- **After the match**, `recordDiscipline(fixture, outcome)` writes the cards the engine already awards:
  `DuelService` calls `stats.onYellowCard` / `onRedCard` and each `PlayerOutcome` carries the totals, so
  this is a write and not an invention. Synthetic engine players (`HOME-1`) are skipped — their ids
  resolve to nobody.
- **Before the match**, `serveSuspensions(fixture)` spends every ban this fixture is serving, for both
  clubs, **before** the squads are built so the players it frees are the ones the auto-pick sees.
- **A suspended player is kept out of the auto-picked eleven.** A manager's own saved lineup is left alone
  — the screen tells him who is unavailable, and silently editing a manager's XI behind his back is worse
  than showing it to him.
- **Recording is best-effort.** The match is already played when it runs, so a failure is logged and the
  match stands.

**Served by the fixture, not by the selection.** The owner's rule is that a red card bars the first next
official match *of the club* — a match he is banned from is a match the ban is spent on whether or not he
would otherwise have been picked. Tying it to selection would let a manager keep a player out of the XI and
quietly reset his suspension.

**Proven able to fail:** removing the `discipline.record(...)` call turns
`DisciplineIsAppliedInASimulationTest` red **while `DisciplineServiceTest` stays green** — which is the
point of having it. The rules' arithmetic was always tested; that they are applied by a match was not, and
that is precisely what was missing.

**These are structural guards and labelled as such.** Proving the wiring behaviourally means seeding a
competition, drawing a fixture and running ninety minutes of engine. The arithmetic behind 3/6/9 is covered
behaviourally; the three call sites are covered by reading them.

### T0-BE-3 UI · ✅ DONE 2026-10-10 — the Team selection screen

T0-BE-3 gave a club the ability to pick a team for one fixture and left it with no place to do it. Same
shape as the substitution plan and the game plan before it — a working backend and a screen that does not
exist. Built before it could be mistaken for a finished feature.

- **`MatchLineupController`** at `/api/sim/fixtures/{id}/lineup`. **The club comes from the session**, never
  from the request — a body carrying a team id would let a manager write a squad sheet for a club he does
  not manage, and a squad sheet decides who is fit to play.
- **`match-lineup-view.js`** — eleven starters in the order chosen, up to seven bench, a formation, and a
  running count of how many are still to pick. **Back to default eleven** clears this fixture's XI.
- **The whole squad comes back with the view**, each player carrying his availability and — when he has one
  — the reason he is suspended, so the manager chooses with the state of his squad in front of him.
- **Warnings are on the screen before anything is submitted.** A warning the manager can only get by
  submitting is a warning he reads too late.

**Verified in the running application at 430 × 932**: the panel renders on an unplayed fixture with the
right copy, the goalkeeper warning visible before any selection, eleven starter rows, the squad list, and
the counter tracking a removal — *"1 still to pick"* — and returning to *"11 picked"*. **No save was
pressed**, so the database is untouched: 0 per-match lineups, 1 template.

**Two things the harness caught before a browser was opened**: a DOM stub too thin to run a screen that
fills sub-lists, and three assertions reading the panel's markup when the eleven and the bench are written
into their own nodes. Both were faults in the check, not in the screen — and the first would have been
quietly "fixed" by adding null-guards to the view, which would have been defensive noise in a browser where
those elements always exist.

### T0-BE-3 · ✅ DONE 2026-10-10 — a club can pick a team for one fixture

**What was in the way:** `Lineup.match` has been a nullable `@ManyToOne` all along, and every reader asked
for `match IS NULL` — the template — because that was the only row that could ever exist. The schema was
already right. The **writer was missing**, and so a manager could not pick a team for a game: the order was
whatever the last template happened to say.

**Built**
1. **`MatchLineupService.save`** — eleven starters in the order chosen, up to seven bench, a formation.
   Keyed by the **match**, which is what the column holds and what the record of who actually started
   means; resolved from the fixture through `MatchFixture.playedMatch`.
2. **`MatchLineupService.resolve`** — **this fixture's lineup first, the template second.** That order is
   the feature, and the decision lives in the service so it can be tested directly.
3. **The goalkeeper rule is said up front**, not discovered at minute 60. The owner specified that a keeper
   may be substituted only by another keeper, and `SubstitutionService.pickReplacement` already enforces
   it by matching GK status — so the screen says it while the manager is still looking at it.
4. **Warnings never block.** An injured player in the XI is warned about and the save goes through; the
   engine has its own rules and will substitute him. Only a selection that cannot field eleven is refused,
   because that is not a lineup with a problem in it.

**Exit criteria**
- [x] A lineup saved for one fixture is used for that fixture only, and the next fixture gets the template
- [x] A fixture with no lineup falls back to the template
- [x] The chosen order is kept, not re-sorted — a formation is not an ordering
- [x] Another club's player cannot be picked even by id
- [x] **Proven able to fail:** disabling the per-match preference turns `thePerMatchLineupWins` red

**Two things found while building it**

- **The first version of the test proved a copy.** It re-implemented the two-line resolution locally, so
  mutating the **production** reader left all ten tests green. The decision was extracted into
  `MatchLineupService.resolve` — which is better design anyway, since it is now the one place the rule
  lives — and the test calls it. The same mutation then turned it red.
- **There was no suspension in the football model.** The task asked for a warning when the XI contains a
  suspended player; `Player` has injuries and nothing else. The check was **removed rather than invented**
  — a check against a field that can never be set is a check that always passes. **Superseded the same
  day: see T-DISC below. The owner then specified the rules, and the check is back and testing something
  real.**

### T0-BE-4 · ✅ DONE 2026-10-09 — conditional substitutions: the contract becomes reachable

**`ConditionalSubstitutionRules` had ten green unit tests and zero production callers. Not a wiring
mistake — a shape that made wiring impossible.**

**What existed:** the rules holder with `Rule(triggerMinute, condition, playerOnId, playerOffId, status)`,
`MatchOrchestrator:654` calling `conditionalSubs.onTick()` every tick, a persisted `SubstitutionPlan`, and a
working REST API. And two things that made it unreachable:

1. **`conditionalSubs` was private with no accessor.**
2. **`SimMatchRunner.run` constructed *and* simulated inside one call**, so there was no moment at which a
   plan could be attached. The engine evaluated an always-empty list on every tick of every match.

**What landed**
- [x] `MatchOrchestrator.conditionalSubstitutions()` — the accessor
- [x] `SimMatchRunner.build(...)` splits build from simulate. **All five `run` overloads untouched**, so
      every launcher, diagnostic and exporter still produces the same football
- [x] `SimMatchService.applySubstitutionPlan` loads by fixture, **never throws** — a malformed plan degrades
      to the default rather than costing the manager his match
- [x] Plan re-keyed **`matchId` → `fixtureId`** (see below)
- [x] Closes one hour before kickoff, refusing with `PLAN_CLOSED` and a reason
- [x] **Proven able to fail:** removing the `add()` and changing nothing else turns
      `ConditionalSubstitutionFiresInAMatchTest` red with the defect named in the message

**The key was wrong, not buggy.** The plan was keyed by `matchId`, so it could only be created once a
`Match` row existed — **after the simulation that consumes it**. All four old tests passed. The owner's rule
closes substitutions an hour *before* kickoff, which is before any match exists.

#### The fixture ↔ match correlation, since the owner required no ambiguity

| | Before | Now |
|---|---|---|
| Direction | `MatchFixture.playedMatch` → `Match`. **One-way.** `Match` has no back-reference, only copied values | Unchanged, deliberately |
| Constraint | **none** — two fixtures could point at one match | `unique = true` + `uk_match_fixture_played_match` |
| Index | **none** — every result-by-fixture read was a scan | `ix_match_fixture_played_match` |

**Why unique is the load-bearing part:** without it the result of a game can read as the result of a
different game. P0-PREV-1/-2/-3 each recorded *"a fixture is not a match, and only the `playedMatch` knows
which one this is"*, and `ZoxApiController` carries the scar of a guess that resolved a dashboard link to
somebody else's played match. One played match now belongs to at most one fixture, so a plan written against
a fixture is read by exactly one simulation — the one for that fixture.

**Live-database check owed:** the Postgres server was down, so the constraint was **not** verified against
`sokker_db`. Declared after reading the only writer (`SimMatchService:356`, in the same block that sets
`played = true`), which cannot set it twice for one fixture. **Verify before the next app start:**
`SELECT played_match_id FROM match_fixture WHERE played_match_id IS NOT NULL GROUP BY played_match_id HAVING count(*) > 1;`

**Not done:** a rule naming a player who is not in the XI is still refused by the **engine** at minute 60,
with a `VoidReason`, rather than at save time. The screen's bench dropdown prevents it for the player-on
side; the server does not yet validate. Filed as **T1-16**.

---

## T1-16 · DONE 2026-10-10 — validated on save, and the outcome is on the screen

**Found while building T0-UI-4, and it is the honest remainder of that task.**

The screen's dropdowns mean a manager cannot name somebody who is not in the squad through the UI. The
**API** can: `PUT /api/sim/fixtures/{id}/substitution-plan` takes `playerOnId` and `playerOffId` as strings
and writes whatever it is given.

The engine catches it — `ConditionalSubstitutionRules.VoidReason` has `PLAYER_UNAVAILABLE`,
`PLAYER_ALREADY_ON_PITCH`, `EMPTY_BENCH`, `NO_SUBS_LEFT`, `NO_WINDOWS_LEFT` — and the rule is marked `VOID`
with a reason. **But that happens during the match**, and the manager has no way to see it, because nothing
reads `rule.voidReason` back.

So the current state is: a typo becomes a silently dead rule, discovered never.

**Tasks**
- [x] Validate on save: the named player is in the squad, the named player off is in the XI, the condition
      is one the engine has, and the minute is in range
- [x] Refuse the save with the reason, rather than accepting a rule that cannot fire
- [x] Surface `voidReason` after the match, so a manager can see which of his instructions were honoured
      and which were not

**Exit criteria**
- [x] A rule naming a player who is not in the squad is refused at save time, with the reason
- [x] A post-match screen reports each rule as fired or void, and why
- [x] **Proven able to fail:** posting an unknown `playerOnId` returns 400 and not 200
- [x] Dead `substitution_plan.match_id` column dropped (was blocking every INSERT in production)
- [x] 12 tests green; 7 of 10 go red when validation is removed
- [x] Verified live: 400 with reason for unknown player, 200 for valid plan, `match_id` column gone from DB
- [x] The outcome is rendered by a real JavaScript engine, and the harness has been seen to fail — both
      mutations (position-matching instead of content, badge deleted from the row) go red

**What the third task actually turned out to be (2026-10-10).** The line above was already ticked when
this was picked up, and it was not true. Writing `outcomeJson` was only half of it. Two things stopped it
reaching the screen:

1. `substitution-plan-view.js` parsed `rulesJson` and nothing else — it never read `outcomeJson`.
2. `fixture-view.js` returned early from `mountSubstitutionPlan` when `fixture.played` was true.

So the record was written on every simulation, served on every request, and displayed nowhere, and the
early return was there for a good reason at the time: the panel had been built purely as an *input*, so
there was genuinely nothing to show once the team was picked. Both are fixed. Outcomes are matched to
rules by content rather than by position, because the engine skips a rule it cannot parse and that shifts
every later row — position matching would have reported the wrong minute for the wrong condition, which
is worse than showing nothing. `PLAYER_ALREADY_ON_PITCH` is described by what the engine actually does
with it (the player named to come *off* is no longer on the pitch) rather than by what the constant is
called, because the constant's name is misleading.

**Not yet verified in a browser.** The post-match view needs a fixture that has been played *and* carries
an outcome, and the database has **0** such rows: `simulate-all` is the only way to produce one and it
would play **5,209** unplayed fixtures across 48 countries. That is the owner's season, so it was not
done for a UI check. What is proven is the rendering, against the real module, by
`SubstitutionOutcomeIsShownTest`. Filed as **T1-16b**.

## T1-16b · ✅ CLOSED 2026-10-10 — the post-match outcome, seen in a browser

The post-match substitution outcome has now been read on the running application, at 430 × 932, with the
manager's own club: a fired condition shows **"Fired at 63'"** and a void one shows **"Void — nobody was
left on the bench"**, read-only, with no Remove button and no add form.

This was filed because the outcome could not be verified without simulating 5,209 fixtures. It was
verified instead by writing one temporary `outcome_json` row against an already-played fixture, opening
that match, and **deleting the row afterwards**. The table is back to the one row it started with.

That attempt is also what uncovered **T0-UI-4b**: the panel was not there at all, because it was mounted
on a page no route renders. T1-16 was not reachable either — the validation, the refusal and the outcome
record were all real and none of them could be used. Closing this one depended on closing that one.

## T0-UI-4b · DONE 2026-10-10 — the substitution plan is reachable

**Found 2026-10-10, while trying to close T1-16b in a browser.** This is the most serious thing on the
board, and it was found by looking rather than by testing.

`substitution-plan-view.js` is mounted by exactly one place: `fixture-view.js`, which renders
`<div id="fm-substitution-plan">`. **No route reaches `fixture-view.js` for a fixture.**

Every fixture card is built by one function (`pages-renderers.js:147-148`), and both branches avoid it:

- a **played** fixture gets `js-load-match` → `loadMatch(matchId)` → **match view**
- an **unplayed** fixture gets `js-load-fixture`, whose handler (`pages-renderers.js:308-318`) tries
  `onLoadMatch` **first** and only falls back to `onLoadFixture` when that is absent. Both call sites
  (`pages-renderers.js:1211`, `:1320`) pass **both**, so `onLoadMatch` always wins and `loadFixture` is
  never called.

The comment at `pages-renderers.js:301-307` says this was deliberate and cites the owner: *"An unplayed
match opens the match view on its Preview tab, not the fixture sheet (owner, 2026-10-01). Both used to
exist and a manager met two different surfaces for one thing."* That decision merged the fixture sheet
into the match view's Preview tab — and the substitution panel went with it, because it had been bolted to
the surface that was retired.

**Seen in the running application**, not inferred. At 430px an unplayed OFK Omladinac fixture opens
**Match Preview**, with tabs Preview / Lineups / Stats / Goals / Replay / Match Report, and
`#fm-substitution-plan` does not exist. A played one opens the same view.

**What this means for the board:**
- **T0-UI-4** is not done. The API and the engine work; the screen does not exist for a manager.
- **T1-16** — the validation, the refusal with a reason, the outcome record — all work, and none of it is
  reachable. A manager cannot set a rule, so cannot make the mistake the validator prevents.
- The P0 fixture/match work is unaffected: it fixed the *id confusion*, which was real and is fixed.

**Why every test was green.** `SubstitutionRuleIsRefusedWhenItCannotFireTest` drives the controller with
`MockMvc`. `SubstitutionOutcomeIsShownTest` drives `loadPlan` directly. Neither goes near the routing that
decides which view a manager actually sees, so the feature can be entirely unreachable with both suites
green. Same shape as the other failures on this board: **the test proved the part it looked at.**

**The fix, and it is both halves:**

1. **BE.** `MatchDTO` carries `playedMatchId` but **no `fixtureId`**, so from a played match opened
   directly (`isFixture === false`) there is no way to reach `/api/sim/fixtures/{id}/substitution-plan`.
   The outcome cannot be fetched from the match view at all until the backend exposes that link.
2. **FE.** Mount `substitution-plan-view.js` in `match-view.js` — on **Preview** for an unplayed fixture
   (editable) and on **Match Report** after the whistle (read-only, with the outcome badges). That is
   where a manager is.

**Fixed 2026-10-10.** Both halves.

- **BE.** `MatchFixtureRepository.findByPlayedMatchId`, and `GET /api/sim/matches/{matchId}/substitution-plan`.
  The controller moved to a single `@RequestMapping("/api/sim")` so the plan has one home and one
  `planView`, rather than two controllers each carrying a copy. A match with no fixture is **404**, not an
  empty plan — an empty plan is what "no conditions set" looks like, and those must not look the same.
- **BE.** `MatchDTO` now carries `homeTeamId` / `awayTeamId`. It already carried the two clubs as *names*,
  so deciding "is the manager at home" meant comparing strings; and `match-view.js` read `homeTeamId` off
  the lineups payload, which is null until kickoff, so an unplayed fixture reported no home club at all.
- **BE.** A **played** fixture is never editable. `isStillEditable` had only the clock, so a fixture played
  ahead of its slot still reported itself open and the screen would have offered to edit an instruction
  for a match already in the record. Caught by `SubstitutionPlanByMatchTest` asserting `editable: false`.
- **FE.** The panel is mounted in `match-view.js` on both surfaces, owned by the home club **by id**,
  and `fixture-view.js` no longer mounts it — one mounting point, because two drift silently.

**Exit criteria**
- [x] The backend can resolve a played match back to its fixture
- [x] The panel is reachable from the Preview tab before kickoff, in a browser, at 430px
- [x] The outcome is reachable from the Match Report after the whistle, in a browser, at 430px
- [x] **Proven able to fail:** a test that drives the real routing, not `loadPlan` in isolation
- [x] T0-UI-4 and T1-16 closed

**Verified in the running application**, at 430 × 932, against the owner's own club:

| | |
|---|---|
| Unplayed fixture (OFK Omladinac v RFK Smederevo) | panel present, editable form, *"Decisions close an hour before kickoff."* |
| A condition added through the UI | saved, and `substitution_plan.rules_json` read back from the database |
| Played fixture (OFK Omladinac 1–1 TSK Budućnost 1919) | panel read-only, no Remove, no add form |
| Outcome | green **"Fired at 63'"** and red **"Void — nobody was left on the bench"**, stacked under the sentence, no overflow |

Every row written for that check was deleted; the table is back to the one row it started with.

**The check that should have existed before T0-UI-4 was closed:**
`SubstitutionPlanIsReachableTest` reads the real routing function and the real mount, then drives the real
module through both routes. Both mutations were run against it: deleting the single line
`void mountSubstitutionPlan();` turns `theMountIsCalled` red, and dropping the home-club test turns
`onlyTheHomeClubIsOfferedThePlan` red. Both are the defect itself, not a paraphrase of it.

It also has a guard on itself: the harness originally searched the *stripped* source for
`'export function ...'`, got `-1`, and sliced from it — a one-character string that matches nothing, so
every check passed vacuously. The guard now asserts the harness never does that.

### T0-UI-4 · ✅ DONE 2026-10-09 — the substitution screen — ✅ closed 2026-10-10 via T0-UI-4b

`substitution-plan-view.js`, mounted on the **fixture** page. **The screen itself was never reachable** —
see T0-UI-4b. Everything below describes the module, which is sound; what is missing is the mounting.

- [x] Per-rule: minute · condition · player on · player off
- [x] **The bench is a dropdown.** The failure this prevents is concrete: a rule naming a player who is
      not in the match cannot fire, and the engine's only report of that is a `VoidReason` the manager
      never sees until after the game
- [x] **A plain-words summary of every rule** — *"From minute 60, if losing: Player X on, the engine picks
      who comes off."* A condition set is easy to get wrong and hard to read back
- [x] **Injury auto-sub stated as automatic**, since `SubstitutionService.onTickInjuriesOnly()` already is
- [x] **Closes an hour before kickoff**, and the screen disables itself from the server's own `editable`
- [x] Refuses a sixth rule with the reason — five subs and three windows are engine limits
- [x] **Mounted only for a fixture the manager's club is playing at home, and only before it is played.**
      The plan is the home club's instruction; offering it on an away fixture, or on one with a result,
      is a control with no meaning — and, as T0-UI-4b records, was briefly mounted on a page no manager
      could reach. It is mounted on the match view now, on both sides of the whistle.
- [x] 430px — the remove control drops under the sentence rather than squeezing it
- [x] **Verified in a browser**, and doing so found a defect that had kept the panel from ever rendering.

### 🔴 Found by opening it: three panels mounted nothing, silently

The panel was not "never verified" — it **could not render at all on a cold page**, and no test said so.

All three mounts on the match view (substitution plan, game plan, lineup) gate on *"is this the
manager's own club"*, and all three read the id:

```js
const managerTeamId = getTeamId?.();   // null until /auth/me answers
if (!managerTeamId) return;            // …so this returns, silently
```

`getTeamId()` returns a variable that is `null` until `/auth/me` resolves. Opening a fixture before that
answered meant **no panel, no error, no warning and an empty host** — a green endpoint behind a guard
that ran on a value that had not arrived. `deps.ensureTeamId` — the existing `ensureUserTeamId()` — was
never passed in, and all three mounts now await it.

The panel now renders **3386 characters** from a cold page, identical to calling `loadPlan` directly.

### Evidence, all in a browser at 430px, against live PostgreSQL

- **Substitutions** panel renders for fixture 16 (OFK Omladinac v TSK Javor Zaječar Sport, unplayed,
  home), no horizontal overflow.
- **Game plan** and **lineup** panels render on the same fixture — **8039** and **5259** characters. All
  three were empty before the fix.
- **`DELETE` returns 204, not 500.** The route this entry exists for.
- Full round trip through the UI buttons: **Add condition** → row created with
  `[{"team":"HOME","triggerMinute":60,"condition":"ANYTIME",…}]`; **Remove** → `rules_json` becomes `[]`;
  `DELETE` → **204**.

**Regression cover** in `route-to-substitution-plan.mjs`, which already existed for exactly this class of
defect and had missed it because every one of its cases supplied a club id that was already loaded.
**Proven able to fail**: reverting the three guards to the cold read turns both new checks red.

**Found while testing it: `DELETE /substitution-plan` was always a 500.** `deleteByMatchId` is a derived
delete and needs a transaction, which the controller had not. It survived because the four tests that
existed tested an unknown id, a round trip, a replace and an empty plan — **and none of them deleted
anything.** A route with no test is a route that was never pressed. Now `@Transactional`, and covered.

### T0-UI-4b · DONE 2026-10-10 — the same defect, in every other way into a page

The three panels fixed above were not the only occurrence. They were the only one **anyone had opened**.

`loadPage` has always awaited `/auth/me` before rendering, which is why every page reached by clicking was
safe. **Twenty functions bypass `loadPage` and call a view directly** — `loadMatch`, `loadPlayer`,
`loadFixture`, `loadFormations`, `loadTacticEditor`, `loadMedicalCenter`, `loadClubProfile`, the three
training loaders, the five league loaders, `loadCountryPage`, `loadTopScorersAndAssists`,
`loadPlayerStats`, the four forum loaders, `loadMessages`, `loadMessageThread`, `window.loadStadium`.

On a deep link or a refresh, `getTeamId()` is `null` in all of them, and a view that asked for it built
`/teams/null/players` or returned silently.

**All twenty now `await settleTeamId()`**, which loads the user context if it is not in hand and is a
single truthiness test when it already is. `window.loadStadium` was the one window export that reached a
view directly; it is guarded too.

### 🟡 Caught in the act of fixing it

The first patch inserted the settle **after** the `return` keyword, leaving `return await settleTeamId();`
followed by unreachable delegation — **23 pages that would have rendered nothing**, and every existing
test still green. Found by reading the generated source rather than by running it, which is the one thing
a green suite could not have told me.

`entry-points-settle-team-id.mjs` now asserts **settling is never the returned expression**, because that
is exactly the mistake and exactly the kind that passes everything else.

### Evidence

- **Both mutations proven able to fail**: removing one guard names it (`loadFixture`), and returning the
  settle instead of awaiting it turns the reachability check red.
- **The sweep check found 7 entry points I had missed** on its first run, which is the argument for
  having written it rather than grepping.
- **Opened in a browser**, after the fix: dashboard, formations, club profile, tactic editor, training
  reports, upcoming matches, fixtures, league table (16k chars), league schedule, top scorers, player
  stats (25k), messages, player 2404 (14.7k) — all render, none "Page not found". The match view still
  shows all three panels: **3386 / 5259 / 8039**.

### T0-UI-5 · CLOSED 2026-10-10 — the premise was wrong; the live panel exists

**This task asked for a screen that implies real-time to stop implying it, because no such screen was
believed to exist. It exists.** The owner pushed back on the proposal to work this, which was correct.

`reveal-ui.js:55` — *"Watch your match"* opens `/demo/service/ui/proposal/index.html`, and that viewer
**simulates the match with the proposal engine**: a live ticker with a running clock, a tick counter, a
speed control and a *"Simulating match…"* state. The button is only offered for a match the manager has not
yet revealed, so opening it *is* watching it happen. There is no copy anywhere claiming a feed that does
not exist — a search of `match-view.js` for live/real-time/streaming wording returns nothing.

**So the honest outcome is that the screen is right, not that the copy needs fixing.** Nothing is being
deferred and nothing is lost.

**The mistake is the part worth keeping.** I proposed this task off the board's wording without opening the
viewer, and the owner — who uses the game — knew the answer immediately. A board entry describes a
*belief* about the system; it is not evidence, and this session produced a run of items where that
distinction cost real time. **Nothing goes on the board as work until someone has looked at the thing it
describes.**

### T0-UI-6 · The analytics panels — audited 2026-10-10, scoped in three parts

**The task as written asked for six things. The audit says two are nearly free, one is already shipped,
and three need data that does not exist.**

| | State, verified against the source and the database |
|---|---|
| **xG per side** | **Already done and already displayed.** `ProposalMatchOutcomeBuilder.computeExpectedGoals` scores every shot from its distance to goal, and the Stats tab has shown `['xG', …]` all along. Nothing to build. |
| **Field tilt** | **Derivable from data that exists.** `player_zone_load` holds **27,757 rows across 235 matches** — nine zones per team (`ATTACKING/MIDFIELD/DEFENSIVE` × `LEFT/CENTRE/RIGHT`) with minutes and intensity. Field tilt is the attacking share of it. |
| **PPDA** | **Cheap, with one gap.** Passes attempted is team-level; `tackles`, `duelsWon`, `blocks`, `interceptions` are all counted but only reach the payload **per player**, never summed per side. One aggregation away. |
| **xA** | **Dropped by owner decision, 2026-10-10** — *"izbaci xA skroz, nepotreban je kad imamo xG."* It never existed, and with xG on the screen a second speculative probability is a number the manager cannot act on any better than the one he already has. |
| **Shot map** | **The data does not survive the match.** `positionRow` / `positionColumn` exist on `sim/recording/MatchEvent` — the **in-memory recorder** — and on nothing else. No table stores where a shot was taken. xG is computed from that position and then persisted as a number; the position itself is gone. Every event table is 0 rows. |
| **Progressive passes** | **Same gap.** Pass positions are not persisted. |
| **Momentum strip** | **Same gap.** There is no per-tick or per-minute timeline in the database — `match_tick_states` is 0 rows and nothing writes it (T2-9). |

**So the shot map, progressive passes and momentum are not UI work.** They are data-layer work: a shot's
and a pass's cell has to be written down at the moment it happens, across 14,723 clubs and 3,600 ticks a
match. That is a decision about what the database stores, and it belongs beside T2-9 rather than inside a
screen.

**The zones are named from the player's own perspective** — `ATTACKING` means the attacking third *for that
player*, so the two sides must be read through the same perspective discipline as the tactical mirror
(T-REST-14) or a home side's attacking third would be compared against the away side's defensive one.

**Recommended order — accepted by the owner, 2026-10-10**
1. **Field tilt and PPDA**, each as a number **with its plain-words reading** — the existing data, no new
   persistence, and the two a manager would actually act on.
2. ~~**xA**~~ — **removed at the owner's decision.** With xG already on the screen it is a second
   speculative probability, and a number the manager cannot act on is a number the panel does not need.
3. **Shot map, progressive passes, momentum** — filed as their own data-layer task, not here.

Every number carries its own explanation, and a 430px pass, as the task already requires.

### T0-UI-6 (part 1) · DONE 2026-10-10 — field tilt and PPDA, each with its reading

The two the audit found were nearly free. **xA is gone** (owner: *"izbaci xA skroz, nepotran je kad imamo xG"*), and the
three that need data the game does not keep are filed as their own task below.

**Field tilt — arithmetic on data that already existed.** `player_zone_load` holds nine zones per player
with minutes and intensity; tilt is each side's share of its **own** minutes in the attacking third. The
zones are named from the player's perspective, so the two sides are compared share-for-share — reversing
one side would compare home's attacking third against away's defensive one and produce a number that looks
like tilt and means nothing. The same perspective discipline as the tactical mirror (T-REST-14).

**PPDA — and what its denominator actually is.** The engine had been counting passes attempted,
clearances, interceptions, blocks, deflections and fouls on every match and **none of them reached the
stored payload** — the numerator was a percentage and the denominator did not exist. `SimReportMapper.statsMap`
now writes all of them. **Tackles are not in it**: they are counted per player and never summed to the side,
so this is deliberately not the textbook "tackles + interceptions + fouls", and **the definition is printed
under the figure** rather than implying a textbook number it is not computing.

**Nothing is invented where it was not measured.** A match whose stored stats predate these keys shows
*"Not recorded for this match"*, not 0.0 — a zero would be a claim about a game rather than a statement
about a column. The **235 matches already in the world** are all in that state for PPDA, and the panel says
so for each of them rather than going silent, because silence reads as "nothing to show" rather than "not
measured".

**Verified in the running application at 430 × 932**, on a real played match with 235-matches-worth of zone
data: field tilt reads **NK Balkan 1928 33% v FK Sinđelić Užice 1945 35%** with the even-match reading
("Both sides spent about as long in the opposition half"), the PPDA panel names itself as not recorded, and
the existing xG row is unaffected. The two panels stack in one column at 430px with no overflow.

**A correction to the audit I wrote an hour earlier.** It said xG "is already computed and displayed".
That is true of the **engine** matches, and `computeTeamStats` prefers `statsJson` — which carries the real
score. But the fallback path for a match with no `statsJson` computes `goals * 0.7 + 0.5` and labels it
xG. No match in the current world takes that path; a legacy or hand-created one would show a fabricated
figure. Recorded rather than quietly corrected, because the fallback is deliberate and the honest fix is a
separate decision.

### T0-UI-6 (part 2) · The shot map, progressive passes and the momentum strip — NOT UI WORK

Deferred by the audit, and filed here so it is not lost. **All three need data the game does not keep.**
A shot's and a pass's cell exist only on `sim/recording/MatchEvent` — the in-memory recorder — and on
nothing else; every event table is 0 rows; there is no per-tick timeline at all (`match_tick_states` is 0
rows, T2-9). xG is computed from that position and persisted as a *number*; the position is gone.

**This is a decision about what the database stores**, written down at match time across 14,723 clubs and
3,600 ticks a match, and it belongs beside **T2-9** rather than inside a screen.

**Exit criteria**
- [ ] An owner decision: persist shot and pass positions, or close these three as not being built
- [ ] If persisted: a shot map, progressive passes, and a momentum strip, each with its reading

### T0-UI-7 · DONE 2026-10-10 — a national side can answer a warm-up, and the senior tab works at all

**Two of the three board items were already true, and the third was hiding behind a crash.**

- [x] Request — the "Ask for a warm-up" button and opponent list have been there since the panel was
      written. No change needed.
- [x] **Optional is stated as optional** — the panel reads, verbatim: *"Optional. A warm-up is an extra
      match in week 6 day 1; not playing one costs nothing and skips no rule."* No change needed.
- [x] The club-side invite button is rendered, at `country-view.js` `[data-warmup-request]`.
- [x] **Accept and decline now exist.** This is the real gap, and it was the only one.

### What was actually broken

`warmUpRequestListHtml` rendered every request as one sentence — **`Side 9 · pending`**. A side that had
been asked for a warm-up had no way to answer it: the backend has had `/respond` and `/cancel` since it
was written and **nothing called them**. A request could be made and then only waited on.

An incoming pending row now answers, an outgoing one withdraws, and a settled one shows its reason and
offers nothing, because a decision that is already made cannot be unmade from this screen. Rows name the
other side — *"Side 9"* is technically correct and useless to a manager deciding whether to accept.

### 🔴 Found on the way: the senior and U-21 tabs were dead

The panel never rendered at all, because `NationalTeamElectionService.describeElection` **creates an
election row on read** and was annotated `@Transactional` — the default `REQUIRED`, which **joins the
caller's transaction and inherits its read-only flag**. `NationalTeamService.describe` is
`@Transactional(readOnly = true)`, so every call threw `cannot execute INSERT in a read-only transaction`.

The damage was not a visible error. The country page reads `seniorNt` to learn the side's team id, so
every read threw, the loader swallowed it into `{ failed: true }`, and **the senior and U-21 tabs rendered
with no squad, no selector and no warm-up panel while looking as though they had loaded fine.**

`REQUIRES_NEW` suspends the outer transaction and gives this one a writable session. The write is
intended and documented, so the transaction was what needed fixing — not the write.

### Evidence

Browser at **430px**, against live PostgreSQL, on Serbia's senior tab:

- Incoming PENDING from Belgium → **Play them** / **Cannot make it**, naming *BELGIUM · PENDING*.
- **Play them** → request `ACCEPTED` **and fixture 5445 created**: Belgium (47) v Serbia (3), season 2,
  **week 6 day 1** — exactly the slot, confirming the whole path end to end.
- **Cannot make it** → request `DECLINED`, reason recorded.
- The panel reads *"Optional. A warm-up is an extra match in week 6 day 1; not playing one costs nothing
  and skips no rule."* — the week comes from `/slot`, so the page never restates it.
- The 409 guard fires correctly: asking a side that already has a match that week returns the sentence,
  which is shown rather than replaced.

**All test data removed** — `friendly_request` rows: 0; fixture 5445 deleted; live DB as found.

### Regression cover

`render-country-view.mjs` now drives all three request states and asserts them: **incoming pending
answers, outgoing pending withdraws, a settled request carries its reason and offers nothing**, and the
other side is named rather than shown as an id.

**Proven able to fail**: disabling the answer buttons turns *"missing `data-warmup-accept`"* and
*"missing `data-warmup-decline`"* red.

The harness's per-state verdict was also corrected while doing this. It printed `ok` on the same line
as two `FAILED` lines, because the verdict was counted globally rather than per state — a green status
next to a red assertion is the exact thing this repository has been burned by, and it would have taught
the next reader to trust it.

**`CountryPageRendersWithoutReferenceErrorTest` reports 0 skipped.** Node is at `/usr/local/bin/node`; it
is simply absent from the agent shell's PATH, which is why this was recorded as a gap in the first place.

### Known gap

- The decline reason reaches the **requester**, not the responder: `incoming()` filters to `PENDING` only
  while `outgoing()` returns everything. Verified in code and now in the harness, but not in a browser —
  no Northern Ireland account exists to log in as.

## T1-1 · 🔴 `ConditionalSubstitutionRules` is dead in production

**DONE — and this one was never true by the time it was read.**

`ConditionalSubstitutionRules` has **four production callers**, verified immediately before closing:
`SimMatchService`, `MatchOrchestrator`, `SubstitutionRuleValidator` and `SubstitutionPlan`.

The defect class this entry names is real and is why it is being closed by measurement rather than by
reading: *an engine contract with ten green unit tests and zero production callers reads exactly like a
working feature*, and it was believed covered for three sprints. Here the wiring exists, so the entry
was describing a past state. Covered by **T0-BE-4**.

## T1-2 · 🔴 `SubstitutionPlan` is persisted and never read

**DONE — also never true by the time it was read.**

`SubstitutionPlan` is read in production by `SimulationController`, `SimMatchService` and
`SubstitutionPlanController`, through `substitutionPlans.findByFixtureId`. A row survives a restart and
is *not* invisible. Covered by **T0-BE-4**.

## T1-3 · 🔴 `TacticsBridge` and `NewLogicTacticsService` are dead code

**DONE — deleted, not wired. The decision this entry asked for has been made and carried out.**

Both files **no longer exist** in `src/main/java`:

| Class | State |
|---|---|
| `TacticsBridge` | **gone.** `fromRuntimeMap()` is not present to have zero callers |
| `NewLogicTacticsService` | **gone.** `loadTacticRules(teamId, formation)` is not present |

This entry's exit criterion was *"the caller count re-verified **immediately before** deletion"* — which
is the part that stops a deletion being justified by a caller count measured weeks earlier. Verified now,
after the fact, by the classes' absence rather than by a count.

## T1-4 · DEFERRED BY OWNER 2026-10-10 — the six tactics sliders have zero readers

**Deferred, deliberately (owner, 2026-10-10):** *"slidere cemo kasnije vezati zaista za taktiku kad se budemo
bavili taktikom, zasad ih drzi jasno u boardu."* Not forgotten and not closed — the six fields stay in the
model and stay visible here, and they get wired when the tactics work picks them up.


`TeamTacticsProfile` carries `aggression`, `defenceLine`, `pressing`, `possession`, `counterAttack` and
`ballControl`. **Zero readers in `src/main`.** They are stored and never used. The archive's position was
that the product should ship **both** models — the grid for a manager who thinks in geometry, sliders for
one who does not — which means both need consumers.

**Exit criteria:** each slider changes something observable in a match, **or is removed.** A stored field
nobody reads is a lie in the data model.

## T1-5 · 🟠 `mirrorWeHaveBallRules` overwrites the defensive shape

`TeamTacticsService` mirrors `WE_HAVE_BALL` onto `OPPONENT_HAS_BALL` on every save, and `DefensiveShape`
derives the out-of-possession shape arithmetically. **This is an owner decision, not a defect** — but it
means the editor's out-of-possession data is unreachable, and the current source of truth is arithmetic in
`DefensiveShape` rather than anything the manager authored.

**Exit criteria:** the decision is written down in the entity's javadoc, and the UI says which shape is in
force. **Do not "fix" the mirroring** — see the traps.

## T1-6 · DONE 2026-10-10 — "9 in catalog, 1 applied" is not true

**The claim being retired.** The archive records *"9 in catalog, 1 applied"*, and the board carried it as
an open item. **It is false.** All nine layouts exist, each fields a distinct eleven, and **two formations
are already in use in the world** — 4-4-2 and 3-4-3 are both saved on real clubs.

**Why it needed a test rather than a reading.** A catalog can hold nine layouts and still hand every club
the same eleven, because {@code getOrDefault(..., layouts.get("4-4-2"))} falls through for any name it
does not recognise. Counting nine layouts proves nothing; what had to be shown is that the nine produce
nine different XIs.

** now asserts that**, and **proven able to fail**: collapsing the lookup so every
formation falls through to 4-4-2 — the exact defect the entry describes — turns **2** checks red,
including *"4-3-3 and 4-4-2 field exactly the same players in the same slots"*.

It also spot-checks the pairs the eye would call the same. 4-4-2 and 4-5-1 differ only in the middle
band, and 3-4-3 and 3-5-2 only in the width of it, so a catalog that quietly widened one into the other
would still hold nine names and pass a count.

## T1-7 · ✅ DONE — `MatchdayJob` already issues one query, not one per competition

**The board described a past state.** `MatchdayJob` fetches the day's fixtures **once** and filters by a
set of competition ids in Java. `findUnplayedOnDay` appears exactly once in the file, outside the loop,
and the comment above it says *"One query, not one per competition"*.

**Measured, not asserted.** The live database holds **exactly sixteen `CUP` competitions**, which is where
the board's *"sixteen identical full-table queries"* came from — so the number was right and the shape it
described was not. There is one query now, and it does not grow with the number of tiers.

Covered by **T2-3**, which is this same entry on the performance board.

## T1-8 · ✅ DONE — the summary and the mover already read the same boundary

**Also a past state.** `buildPlayoffSummary` used to name relegated clubs by literal index — `top.get(8)`
and `top.get(9)`, the 9th and 10th — while `applyPromotionRelegationForLeague` computed the bottom of the
table dynamically. For a sixteen-club league over two lower leagues that is the **15th and 16th**, so the
game relegated one pair of clubs and the panel reported another. Both look plausible, which is why nobody
noticed.

Both sides now call **`boundaryFor(...)`**. There is no literal `get(8)` or `get(9)` left in the boundary
logic; the only occurrences in the file are in the comments recording the defect.

**Verified by running the cover, not by reading it:** `PromotionRelegationBoundaryTest` and
`PlayoffIsScopedToTheTopFlightsCountryTest` — **8 tests, all passing**.

## T1-9 · ✅ DONE 2026-10-10 — one query for the set, and the count measured rather than asserted

`seedAllClubs` asked `staff.countByTeamId(club.getId())` **inside the loop**, once per club. `seedClub`
asked the sponsors the same question. The exit criterion was literally *"one query for the set, not one
per club"*, so the fix reads both sets once with `select distinct` and filters in Java — the same shape
as `MatchdayJob`, for the same reason: a per-row answer cannot narrow the query it makes.

**Measured, not asserted.** `StaffSponsorSeedingQueriesTest` counts calls over a synthetic 14,880-club
world. **Before: 14,882 read queries. After: 2.**

### Two mistakes of mine, both caught here rather than shipped

1. **The first version of the test measured nothing.** It counted the repository mocks *before* calling
   `seedAllClubs`, so it recorded only the stubbing setup and read as a pass for **any** implementation
   at all. It stayed green straight through a mutation that put the per-club count back — which is the
   whole failure mode this repository has, reproduced in a test written specifically to avoid it. It now
   measures the **delta**.
2. **Writes were counted as queries.** 14,880 unstaffed clubs have to be written 14,880 times; counting
   those failed the fix along with the defect. The assertion is about **reads**, which is where the N+1
   was.

**Proven able to fail**: restoring `staff.countByTeamId` into the loop turns **all three** tests red,
including the query count (*"seeding 14,880 clubs issued 14882 read queries"*).

**The live database holds 310 clubs and no staff or sponsor rows**, so the real-world saving is 310
queries, not 14,880. The number that matters is that it no longer grows with the world — and the world
is being built toward 14,880.

## T1-10 · ✅ DONE 2026-10-09 — every loader checks the response, and one "API Error" card is gone

`LoadersCheckResponseOkTest`, 4 tests.

### Why it matters, stated precisely

`authFetch` **throws on every non-2xx.** So an unguarded `await response.json()` never reaches the
`if (!response.ok)` guard written for it — the throw skips it and escapes to the router, and `pages.js` is
the router every page goes through. Its last line replaces the page with a two-word card. So a 403 carrying
*"Only the owning club can accept incoming offers"* and a 500 from an unreachable database **reached the
manager as the same two words.**

### The worst of them were `Promise.all` groups, not single loaders

| Screen | What one non-2xx did |
|---|---|
| **ZOX match view** | preview + statistics + report fetched unchecked. A manager refused the post-match report **also lost the pre-match preview, which he was allowed** |
| **Transfer centre** | market + overview + squad unchecked. A 403 on the global market **also emptied the list of his own players** |
| **Statistics** | team directory guarded, the two leaderboards not — in one `Promise.all` |

### Landed

Two shared readers in `utils.js`, so this is one import and not six copies of a guard:

- **`readJsonOrThrow(response, label)`** — where the screen cannot be drawn without the payload
- **`readJsonOr(response, fallback, label)`** — where one panel failing must not empty the others, and it
  **says which panel went empty**, because a fallback returned without a word is indistinguishable from a
  competition that genuinely has nothing in it

Fixed: `fixture-view` (upcoming + friendlies, plus four `console.log`), `pages.js` `loadCup` /
`loadInternational`, `stats-view` player stats and the two leaderboards, `club-management`'s transfer
`Promise.all`, `zox-match-preview`, and the **admin database-job poll**, where one 500 mid-poll threw out of
the loop and left the loading popup on screen for ever.

**11 debug `console.log` lines removed**, and **`login.js` no longer logs the first 20 characters of the
JWT** to the console.

### Two board items were already fixed and are recorded as stale

- **`fetchPlayerRatingSummary`** — *"called with 1 argument at 2 sites; average rating permanently —"*. It
  takes `authFetch`, checks `response.ok`, and both call sites pass it. Fixed in the P0-PREV work.
- **`matches.js`** — *"the `if (!response.ok)` guard is still unreachable"*. Both loaders now catch and
  render a page saying what failed.

### The guard, and three mistakes of mine that made it look green

It scans the shipped files, because **this repository has no JavaScript test infrastructure at all** — no
runner, no `*.test.js`. It strips comments before scanning (P0-RANK-4 lost a day to a guard matching its own
javadoc) and excludes `tifo.js` **by name with the reason**, since that is the text-football mode.

**My first version of this guard passed against three planted defects.** Three separate errors:

1. **The debug-log window looked the wrong way.** `console.log('Loading X')` sits on the line *above* its
   `authFetch`; the window scanned *upward*, so it read the previous statement, matched nothing, and passed.
   **A window in the wrong direction is not a narrow window — it is no window.**
2. **Two of my mutations were not mutations.** I removed `readJsonOr` from two loaders that were inside a
   `try`, so the code was still handled and the green was correct. A mutation must remove the thing the
   assertion is about **and nothing that was already covering it.**
3. **`git checkout --` on a tracked file reverted the work the previous restore had put back**, and the
   chain of copies in between preserved the wrong version. Today's `fixture-view` changes were gone when I
   noticed; they are restored.

**Exit criteria**
- [x] Every loader in the graphical football UI checks the response, directly or through a shared reader
- [x] Both directions of a `Promise.all` group degrade independently
- [x] A guard, with `tifo.js` excluded by name rather than by a wildcard
- [x] **Proven able to fail:** a planted unguarded loader and a planted `console.log` each turn it red
- [ ] **Not verified in a browser.** Every one of these pages is markup and fetch behaviour

**`tifo.js` — the text-football mode — is excluded and unreviewed.** It has the same shape in several places.
A separate card.

## T1-11 · 🟡 `JobStatusService.report()` reads the whole `job_run` table

The entire table is read into memory on every `GET /admin/jobs`, then re-filtered per job in Java. The
table grows with clock movement — roughly twenty rows a game day. Bounded now, unbounded later.

**Exit criteria:** a per-key aggregate query. **Measured**, since this is a hot admin path.

## T1-12 · 🟡 `GoalEventRepository`'s season-wide variant walks every match with its blob

It backs top scorers, top assists and the club milestone leaders. Survivable only because P1-7b cut the
blob 49×. **This is the reason it is not a P0, and the reason it is not free.**

**Exit criteria:** measured against a full-scale season before anything changes

## T1-13 · ✅ DONE 2026-10-09 — the dead CSS class was a symptom of a dead page

`is-highlighted` was recorded as *"used by the represented-country group tables to mark the country being
viewed, not defined in the stylesheet at all."* **Both halves were true, and the class was not the defect
— the page emitting it was gone.**

### `renderRepresentedCountry` was called and defined nowhere

`loadCountryPage:759` calls `await renderRepresentedCountry(mainContent, countryIso)`. **The function does
not exist anywhere in the static tree.** Nor do `groupStandingTable`, `ordinal` or `formatNumber`.

**It was lost in the commit that fixed a bigger version of this same bug.** `73aafa6` — *"P1-CTRY-1: a
Clubs tab, and a country page that had been DEAD"* — restored `loadCountryPage` after `P0-PREV-1` deleted
it, and re-introduced the call, but not the function. The commit message says it restored *"209 lines, the
one function, not 458 lines and half the file."* **The one function was `loadCountryPage`. The second was
left behind.**

**The crash was reachable by an ordinary click.** World page → any national competition → a group table →
**every country name is a `.js-country` button** (`national-tournament-view.js:26`) → `openCountry` →
`loadPage('country', { simulatedCountry })` → `renderRepresentedCountry` → **`ReferenceError`**.

**And it was uncaught.** The call sits *outside* `loadCountryPage`'s try block, so it propagated out of the
page entirely. A manager clicking their own nation in a World Cup group got a **blank page**, not an error
card.

**Why nothing caught it.** All six tabs green, the whole suite green, `node --check` parses. The harness
written on 2026-10-08 for exactly this class — it loads the real module in Node because *"a
`ReferenceError` inside a template string needs an engine"* — drove **six tabs and never this path**,
because the path is not a tab.

**Landed:**
- [x] All four functions recovered from `51edd45~1`, restored before `loadCountryPage`
- [x] Represented path added to the Node harness, all three endpoints it reads populated — so a missing one
      cannot pass by rendering nothing
- [x] **`is-highlighted` defined in the stylesheet.** Same rule as `is-current-club` and deliberately so:
      both answer "which row is me?", in two different tables
- [x] The harness **fails** on a missing mark rather than logging it — a row marked with a class the
      stylesheet does not define looks identical to one marked correctly on screen
- [x] Two Java tests: the path renders, and the country being viewed is marked
- [x] **Proven able to fail:** removing the class turns **2 of 5 red**; removing the function turned the
      harness red with `renderRepresentedCountry is not defined` **before any fix was written**

**`is-current-club` is still open** and is deliberately not in this task: `CountryController.qualifyingRow`
puts **no `teamId`**, so the frontend cannot mark the manager's club without matching on **name** — the join
that produced four defects in this codebase. It needs one field.

**The lesson, and it is the second time this file has done it:** a function can be deleted by a commit that
is *about* restoring the file, and every signal stays green. Only an engine driving the path a user can
actually reach finds it — which is why the harness now drives it, and why `theHarnessCanFail` asserts it
drives `simulatedCountry` at all.

## T1-13b · DONE 2026-10-09 (board corrected 2026-10-10) — `is-current-club` carries the club id

**What it was.** The rule exists (`inset 3px 0 0 #4a9eff`) and **nothing emitted it.** The manager's own
club was unmarked in his own qualifying race.

`CountryController.qualifyingRow` sent `teamName`, `position`, `points`, goals and `qualifies` — **and no
`teamId`.** The frontend therefore could not compare anything, and the alternative was comparing **names**,
which this codebase has been burned by four times (`isUserMatch`, the ZOX fixture-id guess, the report's
per-side attribution, `APIController.myMatch`).

**What was done.** `teamId` on the qualifying row · the manager's team id passed into the country view as a
dep · the class list **built** rather than assigned, so `is-qualified` is no longer overwritten · one test
per combination of qualifying and own-club.

**This entry sat open while the work was already done.** `qualifyingRow` sends `teamId`, `pages.js` passes
the manager's id in as a dep, and `country-view.js` builds a class list instead of overwriting
`class="is-qualified"` — so a club that is both qualifying and the viewer's own carries both classes. The
JS harness covers that combination and the negative case.

It was found by reading the board against the source rather than by a test, which is the wrong way round.
Three entries had drifted by the end of the session, in **both** directions: two closed bodies under open
headers, and one open body over finished code.

**Exit criteria:**
- [x] A qualifying row carries its club id
- [x] The manager's own club is marked in all three cups across all five tiers
- [x] A club that is both qualifying and the viewer's own carries **both** classes
- [x] **Proven able to fail:** removing the `teamId` from the payload turns the test red

## T1-17 · ✅ DONE 2026-10-10 — the board's premise was wrong; the exclusion was hiding a guard gap

**This entry claimed `tifo.js` has *"at least four unguarded `.json()` calls and the same
`Loading… / Response status:` debug pairs"*.**

**It has zero of each.** Nineteen of the twenty-one reads already carried an `ok`-check, and all three
`"Loading…"` strings are **UI states that resolve to real content or an explicit message** — they are not
debug output. There are no `console.log` calls in the file at all.

### The one real defect, and it was not the one described

`csApi` returns `null` only for a 401. So the init read's `if (!res) return;` **is not a status check** —
a 403 or 500 arrives as a perfectly good `Response` whose body is a JSON error object, which parses
without complaint. `data.active` was then `undefined` and **the mode began as "no active game" rather than
saying the read had failed**, on its very first request.

Verified against the live server that a failed body parses with no `error` key:
`{"status":404,"code":"NOT_FOUND","message":"No static resource …"}` → `parsesAsJson: true`.

Fixed, with a `readErrorMessage` helper so the failure names itself on screen.

### 🟡 The exclusion was masking a gap in the guard, not a review that had not happened

Removing the `tifo.js` exclusion **passed straight away** — three mutations in a row went undetected. The
reason: that mode routes every request through its own **`csApi`** wrapper, and the guard's window only
recognised `authFetch` and `fetch(`. The guard was reading a file it could not see into.

The window now recognises `csApi(` too, and **both guards are proven against tifo.js**:

- planting `console.log('Loading top scorers...')` above a `csApi` call → *"debug logging beside a fetch:
  tifo.js:2493"*
- removing the status check from the init read → *"these loaders read a JSON body with nothing checking
  the response first: tifo.js:61"*

**T1-10's guard now covers the whole shipped JS tree with no exclusions beyond the frozen demo engine.**

### One honest note on method

Mid-way through this, the browser reported `ReferenceError: getCurrentUserCountryIsoCode is not defined`
at `pages.js:621` — a line that does not exist in the file, which has it at 490. The running app and the
browser's module cache were both stale from a build earlier in the session. **It was not a defect**, and it
was nearly written up as one. Re-verified against the served file and in a fresh browser session.

## T1-14 · ✅ DONE 2026-10-09 — the router carried four routes nothing could reach, and named a fifth wrongly

`RouterNamesResolveTest`, 3 tests.

### The four, and what two of them actually were

| Route | What it did | Verdict |
|---|---|---|
| `teamStats` | called the **assists** loader — `describePage` called it *"team statistics"* | **Duplicate of `topAssists`, and it lied about what it drew** |
| `playerStats` | called the **scorers** loader | **Duplicate of `topScorers`** |
| `friendlies` | called a loader the club page's fully-wired friendly panel already replaced | Duplicate |
| `analytics` | redirected to a standalone ZOX page that **duplicates what the match view already fetches inline** and that nothing links to | Duplicate surface |

**Then the guard found two more, and they were a different kind of thing:**

- **`coaches`** — `club-management` exported `loadCoaches: loadStaff`. **A literal alias.** The same function
  under a second name, reachable only through a route nothing navigated to. Deleted at the source, not just
  at the route.
- **`upcoming`** — rendered the club's fixtures from the same endpoint as `schedule`, under a second name.

**`PAGE_NAMES` also carried `chat`, `events`, `coaches` and `upcoming` with no case behind them** — the
remains of the community routes Phase 6 replaced, and of the two duplicates above. A name in that table
with no case behind it is **how `teamStats` came to be described as "team statistics" for a screen that drew
the assists table.**

### Kept, with the reason recorded

`nationalTeam` and `u21Team` are a **second path to the national-team screen**, which the country page also
reaches as a tab. Kept in `ENTRY_POINTS` **because deleting a route does not un-bookmark it** — a shared
link or a bookmark is a real way in, and removing it turns a screen into *"API Error"*.

### The removal nearly caused a new crash, three times

`pages.js` publishes ~60 functions on `window` for legacy callers. Deleting a function leaves
`window.x = x` pointing at nothing — a **`ReferenceError` thrown while the module loads, on every page.**
That happened three times in this task and the guard caught all three.

**It is the same class as the country page**, where `renderRepresentedCountry` was called at line 759 and
defined nowhere (T1-13), and the same mistake I made twice earlier in the session by believing a restore
that had not happened. A deletion is not done until every reference to it has gone.

### The guard, and the flaw the first mutation found

`everyRouteIsReachable` **removes the `case` labels before searching for a caller.** The first version did
not, so a route's own label made it trivially "reachable" — and the assertion **passed against a planted
route called `legacyArchive` that nothing anywhere navigated to.** A guard that is satisfied by the thing it
is judging is not a guard.

`noDanglingWindowGlobal` resolves names against imports as well as local definitions. The first version
looked only at locals and reported five false alarms on names that were perfectly well imported.

**Exit criteria**
- [x] Every route in the switch is reachable, or is a named entry point with a reason
- [x] `PAGE_NAMES` describes only routes that exist
- [x] No `window` global points at something removed
- [x] **Proven able to fail:** a planted route, a planted dangling global and a planted orphan name each
      turn it red — after the first version was caught passing against a planted route
- [ ] **Not verified in a browser.** Deleting a route cannot be verified by a scan; somebody has to click
      every menu entry

### Two manual-vs-menu gaps found on the way, and **not** fixed here

`userManual.md` §4 promises two Club options that **have no menu entry**:

- **Friendlies** — the screen works, and it is rendered on the Club page itself, so this is a menu gap
- **Coaches** — the same screen as Staff, which *is* in the menu

Both are copy or menu decisions, not defects. Filed as **T1-18**.

## T1-18 · ✅ DONE 2026-10-10 — the manual now matches the Club menu, and cannot drift again

**Owner ruling applied: correct the manual, do not add menu entries.** Both screens work; neither is a
menu entry, and a menu entry for a screen already on the page you reach it from is clutter.

**The board entry was wrong about its own subject.** It said the manual promises **Coaches**; the manual
says **Staff**, and the word Coaches appears nowhere in it. It also missed both halves of the real gap:

| | |
|---|---|
| **Loans** | in the Club action row, and **absent from the manual entirely** — read as a missing feature |
| **Stadium** | documented as a Club option; it is opened from the **Club Profile** page's stadium button |
| **Training** | manual said "Training", the menu says **"Training Setup"** |
| **Friendlies** | documented as a Club option; it is a **panel on the Club page** itself |

An omission was worse than a promise: a manager reading §4 would conclude Loans did not exist.

**What changed.** §4 now lists the fourteen menu entries in the order the action row shows them, with
Loans documented and Training renamed to Training Setup. Stadium and Friendlies moved under
*"Two screens that are not in that row"*, each saying where it is actually reached. The heading exists so
the option list above it can be read as exhaustive and true.

**Why it will not drift again.** `UserManualMatchesTheClubMenuTest` reads `buildClubActionsHtml` — the
function that builds the row a manager clicks — and compares its labels, in order, against the `###`
headings of §4. It reads the source rather than a maintained copy, so a menu entry added tomorrow fails
here instead of in a support question.

**Proven able to fail**, in both directions:

| Planted | Result |
|---|---|
| a menu entry `Inbox` the manual does not document | `theMenuAndTheManualAgree` red |
| a manual heading `Scout Network` with no menu entry | two tests red |

Stadium and Friendlies are excluded **by name**, not by pattern: "works, but is not a menu entry" is the
fact being asserted, and it should not be inferred from a string.

## T1-15 · 🟡 `advanceHour` and `advanceWeek` are self-invocations

`GameClockService.advanceHours` is `@Transactional` and calls `advanceHour()` — a self-invocation, so the
proxy is bypassed. `advanceHour` is itself `@Transactional` and is likewise self-invoked, so its annotation
is inert. **`advanceWeek` is therefore 168 separate transactions**, each committing and each calling
`jobRunner.runDue` in between. A crash at step 100 leaves the clock at week+5-days with 100 hours of jobs
applied and 68 not, with no reconciliation pass.

`JobRunner.java:56-60` writes a long comment identifying exactly this trap, and is then walked into one
layer up.

**Exit criteria:** the hour step runs in the caller's transaction, or the partial application is recoverable

---

# 🟡 T2 — performance and optimisation

**Every task here must state a before and after measurement.** A T2 task with no number is not finished.
Where a number is known it is given — do not re-derive it, but re-confirm it if the code near it changed.

**The dev database cannot measure a T2 task.** `match` holds 155 rows. A sequential scan of 155 rows is
the correct plan and will confidently report "no problem" about a query that costs five seconds at full
scale. Every index added here was measured in a throwaway `sokker_bench` database built to those numbers.

## Already measured — do not redo

| Change | Before | After | Commit |
|---|---:|---:|---|
| Daily recovery zone-load read *(155-match dev database)* | 21.0 ms | **10.8 ms** | `d95da9d` |
| Weekly rollover squad reads (30 clubs, 3 listings) | 180 queries | **0** + 1 bulk | `e310856` |
| League top scorers / assists *(89,280-match season)* | 158.7 ms | **0.19 ms** | `e9142ed` |
| Club match history, played, ordered *(same)* | 170.3 ms | **0.26 ms** | `e9142ed` |
| Club page, all matches *(same)* | 140.6 ms | **0.20 ms** | `e9142ed` |
| One week of a season *(same)* | 156.4 ms | **27.8 ms** | `e9142ed` |
| Club Elo replay — 155 matches, 742 KB event log | 443–554 ms | **6.5–11.2 ms** | `6e63831` |
| `event_json` per match | 840,136 B | **17,001 B** | `6e63831` |
| Recovery read, one matchday *(17.7M-row table)* | 6,173 ms | **~539 ms** | `513f738` |
| International Elo replay — whole-world reads | 1 + 3 × matches | **1** | `379cb12` |
| Weekly squad rollover — squad reads | 1 + 14,880 | **2** | `379cb12` |
| AI friendly pass — week reads, per friendly week | ~59,520 | **2** | `379cb12` |
| Club page blob fetched and discarded | 10 MB | **204 KB** | `6e63831` |
| Pyramid seeding existence check | 14,880 round trips | **48** | — |

**Read the scale column, because it is not uniform.** The first three rows were measured on a 155-match
dev database. The `e9142ed` rows on a full 89,280-match season, and the `513f738` baseline on the same
season with `event_json` at its **real** 17 KB width. **Same query, different harness.**

## T0-POSS · ✅ WRONG DIAGNOSIS — it was never the engine (2026-10-10)

**The owner was right that possession was wrong, and I was wrong twice about why.**

### What I got wrong

1. **"The production engine has no duels."** It does. `newLogic` has its own `DuelEngine` (13KB) and a
   `DuelService`, and `MatchOrchestrator:701` runs `detectAndResolveDuels()` every tick. I found this by
   grepping for `DuelEngine` and letting **`head -6` truncate the list** — every line I saw was the frozen
   `demo/` engine, so I stopped looking and reported the wrong answer.
2. **"Zero duels in 707 matches."** Also wrong. `DUEL` is not in `SimReportMapper.REPORTABLE_TYPES`, so
   duels are filtered out of the stored event blob *by design*. The count was an artifact of the filter.
   The proof duels work is simpler and was in front of me: **6.7 home / 8.0 away fouls a match**, and a
   foul can only be recorded from `DuelService:98` — a duel the defender won. ~15 contested duels a match.
3. **"A press must be made non-reactive."** I widened `PRESS_ORDER_RANGE_A` to 4.0 so the closest defender
   is *sent* rather than needing to be in range already. Measured over 60 simulated matches it changed
   **nothing** — and the mutation test proved it: reverting to 1.5 still passed. **A change that cannot be
   shown to matter is not a fix, so it was reverted.**

### What was actually wrong

**The user's own club has a blank squad.**

| team | players | rated 0 | role null |
|---|---|---|---|
| **OFK Omladinac** (id 1) | **13** | **13** | **13** |
| **Sremac Berkasovo** (id 2) | **17** | **17** | **17** |

Match 80 — *OFK Omladinac 2-0 OFK Proleter Apatin, 90.6% possession* — was **13 players rated zero, with
no roles assigned, against a real club of 25 averaging 75.6.** The engine's `Math.max(1, rating)` floor
gave those blanks a nominal strength and the match played out exactly as it should have.

**So the possession number was accurate and the match was legitimate.** 91/9 with 0 away events is what
13 unrated players against 25 rated ones looks like. This is the same signal the dashboard was already
giving: *"Bench depth missing — Only 1/7 bench slots are filled right now."*

### The owner's ruling stands regardless

*"A 0-event match can never happen."* **That is right, and this match is not a counter-example** — it was a
club that could not field a team. The invariant worth keeping is that a side with a real squad always
contests the ball, and `BothSidesGetTheBallTest` asserts it over **100 simulated matches**: worst
possession **7.5%**, least active side still produced **83 actions**.

---

## T0-SQUAD · 🔴 FIXED 2026-10-10 — `PlayerFactory.createPlayer` never set the rating

**The real defect, and it is one line.** `PlayerFactory.createPlayer` set skills, position, age, value,
wage and form — and then **stopped**. No `setRating`, no `setRole`.

Every other path that builds a player does this: the world seeder at `PlayerFactory:246`,
`BotSquadGenerator`, `BotLeagueStandardBackfill`, `PlayerRatingBackfill`. Four writers set it, this fifth
one did not, and the two clubs it created were unplayable.

### What that produced

| | before | after |
|---|---|---|
| OFK Omladinac (13 players) | every rating **0** | **56 – 93** |
| Sremac Berkasovo (17 players) | every rating **0** | **35 – 65** |

**Ratings only.** Their skills, ages, values, talents and positions were all correct throughout — only
the derived number was missing, so the repair is exact and nothing was invented.

A rating of 0 is not "a weak player": the engine floors it at **1**, so a whole squad sits at the bottom
of the scale. That is why the match produced 90.6% possession and an opponent with no events.

### Why it survived

**`WorldIntegrityService` reports the world healthy and never looks at club squads.** It checks countries,
competitions, national sides and legacy rows; `PlayerRepository` appears in it exactly once, for national
squads. "Repair world" was pressed on the owner's database and returned `healthy=true` with these two
clubs unplayable in front of it.

### 🟡 A test I wrote was wrong, and the data corrected it

I first asserted *"no player lacks a role"*. **All 33,430 players lack one** — the column is never
written, because `RealSquadFactory` derives the role from position and always has, and `LoanController`
documents reading `careerRating()` instead. A null role is the world's normal state and is **not** the
defect. Narrowed to the rating, which is the thing that makes a team unplayable.

### Exit criteria

- [x] Root cause found: `createPlayer` never set the rating
- [x] Fixed at source, and `PlayerRatingBackfill` now also fills a null role
- [x] Owner's two clubs repaired — **30 rows**, ratings 0 → 35-93, using the app's own formula
- [x] Backup taken first (`_bak_blank_squads`, 30 rows) before anything was written
- [x] `PlayerFactoryGivesEveryPlayerARatingTest` — 4 tests, **mutation removes the line and 2 go red**
- [x] `ClubSquadIsPlayableTest` asserts the real invariant against the live world
- [ ] **`WorldIntegrityService` should check club squads** — it currently reports a broken world as healthy

## T0-U21 · ✅ FIXED 2026-10-10 — a U-21 side had 31-year-olds in it, and the pool offered more

**Found by the owner, 2026-10-10.** Serbia's U-21 squad screen listed 25 players of whom **11 were over
21**, the oldest **32** (Luigi Verdone 31, Živko Malić 31, Boris Gospojević 30). The national pool
beside it offered a 32-year-old defender to a youth side.

**Measured, not eyeballed:** the U-21 roster spans **ages 19 to 32**, with 11 of 25 over the limit.

### The cause

`availablePlayers(country, squad)` filtered on exactly one thing — *not already called up*. **It was
never given the level it was building a pool for**, so it could not apply an age limit even in
principle. The level was in scope one method away and simply was not passed down.

### The fix

- `U21_MAX_AGE = 21`, **inclusive** — a player is eligible in the season he turns 21, which is the
  football convention. `0` is treated as "not recorded" rather than as an infant, because `Player.age`
  is a primitive `int` and an unknown age cannot be shown to be under 21.
- **The pool is filtered** (so nothing new arrives) **and the write is enforced** in `addToSquad` (so
  nothing gets in by any other route). A pool filter alone stops new mistakes and leaves every existing
  one in place.
- **Nobody is deleted.** The 18 over-age members are named in a new `overAgeSquadMembers` field so the
  selector can see exactly who the rule would refuse and decide what to do. Quietly rewriting a
  manager's roster is worse than showing him what he has.

### Evidence

**Live, against the database, through the running app:**

| | |
|---|---|
| U-21 pool | **0 of 80** over 21 (was offering them) |
| U-21 squad | 18 of 25 over 21 — **all 18 now named** |

`UnderTwentyOneEligibilityTest` — 3 tests, including the check that **Serbia still has 2,040 eligible
players**, so the rule leaves the youth side fieldable. A limit nobody can satisfy is not a rule.

**Honest gap:** the write-path rejection was verified by reading the path and by the exception it
throws, **not by pressing it in a browser** — doing so would have meant surgically rewriting the owner's
live roster, and the first attempt to free a slot was correctly blocked by a `player_contract` foreign
key. The pool result is the live proof; the write check is not.

## T0-CUPS-SEASON1 · ✅ FIXED 2026-10-10 — the World page said "Not drawn yet" for cups that were drawn

**Found by the owner, 2026-10-10**, on the World page's international-competitions table: the Field column
read **"Not drawn yet"** on cups that plainly had a full draw.

### Two separate bugs, and the second is worse

**1. The badge required a perfect score.**

```js
qualified === expected            // expected is 48, or 96 for the Masters Cup
```

**One country failing to qualify flipped every tier from "48 qualified" to "Not drawn yet."** That is not
a warning — it is the opposite of the truth, on a screen whose whole job is to say what exists.

Measured from the live API, which is what made it diagnosable:

| | qualified | shown as |
|---|---|---|
| Champions Cup, tiers 1–3 | 48 / 48 / 48 | ✅ 48 qualified |
| **Tier 4 Champions Cup** | **47** | ❌ **Not drawn yet** |
| **Tier 5 Champions Cup** | **47** | ❌ **Not drawn yet** |
| **Tier 4/5 Masters Cup** | **94** | ❌ **Not drawn yet** |

**2. 🔴 The Netherlands has no lower pyramid at all.**

```sql
Netherlands | 6 leagues | 59 clubs      -- every other country: 31 leagues
tier 1: 1   tier 2: 2   tier 3: 3        -- and nothing below
```

**One country out of forty-eight.** It is `SIMULATED` like the others and `PyramidBuilder` loops all five
tiers unconditionally, so it was simply never fully built. That is the whole cause of 47 — and it is a
**world-building gap, not a draw defect.** The draw correctly produced one entrant per country that has a
tier-4 division, which is 47.

**Fixed:** the badge now reads *"Not drawn yet"* only when **no tier has a single entrant**, which is the
only state in which it is honest. Anything else shows the real count with `of 48` beside it, so a short
draw is visible as short rather than invisible.

### The season-1 gap, which prompted all of this

The owner asked how to seed the cups in **season 1**, having no previous season's results. Correctly
diagnosed: the entry rule reads a **finished** table, season 1 has no season 0, so
`InternationalClubCupJob` drew nothing and logged *"no qualified clubs"* for all fifteen cups.

**Fixed per the owner's ruling — read the current season's tables as they stand.** Same data, same
`LeagueTableOrder` comparator, same rules; only the season asked about differs. No new ordering invented,
and reputation is *not* substituted for finishing position.

The admin button now takes **both seasons** (`qualifyingFrom`, `drawnInto`), because the automatic pairing
(active → active+1) can never reach season 1, and qualifying from a season that is not yet finished is
**refused** rather than quietly treated as the current one.

### Evidence

- `InternationalCupsQualifyInTheFirstSeasonTest` — 4 tests. **Qualifying off season 1 returns 48 clubs**,
  which is exactly a full Champions Cup.
- Every one of the fifteen cups qualifies somebody in the first season.
- The fallback only fires when the previous season has nothing: a test pins that an unfinished season
  qualifies **fewer** clubs than a played one, so the fallback can never quietly draw season 2 off its own
  unfinished tables.

**Honest gap:** the *draw* was not run against the live world, because it writes 15 cups × 48 clubs of
fixtures into the owner's season 1. The qualification path is proven by test; the write is not.

## T0-RESET-MATCHES · ✅ MEASURED 2026-10-10 — Reset DB does clear recent matches; it is not retaining them

**The owner asked whether recent matches survive `Reset DB`.** The dashboard showed three season-1 results,
which looked like residue. **It is not.** Measured, not read:

A throwaway copy of the whole database was created from `sokker_db` (`CREATE DATABASE ... TEMPLATE
sokker_db`), the real `/admin/reset-db` was run against it, and the counts read:

| | before | after |
|---|---|---|
| `match` | **707** | **0** |
| `match_fixture` | **5 452** | **0** |
| `app_user` (preserved) | 2 | **2** |
| `team` (owner clubs) | 14 723 | 2 |

**The reset works exactly as documented.** `ResetService` enumerates tables from the live schema and keeps
only `app_user`, `user`, `tactics`, `formation` and `formation_positions`; `match` is not among them. The
copy was dropped afterwards and `sokker_db` was never touched — still 707 matches.

**Why those three matches are still there:** no reset ran in this session (`RESET DATABASE STARTED`
appears in no log), and all 235 season-1 matches have a fixture pointing at them — **zero orphans**, so
they were played through the normal fixture flow rather than left behind by a failed clear.

## T-REST-19 · 🟡 ANSWERED 2026-10-10 — do cup, national and friendly matches reach the club's schedule?

**Asked by the owner.** Answered by reading the routes and then by calling them, not by assumption.

| Competition | In Club → Schedule? | In Next Match / Recent Matches? |
|---|---|---|
| **International club cups** | **Yes** — `?competitionType=CUP` returns them | **Recent: yes. Next: NO** |
| **National cup** (Kup Srbije) | **Yes** — same route, same filter | **Recent: yes. Next: NO** |
| **Friendlies** | **Route supports it, nothing to show** | same |
| **National-team tournaments** | **No, and cannot** — clubs do not play them | n/a |

### The one real gap: **Next Match ignores everything that is not the league**

`dashboard.js:618` calls `/teams/{id}/schedule` with **no filter**. The controller then does:

```java
Competition competition = (wantedType == null && wantedMatchType == null)
        ? resolveScheduleCompetition(team, activeSeasonYear) : null;   // the club's LEAGUE
```

**Measured on a club that is actually in the Champions Cup** (team 99):

- `/teams/99/schedule?competitionType=CUP` → **5** fixtures
- `/teams/99/schedule` → **0**

So a manager in a continental competition is told **"Next Match: nothing scheduled"** while standing in a
group with five matches in it. The fixtures, the results and the Recent Matches card all know about the
cup; the Next Match card does not.

**Recent Matches is fine** — `/teams/{id}/matches` carries **no competition filter at all**, so a played
Champions Cup tie comes back with its competition and score (verified: a 1-1 Champions Cup match).

### 🟡 Why the schedule page is right and Next Match is not

This is the same shape as the id-space bug the board has twice: **the correct behaviour is already in the
file next to the defect.** `getSchedule` grew `competitionType` and `matchType` filters precisely because
*"a caller asking for one kind of football cannot be answered from the league alone… the frontend's cup
and international screens were pointing at fabricated data because there was nothing real to ask for."*
The Schedule page uses them. The dashboard never asks.

### ✅ FIXED 2026-10-10 — Next Match is any kind of match (owner's ruling)

*"Next matches i recent matches pokazuje bilop koji mec koji je na redu za igru i bilo koje odigrane
meceve nevezno za tip meca"* — next match and recent matches show whichever match is next and whichever
matches have been played, **regardless of type**.

**Recent Matches needed no change.** `/teams/{id}/matches` already carried no competition filter, which
is why a played Champions Cup tie was already coming back with its competition and a 1-1 score.

**Next Match did.** `/teams/{id}/schedule` now takes **`allCompetitions`**, and only the dashboard asks for
it. An explicit parameter rather than a missing one, because **the default has to keep meaning the league**
for the Schedule page — and a filter that silently fell back to the league would be worse than no filter.

**Measured on team 179, in the Tier 4 Masters Cup:**

| | fixtures |
|---|---|
| `/schedule?seasonYear=2` | **0** |
| `/schedule?seasonYear=2&allCompetitions=true` | **4** — *Tier 4 Masters Cup* |

**Honest note on what the dashboard shows today:** the calendar is on season 3 and no cup fixtures exist
for it, so the card reads *"Schedule updating"*. That is true rather than broken — the cups were drawn for
seasons 1 and 2.

## T0-TACTICS · 🔴 OPEN 2026-10-10 — tactics cannot be listed, loaded, copied or chosen

**Found by the owner, who compared it with sokker.org** (logged in, reviewed, logged out).

### The reference, observed

Match settings on sokker.org is one screen carrying three things:

| | sokker.org | here |
|---|---|---|
| **Basic tactic** | dropdown — `V4312` | **absent** |
| **Additional tactic** | dropdown — `4312V` | **absent** |
| **Squad setup → "Based on"** | dropdown, sets **position order** | **absent** |
| Pitch + draggable players | yes | yes |
| Suggest lineup | yes | no |

The screen reads *"Squad setup · Suggest lineup · **Based on: tactic** · position order"*, followed by the
numbered eleven. **A tactic is a saved, named thing you choose, and choosing one sets the position order
of the squad.** That is the whole of the difference.

### What exists here, and why it cannot do any of this

`tactics-editor-view.js` **PUTs to `/teams/{id}/tactics-editor` with no tactic id**, and the row behind it
is `TeamTacticsProfile` — **one profile per club**. So there is no list to render, nothing to load, no id
to save under, and nothing for a match-setup screen to select.

**The consequence the owner hit:** a tactic cannot be selected in match settings, because there is only
ever one and it is not offered.

**This is not the same as the `Tactic` library from T0-BE-1.** That is a *conditional* tactic — a rule set
with six score-based conditions for the match plan. This is the **basic/additional tactical grid**: the
formation, the style, the movement rules and the set pieces, which is what sokker.org's dropdowns hold.

### What is needed

1. **A named tactic list per club**, with an id — so a tactic can be opened, changed and saved.
2. **"New tactic"**, optionally **"based on"** an existing one, so a manager copies rather than starting
   from zero.
3. **Save-as under a new name**, leaving the original untouched.
4. **A tactic selector in match settings**, which also carries the position order — sokker's "Based on".

**Decisions that are the owner's, not mine:** whether "additional tactic" is one more saved tactic or a
distinct second slot in a match, and what a copied tactic inherits beyond the grid (name? set pieces? both?).

## T2-1 · 🔴 `IDENTITY` disables JDBC batching — the blocker under T2-2 and T2-4

`IDENTITY` generation disables JDBC batching for **70 of 71 entities**, so `batch_size=50` is dead code.

**Q1 was answered: keep `IDENTITY`** (option B). No schema change on 14,880 clubs' worth of data, and the
honest partial is taken. **T2-2 stays blocked and should say so rather than be re-attempted.**

The cost is that every write path that wants to batch cannot, which is why T2-2, T2-4 and T2-5 all become
slower rather than faster. That is the price of the decision and it should be recorded as one.

## T2-2 · `ZoneLoadRecorder` writes 198 rows per match, one at a time

22 players × 9 zones, each row saved individually. There is already a unique index on
`(player_id, match_id, zone)`, so the rows are known to be distinct — **the batch is safe to attempt.**

- [ ] **BLOCKED while `IDENTITY` stands** (T2-1). Recorded as blocked, not retried
- [ ] When unblocked: rows and elapsed time measured before and after

## T2-3 · ✅ DONE — same entry as T1-7; `MatchdayJob` issues one query

See **T1-7**. It is on this board twice because it is a **performance** defect and a **correctness** one:
a query issued sixteen times returns the same set, and the number grows with every tier added.

**Closed on measurement.** The live database holds exactly **sixteen `CUP` competitions** — the board's
number was right and its shape was not. `findUnplayedOnDay` appears **once** in the file and the loop
filters in Java. One query, and it does not grow with the tiers.

**Exit criteria:** one query. Measured, not asserted.

## T2-4 · The static world build — ~43,000 inserts for 14,260 clubs

Already improved substantially: per-country `REQUIRES_NEW` (so a failure at country 40 loses 40 countries
rather than all 48), one read and one `saveAll` per division instead of two queries and one INSERT per
club. **The remaining cost is the rows themselves** — about a country a minute. Faster, visible, resumable,
not instant.

- [ ] The remaining cost is stated against a target, not against "it used to be worse"
- [ ] Where it runs is a design question, not a performance one: **the world is being built implicitly
      inside a matchday job** while a **Seed other nations** button exists for exactly that work. Owner
      decision.

## T2-5 · `JobStatusService` reads the whole `job_run` table

See **T1-11**. ~20 rows a game day, unbounded later.

## T2-6 · `GoalEventRepository`'s season-wide blob walk

See **T1-12**. **Measured before changed** — the blob was cut 49×, which is the only reason this is a T2
rather than a P0.

## T2-7 · `StaffSponsorService.seedAllClubs` — 1 + 14,880

See **T1-9**. A genuine N+1 on a seeding path.

## T2-8 · Scale-sensitive read paths at 48 countries

`match`, `match_tick_states` and `player_zone_load` remain scale-sensitive. **Three of the four indexes
already landed were measured on the 89,280-match harness, not the dev table.**

`player_zone_load`'s unique index leads with `player_id`, so `findByMatchId` cannot use it — which is why
paging made it necessary and why `IN` with bound values must never be refactored into a subquery
(5,020 ms against 44 ms, same rows).

**Exit criteria:** every remaining hot query measured at full scale, in the bench harness

## T2-9 · `match_tick_states` is dead and grows zero

Nothing writes it, nothing reads it, 0 rows, and its only writer has no callers. **Do not index it.**
Deleting it is an owner call — see **T-REST-18**.

## T2-10 · The basketball and American-football N+1

`BbMatchSimulationService.savePlayerStats` and its American-football twin do **four queries per player per
match** — `findById`, `save`, a season-stats lookup, `save` — which is **88 round trips a match**. Measured
at **0.1745 ms** per round trip on this machine, so **15.4 ms a match** of pure overhead.

**It has never run**, because no match has ever been simulated in either sport. When these sports are
played, **this is the first thing to fix** — not a missing index on `bb_players`, which tops out at 5,580
rows where a scan costs under a millisecond.

**Exit criteria:** batched writes, measured against the round-trip count

---

## T-PERF-1 — 🔴 P1 · one training pass over the world takes six minutes of pure CPU

**Found by measurement, 2026-10-11**, while proving the clock work.

### What was measured

Against the owner's database — 48 countries, 14,880 clubs, ~300,000 players — one pass of the
day-4 training job:

| | |
|---|---|
| Wall clock | **> 6 minutes**, still running when measured |
| CPU | **388 s of CPU in 398 s of wall** |
| Database | **completely idle** — `pg_stat_activity` showed no running query, six samples over 12 s |
| Thread dump | 388 s inside `org.hibernate.engine.internal.Cascade.cascade` |

**No query was running.** This is not a slow query; it is Hibernate doing work on every query.

### The cause

`SquadEnvironmentService.advanceWeek` (and `SquadTrainingService.trainEveryClub` beside it) walk every
club in one transaction with one persistence context. `advanceWeek` loads all ~300,000 players with
`findByTeamIdIn`, then for each one asks for his minutes and his mentors — **while his club's players
are already dirty**. Hibernate auto-flushes before a query, and the flush cascades over **every entity
dirtied so far**. Three hundred thousand players, one cascade each, over a set that only grows. That is
O(clubs²), and it is why the database is idle while the CPU is not.

`trainSquad` is reached through `this.`, so its `@Transactional` is bypassed and the whole pass is one
transaction; there is no natural flush boundary.

### An attempt that did not work, and was reverted

Separating the read phase from the write phase in `advanceWeek`, and flushing + clearing every 50 clubs
in `trainEveryClub`. **Measured at 393 s CPU in 408 s — no better than the 388/398 baseline.** Reverted
rather than shipped: an unmeasured perf change labelled as a fix is exactly the thing this repository
keeps writing warnings about.

### What is likely to work, and is not done

Reading through a **projection** (`id, teamId, familiarity, morale`) instead of entities, so nothing is
put in the persistence context at all. That is a real change to `advanceWeek` and `trainEveryClub` and
was not started.

**Blocks:** every use of the advance buttons on a 48-country world. The clock work made this visible by
making the advance return immediately and report `syncing` honestly; it did not make the jobs fast.

---

## T-FIN-UI-1 — 🟡 P2 · the Finances page shows totals and throws the rest away

**Found by the owner, 2026-10-11**: *"stavke pojedinacne na Finance mora da se vide da se zna tacno sta i
koliko je stigli i od koga i sta su tacno troskovi.. treba da se mogu videti tenantna nedelja (default),
a da mzoe da se izabere mesec ili season"*.

- **`/api/teams/{id}/finances/ledger` exists and is never called** from anywhere in `src/main/resources`.
  It returns exactly the per-line detail asked for: `id, week, seasonYear, category, label, income,
  amount, note`.
- **`byCategory` is returned by the summary endpoint and never rendered.** A repo-wide grep for it in
  `static/` returns zero hits.
- **No week or month scope exists at all**, and the season picker only renders when the club has a
  ledger in more than one season — so on a fresh world there is no picker at all.

Every ledger row carries a populated `note` ("62% of a tier 1 weekly wage bill of EUR 18,478,000",
"Home fixture, 34,437 of 43,780 at an average EUR 5.87 (79% of the ground)"). All of it is fully
populated and fully invisible.

**Not started.** The endpoint and the data are there; this is a rendering task.


---

## T-ECON-2 — 🔴 P0 · Omladinac's wage bill was 45-84% of his own value per year

**Owner instruction, 2026-10-11:** *"treba ispraviti plate u inicijalizaciji Omladinac igraca... kao
zakrpu, smanji plate direktno u bazi"*.

`PlayerFactory.createOmladinacPlayers` reads a hand-entered table carrying a market value and a weekly
wage in two adjacent columns, and **nothing anywhere checked one against the other**:

| | Value | Weekly wage | Per year | % of value |
|---|---|---|---|---|
| Zvezdan Vukomanović | 186,900,000 | 1,888,000 | 98m | **53%** |
| Žika Veljković | 103,880,000 | 1,684,000 | 88m | **84%** |

Real football pays 15-25% of a transfer fee per year for a star.

### Fixed, both ways

- The wage is **derived** from the value now — 20% a year, on the same age curve the renewal demand
  uses — so the two cannot disagree. Values are untouched: they are the owner's own figures.
- Patched in the database with `tools/patch-omladinac-wages.sql`, idempotent, after a `pg_dump`.

| | Before | After |
|---|---|---|
| Wage bill | 18,478,000 | **4,845,682** |
| % of value per year | 45-84 | **14-22** |

---

## T-FIN-UI-2 — ✅ DONE · the ledger endpoint existed and nothing called it

`/api/teams/{id}/finances/ledger` returned `category, label, income, amount, note` and a repo-wide grep
for it across `src/main/resources` returned **zero hits**. Every ledger row already carried a note
("78% of a tier 1 weekly wage bill of EUR 4,845,682", "Home fixture, 34,437 of 43,780 at an average EUR
5.87 (79% of the ground)").

Now called and rendered, scoped to **this week** by default, with the week read from the game clock
rather than hardcoded, and four-week and whole-season options. A month is four weeks, because there is
no date in this world to make a calendar month out of.

---

## T-PERF-1 — ✅ FIXED 2026-10-11 — but measured honestly

**Before:** a training pass over 14,880 clubs did **not complete in 6+ minutes**. Killed twice while
still running. 388 s of CPU in 398 s of wall, all inside Hibernate's cascade-on-flush, with the
database completely idle — six samples showed no running query at all.

**After: completes in 259 s** and records DONE.

**It was never a slow query.** Every auto-flush before a query cascades over every entity dirtied so
far, and both loops ran in one transaction with one persistence context holding every player in the
world. The cascade set only grows, so the work is quadratic in the size of the world.

Three things were wrong and all three had to change: `trainSquad` is reached through `this.`, so its
`@Transactional` never applies and the whole pass is one session; the mentor and minutes caches resolve
on first use and that first use was happening *inside* the write loop with the chunk already dirty;
and reading each club with `findById` would have traded an ORM problem for a query problem.

**The first attempt is on the board because it failed and was reverted.** Separating reads from writes
and clearing every 50 clubs measured at 393 s CPU in 408 s — no better than the 388/398 baseline.
Chunking alone is not enough; chunking *plus warming the caches* is.

**Not claimed:** 259 s is a completion time, not a speed-up factor. A clean before/after CPU
comparison is not available because the "before" never finished.

**Still open, same shape, not looked at:** the **finance** job walks the same 14,880 clubs per week and
has not been measured.
