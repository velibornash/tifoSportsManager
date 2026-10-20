// af-data.js - API layer and helpers for American Football manager

let AF_CONF = {
    leagueName: 'AF Liga Srbije',
    shortName: 'AFS',
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
    quarters: 4,
    quarterMinutes: 15,
};

let _afApiCache = {
    teams: null, teamsPromise: null,
    players: {}, playersPromise: {},
    table: null, tablePromise: null,
    leagueLeaders: null, leagueLeadersPromise: null,
    teamMatches: {}, teamMatchesPromise: {},
    myTeam: null, myTeamPromise: null,
};

function afInvalidateCache() {
    _afApiCache = {
        teams: null, teamsPromise: null,
        players: {}, playersPromise: {},
        table: null, tablePromise: null,
        leagueLeaders: null, leagueLeadersPromise: null,
        teamMatches: {}, teamMatchesPromise: {},
        myTeam: null, myTeamPromise: null,
    };
}

async function afFetchMyTeam() {
    if (_afApiCache.myTeam) return _afApiCache.myTeam;
    if (_afApiCache.myTeamPromise) return _afApiCache.myTeamPromise;
    _afApiCache.myTeamPromise = (async () => {
        try {
            const res = await window.authFetch('/api/af/my-team');
            const data = await res.json();
            _afApiCache.myTeam = data;
            if (data.hasTeam) {
                AF_CONF.teamId = data.teamId;
                AF_CONF.teamName = data.teamName;
                AF_CONF.teamShortName = data.teamShortName;
                AF_CONF.teamColor = data.teamColor;
                AF_CONF.competitionId = data.competitionId;
                AF_CONF.seasonYear = data.seasonYear || 2025;
            }
            return data;
        } finally {
            _afApiCache.myTeamPromise = null;
        }
    })();
    return _afApiCache.myTeamPromise;
}

async function afFetchTeams() {
    if (_afApiCache.teams) return _afApiCache.teams;
    if (_afApiCache.teamsPromise) return _afApiCache.teamsPromise;
    _afApiCache.teamsPromise = (async () => {
        try {
            const res = await window.authFetch('/api/af/teams');
            const data = await res.json();
            _afApiCache.teams = data;
            return data;
        } finally {
            _afApiCache.teamsPromise = null;
        }
    })();
    return _afApiCache.teamsPromise;
}

async function afFetchTeamPlayers(teamId) {
    if (_afApiCache.players[teamId]) return _afApiCache.players[teamId];
    if (_afApiCache.playersPromise[teamId]) return _afApiCache.playersPromise[teamId];
    _afApiCache.playersPromise[teamId] = (async () => {
        try {
            const res = await window.authFetch('/api/af/teams/' + teamId + '/players');
            const data = await res.json();
            _afApiCache.players[teamId] = data;
            return data;
        } finally {
            _afApiCache.playersPromise[teamId] = null;
        }
    })();
    return _afApiCache.playersPromise[teamId];
}

async function afFetchLeagueTable() {
    if (_afApiCache.table) return _afApiCache.table;
    if (_afApiCache.tablePromise) return _afApiCache.tablePromise;
    _afApiCache.tablePromise = (async () => {
        try {
            await afFetchMyTeam();
            const compId = AF_CONF.competitionId || 58;
            const res = await window.authFetch('/api/af/leagues/' + compId + '/table?seasonYear=' + AF_CONF.seasonYear);
            const data = await res.json();
            _afApiCache.table = data;
            return data;
        } finally {
            _afApiCache.tablePromise = null;
        }
    })();
    return _afApiCache.tablePromise;
}

async function afFetchLeagueLeaders() {
    if (_afApiCache.leagueLeaders) return _afApiCache.leagueLeaders;
    if (_afApiCache.leagueLeadersPromise) return _afApiCache.leagueLeadersPromise;
    _afApiCache.leagueLeadersPromise = (async () => {
        try {
            await afFetchMyTeam();
            const compId = AF_CONF.competitionId || 58;
            const res = await window.authFetch('/api/af/stats/league/' + compId + '?limit=10');
            const data = await res.json();
            _afApiCache.leagueLeaders = data;
            return data;
        } finally {
            _afApiCache.leagueLeadersPromise = null;
        }
    })();
    return _afApiCache.leagueLeadersPromise;
}

async function afFetchTeamMatches(teamId) {
    if (_afApiCache.teamMatches[teamId]) return _afApiCache.teamMatches[teamId];
    if (_afApiCache.teamMatchesPromise[teamId]) return _afApiCache.teamMatchesPromise[teamId];
    _afApiCache.teamMatchesPromise[teamId] = (async () => {
        try {
            const res = await window.authFetch('/api/af/matches/team/' + teamId + '?seasonYear=' + AF_CONF.seasonYear);
            const data = await res.json();
            _afApiCache.teamMatches[teamId] = data;
            return data;
        } finally {
            _afApiCache.teamMatchesPromise[teamId] = null;
        }
    })();
    return _afApiCache.teamMatchesPromise[teamId];
}

async function afFetchTeamFixtures(teamId) {
    try {
        const res = await window.authFetch('/api/af/fixtures/team/' + teamId);
        return await res.json();
    } catch { return []; }
}

async function afPlayFixture(fixtureId) {
    try {
        const res = await window.authFetch('/api/af/fixtures/' + fixtureId + '/play', { method: 'POST' });
        if (!res.ok) {
            const text = await res.text();
            console.error('afPlayFixture error', fixtureId, res.status, text);
            return null;
        }
        return await res.json();
    } catch (e) {
        console.error('afPlayFixture exception', fixtureId, e);
        return null;
    }
}

async function afFetchMatchDetail(matchId) {
    try {
        const res = await window.authFetch('/api/af/matches/' + matchId);
        if (!res.ok) return null;
        return await res.json();
    } catch (e) {
        console.error('afFetchMatchDetail exception', matchId, e);
        return null;
    }
}

async function afFetchRecentMatches(teamId, limit = 3) {
    try {
        const res = await window.authFetch('/api/af/matches/team/' + teamId + '/recent?limit=' + limit + '&seasonYear=' + AF_CONF.seasonYear);
        if (!res.ok) return [];
        return await res.json();
    } catch (e) {
        console.error('afFetchRecentMatches exception', teamId, e);
        return [];
    }
}

function afCalculateOverall(skills, position) {
    const weights = {
        QB: { stamina: 0.1, strength: 0.05, pace: 0.1, playmaking: 0.25, passing: 0.30, running: 0.05, tackling: 0.05, shooting: 0.1 },
        RB: { stamina: 0.15, strength: 0.15, pace: 0.2, playmaking: 0.05, passing: 0.05, running: 0.3, tackling: 0.05, shooting: 0.05 },
        WR: { stamina: 0.1, strength: 0.1, pace: 0.25, playmaking: 0.15, passing: 0.05, running: 0.25, tackling: 0.05, shooting: 0.05 },
        TE: { stamina: 0.15, strength: 0.2, pace: 0.15, playmaking: 0.1, passing: 0.05, running: 0.1, tackling: 0.15, shooting: 0.1 },
        OL: { stamina: 0.15, strength: 0.3, pace: 0.05, playmaking: 0.15, passing: 0.05, running: 0.05, tackling: 0.2, shooting: 0.05 },
        DE: { stamina: 0.15, strength: 0.25, pace: 0.2, playmaking: 0.05, passing: 0.05, running: 0.05, tackling: 0.25, shooting: 0 },
        DT: { stamina: 0.1, strength: 0.35, pace: 0.05, playmaking: 0.05, passing: 0.05, running: 0.05, tackling: 0.3, shooting: 0.05 },
        LB: { stamina: 0.15, strength: 0.2, pace: 0.15, playmaking: 0.1, passing: 0.05, running: 0.1, tackling: 0.25, shooting: 0 },
        CB: { stamina: 0.1, strength: 0.1, pace: 0.3, playmaking: 0.1, passing: 0.05, running: 0.15, tackling: 0.2, shooting: 0 },
        S:  { stamina: 0.15, strength: 0.1, pace: 0.2, playmaking: 0.2, passing: 0.05, running: 0.1, tackling: 0.2, shooting: 0 },
        K:  { stamina: 0.05, strength: 0.1, pace: 0.05, playmaking: 0.05, passing: 0.05, running: 0.05, tackling: 0.05, shooting: 0.6 },
        P:  { stamina: 0.05, strength: 0.1, pace: 0.05, playmaking: 0.05, passing: 0.2, running: 0.05, tackling: 0.05, shooting: 0.45 },
    };
    const w = weights[position] || weights.QB;
    let ovr = 0;
    for (const key in w) {
        ovr += (skills[key] || 5) * w[key];
    }
    return Math.round(ovr);
}

function afPositions() { return ['QB', 'RB', 'WR', 'TE', 'OL', 'DE', 'DT', 'LB', 'CB', 'S', 'K', 'P']; }

window.AF_CONF = AF_CONF;
window.afFetchMyTeam = afFetchMyTeam;
window.afFetchTeams = afFetchTeams;
window.afFetchTeamPlayers = afFetchTeamPlayers;
window.afFetchLeagueTable = afFetchLeagueTable;
window.afFetchLeagueLeaders = afFetchLeagueLeaders;
window.afFetchTeamMatches = afFetchTeamMatches;
window.afFetchTeamFixtures = afFetchTeamFixtures;
window.afPlayFixture = afPlayFixture;
window.afFetchMatchDetail = afFetchMatchDetail;
window.afFetchRecentMatches = afFetchRecentMatches;
window.afCalculateOverall = afCalculateOverall;
window.afPositions = afPositions;
window.afInvalidateCache = afInvalidateCache;
