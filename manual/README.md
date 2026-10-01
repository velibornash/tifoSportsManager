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