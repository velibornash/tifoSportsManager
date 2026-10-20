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
    mc.innerHTML = `<div class="fm-panel" style="max-width:800px;margin:0 auto;">${cmBuildEmptyState('Transfers — coming soon')}</div>`;
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
window.afRenderTransfers = afRenderTransfers;
