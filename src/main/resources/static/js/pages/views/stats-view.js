// pages/views/stats-view.js
import { htmlEscape } from './utils.js';

export function createStatsView(deps) {
    const {
        authFetch, getTeamId, ensureCurrentLeagueId,
        getLeagueSeasonYear, getSeasonYear,
        goBackSmart, renderPlayers, loadLeagueTeam, loadLeagueTeamPlayer,
        // The league area had no navigation of its own, so this page was a route with no way in and
        // no way onward. Passed in rather than imported, like every other action row.
        buildLeagueActionsHtml
    } = deps;

    function renderTopListsError(message) {
        const mainContent = document.getElementById("main-content");
        if (!mainContent) return;
        mainContent.innerHTML = `
            <div class="fm-page fm-page--league">
                <section class="fm-panel fm-club-hero">
                    <button class="back-to-dashboard" data-nav-back="dashboard">Back</button>
                    <div class="fm-club-hero-main">
                        <div>
                            <div class="fm-eyebrow">League stats</div>
                            <h2>Top Lists</h2>
                            <p class="fm-subtle">${htmlEscape(message)}</p>
                        </div>
                        ${buildLeagueActionsHtml('topScorers')}
                    </div>
                </section>
            </div>`;
    }

    async function loadPlayerStats() {
        const teamId = getTeamId();
        console.log(`Loading player stats for userTeamId ${teamId}`);
        const response = await authFetch(`/teams/${teamId}/players`);
        console.log(`Response status: ${response.status}`);
        const players = await response.json();
        renderPlayers(players, "Player Stats");
    }

    async function loadTopScorersAndAssists(mode = "both") {
        try {
            getTeamId();
            const leagueId = await ensureCurrentLeagueId();
            if (!leagueId) {
                // This returned with the page untouched, so a menu entry into it produced a blank
                // screen with no explanation and no way out.
                renderTopListsError('This club is not in a league yet, so there are no league stats to show.');
                return;
            }
            const seasonParam = getLeagueSeasonYear() || getSeasonYear()
                ? `?seasonYear=${getLeagueSeasonYear() || getSeasonYear()}`
                : '';
            const [scorersRes, assistsRes, leagueTeamsRes] = await Promise.all([
                authFetch(`/stats/leagues/${leagueId}/topscorers${seasonParam}`),
                authFetch(`/stats/leagues/${leagueId}/topassists${seasonParam}`),
                authFetch(`/countries/leagues/${leagueId}/teams${seasonParam}`)
            ]);
            const scorers = await scorersRes.json();
            const assists = await assistsRes.json();
            const leagueTeams = leagueTeamsRes.ok ? await leagueTeamsRes.json() : [];
            const teamIdByName = new Map();
            leagueTeams.forEach(t => teamIdByName.set(t.name, t.id));
            const playerIdByKey = new Map();
            try {
                const directoryRes = await authFetch(`/countries/leagues/${leagueId}/player-directory${seasonParam}`);
                if (directoryRes.ok) {
                    const directory = await directoryRes.json();
                    directory.forEach(player => {
                        playerIdByKey.set(`${player.teamName}|${player.name}`, player.id);
                    });
                }
            } catch (e) {}

            const mainContent = document.getElementById("main-content");

            let html = `
            <div class="manager-card" style="padding: 25px;">
                <button class="back-to-dashboard" data-nav-back="dashboard">Back</button>
                <h2 style="text-align: center; margin: 20px 0 30px; color: #e94560;">League Stats - Top Lists</h2>

                <div class="top-lists">

                    ${mode !== "assists" ? `
                    <div class="top-scorers top-list-panel">
                        <h3 style="text-align: center; color: #ffd700; margin-bottom: 15px;">Top Scorers</h3>
                        <ul style="list-style: none; padding: 0; margin: 0;">` : ""}`;

            if (mode !== "assists") {
                scorers.forEach((s, i) => {
                    const rankColor = i < 3 ? '#ffd700' : '#aaa';
                    const bgColor = i % 2 === 0 ? 'rgba(255,255,255,0.05)' : 'rgba(0,0,0,0.1)';
                    html += `
                    <li class="top-list-entry" style="background: ${bgColor};">
                        <span class="top-list-rank" style="color: ${rankColor};">${i+1}.</span>
                        <span class="top-list-player">
                            ${playerIdByKey.get(`${s.teamName}|${s.playerName}`) && teamIdByName.get(s.teamName)
                                ? `<span class="cs-clickable" onclick="loadLeagueTeamPlayer(${playerIdByKey.get(`${s.teamName}|${s.playerName}`)}, ${teamIdByName.get(s.teamName)}, '${htmlEscape(s.teamName)}')">${s.playerName}</span>`
                                : s.playerName}
                            <small style="color: #888;">(${teamIdByName.get(s.teamName) ? `<span class="cs-clickable" onclick="loadLeagueTeam(${teamIdByName.get(s.teamName)}, '${htmlEscape(s.teamName)}')">${s.teamName}</span>` : s.teamName})</small>
                        </span>
                        <span class="top-list-value" style="color: #ff7582;">
                            ${s.goals} goals
                        </span>
                    </li>`;
                });
            }

            if (mode !== "assists") {
                html += `</ul></div>`;
            }

            if (mode !== "scorers") {
                html += `<div class="top-assists top-list-panel">
                        <h3 style="text-align: center; color: #9d4edd; margin-bottom: 15px;">Top Assists</h3>
                        <ul style="list-style: none; padding: 0; margin: 0;">`;
            }

            if (mode !== "scorers") {
                assists.forEach((a, i) => {
                    const rankColor = i < 3 ? '#9d4edd' : '#aaa';
                    const bgColor = i % 2 === 0 ? 'rgba(255,255,255,0.05)' : 'rgba(0,0,0,0.1)';
                    html += `
                    <li class="top-list-entry" style="background: ${bgColor};">
                        <span class="top-list-rank" style="color: ${rankColor};">${i+1}.</span>
                        <span class="top-list-player">
                            ${playerIdByKey.get(`${a.teamName}|${a.playerName}`) && teamIdByName.get(a.teamName)
                                ? `<span class="cs-clickable" onclick="loadLeagueTeamPlayer(${playerIdByKey.get(`${a.teamName}|${a.playerName}`)}, ${teamIdByName.get(a.teamName)}, '${htmlEscape(a.teamName)}')">${a.playerName}</span>`
                                : a.playerName}
                            <small style="color: #888;">(${teamIdByName.get(a.teamName) ? `<span class="cs-clickable" onclick="loadLeagueTeam(${teamIdByName.get(a.teamName)}, '${htmlEscape(a.teamName)}')">${a.teamName}</span>` : a.teamName})</small>
                        </span>
                        <span class="top-list-value" style="color: #4fc3f7;">
                            ${a.assists} assists
                        </span>
                    </li>`;
                });
            }

            if (mode !== "scorers") {
                html += `</ul></div>`;
            }
            html += `</div></div>`;

            mainContent.innerHTML = html;

            document.querySelectorAll('.top-lists li').forEach(li => {
                li.addEventListener('mouseenter', () => {
                    li.style.background = 'rgba(157, 78, 221, 0.15)';
                    li.style.transform = 'translateX(5px)';
                });
                li.addEventListener('mouseleave', () => {
                    li.style.background = li.style.background.includes('0.05') ? 'rgba(255,255,255,0.05)' : 'rgba(0,0,0,0.1)';
                    li.style.transform = 'translateX(0)';
                });
            });

        } catch (err) {
            console.error("Error loading top lists:", err);
            renderTopListsError(`Could not load the top lists: ${err.message}`);
        }
    }

    return { loadTopScorersAndAssists, loadPlayerStats };
}
