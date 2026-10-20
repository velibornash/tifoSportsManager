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
    mc.innerHTML = `
        <div class="fm-panel">
            <button class="back-button" onclick="loadPage('')">← Back to Dashboard</button>
            <div class="bball-eyebrow">Transfer Centre</div>
            <h2 style="margin-bottom:16px;">Transfers</h2>
            ${cmBuildEmptyState('Transfer market coming soon', 'Player transfers and contract management will be available in the next update.')}
        </div>`;
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
window.loadPage = loadPage;
