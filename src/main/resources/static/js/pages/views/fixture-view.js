// pages/views/fixture-view.js
import { htmlEscape, formatDateTimeLabel } from './utils.js';

export function createFixtureView(deps) {
    const {
        authFetch, getTeamId, ensureCurrentLeagueId, getCurrentLeagueBackTarget,
        getCurrentLeagueName, getLeagueSeasonYear, getSeasonYear,
        pushNavState, getActiveLeagueNavState, goBackSmart,
        buildClubActionsHtml, loadMatch, matchesFeature,
        renderFixturesView, renderMatches
    } = deps;

    async function loadUpcomingMatches() {
        const teamId = getTeamId();
        if (!teamId) return;
        console.log(`Loading upcoming matches for ${teamId}`);
        const response = await authFetch(`/demo/matches/teams/${teamId}/upcoming`);
        console.log(`Response status: ${response.status}`);
        const matches = await response.json();
        renderMatches(matches, "Upcoming Matches");
    }

    async function loadFixtures() {
        const teamId = getTeamId();
        if (!teamId) return;
        return matchesFeature.loadFixtures();
    }

    function resolveFixtureStadiumImage(fixture) {
        const venueKey = String(fixture?.stadium || fixture?.stadiumName || '').toLowerCase();
        if (venueKey.includes('livadice')) return '/images/livadice.png';
        if (venueKey.includes('dunjareal')) return '/images/dunjareal.png';
        if (venueKey.includes('bilino')) return '/images/bilinopolje.png';
        return '/images/default-stadium.png';
    }

    async function findFixtureRow(fixtureId) {
        const teamId = getTeamId();
        const numericFixtureId = Number(fixtureId);
        if (!Number.isFinite(numericFixtureId)) return null;

        const scheduleLoaders = [
            async () => {
                const response = await authFetch(`/teams/${teamId}/schedule`);
                return response.ok ? response.json() : [];
            },
            async () => {
                const leagueId = await ensureCurrentLeagueId();
                if (!leagueId) return [];
                const seasonParam = getLeagueSeasonYear() ? `?seasonYear=${getLeagueSeasonYear()}` : '';
                const response = await authFetch(`/countries/leagues/${leagueId}/schedule${seasonParam}`);
                return response.ok ? response.json() : [];
            }
        ];

        for (const loadSchedule of scheduleLoaders) {
            try {
                const rows = await loadSchedule();
                const fixture = (Array.isArray(rows) ? rows : [])
                    .find(row => Number(row?.fixtureId ?? row?.id) === numericFixtureId);
                if (fixture) return fixture;
            } catch (err) {
                console.warn(`Fixture lookup failed for ${numericFixtureId}:`, err);
            }
        }

        return null;
    }

    async function loadFixture(fixtureId, options = {}) {
        const pushHistory = options.pushHistory !== false;
        const backTarget = options.backTarget || 'fixtures';
        if (pushHistory) pushNavState({ type: 'fixture', fixtureId, backTarget, ...getActiveLeagueNavState() });
        const mainContent = document.getElementById("main-content");
        const teamId = getTeamId();
        if (!teamId) return;
        console.log(`Loading fixture ID: ${fixtureId}`);

        try {
            const fixture = await findFixtureRow(fixtureId);
            if (!fixture) {
                mainContent.innerHTML = `
                    <div class="fm-page fm-page--club">
                        <section class="fm-panel fm-club-hero">
                            <button class="back-to-dashboard" data-nav-back="${backTarget}">Back</button>
                            <div class="fm-club-hero-main">
                                <div>
                                    <div class="fm-eyebrow">Club schedule</div>
                                    <h2>Fixture unavailable</h2>
                                    <p class="fm-subtle">We could not load the requested fixture.</p>
                                </div>
                                ${buildClubActionsHtml('schedule')}
                            </div>
                        </section>
                        <section class="fm-panel"><div class="fm-empty">Fixture not found.</div></section>
                    </div>`;
                return;
            }

            const parsedMatchDate = fixture?.matchDate
                ? new Date(String(fixture.matchDate).includes('T') ? String(fixture.matchDate) : String(fixture.matchDate).replace(' ', 'T'))
                : null;
            const hasParsedDate = parsedMatchDate instanceof Date && !Number.isNaN(parsedMatchDate.getTime());
            const homeTeamName = fixture.homeTeam || "Home";
            const awayTeamName = fixture.awayTeam || "Away";
            const matchDate = hasParsedDate ? parsedMatchDate.toLocaleDateString('sr-RS') : (fixture.matchDate || 'N/A');
            const matchTime = hasParsedDate
                ? parsedMatchDate.toLocaleTimeString('sr-RS', { hour: '2-digit', minute: '2-digit' })
                : 'TBD';
            const matchDateTime = hasParsedDate ? `${matchDate} \u2022 ${matchTime}` : htmlEscape(formatDateTimeLabel(fixture.matchDate));
            const venue = fixture.stadium || fixture.stadiumName || "N/A";
            const homeStrength = Number(fixture.homeTeamStrength);
            const awayStrength = Number(fixture.awayTeamStrength);
            const homeForm = Number(fixture.homeTeamForm);
            const awayForm = Number(fixture.awayTeamForm);
            const prediction = fixture.prediction || {};
            const confidence = Number(prediction.confidence);
            const expectedHomeGoals = Number(prediction.expectedHomeGoals);
            const expectedAwayGoals = Number(prediction.expectedAwayGoals);
            const h2h = fixture.h2h || {};
            const statusLabel = fixture.played ? 'Played' : 'Scheduled';
            const previewLabel = prediction.mostLikelyResult
                ? `${prediction.mostLikelyResult.replace(/_/g, ' ')}${Number.isFinite(confidence) ? ` \u00B7 ${Math.round(confidence)}% conf` : ''}`
                : 'Preview pending';
            const mostLikelyLabel = prediction.mostLikelyResult
                ? prediction.mostLikelyResult.replace(/_/g, ' ')
                : 'Pending';
            const previewAnalysis = htmlEscape(String(prediction.analysis || 'No extra preview analysis available yet.'));

            mainContent.innerHTML = `
            <div class="fm-page fm-page--club">
                <section class="fm-panel fm-club-hero">
                    <button class="back-to-dashboard" data-nav-back="${backTarget}">Back</button>
                    <div class="fm-club-hero-main">
                        <div>
                            <div class="fm-eyebrow">Fixture preview</div>
                            <h2>${htmlEscape(homeTeamName)} vs ${htmlEscape(awayTeamName)}</h2>
                            <p class="fm-subtle">Fixture detail now resolves from the real schedule payload, so club and league schedule clicks stay in-app.</p>
                        </div>
                        ${buildClubActionsHtml('schedule')}
                    </div>
                    <div class="fm-medical-stat-grid team-summary-grid">
                        <div><strong>${htmlEscape(matchDate)}</strong><span>Date</span></div>
                        <div><strong>${htmlEscape(matchTime)}</strong><span>Kick-off</span></div>
                        <div><strong>${htmlEscape(fixture.competitionName || 'Competition')}</strong><span>Competition</span></div>
                        <div><strong>${fixture.round ? `Round ${htmlEscape(String(fixture.round))}` : '\u2014'}</strong><span>Status \u00B7 ${htmlEscape(statusLabel)}</span></div>
                    </div>
                </section>
                <div class="fm-grid-top fm-grid-top--fixture-preview">
                    <section class="fm-panel club-profile-detail-card">
                        <div class="fm-panel-head">
                            <div>
                                <h3>Fixture details</h3>
                                <p class="fm-subtle">Core schedule metadata, OVR edge, and preview summary for the selected fixture.</p>
                            </div>
                            <span class="fm-panel-action">Preview</span>
                        </div>
                        <div class="club-profile-detail-list">
                            <div class="club-profile-detail-row"><span>Home team</span><strong>${htmlEscape(homeTeamName)}</strong></div>
                            <div class="club-profile-detail-row"><span>Away team</span><strong>${htmlEscape(awayTeamName)}</strong></div>
                            <div class="club-profile-detail-row"><span>Date & time</span><strong>${htmlEscape(matchDateTime)}</strong></div>
                            <div class="club-profile-detail-row"><span>Venue</span><strong>${htmlEscape(venue)}</strong></div>
                            <div class="club-profile-detail-row"><span>Status</span><strong>${htmlEscape(statusLabel)}</strong></div>
                            <div class="club-profile-detail-row"><span>Home OVR / form</span><strong>${Number.isFinite(homeStrength) ? Math.round(homeStrength) : '\u2014'} \u00B7 ${Number.isFinite(homeForm) ? homeForm.toFixed(1) : '\u2014'}</strong></div>
                            <div class="club-profile-detail-row"><span>Away OVR / form</span><strong>${Number.isFinite(awayStrength) ? Math.round(awayStrength) : '\u2014'} \u00B7 ${Number.isFinite(awayForm) ? awayForm.toFixed(1) : '\u2014'}</strong></div>
                            <div class="club-profile-detail-row"><span>Model lean</span><strong>${htmlEscape(previewLabel)}</strong></div>
                        </div>
                    </section>
                    <section class="fm-panel club-profile-detail-card fm-fixture-preview-card">
                        <div class="fm-panel-head">
                            <div>
                                <h3>Match preview</h3>
                                <p class="fm-subtle">Prediction, projected xG, and head-to-head summary from the real schedule insight flow.</p>
                            </div>
                            <span class="fm-panel-action">${Number.isFinite(confidence) ? `${Math.round(confidence)}%` : 'Heuristic'}</span>
                        </div>
                        <div class="fm-fixture-preview-hero fx-prediction">
                            <div class="fx-prediction-head">
                                <div>
                                    <div class="fx-prediction-title">Prediction</div>
                                    <div class="fm-fixture-preview-result">${htmlEscape(mostLikelyLabel)}</div>
                                </div>
                                <span class="fx-confidence">${Number.isFinite(confidence) ? `${Math.round(confidence)}% conf` : 'Heuristic lean'}</span>
                            </div>
                            <div class="fm-fixture-preview-xg-chip">xG ${Number.isFinite(expectedHomeGoals) ? expectedHomeGoals.toFixed(2) : '\u2014'} : ${Number.isFinite(expectedAwayGoals) ? expectedAwayGoals.toFixed(2) : '\u2014'}</div>
                            <div class="fx-prediction-note">${previewAnalysis}</div>
                        </div>
                        <div class="fm-medical-stat-grid team-summary-grid fm-fixture-preview-stats">
                            <div><strong>${Number.isFinite(homeStrength) ? Math.round(homeStrength) : '\u2014'}</strong><span>${htmlEscape(homeTeamName)} OVR</span></div>
                            <div><strong>${Number.isFinite(awayStrength) ? Math.round(awayStrength) : '\u2014'}</strong><span>${htmlEscape(awayTeamName)} OVR</span></div>
                            <div><strong>${Number.isFinite(homeForm) ? homeForm.toFixed(1) : '\u2014'}</strong><span>${htmlEscape(homeTeamName)} form</span></div>
                            <div><strong>${Number.isFinite(awayForm) ? awayForm.toFixed(1) : '\u2014'}</strong><span>${htmlEscape(awayTeamName)} form</span></div>
                        </div>
                        <div class="club-profile-detail-list">
                            <div class="club-profile-detail-row fm-fixture-preview-row--accent"><span>Model lean</span><strong>${htmlEscape(previewLabel)}</strong></div>
                            <div class="club-profile-detail-row"><span>Head-to-head</span><strong>${htmlEscape(String(h2h.summary || 'No head-to-head history yet.'))}</strong></div>
                            <div class="club-profile-detail-row"><span>Last meeting</span><strong>${htmlEscape(String(h2h.lastMeetingSummary || 'First recorded meeting.'))}</strong></div>
                        </div>
                    </section>
                </div>
            </div>`;
        } catch (err) {
            console.error("Error loading fixture:", err);
            mainContent.innerHTML = `
                <div class="fm-page fm-page--club">
                    <section class="fm-panel fm-club-hero">
                        <button class="back-to-dashboard" data-nav-back="${backTarget}">Back</button>
                        <div class="fm-club-hero-main">
                            <div>
                                <div class="fm-eyebrow">Club schedule</div>
                                <h2>Fixture error</h2>
                                <p class="fm-subtle">There was a problem while loading this fixture.</p>
                            </div>
                            ${buildClubActionsHtml('schedule')}
                        </div>
                    </section>
                    <section class="fm-panel"><div class="fm-empty">Error loading fixture: ${htmlEscape(err.message)}</div></section>
                </div>`;
        }
    }

    async function loadFriendlies() {
        const teamId = getTeamId();
        console.log(`Loading friendlies for ${teamId}`);
        const response = await authFetch(`/demo/matches/teams/${teamId}/friendlies`);
        console.log(`Response status: ${response.status}`);
        const matches = await response.json();
        renderMatches(matches, "Friendlies");
    }

    function renderFixtures(fixtures, title, options = {}) {
        renderFixturesView(fixtures, title, options);
    }

    return { loadFixture, loadFixtures, loadUpcomingMatches, loadFriendlies, findFixtureRow, resolveFixtureStadiumImage, renderFixtures };
}
