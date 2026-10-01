#!/usr/bin/env python3
"""Build the TIFO UI Football Manager manual as a styled HTML document.

Screenshots live in ./desktop-jpg and ./mobile-jpg (produced by capture.sh).
Desktop is the primary illustration; mobile is shown for the key screens.
"""
import base64, html, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
OUT_HTML = os.path.join(HERE, "index.html")


def img(name, mobile=False):
    """Return an <img> tag, or '' if the screenshot is missing."""
    folder = "img"
    fname = ("m-" + name + ".jpg") if mobile else (name + ".jpg")
    path = os.path.join(HERE, folder, fname)
    if not os.path.exists(path):
        return ""
    with open(path, "rb") as fh:
        b64 = base64.b64encode(fh.read()).decode()
    return (f'<img class="shot" src="data:image/jpeg;base64,{b64}" '
            f'alt="{html.escape(name)}">')


def shot(slug, caption, mobile=False):
    tag = img(slug, mobile)
    if not tag:
        return ""
    mob = ""
    if mobile:
        m = img(slug, True)
        if m:
            mob = f'<figure class="mobile">{m}<figcaption>Same screen on a phone ({390}&times;844).</figcaption></figure>'
    return (f'<figure>{tag}<figcaption>{caption}</figcaption></figure>{mob}')


def build():
    p = []
    a = p.append

    a("""<!doctype html><html lang="en"><head><meta charset="utf-8">
<title>TIFO Sports Manager &mdash; UI Football Manager Manual</title>
<style>
:root{--bg:#0b1220;--panel:#121c31;--ink:#e8eefc;--dim:#9fb0d0;--line:#24334f;
      --accent:#3ddc97;--warn:#f0a830;--bad:#e5484d}
*{box-sizing:border-box}
body{margin:0;background:#f4f6fb;color:#1a2233;
     font:16px/1.65 -apple-system,BlinkMacSystemFont,"Segoe UI",Inter,Roboto,sans-serif}
.page{max-width:960px;margin:0 auto;background:#fff;padding:56px 60px 80px;
      box-shadow:0 0 60px rgba(0,0,0,.12)}
h1{font-size:34px;line-height:1.2;margin:0 0 6px}
h2{font-size:23px;margin:44px 0 10px;padding-bottom:8px;border-bottom:2px solid #e6ebf5}
h3{font-size:17px;margin:26px 0 8px;color:#2b3550}
p{margin:0 0 13px}
a{color:#1f6feb}
.cover{border-bottom:4px solid #0b1220;padding-bottom:26px;margin-bottom:12px}
.cover .kicker{color:#1f6feb;font-weight:700;letter-spacing:.09em;font-size:12px;
               text-transform:uppercase;margin-bottom:10px}
.cover .sub{color:#5b6884;font-size:17px;margin:0}
.meta{color:#6b7896;font-size:14px;margin-top:18px}
.meta b{color:#1a2233}
figure{margin:20px 0 26px}
figure img.shot{width:100%;border:1px solid #d8dfea;border-radius:8px;display:block;
                box-shadow:0 2px 10px rgba(20,35,70,.09)}
figcaption{font-size:13.5px;color:#5b6884;margin-top:8px;padding-left:2px}
figure.mobile img{width:290px;display:block;margin:0 auto}
figure.mobile figcaption{text-align:center}
table{width:100%;border-collapse:collapse;margin:16px 0 24px;font-size:14.5px}
th,td{text-align:left;padding:9px 11px;border-bottom:1px solid #e6ebf5;vertical-align:top}
th{background:#f2f5fa;font-weight:700;font-size:13px;text-transform:uppercase;
   letter-spacing:.05em;color:#41506e}
tbody tr:nth-child(even){background:#fafbfd}
code,kbd{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:13.5px;
         background:#eef2f8;padding:1px 6px;border-radius:4px;color:#0b3d91}
.box{border-left:4px solid #1f6feb;background:#f5f8ff;padding:14px 18px;margin:20px 0;
     border-radius:0 6px 6px 0}
.box.good{border-color:#12a370;background:#f2fbf6}
.box.warn{border-color:#e0912f;background:#fff8ee}
.box.bad{border-color:#d13438;background:#fff5f5}
.box p:last-child{margin-bottom:0}
.box .lbl{display:block;font-weight:700;font-size:12px;letter-spacing:.07em;
          text-transform:uppercase;margin-bottom:5px;color:#41506e}
ul,ol{margin:0 0 14px;padding-left:22px}
li{margin-bottom:6px}
.toc{background:#f7f9fd;border:1px solid #e2e8f4;border-radius:8px;padding:18px 26px;
     margin:24px 0 8px}
.toc ol{margin:0;padding-left:20px;columns:2;column-gap:34px}
.toc li{margin-bottom:3px;font-size:14.5px}
.pill{display:inline-block;background:#e7f7ef;color:#0d7a4f;border:1px solid #b9e6d0;
      border-radius:20px;padding:2px 11px;font-size:12.5px;font-weight:700;margin-right:6px}
.pill.no{background:#fdecec;color:#a52121;border-color:#f5c2c2}
.pill.part{background:#fff4e2;color:#96601a;border-color:#f3ddb5}
hr{border:0;border-top:1px solid #e6ebf5;margin:40px 0}
.small{font-size:13.5px;color:#6b7896}
@media print{body{background:#fff}.page{box-shadow:none;max-width:none;padding:0}
  h2{page-break-after:avoid}figure,table,.box{page-break-inside:avoid}}
</style></head><body><div class="page">""")

    # ---------------------------------------------------------------- cover
    a('<div class="cover">')
    a('<div class="kicker">TIFO Sports Manager</div>')
    a('<h1>UI Football Manager &mdash; Manual</h1>')
    a('<p class="sub">A club-management game with a real spatial match engine behind it.</p>')
    a('<div class="meta">')
    a('<p><b>Status:</b> as-is documentation, taken from the running game on 1 October 2026.<br>')
    a('<b>World in these screenshots:</b> 48 countries &middot; 310 clubs in Serbia &middot; '
      'Superliga Srbije, Season 2, Week 12 &middot; manager account <code>Velja</code> (Owner).<br>')
    a('<b>Screenshots:</b> desktop 1440&times;900, plus phone 390&times;844 where it matters.</p>')
    a('</div></div>')

    # ---------------------------------------------------------------- toc
    a('<div class="toc"><b>Contents</b><ol>')
    for n, t in [("1", "Getting in"), ("2", "How to move around"), ("3", "The dashboard"),
                 ("4", "Your club"), ("5", "Tactics"), ("6", "Training"), ("7", "The academy"),
                 ("8", "League, fixtures and results"), ("9", "Transfers"), ("10", "Finances"),
                 ("11", "The world"), ("12", "Watching a match"), ("13", "Community"),
                 ("14", "Admin"), ("15", "What is not built yet")]:
        a(f'<li><a href="#s{n}">{n}. {t}</a></li>')
    a('</ol></div>')

    # ---------------------------------------------------------------- 1
    a('<h2 id="s1">1. Getting in</h2>')
    a('<p>The game is a web app. Open the address, sign in, and pick a game mode. There is nothing '
      'to install and no launcher.</p>')
    a(shot("01-login", "The sign-in screen. Email and password, then <b>Enter Tifo</b>."))
    a('<p>After signing in you land on the mode picker. <b>TIFO UI MANAGER</b> is the game this '
      'manual covers &mdash; the club-management one with the match engine. The other three modes '
      'are separate, smaller games.</p>')
    a(shot("02-home-modes", "The mode picker. Four games; this manual covers the first one."))

    # ---------------------------------------------------------------- 2
    a('<h2 id="s2">2. How to move around</h2>')
    a('<p>The whole game is one page. The bar across the top is always there, and the section you '
      'are in is highlighted.</p>')
    a(shot("03-dashboard", "The header: Lobby, Club, League, your country, World, Community, "
                           "Admin, then your account and the game clock."))
    a('<div class="box good"><span class="lbl">The logo always takes you home</span>')
    a('<p>The badge in the corner &mdash; the manager leaning on the touchline with a pint &mdash; is '
      'a permanent fixture of the page, not part of any section. <b>Clicking it from anywhere in the '
      'game returns you to the dashboard.</b> You never have to hunt for a Home button, and you '
      'cannot get stranded on a sub-page.</p>')
    a('<p>On desktop it sits in the <b>top right</b>, just under the clock. On a phone it moves to the '
      'top left so it does not collide with the clock.</p></div>')
    a(shot("30-logo-corner", "The corner logo, in place, on a club page (Transfers)."))
    a(shot("31-logo-returns-dashboard", "Clicked from Transfers &mdash; back on the dashboard."))
    a('<p>Every section also has a <b>Back</b> button top-left, which returns you to wherever you '
      'came from rather than always going home. Use <b>Back</b> when you drilled in; use the '
      '<b>logo</b> when you are lost.</p>')
    a('<p class="small">Verified during capture: after clicking the logo on the Transfers page, the '
      'dashboard was confirmed loaded (<code>backOnDashboard: true</code>).</p>')

    # ---------------------------------------------------------------- 3
    a('<h2 id="s3">3. The dashboard</h2>')
    a('<p>Your home screen. It answers three questions: what is next, what happened, and what needs '
      'you. It loads the club, the schedule, recent matches and the club&rsquo;s standing stats.</p>')
    a(shot("03-dashboard", "The dashboard."))
    a('<div class="box warn"><span class="lbl">Watch your own result</span>')
    a('<p>Your own club&rsquo;s results stay hidden until you ask for them. Fixtures you have not '
      'played show a dash rather than a score, so nobody can read your season out of a public page. '
      'There is a reveal control for when you want it.</p></div>')

    # ---------------------------------------------------------------- 4
    a('<h2 id="s4">4. Your club</h2>')
    a('<p>Everything about OFK Omladinac lives under <b>Club</b> in the header, and most of it is also '
      'reachable from the shortcuts on the Tactics screen.</p>')
    a(shot("05-squad", "<b>First Team.</b> The squad, with each player&rsquo;s position, age and key "
                       "attributes. The XI and the bench are set here."))
    a(shot("06-club-profile", "<b>Club Profile.</b> The same squad shell plus the club's identity, "
                             "honours and summary."))
    a(shot("19-stadium", "<b>Stadium.</b> Capacity, seat blocks, roof levels and standing as an "
                         "upgradable asset &mdash; it drives gate revenue."))
    a(shot("18-staff", "<b>Staff.</b> Real staff on the books, with wages. Coaching quality feeds "
                       "training, so this is not decoration."))
    a(shot("15-medical", "<b>Medical Center.</b> The recovery queue: who is carrying fatigue or an "
                         "injury, and the treatment you can buy them."))
    a('<div class="box"><span class="lbl">Fatigue actually recovers</span>')
    a('<p>Match load is written to the database, and a weekly passive recovery reduces it. The '
      'medical page adds <i>active</i> treatment on top to speed a player up. Fatigue is a real '
      'input to injury risk and to how well your players perform &mdash; it is not a number that '
      'only goes up.</p></div>')

    # ---------------------------------------------------------------- 5
    a('<h2 id="s5">5. Tactics</h2>')
    a('<p>This is the part of the game that is genuinely different, and the screen to spend the most '
      'time on.</p>')
    a(shot("10-tactics-editor", "<b>Tactic Editor.</b> Drag the ball anywhere on a 6&times;7 grid (or a "
                                "corner), then drag the slot circles to set where each player should "
                                "be <i>in that exact situation</i>. 11 slots, <b>1012 rules</b>, "
                                "version counter on the right."))
    a(shot("35-visual-movement-editor",
           "<b>The Visual movement editor, in full.</b> The three controls at the top set what you "
           "are editing: the <b>ball state</b> you are placing the ball in, the <b>focused slot</b> you "
           "are moving, and the <b>focused target</b> zone. Every one of the <b>42 zones plus 4 "
           "corners</b> is labelled on the pitch \u2014 attacking rows (ATK+, ATK), midfield (MID+, "
           "MID), defensive (DEF+, DEF) and the four corners. The <b>11 slot circles</b> are the "
           "players; drag any of them into a new zone and that becomes their position <i>for this "
           "ball state only</i>. The white marker shows the focused slot."))
    a('<h3>How it works</h3>')
    a('<ul>')
    a('<li><b>Ball state.</b> 42 reachable zones plus 4 corner states. Pick one and every outfielder '
      'gets a position for it.</li>')
    a('<li><b>Slots.</b> 11, numbered, each with a role (GK, DEF, MID, ATT, WNG).</li>')
    a('<li><b>Shape and style.</b> 10 formations and 8 styles. Style sets the base behaviour '
      '(<code>BALANCED</code>, <code>ATTACKING</code>, <code>DEFENSIVE</code>, <code>COUNTER</code>, '
      '<code>POSSESSION</code>, <code>HIGH_PRESS</code>, <code>DIRECT</code>).</li>')
    a('<li><b>Set pieces</b> are configured per slot, separately from the visual editor.</li>')
    a('</ul>')
    a('<p>There is no mentality slider and no tempo slider. The grid <i>is</i> the tactic &mdash; the '
      'same idea as Sokker, and for the same reason: a slider cannot express "when the ball is in '
      'the left channel, the fullback tucks and the winger stays high".</p>')
    a(shot("11-formations", "<b>Formations.</b> The base shape, starting XI and bench. Changing the "
                            "formation reloads the slot anchors from the server; style and visual "
                            "edits autosave locally until you press Save."))
    a('<div class="box"><span class="lbl">Editor versus match</span>')
    a('<p>The editor stores your shape per team. The match engine runs on the same ruleset the editor '
      'writes. Shapes authored here are what the simulation reads &mdash; see section 15 for the '
      'current state of that link.</p></div>')

    # ---------------------------------------------------------------- 6
    a('<h2 id="s6">6. Training</h2>')
    a('<p>Training is where a manager actually improves a squad. It is also the screen with the best '
      'reporting in the game.</p>')
    a(shot("12-training-setup", "<b>Training Setup.</b> Pick the skill each positional group develops, "
                               "and set the club-wide intensity."))
    a('<h3>Intensity is a real trade-off</h3>')
    a('<table><thead><tr><th>Intensity</th><th>Growth</th><th>Fatigue added</th>'
      '<th>Injury chance</th></tr></thead><tbody>')
    a('<tr><td><b>Light</b></td><td>&times;0.75</td><td>low</td><td>none</td></tr>')
    a('<tr><td><b>Normal</b></td><td>&times;1.00</td><td>moderate</td><td>~0.4%</td></tr>')
    a('<tr><td><b>Very hard</b></td><td>&times;1.35</td><td>heavy</td><td>~4.5%</td></tr>')
    a('</tbody></table>')
    a('<p>Pushing a squad hard does not just build it faster &mdash; it makes players tired, and tired '
      'players get injured. That is the classic manager&rsquo;s dilemma, and here it is a number you '
      'chose rather than a mood.</p>')
    a('<p>Training is also <b>weighted by minutes actually played</b>, and coaching staff quality '
      'scales it. A player who did not feature gets less out of the week.</p>')
    a(shot("13-training-reports", "<b>Training Reports.</b> Per player, per skill: the value before, "
                                  "the exact decimal gain, and the resulting whole number. Open any "
                                  "week from the row to see the full squad sheet."))
    a('<div class="box good"><span class="lbl">Why this screen matters</span>')
    a('<p>Sokker shows a <i>Games</i> column and a projected <i>Eff.</i> column, and hides the actual '
      'numbers. This shows the real decimal change per skill per week. If you are coming from a '
      'game where training feels like a black box, this is the first place you will notice the '
      'difference.</p></div>')

    # ---------------------------------------------------------------- 7
    a('<h2 id="s7">7. The academy</h2>')
    a('<p>Juniors arrive once a season. You scout, develop, and decide when to promote them into the '
      'senior squad.</p>')
    a(shot("14-academy", "<b>Youth Academy.</b> The intake, current prospects and their development "
                         "state. Ten places at a time."))
    a('<h3>Talent is hidden, on purpose</h3>')
    a('<p>You are shown a <b>range</b> for a junior&rsquo;s talent, not a number, and the range '
      'narrows as you scout them and as they approach promotion. Current ability is public; the '
      'ceiling is not. That is the same information asymmetry Sokker uses, and it is what makes an '
      'academy a decision rather than a spreadsheet.</p>')
    a('<p>Promotion spends a points budget across eight skills and reveals the allocation one point '
      'at a time, so you watch the player being built.</p>')

    # ---------------------------------------------------------------- 8
    a('<h2 id="s8">8. League, fixtures and results</h2>')
    a('<p>Your league is a live page with the table, the fixture list and the top scorers together, '
      'because you almost always want more than one of them.</p>')
    a(shot("07-league-table", "<b>League table.</b> Position, played, won, drawn, lost, goals for, "
                              "goals against, goal difference and points &mdash; with the title race, "
                              "the promotion places and the relegation zone marked. Fixtures and "
                              "results sit beside it with form and strength for both sides."))
    a('<p>Clubs are clickable, which takes you straight into that club&rsquo;s squad and players '
      'without leaving the league context. Your own club is tagged <b>PLAYER</b>; the rest are '
      'tagged <b>AI</b>.</p>')
    a(shot("08-fixtures", "<b>League schedule.</b> Every round of the season, by round, with the "
                          "current round highlighted."))
    a(shot("09-results", "<b>Results.</b> Matches you have opened, newest first."))
    a(shot("25-top-scorers", "<b>Top scorers and assists.</b> Empty early in a season, once goals "
                             "start landing it fills in."))
    a('<div class="box"><span class="lbl">Promotion is real</span>')
    a('<p>At the end of the season the pyramid moves: the champion is promoted, the bottom clubs go '
      'down, and the middle places settle a playoff. The same ladder runs in every country.</p></div>')

    # ---------------------------------------------------------------- 9
    a('<h2 id="s9">9. Transfers</h2>')
    a('<p>The transfer market is the social centre of the game, so it gets the most screen space: '
      'global list, direct offers, and your own selling desk.</p>')
    a(shot("16-transfers",
           "<b>Transfer Centre.</b> Four panels on one page &mdash; club transfer stats, the "
           "transfer desk, the global market board and your own squad's listings."))
    a('<h3>How a deal works</h3>')
    a('<ul>')
    a('<li>List a player and set a fee and a wage ask.</li>')
    a('<li>Other clubs bid. A negotiation is a thread, not a single yes/no &mdash; you can go '
      'several rounds on <b>fee</b>, <b>wage</b> and <b>contract length</b> separately.</li>')
    a('<li>Agents take a cut. Players can object to a move.</li>')
    a('<li>Accept, and the ledger moves: your budget down, theirs up.</li>')
    a('</ul>')
    a('<p>There is a transfer window, contracts expire into free agency, loans exist, and squad '
      'registration is capped &mdash; so you cannot simply buy eleven players.</p>')
    a('<div class="box warn"><span class="lbl">Current gap</span>')
    a('<p>If several clubs bid, you currently take the highest automatically &mdash; you cannot pick '
      'which offer to accept. The engine supports it; the interface does not expose it yet. See '
      'section 15.</p></div>')

    # ---------------------------------------------------------------- 10
    a('<h2 id="s10">10. Finances</h2>')
    a('<p>Money is the constraint that makes a football manager a manager. Every club runs a weekly '
      'ledger; yours shows you yours.</p>')
    a(shot("17-finances", "<b>Club Finances.</b> Weekly income and outgoings, the ledger, and the "
                          "history. Empty at the start of a season, then it fills line by line."))
    a('<h3>What is in the ledger</h3>')
    a('<table><thead><tr><th>Money in</th><th>Money out</th></tr></thead><tbody>')
    a('<tr><td>Gate receipts</td><td>Player wages</td></tr>')
    a('<tr><td>Broadcast</td><td>Staff wages</td></tr>')
    a('<tr><td>Sponsorship</td><td>Facility upkeep</td></tr>')
    a('<tr><td>Merchandising</td><td>Junior academy upkeep</td></tr>')
    a('<tr><td>Prize money</td><td>Transfer fees</td></tr>')
    a('</tbody></table>')
    a('<div class="box good"><span class="lbl">You are shown the band</span>')
    a('<p>Financial health is a visible multiplier on your club (comfortable, strained, critical). '
      'Most games hide this. Showing it turns a punishment into a decision, because you can see the '
      'line you are approaching and do something about it before it bites.</p></div>')

    # ---------------------------------------------------------------- 11
    a('<h2 id="s11">11. The world</h2>')
    a('<p>The game is not only Serbia. There are 48 countries, each with its own 31-division pyramid '
      'and its own rating.</p>')
    a(shot("20-world", "<b>World.</b> Every country, its rating, and whether it is active or "
                       "simulated. International cups and their qualification counts are on this "
                       "page too."))
    a('<p>Every country starts on a rating of 1500 and <b>earns its rating from results</b> &mdash; '
      'there is no hand-written strength table, and this world is not a replica of the real one.</p>')
    a(shot("21-country", "<b>Country page.</b> One country: its code, currency, divisions, reputation, "
                         "and its divisions table. This is also where cups, playoffs and national "
                         "teams live."))
    a(shot("22-national-team", "<b>National team.</b> The senior squad, with the squad editor for "
                               "selecting and picking a side."))
    a(shot("23-u21", "<b>Under-21 side.</b> Same view for the youth international team."))
    a(shot("24-cup", "<b>Cup.</b> The knockout bracket for the national cup."))
    a('<div class="box"><span class="lbl">Three tiers of club competition</span>')
    a('<p>Beyond the national cup there are three continental cups &mdash; Champions, Masters and '
      'Challenge &mdash; qualified from the season that has finished. Clubs are ranked on the same '
      'table you can read.</p></div>')

    # ---------------------------------------------------------------- 12
    a('<h2 id="s12">12. Watching a match</h2>')
    a('<p>This is the part no other browser manager does the same way. The match is not a dice roll '
      'with a report attached &mdash; it is a <b>spatial simulation</b>, 3,600 ticks of it, and you '
      'can watch every one.</p>')
    a(shot("33-match-viewer-2d", "<b>2D viewer.</b> All 22 players positioned on the pitch with the "
                                  "ball. The panel on the right is the live event stream, and every "
                                  "entry carries the exact coordinates involved &mdash; "
                                  "<code>DUEL DRIBBLE won by H10 (6.5,4.1) v A8 (6.5,4.5)</code>. "
                                  "Speed control, pause, and a scrub bar."))
    a(shot("34-match-viewer-3d", "<b>3D viewer.</b> The same simulation in three dimensions. The event "
                                  "stream shows the ball being saved, a shot missing and a throw-in "
                                  "being awarded."))
    a('<h3>Why this is the point of the whole game</h3>')
    a('<ul>')
    a('<li><b>You can see why.</b> A goal is not a dice roll; it is a sequence of interceptions, '
      'duels and passes you can rewind and read.</li>')
    a('<li><b>It is deterministic.</b> Every match is generated from a seed, printed on screen. The '
      'same seed always produces the same match. That is why the numbers below can be trusted.</li>')
    a('<li><b>The report and the animation are the same truth.</b> They come from the same '
      'simulation, so they cannot disagree.</li>')
    a('</ul>')
    a('<table><thead><tr><th>Measured over 100 matches</th><th>Ours</th><th>Real football</th>'
      '</tr></thead><tbody>')
    a('<tr><td>Goals per match</td><td>3.55</td><td>2.7</td></tr>')
    a('<tr><td>Shots</td><td>35.7</td><td>25</td></tr>')
    a('<tr><td>Shots on target</td><td>28% of shots</td><td>33%</td></tr>')
    a('<tr><td>Pass accuracy</td><td>85%</td><td>80&ndash;86%</td></tr>')
    a('<tr><td>Penalties</td><td>0.24 per match, ~76% taken</td><td>~0.27, ~76%</td></tr>')
    a('</tbody></table>')
    a('<div class="box good"><span class="lbl">About penalties, specifically</span>')
    a('<p>One of the most common complaints about browser managers is that penalties are nonsense &mdash; '
      'too many, or too many missed. Here the award rate is right (0.24 against a real 0.27), the '
      'taker is chosen on skill, and the keeper <b>commits to a side before the kick</b>, so a good '
      'keeper reads it. Conversion was validated over 4,000 seeded kicks.</p></div>')

    # ---------------------------------------------------------------- 13
    a('<h2 id="s13">13. Community</h2>')
    a(shot("26-community", "<b>Community Chat.</b> Managers talk here, and the same panel carries "
                           "new-registration approvals for owners."))

    # ---------------------------------------------------------------- 14
    a('<h2 id="s14">14. Admin</h2>')
    a('<p>Owner and admin accounts get a world-repair panel: rebuild missing pieces of the world, '
      'activate a country, and check the state of the database.</p>')
    a(shot("27-admin", "<b>Admin.</b> Operational tools, visible only to admin accounts."))
    a(shot("28-user-profile", "<b>Your account.</b> Role, subscription, country and club."))

    # ---------------------------------------------------------------- 15
    a('<h2 id="s15">15. What is not built yet</h2>')
    a('<p>Honesty section. This manual documents the game as it runs today, including the parts that '
      'are stubs or unfinished. None of these is hidden in the interface.</p>')
    a('<table><thead><tr><th>Area</th><th>State</th><th>Note</th></tr></thead><tbody>')
    a('<tr><td>Tactic editor &rarr; match engine</td><td><span class="pill part">partly wired</span>'
      '</td><td>The editor stores and renders your shape. The engine runs on the same ruleset, but '
      'the two are being connected &mdash; treat a saved shape as not yet confirmed in a live match.'
      '</td></tr>')
    for area, pill, cls, note in [
        ("Seller picks which bid to accept", "missing", "no",
         "The engine can do it; the button is not exposed yet."),
        ("Top scorers / player stats", "early season", "part",
         "Correct but empty until goals are scored."),
        ("Average match rating on player pages", "broken", "no",
         "Always shows a dash instead of a number."),
        ("World Cup, NT qualifiers, U-21 cups", "not built", "no",
         "Listed on the World page as coming; not playable yet."),
        ("Board expectations / being sacked", "read only", "part",
         "Trust is computed and shown; nothing acts on it yet."),
        ("Retirement", "missing", "no",
         "Players age but never retire, so the pool only grows."),
        ("Supporter mood", "missing", "no",
         "No pressure layer yet &mdash; nothing can go wrong."),
    ]:
        a(f'<tr><td>{area}</td><td><span class="pill {cls}">{pill}</span></td>'
          f'<td>{note}</td></tr>')
    a('</tbody></table>')
    a('<div class="box bad"><span class="lbl">One fix made while writing this</span>')
    a('<p>The league table and the fixture list were both throwing a JavaScript error and rendering '
      '&ldquo;Could not load&rdquo; &mdash; a single mistyped variable reference in the fixture '
      'card renderer. It is fixed, and the screenshots in this manual show both pages working. If '
      'you hit either page and see an error card, it is a different problem and worth reporting.</p>'
      '</div>')

    a('<hr>')
    a('<div class="box bad"><span class="lbl">Not production-ready &mdash; please read before sharing'
      '</span>')
    a('<p>This build is a development instance. The sign-in page currently ships the owner account '
      'address and password as pre-filled default values in the page source, which means anyone who '
      'views source gets an administrator account. That must be removed before this is opened to '
      'the public. A few other things are also unfinished &mdash; the section above is the honest '
      'list.</p></div>')

    a('<hr>')
    a('<p class="small"><b>Regenerating this manual.</b> The screenshots are produced by a script, '
      'so this document can be rebuilt against a newer build rather than hand-maintained. Run it '
      'with the app running, then rebuild the document. Every screenshot in here was captured '
      'automatically, and the pages that failed to render were detected automatically rather than '
      'photographed.</p>')

    a('</div></body></html>')

    with open(OUT_HTML, "w") as fh:
        fh.write("\n".join(p))
    print(f"wrote {OUT_HTML} ({os.path.getsize(OUT_HTML)/1e6:.1f} MB, self-contained)")


if __name__ == "__main__":
    build()