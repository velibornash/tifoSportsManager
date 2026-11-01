// bb-router.js — Page router and shared state for basketball manager

let bbCurrentTeamId = null;
let bbTeams = [];

async function bbInit(teamId) {
    if (teamId) {
        bbCurrentTeamId = teamId;
    } else if (!bbCurrentTeamId) {
        try {
            const myTeam = await window.bbFetchMyTeam();
            if (myTeam.hasTeam) {
                bbCurrentTeamId = myTeam.teamId;
            }
        } catch (e) {
            console.error('Failed to detect user team', e);
        }
    }
    if (!bbCurrentTeamId) bbCurrentTeamId = 1;
    try {
        bbTeams = await window.bbFetchTeams();
    } catch (e) {
        console.error('Failed to fetch teams', e);
    }
    window.bbCurrentTeamId = bbCurrentTeamId;
    window.bbTeams = bbTeams;
}

function _team(id) { return bbTeams.find(t => t.id === Number(id)) || (bbTeams.length > 0 ? bbTeams[0] : null); }

async function loadPage(page, options = {}) {
    const mainContent = document.getElementById('main-content');
    if (!mainContent) return;
    mainContent.innerHTML = '<div style="text-align:center;padding:40px;color:#99a6bb;">Loading...</div>';
    try {
        switch(page) {
            case 'firstTeam': await window.bbRenderTeam(); break;
            case 'leagueTable': await window.bbRenderLeagueTable(); break;
            case 'stats': await window.bbRenderStats(); break;
            case 'schedule': await window.bbRenderSchedule(); break;
            case 'transfers': await bbRenderTransfers(); break;
            case 'tactics': await bbRenderTactics(); break;
            case 'training': await bbRenderTraining(); break;
            case 'inbox': await bbRenderInbox(); break;
            case 'player': await window.bbRenderPlayer(options?.playerId); break;
            case 'matchViewer': await window.bbRenderMatchViewer(options); break;
            default: await window.bbRenderDashboard();
        }
    } catch (e) {
        console.error('Page load error', e);
        mainContent.innerHTML = `<div class="fm-panel">${cmBuildEmptyState('Error loading page')}</div>`;
    }
}

async function bbRenderTransfers() {
    const mc = document.getElementById('main-content');
    if (!mc) return;
    await window.bbFetchMyTeam();
    const budget = window.BBALL_CONF.budget || 0;
    const myPlayers = await window.bbFetchTeamPlayers(window.bbCurrentTeamId);
    const market = await window.bbFetchTransferMarket(80);

    const marketRows = market.map(p => {
        const ovr = p.overall || window.bbCalculateOverall(p.skills, p.position);
        const price = Math.max(5000, ovr * 10000);
        return `<tr>
            <td class="player-name-cell">${cmEscapeHtml(p.name)}</td>
            <td><span class="pos-badge ${p.position}">${p.position}</span></td>
            <td style="font-weight:900;">${ovr}</td>
            <td>${cmEscapeHtml(p.teamName || '')}</td>
            <td style="font-weight:700;color:#f5a623;">${price.toLocaleString()}</td>
            <td><button class="play-all-btn" style="font-size:0.75rem;padding:4px 10px;" onclick="bbBuyPlayerClick(${p.id})">Buy</button></td>
        </tr>`;
    }).join('');

    const sellRows = myPlayers.map(p => {
        const ovr = p.overall || window.bbCalculateOverall(p.skills, p.position);
        const price = Math.max(5000, ovr * 8000);
        return `<tr>
            <td class="player-name-cell">${cmEscapeHtml(p.name)}</td>
            <td><span class="pos-badge ${p.position}">${p.position}</span></td>
            <td style="font-weight:900;">${ovr}</td>
            <td style="font-weight:700;color:#6fcf97;">${price.toLocaleString()}</td>
            <td><button class="reset-btn" style="font-size:0.75rem;padding:4px 10px;" onclick="bbSellPlayerClick(${p.id})">Sell</button></td>
        </tr>`;
    }).join('');

    mc.innerHTML = `
        <div class="fm-panel">
            <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:16px;flex-wrap:wrap;gap:10px;">
                <div>
                    <div class="bball-eyebrow">Transfer Centre</div>
                    <h2 style="margin:0;">Transfers</h2>
                </div>
                <div style="display:flex;align-items:center;gap:12px;">
                    <span style="font-weight:700;color:#f5a623;">Budget: ${budget.toLocaleString()}</span>
                    <button class="back-button" onclick="loadPage('dashboard')">← Dashboard</button>
                </div>
            </div>

            <h3 style="margin:12px 0 8px;">Buy Players (Market)</h3>
            <div style="overflow-x:auto;">
                <table class="player-table">
                    <thead><tr>
                        <th style="text-align:left;">Player</th><th>Pos</th><th>OVR</th><th>Club</th><th>Price</th><th></th>
                    </tr></thead>
                    <tbody>${marketRows || '<tr><td colspan="6" style="text-align:center;color:#99a6bb;">No players available</td></tr>'}</tbody>
                </table>
            </div>

            <h3 style="margin:20px 0 8px;">Sell Players (Your Squad)</h3>
            <div style="overflow-x:auto;">
                <table class="player-table">
                    <thead><tr>
                        <th style="text-align:left;">Player</th><th>Pos</th><th>OVR</th><th>Sale Price</th><th></th>
                    </tr></thead>
                    <tbody>${sellRows || '<tr><td colspan="5" style="text-align:center;color:#99a6bb;">No players</td></tr>'}</tbody>
                </table>
            </div>
        </div>`;
}

async function bbBuyPlayerClick(playerId) {
    const res = await window.bbBuyPlayer(playerId);
    if (res && res.ok) {
        alert('Bought ' + res.playerName + ' for ' + (res.price || 0).toLocaleString());
        loadPage('transfers');
    } else {
        alert(res && res.error ? res.error : 'Buy failed');
    }
}

async function bbSellPlayerClick(playerId) {
    if (!confirm('Sell this player?')) return;
    const res = await window.bbSellPlayer(playerId);
    if (res && res.ok) {
        alert('Sold ' + res.playerName + ' to ' + res.destination + ' for ' + (res.price || 0).toLocaleString());
        loadPage('transfers');
    } else {
        alert(res && res.error ? res.error : 'Sell failed');
    }
}

function bbSelectTeam(teamId) {
    bbCurrentTeamId = teamId;
    window.bbCurrentTeamId = teamId;
    window.BBALL_CONF.teamId = teamId;
    loadPage('firstTeam');
}

function bbShowPlayer(playerId) {
    loadPage('player', { playerId });
}

window.bbCurrentTeamId = bbCurrentTeamId;
window.bbTeams = bbTeams;
window.bbInit = bbInit;
window._team = _team;
window.bbSelectTeam = bbSelectTeam;
window.bbShowPlayer = bbShowPlayer;
async function bbRenderTactics() {
    const mc = document.getElementById('main-content');
    if (!mc) return;
    if (!window.bbTeams.length) await window.bbInit();
    const team = window._team(window.bbCurrentTeamId);
    const players = await window.bbFetchTeamPlayers(window.bbCurrentTeamId);
    const tac = await window.bbFetchTactics(window.bbCurrentTeamId);
    const selected = new Set((tac.starterIds || '').split(',').filter(Boolean).map(Number));
    const style = tac.playStyle || 'BALANCED';

    const styleButtons = ['OFFENSIVE', 'BALANCED', 'DEFENSIVE', 'COUNTER'].map(s => `
        <button class="${s === style ? 'play-all-btn' : 'reset-btn'}" style="font-size:0.8rem;padding:6px 12px;" onclick="bbTacticsStyle='${s}'; document.querySelectorAll('.bb-style-btn').forEach(b=>b.classList.remove('play-all-btn')); this.classList.add('play-all-btn');">${s}</button>
    `).join('');

    const rows = players.map(p => {
        const ovr = p.overall || window.bbCalculateOverall(p.skills, p.position);
        const checked = selected.has(p.id) ? 'checked' : '';
        return `<tr>
            <td><input type="checkbox" class="bb-starter-cb" value="${p.id}" ${checked}></td>
            <td class="player-name-cell">${cmEscapeHtml(p.name)}</td>
            <td><span class="pos-badge ${p.position}">${p.position}</span></td>
            <td style="font-weight:900;">${ovr}</td>
        </tr>`;
    }).join('');

    mc.innerHTML = `
        <div class="fm-panel">
            <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:16px;flex-wrap:wrap;gap:10px;">
                <div>
                    <div class="bball-eyebrow">Team Settings</div>
                    <h2 style="margin:0;">Tactics</h2>
                </div>
                <button class="back-button" onclick="loadPage('dashboard')">← Dashboard</button>
            </div>

            <div style="margin-bottom:16px;">
                <h3 style="margin:0 0 8px;">Play Style</h3>
                <div style="display:flex;gap:8px;flex-wrap:wrap;" id="bb-style-buttons">
                    ${['OFFENSIVE','BALANCED','DEFENSIVE','COUNTER'].map(s => `
                        <button class="bb-style-btn ${s === style ? 'play-all-btn' : 'reset-btn'}" data-style="${s}" style="font-size:0.8rem;padding:6px 12px;" onclick="window.bbTacticsStyle='${s}'; document.querySelectorAll('.bb-style-btn').forEach(b=>{b.classList.remove('play-all-btn');b.classList.add('reset-btn');}); this.classList.remove('reset-btn'); this.classList.add('play-all-btn');">${s}</button>
                    `).join('')}
                </div>
                <p style="font-size:0.75rem;color:#99a6bb;margin-top:6px;">Starters get more shot opportunities. Offensive increases shot volume, Defensive reduces it.</p>
            </div>

            <h3 style="margin:12px 0 8px;">Starting Five (select 5)</h3>
            <div style="overflow-x:auto;">
                <table class="player-table">
                    <thead><tr><th></th><th style="text-align:left;">Player</th><th>Pos</th><th>OVR</th></tr></thead>
                    <tbody>${rows}</tbody>
                </table>
            </div>
            <div style="margin-top:12px;">
                <button class="play-all-btn" onclick="bbSaveTacticsClick()">Save Tactics</button>
            </div>
        </div>`;
    window.bbTacticsStyle = style;
}

async function bbSaveTacticsClick() {
    const checked = Array.from(document.querySelectorAll('.bb-starter-cb:checked')).map(cb => cb.value);
    if (checked.length > 5) { alert('Select at most 5 starters'); return; }
    const style = window.bbTacticsStyle || 'BALANCED';
    const res = await window.bbSaveTactics(window.bbCurrentTeamId, style, checked.join(','));
    if (res && res.ok) {
        alert('Tactics saved');
        loadPage('dashboard');
    } else {
        alert(res && res.error ? res.error : 'Save failed');
    }
}

async function bbRenderTraining() {
    const mc = document.getElementById('main-content');
    if (!mc) return;
    if (!window.bbTeams.length) await window.bbInit();
    const team = window._team(window.bbCurrentTeamId);
    const tr = await window.bbFetchTraining(window.bbCurrentTeamId);
    const focus = tr.focus || 'BALANCED';
    const players = await window.bbFetchTeamPlayers(window.bbCurrentTeamId);

    const focusButtons = ['BALANCED', 'ATTACK', 'DEFENCE', 'CREATION'].map(f => `
        <button class="bb-focus-btn ${f === focus ? 'play-all-btn' : 'reset-btn'}" data-focus="${f}" style="font-size:0.8rem;padding:6px 12px;" onclick="window.bbTrainingFocus='${f}'; document.querySelectorAll('.bb-focus-btn').forEach(b=>{b.classList.remove('play-all-btn');b.classList.add('reset-btn');}); this.classList.remove('reset-btn'); this.classList.add('play-all-btn');">${f}</button>
    `).join('');

    const rows = players.map(p => {
        const ovr = p.overall || window.bbCalculateOverall(p.skills, p.position);
        const s = p.skills || {};
        return `<tr>
            <td class="player-name-cell">${cmEscapeHtml(p.name)}</td>
            <td><span class="pos-badge ${p.position}">${p.position}</span></td>
            <td style="font-weight:900;">${ovr}</td>
            <td>${cmSkillBar(s.pace)}</td>
            <td>${cmSkillBar(s.twoPtShot)}</td>
            <td>${cmSkillBar(s.threePtShot)}</td>
            <td>${cmSkillBar(s.rebounding)}</td>
            <td>${cmSkillBar(s.playmaking)}</td>
        </tr>`;
    }).join('');

    mc.innerHTML = `
        <div class="fm-panel">
            <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:16px;flex-wrap:wrap;gap:10px;">
                <div>
                    <div class="bball-eyebrow">Team Development</div>
                    <h2 style="margin:0;">Training</h2>
                </div>
                <button class="back-button" onclick="loadPage('dashboard')">← Dashboard</button>
            </div>

            <div style="margin-bottom:16px;">
                <h3 style="margin:0 0 8px;">Training Focus</h3>
                <div style="display:flex;gap:8px;flex-wrap:wrap;">${focusButtons}</div>
                <p style="font-size:0.75rem;color:#99a6bb;margin-top:6px;">One squad-wide focus per session. Click "Train Now" to apply development.</p>
            </div>
            <div style="margin-bottom:16px;">
                <button class="play-all-btn" onclick="bbTrainClick()">Train Now</button>
            </div>

            <div style="overflow-x:auto;">
                <table class="player-table">
                    <thead><tr>
                        <th style="text-align:left;">Player</th><th>Pos</th><th>OVR</th>
                        <th>Pace</th><th>2PT</th><th>3PT</th><th>Reb</th><th>Ply</th>
                    </tr></thead>
                    <tbody>${rows}</tbody>
                </table>
            </div>
        </div>`;
    window.bbTrainingFocus = focus;
}

async function bbTrainClick() {
    const focus = window.bbTrainingFocus || 'BALANCED';
    await window.bbSetTrainingFocus(window.bbCurrentTeamId, focus);
    const res = await window.bbTrain(window.bbCurrentTeamId);
    if (res && res.ok) {
        alert('Training complete: ' + (res.playersImproved || 0) + ' of ' + (res.playersTrained || 0) + ' players improved.');
        loadPage('training');
    } else {
        alert(res && res.error ? res.error : 'Training failed');
    }
}

async function bbRenderInbox() {
    const mc = document.getElementById('main-content');
    if (!mc) return;
    const msgs = await window.bbFetchInbox();
    const rows = msgs.map(m => `
        <div class="match-row" style="flex-direction:column;align-items:flex-start;gap:4px;padding:10px 0;border-bottom:1px solid rgba(255,255,255,0.06);cursor:pointer;" onclick="bbInboxRead(${m.id})">
            <div style="display:flex;gap:8px;align-items:center;width:100%;">
                <span style="font-size:0.7rem;text-transform:uppercase;color:var(--bball-primary-light);">${cmEscapeHtml(m.desk || '')}</span>
                <span style="font-weight:700;flex:1;">${cmEscapeHtml(m.title || '')}</span>
                ${m.read ? '' : '<span style="color:#f5a623;font-size:0.7rem;">● unread</span>'}
            </div>
            <div style="font-size:0.85rem;color:#99a6bb;">${cmEscapeHtml(m.body || '')}</div>
            <div style="font-size:0.7rem;color:#666;">${cmFormatDate(m.createdAt)}</div>
        </div>
    `).join('');

    mc.innerHTML = `
        <div class="fm-panel">
            <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:16px;flex-wrap:wrap;gap:10px;">
                <div>
                    <div class="bball-eyebrow">News</div>
                    <h2 style="margin:0;">Inbox</h2>
                </div>
                <div style="display:flex;gap:8px;">
                    <button class="reset-btn" style="font-size:0.75rem;padding:4px 10px;" onclick="bbInboxMarkAll()">Mark all read</button>
                    <button class="back-button" onclick="loadPage('dashboard')">← Dashboard</button>
                </div>
            </div>
            ${rows || cmBuildEmptyState('No messages yet')}
        </div>`;
}

async function bbInboxRead(id) {
    await window.bbMarkInboxRead(id);
    loadPage('inbox');
}
async function bbInboxMarkAll() {
    await window.bbMarkAllInboxRead();
    loadPage('inbox');
}

window.bbRenderInbox = bbRenderInbox;
window.bbRenderTraining = bbRenderTraining;
window.bbRenderTactics = bbRenderTactics;
window.loadPage = loadPage;
