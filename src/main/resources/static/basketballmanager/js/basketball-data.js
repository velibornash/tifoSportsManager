// basketball-data.js - API layer and helpers for basketball manager

let BBALL_CONF = {
    leagueName: 'Košarkaška Liga Srbije',
    shortName: 'KLS',
    country: 'Serbia',
    tier: 1,
    pointsWin: 3,
    pointsDraw: 1,
    pointsLoss: 0,
    seasonYear: 2025,
    competitionId: null,
    teamId: null,
    teamName: null,
    teamShortName: null,
    teamColor: null,
    budget: 0,
    quarters: 4,
    quarterMinutes: 10,
};

// ─── API Cache ───
let _bbApiCache = {
    teams: null,
    teamsPromise: null,
    players: {},
    playersPromise: {},
    table: null,
    tablePromise: null,
    leagueLeaders: null,
    leagueLeadersPromise: null,
    teamMatches: {},
    teamMatchesPromise: {},
    myTeam: null,
    myTeamPromise: null,
};

function bbInvalidateCache() {
    _bbApiCache = {
        teams: null, teamsPromise: null,
        players: {}, playersPromise: {},
        table: null, tablePromise: null,
        leagueLeaders: null, leagueLeadersPromise: null,
        teamMatches: {}, teamMatchesPromise: {},
        myTeam: null, myTeamPromise: null,
    };
}

// ─── My Team (authenticated user) ───

async function bbFetchMyTeam() {
    if (_bbApiCache.myTeam) return _bbApiCache.myTeam;
    if (_bbApiCache.myTeamPromise) return _bbApiCache.myTeamPromise;
    _bbApiCache.myTeamPromise = (async () => {
        try {
            const res = await window.authFetch('/api/bb/my-team');
            const data = await res.json();
            _bbApiCache.myTeam = data;
            if (data.hasTeam) {
                BBALL_CONF.teamId = data.teamId;
                BBALL_CONF.teamName = data.teamName;
                BBALL_CONF.teamShortName = data.teamShortName;
                BBALL_CONF.teamColor = data.teamColor;
                BBALL_CONF.budget = data.budget || 0;
                BBALL_CONF.competitionId = data.competitionId;
                BBALL_CONF.seasonYear = data.seasonYear || 2025;
            }
            return data;
        } finally {
            _bbApiCache.myTeamPromise = null;
        }
    })();
    return _bbApiCache.myTeamPromise;
}

// ─── API Fetch Functions ───

async function bbFetchTeams() {
    if (_bbApiCache.teams) return _bbApiCache.teams;
    if (_bbApiCache.teamsPromise) return _bbApiCache.teamsPromise;
    _bbApiCache.teamsPromise = (async () => {
        try {
            const res = await window.authFetch('/api/bb/teams');
            const data = await res.json();
            _bbApiCache.teams = data;
            return data;
        } finally {
            _bbApiCache.teamsPromise = null;
        }
    })();
    return _bbApiCache.teamsPromise;
}

async function bbFetchTeamPlayers(teamId) {
    if (_bbApiCache.players[teamId]) return _bbApiCache.players[teamId];
    if (_bbApiCache.playersPromise[teamId]) return _bbApiCache.playersPromise[teamId];
    _bbApiCache.playersPromise[teamId] = (async () => {
        try {
            const res = await window.authFetch('/api/bb/teams/' + teamId + '/players');
            const data = await res.json();
            _bbApiCache.players[teamId] = data;
            return data;
        } finally {
            _bbApiCache.playersPromise[teamId] = null;
        }
    })();
    return _bbApiCache.playersPromise[teamId];
}

async function bbFetchLeagueTable() {
    if (_bbApiCache.table) return _bbApiCache.table;
    if (_bbApiCache.tablePromise) return _bbApiCache.tablePromise;
    _bbApiCache.tablePromise = (async () => {
        try {
            await bbFetchMyTeam();
            const compId = BBALL_CONF.competitionId || 32;
            const res = await window.authFetch('/api/bb/leagues/' + compId + '/table?seasonYear=' + BBALL_CONF.seasonYear);
            const data = await res.json();
            _bbApiCache.table = data;
            return data;
        } finally {
            _bbApiCache.tablePromise = null;
        }
    })();
    return _bbApiCache.tablePromise;
}

async function bbFetchLeagueLeaders() {
    if (_bbApiCache.leagueLeaders) return _bbApiCache.leagueLeaders;
    if (_bbApiCache.leagueLeadersPromise) return _bbApiCache.leagueLeadersPromise;
    _bbApiCache.leagueLeadersPromise = (async () => {
        try {
            await bbFetchMyTeam();
            const compId = BBALL_CONF.competitionId || 32;
            const res = await window.authFetch('/api/bb/stats/league/' + compId + '?limit=10');
            const data = await res.json();
            _bbApiCache.leagueLeaders = data;
            return data;
        } finally {
            _bbApiCache.leagueLeadersPromise = null;
        }
    })();
    return _bbApiCache.leagueLeadersPromise;
}

async function bbFetchTeamMatches(teamId) {
    if (_bbApiCache.teamMatches[teamId]) return _bbApiCache.teamMatches[teamId];
    if (_bbApiCache.teamMatchesPromise[teamId]) return _bbApiCache.teamMatchesPromise[teamId];
    _bbApiCache.teamMatchesPromise[teamId] = (async () => {
        try {
            const res = await window.authFetch('/api/bb/matches/team/' + teamId + '?seasonYear=' + BBALL_CONF.seasonYear);
            const data = await res.json();
            _bbApiCache.teamMatches[teamId] = data;
            return data;
        } finally {
            _bbApiCache.teamMatchesPromise[teamId] = null;
        }
    })();
    return _bbApiCache.teamMatchesPromise[teamId];
}

async function bbFetchTeamFixtures(teamId) {
    try {
        const res = await window.authFetch('/api/bb/fixtures/team/' + teamId);
        return await res.json();
    } catch { return []; }
}

async function bbPlayFixture(fixtureId) {
    try {
        const res = await window.authFetch('/api/bb/fixtures/' + fixtureId + '/play', { method: 'POST' });
        if (!res.ok) {
            const text = await res.text();
            console.error('bbPlayFixture error', fixtureId, res.status, text);
            return null;
        }
        return await res.json();
    } catch (e) {
        console.error('bbPlayFixture exception', fixtureId, e);
        return null;
    }
}

async function bbFetchMatchDetail(matchId) {
    try {
        const res = await window.authFetch('/api/bb/matches/' + matchId);
        if (!res.ok) return null;
        return await res.json();
    } catch (e) {
        console.error('bbFetchMatchDetail exception', matchId, e);
        return null;
    }
}

async function bbFetchTactics(teamId) {
    try {
        const res = await window.authFetch('/api/bb/teams/' + teamId + '/tactics');
        if (!res.ok) return { playStyle: 'BALANCED', starterIds: '' };
        return await res.json();
    } catch { return { playStyle: 'BALANCED', starterIds: '' }; }
}

async function bbSaveTactics(teamId, playStyle, starterIds) {
    try {
        const res = await window.authFetch('/api/bb/teams/' + teamId + '/tactics', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ playStyle, starterIds })
        });
        const data = await res.json();
        if (!res.ok) return { ok: false, error: data.error || 'Save failed' };
        return data;
    } catch (e) { return { ok: false, error: e.message }; }
}

async function bbFetchTraining(teamId) {
    try {
        const res = await window.authFetch('/api/bb/teams/' + teamId + '/training');
        if (!res.ok) return { focus: 'BALANCED' };
        return await res.json();
    } catch { return { focus: 'BALANCED' }; }
}
async function bbSetTrainingFocus(teamId, focus) {
    try {
        const res = await window.authFetch('/api/bb/teams/' + teamId + '/training/focus', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ focus })
        });
        const data = await res.json();
        if (!res.ok) return { ok: false, error: data.error };
        return data;
    } catch (e) { return { ok: false, error: e.message }; }
}
async function bbTrain(teamId) {
    try {
        const res = await window.authFetch('/api/bb/teams/' + teamId + '/training/train', { method: 'POST' });
        const data = await res.json();
        if (!res.ok) return { ok: false, error: data.error };
        return data;
    } catch (e) { return { ok: false, error: e.message }; }
}

async function bbStartNewSeason() {
    try {
        const res = await window.authFetch('/api/bb/season/new', { method: 'POST' });
        const data = await res.json();
        if (!res.ok) return { ok: false, error: data.error };
        if (data.newSeason) window.BBALL_CONF.seasonYear = data.newSeason;
        window.bbInvalidateCache();
        return data;
    } catch (e) { return { ok: false, error: e.message }; }
}

async function bbFetchInbox() {
    try {
        const res = await window.authFetch('/api/bb/inbox');
        if (!res.ok) return [];
        return await res.json();
    } catch { return []; }
}
async function bbMarkInboxRead(id) {
    try { await window.authFetch('/api/bb/inbox/' + id + '/read', { method: 'POST' }); } catch {}
}
async function bbMarkAllInboxRead() {
    try { await window.authFetch('/api/bb/inbox/read-all', { method: 'POST' }); } catch {}
}
async function bbFetchUnreadCount() {
    try {
        const res = await window.authFetch('/api/bb/inbox/unread-count');
        if (!res.ok) return 0;
        const d = await res.json();
        return d.count || 0;
    } catch { return 0; }
}

async function bbFetchRecentMatches(teamId, limit = 3) {
    try {
        const res = await window.authFetch('/api/bb/matches/team/' + teamId + '/recent?limit=' + limit + '&seasonYear=' + BBALL_CONF.seasonYear);
        if (!res.ok) return [];
        return await res.json();
    } catch (e) {
        console.error('bbFetchRecentMatches exception', teamId, e);
        return [];
    }
}

// ─── Transfers ───

async function bbFetchTransferMarket(limit = 100) {
    try {
        const res = await window.authFetch('/api/bb/transfers/market?limit=' + limit);
        if (!res.ok) return [];
        return await res.json();
    } catch (e) { console.error(e); return []; }
}

async function bbBuyPlayer(playerId) {
    try {
        const res = await window.authFetch('/api/bb/transfers/buy/' + playerId, { method: 'POST' });
        const data = await res.json();
        if (!res.ok) return { ok: false, error: data.error || 'Buy failed' };
        window.BBALL_CONF.budget = data.budget || window.BBALL_CONF.budget;
        window.bbInvalidateCache();
        return data;
    } catch (e) { console.error(e); return { ok: false, error: e.message }; }
}

async function bbSellPlayer(playerId) {
    try {
        const res = await window.authFetch('/api/bb/transfers/sell/' + playerId, { method: 'POST' });
        const data = await res.json();
        if (!res.ok) return { ok: false, error: data.error || 'Sell failed' };
        window.BBALL_CONF.budget = data.budget || window.BBALL_CONF.budget;
        window.bbInvalidateCache();
        return data;
    } catch (e) { console.error(e); return { ok: false, error: e.message }; }
}

// ─── Overall Rating (client-side, from skills map) ───

function bbCalculateOverall(skills, position) {
    const weights = {
        PG: { pace: 0.2, steals: 0.1, blocks: 0.05, freeThrows: 0.05, twoPtShot: 0.1, threePtShot: 0.2, rebounding: 0.05, playmaking: 0.25 },
        SG: { pace: 0.15, steals: 0.1, blocks: 0.05, freeThrows: 0.1, twoPtShot: 0.2, threePtShot: 0.25, rebounding: 0.05, playmaking: 0.1 },
        SF: { pace: 0.15, steals: 0.1, blocks: 0.1, freeThrows: 0.05, twoPtShot: 0.25, threePtShot: 0.1, rebounding: 0.15, playmaking: 0.1 },
        PF: { pace: 0.1, steals: 0.05, blocks: 0.2, freeThrows: 0.05, twoPtShot: 0.25, threePtShot: 0.05, rebounding: 0.25, playmaking: 0.05 },
        C:  { pace: 0.05, steals: 0.05, blocks: 0.25, freeThrows: 0.1, twoPtShot: 0.2, threePtShot: 0, rebounding: 0.3, playmaking: 0.05 },
    };
    const w = weights[position] || weights.SF;
    let ovr = 0;
    for (const key in w) {
        ovr += (skills[key] || 5) * w[key];
    }
    return Math.round(ovr);
}

function bbPositions() { return ['PG', 'SG', 'SF', 'PF', 'C']; }

// ─── Exports ───
window.BBALL_CONF = BBALL_CONF;
window.bbFetchMyTeam = bbFetchMyTeam;
window.bbFetchTeams = bbFetchTeams;
window.bbFetchTeamPlayers = bbFetchTeamPlayers;
window.bbFetchLeagueTable = bbFetchLeagueTable;
window.bbFetchLeagueLeaders = bbFetchLeagueLeaders;
window.bbFetchTeamMatches = bbFetchTeamMatches;
window.bbFetchTeamFixtures = bbFetchTeamFixtures;
window.bbPlayFixture = bbPlayFixture;
window.bbFetchMatchDetail = bbFetchMatchDetail;
window.bbFetchRecentMatches = bbFetchRecentMatches;
window.bbFetchTactics = bbFetchTactics;
window.bbSaveTactics = bbSaveTactics;
window.bbFetchTraining = bbFetchTraining;
window.bbStartNewSeason = bbStartNewSeason;
window.bbFetchInbox = bbFetchInbox;
window.bbMarkInboxRead = bbMarkInboxRead;
window.bbMarkAllInboxRead = bbMarkAllInboxRead;
window.bbFetchUnreadCount = bbFetchUnreadCount;
window.bbSetTrainingFocus = bbSetTrainingFocus;
window.bbTrain = bbTrain;
window.bbFetchTransferMarket = bbFetchTransferMarket;
window.bbBuyPlayer = bbBuyPlayer;
window.bbSellPlayer = bbSellPlayer;
window.bbCalculateOverall = bbCalculateOverall;
window.bbPositions = bbPositions;
window.bbInvalidateCache = bbInvalidateCache;
