# SESSION HANDOFF — 2026-10-11

Read this first. It is the state of play, what is proven, what is **not** proven, and what to do next.
Written so a fresh session can continue without re-deriving any of it.

---

## 1. Environment

| | |
|---|---|
| Java | `export JAVA_HOME=/Users/velja/Library/Java/JavaVirtualMachines/corretto-21.0.12/Contents/Home` |
| Build | `export PATH="$JAVA_HOME/bin:/usr/local/bin:$PATH"` then `mvn clean package -DskipTests` |
| Run | `./run-app.sh -DskipTests` — **never** `mvn spring-boot:run` directly |
| Local DB | PostgreSQL `sokker_db`, `postgres`/`stojke`. psql is **not** on PATH by default: `export PATH="/usr/local/opt/libpq/bin:$PATH"` |
| Oracle | `http://158.180.22.180:8080/`, same credentials. Runs the **prod** profile. |
| Login | `velibor@example.com` / `A12345!` |

**The agent shell does not inherit `JAVA_HOME`.** Every build command must re-export it or it fails with
`mvn: command not found`.

---

## 2. The one thing to understand before touching anything

**There are two clocks, and they are not interchangeable.**

| | Local (`dev`) | Oracle (`prod`) |
|---|---|---|
| `game.clock.scheduler-enabled` | `false` | **`true`** |

On Oracle the game clock **auto-advances one game-hour per real hour, unattended**. Nothing has to be
clicked. On the Mac it only moves when you press Advance.

Everything scheduled — fixture kickoffs, job triggers, transfer deadlines — is in **game hours**. The
header now shows the game clock (`0e9b8af`), not wall time.

---

## 3. Committed and verified this session

| Commit | What | Verified how |
|---|---|---|
| `867754b` | Results + Recent Matches sort on `MatchDTO` keys | Live in app, both pages |
| `0e9b8af` | Header clock = game clock | Live in app, `09:00` |

### `867754b` — the sort that was silently doing nothing

`/teams/{id}/matches` returns `MatchDTO`; `/teams/{id}/schedule` returns a `Map`. **They have different
field names**, and both were sorted with the schedule names:

| Endpoint | Season / Week / Day | Round |
|---|---|---|
| `/schedule` (Map) | `seasonYear`, `week`, `day` | `round` |
| `/matches` (MatchDTO) | `seasonNumber`, `weekNumber`, `dayNumber` | **none** |

Every key resolved to `undefined`, every row tied, and the comparator fell through to `matchDate`.
Nothing threw, nothing logged, the list just came out in the wrong order. **This is the "green status is
not evidence" shape — it looked fine and was inert.**

Root cause of the *wrong* order underneath: `match_date` is unreliable. A played Day 1 Cup tie carries
`2026-10-15` — four days in the future. See `SELECT id, match_date FROM "match" WHERE id IN (1,481);`

### `0e9b8af` — header clock

Real Belgrade time removed, along with `/api/server-time` (existed only to correct it for drift).

---

## 4. Corrections to things previously reported here

**Do not trust these if you find them written down elsewhere.**

- **I claimed `?allCompetitions=true` fixed the missing international match. It did nothing.**
  `/teams/{id}/matches` has no such parameter and no competition filter. Only `/schedule` has it. The
  flag was removed from the `/matches` calls; it remains on `/schedule`, where it works.
- **`window.loadPage is not defined` was not a build error.** The real fault was a template literal in
  `pages-renderers.js` that I had broken myself — `${scheduleFilterHtml(...)}` left outside the closing
  backtick, which killed the whole module graph. `node --check` passed on it.
- **Browser automation was unreliable and I drew conclusions from it anyway.** `agent-browser eval`
  returns garbage under session expiry. Check `location.href` before believing a reading.

---

## 5. Open, unstarted

1. **Job verification never happened.** No run to Week 2 Day 1 was ever completed. Every verdict in
   `PLAN_TEST_JOBOVA.md` beyond "the clock advances" is **read from config, not observed**. Treat that
   file as untested.
2. **Fixture kickoff hour disagrees with the job that plays it.** The Champions Cup fixture says `18:00`;
   `matchday-international-club-cup` triggers from `20:00`; Day 1's real international kickoff is `20:45`.
   A manager told his tie kicks off at 18:00 sees nothing happen at 18:00. **This is the next thing to
   fix.**
3. **`Rd null` on cup fixtures** — `pages-renderers.js:165-170` guards with
   `Number.isFinite(Number(x))`, and `Number(null) === 0` is finite, so `null` slips through. Diagnose
   the actual response value before changing it.
4. **Results prints `Date: 2026-10-15`** for an already-played match — the same unreliable
   `match_date`. The other two surfaces show `Season 1 · Day 1`; this one should match.
5. **Tactical Editor** — the owner's Sokker-parity request: save named tactics, load and edit, "New
   tactic", "Based on" (copies everything under a new name, user then edits), and three selectable
   tactic slots switchable in Orders by condition/minute. **Not started.**
6. **Match report timeline** — overlapping text; events render as words (`yellow_card`, `var`, `goal`)
   and should be icons.
7. **Post-match OVR too generous** — 10.0 awarded for a couple of fouls; 10.0 should be reserved for
   ~3–4 goals or assists.

---

## 6. How to resume the job run (the original ask)

The owner wants: advance one day at a time to **Week 2 Day 1**, and for **every job** state whether it
did what it was supposed to.

Method that works — do not use the dashboard buttons, they are unreliable under automation:

```
agent-browser open "http://localhost:8080/dashboard.html"
agent-browser eval "(async()=>{const r=await window.advanceDayTest();
  return JSON.stringify({day:r?.day,label:r?.dayLabel,hour:r?.hour,
    ran:r?.jobs?.ran,skipped:r?.jobs?.skipped,failed:r?.jobs?.failed,
    jobs:(r?.jobs?.jobs||[]).map(j=>j.key+':'+j.status)});})()"
```

Then **look at the app** for each job's effect. A job reporting `DONE` is not evidence — check the
training report, the fixture `played` flag, the league table.

On Oracle the clock moves by itself, so a local run is the only way to step day by day.