// pages/views/match-view.js
import { byPosition } from '../../ui/position-order.js';
import {
    htmlEscape, parseMatchDate, formatDateTimeLabel, formatRatingBadge,
    formatCompactPlayerName, buildLineupEventBadges
} from './utils.js';

export function createMatchView(deps) {
    const { authFetch, getTeamId, goBackSmart, getLeagueNavState } = deps;

    async function loadMatch(matchId, caller, options = {}) {
        const pushHistory = options.pushHistory !== false;
        const navState = typeof getLeagueNavState === 'function' ? getLeagueNavState() : {};
        if (pushHistory) {
            const depsNav = deps.getNavigationDeps?.();
            if (depsNav?.pushNavState) {
                depsNav.pushNavState({ type: 'match', matchId, caller, ...navState });
            }
        }
        const mainContent = document.getElementById("main-content");
        // The three tabs, and the requested one validated against them.
        //
        // It used to be `options.initialTab === 'report' ? 'report' : 'preview'` — a two-way test on one
        // tab, so **every other request silently became Preview**. "Show results" asks for 'goals', is not
        // 'report', and landed a manager who had just asked to see his result on the pre-match screen.
        // Three tabs, one list, and an unknown request is refused rather than defaulted into the one tab
        // nobody should land on by accident.
        // Which id space this id belongs to, and it is **not guessed**.
        //
        // A fixture and a Match are separate tables with overlapping ids - fixture 15 and match 15 are
        // both 15 - so a caller that does not say which it holds gets whichever the server finds first.
        // That is how "Next match" on the dashboard, which holds a fixture id, opened a different club's
        // already-played game. Callers now pass `fixture: true`, and the two spaces have two endpoints.
        const isFixture = options.fixture === true;

        // **Match Setup is its own tab, and it used not to be.** The owner's report: *"podesavanje
        // lineupa i taktike ne treba da bude na Preview tabu meca, bolje dodaj Match Setup tab i stavi na
        // njega deo za lineup, taktike itd"* - setting the eleven and the tactics should not be on the
        // Preview tab, they should be on a Match Setup tab.
        //
        // They were, all three panels, underneath the pre-match preview. So the tab a manager opens to
        // *look at* a match also let him *change* it, and the preview buried the prediction under an
        // eleven, a game plan and a substitution sheet.
        const MATCH_TABS = ['preview', 'setup', 'goals', 'report'];
        const requestedTab = String(options.initialTab || '').toLowerCase();
        const initialTab = MATCH_TABS.includes(requestedTab) ? requestedTab : 'preview';
        if (caller === "undefined") {
            mainContent.innerHTML = `<div class="team-card"><p>Match not found.</p></div>`;
            return;
        }
        try {
            // An unplayed fixture has **no Match row at all**, so there is no event stream to fetch -
            // and asking for one is not a tolerated 404, it is a guaranteed one.
            //
            // This used to fetch a deliberate `/nonexistent` and then tolerate the failure, because
            // authFetch's contract was assumed to be "returns a response, `ok` says what happened".
            // It is not: **authFetch throws AuthFetchError on every non-2xx.** So the throw skipped
            // the tolerance three lines below it, landed in the catch at the bottom of this function,
            // and rendered "Error loading match: No static resource nonexistent." The `eventsOk`
            // branch was unreachable, and "Next match" on the dashboard was broken for every manager
            // because it opens a fixture.
            //
            // The metadata endpoint below is the record that does exist for an unplayed fixture, and
            // it is already fetched with its own catch, so the header builds from that. Nothing is
            // lost and one pointless round trip per fixture view disappears with it.
            const eventsRequest = isFixture
                ? Promise.resolve([])
                : authFetch(`/matches/${matchId}/detail`)
                    .then(r => (r.ok ? r.json() : []))
                    .catch(() => []);

            // The events carry the score and the date but not what kind of match it was, so the
            // header pulls the one record that does. It is one extra request in parallel, not a
            // second round trip.
            const [events, matchMeta] = await Promise.all([
                eventsRequest,
                authFetch(isFixture ? `/matches/by-fixture/${matchId}` : `/matches/${matchId}`)
                    .then(r => r.ok ? r.json() : null)
                    .catch(() => null)
            ]);

            // **THE FIX. The id space is resolved once, here, and nowhere else.**
            //
            // `matchId` above is a FIXTURE id whenever `isFixture` is true, and the two spaces overlap:
            // fixture 5 and match 5 are both 5. This used to pass it straight to
            // `/match-stats/lineups/${matchId}` — a match endpoint — so a fixture for OFK Omladinac v
            // SK Teleoptik City rendered the lineups of GFK Bor 1945 v SK Kragujevac. A different club's
            // played game, with real names, ratings, cards and minutes, under this fixture's heading.
            //
            // The rule was already written in this file's own comment twenty lines above: *"a caller that
            // does not say which it holds gets whichever the server finds first."* Three places did not
            // say which they held.
            //
            // So: the metadata endpoint carries `playedMatchId`, and **no match-only endpoint is called
            // unless there is one.** An unplayed fixture gets an honest empty state instead of another
            // team's result, which is what `MatchDTO.unplayed` and this screen have claimed since the
            // fixture route was introduced.
            const playedMatchId = isFixture
                ? (matchMeta?.playedMatchId ?? null)
                : matchId;
            const hasPlayedMatch = playedMatchId !== null && playedMatchId !== undefined;

            const lineupsPayload = hasPlayedMatch
                ? await authFetch(`/match-stats/lineups/${playedMatchId}`)
                    .then(r => r.ok ? r.json() : null)
                    .catch(() => null)
                : null;

            // No events and no metadata means there is genuinely nothing to show. No events *with*
            // metadata means an unplayed match, which has a header and a preview and is not an error.
            if (events.length === 0 && !matchMeta) {
                mainContent.innerHTML = `<div class="team-card"><p>Match not found.</p></div>`;
                return;
            }

            // The event is the richer record when there is one; the metadata is the only record when the
            // match has not been played. Either way the score is null rather than 0, because a 0-0 that
            // was never played is indistinguishable from a goelless draw.
            const first = events[0] || {};
            const homeTeamName = first.homeTeam || matchMeta?.homeTeam || "Home";
            const awayTeamName = first.awayTeam || matchMeta?.awayTeam || "Away";
            const homeGoals = first.homeGoals ?? matchMeta?.homeGoals ?? null;
            const awayGoals = first.awayGoals ?? matchMeta?.awayGoals ?? null;
            const played = homeGoals !== null && awayGoals !== null;
            const homeTeamId = lineupsPayload?.homeTeamId || matchMeta?.homeTeamId || null;
            const awayTeamId = lineupsPayload?.awayTeamId || matchMeta?.awayTeamId || null;

            const matchDate = parseMatchDate(first.matchDate || matchMeta?.matchDate);
            // Time only, no date - there is no calendar in this game (owner, 2026-10-01). The season
            // label underneath carries Season/Week/Day, and that is the whole of when a match is.
            const formattedDate = Number.isNaN(matchDate?.getTime?.())
                ? ''
                : `${String(matchDate.getHours()).padStart(2, '0')}:${String(matchDate.getMinutes()).padStart(2, '0')}`;

            // "Premier League · League" reads worse than "Premier League" when the type is already
            // implied, but the owner asked to see what kind of match it is, so it is shown and the
            // fallback keeps the header tidy for a match with no competition on it.
            const competitionHeading = matchMeta?.competitionName
                ? `<div style="text-align:center; margin-bottom:6px; color:#8fd18f; font-weight:600;">${htmlEscape(matchMeta.competitionName)}${matchMeta.competitionType ? ` <span style="color:#aaa; font-weight:400;">· ${htmlEscape(matchMeta.competitionType)}</span>` : ''}</div>`
                : '';
            // Owner request, 2026-10-12: the season, the week and the day belong next to the kickoff
            // time, not on a line of their own under it.
            //
            // The label was taken from `matchMeta.seasonDayLabel` and that is null on the fixture path,
            // so the header showed a bare "16:00" with no date at all for every unplayed match - which is
            // most of them. Built from the fixture's own columns instead, and falling back to whatever
            // the server sent, so a played match and an unplayed one read the same way.
            const seasonDayLabel = buildCalendarLabel(matchMeta);
            // The server's label ends with the kickoff time, and the time is already printed beside it,
            // so it is dropped rather than shown twice.
            const calendarSuffix = seasonDayLabel
                ? seasonDayLabel.replace(/\s*·\s*\d{1,2}:\d{2}\s*$/, '').trim()
                : '';

            // **An unrecognised caller goes back where it came from, not to the league results page.**
            //
            // This list is an allowlist, so every surface that is not in it fell through to 'results'
            // - and Back on a national-team tie landed a manager on a league's match list. The owner
            // reported it as "the back button does not work after clicking a national-cup match", which
            // is exactly what it looked like from the outside: a Back button that is there, and does
            // something else.
            //
            // `null` means "pop the history stack". That is the correct answer for a caller this build
            // has never heard of, and it stays correct for the next surface to be added, which an
            // allowlist cannot do. The named cases keep their explicit targets because Back from a
            // league table is expected to land on the table, not on wherever the manager came from.
            const KNOWN_BACK_TARGETS = {
                match: 'results',
                results: 'results',
                leagueMatches: 'leagueMatches',
                leagueTable: 'leagueTable',
                leagueSchedule: 'leagueSchedule',
                dashboard: 'dashboard'
            };
            let backTarget = KNOWN_BACK_TARGETS[caller] ?? 'dashboard';
            if (backTarget === 'dashboard' && !(caller in KNOWN_BACK_TARGETS)) {
                console.info(`Unknown caller "${caller}" - Back will return to the dashboard.`);
            }

            /**
             * The tab bar's buttons, built one at a time.
             *
             * <p>**Written as parts, not as one long line of markup with the state inside it** (owner,
             * 2026-10-21: a played match could not be opened at all - "Error loading match: Cannot read
             * properties of null (reading 'addEventListener')"). The line carried the "disabled" state
             * **inside** the `class` attribute, so the attribute and the state were parsed as one thing,
             * and the buttons after it were not buttons any more: the browser read the rest of the tab
             * bar as the text of the first one, and `getElementById("view-stats")` found nothing.
             *
             * <p>Here every button is written whole - the class, then the disabled attribute, then the
             * label - and the state is decided before the string is built. Nothing can be half-written.
             */
            const matchTabButtons = [
                { id: 'view-preview', label: 'Preview' },
                { id: 'view-setup', label: 'Match Setup' },
                { id: 'view-lineups', label: 'Lineups', needsPlayed: true, disabledTitle: 'Recorded once the match has been played' },
                { id: 'view-stats', label: 'Stats', needsPlayed: true, disabledTitle: 'Recorded once the match has been played' },
                { id: 'view-goals', label: 'Goals', needsPlayed: true, disabledTitle: 'Recorded once the match has been played' },
                { id: 'view-replay', label: 'Replay', needsPlayed: true, disabledTitle: 'Available once the match has been played' },
                { id: 'view-report', label: 'Match Report', needsPlayed: true, disabledTitle: 'Written once the match has been played' }
            ];
            const matchTabButtonsHtml = matchTabButtons.map(tab => {
                const active = initialTab === tab.id.replace('view-', '');
                const disabled = tab.needsPlayed && !hasPlayedMatch;
                return `<button type="button" id="${tab.id}" class="fm-action-btn secondary fm-match-action-btn${active ? ' is-active' : ''}"${disabled ? ` disabled title="${tab.disabledTitle}"` : ''}>${tab.label}</button>`;
            }).join('');

            mainContent.innerHTML = `
            <div class="team-card fm-match-page">
                <div style="display:flex; justify-content:flex-start; margin-bottom:10px;">
                    <button type="button" id="back-button-top" class="back-to-dashboard" onclick="${backTarget === 'dashboard' ? 'window.loadDashboard && window.loadDashboard()' : `goBackSmart('${backTarget}')`}">&#8592; Back</button>
                </div>
                <!--
                    **The tabs are a tab bar, and they sit under the Back button** (owner, 2026-10-20:
                    "kad se otvori bilo koji tab - Preview, Match setup, Lineups, Stats, Goals, Replay,
                    Match report dugmad izadju prva pa se ne zna odmah na kojojk smo strani i mora da se
                    skrola, mozes li ih staviti kao tabove u jednim redu npr u vrhu strane odmah ispod
                    backa").

                    <p>They were a row of ordinary buttons in the middle of the page, below the scoreline,
                    and every one of them looked the same - so a manager who opened "Stats" could not
                    say from the screen which tab he was on, and on a phone the row was below the fold.
                    The open tab is marked, and the row is a bar that scrolls sideways when it does not
                    fit.
                -->
                <div id="match-buttons-container" class="fm-match-actions fm-match-tabs" role="tablist">${matchTabButtonsHtml}</div>
                <h2 style="text-align:center;">${played ? 'Match Details' : 'Match Preview'}</h2>
                ${competitionHeading}
                <div class="fm-match-scoreline" style="font-size:1.5em; margin:20px 0; font-weight:bold;">
                    <div class="fm-match-score-team">
                        <div class="fm-match-score-name">${homeTeamId ? `<span class="cs-clickable" onclick="loadLeagueTeam(${homeTeamId}, '${htmlEscape(homeTeamName)}')">${homeTeamName}</span>` : homeTeamName}</div>
                        ${played ? `<div>${homeGoals}</div>` : ''}
                    </div>
                    <div class="fm-match-score-separator" style="font-size:1.9em;">-</div>
                    <div class="fm-match-score-team">
                        <div class="fm-match-score-name">${awayTeamId ? `<span class="cs-clickable" onclick="loadLeagueTeam(${awayTeamId}, '${htmlEscape(awayTeamName)}')">${awayTeamName}</span>` : awayTeamName}</div>
                        ${played ? `<div>${awayGoals}</div>` : ''}
                    </div>
                </div>
                <div style="text-align:center; color:#aaa; margin-bottom:25px;">
                    <span class="fm-match-when">
                        &#128197; ${htmlEscape(formattedDate)}${calendarSuffix ? ` <span class="fm-match-when-calendar">· ${htmlEscape(calendarSuffix)}</span>` : ''}
                    </span>
                </div>
                <div id="match-info" style="margin-top:15px; min-height:200px;"></div>
                <div id="fm-match-setup">
                    <p class="fm-subtle" style="margin-bottom:14px;">
                        Set the eleven, the tactics and the orders for this match. Everything here is
                        closed an hour before kickoff, when the match is decided.
                    </p>
                    <div id="fm-match-lineup"></div>
                    <div id="fm-substitution-plan"></div>
                </div>
                <div style="text-align:center; margin-top:30px;">
                    <button id="back-button" style="padding:10px 24px; font-size:1.1em;">Back</button>
                </div>
            </div>`;

            // The bottom Back button was never wired to anything.
            //
            // There are two of them: the top one carries an inline onclick and works, and this one was
            // given a dataset and a display style and then nothing else - no listener, no onclick. So
            // clicking it did exactly nothing, silently, with no console error, which is why it read as
            // "the button is not there" rather than as a bug. It was reported as a cup problem and it
            // was not one: this button has been dead on every match view.
            //
            // The listener is attached here, at render, rather than through the inline attribute so the
            // two buttons cannot drift apart again - the top one had the target baked into its markup
            // and this one did not, which is exactly the shape that produced the bug.
            const backButton = document.getElementById('back-button');
            backButton.dataset.target = backTarget;
            backButton.style.display = 'inline-block';
            backButton.addEventListener('click', () => goBackSmart(backTarget));

            const infoDiv = document.getElementById("match-info");
            let cachedMatchPreview = null;
            let cachedMatchReport = null;

            async function revealMatchResultIfAllowed() {
                try {
                    await authFetch(`/matches/${matchId}/reveal`, { method: 'POST' });
                } catch (error) {
                    console.warn(`Reveal skipped for match ${matchId}:`, error);
                }
            }

            /**
             * "Season 1 · Week 1 · Day 7", from whatever the payload carries.
             *
             * <p>Prefer the server's own label when there is one and build it otherwise, because the
             * fixture path has no `seasonDayLabel` and the played-match path does. Returns null when
             * neither carries the numbers, rather than a label with holes in it.
             */
            function buildCalendarLabel(meta) {
                if (!meta) return null;
                if (meta.seasonDayLabel) return meta.seasonDayLabel;
                const season = meta.seasonNumber ?? meta.seasonYear;
                const week = meta.weekNumber;
                const day = meta.dayNumber;
                const bits = [];
                if (season !== null && season !== undefined) bits.push(`Season ${season}`);
                if (week !== null && week !== undefined) bits.push(`Week ${week}`);
                if (day !== null && day !== undefined) bits.push(`Day ${day}`);
                return bits.length ? bits.join(' · ') : null;
            }

            function renderMatchPreview(previewPayload) {
                /*
                 * No calendar line of its own.
                 *
                 * <p>This card used to print the season, the week, the day and a **real calendar
                 * date** under its title (owner, 2026-10-19: "ima real datum, ne treba ni da bude tu
                 * jer imamo gore iznad"). The header of the page already prints the kickoff and the
                 * season, the week and the day, and a date is not a fact this game has - a season is
                 * twelve weeks. So the line is gone, on the phone and on the desktop alike.
                 */
                const predictionReasons = Array.isArray(previewPayload?.predictionReasons) ? previewPayload.predictionReasons : [];
                const homeInsights = Array.isArray(previewPayload?.homeInsights) ? previewPayload.homeInsights : [];
                const awayInsights = Array.isArray(previewPayload?.awayInsights) ? previewPayload.awayInsights : [];
                const homeAbsentees = Array.isArray(previewPayload?.homeAbsentees) ? previewPayload.homeAbsentees : [];
                const awayAbsentees = Array.isArray(previewPayload?.awayAbsentees) ? previewPayload.awayAbsentees : [];
                // Defined here because the six fields below use it, and `const` is not hoisted: a
                // helper declared 40 lines further down is a ReferenceError, not a late binding.
                const numberOr = (value, fallback) => (value === null || value === undefined ? fallback : Number(value));

                const homeWin = Number(previewPayload?.homeWinProbability ?? 0) * 100;
                const draw = Number(previewPayload?.drawProbability ?? 0) * 100;
                const awayWin = Number(previewPayload?.awayWinProbability ?? 0) * 100;
                const expectedHomeGoals = Number(previewPayload?.expectedHomeGoals ?? 0);
                const expectedAwayGoals = Number(previewPayload?.expectedAwayGoals ?? 0);
                // `null` means "not knowable before a match", and `MatchPreviewService` sends exactly
                // that for formation fitness, bench quality and availability. It must survive to the
                // renderer, because `pct`/`fixed1`/`withUnit` below are written to turn null into
                // nothing at all - and then the row reads "Not known yet".
                //
                // This used to be `Number(x ?? 0)`, which collapsed the null to a real 0 three lines
                // before the helpers could see it. Every guard downstream then had nothing to guard:
                // `pct(0)` is "0", not null, so `pct(x) === null ? '' : ...` printed
                // **"Availability 0% vs 0%"** and **"0.0 bench"** on a fixture that has not happened.
                //
                // A 0-0 that was never played is not the same as a 0-0 that was. Same argument as the
                // null score in MatchDTO, and it cost the same way.
                //
                // `numberOr(value, null)` keeps the null and still coerces a real number.
                const homeFormationFitness = numberOr(previewPayload?.homeFormationFitness, null);
                const awayFormationFitness = numberOr(previewPayload?.awayFormationFitness, null);
                const homeBenchQuality = numberOr(previewPayload?.homeBenchQuality, null);
                const awayBenchQuality = numberOr(previewPayload?.awayBenchQuality, null);
                const homeAvailabilityScore = numberOr(previewPayload?.homeAvailabilityScore, null);
                const awayAvailabilityScore = numberOr(previewPayload?.awayAvailabilityScore, null);
                // The edges the prediction was built from. Kept as null rather than defaulted to 0, for
                // the same reason as the availability scores above: a fabricated zero on the preview is
                // worse than an honest blank.
                const homeEdgeOvr = numberOr(previewPayload?.homeEdgeOvr, null);
                const awayEdgeOvr = numberOr(previewPayload?.awayEdgeOvr, null);
                const homeAdvantage = numberOr(previewPayload?.homeAdvantageOvr, 0);
                const analysis = htmlEscape(String(previewPayload?.analysisText || ''));
                const playedOnce = previewPayload?.played !== false;

                // Semantic classes, not inline styles.
                //
                // This block was ~30 `style="..."` attributes, which is why the Preview tab looked like
                // nothing else in the game while the ZOX page beside it looked deliberate: the ZOX page
                // has a stylesheet and a palette, and this had neither. Same content, same numbers - it
                // just could not be styled as a set. `fm-mpv-*` is styled in dashboard.css.
                const renderInsights = items => items.length
                    ? items.map(item => `<div class="fm-mpv-row"><span>${htmlEscape(String(item.label || 'Insight'))}</span><strong>${htmlEscape(String(item.value || 'N/A'))}</strong></div>`).join('')
                    : `<div class="fm-mpv-empty">Nothing known yet.</div>`;

                const renderAbsentees = items => items.length
                    ? items.map(item => `<span class="fm-mpv-chip">${htmlEscape(String(item))}</span>`).join('')
                    : `<span class="fm-mpv-empty">No absences reported.</span>`;

                // Null is "not known yet", and it must read as absent rather than as a number.
                //
                // `Number(null || 0).toFixed(0)` is "0", so an unplayed match opened straight into this
                // screen and reported a 0% formation fitness, a 0.00 xG and a 92% fit for nobody - the
                // defaults the endpoint uses when it has no data, rendered as if it had some. The owner
                // expects an unplayed match to look empty; empty is the honest version of that.
                const pct = value => (value === null || value === undefined ? null : Number(value).toFixed(0));
                const fixed1 = value => (value === null || value === undefined ? null : Number(value).toFixed(1));
                const withUnit = (value, unit) => (value === null ? '' : `${value}${unit}`);

                // A fraction rendered as a percentage, and null-safe about it. Declared here, after
                // `pct`, because it calls `pct` and `const` is not hoisted.
                //
                // `pct(homeFormationFitness * 100)` was the original line, and it carries the same defect
                // as the `?? 0` one level up: in JavaScript **`null * 100 === 0`**, so the null became a
                // real 0 during the arithmetic and `pct` was handed a number it was perfectly happy to
                // print. That is why fixing the `?? 0` removed "0.0 bench" and left "0%" and "0% fit"
                // still standing - the same defect one operator further along.
                //
                // The multiply must happen only once there is something to multiply.
                const pctOfFraction = value => (value === null || value === undefined ? null : pct(value * 100));

                /**
                 * The edge a side holds, on the scale the rest of the screen speaks.
                 *
                 * These two cards used to show formation fitness and bench quality, which read `null`
                 * until a manager had picked eleven for this specific fixture - so they said "Not known
                 * yet" beside a prediction that was confident from the squad average alone. They now
                 * show the same numbers the prediction was built from, which are always available, and
                 * the home card says how much of its edge is the ground.
                 */
                const edgeLine = (edge, advantage) => {
                    const value = Number(edge);
                    if (!Number.isFinite(value)) return 'Not known yet';
                    const advantageText = Number.isFinite(Number(advantage)) && Number(advantage) !== 0
                        ? ` &middot; +${Number(advantage).toFixed(1)} from home advantage`
                        : '';
                    return `Edge ${value > 0 ? '+' : ''}${value.toFixed(1)} OVR${advantageText}`;
                };

                infoDiv.innerHTML = `
                    <div class="fm-mpv">
                        <header class="fm-mpv-band">
                            <span class="fm-mpv-band-kicker">Match preview</span>
                            <h3 class="fm-mpv-band-title">${htmlEscape(homeTeamName)}</h3>
                            <div class="fm-mpv-band-versus">vs</div>
                            <h3 class="fm-mpv-band-title">${htmlEscape(awayTeamName)}</h3>
                        </header>

                        <div class="fm-mpv-shapes">
                            ${shapePanel('Home', homeTeamName, previewPayload?.homeFormation,
                                previewPayload?.homeFormationFitness, previewPayload?.homeBenchQuality,
                                previewPayload?.homePositionMismatches, previewPayload?.homeHasLineup)}
                            ${shapePanel('Away', awayTeamName, previewPayload?.awayFormation,
                                previewPayload?.awayFormationFitness, previewPayload?.awayBenchQuality,
                                previewPayload?.awayPositionMismatches, previewPayload?.awayHasLineup)}
                        </div>

                        <div class="fm-mpv-row-3">
                            <section class="fm-mpv-card fm-mpv-card--home">
                                <h4 class="fm-mpv-label">Home edge</h4>
                                <div class="fm-mpv-team">${htmlEscape(homeTeamName)}</div>
                                <div class="fm-mpv-sub">${edgeLine(homeEdgeOvr, homeAdvantage)}</div>
                            </section>
                            <section class="fm-mpv-card fm-mpv-card--pred">
                                <h4 class="fm-mpv-label">Prediction</h4>
                                <div class="fm-mpv-prediction">${previewPayload?.expectedResult
                                    || (playedOnce ? 'Home win' : 'Not predicted')}</div>
                                <div class="fm-mpv-probs">${[pct(homeWin), pct(draw), pct(awayWin)].every(v => v === null)
                                    ? '' : `<span>${pct(homeWin)}%</span><span>${pct(draw)}%</span><span>${pct(awayWin)}%</span>`}</div>
                                <div class="fm-mpv-sub">${expectedHomeGoals === null ? '' : `xG ${expectedHomeGoals.toFixed(2)} : ${expectedAwayGoals.toFixed(2)}`}</div>
                            </section>
                            <section class="fm-mpv-card fm-mpv-card--away">
                                <h4 class="fm-mpv-label">Away edge</h4>
                                <div class="fm-mpv-team">${htmlEscape(awayTeamName)}</div>
                                <div class="fm-mpv-sub">${edgeLine(awayEdgeOvr, 0)}</div>
                            </section>
                        </div>

                        <div class="fm-mpv-row-2">
                            <section class="fm-mpv-card">
                                <h4 class="fm-mpv-label">Squad fit</h4>
                                <div class="fm-mpv-vs">
                                    <div>
                                        <div class="fm-mpv-sub">${htmlEscape(homeTeamName)}</div>
                                        <strong>${htmlEscape(String(previewPayload?.homeFormation || '–'))}</strong>
                                        <div class="fm-mpv-sub">${withUnit(pctOfFraction(homeFormationFitness), '% fit')}</div>
                                        <div class="fm-mpv-sub">${withUnit(fixed1(homeBenchQuality), ' bench')}</div>
                                    </div>
                                    <div class="fm-mpv-vs-right">
                                        <div class="fm-mpv-sub">${htmlEscape(awayTeamName)}</div>
                                        <strong>${htmlEscape(String(previewPayload?.awayFormation || '–'))}</strong>
                                        <div class="fm-mpv-sub">${withUnit(pctOfFraction(awayFormationFitness), '% fit')}</div>
                                        <div class="fm-mpv-sub">${withUnit(fixed1(awayBenchQuality), ' bench')}</div>
                                    </div>
                                </div>
                                <div class="fm-mpv-rule">${pct(homeAvailabilityScore) === null ? '' : `Availability ${pct(homeAvailabilityScore)}% vs ${pct(awayAvailabilityScore)}%`}</div>
                                <div class="fm-mpv-sub">${previewPayload?.homePositionMismatches === null ? '' : `Position mismatches ${numberOr(previewPayload?.homePositionMismatches, 0)} : ${numberOr(previewPayload?.awayPositionMismatches, 0)}`}</div>
                                <div class="fm-mpv-sub fm-mpv-sub--faint">${previewPayload?.homePlayStyle ? `${htmlEscape(String(previewPayload.homePlayStyle))} vs ${htmlEscape(String(previewPayload.awayPlayStyle || ''))}` : ''}</div>
                            </section>
                            <section class="fm-mpv-card">
                                <h4 class="fm-mpv-label">Why this prediction</h4>
                                <ul class="fm-mpv-list">${predictionReasons.map(reason => `<li>${htmlEscape(String(reason))}</li>`).join('')}</ul>
                                <!-- analysisText is deliberately NOT rendered here.

                                     MatchPreviewService.reasonsFor already appends prediction.analysis()
                                     to the reasons list, and analysisText is that same string. Rendering
                                     both put "Away edge · OVR 38:82 - form 7.1:4.6" on the card twice, under
                                     two different headings, which read as two separate findings.

                                     The list is the single source for this card. analysisText stays in the
                                     payload because the standalone ZOX page renders the list only and has no
                                     other use for it - removing it from the API would break that page for no
                                     gain.

                                     NOTE, and this cost a browser session to find: no backticks in here.
                                     This comment sits inside a JavaScript template literal, so a single
                                     backtick terminates the literal early and the whole module fails to
                                     parse with "Unexpected identifier". node --check does NOT catch it -
                                     it passes as a CommonJS script and fails only as an ES module, which
                                     is why every source-scan guard in this repository stayed green while
                                     the app was broken. See ModuleBackticksInTemplateTest. -->
                                <div class="fm-mpv-sub fm-mpv-sub--faint">${
                                    analysis && !predictionReasons.includes(analysis) ? analysis : ''
                                }</div>
                            </section>
                        </div>

                        <div class="fm-mpv-row-3">
                            <section class="fm-mpv-card">
                                <h4 class="fm-mpv-label">${htmlEscape(homeTeamName)} readiness</h4>
                                ${renderInsights(homeInsights)}
                            </section>
                            <section class="fm-mpv-card">
                                <h4 class="fm-mpv-label">${htmlEscape(awayTeamName)} readiness</h4>
                                ${renderInsights(awayInsights)}
                            </section>
                            <section class="fm-mpv-card">
                                <h4 class="fm-mpv-label">Absences</h4>
                                <div class="fm-mpv-sub">${htmlEscape(homeTeamName)}</div>
                                ${renderAbsentees(homeAbsentees)}
                                <div class="fm-mpv-sub fm-mpv-sub--gap">${htmlEscape(awayTeamName)}</div>
                                ${renderAbsentees(awayAbsentees)}
                            </section>
                        </div>
                    </div>`;
            }

            async function showPreview() {
                if (cachedMatchPreview) {
                    renderMatchPreview(cachedMatchPreview);
                    return;
                }
                infoDiv.innerHTML = `<p style="color:#aaa; text-align:center; padding:30px;">Loading preview...</p>`;
                try {
                    const response2 = await authFetch(
                        isFixture ? `/api/zox/fixture-preview/${matchId}` : `/api/zox/match-preview/${matchId}`);
                    cachedMatchPreview = await response2.json();
                    renderMatchPreview(cachedMatchPreview);
                } catch (error) {
                    console.error('Failed to load match preview:', error);
                    infoDiv.innerHTML = `<p style="color:#ffb3b3; text-align:center; padding:30px;">Match preview is not available for this match.</p>`;
                }
            }

            /**
             * One side's shootout kicks, each with its number and whether it went in.
             *
             * <p>The marker is the whole point. A list of numbers reading "6 7 5 6 7" is not a shootout,
             * and a tick next to a miss is the only thing that makes the sequence legible — which kick was
             * taken when the tie was still open, and which one killed it.
             */
            function penaltyColumn(teamLabel, kicks) {
                return `
                    <div>
                        <h4 style="margin:0 0 8px; color:#dfe6eb;">${teamLabel}</h4>
                        ${kicks.length ? kicks.map(kick => `
                            <div class="fm-penalty-kick${kick.scored ? '' : ' is-missed'}">
                                <span class="fm-penalty-marker">${kick.scored ? '&#9679;' : '&#10008;'}</span>
                                <span class="fm-penalty-taker">${htmlEscape(String(kick.playerName || 'Unknown'))}</span>
                            </div>`).join('') : '<div style="color:#9aa0a6;">No kicks recorded.</div>'}
                    </div>`;
            }

            function renderMatchReport(reportPayload) {
                const headline = htmlEscape(String(reportPayload?.headline || 'Match Report'));
                const reportText = htmlEscape(String(reportPayload?.summary || 'No match report available.'));
                const motm = reportPayload?.playerOfTheMatch || null;
                const timeline = Array.isArray(reportPayload?.timeline) ? reportPayload.timeline : [];
                const stats = reportPayload?.stats || {};
                const homeTop = Array.isArray(reportPayload?.homeTopPerformers) ? reportPayload.homeTopPerformers : [];
                const awayTop = Array.isArray(reportPayload?.awayTopPerformers) ? reportPayload.awayTopPerformers : [];
                // The shootout: every kick, scored and missed alike, in the order it was taken. The
                // owner's words: *"po pravilima treba da budu penali a nema ih nigde u izvestaju"*
                // and *"ubacis negde, recimo u goals i pogodjene i promasene penale"*. A miss is half
                // of what a shootout is; showing only the conversions would hide how the tie was won.
                const homePens = Array.isArray(reportPayload?.homePenalties) ? reportPayload.homePenalties : [];
                const awayPens = Array.isArray(reportPayload?.awayPenalties) ? reportPayload.awayPenalties : [];
                const penaltyBlock = (homePens.length || awayPens.length) ? `
                    <div class="fm-penalty-block">
                        <div class="fm-milestone-kicker">Penalty shootout</div>
                        <p class="fm-subtle">${htmlEscape(homeTeamName)} ${Number(reportPayload?.homePenaltyGoals ?? 0)} — ${Number(reportPayload?.awayPenaltyGoals ?? 0)} ${htmlEscape(awayTeamName)}</p>
                        <div class="fm-penalty-columns">
                            ${penaltyColumn(htmlEscape(homeTeamName), homePens)}
                            ${penaltyColumn(htmlEscape(awayTeamName), awayPens)}
                        </div>
                    </div>` : '';
                const motmFacts = [];
                if (Number.isFinite(Number(motm?.rating10))) motmFacts.push(`${Number(motm.rating10).toFixed(1)} rating`);
                if (Number(motm?.goals) > 0) motmFacts.push(`${Number(motm.goals)} goal${Number(motm.goals) === 1 ? '' : 's'}`);
                if (Number(motm?.assists) > 0) motmFacts.push(`${Number(motm.assists)} assist${Number(motm.assists) === 1 ? '' : 's'}`);
                if (Number(motm?.saves) > 0) motmFacts.push(`${Number(motm.saves)} save${Number(motm.saves) === 1 ? '' : 's'}`);
                if (Number(motm?.interceptions) > 0) motmFacts.push(`${Number(motm.interceptions)} interceptions`);
                if (Number(motm?.minutesPlayed) > 0) motmFacts.push(`${Number(motm.minutesPlayed)} min`);
                if (motm?.cleanSheet) motmFacts.push('clean sheet');

                const motmPlayerLabel = motm?.playerId && motm?.teamId
                    ? `<span class="cs-clickable" onclick="loadLeagueTeamPlayer(${Number(motm.playerId)}, ${Number(motm.teamId)}, '${htmlEscape(motm.teamName || 'Team')}')">${htmlEscape(motm.playerName || 'Unknown')}</span>`
                    : htmlEscape(String(motm?.playerName || 'Unknown'));
                const motmTeamLabel = motm?.teamId
                    ? `<span class="cs-clickable" onclick="loadLeagueTeam(${Number(motm.teamId)}, '${htmlEscape(motm.teamName || 'Team')}')">${htmlEscape(motm.teamName || 'Unknown')}</span>`
                    : htmlEscape(String(motm?.teamName || 'Unknown'));
                const motmBlock = motm ? `
                    <div class="fm-match-report-motm">
                        <div class="fm-match-report-motm-top">
                            <div>
                                <div class="fm-milestone-kicker">Man of the Match</div>
                                <div class="fm-match-report-motm-name">${motmPlayerLabel}</div>
                            </div>
                            <div class="fm-match-report-motm-team">${motmTeamLabel}</div>
                        </div>
                        <div class="fm-match-report-motm-meta">${htmlEscape(motmFacts.join(' · ') || 'Best overall performance recorded for this match.')}</div>
                    </div>` : '';

                infoDiv.innerHTML = `
                    <div class="fm-match-report-shell">
                        <h3 style="text-align:center; margin:0 0 14px; color:#4CAF50;">Match Report</h3>
                        <div class="fm-match-report-headline">${headline}</div>
                        ${motmBlock}
                        ${penaltyBlock}
                        <div class="fm-match-report-body">${reportText}</div>
                        <div style="margin-top:14px; color:#9aa0a6;">${htmlEscape(String(reportPayload?.turningPoint || ''))}</div>
                        <div style="margin-top:8px; color:#9aa0a6;">${htmlEscape(String(reportPayload?.tacticalVerdict || ''))}</div>
                        <div style="margin-top:18px; display:grid; grid-template-columns:repeat(auto-fit, minmax(220px, 1fr)); gap:14px;">
                            <div style="padding:14px; border-radius:12px; background:rgba(255,255,255,0.04);">
                                <h4 style="margin:0 0 10px; color:#dfe6eb;">Top ${htmlEscape(homeTeamName)}</h4>
                                ${homeTop.length ? homeTop.map(player => `<div style="padding:8px 0; border-bottom:1px solid rgba(255,255,255,0.06);"><strong>${htmlEscape(String(player.playerName || 'Unknown'))}</strong><div style="color:#9aa0a6; font-size:0.88em;">${htmlEscape(String(player.summary || 'Match contribution logged'))}</div></div>`).join('') : '<div style="color:#9aa0a6;">No top performers logged.</div>'}
                            </div>
                            <div style="padding:14px; border-radius:12px; background:rgba(255,255,255,0.04);">
                                <h4 style="margin:0 0 10px; color:#dfe6eb;">Top ${htmlEscape(awayTeamName)}</h4>
                                ${awayTop.length ? awayTop.map(player => `<div style="padding:8px 0; border-bottom:1px solid rgba(255,255,255,0.06);"><strong>${htmlEscape(String(player.playerName || 'Unknown'))}</strong><div style="color:#9aa0a6; font-size:0.88em;">${htmlEscape(String(player.summary || 'Match contribution logged'))}</div></div>`).join('') : '<div style="color:#9aa0a6;">No top performers logged.</div>'}
                            </div>
                        </div>
                        <div style="margin-top:16px; padding:14px; border-radius:12px; background:rgba(255,255,255,0.04);">
                            <h4 style="margin:0 0 10px; color:#dfe6eb;">Team stats</h4>
                            <table style="width:100%; border-collapse:collapse;">
                                <tbody>
                                    <tr><td style="padding:8px 0;">Possession</td><td style="text-align:center;">${Number(stats.homePossession || 0).toFixed(0)}%</td><td style="text-align:center;">${Number(stats.awayPossession || 0).toFixed(0)}%</td></tr>
                                    <tr><td style="padding:8px 0;">xG</td><td style="text-align:center;">${Number(stats.homeExpectedGoals || 0).toFixed(2)}</td><td style="text-align:center;">${Number(stats.awayExpectedGoals || 0).toFixed(2)}</td></tr>
                                    <tr><td style="padding:8px 0;">Shots on target</td><td style="text-align:center;">${Number(stats.homeShotsOnTarget || 0)}</td><td style="text-align:center;">${Number(stats.awayShotsOnTarget || 0)}</td></tr>
                                    <tr><td style="padding:8px 0;">Pass accuracy</td><td style="text-align:center;">${Number(stats.homePassAccuracy || 0).toFixed(0)}%</td><td style="text-align:center;">${Number(stats.awayPassAccuracy || 0).toFixed(0)}%</td></tr>
                                    <tr><td style="padding:8px 0;">Corners</td><td style="text-align:center;">${Number(stats.homeCorners || 0)}</td><td style="text-align:center;">${Number(stats.awayCorners || 0)}</td></tr>
                                    <tr><td style="padding:8px 0;">Penalties awarded</td><td style="text-align:center;">${Number(stats.homePenalties || 0)}</td><td style="text-align:center;">${Number(stats.awayPenalties || 0)}</td></tr>
                                </tbody>
                            </table>
                        </div>
                        <div style="margin-top:16px; padding:14px; border-radius:12px; background:rgba(255,255,255,0.04);">
                            <h4 style="margin:0 0 10px; color:#dfe6eb;">Timeline</h4>
                            ${timeline.length
                                ? timeline.map(event => `<div class="fm-timeline-row">
                                       <strong class="fm-timeline-minute">${Number(event.minute || 0)}'</strong>
                                       <span class="fm-timeline-icon fm-timeline-icon--${htmlEscape(String(event.iconKind || 'other'))}"
                                             title="${htmlEscape(String(event.iconKind || ''))}">${htmlEscape(String(event.icon || '•'))}</span>
                                       <div class="fm-timeline-body">
                                           <strong>${htmlEscape(String(event.title || 'Event'))}</strong>
                                           <div class="fm-timeline-sub">${htmlEscape(String(event.teamName || ''))}${event.detail ? ' · ' + htmlEscape(String(event.detail)) : ''}</div>
                                       </div>
                                   </div>`).join('')
                                : '<div style="color:#9aa0a6;">No key events logged.</div>'}
                        </div>
                    </div>`;
            }

            async function showMatchReport() {
                await revealMatchResultIfAllowed();
                if (cachedMatchReport) {
                    renderMatchReport(cachedMatchReport);
                    return;
                }
                // Same rule: a match-only endpoint needs a MATCH id.
                if (!hasPlayedMatch) {
                    infoDiv.innerHTML = `<p style="color:#aaa; text-align:center; padding:30px;">There is no match report until the match has been played.</p>`;
                    return;
                }
                infoDiv.innerHTML = `<p style="color:#aaa; text-align:center; padding:30px;">Loading match report...</p>`;
                try {
                    const response2 = await authFetch(`/api/zox/post-match-report/${playedMatchId}`);
                    cachedMatchReport = await response2.json();
                    renderMatchReport(cachedMatchReport);
                } catch (error) {
                    console.error('Failed to load match report:', error);
                    infoDiv.innerHTML = `<p style="color:#ffb3b3; text-align:center; padding:30px;">Match report is not available for this match.</p>`;
                }
            }

            /**
             * Field tilt and PPDA, each with the reading that makes it usable (T0-UI-6).
             *
             * <p>Both are shown only when the server actually measured them. A match whose stored stats
             * predate these counts says so, because a 0.0 would read as a fact about the game rather than
             * a gap in a column — and a number the manager cannot interpret is one he will not act on.
             *
             * <p>Written as pure functions taking the payload, so the reading can be checked without a
             * browser.
             */
            function buildAnalyticsPanels(payload) {
                const num = (v) => (v === null || v === undefined || Number.isNaN(Number(v)) ? null : Number(v));
                const homeName = htmlEscape(String(payload.homeTeamName ?? 'Home'));
                const awayName = htmlEscape(String(payload.awayTeamName ?? 'Away'));

                const panels = [];

                const homeTilt = num(payload.homeAttackShare);
                const awayTilt = num(payload.awayAttackShare);
                if (homeTilt !== null && awayTilt !== null) {
                    panels.push(`
                        <section class="fm-analytics-panel">
                            <h4>Field tilt</h4>
                            <div class="fm-analytics-row">
                                <span>${homeName}</span>
                                <strong>${homeTilt.toFixed(0)}%</strong>
                            </div>
                            <div class="fm-analytics-row">
                                <span>${awayName}</span>
                                <strong>${awayTilt.toFixed(0)}%</strong>
                            </div>
                            <p class="fm-analytics-reading">${htmlEscape(readFieldTilt(homeTilt, awayTilt))}</p>
                            <p class="fm-analytics-def">${htmlEscape(String(payload.tiltDefinition ?? ''))}</p>
                        </section>`);
                }

                const homePpda = num(payload.homePpda);
                const awayPpda = num(payload.awayPpda);
                // Named individually. If PPDA is missing for this match while tilt is present, saying
                // nothing about it reads as "there is nothing to say" rather than "we did not measure it" —
                // and the 235 matches already in the world are exactly that case.
                const missing = [];
                if (homePpda === null && awayPpda === null) missing.push('PPDA');
                if (panels.length && missing.length) {
                    panels.push(`
                        <section class="fm-analytics-panel fm-analytics-panel--missing">
                            <h4>${htmlEscape(missing.join(' and '))}</h4>
                            <p class="fm-analytics-reading">Not recorded for this match — it was played
                                before these numbers were kept. A new match will carry them.</p>
                        </section>`);
                }
                if (homePpda !== null || awayPpda !== null) {
                    panels.push(`
                        <section class="fm-analytics-panel">
                            <h4>PPDA</h4>
                            <div class="fm-analytics-row">
                                <span>${homeName}</span>
                                <strong>${homePpda === null ? '—' : homePpda.toFixed(1)}</strong>
                            </div>
                            <div class="fm-analytics-row">
                                <span>${awayName}</span>
                                <strong>${awayPpda === null ? '—' : awayPpda.toFixed(1)}</strong>
                            </div>
                            ${homePpda !== null && awayPpda !== null
                                ? `<p class="fm-analytics-reading">${htmlEscape(readPpda(homePpda, awayPpda))}</p>`
                                : ''}
                            <p class="fm-analytics-def">${htmlEscape(String(payload.definition ?? ''))}</p>
                        </section>`);
                }

                if (!panels.length) {
                    return `<p class="fm-subtle">The deeper numbers for this match were not recorded — it
                        was played before they existed. Possession, shots and xG below are unaffected.</p>`;
                }
                return `<div class="fm-analytics">${panels.join('')}</div>`;
            }

            /** The sentence that turns a percentage into something a manager can act on. */
            function readFieldTilt(home, away) {
                const gap = Math.abs(home - away);
                const leader = home >= away ? 'The home side' : 'The away side';
                if (gap < 3) {
                    return 'Both sides spent about as long in the opposition half. The result came from what '
                        + 'they did with it, not where they stood.';
                }
                const leaderShare = Math.max(home, away);
                const side = home >= away ? 'home' : 'away';
                return `${leader} spent ${leaderShare.toFixed(0)}% of the match in the opposition's third — `
                    + `a ${gap.toFixed(0)}-point ${side} tilt.`;
            }

            /** Higher means the opposition had to work harder to stop them. */
            function readPpda(home, away) {
                const gap = home - away;
                const better = gap >= 0 ? 'home' : 'away';
                if (Math.abs(gap) < 0.5) {
                    return 'Both sides completed about the same number of passes per defensive action — '
                        + 'neither was put under sustained pressure.';
                }
                return `${better === 'home' ? 'Home' : 'Away'} needed `
                    + `${Math.abs(gap).toFixed(1)} more passes per defensive action to win the ball back. `
                    + `${gap >= 0 ? 'Home' : 'Away'} was pressed harder.`;
            }

            async function showStats() {
                // Canonical stats come from the engine's statsJson (statsMap),
                // read verbatim by /api/zox/match-stats/{id}. Deriving them from
                // the detail events (eventJson) yields zeros for engine matches
                // because eventJson only carries GOAL events.
                // A match-only endpoint, so it needs a MATCH id. Passing the fixture id returned
                // another fixture's statistics - see the resolution at the top of this function.
                if (!hasPlayedMatch) {
                    infoDiv.innerHTML = `<p style="color:#aaa; text-align:center; padding:30px;">Statistics are recorded once the match has been played.</p>`;
                    return;
                }
                let payload = null;
                try {
                    const resp = await authFetch(`/api/zox/match-stats/${playedMatchId}`);
                    if (resp.ok) payload = await resp.json();
                } catch (error) {
                    console.error('Failed to load match stats:', error);
                }
                if (!payload || typeof payload !== 'object') {
                    infoDiv.innerHTML = `<p style="color:#ffb3b3; text-align:center; padding:30px;">Match stats are not available for this match.</p>`;
                    return;
                }

                const n = (v, fallback = 0) => {
                    const num = Number(v);
                    return Number.isFinite(num) ? num : fallback;
                };
                const homeShotsOn = n(payload.homeShotsOnTarget);
                const awayShotsOn = n(payload.awayShotsOnTarget);
                const homeShotsOff = n(payload.homeShotsOffTarget);
                const awayShotsOff = n(payload.awayShotsOffTarget);

                const rows = [
                    ['Possession', n(payload.homePossession).toFixed(0) + '%', n(payload.awayPossession).toFixed(0) + '%'],
                    ['xG', n(payload.homeExpectedGoals).toFixed(2), n(payload.awayExpectedGoals).toFixed(2)],
                    ['Shots', homeShotsOn + homeShotsOff, awayShotsOn + awayShotsOff],
                    ['Shots on target', homeShotsOn, awayShotsOn],
                    ['Shots off target', homeShotsOff, awayShotsOff],
                    ['Pass accuracy', n(payload.homePassAccuracy).toFixed(0) + '%', n(payload.awayPassAccuracy).toFixed(0) + '%'],
                    ['Corners', n(payload.homeCorners), n(payload.awayCorners)],
                    ['Offsides', n(payload.homeOffsides), n(payload.awayOffsides)],
                    ['Yellow cards', n(payload.homeYellowCards), n(payload.awayYellowCards)],
                    ['Red cards', n(payload.homeRedCards), n(payload.awayRedCards)],
                    ['Penalties awarded', n(payload.homePenalties), n(payload.awayPenalties)],
                    ['Fouls', n(payload.homeFouls), n(payload.awayFouls)],
                ];
                const body = rows.map(([label, homeVal, awayVal], i) => {
                    const zebra = i % 2 === 1 ? ' style="background:rgba(255,255,255,0.04);"' : '';
                    const accent = label === 'Yellow cards' ? 'color:#ff9800;'
                        : label === 'Red cards' ? 'color:#f44336;'
                        : label === 'Possession' || label === 'Shots on target' ? 'font-weight:bold;'
                        : '';
                    return `<tr${zebra}><td style="padding:10px;">${label}</td><td style="text-align:center;${accent}">${homeVal}</td><td style="text-align:center;${accent}">${awayVal}</td></tr>`;
                }).join('');

                let html = `<h3 style="text-align:center; margin:0 0 20px; color:#4CAF50;">Match Stats</h3>`;
                html += buildAnalyticsPanels(payload);
                html += `
                <table style="width:100%; border-collapse:collapse; font-size:0.95em;">
                    <thead>
                        <tr style="background:rgba(76,175,80,0.15);">
                            <th style="padding:12px; text-align:left;">Stats</th>
                            <th style="padding:12px; text-align:center;">${homeTeamId ? `<span class="cs-clickable" onclick="loadLeagueTeam(${homeTeamId}, '${htmlEscape(homeTeamName)}')">${homeTeamName}</span>` : homeTeamName}</th>
                            <th style="padding:12px; text-align:center;">${awayTeamId ? `<span class="cs-clickable" onclick="loadLeagueTeam(${awayTeamId}, '${htmlEscape(awayTeamName)}')">${awayTeamName}</span>` : awayTeamName}</th>
                        </tr>
                    </thead>
                    <tbody>${body}</tbody>
                </table>`;
                infoDiv.innerHTML = html;
            }


    /**
     * The manager's own club id, resolved rather than merely read.
     *
     * <p>The three panels below are all gated on "is this my club", and all three used `getTeamId()`,
     * which returns a variable that is `null` until `/auth/me` has answered. Opening a fixture from a
     * cold page therefore mounted nothing: no panel, no error, no warning — just an empty host. The
     * endpoints were fine and the guard was doing its job on a value that had not arrived yet.
     *
     * <p>`deps.ensureTeamId` is the existing `ensureUserTeamId()`, which loads the user context if it
     * is not already in hand. Awaiting it turns a silent no-op into a panel, and costs one request only
     * when the id is genuinely unknown.
     */
    async function resolveTeamId() {
        const resolved = await deps.ensureTeamId?.();
        return resolved ?? getTeamId?.() ?? null;
    }

            if (initialTab === 'report') void showMatchReport();
            else if (initialTab === 'goals') showGoals();
            else void showPreview();

            if (initialTab === 'setup') void mountSetupPanels();

            // **Only for the Match Setup tab.** They were mounted on every visit, which is how a
            // preview page came to carry three editable panels nobody put there on purpose.
            async function mountSetupPanels() {
                const host = document.getElementById('fm-match-setup');
                if (!host) return;
                // After a match is played there is nothing to set, and the tab says so rather than
                // offering controls that would be refused.
                if (hasPlayedMatch) {
                    host.innerHTML = `<p class="fm-subtle">This match has been played. Its lineup,
                        tactics and orders are part of the record now.</p>`;
                    return;
                }
                // **Each panel gets a heading and reports its own failure.**
                //
                // All three mounts swallow their errors into `console.warn` — which is right for a panel
                // on a working page and wrong here. Three swallowed failures leave an empty container,
                // so the owner's report was "Match Setup shows nothing on this page": a blank tab with no
                // heading, no panel and no message, from three loaders that were told to stay quiet.
                // The same club menu every other club page carries.
                //
                // Owner request, 2026-10-12: this tab should open the identical menu as
                // Club - Tactics. A manager who wants to change one of the three tactics has to be able
                // to go and build it from here, or he has to go back, remember where he was, and come
                // back - and the tab he was setting up is the one that loses its state.
                //
                // It is written here rather than into the host's own markup because this function
                // replaces the host wholesale, which is why the first attempt at this put the menu in
                // the template and then rendered nothing at all.
                const clubMenu = deps.buildClubActionsHtml ? deps.buildClubActionsHtml('tacticEditor') : '';
                host.innerHTML = `
                    ${clubMenu}
                    <p class="fm-subtle" style="margin-bottom:14px;">
                        Set the eleven, the tactics and the orders for this match. Everything here is
                        closed an hour before kickoff, when the match is decided.
                    </p>
                    ${PANELS.map(({ host: id, title, load }) => `
                        <section class="fm-panel fm-setup-section">
                            <div class="fm-panel-head">${title}</div>
                            <div class="fm-setup-section-body" id="${id}">
                                <p class="fm-subtle">Loading…</p>
                            </div>
                        </section>`).join('')}`;

                // Sequential, and each one guarded: one panel failing must not leave the other two blank.
                for (const panel of PANELS) {
                    const body = document.getElementById(panel.host);
                    if (!body) continue;
                    try {
                        await panel.load(body);
                    } catch (error) {
                        body.innerHTML = `<p class="fm-setup-error">${titleOf(panel)} could not be
                            loaded: ${htmlEscape(error && error.message ? error.message : String(error))}</p>`;
                    }
                }
            }

            /**
             * The things a manager sets before kickoff, in the order a manager does them.
             *
             * <p><b>There was a fifth and it was a duplicate.</b> "Game plan" held three numbered slots
             * — first, second, third choice — each with a tactic, a condition and a from-minute. "Tactic
             * orders" holds ten rows of those same three fields. Same data at two sizes, and the owner
             * read the two panels and said they look like the same thing (2026-10-16). The plan is gone
             * from this screen and orders stand alone.
             *
             * <p>Nothing is lost by it. An order may name any of the club's own tactics, not only three
             * of them, and `MatchTacticsPreparationService` already resolves a fixture that carries orders
             * and no plan: the default shape holds until the first order fires. The plan's PUT, its
             * reorder endpoint and `MatchTacticAssignment` are still there and the engine still reads
             * them — this only stops the screen offering the same thing twice.
             */
            const PANELS = [
                { host: 'fm-match-lineup', title: 'The eleven', load: (body) => mountLineup(body) },
                { host: 'fm-tactic-orders', title: 'Tactic orders', load: (body) => mountTacticOrders(body) },
                { host: 'fm-substitution-plan', title: 'Substitutions', load: (body) => mountSubstitutionPlan(body) },
            ];

            function titleOf(panel) {
                return panel.title;
            }

            /**
             * Puts the conditional-substitution plan on this screen, which is where a manager actually is.
             *
             * <p><b>This used to live on the fixture sheet, and the fixture sheet is gone.</b> Every
             * fixture routes here — a played one directly, an unplayed one with `fixture: true` — so the
             * panel was mounted on a page no manager could reach and the whole feature was dead code
             * (T0-UI-4b). The module is unchanged; only where it is mounted moved.
             *
             * <p><b>Only for the home club.</b> The plan is the home club's instruction and the engine
             * reads it for the home side, so offering it on an away fixture would be a control with no
             * meaning. The test is on the club <em>id</em>, never on the name.
             *
             * <p>Failures are swallowed on purpose: the plan is one panel on a working screen, and a plan
             * that will not load must not take the match down with it.
             */
            /**
             * The conditional tactics for this fixture, on the same panel row as the substitution plan.
             *
             * <p>Mounted for the manager's own club on either side of the fixture, and only while the
             * fixture is unplayed. The server resolves the side from the session — this screen never sends
             * one — so there is no way from here to write the opposition's plan.
             */
            /**
             * The eleven for this fixture, above the game plan and the substitution rules — because a
             * substitution condition names a player, and a manager who has not picked his eleven has
             * nothing for it to refer to.
             */
            async function mountLineup(host) {
                if (!host) return;
                const managerTeamId = await resolveTeamId();
                if (!managerTeamId) {
                    host.innerHTML = '<p class="fm-setup-error">Your club could not be resolved on this page.</p>';
                    return;
                }
                try {
                    const lineupView = deps.createMatchLineupView?.();
                    if (!lineupView) throw new Error('the lineup module did not load');
                    await lineupView.loadPlan(matchId, host);
                } catch (error) {
                    console.warn('Could not load the team selection:', error);
                }
            }

            /**
             * The tactic orders panel (T-MATCH-6).
             *
             * <p>It needs the club's tactics to populate its dropdowns, and the plan already fetched them
             * for the same request - so they are read back out of the game plan's payload rather than
             * asking for them a second time.
             */
            async function mountTacticOrders(host) {
                if (!host) return;
                const managerTeamId = await resolveTeamId();
                if (!managerTeamId) {
                    host.innerHTML = '<p class="fm-setup-error">Your club could not be resolved on this page.</p>';
                    return;
                }
                try {
                    const orderView = deps.createTacticOrderView?.();
                    if (!orderView) throw new Error('the tactic orders module did not load');
                    const res = await authFetch(`/teams/${managerTeamId}/tactics`);
                    const body = await res.json();
                    await orderView.render(host, { fixtureId: matchId, tactics: body || [] });
                } catch (error) {
                    host.innerHTML = `<p class="fm-setup-error">Tactic orders could not be shown:
                        ${htmlEscape(error && error.message ? error.message : String(error))}</p>`;
                }
            }

            async function mountSubstitutionPlan(host) {
                if (!host) return;
                const managerTeamId = await resolveTeamId();
                if (!managerTeamId || !homeTeamId || Number(managerTeamId) !== Number(homeTeamId)) {
                    // Said plainly rather than left blank. "Your club is not at home in this fixture"
                    // is the answer, and a manager looking at an empty panel has no way to know it.
                    host.innerHTML = `<p class="fm-subtle">The substitution plan is the home club's
                        instruction, so it is not shown when you are away.</p>`;
                    return;
                }
                try {
                    const planView = deps.createSubstitutionPlanView?.();
                    if (!planView) throw new Error('the substitution plan module did not load');
                    // The two id spaces are kept apart on purpose. `matchId` above is a fixture id when
                    // `isFixture` is true and a match id otherwise, and passing the wrong one to the
                    // wrong endpoint is the defect the owner's P0 report was about.
                    if (isFixture) await planView.loadPlan(matchId, host, managerTeamId);
                    else await planView.loadPlanForMatch(matchId, host, managerTeamId);
                } catch (error) {
                    console.warn('Could not load the substitution plan:', error);
                }
            }

            /**
             * The tab bar: **one listener**, and the buttons are found by their id when it is asked.
             *
             * <p>Two reasons, and the second one is the owner's report (owner, 2026-10-21): a played
             * match could not be opened at all - "Error loading match: Cannot read properties of null
             * (reading 'addEventListener')" - because the code went looking for seven buttons by id
             * and one of them was not a button. A bar that is asked "which button was pressed" cannot
             * fail that way: a button that is not there is simply never pressed.
             *
             * <p>The open tab is the marked one (owner, 2026-10-20: "pa se ne zna odmah na kojojk smo
             * strani"), and the bar marks itself.
             */
            const tabBar = document.getElementById('match-buttons-container');
            const TAB_HANDLERS = {
                'view-preview': () => {
                // Leaving Match Setup empties its panels, so a preview is a preview and not a preview
                // with three editors still sitting underneath it.
                const setup = document.getElementById('fm-match-setup');
                if (setup) setup.innerHTML = '';
                void showPreview();
                },
                'view-setup': () => {
                infoDiv.innerHTML = '';
                void mountSetupPanels();
                },
                'view-lineups': () => {
                if (!lineupsPayload || (!lineupsPayload.homeLineup && !lineupsPayload.awayLineup)) {
                    infoDiv.innerHTML = `<p style="color:#aaa; text-align:center; padding:30px;">Lineups are not available for this match.</p>`;
                    return;
                }
                const seasonYear = deps.getCurrentSeasonYear?.() || null;
                const renderLineup = (teamName, teamId, players) => {
                    const sorted = [...(players || [])].sort(byPosition(9));
                    if (sorted.length === 0) return `<p class="fm-subtle">No lineup data.</p>`;
                    // **A standard table, like every other table in the app** (owner, 2026-10-20: "remove
                    // that button around player name, standard table as everywhere across app"). The
                    // player's name is plain text - it was a button that looked like a link and was
                    // not one of the app's own name links, and the heading row did not sit over its
                    // columns because the table was a grid.
                    let html2 = `
                        <section class="fm-match-lineup-team">
                            <h4 class="fm-match-lineup-title">${htmlEscape(teamName)}</h4>
                            <div class="fm-squad-wrap">
                            <table class="fm-squad fm-match-lineups-table">
                                <thead>
                                    <tr>
                                        <th>POS</th>
                                        <th>Player</th>
                                        <th>Rate</th>
                                        <th>Impact</th>
                                        <th>Min</th>
                                    </tr>
                                </thead>
                                <tbody>`;
                    sorted.forEach(p => {
                        const compactName = htmlEscape(formatCompactPlayerName(p.playerName));
                        html2 += `
                            <tr>
                                <td class="fm-match-lineup-pos">${htmlEscape(p.position || '-')}</td>
                                <td class="fm-match-lineup-player-cell"><span class="fm-match-lineup-player">${compactName}</span></td>
                                <td class="fm-match-lineup-grade">${formatRatingBadge(p.grade)}</td>
                                <td class="fm-match-lineup-badge-cell">${buildLineupEventBadges(p)}</td>
                                <td class="fm-match-lineup-min">${Number(p.minutesPlayed ?? 0)}</td>
                            </tr>`;
                    });
                    return `${html2}</tbody></table></div></section>`;
                };

                infoDiv.innerHTML = `
                    <div class="fm-match-lineups">
                        <h3 class="fm-match-lineups-title">Lineups & Grades</h3>
                        <div class="fm-match-lineups-grid">
                            ${renderLineup(lineupsPayload.homeTeam || homeTeamName, lineupsPayload.homeTeamId || 0, lineupsPayload.homeLineup || [])}
                            ${renderLineup(lineupsPayload.awayTeam || awayTeamName, lineupsPayload.awayTeamId || 0, lineupsPayload.awayLineup || [])}
                        </div>
                    </div>`;
                },
                'view-stats': () => void showStats(),
                'view-replay': () => {
                void (async () => {
                    await revealMatchResultIfAllowed();
                    // Regular matchday matches have no stored replay (storeReplay=false). The server
                    // rebuilds it on demand and hands back a recording that exists — the seed is the
                    // fixture id, so the result the manager sees is the result he already saw.
                    //
                    // **The recording id is passed as `replayId`, never as `matchId`.** The two id
                    // spaces overlap, and the viewer used to fall back from one to the other — so a
                    // recording that had been evicted out of the store opened a different match.
                    if (hasPlayedMatch && playedMatchId) {
                        try {
                            const response = await authFetch(`/api/sim/replay/${playedMatchId}`, { method: 'POST' });
                            if (response.ok) {
                                const data = await response.json();
                                if (data?.replayId) {
                                    window.location.href = `/demo/service/ui/proposal/index.html?replayId=${encodeURIComponent(data.replayId)}`;
                                    return;
                                }
                            }
                        } catch (err) {
                            console.warn('Replay re-simulation failed:', err);
                        }
                    }
                    window.location.href = `/demo/service/ui/proposal/index.html?matchId=${encodeURIComponent(matchId)}`;
                })();
                },
                'view-report': () => void showMatchReport(),
                'view-goals': () => showGoals()
            };

            tabBar?.addEventListener('click', (event) => {
                const button = event.target.closest('.fm-match-action-btn');
                if (!button || button.disabled) return;
                const handler = TAB_HANDLERS[button.id];
                if (!handler) return;
                tabBar.querySelectorAll('.fm-match-action-btn').forEach(other => other.classList.remove('is-active'));
                button.classList.add('is-active');
                handler();
            });
            // Named so `initialTab: 'goals'` can open it. It was an anonymous arrow, which is why
            // "Show results" could only ever land on the preview or the report and the owner asked
            // for the goals.
            function showGoals() {
                const goals = events.filter(e => e.eventType === "GoalEvent");
                if (goals.length === 0) {
                    infoDiv.innerHTML = `<p style="color:#aaa; text-align:center; padding:30px;">No goals in this match.</p>`;
                    return;
                }
                let html2 = `<h3 style="text-align:center; margin:0 0 20px; color:#4CAF50;">Goals</h3><ul style="list-style:none; padding:0;">`;
                goals.forEach(g => {
                    const disallowed = g.goalScored === false;
                    const lineColor = disallowed ? "#ffb3b3" : "inherit";
                    const verdict = disallowed ? ` <span style="color:#ff6b6b; font-weight:600;">DISALLOWED (VAR)</span>` : "";
                    const scorerTeamId = g.scoreTeam === homeTeamName ? homeTeamId : (g.scoreTeam === awayTeamName ? awayTeamId : null);
                    const scorerStat = (g.scoreTeam === homeTeamName ? (lineupsPayload?.homeLineup || []) : (lineupsPayload?.awayLineup || []))
                        .find(p2 => p2.playerName === g.scorer);
                    const assistStat = (g.scoreTeam === homeTeamName ? (lineupsPayload?.homeLineup || []) : (lineupsPayload?.awayLineup || []))
                        .find(p2 => p2.playerName === g.assistant);
                    const scorerLabel = scorerStat?.playerId && scorerTeamId
                        ? `<span class="cs-clickable" onclick="loadLeagueTeamPlayer(${scorerStat.playerId}, ${scorerTeamId}, '${htmlEscape(g.scoreTeam || '')}')">${g.scorer || "?"}</span>`
                        : (g.scorer || "?");
                    const assistLabel = assistStat?.playerId && scorerTeamId
                        ? `<span class="cs-clickable" onclick="loadLeagueTeamPlayer(${assistStat.playerId}, ${scorerTeamId}, '${htmlEscape(g.scoreTeam || '')}')">${g.assistant}</span>`
                        : (g.assistant || "");
                    html2 += `
                    <li style="padding:12px; margin:8px 0; background:rgba(255,255,255,0.05); border-radius:8px;">
                        <strong>${g.matchMinute}'</strong> <span style="color:${lineColor};">&#9917; ${scorerLabel}${g.assistant ? ` <span style="color:#888;">(assist: ${assistLabel})</span>` : ''}${verdict}</span>
                        <span style="float:right; color:#aaa;">${g.scoreAfterGoal || ""}</span>
                    </li>`;
                });
                html2 += `</ul>`;
                infoDiv.innerHTML = html2;
            }
        } catch (err) {
            console.error("Error loading match:", err);
            document.getElementById("main-content").innerHTML = `<div class="team-card"><p>Error loading match: ${err.message}</p></div>`;
        }
    }

    return { loadMatch };
}

/**
 * One side's shape: the eleven drawn on a pitch, and how well they fit it.
 *
 * The rows come from the formation's own digits - first is defenders, last is strikers, and anything
 * between is a midfield line, read from the back. That is the same reading the engine gives the shape,
 * so the picture and the engine cannot drift apart.
 */
function pitchRowsFor(formation) {
    const nums = String(formation || '').split('-').map(n => parseInt(n, 10)).filter(n => Number.isFinite(n));
    if (!nums.length) {
        return [];
    }
    const defenders = nums[0];
    const strikers = nums.length > 1 ? nums[nums.length - 1] : 0;
    const middle = nums.slice(1, Math.max(1, nums.length - 1));
    const rows = [];
    if (strikers > 0) rows.push({ kind: 'ST', count: strikers });
    if (middle.length === 1) rows.push({ kind: 'M', count: middle[0] });
    if (middle.length > 1) {
        for (let i = 0; i < middle.length; i++) {
            rows.push({ kind: i === 0 ? 'DM' : 'M', count: middle[i] });
        }
    }
    rows.push({ kind: 'D', count: defenders });
    rows.push({ kind: 'GK', count: 1 });
    return rows;
}

function shapePanel(side, teamName, formation, fitness, benchQuality, mismatches, hasLineup) {
    // `fixed1` and `pctOfFraction` are locals of the renderer above, so they are repeated rather than
    // hoisted: moving them would touch every other card in the preview for no benefit here.
    const one = value => (value === null || value === undefined ? null : Number(value).toFixed(1));
    const asPercent = value => (value === null || value === undefined ? null : `${Math.round(Number(value) * 100)}`);
    const rows = pitchRowsFor(formation);
    const pitch = rows.length
        ? `<div class="fm-mpv-pitch">${rows.map(row => `
                <div class="fm-mpv-line fm-mpv-line--${row.kind.toLowerCase()}">
                    ${Array.from({ length: row.count }, () => '<span class="fm-mpv-dot"></span>').join('')}
                </div>`).join('')}
            </div>`
        : '<div class="fm-mpv-sub">Shape not known</div>';

    // "Not picked" and "picked and fits perfectly" are different things, and a dash would hide the first
    // behind the second. Nothing chosen reads as nothing chosen.
    const fit = fitness === null || fitness === undefined
        ? 'Eleven not picked yet'
        : `${asPercent(fitness)}% fit${mismatches > 0 ? ` &middot; ${mismatches} out of place` : ''}`;
    const bench = benchQuality === null || benchQuality === undefined
        ? 'No bench named'
        : `${one(benchQuality)} bench`;

    return `
        <section class="fm-mpv-shape fm-mpv-shape--${side.toLowerCase()}">
            <header class="fm-mpv-shape-head">
                <span class="fm-mpv-label">${htmlEscape(side)}</span>
                <span class="fm-mpv-shape-formation">${htmlEscape(String(formation || '–'))}</span>
            </header>
            <div class="fm-mpv-shape-team">${htmlEscape(String(teamName || ''))}</div>
            ${pitch}
            <div class="fm-mpv-shape-fit">${fit}</div>
            <div class="fm-mpv-sub">${bench}</div>
        </section>`;
}
