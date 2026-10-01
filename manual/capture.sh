#!/usr/bin/env bash
# Capture TIFO UI Football Manager manual screenshots.
# Repeatable: after any fix, re-run and the images regenerate against the current build.
#   ./capture.sh desktop   # 1440x900
#   ./capture.sh mobile    # 390x844
# Requires the app on :8080 and Postgres on :5432.

set -uo pipefail
MODE="${1:-desktop}"
BASE="http://localhost:8080"
HERE="$(cd "$(dirname "$0")" && pwd)"

if [ "$MODE" = "mobile" ]; then W=390; H=844; SUFFIX="-m"; else W=1440; H=900; SUFFIX=""; fi
OUT="$HERE/$MODE"
MANIFEST="$HERE/manifest-$MODE.txt"
mkdir -p "$OUT"; : > "$MANIFEST"
ab() { agent-browser "$@" >/dev/null 2>&1; }

echo "==> ${W}x${H}"
ab set viewport "$W" "$H"

ab open "$BASE/login.html"; ab wait 1500
ab screenshot "$OUT/01-login$SUFFIX.png"
ab find text "Enter Tifo" click; ab wait 4000
ab screenshot "$OUT/02-home-modes$SUFFIX.png"
ab find text "TIFO UI MANAGER" click; ab wait 5000
ab screenshot "$OUT/03-dashboard$SUFFIX.png"

# The dashboard is the default view, not a loadPage target: loadPage('dashboard')
# falls through to "Page not found". 03 above is the dashboard.
PAGES="
05-squad:firstTeam
06-club-profile:profile
07-league-table:leagueTable
08-fixtures:leagueSchedule
09-results:results
10-tactics-editor:tacticEditor
11-formations:formations
12-training-setup:training
13-training-reports:trainingReports
14-academy:juniors
15-medical:medicalCenter
16-transfers:transfers
17-finances:finances
18-staff:staff
19-stadium:stadium
20-world:world
21-country:country
22-national-team:nationalTeam
23-u21:u21Team
24-cup:cup
25-top-scorers:topScorers
26-community:chat
27-admin:admin
28-user-profile:userProfile
29-player-stats:playerStats
"
for entry in $PAGES; do
  slug="${entry%%:*}"; page="${entry##*:}"
  ab eval "window.loadPage('$page')"; ab wait 2600
  ab screenshot "$OUT/${slug}$SUFFIX.png"
  info=$(agent-browser eval "(()=>{const m=document.getElementById('main-content');const t=(m?.innerText||'').trim();return JSON.stringify({len:t.length,head:t.slice(0,70).replace(/\s+/g,' '),bad:/API Error|Page not found/.test(t)});})()" 2>/dev/null | tail -1)
  printf '%s\t%s\t%s\n' "$slug" "$page" "$info" >> "$MANIFEST"
  echo "    $slug"
done

# The corner logo must return to the dashboard from any page.
echo "==> logo return"
ab eval "window.loadPage('transfers')"; ab wait 2000
LOGOPOS=$(agent-browser eval "(()=>{const i=document.querySelector('.logo-corner img,.mobile-logo-wrapper img');if(!i)return 'MISSING';const r=i.getBoundingClientRect();const cs=getComputedStyle(i.parentElement);return JSON.stringify({top:Math.round(r.top),left:Math.round(r.left),cssTop:cs.top,cssLeft:cs.left,cssRight:cs.right});})()" 2>/dev/null | tail -1)
printf 'logo-position\t%s\n' "$LOGOPOS" >> "$MANIFEST"
ab screenshot "$OUT/30-logo-corner$SUFFIX.png"
ab click ".logo-corner img"; ab wait 3000
AFTER=$(agent-browser eval "(()=>{const t=(document.getElementById('main-content')?.innerText||'');return JSON.stringify({backOnDashboard:/OFK Omladinac/.test(t)&&!/API Error/.test(t)});})()" 2>/dev/null | tail -1)
printf 'logo-returns-dashboard\t%s\n' "$AFTER" >> "$MANIFEST"
ab screenshot "$OUT/31-logo-returns-dashboard$SUFFIX.png"

echo "==> standalone viewers"
ab open "$BASE/zox-match-preview.html"; ab wait 3500
ab screenshot "$OUT/32-match-preview$SUFFIX.png"
ab open "$BASE/demo/service/ui/proposal/index.html"; ab wait 5000
ab screenshot "$OUT/33-match-viewer-2d$SUFFIX.png"
ab open "$BASE/realisticDemo.html"; ab wait 4000
ab screenshot "$OUT/34-match-viewer-3d$SUFFIX.png"

echo "==> done: $(ls "$OUT" | wc -l) images, manifest $MANIFEST"
