export function renderPlayersView(players, title, { loadPlayer, getImageFilename }) {
    const mainContent = document.getElementById("main-content");

    if (!Array.isArray(players)) {
        mainContent.innerHTML = `<div class="manager-card" style="text-align:center; padding:40px;"><h2>No players found</h2></div>`;
        return;
    }

    let html = `
    <div class="manager-card">
        <button class="back-to-dashboard" onclick="loadDashboard()">Back to Dashboard</button>
        <h2>${title}</h2>
        <div class="manager-grid">`;

    players.forEach(player => {
        const filename = getImageFilename(player.name);
        const rating = Number(player.rating);
        const form = Number(player.form);
        const ratingColor = Number.isFinite(rating)
            ? (rating >= 7.5 ? "#4caf50" : rating >= 6.5 ? "#ffd700" : rating >= 5.5 ? "#ff9800" : "#f44336")
            : "#9aa0a6";
        const formBadge = Number.isFinite(form)
            ? (form >= 7.8
                ? `<span class="form-badge hot">&#128293; ${form.toFixed(1)}</span>`
                : form <= 5.8
                    ? `<span class="form-badge cold">&#129482; ${form.toFixed(1)}</span>`
                    : `<span class="form-badge neutral">${form.toFixed(1)}</span>`)
            : `<span class="form-badge neutral">-</span>`;
        html += `
        <div class="manager-player-card" onclick="loadPlayer(${player.id})">
            <img src="/images/${filename}.jpg" onerror="this.src='/images/player.jpg'">
            <div class="player-name">${player.name}</div>
            <div class="player-meta">${player.position} - ${player.age}</div>
            <div class="player-rating">OVR ${player.overall}</div>
            <div class="player-meta">Rating: <span style="color:${ratingColor}; font-weight:700;">${Number.isFinite(rating) ? rating.toFixed(1) : "-"}</span> | Form: ${formBadge}</div>
        </div>`;
    });

    html += `</div></div>`;
    mainContent.innerHTML = html;

    mainContent.querySelectorAll('.player-card').forEach(card => {
        card.addEventListener('click', () => {
            loadPlayer(card.dataset.playerId);
        });
    });
}

export function renderMatchesView(matches, title, { loadMatch }) {
    const mainContent = document.getElementById("main-content");

    let html = `
    <div class="manager-card">
        <button class="back-to-dashboard" onclick="loadDashboard()">⬅ Back to Dashboard</button>
        <h2>${title}</h2>
        <div class="match-list">`;

    if (!Array.isArray(matches) || matches.length === 0) {
        html += `<p style="text-align:center; color:#aaa;">No matches to display.</p>`;
    } else {
        matches.forEach(match => {
            html += `
            <div class="match-row" data-match-id="${match.id}" data-caller="match">
                <div style="font-size:0.9em; color:#aaa; margin-bottom:4px;">🗓 ${match.matchDate || "N/A"}</div>
                <div class="match-teams">
                    <span class="team-home">${match.homeTeam}</span>
                    <span class="score">${match.homeGoals ?? "-"} : ${match.awayGoals ?? "-"}</span>
                    <span class="team-away">${match.awayTeam}</span>
                </div>
            </div>`;
        });
    }

    html += `</div></div>`;
    mainContent.innerHTML = html;

    document.getElementById("main-content").addEventListener('click', function(e) {
        const row = e.target.closest('.match-row');
        if (row) {
            const matchId = row.dataset.matchId;
            const caller = row.dataset.caller || 'match';
            if (matchId) loadMatch(matchId, caller);
        }
    });
}

export function renderFixturesView(fixtures, title) {
    const mainContent = document.getElementById("main-content");

    let html = `
    <div class="manager-card">
        <button class="back-to-dashboard" onclick="loadDashboard()">⬅ Back to Dashboard</button>
        <h2>${title}</h2>
        <div class="match-list">`;

    fixtures.forEach(fixture => {
        const fixtureId = fixture.id || fixtures.indexOf(fixture);
        html += `
        <div class="match-row upcoming-match" onclick="loadFixture(${fixtureId})">
            <div style="font-size:0.9em; color:#aaa; margin-bottom:4px;">🗓 ${fixture.matchDate || "N/A"} • ${fixture.matchTime || ""}</div>
            <span class="team-home">${fixture.homeTeam}</span>
            <span class="score">VS</span>
            <span class="team-away">${fixture.awayTeam}</span>
            <div style="font-size:0.85em; color:#888; margin-top:6px;">🏟️ ${fixture.stadiumName || "N/A"}</div>
        </div>`;
    });

    html += `</div></div>`;
    mainContent.innerHTML = html;
}

export function renderLeagueMatchesView(matches, title = "League Results", { loadMatch }) {
    const mainContent = document.getElementById("main-content");

    let html = `
    <div class="manager-card">
        <button class="back-to-dashboard" onclick="loadDashboard()">⬅ Back to Dashboard</button>
        <h2>${title}</h2>
        <div class="match-list">`;

    if (!Array.isArray(matches) || matches.length === 0) {
        html += `<p style="text-align:center; color:#aaa;">No matches in this league yet.</p>`;
    } else {
        matches.forEach(match => {
            let badgeClass = "";
            let badgeText = "";

            if (match.homeGoals !== null && match.awayGoals !== null) {
                if (match.homeGoals > match.awayGoals) {
                    badgeClass = "win";
                    badgeText = "1";
                } else if (match.homeGoals < match.awayGoals) {
                    badgeClass = "loss";
                    badgeText = "2";
                } else {
                    badgeClass = "draw";
                    badgeText = "X";
                }
            }

            html += `
            <div class="match-row" data-match-id="${match.id}" data-caller="leagueMatches">
                <div style="font-size:0.9em; color:#aaa;">${match.matchDate || "N/A"}</div>
                <div class="match-teams">
                    <span class="team-home">${match.homeTeam}</span>
                    <span class="score">${match.homeGoals ?? "-"} : ${match.awayGoals ?? "-"}</span>
                    <span class="team-away">${match.awayTeam}</span>
                </div>
                ${badgeText ? `<span class="result-badge ${badgeClass}">${badgeText}</span>` : ''}
            </div>`;
        });
    }

    html += `</div></div>`;
    mainContent.innerHTML = html;

    document.getElementById("main-content").addEventListener('click', function(e) {
        const row = e.target.closest('.match-row');
        if (row) {
            const matchId = row.dataset.matchId;
            const caller = row.dataset.caller || 'leagueMatches';
            if (matchId) loadMatch(matchId, caller);
        }
    });
}

export function renderTableView(table, { loadLeagueTeam, escapeHtml, formatGoalDiff }) {
    const mainContent = document.getElementById("main-content");
    const rows = Array.isArray(table) ? table : [];

    mainContent.innerHTML = `
    <div class="manager-card league-table-card" style="padding: 25px;">
        <button class="back-to-dashboard" onclick="loadDashboard()">Back to Dashboard</button>
        <h2 class="league-table-title">Superliga Table</h2>

        <div class="league-table-controls">
            <button type="button" class="table-view-btn active" data-view="normal">Normal</button>
            <button type="button" class="table-view-btn" data-view="full">Full</button>
        </div>

        <div style="overflow-x: auto;">
            <table class="league-table" style="width: 100%; border-collapse: collapse; font-size: 0.95rem;">
                <thead id="league-table-head"></thead>
                <tbody id="league-table-body"></tbody>
            </table>
        </div>
        <p class="league-table-updated">Updated: ${new Date().toLocaleString("en-US")}</p>
    </div>`;

    const headEl = document.getElementById("league-table-head");
    const bodyEl = document.getElementById("league-table-body");
    const controlButtons = mainContent.querySelectorAll(".table-view-btn");

    function headerHtml(mode) {
        if (mode === "full") {
            return `
                <tr style="background: rgba(157, 78, 221, 0.25); color: #fff;">
                    <th style="padding: 12px; text-align: center; border-bottom: 2px solid #555;">#</th>
                    <th style="padding: 12px; text-align: left; border-bottom: 2px solid #555;">Team</th>
                    <th style="padding: 12px; text-align: center; border-bottom: 2px solid #555;">P</th>
                    <th style="padding: 12px; text-align: center; border-bottom: 2px solid #555;">W</th>
                    <th style="padding: 12px; text-align: center; border-bottom: 2px solid #555;">D</th>
                    <th style="padding: 12px; text-align: center; border-bottom: 2px solid #555;">L</th>
                    <th style="padding: 12px; text-align: center; border-bottom: 2px solid #555;">GF</th>
                    <th style="padding: 12px; text-align: center; border-bottom: 2px solid #555;">GA</th>
                    <th style="padding: 12px; text-align: center; border-bottom: 2px solid #555;">GD</th>
                    <th style="padding: 12px; text-align: center; border-bottom: 2px solid #555;">Pts</th>
                </tr>`;
        }

        return `
            <tr style="background: rgba(157, 78, 221, 0.25); color: #fff;">
                <th style="padding: 12px; text-align: center; border-bottom: 2px solid #555;">#</th>
                <th style="padding: 12px; text-align: left; border-bottom: 2px solid #555;">Team</th>
                <th style="padding: 12px; text-align: center; border-bottom: 2px solid #555;">Pts</th>
                <th style="padding: 12px; text-align: center; border-bottom: 2px solid #555;">GD</th>
            </tr>`;
    }

    function rowHtml(team, index, mode) {
        const rank = Number(team.position || index + 1);
        const wins = Number(team.wins || 0);
        const draws = Number(team.draws || 0);
        const losses = Number(team.losses || 0);
        const played = wins + draws + losses;
        const gd = Number(team.goalDifference || 0);
        const rowBg = index % 2 === 0 ? "rgba(255,255,255,0.03)" : "rgba(0,0,0,0.08)";
        const gdColor = gd > 0 ? "#4caf50" : gd < 0 ? "#f44336" : "#aaa";
        const clickableClass = team.teamId ? "league-team-row clickable-team-row" : "league-team-row";
        const teamCell = `<td style="padding: 12px; font-weight: 600;">${escapeHtml(team.name)}</td>`;
        const commonAttrs = `class="${clickableClass}" style="background:${rowBg}; transition:all 0.2s;" data-team-id="${team.teamId || ""}" data-team-name="${escapeHtml(team.name)}"`;

        if (mode === "full") {
            return `
                <tr ${commonAttrs}>
                    <td style="padding: 12px; text-align: center; color:#aaa;">${rank}</td>
                    ${teamCell}
                    <td style="padding: 12px; text-align: center;">${played}</td>
                    <td style="padding: 12px; text-align: center;">${wins}</td>
                    <td style="padding: 12px; text-align: center;">${draws}</td>
                    <td style="padding: 12px; text-align: center;">${losses}</td>
                    <td style="padding: 12px; text-align: center;">${team.goalsScored ?? 0}</td>
                    <td style="padding: 12px; text-align: center;">${team.goalsConceded ?? 0}</td>
                    <td style="padding: 12px; text-align: center; color:${gdColor}; font-weight:700;">${formatGoalDiff(gd)}</td>
                    <td style="padding: 12px; text-align: center; font-weight:700; color:#ffd700;">${team.points ?? 0}</td>
                </tr>`;
        }

        return `
            <tr ${commonAttrs}>
                <td style="padding: 12px; text-align: center; color:#aaa;">${rank}</td>
                ${teamCell}
                <td style="padding: 12px; text-align: center; font-weight:700; color:#ffd700;">${team.points ?? 0}</td>
                <td style="padding: 12px; text-align: center; color:${gdColor}; font-weight:700;">${formatGoalDiff(gd)}</td>
            </tr>`;
    }

    function bindTeamRowClicks() {
        mainContent.querySelectorAll(".clickable-team-row").forEach(row => {
            row.addEventListener("click", () => {
                const teamId = Number(row.dataset.teamId);
                const teamName = row.dataset.teamName || "Team";
                if (teamId) loadLeagueTeam(teamId, teamName);
            });
        });
    }

    function setMode(mode) {
        headEl.innerHTML = headerHtml(mode);
        bodyEl.innerHTML = rows.map((team, index) => rowHtml(team, index, mode)).join("");
        controlButtons.forEach(btn => btn.classList.toggle("active", btn.dataset.view === mode));
        bindTeamRowClicks();
    }

    controlButtons.forEach(btn => {
        btn.addEventListener("click", () => setMode(btn.dataset.view || "normal"));
    });
    setMode("normal");
}
