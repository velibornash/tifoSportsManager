// pages/views/stats-view.js
import { htmlEscape } from './utils.js';

export function createStatsView(deps) {
    const {
        authFetch, getTeamId, ensureCurrentLeagueId,
        getLeagueSeasonYear, getSeasonYear,
        goBackSmart, renderPlayers, loadLeagueTeam, loadLeagueTeamPlayer
    } = deps;

    async function loadPlayerStats() {
        const teamId = getTeamId();
        console.log(`Loading player stats for userTeamId ${teamId}`);
        const response = await authFetch(`/demo/stats/teams/${teamId}/players`);
        console.log(`Response status: ${response.status}`);
        const players = await response.json();
        renderPlayers(players, "Player Stats");
    }

    async function loadTeamStats() {
        const teamId = getTeamId();
        console.log(`Loading team stats for ${teamId}`);
        const response = await authFetch(`/demo/stats/teams/${teamId}`);
        console.log(`Response status: ${response.status}`);
        const stats = await response.json();

        const mainContent = document.getElementById("main-content");

        let html = `
        <div class="manager-card">
            <button class="back-to-dashboard" data-nav-back="dashboard">Back</button>
            <h2>Team Stats</h2>
            <p>Goals: ${stats.goals}</p>
            <p>Conceded: ${stats.conceded}</p>
            <p>Possession: ${stats.possession}%</p>
            <p>Shots per game: ${stats.shots}</p>
        </div>`;
        mainContent.innerHTML = html;
    }

    async function loadTopScorersAndAssists(mode = "both") {
        try {
            const teamId = getTeamId();
            console.log(`Loading top scorers for ${teamId}`);
            const leagueId = await ensureCurrentLeagueId();
            if (!leagueId) return;
            const seasonParam = getLeagueSeasonYear() || getSeasonYear()
                ? `?seasonYear=${getLeagueSeasonYear() || getSeasonYear()}`
                : '';
            const [scorersRes, assistsRes, leagueTeamsRes] = await Promise.all([
                authFetch(`/stats/leagues/${leagueId}/topscorers${seasonParam}`),
                authFetch(`/stats/leagues/${leagueId}/topassists${seasonParam}`),
                authFetch(`/countries/leagues/${leagueId}/teams${seasonParam}`)
            ]);
            console.log(`Response status: ${scorersRes.status}`);
            console.log(`Response status: ${assistsRes.status}`);
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
            document.getElementById("main-content").innerHTML = `
                <div class="manager-card">
                    <button data-nav-back="dashboard">Back</button>
                    <h2>Error</h2>
                    <p>Could not load top lists. Check connection or backend.</p>
                </div>`;
        }
    }

    return { loadTopScorersAndAssists, loadPlayerStats, loadTeamStats };
}
