// pages/views/league-view.js
import {
    htmlEscape, normalizeTeamKey, normalizePlayerKey, formatGoalDiff,
    buildMilestoneBoardHtml, formatSeasonShortLabel, buildEmptyState
} from './utils.js';

export function createLeagueView(deps) {
    const {
        authFetch, getTeamId, ensureCurrentLeagueId, getCurrentLeagueBackTarget,
        getCurrentLeagueName, getLeagueSeasonYear, getSeasonYear,
        pushNavState, getActiveLeagueNavState, goBackSmart,
        loadMatch, loadFixture, loadPlayer, loadPage,
        syncUserLeagueContext, setActiveLeagueContext, normalizeLeagueId,
        activeLeagueId, currentUserCompetitionId,
        buildSquadTableHtml, buildTeamTransferOverviewHtml, bindSquadRowClicks,
        buildPlayerProfileHtml, initPlayerProfilePage, handlePlayerTransferAction,
        fetchPlayerRatingSummary, fetchPlayerTransferStatus,
        buildClubActionsHtml,
        renderTableView, renderLeagueScheduleView, renderLeagueMatchesView
    } = deps;

    async function openTeamByName(teamName) {
        try {
            const leagueId = await ensureCurrentLeagueId();
            if (!leagueId) return;
            const seasonParam = getLeagueSeasonYear() ? `?seasonYear=${encodeURIComponent(getLeagueSeasonYear())}` : '';
            const res = await authFetch(`/countries/leagues/${leagueId}/teams${seasonParam}`);
            if (!res.ok) return;
            const teams = await res.json();
            const key = normalizeTeamKey(teamName);
            const found = teams.find(t => normalizeTeamKey(t.name) === key);
            if (found) {
                loadLeagueTeam(found.id, found.name);
            }
        } catch (e) {
            console.warn('Team navigation failed:', e);
        }
    }

    async function loadLeagueTable(seasonYear = null) {
        try {
            const leagueId = await ensureCurrentLeagueId();
            if (!leagueId) return;
            const backTarget = getCurrentLeagueBackTarget();
            const leagueName = getCurrentLeagueName();
            const seasonsResponse = await authFetch(`/countries/leagues/${leagueId}/seasons`);
            const seasons = seasonsResponse.ok ? await seasonsResponse.json() : [];
            const selectedSeason = seasonYear || getLeagueSeasonYear() || getSeasonYear() || seasons[seasons.length - 1]?.seasonYear || null;
            const seasonObj = { value: selectedSeason };
            if (deps.setLeagueSeasonYear) deps.setLeagueSeasonYear(selectedSeason);
            const selectedSeasonNumber = seasons.find(s => s.seasonYear === selectedSeason)?.seasonNumber
                || (selectedSeason ? Math.max(1, selectedSeason - 2025 + 1) : 1);
            const seasonParam = selectedSeason ? `?seasonYear=${selectedSeason}` : "";

            const [tableResponse, teamsResponse, scheduleResponse, scorersResponse, assistsResponse, milestonesResponse, seasonSummaryResponse] = await Promise.all([
                authFetch(`/countries/leagues/${leagueId}/table${seasonParam}`),
                authFetch(`/countries/leagues/${leagueId}/teams${seasonParam}`),
                authFetch(`/countries/leagues/${leagueId}/schedule${seasonParam}`),
                authFetch(`/stats/leagues/${leagueId}/topscorers${seasonParam}`),
                authFetch(`/stats/leagues/${leagueId}/topassists${seasonParam}`),
                authFetch(`/stats/leagues/${leagueId}/milestones${seasonParam}`),
                authFetch(`/countries/leagues/${leagueId}/season-summary${seasonParam}`)
            ]);
            if (!tableResponse.ok) throw new Error(`League table load failed: ${tableResponse.status}`);
            if (!teamsResponse.ok) throw new Error(`League teams load failed: ${teamsResponse.status}`);

            const table = await tableResponse.json();
            const leagueTeams = await teamsResponse.json();
            const schedule = scheduleResponse.ok ? await scheduleResponse.json() : [];
            const scorers = scorersResponse.ok ? await scorersResponse.json() : [];
            const assists = assistsResponse.ok ? await assistsResponse.json() : [];
            const milestones = milestonesResponse.ok ? await milestonesResponse.json() : null;
            const seasonSummary = seasonSummaryResponse.ok ? await seasonSummaryResponse.json() : null;
            const teamMetaByName = new Map();
            const teamIdByName = new Map();
            leagueTeams.forEach(team => {
                const key = normalizeTeamKey(team.name);
                teamMetaByName.set(key, team);
                teamIdByName.set(key, team.id);
            });

            const enhancedTable = table.map(row => ({
                ...row,
                teamId: row.teamId ?? teamIdByName.get(normalizeTeamKey(row.name)) ?? null,
                humanControlled: typeof row.humanControlled === 'boolean'
                    ? row.humanControlled
                    : (typeof teamMetaByName.get(normalizeTeamKey(row.name))?.humanControlled === 'boolean'
                        ? teamMetaByName.get(normalizeTeamKey(row.name)).humanControlled
                        : null)
            }));

            const byRound = new Map();
            schedule.forEach(match => {
                const round = Number(match.round || 1);
                if (!byRound.has(round)) byRound.set(round, []);
                byRound.get(round).push({
                    ...match,
                    homeTeamId: teamIdByName.get(normalizeTeamKey(match.homeTeam)) ?? null,
                    awayTeamId: teamIdByName.get(normalizeTeamKey(match.awayTeam)) ?? null
                });
            });
            const rounds = [...byRound.keys()].sort((a, b) => a - b);
            const currentRound = rounds.find(round => (byRound.get(round) || []).some(match => !match.played))
                || rounds[rounds.length - 1]
                || 1;

            const playerIdByKey = new Map();
            try {
                const response = await authFetch(`/countries/leagues/${leagueId}/player-directory${seasonParam}`);
                if (response.ok) {
                    const directory = await response.json();
                    directory.forEach(player => {
                        playerIdByKey.set(
                            `${normalizeTeamKey(player.teamName)}|${normalizePlayerKey(player.name)}`,
                            player.id
                        );
                    });
                }
            } catch (e) {
                console.warn('League player directory fetch failed:', e);
            }

            const visibleRounds = rounds.map(round => ({
                round,
                label: round === currentRound
                    ? 'Current focus'
                    : round === currentRound - 1
                        ? 'Latest results'
                        : round === currentRound + 1
                            ? 'Next fixtures'
                            : '',
                isFocusRound: round === currentRound,
                matches: byRound.get(round)
            }));

            const mappedScorers = scorers.map(item => ({
                ...item,
                teamId: teamIdByName.get(normalizeTeamKey(item.teamName)) ?? null,
                playerId: playerIdByKey.get(`${normalizeTeamKey(item.teamName)}|${normalizePlayerKey(item.playerName || item.name)}`) ?? null
            }));
            const mappedAssists = assists.map(item => ({
                ...item,
                teamId: teamIdByName.get(normalizeTeamKey(item.teamName)) ?? null,
                playerId: playerIdByKey.get(`${normalizeTeamKey(item.teamName)}|${normalizePlayerKey(item.playerName || item.name)}`) ?? null
            }));

            renderTable({
                leagueId,
                leagueName,
                backTarget,
                fixtureBackTarget: 'leagueTable',
                matchCaller: 'leagueTable',
                table: enhancedTable,
                fixtures: visibleRounds,
                topScorers: mappedScorers,
                topAssists: mappedAssists,
                milestones,
                seasonSummary,
                seasons,
                selectedSeason,
                selectedSeasonNumber
            });
        } catch (err) {
            console.error("Failed to load league table:", err);
            document.getElementById("main-content").innerHTML = `
                <div class="manager-card">
                    <button class="back-to-dashboard" data-nav-back="${getCurrentLeagueBackTarget()}">Back</button>
                    <h2>Error</h2>
                    <p>Could not load league table.</p>
                </div>`;
        }
    }

    function renderTable(table) {
        renderTableView(table, { loadLeagueTeam, loadLeagueTeamPlayer, loadLeagueTable, loadMatch, loadFixture, escapeHtml: htmlEscape, formatGoalDiff });
    }

    async function loadLeagueMatches(seasonYear = null) {
        try {
            const leagueId = await ensureCurrentLeagueId();
            if (!leagueId) return;
            const backTarget = getCurrentLeagueBackTarget();
            console.log(`Loading league matches...`);
            const selectedSeason = seasonYear || getLeagueSeasonYear() || getSeasonYear() || null;
            if (deps.setLeagueSeasonYear) deps.setLeagueSeasonYear(selectedSeason ?? undefined);
            const seasonParam = selectedSeason ? `?seasonYear=${selectedSeason}` : "";
            const response = await authFetch(`/countries/leagues/${leagueId}/matches${seasonParam}`);
            console.log(`Response status: ${response.status}`);
            if (!response.ok) throw new Error("Failed to load league matches");
            const matches = await response.json();
            const results = matches.sort((a, b) => new Date(b.matchDate) - new Date(a.matchDate));
            const seasonNumber = selectedSeason ? Math.max(1, selectedSeason - 2025 + 1) : null;
            const titleBase = `${getCurrentLeagueName()} Results`;
            renderLeagueMatchesView(results, seasonNumber ? `${titleBase} - Season ${seasonNumber}` : titleBase, { backTarget, caller: 'leagueMatches' });
        } catch (err) {
            console.error(err);
            document.getElementById("main-content").innerHTML = `
                <div class="manager-card">
                    <button data-nav-back="${getCurrentLeagueBackTarget()}">Back</button>
                    <h2>Error</h2>
                    <p>Could not load league matches.</p>
                </div>`;
        }
    }

    async function loadLeagueSchedule(seasonYear = null) {
        try {
            const leagueId = await ensureCurrentLeagueId();
            if (!leagueId) return;
            const backTarget = getCurrentLeagueBackTarget();
            const selectedSeasonParam = seasonYear || getLeagueSeasonYear() || getSeasonYear() || null;
            const teamsSeasonParam = selectedSeasonParam ? `?seasonYear=${encodeURIComponent(selectedSeasonParam)}` : '';
            const [seasonsResponse, teamsResponse] = await Promise.all([
                authFetch(`/countries/leagues/${leagueId}/seasons`),
                authFetch(`/countries/leagues/${leagueId}/teams${teamsSeasonParam}`)
            ]);
            const seasons = seasonsResponse.ok ? await seasonsResponse.json() : [];
            const leagueTeams = teamsResponse.ok ? await teamsResponse.json() : [];
            const teamIdByName = new Map();
            leagueTeams.forEach(team => {
                teamIdByName.set(normalizeTeamKey(team.name), team.id);
            });
            const selectedSeason = selectedSeasonParam || seasons[seasons.length - 1]?.seasonYear || null;
            if (deps.setLeagueSeasonYear) deps.setLeagueSeasonYear(selectedSeason);
            const selectedSeasonNumber = seasons.find(s => s.seasonYear === selectedSeason)?.seasonNumber || 1;
            const seasonParam = selectedSeason ? `?seasonYear=${selectedSeason}` : "";
            const response = await authFetch(`/countries/leagues/${leagueId}/schedule${seasonParam}`);
            if (!response.ok) throw new Error(`Failed to load schedule: ${response.status}`);
            const schedule = await response.json();

            const byRound = new Map();
            let currentRound = 1;
            schedule.forEach(m => {
                const round = Number(m.round || 1);
                if (!byRound.has(round)) byRound.set(round, []);
                byRound.get(round).push({
                    ...m,
                    homeTeamId: teamIdByName.get(normalizeTeamKey(m.homeTeam)) ?? null,
                    awayTeamId: teamIdByName.get(normalizeTeamKey(m.awayTeam)) ?? null
                });
                if (!m.played && round < currentRound) currentRound = round;
            });
            const rounds = [...byRound.keys()].sort((a, b) => a - b);
            const firstUnplayed = rounds.find(r => byRound.get(r).some(m => !m.played));
            if (firstUnplayed) currentRound = firstUnplayed;
            renderLeagueScheduleView({
                rounds: rounds.map(round => ({
                    round,
                    label: round === currentRound
                        ? 'Current focus'
                        : round === currentRound - 1
                            ? 'Latest results'
                            : round === currentRound + 1
                                ? 'Next fixtures'
                                : '',
                    isFocusRound: round === currentRound,
                    matches: byRound.get(round) || []
                })),
                leagueName: getCurrentLeagueName(),
                backTarget,
                fixtureBackTarget: 'leagueSchedule',
                matchCaller: 'leagueSchedule',
                seasons,
                selectedSeason,
                selectedSeasonNumber
            }, {
                loadLeagueSchedule,
                loadMatch,
                loadLeagueTeam,
                loadFixture
            });
        } catch (err) {
            console.error("Failed to load league schedule:", err);
            document.getElementById("main-content").innerHTML = `
                <div class="manager-card">
                    <button class="back-to-dashboard" data-nav-back="${getCurrentLeagueBackTarget()}">Back</button>
                    <h2>Error</h2>
                    <p>Could not load league schedule.</p>
                </div>`;
        }
    }

    async function loadLeagueTeam(teamId, teamName, options = {}) {
        const seasonYear = options.seasonYear ?? getLeagueSeasonYear() ?? null;
        const pushHistory = options.pushHistory !== false;
        if (deps.setLeagueSeasonYear) deps.setLeagueSeasonYear(seasonYear ?? undefined);
        if (pushHistory) pushNavState({ type: 'leagueTeam', teamId, teamName, seasonYear, ...getActiveLeagueNavState() });
        const mainContent = document.getElementById("main-content");
        try {
            const [response, transferOverview, milestones] = await Promise.all([
                authFetch(`/teams/${teamId}/players`),
                (async () => {
                    try {
                        const transferResponse = await authFetch(`/transfers/team/${teamId}?viewerTeamId=${encodeURIComponent(getTeamId())}`);
                        return await transferResponse.json();
                    } catch {
                        return null;
                    }
                })(),
                (async () => {
                    try {
                        const milestoneResponse = await authFetch(`/teams/${teamId}/milestones`);
                        return milestoneResponse.ok ? await milestoneResponse.json() : null;
                    } catch {
                        return null;
                    }
                })()
            ]);
            if (!response.ok) throw new Error(`Team players load failed: ${response.status}`);
            const players = await response.json();
            const isUserTeam = Number(teamId) === Number(getTeamId());

            const avgOverall = players.length
                ? (players.reduce((sum, player) => sum + Number(player.overall || 0), 0) / players.length).toFixed(1)
                : '-';
            const avgAge = players.length
                ? (players.reduce((sum, player) => sum + Number(player.age || 0), 0) / players.length).toFixed(1)
                : '-';
            const totalGoals = players.reduce((sum, player) => sum + Number(player.totalGoals ?? player.goals ?? 0), 0);
            const totalAssists = players.reduce((sum, player) => sum + Number(player.totalAssists ?? player.assists ?? 0), 0);
            const injuryCount = players.filter(player => player.injured).length;
            const poorFormCount = players.filter(player => Number(player.form) <= 5.8).length;

            let html = `
            <div class="fm-page fm-page--team-detail">
                <section class="fm-panel fm-club-hero">
                    <button class="back-to-dashboard" id="league-team-back-btn">Back</button>
                    <div class="fm-club-hero-main">
                        <div>
                            <div class="fm-eyebrow">Team overview</div>
                            <h2>${htmlEscape(teamName)}</h2>
                            <p class="fm-subtle">Open-football inspired squad screen. Click any row to open the player profile.</p>
                        </div>
                        ${isUserTeam ? buildClubActionsHtml('firstTeam') : ''}
                    </div>
                    <div class="fm-medical-stat-grid team-summary-grid">
                        <div><strong>${players.length}</strong><span>Players</span></div>
                        <div><strong>${avgOverall}</strong><span>Avg OVR</span></div>
                        <div><strong>${avgAge}</strong><span>Avg age</span></div>
                        <div><strong>${totalGoals}/${totalAssists}</strong><span>Goals / assists</span></div>
                    </div>
                </section>
                <section class="fm-panel fm-milestone-board-panel">
                    <div class="fm-panel-head">
                        <div>
                            <h3>${isUserTeam ? 'Club milestones' : 'Team milestones'}</h3>
                            <p class="fm-subtle">Season board for ${htmlEscape(teamName)}, keeping club context visible without losing the main league board.</p>
                        </div>
                        <span class="fm-panel-action">${isUserTeam ? 'Club area' : 'Season board'}</span>
                    </div>
                    ${buildMilestoneBoardHtml(milestones)}
                </section>
                <div class="fm-team-layout ${isUserTeam ? 'has-side-panel' : ''}">
                    <section class="fm-panel">
                        <div class="fm-panel-head">
                            <h3>Squad</h3>
                            <span class="fm-panel-action">${players.length} registered</span>
                        </div>
                        ${buildSquadTableHtml(players, {
                            rowClass: 'league-player-card',
                            teamId,
                            teamName,
                            emptyText: 'No registered players found for this team.'
                        })}
                    </section>
                    ${isUserTeam ? `
                    <aside class="fm-panel fm-medical-panel">
                        <div class="fm-panel-head">
                            <h3>Medical Center</h3>
                            <span class="fm-panel-action">Club area</span>
                        </div>
                        <div class="fm-medical-icon">&#10010; &#129658;</div>
                        <p class="fm-subtle">Keep the open-football squad view, but retain our app-specific medical workflow for injuries and recovery.</p>
                        <div class="fm-medical-stat-grid">
                            <div><strong>${injuryCount}</strong><span>Injuries</span></div>
                            <div><strong>${poorFormCount}</strong><span>Low morale</span></div>
                            <div><strong>${players.length - injuryCount}</strong><span>Available</span></div>
                        </div>
                        <button type="button" class="fm-action-btn secondary" onclick="loadPage('medicalCenter')">Open Medical Center</button>
                    </aside>` : ''}
                </div>
                ${buildTeamTransferOverviewHtml(transferOverview, { isUserTeam, teamName })}
            </div>`;
            mainContent.innerHTML = html;

            document.getElementById("league-team-back-btn")?.addEventListener("click", () => goBackSmart('leagueTable'));

            bindSquadRowClicks(mainContent, row => {
                const playerId = Number(row.dataset.playerId);
                const playerTeamId = Number(row.dataset.teamId);
                const playerTeamName = row.dataset.teamName || "Team";
                loadLeagueTeamPlayer(playerId, playerTeamId, playerTeamName, { seasonYear });
            });
            mainContent.querySelectorAll('[data-team-transfer-player-id]').forEach(button => {
                button.addEventListener('click', () => {
                    loadLeagueTeamPlayer(
                        Number(button.dataset.teamTransferPlayerId || 0),
                        Number(button.dataset.teamTransferTeamId || teamId),
                        button.dataset.teamTransferTeamName || teamName || 'Team',
                        { seasonYear }
                    );
                });
            });
        } catch (err) {
            console.error("Failed to load team details:", err);
            mainContent.innerHTML = `
            <div class="manager-card">
                <button class="back-to-dashboard" id="league-team-error-back">Back</button>
                <h2>Error</h2>
                <p>Could not load team details.</p>
            </div>`;
            document.getElementById("league-team-error-back")?.addEventListener("click", () => goBackSmart('leagueTable'));
        }
    }

    async function loadLeagueTeamPlayer(playerId, teamId, teamName, options = {}) {
        const seasonYear = options.seasonYear ?? getLeagueSeasonYear() ?? null;
        const pushHistory = options.pushHistory !== false;
        if (deps.setLeagueSeasonYear) deps.setLeagueSeasonYear(seasonYear ?? undefined);
        if (pushHistory) pushNavState({ type: 'leagueTeamPlayer', playerId, teamId, teamName, seasonYear, ...getActiveLeagueNavState() });
        const mainContent = document.getElementById("main-content");
        try {
            const [playerResponse, ratingSummary, transferStatus] = await Promise.all([
                authFetch(`/players/${playerId}`),
                fetchPlayerRatingSummary(playerId),
                fetchPlayerTransferStatus(playerId)
            ]);

            if (!playerResponse.ok) throw new Error(`Player load failed: ${playerResponse.status}`);
            const player = await playerResponse.json();
            mainContent.innerHTML = buildPlayerProfileHtml(player, {
                backLabel: 'Back',
                eyebrow: 'Team player',
                teamName: teamName || 'Team',
                ratingSummary,
                transferStatus,
                placeholderPrefix: 'This tab UI is ready'
            });
            initPlayerProfilePage({
                onBack: () => goBackSmart('leagueTable'),
                onTransferAction: (action, button) => handlePlayerTransferAction(action, button, {
                    playerId,
                    reloadCurrent: () => loadLeagueTeamPlayer(playerId, teamId, teamName, { pushHistory: false, seasonYear }),
                    reloadOwned: () => loadPlayer(playerId, 'leagueTable', { pushHistory: false })
                })
            });
        } catch (err) {
            console.error("Failed to load player profile:", err);
            mainContent.innerHTML = `
                <div class="manager-card">
                    <button id="back-to-league-team-fallback" class="back-to-dashboard">Back</button>
                    <h2>Error</h2>
                    <p>Could not load player profile.</p>
                </div>`;
            const backButton = document.getElementById("back-to-league-team-fallback");
            if (backButton) {
                backButton.addEventListener("click", () => goBackSmart('leagueTable'));
            }
        }
    }

    return { loadLeagueTable, loadLeagueSchedule, loadLeagueMatches, loadLeagueTeam, loadLeagueTeamPlayer, openTeamByName };
}
