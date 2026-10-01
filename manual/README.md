# Manual — how it is produced and how to rebuild it

The user-facing manual is **`TIFO-UI-Football-Manager-Manual.pdf`** in the repository root.
It is a generated artifact, not a hand-maintained file: every screenshot is captured by a script
and the document is assembled from those images.

```
manual/
  capture.sh        screenshots the running game
  build_manual.py   assembles index.html from the images
  img/              the screenshots (gitignored — regenerate with capture.sh)
  index.html        built document, self-contained (images inlined as base64)
  manifest-*.txt    what each page rendered, and which pages failed
```

## Rebuilding

```bash
# 1. the app must be running, with Postgres up
./run-app.sh

# 2. capture (from the repo root; writes PNGs next to the script)
cd manual
./capture.sh desktop      # 1440x900
./capture.sh mobile       # 390x844

# 3. the PNGs need converting to JPEG before the document will build
python3 - <<'PY'
from PIL import Image; import glob, os
for mode in ("desktop", "mobile"):
    os.makedirs(f"img", exist_ok=True)
    for f in sorted(glob.glob(f"{mode}/*.png")):
        name = os.path.basename(f)[:-4]
        pre = "m-" if mode == "mobile" else ""
        Image.open(f).convert("RGB").save(
            f"img/{pre}{name}.jpg", "JPEG", quality=82, optimize=True, progressive=True)
PY

# 4. assemble and print
python3 build_manual.py
"/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" \
  --headless --disable-gpu --no-sandbox --no-pdf-header-footer \
  --print-to-pdf="$PWD/../TIFO-UI-Football-Manager-Manual.pdf" "file://$PWD/index.html"
```

## Two things worth knowing before you re-run it

**The capture script detects broken pages.** It does not just photograph the viewport — after each
page it reads the rendered text and records the length plus whether the page fell back to
`"API Error"` or `"Page not found"`. That is how the league table crash was found: the script
reported `len=40` for a page that should have had five figures. Check `manifest-desktop.txt`
before trusting a capture; any row with `bad:true` or a tiny `len` is a page that did not load.

**The running app serves static files from `target/classes`, not from `src/main/resources`.** If you
fix a `.js` or `.css` file while the app is running, the change is **not** live. Copy it across or
restart the app:

```bash
cp src/main/resources/static/js/<file>.js target/classes/static/js/<file>.js
```

The browser also caches these aggressively. `agent-browser close --all` before a capture run is the
cheapest way to guarantee a clean cache — a stale cached bundle will make a fix look like it did not
work.

---

## Notes for the next iteration

Recorded 2026-10-01, after the first pass. **Owner instructions — these are the requested changes,
not findings.**

1. **Screenshot the first squad separately, so the players are visible.** On the club page the squad
   sits in the *lower* part of the page, and a plain viewport screenshot only catches the top.
2. **Every numbered section must start on a new page.**
3. **An image must not break across two pages.**
4. **Screenshot the top scorers once they are populated.**

### What was learned doing them, so the next pass does not rediscover it

**1 — the squad shot is already solved and sitting in temp.** It needs scroll-then-crop, not an
element screenshot, the same way the visual movement editor did. The working crop is:

```
agent-browser eval  // find the section holding >0 .league-player-card, scroll it to ~70px from top
                   // window.scrollBy(0, r.top-70); record getBoundingClientRect()
agent-browser screenshot _squad-vp.png      # viewport, then crop in PIL to that rect
```

Saved as `desktop-jpg/05b-first-team-squad.jpg` (1196×712) and not yet copied into `manual/img/`.
It shows all 13 players with name, position, age, ability, potential, condition, morale and the
apps/goals/assists columns.

**Trap:** the First Team page renders **empty on a first load** — "0 Players", "No registered
players found". The API returns all 13 and a re-render is correct. It is stale first-load state, not
a data problem, so **reload before capturing** or the shot will show an empty squad. Worth checking
whether a real user can hit that empty first paint, because it looks like a broken page.

**2 and 3 — the CSS changes needed.** In `build_manual.py`:

- `h2 { break-before: page; }` for the numbered sections.
- `figure { break-inside: avoid; }` plus an image height cap, because a figure taller than the page
  content height *will* split no matter what `break-inside` says. The visual movement editor shot
  is 780×1262 and is the one that forces this — either cap it and let it scale down, or give tall
  figures their own page.
- Note: `loading="lazy"` had to be removed from the `<img>` tags for the same underlying reason —
  Chrome's `--print-to-pdf` does not reliably trigger lazy loading, so images can be missing from
  the PDF while looking fine in a browser.

**4 — top scorers cannot be filled by simulating more matches.** This is the one to read before
planning. The endpoint does not read player statistics; it reads a `goal_event` table:

```
StatsController:93  goalEventRepository
                    .findByMatchCompetitionIdAndMatchSeasonYearAndScoredTrue(...)
```

and **`goal_event` has 0 rows** — as do all 23 `*_event` tables. The goals themselves are not lost:
the `match` rows carry **412 goals across 142 matches**, and `match_player_stats` has 3,410 rows
with 306 players on non-zero goals. So the top-scorer and top-assist pages read an empty table while
the data sits elsewhere. Filling item 4 is therefore blocked on a backend decision about where goals
should be read from, **not** on generating more fixtures. Flagged, not actioned.

For reference, `POST /simulation/current-round/simulate-all` does work and played 5 fixtures, but
that changed the league table the manual already photographed — so re-shooting after it is expected.