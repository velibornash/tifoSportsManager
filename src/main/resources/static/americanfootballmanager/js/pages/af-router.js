let afCurrentTeamId = null;
let afTeams = [];

function _team(id) { return afTeams.find(t => t.id === Number(id)) || (afTeams.length > 0 ? afTeams[0] : null); }

async function afInit(teamId) {
    if (teamId) {
        afCurrentTeamId = teamId;
    } else if (!afCurrentTeamId) {
        try {
            const myTeam = await window.afFetchMyTeam();
            if (myTeam.hasTeam) {
                afCurrentTeamId = myTeam.teamId;
            }
        } catch (e) {
            console.error('Failed to detect user team', e);
        }
    }
    if (!afCurrentTeamId) afCurrentTeamId = 1;
    try {
        afTeams = await window.afFetchTeams();
    } catch (e) {
        console.error('Failed to fetch teams', e);
    }
    window.afCurrentTeamId = afCurrentTeamId;
    window.afTeams = afTeams;
}

async function loadPage(page, options = {}) {
    const mc = document.getElementById('main-content');
    if (!mc) return;
    mc.innerHTML = '<div style="text-align:center;padding:40px;color:#99a6bb;">Loading...</div>';
    try {
        switch(page) {
            case 'firstTeam': await window.afRenderTeam(); break;
            case 'leagueTable': await window.afRenderLeagueTable(); break;
            case 'stats': await window.afRenderStats(); break;
            case 'schedule': await window.afRenderSchedule(); break;
            case 'transfers': await afRenderTransfers(); break;
            case 'tactics': await afRenderTactics(); break;
            case 'player': await window.afRenderPlayer(options?.playerId); break;
            case 'matchViewer': await window.afRenderMatchViewer(options); break;
            default: await window.afRenderDashboard();
        }
    } catch (e) {
        console.error('Page load error', e);
        mc.innerHTML = `<div class="fm-panel">${cmBuildEmptyState('Error loading page')}</div>`;
    }
}

async function afRenderTransfers() {
    const mc = document.getElementById('main-content');
    if (!mc) return;
    await window.afFetchMyTeam();
    const budget = window.AF_CONF.budget || 0;
    const myPlayers = await window.afFetchTeamPlayers(window.afCurrentTeamId);
    const market = await window.afFetchTransferMarket(80);

    const marketRows = market.map(p => {
        const ovr = p.overall || 0;
        const price = Math.max(10000, ovr * 50000);
        return `<tr>
            <td class="player-name-cell">${cmEscapeHtml(p.name)}</td>
            <td>${p.position}</td>
            <td style="font-weight:900;">${ovr}</td>
            <td>${cmEscapeHtml(p.teamName || '')}</td>
            <td style="font-weight:700;color:#f5a623;">${price.toLocaleString()}</td>
            <td><button class="play-all-btn" style="font-size:0.75rem;padding:4px 10px;" onclick="afBuyPlayerClick(${p.id})">Buy</button></td>
        </tr>`;
    }).join('');

    const sellRows = myPlayers.map(p => {
        const ovr = p.overall || 0;
        const price = Math.max(10000, ovr * 40000);
        return `<tr>
            <td class="player-name-cell">${cmEscapeHtml(p.name)}</td>
            <td>${p.position}</td>
            <td style="font-weight:900;">${ovr}</td>
            <td style="font-weight:700;color:#6fcf97;">${price.toLocaleString()}</td>
            <td><button class="reset-btn" style="font-size:0.75rem;padding:4px 10px;" onclick="afSellPlayerClick(${p.id})">Sell</button></td>
        </tr>`;
    }).join('');

    mc.innerHTML = `
        <div class="fm-panel">
            <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:16px;flex-wrap:wrap;gap:10px;">
                <div>
                    <div class="af-eyebrow">Transfer Centre</div>
                    <h2 style="margin:0;">Transfers</h2>
                </div>
                <div style="display:flex;align-items:center;gap:12px;">
                    <span style="font-weight:700;color:#f5a623;">Cap Space: ${budget.toLocaleString()}</span>
                    <button class="back-button" onclick="loadPage('dashboard')">← Dashboard</button>
                </div>
            </div>
            <h3 style="margin:12px 0 8px;">Buy Players (Market)</h3>
            <div style="overflow-x:auto;">
                <table class="player-table">
                    <thead><tr><th style="text-align:left;">Player</th><th>Pos</th><th>OVR</th><th>Club</th><th>Price</th><th></th></tr></thead>
                    <tbody>${marketRows || '<tr><td colspan="6" style="text-align:center;color:#99a6bb;">No players available</td></tr>'}</tbody>
                </table>
            </div>
            <h3 style="margin:20px 0 8px;">Sell Players (Your Roster)</h3>
            <div style="overflow-x:auto;">
                <table class="player-table">
                    <thead><tr><th style="text-align:left;">Player</th><th>Pos</th><th>OVR</th><th>Sale Price</th><th></th></tr></thead>
                    <tbody>${sellRows || '<tr><td colspan="5" style="text-align:center;color:#99a6bb;">No players</td></tr>'}</tbody>
                </table>
            </div>
        </div>`;
}

async function afBuyPlayerClick(playerId) {
    const res = await window.afBuyPlayer(playerId);
    if (res && res.ok) { alert('Bought ' + res.playerName + ' for ' + (res.price||0).toLocaleString()); loadPage('transfers'); }
    else { alert(res && res.error ? res.error : 'Buy failed'); }
}
async function afSellPlayerClick(playerId) {
    if (!confirm('Sell this player?')) return;
    const res = await window.afSellPlayer(playerId);
    if (res && res.ok) { alert('Sold ' + res.playerName + ' to ' + res.destination + ' for ' + (res.price||0).toLocaleString()); loadPage('transfers'); }
    else { alert(res && res.error ? res.error : 'Sell failed'); }
}

function afSelectTeam(teamId) {
    afCurrentTeamId = teamId;
    window.afCurrentTeamId = teamId;
    window.AF_CONF.teamId = teamId;
    loadPage('firstTeam');
}

function afShowPlayer(playerId) {
    loadPage('player', { playerId });
}

window.afCurrentTeamId = afCurrentTeamId;
window.afTeams = afTeams;
window.afInit = afInit;
window._team = _team;
window.afSelectTeam = afSelectTeam;
window.afShowPlayer = afShowPlayer;
window.loadPage = loadPage;
async function afRenderTactics() {
    const mc = document.getElementById('main-content');
    if (!mc) return;
    if (!window.afTeams.length) await window.afInit();
    const players = await window.afFetchTeamPlayers(window.afCurrentTeamId);
    const tac = await window.afFetchTactics(window.afCurrentTeamId);
    const selected = new Set((tac.starterIds || '').split(',').filter(Boolean).map(Number));
    const style = tac.playStyle || 'BALANCED';

    const rows = players.map(p => {
        const ovr = p.overall || 0;
        const checked = selected.has(p.id) ? 'checked' : '';
        return `<tr>
            <td><input type="checkbox" class="af-starter-cb" value="${p.id}" ${checked}></td>
            <td class="player-name-cell">${cmEscapeHtml(p.name)}</td>
            <td>${p.position}</td>
            <td style="font-weight:900;">${ovr}</td>
        </tr>`;
    }).join('');

    mc.innerHTML = `
        <div class="fm-panel">
            <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:16px;flex-wrap:wrap;gap:10px;">
                <div><div class="af-eyebrow">Team Settings</div><h2 style="margin:0;">Tactics</h2></div>
                <button class="back-button" onclick="loadPage('dashboard')">← Dashboard</button>
            </div>
            <div style="margin-bottom:16px;">
                <h3 style="margin:0 0 8px;">Play-Calling Style</h3>
                <div style="display:flex;gap:8px;flex-wrap:wrap;">
                    ${['RUN_HEAVY','BALANCED','PASS_HEAVY'].map(s => `
                        <button class="af-style-btn ${s === style ? 'play-all-btn' : 'reset-btn'}" data-style="${s}" style="font-size:0.8rem;padding:6px 12px;" onclick="window.afTacticsStyle='${s}'; document.querySelectorAll('.af-style-btn').forEach(b=>{b.classList.remove('play-all-btn');b.classList.add('reset-btn');}); this.classList.remove('reset-btn'); this.classList.add('play-all-btn');">${s}</button>
                    `).join('')}
                </div>
            </div>
            <h3 style="margin:12px 0 8px;">Depth Chart (select starters)</h3>
            <div style="overflow-x:auto;">
                <table class="player-table">
                    <thead><tr><th></th><th style="text-align:left;">Player</th><th>Pos</th><th>OVR</th></tr></thead>
                    <tbody>${rows}</tbody>
                </table>
            </div>
            <div style="margin-top:12px;"><button class="play-all-btn" onclick="afSaveTacticsClick()">Save Tactics</button></div>
        </div>`;
    window.afTacticsStyle = style;
}

async function afSaveTacticsClick() {
    const checked = Array.from(document.querySelectorAll('.af-starter-cb:checked')).map(cb => cb.value);
    const style = window.afTacticsStyle || 'BALANCED';
    const res = await window.afSaveTactics(window.afCurrentTeamId, style, checked.join(','));
    if (res && res.ok) { alert('Tactics saved'); loadPage('dashboard'); }
    else { alert(res && res.error ? res.error : 'Save failed'); }
}

window.afRenderTactics = afRenderTactics;
window.afRenderTransfers = afRenderTransfers;
