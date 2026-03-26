const state = {
    feed: null,
    minute: 0,
    lastRenderedMinute: -1,
    startTs: 0,
    frameId: null,
    matchStates: new Map(),
    feedItems: [],
    lastClockTick: 0,
    visibleLeagueKeys: new Set(),
    filtersCollapsed: false
};

window.addEventListener('load', async () => {
    try {
        const response = await teletextFetch('/simulation/current-round/feed');
        const data = await response.json().catch(() => null);
        if (!response.ok || !data || data.status !== 'ok') {
            throw new Error(data?.message || data?.error || 'Unable to load current-round feed.');
        }
        state.feed = data;
        bootstrapMatchStates(data);
        bootstrapLeagueFilters(data);
        renderBoard();
        updateClock();
        animate();
    } catch (error) {
        const board = document.getElementById('ttBoard');
        const note = document.querySelector('.tt-note');
        if (board) {
            board.innerHTML = `<div class="tt-league"><div class="tt-league-head">Feed Error</div><div class="tt-match"><div class="tt-team">${escapeHtml(error.message || 'Could not load feed.')}</div></div></div>`;
        }
        if (note) {
            note.textContent = error.message || 'Could not load the live results desk.';
        }
    }
});

async function teletextFetch(url, options = {}) {
    const token = localStorage.getItem('token');
    if (!token) {
        throw new Error('Nema aktivne sesije. Uloguj se ponovo pa otvori Results Desk.');
    }

    const headers = {
        ...options.headers,
        Authorization: `Bearer ${token}`,
        'X-Requested-With': 'XMLHttpRequest'
    };

    const response = await fetch(url, { ...options, headers });
    if (!response.ok) {
        let message = `HTTP ${response.status}`;
        try {
            const text = await response.text();
            if (response.status === 401 || response.status === 403) {
                message = 'Sesija je istekla ili pristup nije dozvoljen. Vrati se na dashboard ili se uloguj ponovo.';
            } else if (text && !text.includes('<!DOCTYPE') && !text.includes('<html')) {
                try {
                    const json = JSON.parse(text);
                    message = json.message || json.error || message;
                } catch {
                    message = `${message}: ${text.substring(0, 180)}`;
                }
            } else {
                message = 'Server je vratio neispravan odgovor za live results desk.';
            }
        } catch {
            // keep fallback message
        }
        throw new Error(message);
    }
    return response;
}

function bootstrapMatchStates(data) {
    document.getElementById('ttWeekLabel').textContent = `${escapeHtml(data.userLeague || 'League')} first • Week ${Number(data.currentWeek || 0)}`;
    const orderedLeagues = getOrderedLeagues(data.leagues || []);
    orderedLeagues.forEach((league, leagueIndex) => {
        (league.matches || []).forEach((match, matchIndex) => {
            const key = matchKey(match);
            const incidents = Array.isArray(match.events) ? match.events : [];
            const lastMinute = incidents.reduce((max, item) => Math.max(max, Number(item.minute || 0)), 0);
            const finishMinute = 90 + ((leagueIndex + matchIndex + Number(match.fixtureId || 0)) % 6);
            state.matchStates.set(key, {
                homeGoals: 0,
                awayGoals: 0,
                eventIndex: 0,
                flashUntil: 0,
                finished: false,
                finishMinute: Math.max(finishMinute, lastMinute + 1),
                lastScoringSide: null,
                lateOffsetMs: ((leagueIndex + 1) * 430) + ((matchIndex + 1) * 260) + ((Number(match.fixtureId || 0) % 5) * 190)
            });
        });
    });
}

function bootstrapLeagueFilters(data) {
    const leagues = getOrderedLeagues(data.leagues || []);
    const userLeague = leagues.find(league => league.userLeague);
    state.visibleLeagueKeys = new Set(userLeague ? [leagueKey(userLeague)] : leagues.slice(0, 1).map(league => leagueKey(league)));
    state.filtersCollapsed = window.matchMedia('(max-width: 920px)').matches;
    ensureVisibleLeagueSelection(leagues);
    bindLeagueFilterActions(leagues);
    renderLeagueFilters(leagues);
}

function bindLeagueFilterActions(leagues) {
    document.getElementById('ttFilterToggleBtn')?.addEventListener('click', () => {
        state.filtersCollapsed = !state.filtersCollapsed;
        syncFilterPanelState();
    });

    document.getElementById('ttSelectAllBtn')?.addEventListener('click', () => {
        state.visibleLeagueKeys = new Set(leagues.map(league => leagueKey(league)));
        renderLeagueFilters(leagues);
        renderBoard();
        renderFeed();
    });

    document.getElementById('ttDeselectAllBtn')?.addEventListener('click', () => {
        const userLeague = leagues.find(league => league.userLeague);
        state.visibleLeagueKeys = new Set(userLeague ? [leagueKey(userLeague)] : leagues.slice(0, 1).map(league => leagueKey(league)));
        ensureVisibleLeagueSelection(leagues);
        renderLeagueFilters(leagues);
        renderBoard();
        renderFeed();
    });
}

function renderLeagueFilters(leagues) {
    const host = document.getElementById('ttFilterList');
    if (!host) return;
    ensureVisibleLeagueSelection(leagues);
    syncFilterPanelState();
    const selectedCount = leagues.filter(league => state.visibleLeagueKeys.has(leagueKey(league))).length;
    const summary = document.getElementById('ttFilterSummary');
    if (summary) {
        summary.textContent = `${selectedCount}/${leagues.length || 0} leagues shown`;
    }
    host.innerHTML = leagues.map(league => {
        const checked = state.visibleLeagueKeys.has(leagueKey(league)) ? 'checked' : '';
        const badge = league.userLeague ? 'YOUR' : `${(league.matches || []).length}M`;
        return `
            <label class="tt-filter-item">
                <input type="checkbox" data-league-key="${escapeHtml(leagueKey(league))}" ${checked}>
                <span class="tt-filter-label">${escapeHtml(league.leagueName)}</span>
                <span class="tt-filter-badge">${escapeHtml(badge)}</span>
            </label>
        `;
    }).join('');

    host.querySelectorAll('input[type="checkbox"][data-league-key]').forEach(input => {
        input.addEventListener('change', (event) => {
            const selectedLeagueKey = event.target.dataset.leagueKey;
            if (!selectedLeagueKey) return;
            if (event.target.checked) {
                state.visibleLeagueKeys.add(selectedLeagueKey);
            } else if (state.visibleLeagueKeys.size > 1) {
                state.visibleLeagueKeys.delete(selectedLeagueKey);
            } else {
                event.target.checked = true;
            }
            ensureVisibleLeagueSelection(leagues);
            renderBoard();
            renderFeed();
            updateFilterSummary(leagues);
        });
    });

    updateFilterSummary(leagues);
}

function animate(now = 0) {
    if (!state.startTs) {
        state.startTs = now;
    }

    const elapsed = Math.max(0, now - state.startTs);
    state.minute = deriveMinute(elapsed);
    document.getElementById('ttMinuteLabel').textContent = `${String(state.minute).padStart(2, '0')}'`;
    if (now - state.lastClockTick > 900) {
        updateClock();
        state.lastClockTick = now;
    }

    let changed = state.minute !== state.lastRenderedMinute;
    (state.feed?.leagues || []).forEach(league => {
        (league.matches || []).forEach(match => {
            const tracker = state.matchStates.get(matchKey(match));
            if (!tracker) return;
            const matchMinute = deriveMatchMinute(tracker);

            const incidents = Array.isArray(match.events) ? match.events : [];
            while (tracker.eventIndex < incidents.length && Number(incidents[tracker.eventIndex].minute || 0) <= matchMinute) {
                const incident = incidents[tracker.eventIndex];
                tracker.homeGoals = Number(incident.homeGoals || tracker.homeGoals || 0);
                tracker.awayGoals = Number(incident.awayGoals || tracker.awayGoals || 0);
                tracker.flashUntil = performance.now() + 2600;
                tracker.lastScoringSide = resolveScoringSide(match, incident);
                pushFeedItem(league, match, incident);
                tracker.eventIndex += 1;
                changed = true;
            }

            if (!tracker.finished && matchMinute >= tracker.finishMinute) {
                tracker.finished = true;
                tracker.homeGoals = Number(match.homeGoals || tracker.homeGoals || 0);
                tracker.awayGoals = Number(match.awayGoals || tracker.awayGoals || 0);
                changed = true;
            }
        });
    });

    if (changed) {
        state.lastRenderedMinute = state.minute;
        renderBoard();
        renderFeed();
    } else {
        refreshFlashState();
    }

    if (state.minute < 96) {
        state.frameId = requestAnimationFrame(animate);
    } else {
        renderBoard();
    }
}

function renderBoard() {
    const board = document.getElementById('ttBoard');
    if (!board || !state.feed) return;
    const orderedLeagues = getOrderedLeagues(state.feed.leagues || []);
    ensureVisibleLeagueSelection(orderedLeagues);
    const visibleLeagues = orderedLeagues
        .filter(league => state.visibleLeagueKeys.has(leagueKey(league)));
    if (!visibleLeagues.length) {
        const hasAnyLeagues = orderedLeagues.length > 0;
        board.innerHTML = hasAnyLeagues
            ? '<section class="tt-league"><div class="tt-league-head">League selection recovered</div><div class="tt-match"><div class="tt-team">Teletext restored the first available league automatically.</div></div></section>'
            : '<section class="tt-league"><div class="tt-league-head">No fixtures loaded</div><div class="tt-match"><div class="tt-team">Current-week feed has no league fixtures to display.</div></div></section>';
        return;
    }
    board.innerHTML = visibleLeagues.map(league => `
        <section class="tt-league">
            <div class="tt-league-head">${escapeHtml(league.leagueName)}${league.userLeague ? ' // YOUR LEAGUE' : ''}</div>
            ${(league.matches || []).map(match => renderMatchRow(match)).join('')}
        </section>
    `).join('');
}

function renderMatchRow(match) {
    const tracker = state.matchStates.get(matchKey(match));
    const matchMinute = deriveMatchMinute(tracker);
    const status = tracker?.finished ? 'FT' : `${String(Math.min(matchMinute, tracker?.finishMinute || matchMinute)).padStart(2, '0')}'`;
    const rowClass = [
        'tt-match',
        match.isUserMatch ? 'user' : '',
        tracker && !tracker.finished && tracker.flashUntil > performance.now() ? 'flash' : '',
        tracker?.finished ? 'finished' : ''
    ].filter(Boolean).join(' ');

    return `
        <div class="${rowClass}" data-fixture-id="${escapeHtml(match.fixtureId)}">
            <div class="tt-team flag">${match.isUserMatch ? 'YOUR MATCH' : '&nbsp;'}</div>
            <div class="tt-team ${tracker?.lastScoringSide === 'home' ? 'scored' : ''}">${escapeHtml(match.homeTeam)}</div>
            <div class="tt-score">${tracker ? `${tracker.homeGoals}-${tracker.awayGoals}` : `${match.homeGoals}-${match.awayGoals}`}</div>
            <div class="tt-team away ${tracker?.lastScoringSide === 'away' ? 'scored' : ''}">${escapeHtml(match.awayTeam)}</div>
            <div class="tt-status ${tracker?.finished ? 'finished' : ''}">${status}</div>
        </div>
    `;
}

function renderFeed() {
    const host = document.getElementById('ttFeed');
    if (!host) return;
    const filteredItems = state.feedItems.filter(item => state.visibleLeagueKeys.has(item.leagueKey));
    if (!filteredItems.length) {
        host.innerHTML = state.feedItems.length
            ? '<div class="tt-feed-item">No service messages for the selected leagues yet.</div>'
            : '<div class="tt-feed-item">Watching the service desk for the first change...</div>';
        return;
    }
    host.innerHTML = filteredItems.slice(0, 18).map(item => `
        <div class="tt-feed-item">
            <span class="tt-feed-time">${escapeHtml(item.minute)}'</span>
            <span class="tt-feed-code">${escapeHtml(item.code)}</span>
            <span class="tt-feed-icon" aria-hidden="true">${escapeHtml(item.icon || '•')}</span>
            ${escapeHtml(item.line)}
        </div>
    `).join('');
}

function refreshFlashState() {
    document.querySelectorAll('.tt-match.flash').forEach(node => {
        const fixtureId = node.dataset.fixtureId;
        if (!fixtureId) return;
        const tracker = [...state.matchStates.entries()].find(([key]) => key.endsWith(`:${fixtureId}`))?.[1];
        if (!tracker || tracker.flashUntil <= performance.now()) {
            node.classList.remove('flash');
        }
    });
}

function pushFeedItem(league, match, incident) {
    const score = `${incident.homeGoals || 0}-${incident.awayGoals || 0}`;
    const player = incident.playerName ? ` ${incident.playerName}` : '';
    state.feedItems.unshift({
        minute: Number(incident.minute || 0),
        code: incident.code || 'INFO',
        icon: feedIconForCode(incident.code),
        leagueKey: leagueKey(league),
        line: `${match.homeTeam} ${score} ${match.awayTeam}${player ? ` // ${player}` : ''}`
    });
}

function feedIconForCode(code) {
    switch (String(code || '').toUpperCase()) {
        case 'GOAL':
            return '⚽';
        case 'PEN':
            return '◎';
        case 'RC':
            return '🟥';
        case 'YC':
            return '🟨';
        case 'VAR':
            return '📺';
        case 'SUB':
            return '⇄';
        case 'INJ':
            return '✚';
        default:
            return '•';
    }
}

function syncFilterPanelState() {
    const panel = document.getElementById('ttFiltersPanel');
    const toggle = document.getElementById('ttFilterToggleBtn');
    if (!panel || !toggle) return;
    panel.classList.toggle('collapsed', state.filtersCollapsed);
    toggle.setAttribute('aria-expanded', String(!state.filtersCollapsed));
    toggle.textContent = state.filtersCollapsed ? 'Show filters' : 'Hide filters';
}

function updateFilterSummary(leagues) {
    const summary = document.getElementById('ttFilterSummary');
    if (!summary) return;
    const selectedCount = (leagues || []).filter(league => state.visibleLeagueKeys.has(leagueKey(league))).length;
    summary.textContent = `${selectedCount}/${(leagues || []).length} leagues shown`;
}

function deriveMinute(elapsedMs) {
    const normalPhase = 70 * 1280;
    const tensionPhase = 10 * 1820;
    if (elapsedMs <= normalPhase) {
        return Math.min(70, Math.floor(elapsedMs / 1280));
    }
    if (elapsedMs <= normalPhase + tensionPhase) {
        return Math.min(80, 70 + Math.floor((elapsedMs - normalPhase) / 1820));
    }
    const latePhase = elapsedMs - normalPhase - tensionPhase;
    return Math.min(96, 80 + Math.floor(latePhase / 3040));
}

function deriveMatchMinute(tracker) {
    if (!tracker) {
        return state.minute;
    }
    if (state.minute < 88) {
        return state.minute;
    }
    const baseTo88 = (70 * 1280) + (10 * 1820) + (8 * 3040);
    const elapsedSince88 = Math.max(0, (performance.now() - state.startTs) - baseTo88);
    const adjustedElapsed = Math.max(0, elapsedSince88 - (tracker.lateOffsetMs || 0));
    return Math.min(96, 88 + Math.floor(adjustedElapsed / 3040));
}

function updateClock() {
    const now = new Date();
    document.getElementById('ttClock').textContent = now.toLocaleTimeString('en-GB', {
        hour: '2-digit',
        minute: '2-digit'
    });
}

function matchKey(match) {
    return `${match.matchId || 'fixture'}:${match.fixtureId}`;
}

function resolveScoringSide(match, incident) {
    const code = String(incident.code || '').toUpperCase();
    if (!['GOAL', 'PEN'].includes(code)) {
        return null;
    }
    const teamName = String(incident.teamName || '');
    if (teamName && teamName === String(match.homeTeam || '')) return 'home';
    if (teamName && teamName === String(match.awayTeam || '')) return 'away';
    const homeGoals = Number(incident.homeGoals || 0);
    const awayGoals = Number(incident.awayGoals || 0);
    const tracker = state.matchStates.get(matchKey(match));
    if (!tracker) return null;
    if (homeGoals > tracker.homeGoals) return 'home';
    if (awayGoals > tracker.awayGoals) return 'away';
    return null;
}

function compareLeagues(left, right) {
    if (Boolean(left.userLeague) !== Boolean(right.userLeague)) {
        return left.userLeague ? -1 : 1;
    }
    const rankDiff = leagueRank(left.leagueName) - leagueRank(right.leagueName);
    if (rankDiff !== 0) return rankDiff;
    return naturalLeagueCompare(left.leagueName, right.leagueName);
}

function getOrderedLeagues(leagues) {
    return [...leagues].sort(compareLeagues);
}

function ensureVisibleLeagueSelection(leagues) {
    const availableKeys = new Set((leagues || []).map(league => leagueKey(league)).filter(Boolean));
    state.visibleLeagueKeys = new Set([...state.visibleLeagueKeys].filter(key => availableKeys.has(key)));
    if (state.visibleLeagueKeys.size > 0) {
        return;
    }
    const userLeague = (leagues || []).find(league => league.userLeague);
    if (userLeague) {
        state.visibleLeagueKeys.add(leagueKey(userLeague));
        return;
    }
    const firstLeague = (leagues || []).find(Boolean);
    if (firstLeague) {
        state.visibleLeagueKeys.add(leagueKey(firstLeague));
    }
}

function leagueKey(league) {
    if (!league) {
        return '';
    }
    const idPart = league.leagueId != null ? String(league.leagueId).trim() : '';
    const namePart = String(league.leagueName || '').trim().toLowerCase();
    return `${idPart}::${namePart}`;
}

function leagueRank(name) {
    const value = String(name || '').toLowerCase();
    if (value.includes('superliga')) return 1;
    if (value.includes('prva liga')) return 2;
    if (value.includes('srpska liga')) return 3;
    if (value.includes('okružna liga') || value.includes('okruzna liga')) return 4;
    if (value.includes('opštinska liga') || value.includes('opstinska liga')) return 5;
    return 9;
}

function naturalLeagueCompare(left, right) {
    return String(left || '').localeCompare(String(right || ''), undefined, { numeric: true, sensitivity: 'base' });
}

function escapeHtml(value) {
    return String(value ?? '')
        .replaceAll('&', '&amp;')
        .replaceAll('<', '&lt;')
        .replaceAll('>', '&gt;')
        .replaceAll('"', '&quot;')
        .replaceAll("'", '&#39;');
}
