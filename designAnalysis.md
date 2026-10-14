# designAnalysis.md — a design review of the TIFO football manager UI

**Written 2026-10-14 against the running application, not against the source alone.**
Reviewed as a designer for football managers. Every claim below was observed in the browser at
`localhost:8080` with the owner account (`velibor@example.com`, club **OFK Omladinac**, country
**Serbia**, Superliga Srbije, Season 1 · Week 6).

| Viewport | Used for |
|---|---|
| `1920×900`, `1512×982 @2x` | desktop |
| `1280×900`, `1440×900` | laptop / breakpoint hunting |
| `430×932 @3x` | **iPhone 14 Pro Max, portrait** — the reference device |

Pages opened and read: Dashboard · First Team · Player · League Table · League Schedule · League
Matches · Top Scorers/Assists · Country General/Calendar/Clubs/Qualifying/National Team/U-21 ·
Kup Srbije · World · Transfers · Finances · Admin Tools/Jobs · mobile menu · account and
notification dropdowns.

This document does not touch the backend. Where something is a data defect rather than a design
defect, it says so and leaves it to the board.

---

## Contents

1. [The short version](#1-the-short-version)
2. [What is genuinely good](#2-what-is-genuinely-good)
3. [P0 — broken, blocking or unreadable](#3-p0--broken-blocking-or-unreadable)
4. [P1 — serious, and the reason you cannot find your club](#4-p1--serious-and-the-reason-you-cannot-find-your-club)
5. [Finding your club and your country — the full inventory](#5-finding-your-club-and-your-country--the-full-inventory)
6. [Lists, tables and dropdown menus](#6-lists-tables-and-dropdown-menus)
7. [Mobile — iPhone 14 Pro Max portrait](#7-mobile--iphone-14-pro-max-portrait)
8. [Cross-cutting](#8-cross-cutting)
9. [A proposed "you are here" convention](#9-a-proposed-you-are-here-convention)
10. [Suggested order of work](#10-suggested-order-of-work)

---

## 1. The short version

The visual language is good and the information architecture is mostly right. What is broken is
**wayfinding**: the application rarely tells you which club is yours, which country is yours, or
which row of a list you are looking for. On top of that there are four genuine rendering bugs —
one of which makes the season dropdown unclickable on a 1280px laptop, and one of which hides the
points column of the league table on a phone.

| Verdict | Area |
|---|---|
| **Broken** | `.logo-corner` covers live UI at every width ≥ 769px · player page columns overlap by 88px · league table clips PTS/Elo/± on mobile · the Back button is a 1138px gradient bar on every club page |
| **Not findable** | league table (no own-club row) · Kup Srbije (278 ties, no bracket, no own-tie jump) · country qualifying (15 stacked tables, 6,588px) · transfer market (74 cards, 10,407px) · country page (31 options behind a dropdown, no division list) |
| **Good** | dark card system and spacing · Admin Jobs table adapts properly on mobile · league results density and win/draw/loss colouring · player match history · id-based own-club marking where it exists |

The single highest-value change in this document is **[§9](#9-a-proposed-you-are-here-convention)**:
one convention, one token, applied to every table. The second is the cup page getting a bracket
and a "your tie" jump.

---

## 2. What is genuinely good

Worth protecting, because a redesign that loses these would be a step backwards.

**2.1 The visual system is coherent.** One dark palette, one card idiom (`.fm-panel` with a soft
border and a rounded corner), one accent (`#8fd3ff`), one KPI tile. Panels, tabs, chips and pills
read as one family. The stadium photograph behind everything gives the product an identity that a
generic admin template would not.

**2.2 Responsive tables were done deliberately, not by accident.** The squad table, the league
standings and the two top-list tables are wrapped in `.fm-squad-wrap { overflow-x: auto }`, and the
source comments say exactly why and with what measurement:

> `pages-renderers.js:1201` — *"Measured at 430 CSS px: the table rendered 510px wide, the panel
> ended at 408px, and there was no scroller — so the Elo and rating-delta columns ran under the
> panel's rounded edge and out of reach."*

That is the right instinct and the right comment. What is missing is the second half: making the
horizontal scroll **legible** (§7.2).

**2.3 The Admin Jobs table is the best responsive work in the app.** At 430px the `Trigger` and
`Next trigger` columns are dropped with `display:none`, the table reflows to exactly the wrapper
width (384px, `scrollWidth` 384 — no overflow), and the remaining three columns — job name, last
outcome, failure count — are precisely the three that matter. Verified:

```
table 384px = wrapper 384px, overflow-x: auto, no clipping.
```

**2.4 The league results list has the right density and the right emphasis.** 45 results in one
scannable column, the score in a box whose colour carries W/D/L, the competition and round on the
left. This is the one long list in the app that behaves like a football list.

**2.5 The player match history is excellent.** `L` chip, `ŽFK Radnički Sport 2–1 OFK Omladinac`,
competition underneath, `90'` and a coloured rating at the right, goal/assist/card icons inline.
It is the densest information per pixel on any screen, and it reads at a glance.

**2.6 Dot-ratings for ability and potential.** Six dots instead of a number is a good instinct for a
squad view — the manager scans shape, not precision. The `HOT` / `INJ` / `FIT` / `GLS` / `AST`
chips give the left edge of the row a second, colour-coded channel.

**2.7 Own-club marking, where it exists, is id-based — never name-based.** This is important and it
is correct. `country-view.js:151` carries the comment *"Compare by id, not by name. Name comparison
has been burned four times in this codebase."* The convention deserves to be spread; the
engineering should not be touched.

**2.8 Error states carry the server's message.** `buildErrorState(err, describePage(page))` replaced
the old generic `"API Error"` card, and `PAGE_NAMES` maps route ids to *"the first team"*, *"the
league table"*. A manager who hits a problem is told what it was.

**2.9 Scroll-to-top on every navigation.** `scrollToTopOfPage()` in a `finally`, instant, with a
comment explaining why. Small, correct, and it was a real complaint once.

---

## 3. P0 — broken, blocking or unreadable

### P0-1 · The corner logo is a fixed overlay that covers live UI at every desktop width

`overrides.css:255` — `@media (min-width: 769px) { .logo-corner { position: fixed; top: 12px;
right: 20px; z-index: 10; } .corner-logo { width: 146px; } }`

There is no space reserved for it. It floats over whatever happens to be in the top-right corner.

**At 1280×900 — the most common laptop width — it sits directly on top of the season dropdown.**
Measured: logo occupies `x 1106–1260, y 12–170`; the `.fm-season-select` on the league pages sits
in exactly that band. You cannot open the season picker. The same screenshot also shows the live
clock running past the right edge (`#clock-date` right edge 1303px in a 1280px viewport).

The same fixed logo also overlaps:

- the **IMPORTANT UPDATES ticker** on the Dashboard,
- the **Fixtures & Results** panel header on the League Table page,
- the **country hero** on every Country page.

At 1920 and 1440 it sits in empty space, which is why this survived. It is not a mobile-only or a
narrow-window problem; it is the standard laptop.

**Measured horizontal overflow:**

| Viewport | `document.scrollWidth` | Viewport | Overflow |
|---|---|---|---|
| 1920 | 1920 | 1920 | none |
| 1440 | 1440 | 1440 | none |
| **1280** | **1303** | 1280 | **+23px** |
| **1100** | **1303** | 1100 | **+203px** |

The whole application scrolls sideways between roughly 1100px and 1303px.

> **Fix.** Take the logo out of `position: fixed` and make it the last cell of the top bar's flex
> row (`margin-left: auto`). If it must stay a floating emblem, the top bar needs
> `padding-right: 180px` and the clock needs `flex-shrink: 0` / a `min-width: 0` on its text.
> Either is ten lines. The current state is a functional break, not a cosmetic one.

### P0-2 · The player page's two columns overlap by 88px and the pitch covers the attributes

`dashboard.css:3073` — `.fm-player-grid { grid-template-columns: minmax(280px, 0.85fr)
minmax(0, 1.45fr); }`

Grid items default to `min-width: auto`, so the left item's min-content width wins over its track.
Measured inside a 1180px container:

```
left column  x 166, w 533   → right edge 699
right column x 611, w 735   → left edge  611
                                  overlap 88px
```

533 + 735 + gap exceeds the 1180px container by 88px. The `min-width: 0` that fixes this exists
in the stylesheet — but only inside the mobile `@media` block at `dashboard.css:3438`. On desktop
nothing applies it.

What the manager sees: the green pitch is drawn on top of the **Attributes** panel, covering the
attribute *names* (`Pace`, `Finishing`, `Technique`, `Dribbling` are unreadable — you see `…cina`,
`…nique`, `…ng`), and it also covers the first column of the **Statistics** table header.

> **Fix.** Move `min-width: 0` out of the media query onto `.fm-player-grid > *`. One declaration.

### P0-3 · On a phone the league table's points column is off-screen with no scroll affordance

Measured at 430px:

```
table      scrollWidth 510, clientWidth 510
wrapper    clientWidth 384, overflow-x auto
```

The wrapper *is* scrollable — the earlier fix was correct — but:

- **PTS is the column that decides the table.** It is clipped. Elo and ± are entirely invisible.
- There is **no visual hint** that it scrolls: no fade, no shadow, no "→ scroll" affordance, no
  sticky first column. On a touch device there is nothing to tell you to swipe.
- Club names wrap to **three and four lines** (`ŽFK Radnički Sport`, `OFK Timok Čačak 1901`) in a
  ~90px column, so rows are ~200px tall. Ten clubs occupy 2,000px.
- The **manager's email address** (`velibor@example.com`) wraps onto two lines inside the table
  cell, adding a third line to the longest row.

> **Fix, in order of value:**
> 1. Drop `GF` and `GA` below 640px and keep `P W D L GD PTS`. Nine columns → seven.
> 2. Sticky the `#` and `Club` columns (or at minimum `#`) so the identity never scrolls away.
> 3. Add a right-edge fade mask on `.fm-squad-wrap` so it is obvious there is more.
> 4. Remove the `AI` badge from its own line — inline it with the name, or show it only on hover /
>    in the club profile.
> 5. Move the manager name out of the row entirely; the `PLAYER` badge already carries the fact.

### P0-4 · The league table does not mark your own club

This is the screen the whole game is about, and it does not say which row is you.

`pages-renderers.js:979` builds the row class as:

```js
class="${zoneClass(rank, rows.length)} ${CTeam.teamId ? 'js-load-CTeam' : ''}"
```

`zoneClass` returns `zone-title` / `zone-top` / `zone-rel` — those are **promotion and relegation
zones**, not ownership. The colour bars down the left edge that look like "this is mine" are
rank 2–4 colouring, and they are on TSK Tamiš Leskovac and ŽFK Radnički Sport exactly as they are
on OFK Omladinac.

The only ownership signals on your own row are:

- a `PLAYER` chip,
- and a **clickable email address**.

That is it. On mobile, `velibor@example.com` breaks across two lines, and the `PLAYER` chip sits
under a three-line club name. This is the core of your complaint about not being able to see where
you are.

> **Fix.** `renderTableView` already has the row objects; add an `is-own-club` class on
> `Number(CTeam.teamId) === Number(userTeamId)` and reuse the rule that already exists at
> `dashboard.css:6323`. See [§9](#9-a-proposed-you-are-here-convention).

### P0-5 · The Back button is a 1138px gradient bar on every club page

Measured on First Team, Transfers, Finances and Player:

```
button.back-to-dashboard   display:flex  width:1138px  height:40px  text-align:center
parent                     .fm-panel.fm-club-hero  display:grid  width:1180px
```

It is a grid item with no `justify-self`, so it stretches the full width of the hero. Visually it
is a blue-to-green gradient slab reading "Back" in the middle, which reads as a broken primary
button, not as a back link. On mobile it is the first thing on the screen and it is 40px of
useless gradient.

> **Fix.** `justify-self: start` on `.back-to-dashboard`. Five characters.

---

## 4. P1 — serious, and the reason you cannot find your club

### P1-1 · Kup Srbije: 278 ties as a flat list, no bracket, no way to your own tie

Measured: **6,087px tall**, rounds newest-first, weeks 5–10 rendered as six "Not drawn yet."
placeholders before any real content.

The order is wrong for the reader. Your club is in Week 4. The manager must scroll past six empty
rounds to reach it, then scan 32 ties in a two-column list to find one name.

The own-tie highlight **does exist** — `components.css:788`:

```css
.fm-cup-tie-side--own {
    background: rgba(56, 189, 248, 0.16);
    border-radius: 3px;
    padding: 1px 4px;
    box-shadow: inset 2px 0 0 rgb(56, 189, 248);
    font-weight: 600;
}
```

— but it paints a 16%-alpha tint on the *name span only*. In a wall of 32 identical rows at ~10px
type it is effectively invisible. This is the commit `56133fec` that `kanban.md` records as
**"never seen running"**. It is now seen, and it does not work.

Three further problems on the same page:

- **The winner of a played tie is not marked.** `FK Železničar Zaječar City 1–3 ŽFK Teleoptik
  Prokuplje` — 3 is the winner and it looks identical to the 1.
- **Rounds are labelled by week, not by round.** "WEEK 4" tells you nothing about whether it is a
  round of 128 or a quarter-final. A cup without round names is not a cup.
- **No bracket at all.** This is the one screen in the game where a bracket is the natural form.

> **Fix, in priority order:**
> 1. **Your-club summary card at the top** — *"Kup Srbije · Round of 32 (Week 4) · SK Požarevac
>    0–0 OFK Omladinac · next: Round of 16, Week 5"* — with a jump link. One card, ~80px, answers
>    the only question anyone opens this page with.
> 2. **Collapse "Not drawn yet" rounds** into a single line: *"Rounds 5–10 not drawn yet."*
> 3. **Full-row own-tie treatment**, not a name tint (see §9).
> 4. **Name the rounds** (Round of 128 / 64 / 32 / 16 / QF / SF / Final), from the tie count.
> 5. **Bold and colour the winner** on played ties.
> 6. A real bracket view as a second tab, for the knockout phase.

### P1-2 · Country → Qualifying: fifteen identical mini-tables, 6,588px, no summary

Measured: **15 `fm-qualifying-table` elements, 6,588px page height**, grouped only by a small bold
"Tier 1" … "Tier 5" label. Each mini-table is a 5-column `POS | CLUB | PTS | GD | STATUS` block
rendered at ~460px inside a 1,138px card — **roughly half the available width is empty on every
one of them.**

Your club *is* marked — `dashboard.css:5618` gives `.is-current-club` a 3px blue inset bar — and it
is at the top-left, in the second mini-table of Tier 1. But:

- there is **no summary**, so you must find it;
- 14 other mini-tables look exactly the same and carry the same weight;
- the only visual difference between "Qualifies" (green pill) and "Candidate" (dim grey) is subtle,
  and the same green is used for your own row's left bar.

> **Fix.**
> 1. **A "Your club" summary card at the top**, same pattern as P1-1: one line per race your club is
>    in, with position, points and status. Tier 1 Masters Cup → **2nd of 2, 14 pts, Qualifies**.
> 2. **Group by tier into collapsed sections**, open the tier your club is in.
> 3. **Widen the tables** or put two races side by side. They currently waste 60% of their card.
> 4. Give "Qualifies" a solid left bar, not just a pill, so the winners read as a column.

### P1-3 · The country page hides 31 divisions behind a dropdown

`Country → General` shows `Cup · 278 ties drawn · Open ›`, `Playoffs · No playoff competition
exists · Open ›`, and then a **native `<select>` with 31 options** labelled "Leagues", pre-set to
`Superliga Srbije · Tier 1`.

`userManual.md` §6 says the country page *"Shows the divisions in the country"*. It does not. The
divisions exist only inside a closed dropdown, flat, ungrouped, unsearchable, with no indication
of which one your club is in. **This is why the country page "jedva se vidi."**

Also on this page:

- **Nothing says this is your country.** It says `SERBIA / Serbia` with a flag. No "your country"
  marker anywhere.
- The rows are laid out with ~1,000px of horizontal air between the label, the description and
  `Open ›`. Enormous empty runs on every row.
- **"Stand for election" / "Voting is open…" appears twice, identically**, once under Senior and
  once under U-21, with no visual distinction.
- **U-21 says "7 squad"** where the Senior says "25 squad".

> **Fix.**
> 1. **Replace the dropdown with a division list** — tier as a group heading, 31 rows, each with
>    club count and a link. Your club's division marked.
> 2. **Pin your own division to the top of that list** as *"Superliga Srbije — your club"*, ahead of
>    the other thirty.
> 3. **"Your country" badge** in the hero next to the flag.
> 4. Collapse the Senior/U-21 election blocks into one shared panel, or label them clearly.

### P1-4 · The transfer market is 74 stacked cards, 10,407px, with no filter and no sort

Measured: **10,407px**, 74 `fm-squad` cards stacked one under another, single column. The only
control on the whole page is a native `<select>` with **49 countries** (`My country` first — a good
default).

There is no filter by position, no filter by age, no filter by price, no sort, no search, no
pagination. For a transfer market in a 14,880-club world this is the single largest functional
gap in the application. `userManual.md` §4 advertises "browse listed players" and "filter the market
by country" — country only.

### P1-5 · The league schedule page is half empty and half clipped

At 1512×982 the page is a two-column grid: the left column contains one short KPI card and then
**~700px of empty stadium photograph**; the right column's round list is **clipped at the top**
(the first round's heading is cut in half by the panel's own top edge) and **clipped at the
bottom** (a tie row is sliced through the middle by the panel's bottom edge). There is no scroll
affordance.

Club names are truncated to `FK Morava Sre…`, `OFK Timok Čač…`, `RFK Radnički 1…` in the tie
*header* while the same names appear in full in the card body. Each fixture card is ~200px tall for
four data points.

### P1-6 · The dashboard ticker clips both ends of every message

```css
.fm-dashboard-ticker { overflow: hidden; text-overflow: clip; white-space: normal; animation: none }
```

Desktop, 1180px frame, 1178px content, with the `IMPORTANT UPDATES` label taking the first ~230px.
Observed output:

> `IMPORTANT UPDATES   etu is still draft-only (0/11 starters, 0/7 bench).  ✦ Urgent: Starting X…`

The beginning of the message is eaten. On mobile:

> `IMPORTANT UPDATES   1, week 6. Training is once a week and`

— cut mid-sentence, no ellipsis, no marquee, no way to read the rest. **The most urgent
information on the dashboard is unreadable on both platforms.**

### P1-7 · Collapsed accordions in the mobile menu render as empty 18px bars

Measured in `#mobileSidebar`:

```
accordion-content × 2   height: 18px   style.maxHeight: "" (never initialised)
```

`overrides.css:8` sets `max-height: 0; overflow: hidden;` but also `padding: 0 0 10px 20px`. The
padding survives `max-height: 0`, so each collapsed section renders as a **18px empty strip with a
visible border and background**, directly under "Club" and "Community". It looks like a rendering
fault.

Two related problems in the same panel:

- **The menu is 950px tall in a 932px viewport.** "Sign out" is below the fold.
- **No active state.** Nothing indicates which section you are in.

### P1-8 · Mobile top bar: 180px of empty header with no club and no clock

At 430px the header is a bell, a hamburger, and a 64px logo — and ~180px of nothing. The game
clock is `.desktop-only` and hidden. The club name is absent. `userManual.md` §12 says the clock is
shown "in the mobile menu"; it exists in the DOM (`#live-clock-mobile`, 62px tall) but sits below
the 950px menu.

The first screen a manager sees on a phone therefore contains: two buttons, a logo, and a clipped
ticker.

### P1-9 · Finances: the most alarming number on the page has no alarm

```
EUR -5,850,781.87  Budget          (white, same weight as everything else)
EUR  -9,880,599.87  Net to date    (white)
"The club is overdrawn."
"The club is comfortably in the black."      ← both bullets, at the same time
"WAGES VS INCOME  0.08×"  "Sustainable"       ← while spending more than it earns
```

A club that is €9.9m in the hole is told it is comfortably in the black and that its wages are
sustainable, in the same panel, in the same weight. Whether the logic is wrong is a backend
question; **the design question is that a negative money figure is rendered identically to a
positive one.** In a management game the budget is the number you check first.

Two more, same page:

- **The "Period" dropdown is an unstyled native `<select>`**: `background: rgb(255,255,255)`,
  `color: rgb(0,0,0)`, `height: 19px`, `appearance: auto`. A white box on a black page. The league
  season select and the country league select *are* styled (`.fm-season-select`); this one has no
  class at all.
- **The weekly ledger bars have no zero line.** A `EUR -9,880,599.87` balance is drawn as a bar
  extending rightward from the left edge, in blue, at the same visual weight as a positive income.
  The sign is carried entirely by colour.

---

## 5. Finding your club and your country — the full inventory

You asked specifically whether your club and your country are highlighted. Here is the honest
picture, page by page. **"—" means there is no marking at all.**

| Screen | Own club marked? | What the marking is | Verdict |
|---|---|---|---|
| **League Table** (standings) | **—** | `PLAYER` chip + manager email | **the primary screen has no marking** |
| **League Matches** (results) | **—** | rows are `match-row recent-match`, no class | no marking |
| **League Schedule** | **—** | none | no marking |
| **Top Scorers / Assists** | **—** | none | your own players are unmarked in the league's own top lists |
| Country → Clubs (310 clubs) | ✅ | 3px bar + tint + bold | **good** |
| Country → Qualifying (15 races) | ⚠️ | 3px bar only | correct, but no summary, so you must find it |
| Country → National qualifying group | ✅ | bar + tint + bold | good |
| Country → General (31 divisions) | **—** | hidden in a dropdown | no marking, no list |
| **Kup Srbije** | ⚠️ | 16% tint on the name span | **invisible in practice** |
| **World** (48 countries) | ⚠️ | green tint = `Active`, the same signal | you cannot tell *why* Serbia is green |
| Transfers → your desk | ✅ | it's a "My…" panel by construction | fine |
| Transfers → market | n/a | — | your club is not in the market list |
| Squad / Player | n/a | — | by construction |

**Three different treatments for the same fact.** `is-own-club` (bar + tint + bold),
`is-current-club` (bar only), `fm-cup-tie-side--own` (name tint). A manager learns the mark on one
screen and has to learn it again on the next. And the most important screen of all has none.

### Your country specifically

- **Country page:** the hero reads `SERBIA / Serbia` with a flag. There is no "your country"
  indicator, and there is no mention of your club anywhere on the page.
- **World page:** Serbia (rank 39) is tinted green — but that green is the `Active` state, the same
  tint any activated country would get. There is no second signal saying *"this is yours."*
- **National team panels** are the one place the app personalises well: *"Selector: You are the
  selector"*, `25 squad`, a `Manage` button. That is the standard the rest of the app should meet.

---

## 6. Lists, tables and dropdown menus

**Tables.** Five distinct table styles for what is conceptually the same thing:

| Class | Used on | Notes |
|---|---|---|
| `.fm-standings` | League Table | 12 columns, 5 of which (P W D L GD) are derivable |
| `.fm-squad` | Squad, Country Clubs, Transfers, World, World competitions | the workhorse |
| `.fm-player-stats` | Top scorers / assists | 3 columns, name and club concatenated with no separator |
| `.fm-league-table` | Country qualifying | header row repeated 15× |
| `.fm-cup-tie` | Kup Srbije | not a table at all — a flex list |

Padding, header weight and row height differ between them. `.fm-standings` has its own rules at
`dashboard.css:1820-1866` that diverge from `.fm-squad`. The result reads as five products.

**Dropdowns.** Five native `<select>` elements across the app:

| Where | Options | Styled? |
|---|---|---|
| League pages — Season | 1 | ✅ `.fm-season-select` |
| Country → General — Leagues | **31** | ✅ but flat and unsearchable |
| Transfers — Market country | **49** | ✅, `My country` first |
| Finances — Period | 3 | ❌ **unstyled: white background, black text, 19px tall** |
| Admin — Role | 7 | ✅ |

One unstyled control in a dark UI reads as a bug, and it is on the money page.

**The club sub-navigation is the other dropdown-shaped problem.** `buildClubActionsHtml` emits 15
buttons — First Team, Schedule, Club Profile, Stadium, Medical Center, Juniors, Loans, Tactics,
Tactic Editor, Staff, Finances, Transfers, Results, Training Setup, Training Reports. On desktop
they wrap into three ragged rows beside the page title; on mobile they will be fifteen stacked
full-width buttons, which is most of a screen before any content. This is a hierarchy of 15 items
with no grouping. **Club / Squad / Staff / Finance / Match** would be five groups.

---

## 7. Mobile — iPhone 14 Pro Max portrait (430×932 @3x)

The layout does not break catastrophically — nothing overflows the document horizontally on any
page I measured (`docScrollW` = 430 everywhere). The problems are density, clipping and priority.

### 7.1 What costs the most vertical space

| Element | Height | Could be |
|---|---|---|
| Header (bell + hamburger + logo) | ~180px | ~56px, with the club name |
| IMPORTANT UPDATES ticker | ~70px, clipped | ~44px, two lines, tappable |
| Dashboard KPI tiles, 4 × 1 column | ~600px | ~300px in a 2×2 |
| League tabs, 5 × stacked full-width | ~600px | ~56px, horizontally scrollable |
| League table row | ~200px | ~56px |
| Mobile menu total | 950px in a 932px viewport | ~600px |

The league table alone is 2,000px for ten clubs.

### 7.2 Horizontal tables scroll, but give no sign that they do

The scroller exists and is correct. What is missing is everything that makes it usable:

- no right-edge fade,
- no shadow on the scrollable edge,
- no sticky identity column,
- no column-count affordance (`9 of 12`),
- and the **most important column (`Pts`) is off-screen by default**.

On a touch device there is no scrollbar to discover. The result is that the manager sees a table
that appears to end at `GD` and assumes that is all of it.

### 7.3 Mobile-specific defects

- **The `LEAGUE VIEW / Superliga Srbije · Season 1` card overflows its own card** — the title runs
  past the right edge and "Season 1" is cut.
- **The "Season" label sits outside any card**, floating on the stadium background, above a
  full-width select.
- **The mobile menu's collapsed accordions render as 18px empty strips** (§P1-7).
- **Club names truncate on the cup page** (`RFK Jedinstvo Vrnjačka Ba…`, `ŽFK Podunavac Panč…`) and
  the `--own` tint is still only on the name.
- **The mobile cup page opens with two screens of "Not drawn yet"** before your club's round.

### 7.4 What is right on mobile

- The Admin Jobs table adapts correctly and completely (§2.3).
- Tap targets on the cup ties and the league rows are comfortably above 44px — I measured no
  sub-44px interactive elements.
- The mobile drawer menu itself is a reasonable pattern; it is the contents and the collapsed
  accordions that are wrong.
- No page produces horizontal document scroll.

---

## 8. Cross-cutting

**8.1 Information priority is inverted on two pages.**
The **First Team** page — called "First Team", whose entire purpose is the squad — puts a
five-card milestone board (with an "HONOURS / No medals yet" card) between the header and the
squad, pushing the squad ~900px down. The **Country → General** page puts an empty-state playoff
row above the only thing that matters (your division).

**8.2 The `iconCount` milestone cards render as emoji walls.** `pages-renderers.js:1050` repeats one
badge per goal or assist. Top scorer = eight ⚽ badges wrapping onto two lines; top assist = three 🅰️
badges on one line and three more wrapping to the next. Mixed apparent sizes, inconsistent colours,
no meaning beyond "count". A number plus a 4-segment bar reads faster and occupies a third of the
space.

**8.3 Redundancy.** The Medical Center appears three times on the First Team page: as a sub-nav
button, as a full panel with its own KPIs, and as a route. The milestone board appears on the
Dashboard *and* on First Team *and* on the League Table page — the same top-scorer card three times.

**8.4 Contradictions a manager can see.** The board panel listing "The club is overdrawn" and "The
club is comfortably in the black" as simultaneous bullets (§P1-9). The season is "Week 6 · Day 1
(International)" in the header while the Next Match card reads "Week 4 · Day 7 · 16:00".

**8.5 Empty states dressed as content.** "Week 6 · 0 lines / Nothing settled in Week 6 yet" with an
"EUR 0 in / EUR 0 out / EUR 0 net" legend above it. "No medals yet" as a full-width card. When
there is nothing, the panel should collapse to one line.

**8.6 World page: a ranking of 48 identical numbers.** All 48 countries read `RATING 1500`.
Serbia is "39" on that basis. The column is decoration; the ranking it implies is meaningless. No
flags, no confederation, no sort, no "Active only" filter. Adding an Active filter would take the
48 rows to 1.

**8.7 Numbers.** Money is shown to the cent everywhere (`EUR 29,879,559.81`) on club-level finance.
Club ratings mix `1691.25` (two decimals) on the country Clubs tab with `1556` (integer) in the
league table. Pick one convention per kind of number.

---

## 9. A proposed "you are here" convention

This is the change I would make first, and it is small.

**One class, one token, one meaning.** There is already a correct implementation at
`dashboard.css:6323`:

```css
.fm-clubs-ranking-table tr.is-own-club {
    box-shadow: inset 3px 0 0 #4a9eff;
    background: rgba(74, 158, 255, 0.07);
}
.fm-clubs-ranking-table tr.is-own-club td { color: #cfe4ff; font-weight: 600; }
```

Generalise it to `.fm-panel tbody tr.is-own` so it applies to every table, and give the manager's
country the same treatment. Three rules, then used everywhere:

1. **Row-level** — `is-own` on the `<tr>`: 4px left bar, a tint that is visible (0.10–0.12, not
   0.07), and bold. On the cup page this goes on the **whole tie row**, not the name span.
2. **Label-level** — a small `YOUR CLUB` chip beside the name. The existing `PLAYER` chip says
   *a human manages this club*; it does not say *this is you*. Say it.
3. **Page-level** — a summary card at the top of any long list that contains your club: cup,
   qualifying, transfers, schedule. One line, with a jump link.

```
Kup Srbije                                    ← page title
┌──────────────────────────────────────────────────────────────┐
│ YOUR CLUB                                                      │
│ OFK Omladinac · Round of 32 · Week 4                           │
│ SK Požarevac  0 – 0  OFK Omladinac      → Jump to your ties   │
└──────────────────────────────────────────────────────────────┘
```

Then apply the same three rules to your **country**: a `YOUR COUNTRY` badge in the hero, a pinned
"your division" at the top of the 31-division list, and the World page's Serbia row carrying a
second, distinct signal from the `Active` tint.

---

## 10. Suggested order of work

Nothing here needs a backend change except where marked. Each item is small enough to verify in one
browser session, which is the standard this repository holds itself to.

**Do first — they are small and one of them is a functional break.**

| # | Change | Size |
|---|---|---|
| 1 | Move `.logo-corner` into the top bar flow; kill the 1100–1303px overflow (§P0-1) | ~10 lines |
| 2 | `min-width: 0` on `.fm-player-grid > *` (§P0-2) | 1 line |
| 3 | `justify-self: start` on `.back-to-dashboard` (§P0-5) | 1 line |
| 4 | Ticker: clamp to 2 lines with a fade, make it tappable for the full message (§P1-6) | ~15 lines |
| 5 | Collapsed accordion: `padding-bottom: 0` when closed, or `max-height: 0; padding: 0` (§P1-7) | 1 line |
| 6 | Style the Finances `Period` select to match `.fm-season-select` (§P1-9) | ~8 lines |
| 7 | `is-own` row class in `standingsRowsHtml` — the league table finally marks you (§P0-4) | ~10 lines |
| 8 | Colour negative money (§P1-9) | ~5 lines |

**Do next — this is your actual complaint.**

| # | Change |
|---|---|
| 9 | "Your club" summary card + jump on **Kup Srbije**, and move it above the "not drawn yet" rounds |
| 10 | Round names instead of week numbers; bold the winner on played ties |
| 11 | Full-row own-tie marking instead of a name tint |
| 12 | "Your club" summary on **Country → Qualifying**; group the 15 races by tier, collapsed |
| 13 | Replace the 31-option country dropdown with a real division list, your division pinned |
| 14 | "Your country" badge in the country hero and a distinct mark for your row on World |

**Then — mobile.**

| # | Change |
|---|---|
| 15 | Mobile header: club name + clock, down from ~180px to ~56px |
| 16 | League tabs as a horizontal strip; dashboard KPIs as 2×2 |
| 17 | League table on mobile: drop `GF`/`GA`, sticky `#`, scroll-fade affordance |
| 18 | Hide the `AI` chip and the manager email inside table cells |
| 19 | Group the 15 club sub-nav buttons into ~5 |

**Then — the long tail.**

| # | Change |
|---|---|
| 20 | Transfer market: filters (position / age / price), sort, pagination — replace the 74-card stack |
| 21 | League Schedule: rebalance the two columns, fix the clipped round list |
| 22 | First Team: move the milestone board below the squad, or to the Dashboard only |
| 23 | `iconCount` → number + bar in the milestone cards |
| 24 | League Results: group by round with a header row, drop the repeated `Season · Week · Day · time` |
| 25 | Top Scorers: separate the player name and the club into two columns, mark your own players |
| 26 | World: "Active only" filter, flags, and hide a rating column where every value is identical |
| 27 | Resolve the board-copy contradictions, or label the two bullets with the rule that produced them |
| 28 | Money to whole euros; one decimal convention per kind of number |

---

## Appendix — how this was produced

Every measurement in this document was taken in the running application with `agent-browser`:
viewport screenshots at each size, plus scripted DOM probes for table geometry, overflow
offenders, computed styles and element rectangles. No finding here is inferred from reading the
source alone — where a CSS rule and the rendered result disagree (§P0-2, the missing
`min-width: 0`), the rendered result is what is reported.

Screenshots and probe output were kept outside the repository. If any of these findings is to be
fixed, the verification standard this repository already uses applies: **open it in the browser and
look at it.**