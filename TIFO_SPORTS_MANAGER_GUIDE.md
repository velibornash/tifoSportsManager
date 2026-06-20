# TIFO Sports Manager — User Guide

Welcome to TIFO Sports Manager! This guide gives you a quick overview of all four game modes, how to start playing, and what each mode supports.

---

## Quick Start

1. Open `http://localhost:8080`
2. Log in with `velibor@example.com` / `A12345!` (or register a new account)
3. On the home page (`/home.html`) you'll see four game-mode cards
4. Pick the sport you want to play

> **Save note:** The main football SPA, Basketball, and American Football all save your progress to the database. The TIFO Text mode is in-memory — progress is lost when you close the tab.

---

## ⚽ Football (UI Manager) — `/dashboard.html`

The flagship mode. A full Single Page Application with modern graphics, real-time tactics, training, youth academy, transfers, and the most advanced match engine.

### What you can do
- **Manage your CTeam** (First Team, Juniors, Staff, Finances, Medical Center)
- **Set formations & tactics** with the visual Tactics and Tactic Editor (drag & drop lineup, movement rules, set pieces)
- **Train CPlayers** — weekly training setup with general, positional, and specialized sessions + training reports with charts
- **Play matches** using the realistic tick-based engine (1080 ticks, offsides, set pieces, fatigue, injuries, cards, VAR)
- **Watch replays** — timeline with CPlayer positions on the pitch
- **Follow the league** — full 5-tier Serbian pyramid, standings, fixtures, cup, international matches, friendlies
- **Track stats** — top scorers, assists, CTeam analytics
- **Use admin tools** — DB initialize/reset, approve/reject registration requests (Community section)
- **Chat** with other managers via WebSocket

### How to start
1. Click **TIFO UI MANAGER** on the home page
2. Your dashboard shows upcoming matches, standings, and recent results
3. Navigate via the top ribbon (Club, Training, League, Country, Community) or the sidebar
4. Click any fixture to play it

> **Note:** There's also a **v2 match engine** in development at `/newLogic/index.html` with its own pure-Java simulation.

---

## 🏀 Basketball Manager — `/basketballmanager/dashboard.html`

A basketball club simulation with a 31-league Serbian pyramid (310 teams). Your CTeam is **KK Omladinac** in the Super League.

### What you can do
- **View your roster** — CPlayers sorted by any stat column (click headers to sort)
- **Player profiles** — skills, season stats, career history
- **League table** — W-L, points for/against, point diff, form badges
- **League leaders** — top scorers, rebounders, assistants
- **Play matches** — click Play Now on the next fixture, or Play All Round to simulate all remaining matches in the round
- **Match viewer** — live scoreboard with running clock, quarter-by-quarter scoring, play-by-play events, sortable CPlayer stats with CTeam totals
- **Replay** — click any played match to see full details without re-simulating
- **Start/Reset season** — Start Season initializes all leagues, Reset Season clears all matches and stats

### How to start
1. Click **BASKETBALL** on the home page
2. If first time, click **🏀 Start Season** on the dashboard
3. Click **▶ Play Now** on the next fixture

> **Planned:** Promotions/relegations, training, transfers, playoffs.

---

## 🏈 American Football Manager — `/americanfootballmanager/dashboard.html`

An American football simulation with the same 31-league pyramid. Your CTeam is **AF Omladinac**.

### What you can do
- **View your roster** — 53 CPlayers grouped by CSPosition (QB, RB, WR, TE, OL, DE, DT, LB, CB, S, K, P)
- **Player profiles** — skill bars, season stats
- **League table** — W-L, points for/against, point diff, form badges
- **League leaders** — passing, rushing, receiving yards, tackles, interceptions, sacks
- **Play matches** — Play Now or Play All Round
- **Match viewer** — live sequential playback (events appear one by one, like basketball), sortable CPlayer stats, quarter scores
- **Replay** — click any played match from Recent Matches
- **Start/Reset season** — buttons on the dashboard

### How to start
1. Click **AMERICAN FOOTBALL** on the home page
2. If first time, click **🏈 Start Season** on the dashboard
3. Click **▶ Play Now** on the next fixture

> **Note:** The match engine runs a drive-based simulation (4 quarters + OT) with runs, passes, FGs, and TDs. The engine currently generates many plays per drive (yardage totals are higher than realistic). Stats properly reset on season reset.

> **Planned:** Depth chart management, trading, playoffs/Super Bowl, promotion/relegation.

---

## 📟 TIFO Text Manager — `/tifo.html`

A retro text-based football manager inspired by teletext. No graphics — everything is shown as text tables and reports.

### Important
- **In-memory only** — your progress is lost when you close the browser tab
- This is a deliberate design choice (no-cheat, play-a-season-in-one-sitting mode)

### What you can do
- **Manage your squad** — view CPlayer skills, form, fitness, injury/suspension/warning icons
- **Set tactics** — 9 formations, 4 play styles, starting XI + 7 subs
- **Manager's office** — win rate, league CSPosition, budget, job security bar
- **Play the season** — 20-CTeam league, 38 rounds, double round-robin
- **Match details** — tick-by-tick events (goals, cards, subs, injuries, VAR), 4-tab match view (Lineups, Events, Stats, Report)
- **Transfer market** — CPlayer listings, bidding, accept/reject offers
- **Inbox** — 17+ message types, entity-linked names, filtering
- **International football** — international matches, windows, standings

### How to start
1. Click **TIFO TEXT BASED** on the home page
2. Click **▶ START GAME** on the dashboard
3. Navigate with the sidebar (Squad, Tactics, Transfers, Inbox...)
4. Click **▶ NEXT ROUND** at the bottom of the sidebar to advance

> **Planned:** Training system, calendar, scouting, staff, press conferences, youth academy, cup competitions.

---

## Feature Comparison

| Feature | Football (SPA) | Basketball | American Football | TIFO Text |
|---|---|---|---|---|
| Roster management | ✅ Full | ✅ Basic | ✅ Basic | ✅ Full |
| Tactics/Formations | ✅ Visual editor | ❌ | ❌ | ✅ 9 formations |
| Training | ✅ Full system | ❌ | ❌ | ❌ |
| Transfers | ✅ Full | ❌ | ❌ | ✅ Bidding system |
| Youth academy | ✅ Yes | ❌ | ❌ | ❌ |
| Match engine | ✅ Tick-based | ✅ 100 possessions | ✅ Drive-based | ✅ Tick-by-tick |
| Live match viewer | ✅ Timeline + pitch | ✅ Sequential playback | ✅ Sequential playback | ✅ Text report |
| League pyramid | ✅ 5 tiers | ✅ 31 leagues | ✅ 31 leagues | ❌ 1 tier |
| Promotion/Relegation | ❌ Planned | ❌ Planned | ❌ Planned | ❌ |
| Save to DB | ✅ Yes | ✅ Yes | ✅ Yes | ❌ No (in-memory) |
| Admin tools | ✅ DB + Registration | ❌ | ❌ | ❌ |

---

## Login Credentials

- **Email:** `velibor@example.com`
- **Password:** `A12345!`
- **Home page:** `http://localhost:8080/home.html`
