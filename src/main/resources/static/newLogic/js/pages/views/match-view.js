// pages/views/match-view.js
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
        const initialTab = options.initialTab === 'report' ? 'report' : 'preview';
        console.log(`Loading match ID: ${matchId}, caller: ${caller}`);
        if (caller === "undefined") {
            console.log(`Match not found.`);
            mainContent.innerHTML = `<div class="team-card"><p>Match not found.</p></div>`;
            return;
        }
        try {
            const response = await authFetch(`/matches/${matchId}/detail`);
            console.log(`Status: ${response.status}`);

            if (!response.ok) {
                const text = await response.text();
                console.error(`Error ${response.status}: ${text}`);
                mainContent.innerHTML = `<div class="team-card"><p>Match not found.</p></div>`;
                return;
            }

            const [events, lineupsPayload] = await Promise.all([
                response.json(),
                authFetch(`/match-stats/lineups/${matchId}`)
                    .then(r => r.ok ? r.json() : null)
                    .catch(() => null)
            ]);
            console.log("MATCH EVENTS:", events);

            if (events.length === 0) {
                mainContent.innerHTML = `<div class="team-card"><p>No data available for this match.</p></div>`;
                return;
            }

            const first = events[0];
            const homeTeamName = first.homeTeam || "Home";
            const awayTeamName = first.awayTeam || "Away";
            const homeGoals = first.homeGoals ?? 0;
            const awayGoals = first.awayGoals ?? 0;
            const homeTeamId = lineupsPayload?.homeTeamId || null;
            const awayTeamId = lineupsPayload?.awayTeamId || null;

            const matchDate = parseMatchDate(first.matchDate);
            const formattedDate = matchDate.toLocaleString('en-US', {
                weekday: 'short', year: 'numeric', month: 'short', day: 'numeric',
                hour: '2-digit', minute: '2-digit'
            });

            mainContent.innerHTML = `
            <div class="team-card">
                <h2 style="text-align:center;">Match Details</h2>
                <div class="fm-match-scoreline" style="font-size:1.3em; margin:20px 0; font-weight:bold;">
                    <div class="fm-match-score-team">
                        <div class="fm-match-score-name">${homeTeamId ? `<span class="cs-clickable" onclick="loadLeagueTeam(${homeTeamId}, '${htmlEscape(homeTeamName)}')">${homeTeamName}</span>` : homeTeamName}</div>
                        <div>${homeGoals}</div>
                    </div>
                    <div class="fm-match-score-separator" style="font-size:1.6em;">-</div>
                    <div class="fm-match-score-team">
                        <div class="fm-match-score-name">${awayTeamId ? `<span class="cs-clickable" onclick="loadLeagueTeam(${awayTeamId}, '${htmlEscape(awayTeamName)}')">${awayTeamName}</span>` : awayTeamName}</div>
                        <div>${awayGoals}</div>
                    </div>
                </div>
                <div style="text-align:center; color:#aaa; margin-bottom:25px;">
                    &#128197; ${formattedDate}
                </div>
                <div id="match-buttons-container" class="fm-match-actions">
                    <button type="button" id="view-preview" class="fm-action-btn secondary fm-match-action-btn">Preview</button>
                    <button type="button" id="view-lineups" class="fm-action-btn secondary fm-match-action-btn">Lineups</button>
                    <button type="button" id="view-stats" class="fm-action-btn secondary fm-match-action-btn">Stats</button>
                    <button type="button" id="view-goals" class="fm-action-btn secondary fm-match-action-btn">Goals</button>
                    <button type="button" id="view-replay" class="fm-action-btn secondary fm-match-action-btn">Replay</button>
                    <button type="button" id="view-report" class="fm-action-btn secondary fm-match-action-btn">Match Report</button>
                </div>
                <div id="match-info" style="margin-top:15px; min-height:200px;"></div>
                <div style="text-align:center; margin-top:30px;">
                    <button id="back-button" style="padding:10px 24px; font-size:1.1em;">Back</button>
                </div>
            </div>`;

            const backButton = document.getElementById('back-button');
            let backTarget = 'results';
            if (caller === 'match' || caller === 'results') backTarget = 'results';
            else if (caller === 'leagueMatches') backTarget = 'leagueMatches';
            else if (caller === 'leagueTable') backTarget = 'leagueTable';
            else if (caller === 'leagueSchedule') backTarget = 'leagueSchedule';
            else console.warn(`Unknown caller: ${caller} -> fallback to 'results'`);

            backButton.dataset.target = backTarget;
            backButton.style.display = 'inline-block';

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

            function renderMatchPreview(previewPayload) {
                const predictionReasons = Array.isArray(previewPayload?.predictionReasons) ? previewPayload.predictionReasons : [];
                const homeInsights = Array.isArray(previewPayload?.homeInsights) ? previewPayload.homeInsights : [];
                const awayInsights = Array.isArray(previewPayload?.awayInsights) ? previewPayload.awayInsights : [];
                const homeAbsentees = Array.isArray(previewPayload?.homeAbsentees) ? previewPayload.homeAbsentees : [];
                const awayAbsentees = Array.isArray(previewPayload?.awayAbsentees) ? previewPayload.awayAbsentees : [];
                const homeWin = Number(previewPayload?.homeWinProbability ?? 0) * 100;
                const draw = Number(previewPayload?.drawProbability ?? 0) * 100;
                const awayWin = Number(previewPayload?.awayWinProbability ?? 0) * 100;
                const expectedHomeGoals = Number(previewPayload?.expectedHomeGoals ?? 0);
                const expectedAwayGoals = Number(previewPayload?.expectedAwayGoals ?? 0);
                const homeFormationFitness = Number(previewPayload?.homeFormationFitness ?? 0);
                const awayFormationFitness = Number(previewPayload?.awayFormationFitness ?? 0);
                const homeBenchQuality = Number(previewPayload?.homeBenchQuality ?? 0);
                const awayBenchQuality = Number(previewPayload?.awayBenchQuality ?? 0);
                const homeAvailabilityScore = Number(previewPayload?.homeAvailabilityScore ?? 0);
                const awayAvailabilityScore = Number(previewPayload?.awayAvailabilityScore ?? 0);
                const analysis = htmlEscape(String(previewPayload?.analysisText || 'No extra preview analysis available.'));

                const renderInsights = items => items.length
                    ? items.map(item => `<div style="display:flex; justify-content:space-between; gap:12px; padding:8px 0; border-bottom:1px solid rgba(255,255,255,0.06);"><span style="color:#9aa0a6;">${htmlEscape(String(item.label || 'Insight'))}</span><strong>${htmlEscape(String(item.value || 'N/A'))}</strong></div>`).join('')
                    : `<div style="color:#aaa;">No extra insight available.</div>`;

                const renderAbsentees = items => items.length
                    ? items.map(item => `<span style="display:inline-flex; padding:7px 10px; border-radius:999px; background:rgba(255,255,255,0.06); margin:0 8px 8px 0;">${htmlEscape(String(item))}</span>`).join('')
                    : `<span style="color:#9aa0a6;">No absences reported.</span>`;

                infoDiv.innerHTML = `
                    <div class="fm-match-report-shell">
                        <h3 style="text-align:center; margin:0 0 16px; color:#4CAF50;">Match Preview</h3>
                        <div style="display:grid; grid-template-columns:repeat(auto-fit, minmax(220px, 1fr)); gap:14px; margin-bottom:16px;">
                            <div style="padding:16px; border-radius:14px; background:rgba(255,255,255,0.05); text-align:center;">
                                <div style="color:#9aa0a6; font-size:0.9em; margin-bottom:8px;">Prediction</div>
                                <div style="display:grid; grid-template-columns:repeat(3, 1fr); gap:8px; margin-bottom:10px;">
                                    <div><div style="font-size:0.8em; color:#9aa0a6;">1</div><div style="font-size:1.35em; font-weight:700;">${homeWin.toFixed(0)}%</div></div>
                                    <div><div style="font-size:0.8em; color:#9aa0a6;">X</div><div style="font-size:1.35em; font-weight:700;">${draw.toFixed(0)}%</div></div>
                                    <div><div style="font-size:0.8em; color:#9aa0a6;">2</div><div style="font-size:1.35em; font-weight:700;">${awayWin.toFixed(0)}%</div></div>
                                </div>
                                <div style="font-size:0.92em; color:#dfe6eb;">xG ${expectedHomeGoals.toFixed(2)} : ${expectedAwayGoals.toFixed(2)}</div>
                                <div style="font-size:0.88em; color:#9aa0a6; margin-top:8px;">${analysis}</div>
                            </div>
                            <div style="padding:16px; border-radius:14px; background:rgba(255,255,255,0.05);">
                                <div style="color:#9aa0a6; font-size:0.9em; margin-bottom:10px;">Squad fit</div>
                                <div style="display:flex; justify-content:space-between; gap:12px; margin-bottom:10px;">
                                    <div><div style="font-size:0.82em; color:#9aa0a6;">${htmlEscape(homeTeamName)}</div><div style="font-weight:700;">${htmlEscape(String(previewPayload?.homeFormation || '4-3-3'))}</div><div style="color:#9aa0a6; font-size:0.88em;">Fit ${(homeFormationFitness * 100).toFixed(0)}%</div><div style="color:#9aa0a6; font-size:0.88em;">Bench ${homeBenchQuality.toFixed(1)}</div></div>
                                    <div style="text-align:right;"><div style="font-size:0.82em; color:#9aa0a6;">${htmlEscape(awayTeamName)}</div><div style="font-weight:700;">${htmlEscape(String(previewPayload?.awayFormation || '4-3-3'))}</div><div style="color:#9aa0a6; font-size:0.88em;">Fit ${(awayFormationFitness * 100).toFixed(0)}%</div><div style="color:#9aa0a6; font-size:0.88em;">Bench ${awayBenchQuality.toFixed(1)}</div></div>
                                </div>
                                <div style="padding-top:10px; border-top:1px solid rgba(255,255,255,0.08); font-size:0.92em; color:#dfe6eb;">Availability ${homeAvailabilityScore.toFixed(0)}% vs ${awayAvailabilityScore.toFixed(0)}%</div>
                                <div style="margin-top:8px; color:#9aa0a6; font-size:0.88em;">Position mismatches ${Number(previewPayload?.homePositionMismatches ?? 0)} : ${Number(previewPayload?.awayPositionMismatches ?? 0)}</div>
                                <div style="margin-top:4px; color:#7f8c8d; font-size:0.82em;">${htmlEscape(String(previewPayload?.homePlayStyle || 'BALANCED'))} vs ${htmlEscape(String(previewPayload?.awayPlayStyle || 'BALANCED'))}</div>
                            </div>
                        </div>
                        <div style="display:grid; grid-template-columns:repeat(auto-fit, minmax(260px, 1fr)); gap:14px;">
                            <div style="padding:16px; border-radius:14px; background:rgba(255,255,255,0.04);">
                                <h4 style="margin:0 0 12px; color:#dfe6eb;">Why this prediction</h4>
                                <ul style="margin:0; padding-left:18px; color:#dfe6eb;">${predictionReasons.map(reason => `<li style="margin-bottom:8px;">${htmlEscape(String(reason))}</li>`).join('')}</ul>
                            </div>
                            <div style="padding:16px; border-radius:14px; background:rgba(255,255,255,0.04);">
                                <h4 style="margin:0 0 12px; color:#dfe6eb;">${htmlEscape(homeTeamName)} insights</h4>
                                ${renderInsights(homeInsights)}
                            </div>
                            <div style="padding:16px; border-radius:14px; background:rgba(255,255,255,0.04);">
                                <h4 style="margin:0 0 12px; color:#dfe6eb;">${htmlEscape(awayTeamName)} insights</h4>
                                ${renderInsights(awayInsights)}
                            </div>
                        </div>
                        <div style="display:grid; grid-template-columns:repeat(auto-fit, minmax(260px, 1fr)); gap:14px; margin-top:14px;">
                            <div style="padding:16px; border-radius:14px; background:rgba(255,255,255,0.04);">
                                <h4 style="margin:0 0 12px; color:#dfe6eb;">${htmlEscape(homeTeamName)} absences</h4>
                                ${renderAbsentees(homeAbsentees)}
                            </div>
                            <div style="padding:16px; border-radius:14px; background:rgba(255,255,255,0.04);">
                                <h4 style="margin:0 0 12px; color:#dfe6eb;">${htmlEscape(awayTeamName)} absences</h4>
                                ${renderAbsentees(awayAbsentees)}
                            </div>
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
                    const response2 = await authFetch(`/api/zox/match-preview/${matchId}`);
                    if (!response2.ok) throw new Error(`Preview unavailable (${response2.status})`);
                    cachedMatchPreview = await response2.json();
                    renderMatchPreview(cachedMatchPreview);
                } catch (error) {
                    console.error('Failed to load match preview:', error);
                    infoDiv.innerHTML = `<p style="color:#ffb3b3; text-align:center; padding:30px;">Match preview is not available for this match.</p>`;
                }
            }

            function renderMatchReport(reportPayload) {
                const headline = htmlEscape(String(reportPayload?.headline || 'Match Report'));
                const reportText = htmlEscape(String(reportPayload?.summary || 'No match report available.'));
                const motm = reportPayload?.playerOfTheMatch || null;
                const timeline = Array.isArray(reportPayload?.timeline) ? reportPayload.timeline : [];
                const stats = reportPayload?.stats || {};
                const homeTop = Array.isArray(reportPayload?.homeTopPerformers) ? reportPayload.homeTopPerformers : [];
                const awayTop = Array.isArray(reportPayload?.awayTopPerformers) ? reportPayload.awayTopPerformers : [];
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
                                </tbody>
                            </table>
                        </div>
                        <div style="margin-top:16px; padding:14px; border-radius:12px; background:rgba(255,255,255,0.04);">
                            <h4 style="margin:0 0 10px; color:#dfe6eb;">Timeline</h4>
                            ${timeline.length ? timeline.map(event => `<div style="display:grid; grid-template-columns:48px 28px minmax(0,1fr); gap:10px; padding:8px 0; border-bottom:1px solid rgba(255,255,255,0.06);"><strong style="color:#e8d47d;">${Number(event.minute || 0)}'</strong><span>${htmlEscape(String(event.icon || '•'))}</span><div><strong>${htmlEscape(String(event.title || 'Event'))}</strong><div style="color:#9aa0a6; font-size:0.88em;">${htmlEscape(String(event.teamName || ''))} · ${htmlEscape(String(event.detail || ''))}</div></div></div>`).join('') : '<div style="color:#9aa0a6;">No key events logged.</div>'}
                        </div>
                    </div>`;
            }

            async function showMatchReport() {
                await revealMatchResultIfAllowed();
                if (cachedMatchReport) {
                    renderMatchReport(cachedMatchReport);
                    return;
                }
                infoDiv.innerHTML = `<p style="color:#aaa; text-align:center; padding:30px;">Loading match report...</p>`;
                try {
                    const response2 = await authFetch(`/api/zox/post-match-report/${matchId}`);
                    if (!response2.ok) throw new Error(`Report unavailable (${response2.status})`);
                    cachedMatchReport = await response2.json();
                    renderMatchReport(cachedMatchReport);
                } catch (error) {
                    console.error('Failed to load match report:', error);
                    infoDiv.innerHTML = `<p style="color:#ffb3b3; text-align:center; padding:30px;">Match report is not available for this match.</p>`;
                }
            }

            function showStats() {
                const homeShotsOn = events.filter(e => e.eventType === "ShotOnTargetEvent" && e.shotOnTargetTeam === homeTeamName).length;
                const awayShotsOn = events.filter(e => e.eventType === "ShotOnTargetEvent" && e.shotOnTargetTeam === awayTeamName).length;
                const homeShotsOff = events.filter(e => e.eventType === "ShotOffTargetEvent" && e.shotOffTargetTeam === homeTeamName).length;
                const awayShotsOff = events.filter(e => e.eventType === "ShotOffTargetEvent" && e.shotOffTargetTeam === awayTeamName).length;
                const homeGoalsCount = events.filter(e => e.eventType === "GoalEvent" && e.scoreTeam === homeTeamName && e.goalScored !== false).length;
                const awayGoalsCount = events.filter(e => e.eventType === "GoalEvent" && e.scoreTeam === awayTeamName && e.goalScored !== false).length;

                const adjHomeShotsOn = homeShotsOn + homeGoalsCount;
                const adjAwayShotsOn = awayShotsOn + awayGoalsCount;
                const homeTotalShots = adjHomeShotsOn + homeShotsOff;
                const awayTotalShots = adjAwayShotsOn + awayShotsOff;

                const homeCorners = events.filter(e => e.eventType === "CornerEvent" && e.eventTeam === homeTeamName).length;
                const awayCorners = events.filter(e => e.eventType === "CornerEvent" && e.eventTeam === awayTeamName).length;
                const homeYellows = events.filter(e => e.eventType === "YellowCardEvent" && e.eventTeam === homeTeamName).length;
                const awayYellows = events.filter(e => e.eventType === "YellowCardEvent" && e.eventTeam === awayTeamName).length;
                const homeReds = events.filter(e => e.eventType === "RedCardEvent" && e.eventTeam === homeTeamName).length;
                const awayReds = events.filter(e => e.eventType === "RedCardEvent" && e.eventTeam === awayTeamName).length;
                const homePenalties = events.filter(e => e.eventType === "PenaltyEvent" && e.eventTeam === homeTeamName).length;
                const awayPenalties = events.filter(e => e.eventType === "PenaltyEvent" && e.eventTeam === awayTeamName).length;

                const extractEventXg = event => {
                    const rawValue = Number(event?.xG ?? event?.xg ?? 0);
                    return Number.isFinite(rawValue) ? rawValue : 0;
                };
                const isXgEvent = event =>
                    event.eventType === "ShotOnTargetEvent" ||
                    event.eventType === "ShotOffTargetEvent" ||
                    (event.eventType === "GoalEvent" && event.goalScored !== false);
                const resolveXgTeam = event => {
                    if (event.eventType === "GoalEvent") return event.scoreTeam || event.eventTeam;
                    if (event.eventType === "ShotOnTargetEvent") return event.shotOnTargetTeam || event.eventTeam;
                    if (event.eventType === "ShotOffTargetEvent") return event.shotOffTargetTeam || event.eventTeam;
                    return event.eventTeam;
                };
                const sumTeamXg = teamName =>
                    events.reduce((sum, event) => {
                        if (!isXgEvent(event) || resolveXgTeam(event) !== teamName) return sum;
                        return sum + extractEventXg(event);
                    }, 0);

                const homeXg = sumTeamXg(homeTeamName);
                const awayXg = sumTeamXg(awayTeamName);

                const countTeamEvents = (type, teamName) =>
                    events.filter(e => e.eventType === type && e.eventTeam === teamName).length;

                const homePossWeight =
                    (countTeamEvents("ChanceEvent", homeTeamName) * 3.0) +
                    (homeShotsOn * 2.0) + (homeShotsOff * 1.4) +
                    (homeCorners * 1.2) + (countTeamEvents("FreeKickEvent", homeTeamName) * 0.9) +
                    (homePenalties * 1.3) + (countTeamEvents("GoalEvent", homeTeamName) * 1.1);
                const awayPossWeight =
                    (countTeamEvents("ChanceEvent", awayTeamName) * 3.0) +
                    (awayShotsOn * 2.0) + (awayShotsOff * 1.4) +
                    (awayCorners * 1.2) + (countTeamEvents("FreeKickEvent", awayTeamName) * 0.9) +
                    (awayPenalties * 1.3) + (countTeamEvents("GoalEvent", awayTeamName) * 1.1);

                const baselineWeight = 18.0;
                const totalPoss = (homePossWeight + baselineWeight) + (awayPossWeight + baselineWeight);
                let homePossPct = totalPoss > 0
                    ? Math.round(((homePossWeight + baselineWeight) / totalPoss) * 100)
                    : 50;
                homePossPct = Math.max(32, Math.min(68, homePossPct));
                const awayPossPct = 100 - homePossPct;

                let html = `<h3 style="text-align:center; margin:0 0 20px; color:#4CAF50;">Match Stats</h3>`;
                html += `
                <table style="width:100%; border-collapse:collapse; font-size:0.95em;">
                    <thead>
                        <tr style="background:rgba(76,175,80,0.15);">
                            <th style="padding:12px; text-align:left;">Stats</th>
                            <th style="padding:12px; text-align:center;">${homeTeamId ? `<span class="cs-clickable" onclick="loadLeagueTeam(${homeTeamId}, '${htmlEscape(homeTeamName)}')">${homeTeamName}</span>` : homeTeamName}</th>
                            <th style="padding:12px; text-align:center;">${awayTeamId ? `<span class="cs-clickable" onclick="loadLeagueTeam(${awayTeamId}, '${htmlEscape(awayTeamName)}')">${awayTeamName}</span>` : awayTeamName}</th>
                        </tr>
                    </thead>
                    <tbody>
                        <tr><td style="padding:10px;">Possession</td><td style="text-align:center;font-weight:bold;">${homePossPct}%</td><td style="text-align:center;font-weight:bold;">${awayPossPct}%</td></tr>
                        <tr style="background:rgba(255,255,255,0.04);"><td style="padding:10px;">xG</td><td style="text-align:center;">${homeXg.toFixed(2)}</td><td style="text-align:center;">${awayXg.toFixed(2)}</td></tr>
                        <tr><td style="padding:10px;">Shots</td><td style="text-align:center;">${homeTotalShots}</td><td style="text-align:center;">${awayTotalShots}</td></tr>
                        <tr><td style="padding:10px;">Shots on target</td><td style="text-align:center;">${adjHomeShotsOn}</td><td style="text-align:center;">${adjAwayShotsOn}</td></tr>
                        <tr style="background:rgba(255,255,255,0.04);"><td style="padding:10px;">Shots off target</td><td style="text-align:center;">${homeShotsOff}</td><td style="text-align:center;">${awayShotsOff}</td></tr>
                        <tr><td style="padding:10px;">Corners</td><td style="text-align:center;">${homeCorners}</td><td style="text-align:center;">${awayCorners}</td></tr>
                        <tr style="background:rgba(255,255,255,0.04);"><td style="padding:10px;">Yellow cards</td><td style="text-align:center;color:#ff9800;">${homeYellows}</td><td style="text-align:center;color:#ff9800;">${awayYellows}</td></tr>
                        <tr><td style="padding:10px;">Red cards</td><td style="text-align:center;color:#f44336;">${homeReds}</td><td style="text-align:center;color:#f44336;">${awayReds}</td></tr>
                        <tr style="background:rgba(255,255,255,0.04);"><td style="padding:10px;">Penalties</td><td style="text-align:center;">${homePenalties}</td><td style="text-align:center;">${awayPenalties}</td></tr>
                    </tbody>
                </table>`;
                infoDiv.innerHTML = html;
            }

            if (initialTab === 'report') void showMatchReport();
            else void showPreview();

            document.getElementById("view-preview").addEventListener("click", () => void showPreview());
            document.getElementById("view-lineups").addEventListener("click", () => {
                if (!lineupsPayload || (!lineupsPayload.homeLineup && !lineupsPayload.awayLineup)) {
                    infoDiv.innerHTML = `<p style="color:#aaa; text-align:center; padding:30px;">Lineups are not available for this match.</p>`;
                    return;
                }
                const seasonYear = deps.getCurrentSeasonYear?.() || null;
                const renderLineup = (teamName, teamId, players) => {
                    const sorted = [...(players || [])].sort((a, b) => {
                        const posOrder = { GK: 0, DEF: 1, MID: 2, WNG: 3, ATT: 4 };
                        return (posOrder[a.position] ?? 9) - (posOrder[b.position] ?? 9);
                    });
                    if (sorted.length === 0) return `<p class="fm-subtle">No lineup data.</p>`;
                    let html2 = `
                        <section class="fm-match-lineup-team">
                            <h4 class="fm-match-lineup-title">${htmlEscape(teamName)}</h4>
                            <div class="fm-match-lineup-head">
                                <div>POS</div>
                                <div>Player</div>
                                <div>Rate</div>
                                <div>Impact</div>
                                <div>Min</div>
                            </div>
                            <div class="fm-match-lineup-body">`;
                    sorted.forEach(p => {
                        const compactName = htmlEscape(formatCompactPlayerName(p.playerName));
                        html2 += `
                            <div class="fm-match-lineup-row">
                                <div class="fm-match-lineup-pos">${htmlEscape(p.position || '-')}</div>
                                <div class="fm-match-lineup-player-cell">
                                    ${p.playerId && teamId
                                        ? `<button type="button" class="fm-match-lineup-player js-load-lineup-player" data-player-id="${p.playerId}" data-team-id="${teamId}" data-team-name="${htmlEscape(teamName)}" data-season-year="${seasonYear ?? ''}">${compactName}</button>`
                                        : `<span class="fm-match-lineup-player is-static">${compactName}</span>`}
                                </div>
                                <div class="fm-match-lineup-grade">${formatRatingBadge(p.grade)}</div>
                                <div class="fm-match-lineup-badge-cell">${buildLineupEventBadges(p)}</div>
                                <div class="fm-match-lineup-min">${Number(p.minutesPlayed ?? 0)}</div>
                            </div>`;
                    });
                    return `${html2}</div></section>`;
                };

                infoDiv.innerHTML = `
                    <div class="fm-match-lineups">
                        <h3 class="fm-match-lineups-title">Lineups & Grades</h3>
                        <div class="fm-match-lineups-grid">
                            ${renderLineup(lineupsPayload.homeTeam || homeTeamName, lineupsPayload.homeTeamId || 0, lineupsPayload.homeLineup || [])}
                            ${renderLineup(lineupsPayload.awayTeam || awayTeamName, lineupsPayload.awayTeamId || 0, lineupsPayload.awayLineup || [])}
                        </div>
                    </div>`;
                infoDiv.querySelectorAll('.js-load-lineup-player').forEach(node => {
                    node.addEventListener('click', () => {
                        const playerId = Number(node.dataset.playerId);
                        const teamId2 = Number(node.dataset.teamId);
                        const teamName2 = node.dataset.teamName || 'Team';
                        const lineupSeasonYear = node.dataset.seasonYear ? Number(node.dataset.seasonYear) : (deps.getCurrentSeasonYear?.() || null);
                        if (playerId && teamId2) {
                            deps.loadLeagueTeamPlayer?.(playerId, teamId2, teamName2, { seasonYear: lineupSeasonYear });
                        }
                    });
                });
            });
            document.getElementById("view-stats").addEventListener("click", showStats);
            document.getElementById("view-replay").addEventListener("click", () => {
                void (async () => {
                    await revealMatchResultIfAllowed();
                    window.location.href = `/newLogic/realisticDemo.html?matchId=${encodeURIComponent(matchId)}&mode=replay`;
                })();
            });
            document.getElementById("view-report").addEventListener("click", () => void showMatchReport());
            document.getElementById("view-goals").addEventListener("click", () => {
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
            });
        } catch (err) {
            console.error("Error loading match:", err);
            document.getElementById("main-content").innerHTML = `<div class="team-card"><p>Error loading match: ${err.message}</p></div>`;
        }
    }

    return { loadMatch };
}
