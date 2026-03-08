import { authFetch } from './auth.js';

let matchId = null;
let displayMinute = 0;
let homeScore = 0;
let awayScore = 0;
let bannerTimeout = null;
let animationFrame = null;
let activeAnimation = null;
let goalGifTimeout = null;
let playbackFrame = null;
let highlightTimeout = null;
let replayMetadata = null;
let totalDurationMs = 0;
let chunkDurationMs = 30_000;
let totalChunks = 0;
let currentTime = 0;
let isPlaying = true;
let playbackRate = 1;
let lastFrameTs = 0;
let lastBallIdx = 0;
let lastEventIdx = 0;
let isScrubbing = false;
let resumeAfterScrub = false;
let controlsBound = false;
let replayMode = 'replay';

const EVENT_DELAY = 1650;
const GOAL_DELAY = 2600;
const MAX_FEED_ITEMS = 70;
const MAX_KEY_MOMENTS = 8;
const TARGET_FPS = 30;
const FRAME_INTERVAL = 1000 / TARGET_FPS;
const METADATA_POLL_MS = 2000;
const MAX_METADATA_POLL_ATTEMPTS = 120;
const CHUNK_PRELOAD_AHEAD = 1;
const playerElements = new Map();
const playerSlots = new Map();
const playerNames = new Map();
const latestPositions = new Map();
const pendingVarGoals = [];
const loadedChunks = new Set();
const loadingChunks = new Set();
const loadedPlayerPositions = {};
const lastPlayerIdx = {};
const keyMoments = [];
const matchEndedImg = new Image();
matchEndedImg.src = '/images/match_ended.jpg';

let replayEvents = [];
let ballData = [];
let currentInvolvedPlayerIds = new Set();

const matchData = {
    homeTeam: 'Home',
    awayTeam: 'Away'
};

const teamStats = {
    HOME: createTeamStats(),
    AWAY: createTeamStats()
};

window.addEventListener('load', async () => {
    const params = new URLSearchParams(window.location.search);
    matchId = params.get('matchId');
    replayMode = (params.get('mode') || 'replay').toLowerCase() === 'live' ? 'live' : 'replay';

    if (!matchId) {
        document.getElementById('events-list').innerHTML = '<p style="color:#f44336;">Missing matchId.</p>';
        return;
    }

    initPitchOverlay();
    configureReplayModeUi();
    bindPlaybackControls();
    resetReplayUi();

    try {
        await initializeReplay();
    } catch (error) {
        console.error('Failed to initialize replay:', error);
        setReplayStatus('Replay unavailable');
        document.getElementById('events-list').innerHTML = `<p style="color:#f44336;">${escapeHtml(error.message || 'Failed to load replay data.')}</p>`;
    }
});

async function initializeReplay() {
    setReplayStatus('Preparing match replay...');
    const metadata = await waitForReplayMetadata();
    hydrateReplayMetadata(metadata);
    renderSquads(resolveMetadataPlayers(metadata));
    renderGoalMarkers(isReplayMode() ? resolveMetadataGoals(metadata) : []);
    updateTimelineBounds();

    await ensureChunkLoaded(0);
    void ensureChunkLoaded(1);

    await rebuildReplayFromTime(0, { animate: false });
    setReplayStatus(isReplayMode() ? 'Replay ready • 10s = 1 match minute' : 'Live match view • timeline hidden');
    setPlaybackToggleLabel();
    startPlaybackLoop();
}

async function waitForReplayMetadata() {
    for (let attempt = 1; attempt <= MAX_METADATA_POLL_ATTEMPTS; attempt += 1) {
        const response = await authFetch(`/api/zox/replay/${matchId}/metadata`);
        if (response.ok) {
            const metadata = await response.json();
            const duration = Number(metadata.total_duration_ms ?? metadata.totalDurationMs ?? metadata.match_time_ms ?? 0);
            if (duration > 0) {
                return metadata;
            }
        }

        setReplayStatus(`Simulation in progress... waiting for recorded playback (${attempt}/${MAX_METADATA_POLL_ATTEMPTS})`);
        await delay(METADATA_POLL_MS);
    }

    throw new Error('Replay data is still not ready.');
}

function hydrateReplayMetadata(metadata) {
    replayMetadata = metadata;
    totalDurationMs = Number(metadata.total_duration_ms ?? metadata.totalDurationMs ?? metadata.match_time_ms ?? 0);
    chunkDurationMs = Number(metadata.chunk_duration_ms ?? metadata.chunkDurationMs ?? 30_000);
    totalChunks = Number(metadata.chunk_count ?? metadata.chunkCount ?? 0);
    replayEvents = mergeSortedSeries([], resolveMetadataEvents(metadata));

    matchData.homeTeam = metadata.homeTeamName || metadata.home_team_name || matchData.homeTeam;
    matchData.awayTeam = metadata.awayTeamName || metadata.away_team_name || matchData.awayTeam;

    homeScore = 0;
    awayScore = 0;
    resetDerivedState();

    document.getElementById('homeTeam').textContent = matchData.homeTeam;
    document.getElementById('awayTeam').textContent = matchData.awayTeam;
    document.getElementById('home-squad-title').textContent = matchData.homeTeam;
    document.getElementById('away-squad-title').textContent = matchData.awayTeam;
    document.getElementById('home-formation').textContent = metadata.homeFormation || metadata.home_formation || 'Formation';
    document.getElementById('away-formation').textContent = metadata.awayFormation || metadata.away_formation || 'Formation';

    playerSlots.clear();
    playerNames.clear();
    latestPositions.clear();

    for (const player of resolveMetadataPlayers(metadata)) {
        registerPlayerSlot(player);
    }

    updateDisplayedMinute(0, true);
    updateScore();
    renderStatBoard();
    renderKeyMoments();
}

function registerPlayerSlot(player) {
    const playerId = Number(player.playerId ?? player.id);
    if (!Number.isFinite(playerId)) return;

    const normalized = {
        id: playerId,
        name: player.name || player.fullName || player.last_name || `Player ${playerId}`,
        position: player.position || 'N/A',
        squadNumber: player.squadNumber ?? player.shirt_number ?? null,
        teamSide: player.teamSide || (player.is_home ? 'HOME' : 'AWAY'),
        starter: Boolean(player.starter ?? player.is_starter)
    };

    playerSlots.set(playerId, normalized);
    if (normalized.name) {
        playerNames.set(normalize(normalized.name), playerId);
    }
}

function resolveMetadataPlayers(metadata) {
    return metadata.players || metadata.playersData || [];
}

function resolveMetadataGoals(metadata) {
    return metadata.goals || metadata.goalsData || [];
}

function resolveMetadataEvents(metadata) {
    return metadata.events || metadata.eventData || [];
}

function isReplayMode() {
    return replayMode === 'replay';
}

function configureReplayModeUi() {
    const timelineCard = document.getElementById('timeline-card');
    const kicker = document.getElementById('playback-kicker');

    if (timelineCard) {
        timelineCard.hidden = !isReplayMode();
    }

    if (kicker) {
        kicker.textContent = isReplayMode() ? 'Open-football style replay' : 'Live match view';
    }
}

function bindPlaybackControls() {
    if (controlsBound) return;
    controlsBound = true;

    document.getElementById('playback-toggle')?.addEventListener('click', () => {
        isPlaying = !isPlaying;
        if (isPlaying) {
            lastFrameTs = 0;
            setReplayStatus('Replay playing');
        } else {
            setReplayStatus('Replay paused');
        }
        setPlaybackToggleLabel();
    });

    document.getElementById('playback-restart')?.addEventListener('click', async () => {
        isPlaying = true;
        await seekTo(0, { animate: false });
        setReplayStatus('Replay restarted');
        setPlaybackToggleLabel();
    });

    document.getElementById('playback-speed')?.addEventListener('change', event => {
        playbackRate = Number(event.target.value) || 1;
        setReplayStatus(`Replay speed ${playbackRate.toFixed(playbackRate % 1 === 0 ? 0 : 2)}x`);
    });

    const range = document.getElementById('timeline-range');
    if (range) {
        range.addEventListener('pointerdown', () => {
            isScrubbing = true;
            resumeAfterScrub = isPlaying;
            isPlaying = false;
            setPlaybackToggleLabel();
        });

        range.addEventListener('input', event => {
            void seekTo(Number(event.target.value) || 0, { animate: false });
        });

        range.addEventListener('change', async event => {
            await seekTo(Number(event.target.value) || 0, { animate: false });
            isScrubbing = false;
            isPlaying = resumeAfterScrub;
            setPlaybackToggleLabel();
        });
    }

    window.addEventListener('pointerup', () => {
        if (!isScrubbing) return;
        isScrubbing = false;
        isPlaying = resumeAfterScrub;
        setPlaybackToggleLabel();
    });
}

async function seekTo(timeMs, options = {}) {
    currentTime = clamp(Number(timeMs) || 0, 0, totalDurationMs || 0);
    await ensureChunkLoaded(getChunkNumber(currentTime));
    void ensureChunkLoaded(getChunkNumber(currentTime) + 1);
    await rebuildReplayFromTime(currentTime, options);
    updateTimelineUi();
}

async function rebuildReplayFromTime(timeMs, options = {}) {
    resetReplayUi();
    resetTemporalCaches();
    currentTime = clamp(timeMs, 0, totalDurationMs || 0);

    for (const event of replayEvents) {
        if (getTimestamp(event) > currentTime) break;
        applyReplayEvent(event, { animate: false });
        lastEventIdx += 1;
    }

    updateReplayFrame(currentTime, options);
    updateTimelineUi();
}

function startPlaybackLoop() {
    if (playbackFrame) cancelAnimationFrame(playbackFrame);

    const loop = now => {
        if (isPlaying && totalDurationMs > 0) {
            if (!lastFrameTs) lastFrameTs = now;
            const elapsed = now - lastFrameTs;
            if (elapsed >= FRAME_INTERVAL) {
                lastFrameTs = now;
                currentTime = clamp(currentTime + elapsed * playbackRate, 0, totalDurationMs);
                ensureChunksNearTime(currentTime);
                updateReplayFrame(currentTime, { animate: true });
                advanceEventsToTime(currentTime);
                updateTimelineUi();

                if (currentTime >= totalDurationMs) {
                    isPlaying = false;
                    setReplayStatus('Replay finished');
                    setPlaybackToggleLabel();
                }
            }
        } else {
            lastFrameTs = 0;
        }

        playbackFrame = requestAnimationFrame(loop);
    };

    playbackFrame = requestAnimationFrame(loop);
}

function advanceEventsToTime(timeMs) {
    while (lastEventIdx < replayEvents.length && getTimestamp(replayEvents[lastEventIdx]) <= timeMs) {
        applyReplayEvent(replayEvents[lastEventIdx], { animate: true });
        lastEventIdx += 1;
    }
}

function applyReplayEvent(event, options = {}) {
    const animate = Boolean(options.animate);
    applyEventState(event);
    renderEvent(event);
    appendKeyMoment(event);

    clearPitchHighlights();
    currentInvolvedPlayerIds = resolveInvolvedPlayers(event);

    if (animate && currentInvolvedPlayerIds.size > 0) {
        if (highlightTimeout) clearTimeout(highlightTimeout);
        highlightTimeout = setTimeout(() => {
            currentInvolvedPlayerIds.clear();
            clearPitchHighlights();
        }, resolveEventDelay(event));
    }

    if (animate && isPitchKeyEvent(event)) {
        renderPitchEvent(event);
    } else if (!animate) {
        hidePitchBanner();
    }
}

function resolveInvolvedPlayers(event) {
    const involved = new Set();
    const mainSlot = getSlotByName(event.playerName || event.scorerName || event.takerName || event.goalkeeperName || event.playerOutName);
    const secondarySlot = getSlotByName(event.targetPlayerName || event.secondaryPlayerName || event.assistantName || event.playerInName);
    if (mainSlot?.id != null) involved.add(Number(mainSlot.id));
    if (secondarySlot?.id != null) involved.add(Number(secondarySlot.id));
    return involved;
}

function updateReplayFrame(timeMs) {
    const state = buildInterpolatedState(timeMs);
    renderPlayers(state);
    updateDisplayedMinute(deriveMinuteFromTime(timeMs), true);
}

function buildInterpolatedState(timeMs) {
    const players = [];

    for (const [playerId, slot] of playerSlots.entries()) {
        const series = loadedPlayerPositions[playerId];
        if (!Array.isArray(series) || series.length === 0) continue;

        const firstTs = getTimestamp(series[0]);
        const lastTs = getTimestamp(series[series.length - 1]);
        if (timeMs < firstTs - 1000 || timeMs > lastTs + 1000) continue;

        const idx = findIndexNear(series, timeMs, lastPlayerIdx[playerId] ?? 0);
        if (idx < 0) continue;
        lastPlayerIdx[playerId] = idx;

        const [x, y] = interpolateSeriesPoint(series, idx, timeMs);
        players.push({
            id: playerId,
            team: slot.teamSide,
            x,
            y
        });
    }

    const ballPoint = resolveBallPoint(timeMs);
    return {
        players,
        ball: ballPoint ? { x: ballPoint[0], y: ballPoint[1] } : null,
        carrierPlayerId: resolveCarrierPlayerId(timeMs),
        ballInTransit: resolveBallInTransit(timeMs)
    };
}

function resolveBallPoint(timeMs) {
    if (!Array.isArray(ballData) || ballData.length === 0) return null;
    lastBallIdx = findIndexNear(ballData, timeMs, lastBallIdx);
    if (lastBallIdx < 0) return null;
    return interpolateSeriesPoint(ballData, lastBallIdx, timeMs);
}

function resolveCarrierPlayerId(timeMs) {
    if (!Array.isArray(ballData) || ballData.length === 0) return null;
    const idx = Math.max(0, Math.min(lastBallIdx, ballData.length - 1));
    return ballData[idx]?.carrierPlayerId ?? null;
}

function resolveBallInTransit() {
    if (!Array.isArray(ballData) || ballData.length === 0) return false;
    const idx = Math.max(0, Math.min(lastBallIdx, ballData.length - 1));
    return Boolean(ballData[idx]?.ballInTransit);
}

function findIndexNear(arr, timeMs, hint = 0) {
    const len = Array.isArray(arr) ? arr.length : 0;
    if (!len) return -1;

    if (hint >= 0 && hint < len) {
        const hintedTs = getTimestamp(arr[hint]);
        if (hintedTs <= timeMs) {
            let i = hint;
            while (i + 1 < len && getTimestamp(arr[i + 1]) <= timeMs) i += 1;
            return i;
        }
        let i = Math.max(0, hint - 1);
        while (i > 0 && getTimestamp(arr[i]) > timeMs) i -= 1;
        return i;
    }

    let lo = 0;
    let hi = len - 1;
    while (lo < hi) {
        const mid = (lo + hi + 1) >> 1;
        if (getTimestamp(arr[mid]) <= timeMs) lo = mid;
        else hi = mid - 1;
    }
    return lo;
}

function interpolateSeriesPoint(arr, idx, timeMs) {
    const current = getPointTuple(arr[idx]);
    if (idx + 1 < arr.length) {
        const next = getPointTuple(arr[idx + 1]);
        const currentTs = getTimestamp(arr[idx]);
        const nextTs = getTimestamp(arr[idx + 1]);
        const delta = nextTs - currentTs;
        if (delta > 0) {
            const t = clamp((timeMs - currentTs) / delta, 0, 1);
            return [
                current[0] + (next[0] - current[0]) * t,
                current[1] + (next[1] - current[1]) * t,
                (current[2] || 0) + ((next[2] || 0) - (current[2] || 0)) * t
            ];
        }
    }
    return current;
}

function getPointTuple(point) {
    if (Array.isArray(point?.position)) {
        return point.position;
    }
    return [Number(point?.x ?? 50), Number(point?.y ?? 50), Number(point?.z ?? 0)];
}

function getTimestamp(point) {
    return Number(point?.timestamp ?? point?.timestampMs ?? 0);
}

function getChunkNumber(timeMs) {
    if (!chunkDurationMs || chunkDurationMs <= 0) return 0;
    return Math.floor(Math.max(0, timeMs) / chunkDurationMs);
}

function ensureChunksNearTime(timeMs) {
    const currentChunk = getChunkNumber(timeMs);
    void ensureChunkLoaded(currentChunk);
    for (let offset = 1; offset <= CHUNK_PRELOAD_AHEAD; offset += 1) {
        void ensureChunkLoaded(currentChunk + offset);
    }
}

async function ensureChunkLoaded(chunkIndex) {
    if (!Number.isFinite(chunkIndex) || chunkIndex < 0) return;
    if (totalChunks > 0 && chunkIndex >= totalChunks) return;
    if (loadedChunks.has(chunkIndex) || loadingChunks.has(chunkIndex)) return;

    loadingChunks.add(chunkIndex);
    try {
        const response = await authFetch(`/api/zox/replay/${matchId}/chunks/${chunkIndex}`);
        if (!response.ok) {
            throw new Error(`Failed to load replay chunk ${chunkIndex}`);
        }
        const chunk = await response.json();
        mergeChunkData(chunk);
        loadedChunks.add(chunkIndex);
    } finally {
        loadingChunks.delete(chunkIndex);
    }
}

function mergeChunkData(chunk) {
    const players = chunk.players || chunk.playerPositions || {};
    const ball = chunk.ball || chunk.ballData || [];
    const events = chunk.events || chunk.eventData || [];

    for (const [playerId, incomingSeries] of Object.entries(players)) {
        const numericId = Number(playerId);
        loadedPlayerPositions[numericId] = mergeSortedSeries(loadedPlayerPositions[numericId] || [], incomingSeries || []);
    }

    ballData = mergeSortedSeries(ballData, ball);
    replayEvents = mergeSortedSeries(replayEvents, events);
}

function mergeSortedSeries(existing, incoming) {
    const mergedByKey = new Map();
    for (const item of [...(existing || []), ...(incoming || [])]) {
        mergedByKey.set(buildSeriesIdentity(item), item);
    }
    return [...mergedByKey.values()].sort(compareSeriesItems);
}

function buildSeriesIdentity(item) {
    const eventId = Number(item?.eventId ?? item?.event_id);
    if (Number.isFinite(eventId) && eventId > 0) {
        return `event:${eventId}`;
    }

    const type = String(item?.type || '').toLowerCase();
    if (type || item?.description || item?.minute != null || item?.clockLabel || item?.clock_label) {
        return [
            'event',
            getTimestamp(item),
            type,
            item?.minute ?? '',
            item?.playerId ?? item?.player_id ?? '',
            item?.secondaryPlayerId ?? item?.secondary_player_id ?? '',
            item?.teamSide ?? item?.team_side ?? '',
            item?.decision ?? '',
            item?.reviewTarget ?? item?.review_target ?? '',
            item?.description ?? ''
        ].join('|');
    }

    return [
        'point',
        getTimestamp(item),
        Number(item?.x ?? 50),
        Number(item?.y ?? 50),
        Number(item?.z ?? 0),
        item?.carrierPlayerId ?? item?.carrier_player_id ?? '',
        item?.pendingReceiverId ?? item?.pending_receiver_id ?? '',
        Boolean(item?.ballInTransit ?? item?.ball_in_transit)
    ].join('|');
}

function compareSeriesItems(left, right) {
    const timestampDelta = getTimestamp(left) - getTimestamp(right);
    if (timestampDelta !== 0) return timestampDelta;

    const leftEventId = Number(left?.eventId ?? left?.event_id ?? 0);
    const rightEventId = Number(right?.eventId ?? right?.event_id ?? 0);
    if (leftEventId !== rightEventId) return leftEventId - rightEventId;

    const leftMinute = Number(left?.minute ?? -1);
    const rightMinute = Number(right?.minute ?? -1);
    if (leftMinute !== rightMinute) return leftMinute - rightMinute;

    return buildSeriesIdentity(left).localeCompare(buildSeriesIdentity(right));
}

function renderSquads(players) {
    renderSquadList('home-starters-list', players.filter(player => resolveTeamSideForPlayer(player) === 'HOME' && Boolean(player.starter ?? player.is_starter)));
    renderSquadList('home-bench-list', players.filter(player => resolveTeamSideForPlayer(player) === 'HOME' && !Boolean(player.starter ?? player.is_starter)));
    renderSquadList('away-starters-list', players.filter(player => resolveTeamSideForPlayer(player) === 'AWAY' && Boolean(player.starter ?? player.is_starter)));
    renderSquadList('away-bench-list', players.filter(player => resolveTeamSideForPlayer(player) === 'AWAY' && !Boolean(player.starter ?? player.is_starter)));
}

function renderSquadList(elementId, players) {
    const container = document.getElementById(elementId);
    if (!container) return;
    if (!players.length) {
        container.innerHTML = '<div class="placeholder">No player data.</div>';
        return;
    }

    container.innerHTML = players.map(player => {
        const name = player.name || player.fullName || player.last_name || 'Unknown';
        const squadNumber = player.squadNumber ?? player.shirt_number ?? '?';
        const position = player.position || 'N/A';
        return `
            <div class="squad-player-row">
                <span class="squad-num">${escapeHtml(String(squadNumber))}</span>
                <div>
                    <div class="squad-player-name">${escapeHtml(name)}</div>
                    <div class="squad-player-meta">${escapeHtml(position)}</div>
                </div>
            </div>
        `;
    }).join('');
}

function renderGoalMarkers(goals) {
    const container = document.getElementById('goal-markers');
    if (!container) return;
    container.innerHTML = '';

    for (const goal of goals) {
        const time = Number(goal.time ?? goal.timestampMs ?? goal.timestamp ?? 0);
        const pct = totalDurationMs > 0 ? clamp((time / totalDurationMs) * 100, 0, 100) : 0;
        const side = (goal.teamSide || goal.team_side || 'HOME').toString().toLowerCase();
        const marker = document.createElement('div');
        marker.className = `goal-marker ${side === 'away' ? 'away' : 'home'}`;
        marker.style.left = `${pct}%`;
        marker.title = `${goal.playerName || goal.player_name || 'Goal'} ${goal.minute != null ? `${goal.minute}'` : ''}`.trim();
        container.appendChild(marker);
    }
}

function updateTimelineBounds() {
    const range = document.getElementById('timeline-range');
    if (!range) return;
    range.max = String(totalDurationMs);
    range.value = String(currentTime);
}

function updateTimelineUi() {
    const progress = document.getElementById('timeline-progress');
    const range = document.getElementById('timeline-range');
    const label = document.getElementById('timeline-time');
    if (!progress || !range || !label) return;

    const pct = totalDurationMs > 0 ? clamp((currentTime / totalDurationMs) * 100, 0, 100) : 0;
    progress.style.width = `${pct}%`;
    range.value = String(currentTime);
    label.style.left = `${pct}%`;
    label.textContent = formatMatchClockFromMs(currentTime);
}

function setPlaybackToggleLabel() {
    const button = document.getElementById('playback-toggle');
    if (button) {
        button.textContent = isPlaying ? 'Pause' : 'Play';
    }
}

function setReplayStatus(text) {
    const status = document.getElementById('replay-status');
    if (status) {
        status.textContent = text;
    }
}

function resetReplayUi() {
    homeScore = 0;
    awayScore = 0;
    resetDerivedState();
    clearEventFeed();
    renderKeyMoments();
    updateScore();
    renderStatBoard();
    updateDisplayedMinute(deriveMinuteFromTime(currentTime), true);
    hidePitchBanner();
    clearPitchHighlights();
    clearCanvasAnimation();
    currentInvolvedPlayerIds.clear();
}

function resetTemporalCaches() {
    lastBallIdx = 0;
    lastEventIdx = 0;
    Object.keys(lastPlayerIdx).forEach(key => delete lastPlayerIdx[key]);
}

function clearEventFeed() {
    const eventsList = document.getElementById('events-list');
    if (!eventsList) return;
    eventsList.innerHTML = '<div class="placeholder">Match events will appear here.</div>';
}

function deriveMinuteFromTime(timeMs) {
    return Math.floor(toMatchSeconds(timeMs) / 60);
}

function toMatchSeconds(timeMs) {
    if (totalDurationMs > 0) {
        return Math.floor((clamp(timeMs, 0, totalDurationMs) / totalDurationMs) * 90 * 60);
    }
    return Math.floor((timeMs / 10_000) * 60);
}

function formatMatchClockFromMs(timeMs) {
    const totalSeconds = toMatchSeconds(timeMs);
    const minutes = Math.floor(totalSeconds / 60);
    const seconds = totalSeconds % 60;
    return `${minutes}:${String(seconds).padStart(2, '0')}`;
}

function delay(ms) {
    return new Promise(resolve => window.setTimeout(resolve, ms));
}

function resolveTeamSideForPlayer(player) {
    if (player.teamSide) return player.teamSide;
    if (player.team_side) return player.team_side;
    return player.is_home ? 'HOME' : 'AWAY';
}

function applyEventState(event) {
    if (event.homeTeamName) matchData.homeTeam = event.homeTeamName;
    if (event.awayTeamName) matchData.awayTeam = event.awayTeamName;
    document.getElementById('homeTeam').textContent = matchData.homeTeam;
    document.getElementById('awayTeam').textContent = matchData.awayTeam;

    if (Number.isFinite(event.minute)) {
        updateDisplayedMinute(event.minute);
    }

    const type = (event.type || '').toLowerCase();
    const parsedScore = parseScore(event.scoreAfterGoal);
    if (parsedScore) {
        homeScore = parsedScore.home;
        awayScore = parsedScore.away;
    } else if (Number.isFinite(event.homeGoals) && Number.isFinite(event.awayGoals)) {
        homeScore = event.homeGoals;
        awayScore = event.awayGoals;
    } else if (type === 'goal') {
        const eventTeam = normalize(event.teamName);
        if (eventTeam && eventTeam === normalize(matchData.homeTeam)) homeScore += 1;
        if (eventTeam && eventTeam === normalize(matchData.awayTeam)) awayScore += 1;
    }

    if (type === 'goal') {
        const scorer = event.scorerName || event.playerName || '';
        const assistant = event.assistantName || '';
        pendingVarGoals.push({ scorer, assistant, teamName: event.teamName || '' });
    }

    if (type === 'varreview'
        && String(event.decision || '').toLowerCase() === 'overturned'
        && String(event.reviewTarget || '').toLowerCase() === 'goal') {
        rollbackGoalFromVar(event);
    }

    updateTeamStats(event);
    updateScore();
    renderStatBoard();
}

function rollbackGoalFromVar(event) {
    const reviewedPlayer = event.playerName || '';
    const eventTeam = normalize(event.teamName);
    if (eventTeam && eventTeam === normalize(matchData.homeTeam)) {
        homeScore = Math.max(0, homeScore - 1);
    } else if (eventTeam && eventTeam === normalize(matchData.awayTeam)) {
        awayScore = Math.max(0, awayScore - 1);
    }

    for (let i = pendingVarGoals.length - 1; i >= 0; i -= 1) {
        const candidate = pendingVarGoals[i];
        if (!reviewedPlayer || normalize(candidate.scorer) === normalize(reviewedPlayer)) {
            pendingVarGoals.splice(i, 1);
            break;
        }
    }
}
function renderEvent(event) {
    const eventsList = document.getElementById('events-list');
    const loading = eventsList.querySelector('.loading');
    if (loading) loading.remove();

    const item = document.createElement('div');
    item.className = `event ${normalizeEventType(event.type)} ${resolveEventCategory(event)} ${resolveEventImportance(event)}`;
    item.innerHTML = buildEventHtml(event);
    eventsList.prepend(item);

    while (eventsList.children.length > MAX_FEED_ITEMS) {
        eventsList.removeChild(eventsList.lastChild);
    }
}

function buildEventHtml(event) {
    const headline = buildEventHeadline(event);
    const description = event.description && normalize(event.description) !== normalize(headline)
        ? escapeHtml(event.description)
        : '';
    const xgBadge = formatXgBadge(event);

    return `
        <div class="event-meta">
            <span class="event-clock">${escapeHtml(resolveEventClockLabel(event))}</span>
            <span class="event-label">${escapeHtml(resolveEventTypeLabel(event))}</span>
            ${xgBadge ? `<span class="event-badge">${escapeHtml(xgBadge)}</span>` : ''}
        </div>
        <div class="event-main">${escapeHtml(headline)}</div>
        ${description ? `<div class="event-sub">${description}</div>` : ''}
    `;
}

function renderPitchEvent(event) {
    const type = normalizeEventType(event.type);
    const mainName = event.playerName || event.scorerName || event.takerName || event.goalkeeperName;
    const secondaryName = event.targetPlayerName || event.secondaryPlayerName || event.assistantName;

    showPitchBanner(buildPitchBannerText(event), event);

    const mainEl = findPlayerElementByName(mainName);
    const secondaryEl = findPlayerElementByName(secondaryName);

    if (mainEl) mainEl.classList.add('involved-primary');
    if (secondaryEl) secondaryEl.classList.add('involved-secondary');

    if (type === 'duel' || type === 'interception') {
        if (mainEl) {
            mainEl.classList.add('duel-clash');
            setTimeout(() => mainEl.classList.remove('duel-clash'), 600);
        }
        if (secondaryEl) {
            secondaryEl.classList.add('duel-clash');
            setTimeout(() => secondaryEl.classList.remove('duel-clash'), 600);
        }
    }

    if (type === 'goal') {
        showGoalCelebration(event);
    }

    if (isCanvasAnimationEvent(type)) {
        startCanvasAnimation(event);
    }
}

function getSlotByName(name) {
    if (!name) return null;
    const playerId = playerNames.get(normalize(name));
    return playerId != null ? playerSlots.get(Number(playerId)) || null : null;
}

function buildPitchBannerText(event) {
    const clock = resolveEventClockLabel(event);
    const type = normalizeEventType(event.type);
    const player = event.playerName || event.scorerName || event.takerName || event.goalkeeperName || event.playerOutName || event.playerInName || '';

    switch (type) {
        case 'matchstarted':
            return `${clock} • Kick-off`;
        case 'matchended':
            return `${clock} • Full time • ${matchData.homeTeam} ${homeScore}-${awayScore} ${matchData.awayTeam}`;
        case 'goal':
            return `${clock} • Goal • ${player}`;
        case 'penalty':
            return `${clock} • Penalty • ${player}`;
        case 'shotontarget':
            return `${clock} • Shot on target • ${player}`;
        case 'shotofftarget':
            return `${clock} • Shot off target • ${player}`;
        case 'varreview':
            return `${clock} • VAR • ${String(event.decision || 'review').toUpperCase()}`;
        case 'substitution':
            return `${clock} • Substitution • ${event.teamName || 'Team change'}`;
        case 'chance':
            return `${clock} • Big chance • ${player || event.teamName || 'Attack'}`;
        case 'yellowcard':
            return `${clock} • Yellow card • ${player}`;
        case 'redcard':
            return `${clock} • Red card • ${player}`;
        case 'injury':
            return `${clock} • Injury • ${player}`;
        default:
            return `${clock} • ${resolveEventTypeLabel(event)}`;
    }
}

function showPitchBanner(text, event) {
    const banner = document.getElementById('pitch-event-banner');
    if (!banner) return;

    banner.textContent = text;
    banner.dataset.importance = resolveEventImportance(event);
    banner.dataset.category = resolveEventCategory(event);
    banner.classList.add('visible');
    if (bannerTimeout) clearTimeout(bannerTimeout);
    bannerTimeout = setTimeout(() => banner.classList.remove('visible'), resolveBannerDuration(event));
}

function hidePitchBanner() {
    const banner = document.getElementById('pitch-event-banner');
    if (!banner) return;
    banner.classList.remove('visible');
    if (bannerTimeout) clearTimeout(bannerTimeout);
}

function showGoalCelebration(event) {
    const goalGif = document.getElementById('goal-celebration');
    if (!goalGif) return;

    const direction = getAttackDirection(event);
    goalGif.style.left = direction === 1 ? '92%' : '8%';
    goalGif.style.top = '50%';
    goalGif.classList.add('visible');

    if (goalGifTimeout) {
        clearTimeout(goalGifTimeout);
    }
    goalGifTimeout = setTimeout(() => {
        goalGif.classList.remove('visible');
    }, 1800);
}

function clearPitchHighlights() {
    for (const el of playerElements.values()) {
        el.classList.remove('involved-primary', 'involved-secondary');
    }
}

function findPlayerElementByName(name) {
    if (!name) return null;
    const playerId = playerNames.get(normalize(name));
    return playerId != null ? playerElements.get(String(playerId)) || null : null;
}

function renderPlayers(state) {
    const container = document.getElementById('players-container');
    const ballEl = document.getElementById('ball');

    if (!Array.isArray(state.players) || state.players.length === 0) {
        for (const [key, el] of playerElements.entries()) {
            el.remove();
            playerElements.delete(key);
            latestPositions.delete(Number(key));
        }
        if (ballEl) {
            ballEl.style.display = 'none';
        }
        return;
    }

    const seen = new Set();

    state.players.forEach(player => {
        const key = String(player.id);
        let el = playerElements.get(key);
        if (!el) {
            el = document.createElement('div');
            el.className = `player ${(player.team || 'HOME').toLowerCase()}`;
            el.dataset.playerId = key;
            el.innerHTML = '<div class="player-marker"><span class="player-number"></span></div><div class="player-label"></div>';
            playerElements.set(key, el);
            container.appendChild(el);
        }

        const slotData = playerSlots.get(Number(player.id));
        const x = clamp(player.x ?? 50, 0, 100);
        const y = clamp(player.y ?? 50, 0, 100);
        const badgeEl = el.querySelector('.player-number');
        const labelEl = el.querySelector('.player-label');
        if (badgeEl) badgeEl.textContent = getPlayerBadge(player.id, slotData);
        if (labelEl) labelEl.textContent = getPlayerShortLabel(slotData, player.id);
        el.title = slotData ? `${slotData.name} (${slotData.position || 'N/A'})` : `Slot ${player.id}`;

        const isCarrier = Number(player.id) === Number(state.carrierPlayerId);
        const isInvolved = currentInvolvedPlayerIds.has(Number(player.id));
        const isGoalkeeper = (slotData?.position || '').toUpperCase() === 'GK';
        el.classList.toggle('carrier', isCarrier);
        el.classList.toggle('involved', isInvolved);
        el.classList.toggle('gk', isGoalkeeper);
        el.classList.toggle('home', (player.team || '').toLowerCase() === 'home');
        el.classList.toggle('away', (player.team || '').toLowerCase() === 'away');
        el.style.left = `${x}%`;
        el.style.top = `${y}%`;

        latestPositions.set(Number(player.id), { x, y, team: (player.team || '').toUpperCase() });
        seen.add(key);
    });

    for (const [key, el] of playerElements.entries()) {
        if (!seen.has(key)) {
            el.remove();
            playerElements.delete(key);
            latestPositions.delete(Number(key));
        }
    }

    // Ball position is now correctly synchronized with carrier in backend (MatchPlaybackEngine)
    if (state.ball && Number.isFinite(state.ball.x) && Number.isFinite(state.ball.y) && ballEl) {
        ballEl.style.left = `${clamp(state.ball.x, 0, 100)}%`;
        ballEl.style.top = `${clamp(state.ball.y, 0, 100)}%`;
        ballEl.style.display = 'block';
    } else if (ballEl) {
        ballEl.style.display = 'none';
    }
}

function initPitchOverlay() {
    const overlay = document.getElementById('pitch-overlay');
    if (!overlay) return;
    syncPitchOverlaySize();
    window.addEventListener('resize', syncPitchOverlaySize);
}

function syncPitchOverlaySize() {
    const pitch = document.querySelector('.pitch');
    const overlay = document.getElementById('pitch-overlay');
    if (!pitch || !overlay) return;
    const rect = pitch.getBoundingClientRect();
    overlay.width = Math.max(1, Math.round(rect.width));
    overlay.height = Math.max(1, Math.round(rect.height));
}

function isShotAnimationEvent(type) {
    return type === 'goal' || type === 'shotontarget' || type === 'shotofftarget' || type === 'penalty';
}

function isCanvasAnimationEvent(type) {
    return isShotAnimationEvent(type) || type === 'varreview' || type === 'substitution' || type === 'matchended' || type === 'injury';
}

function startCanvasAnimation(event) {
    clearCanvasAnimation();
    syncPitchOverlaySize();

    const type = (event.type || '').toLowerCase();
    if (isShotAnimationEvent(type)) {
        const direction = getAttackDirection(event);
        const shooter = resolveShooterPoint(event, direction, type);
        const keeper = resolveGoalkeeperPoint(direction);
        const target = resolveShotTarget(event, type, direction, keeper);
        activeAnimation = {
            type,
            event,
            shooter,
            keeper,
            target,
            scored: Boolean(event.scored),
            startTs: performance.now(),
            duration: type === 'goal' || type === 'penalty' ? 1200 : 950
        };
    } else {
        activeAnimation = {
            type,
            event,
            startTs: performance.now(),
            duration: type === 'varreview' ? 2600 : type === 'matchended' ? 2300 : 2200
        };
    }

    const ballEl = document.getElementById('ball');
    if (ballEl && isShotAnimationEvent(type)) {
        ballEl.style.opacity = '0';
    }

    animationFrame = requestAnimationFrame(drawAnimationFrame);
}

function clearCanvasAnimation() {
    if (animationFrame) {
        cancelAnimationFrame(animationFrame);
        animationFrame = null;
    }
    activeAnimation = null;
    const overlay = document.getElementById('pitch-overlay');
    if (overlay) {
        const ctx = overlay.getContext('2d');
        ctx.clearRect(0, 0, overlay.width, overlay.height);
    }
    const ballEl = document.getElementById('ball');
    if (ballEl) {
        ballEl.style.opacity = '1';
    }
}

function drawAnimationFrame(timestamp) {
    if (!activeAnimation) return;
    const overlay = document.getElementById('pitch-overlay');
    if (!overlay) return;

    const ctx = overlay.getContext('2d');
    const progress = Math.min(1, (timestamp - activeAnimation.startTs) / activeAnimation.duration);
    ctx.clearRect(0, 0, overlay.width, overlay.height);

    if (isShotAnimationEvent(activeAnimation.type)) {
        drawShotMarkers(ctx, activeAnimation, progress);
        drawAnimatedBall(ctx, activeAnimation, progress);
    } else if (activeAnimation.type === 'varreview') {
        drawVarAnimation(ctx, overlay, activeAnimation, progress);
    } else if (activeAnimation.type === 'substitution') {
        drawSubstitutionAnimation(ctx, overlay, activeAnimation);
    } else if (activeAnimation.type === 'matchended') {
        drawMatchEndedAnimation(ctx, overlay);
    } else if (activeAnimation.type === 'injury') {
        drawInjuryAnimation(ctx, overlay, activeAnimation, progress);
    }

    if (progress < 1) {
        animationFrame = requestAnimationFrame(drawAnimationFrame);
    } else {
        clearCanvasAnimation();
    }
}

function drawShotMarkers(ctx, anim, progress) {
    drawPulseCircle(ctx, anim.shooter.x, anim.shooter.y, 13, anim.shooter.color, 0.9);
    drawPulseCircle(ctx, anim.keeper.x, anim.keeper.y, 14, '#f0ad15', 0.78);

    if ((anim.type === 'goal' || (anim.type === 'penalty' && anim.scored)) && progress > 0.82) {
        const glowWidth = 18;
        const goalX = anim.target.x + (anim.target.x > anim.keeper.x ? 4 : -4);
        ctx.fillStyle = 'rgba(255,255,255,0.14)';
        ctx.fillRect(goalX - glowWidth / 2, anim.target.y - 28, glowWidth, 56);
    }
}

function drawAnimatedBall(ctx, anim, progress) {
    const eased = 1 - Math.pow(1 - progress, 3);
    const x = anim.shooter.x + (anim.target.x - anim.shooter.x) * eased;
    const y = anim.shooter.y + (anim.target.y - anim.shooter.y) * eased;
    ctx.fillStyle = '#ffffff';
    ctx.beginPath();
    ctx.arc(x, y, 7, 0, Math.PI * 2);
    ctx.fill();
    ctx.strokeStyle = '#1b1b1b';
    ctx.lineWidth = 1.2;
    ctx.stroke();
}

function drawPulseCircle(ctx, x, y, radius, color, alpha) {
    ctx.save();
    ctx.globalAlpha = alpha;
    ctx.fillStyle = color;
    ctx.beginPath();
    ctx.arc(x, y, radius, 0, Math.PI * 2);
    ctx.fill();
    ctx.restore();
}

function drawVarAnimation(ctx, overlay, anim, progress) {
    const w = overlay.width;
    const h = overlay.height;

    ctx.fillStyle = 'rgba(8, 16, 28, 0.72)';
    ctx.fillRect(0, 0, w, h);

    const cardW = Math.min(520, w - 40);
    const cardH = 230;
    const cardX = (w - cardW) / 2;
    const cardY = (h - cardH) / 2;
    drawRoundedRect(ctx, cardX, cardY, cardW, cardH, 14, '#0f2038', '#3f5f90');

    drawCameraIcon(ctx, cardX + 72, cardY + 70);

    ctx.textAlign = 'left';
    ctx.fillStyle = '#d9f2ff';
    ctx.font = '700 31px Arial';
    if (progress < 0.62) {
        const dots = '.'.repeat((Math.floor(progress * 10) % 3) + 1);
        ctx.fillText(`VAR CHECK${dots}`, cardX + 142, cardY + 96);
        ctx.fillStyle = '#9cb8d8';
        ctx.font = '600 20px Arial';
        ctx.fillText(`Reviewing ${anim.event.reviewTarget || 'incident'}...`, cardX + 142, cardY + 132);
    } else {
        const isOverturned = String(anim.event.decision || '').toLowerCase() === 'overturned';
        ctx.fillStyle = isOverturned ? '#ff6767' : '#8bff9f';
        ctx.fillText(`VAR ${String(anim.event.decision || 'PENDING').toUpperCase()}`, cardX + 142, cardY + 96);
        ctx.fillStyle = '#ffffff';
        ctx.font = '600 22px Arial';
        const reason = anim.event.overturnReason || 'check complete';
        ctx.fillText(`${anim.event.reviewTarget || 'incident'} - ${reason}`, cardX + 142, cardY + 134);
    }
    ctx.textAlign = 'start';
}
function drawInjuryAnimation(ctx, overlay, anim, progress) {
    const w = overlay.width;
    const h = overlay.height;
    const pulse = 0.55 + Math.abs(Math.sin(progress * Math.PI * 6)) * 0.35;
    ctx.fillStyle = `rgba(140, 22, 22, ${pulse})`;
    ctx.fillRect(0, 0, w, h);
    ctx.textAlign = 'center';
    ctx.fillStyle = '#fff';
    ctx.font = '700 46px Arial';
    ctx.fillText('INJURY', w / 2, h / 2 - 16);
    ctx.font = '600 25px Arial';
    ctx.fillText(anim.event.playerName || 'Player', w / 2, h / 2 + 28);
    ctx.textAlign = 'start';
}

function drawSubstitutionAnimation(ctx, overlay, anim) {
    const w = overlay.width;
    const h = overlay.height;
    ctx.fillStyle = 'rgba(12, 28, 20, 0.78)';
    ctx.fillRect(0, 0, w, h);
    ctx.textAlign = 'center';
    ctx.fillStyle = '#d8ffe8';
    ctx.font = '700 42px Arial';
    ctx.fillText('SUBSTITUTION', w / 2, h / 2 - 30);
    ctx.font = '700 26px Arial';
    ctx.fillStyle = '#ff9b9b';
    ctx.fillText(anim.event.playerOutName || 'Player out', w / 2, h / 2 + 8);
    ctx.fillStyle = '#9bffb5';
    ctx.fillText(anim.event.playerInName || 'Player in', w / 2, h / 2 + 48);
    ctx.textAlign = 'start';
}

function drawMatchEndedAnimation(ctx, overlay) {
    const w = overlay.width;
    const h = overlay.height;

    ctx.fillStyle = 'rgba(8, 12, 20, 0.66)';
    ctx.fillRect(0, 0, w, h);

    const cardW = Math.min(460, w - 36);
    const cardH = 250;
    const cardX = Math.floor((w - cardW) / 2);
    const cardY = Math.floor((h - cardH) / 2);
    drawRoundedRect(ctx, cardX, cardY, cardW, cardH, 14, '#111b2a', '#3e5f86');

    if (matchEndedImg.complete) {
        ctx.drawImage(matchEndedImg, cardX + 20, cardY + 20, 180, 120);
    }

    ctx.fillStyle = '#d9f2ff';
    ctx.font = '700 36px Arial';
    ctx.fillText('FULL TIME', cardX + 220, cardY + 92);
    ctx.font = '600 24px Arial';
    ctx.fillStyle = '#ffffff';
    ctx.fillText(`${matchData.homeTeam} ${homeScore} - ${awayScore} ${matchData.awayTeam}`, cardX + 28, cardY + 190);
}

function drawRoundedRect(ctx, x, y, w, h, r, fill, stroke) {
    ctx.beginPath();
    ctx.moveTo(x + r, y);
    ctx.lineTo(x + w - r, y);
    ctx.quadraticCurveTo(x + w, y, x + w, y + r);
    ctx.lineTo(x + w, y + h - r);
    ctx.quadraticCurveTo(x + w, y + h, x + w - r, y + h);
    ctx.lineTo(x + r, y + h);
    ctx.quadraticCurveTo(x, y + h, x, y + h - r);
    ctx.lineTo(x, y + r);
    ctx.quadraticCurveTo(x, y, x + r, y);
    ctx.closePath();
    ctx.fillStyle = fill;
    ctx.fill();
    ctx.strokeStyle = stroke;
    ctx.lineWidth = 2;
    ctx.stroke();
}

function drawCameraIcon(ctx, cx, cy) {
    ctx.fillStyle = '#4fb2ff';
    ctx.fillRect(cx - 30, cy - 18, 60, 36);
    ctx.fillStyle = '#0f2038';
    ctx.fillRect(cx + 24, cy - 10, 20, 20);
    ctx.fillStyle = '#d6f3ff';
    ctx.beginPath();
    ctx.arc(cx, cy, 12, 0, Math.PI * 2);
    ctx.fill();
    ctx.fillStyle = '#4fb2ff';
    ctx.beginPath();
    ctx.arc(cx, cy, 6, 0, Math.PI * 2);
    ctx.fill();
}

function getAttackDirection(event) {
    const teamName = normalize(event.teamName);
    if (teamName && teamName === normalize(matchData.homeTeam)) {
        return 1;
    }
    return -1;
}

function resolveShooterPoint(event, direction, type) {
    if (type === 'penalty') {
        return getPenaltySpot(direction);
    }

    const name = event.playerName || event.scorerName || event.takerName || event.goalkeeperName;
    const fromPlayer = getPlayerPointByName(name);
    if (fromPlayer) return fromPlayer;

    const fallbackX = direction === 1 ? 78 : 22;
    return {
        x: pitchPercentToX(fallbackX),
        y: pitchPercentToY(50),
        color: resolveTeamColorForEvent(event)
    };
}

function resolveGoalkeeperPoint(direction) {
    const defendingTeam = direction === 1 ? 'AWAY' : 'HOME';
    for (const [playerId, slot] of playerSlots.entries()) {
        if ((slot.position || '').toUpperCase() !== 'GK') continue;
        const current = latestPositions.get(Number(playerId));
        if (current && current.team === defendingTeam) {
            return {
                x: pitchPercentToX(current.x),
                y: pitchPercentToY(current.y),
                color: '#f0ad15'
            };
        }
    }

    return {
        x: pitchPercentToX(direction === 1 ? 94 : 6),
        y: pitchPercentToY(50),
        color: '#f0ad15'
    };
}

function resolveShotTarget(event, type, direction, keeper) {
    const insideGoalX = direction === 1 ? 97 : 3;
    const missX = direction === 1 ? 100 : 0;
    const verticalBias = (Math.random() - 0.5) * 26;

    if (type === 'goal' || (type === 'penalty' && event.scored)) {
        const keeperAvoid = keeper.y <= pitchPercentToY(50) ? 18 : -18;
        return {
            x: pitchPercentToX(insideGoalX),
            y: clampPx(keeper.y + keeperAvoid + verticalBias * 0.15, 26)
        };
    }

    if (type === 'shotontarget' || (type === 'penalty' && !event.scored)) {
        return {
            x: keeper.x,
            y: keeper.y + (Math.random() - 0.5) * 8
        };
    }

    return {
        x: pitchPercentToX(missX),
        y: clampPx(pitchPercentToY(50) + verticalBias + (Math.random() < 0.5 ? -30 : 30), 12)
    };
}

function resolveTeamColorForEvent(event) {
    return getAttackDirection(event) === 1 ? '#FF6B6B' : '#4ECDC4';
}

function getPlayerPointByName(name) {
    if (!name) return null;
    const playerId = playerNames.get(normalize(name));
    if (playerId == null) return null;
    const pos = latestPositions.get(Number(playerId));
    if (!pos) return null;
    return {
        x: pitchPercentToX(pos.x),
        y: pitchPercentToY(pos.y),
        color: pos.team === 'HOME' ? '#FF6B6B' : '#4ECDC4'
    };
}

function getPenaltySpot(direction) {
    return {
        x: pitchPercentToX(direction === 1 ? 88 : 12),
        y: pitchPercentToY(50),
        color: direction === 1 ? '#FF6B6B' : '#4ECDC4'
    };
}

function pitchPercentToX(percent) {
    const overlay = document.getElementById('pitch-overlay');
    return (clamp(percent, 0, 100) / 100) * (overlay?.width || 1);
}

function pitchPercentToY(percent) {
    const overlay = document.getElementById('pitch-overlay');
    return (clamp(percent, 0, 100) / 100) * (overlay?.height || 1);
}

function clampPx(value, padding) {
    const overlay = document.getElementById('pitch-overlay');
    const maxY = Math.max((overlay?.height || 1) - padding, padding);
    return Math.max(padding, Math.min(maxY, value));
}
function getPlayerBadge(positionId, slotData) {
    if (slotData && Number.isFinite(Number(slotData.squadNumber)) && Number(slotData.squadNumber) > 0) {
        return String(slotData.squadNumber);
    }
    if (!slotData || !slotData.name) {
        return String(positionId);
    }
    const parts = slotData.name.trim().split(/\s+/).filter(Boolean);
    if (parts.length === 1) return parts[0].slice(0, 2).toUpperCase();
    return `${parts[0][0] || ''}${parts[parts.length - 1][0] || ''}`.toUpperCase();
}

function getPlayerShortLabel(slotData, playerId) {
    if (!slotData || !slotData.name) {
        return `Player ${playerId}`;
    }

    const parts = slotData.name.trim().split(/\s+/).filter(Boolean);
    return parts[parts.length - 1] || slotData.name;
}

function updateDisplayedMinute(minute, force = false) {
    const nextMinute = Number(minute) || 0;
    displayMinute = force ? nextMinute : Math.max(displayMinute, nextMinute);
    document.getElementById('minute').textContent = `${displayMinute}'`;
}

function updateScore() {
    document.getElementById('score').textContent = `${homeScore} - ${awayScore}`;
    document.getElementById('homeStats').textContent = buildTeamSummary('HOME');
    document.getElementById('awayStats').textContent = buildTeamSummary('AWAY');
}

function createTeamStats() {
    return {
        xG: 0,
        shots: 0,
        onTarget: 0,
        dangerousAttacks: 0,
        corners: 0,
        yellowCards: 0,
        redCards: 0
    };
}

function resetDerivedState() {
    teamStats.HOME = createTeamStats();
    teamStats.AWAY = createTeamStats();
    keyMoments.length = 0;
    pendingVarGoals.length = 0;
}

function shouldQueueEvent(event) {
    const type = normalizeEventType(event.type);
    if (type === 'possession') return false;
    return resolveEventCategory(event) !== 'micro';
}

function isPitchKeyEvent(event) {
    return Boolean(event.keyEvent) || ['key', 'system'].includes(resolveEventCategory(event));
}

function resolveEventDelay(event) {
    const type = normalizeEventType(event.type);
    const importance = resolveEventImportance(event);

    if (type === 'varreview') return 2700;
    if (type === 'matchended') return 2500;
    if (type === 'goal') return GOAL_DELAY;
    if (isCanvasAnimationEvent(type)) return 2200;
    if (importance === 'critical') return 1800;
    if (importance === 'high') return 1500;
    if (resolveEventCategory(event) === 'commentary') return 950;
    return EVENT_DELAY;
}

function resolveEventCategory(event) {
    const mapped = normalize(event.displayCategory);
    if (mapped) return mapped;

    const type = normalizeEventType(event.type);
    if (['goal', 'penalty', 'shotontarget', 'shotofftarget', 'varreview', 'yellowcard', 'redcard', 'injury', 'substitution'].includes(type)) {
        return 'key';
    }
    if (type === 'chance') {
        return event.dangerous ? 'key' : 'commentary';
    }
    if (['matchstarted', 'matchended'].includes(type)) {
        return 'system';
    }
    if (['corner', 'throwin', 'goalkick', 'freekick', 'offside'].includes(type)) {
        return 'commentary';
    }
    return 'micro';
}

function resolveEventImportance(event) {
    const mapped = normalize(event.importance);
    if (mapped) return mapped;

    const type = normalizeEventType(event.type);
    if (type === 'goal') return 'critical';
    if (['varreview', 'penalty', 'redcard'].includes(type)) return 'high';
    if (type === 'chance') return event.dangerous ? 'high' : 'medium';
    if (['shotontarget', 'shotofftarget', 'yellowcard', 'injury', 'substitution', 'matchstarted', 'matchended'].includes(type)) {
        return 'medium';
    }
    return 'low';
}

function resolveEventClockLabel(event) {
    if (event.clockLabel) return event.clockLabel;
    const type = normalizeEventType(event.type);
    if (type === 'matchstarted') return 'KO';
    if (type === 'matchended') return 'FT';
    return `${Number.isFinite(event.minute) ? Number(event.minute) : displayMinute}'`;
}

function resolveEventTypeLabel(event) {
    const type = normalizeEventType(event.type);
    switch (type) {
        case 'matchstarted': return 'Kick-off';
        case 'matchended': return 'Full time';
        case 'goal': return 'Goal';
        case 'penalty': return 'Penalty';
        case 'chance': return event.dangerous ? 'Big chance' : 'Attack';
        case 'shotontarget': return 'Shot on target';
        case 'shotofftarget': return 'Shot off target';
        case 'yellowcard': return 'Yellow card';
        case 'redcard': return 'Red card';
        case 'corner': return 'Corner';
        case 'offside': return 'Offside';
        case 'throwin': return 'Throw-in';
        case 'goalkick': return 'Goal kick';
        case 'freekick': return 'Free kick';
        case 'injury': return 'Injury';
        case 'substitution': return 'Substitution';
        case 'varreview': return 'VAR';
        default: return 'Match event';
    }
}

function buildEventHeadline(event) {
    const type = normalizeEventType(event.type);
    const player = event.playerName || event.scorerName || event.takerName || event.goalkeeperName || event.playerOutName || event.playerInName || 'Unknown';
    const team = event.teamName || 'team';

    switch (type) {
        case 'matchstarted':
            return `${matchData.homeTeam} vs ${matchData.awayTeam}`;
        case 'goal':
            return `${player} scores${event.assistantName ? `, assisted by ${event.assistantName}` : ''} for ${team}`;
        case 'penalty':
            return `${player} ${event.scored ? 'converts' : 'misses'} the penalty`;
        case 'chance':
            return event.dangerous
                ? `${team} create a dangerous attack${player ? ` through ${player}` : ''}`
                : (event.description || `${team} keep the pressure on`);
        case 'shotontarget':
            return `${player} forces a save / shot on target`;
        case 'shotofftarget':
            return `${player} misses the target`;
        case 'yellowcard':
            return `${player} goes into the book`;
        case 'redcard':
            return `${player} is sent off`;
        case 'corner':
            return `Corner for ${team}${event.takerName ? ` • ${event.takerName}` : ''}`;
        case 'offside':
            return `${player} is caught offside`;
        case 'throwin':
            return `Throw-in for ${team}`;
        case 'goalkick':
            return `Goal kick for ${team}${event.goalkeeperName ? ` • ${event.goalkeeperName}` : ''}`;
        case 'freekick':
            return `Free kick for ${team}${event.takerName ? ` • ${event.takerName}` : ''}`;
        case 'injury':
            return `Play stops for ${player}`;
        case 'substitution':
            return `${team} change: ${event.playerOutName || '?'} off, ${event.playerInName || '?'} on`;
        case 'varreview':
            return `VAR ${String(event.decision || 'review').toUpperCase()}${event.overturnReason ? ` • ${event.overturnReason}` : ''}`;
        case 'matchended':
            return `${matchData.homeTeam} ${homeScore} - ${awayScore} ${matchData.awayTeam}`;
        default:
            return event.description || 'Match event';
    }
}

function updateTeamStats(event) {
    const type = normalizeEventType(event.type);
    const side = resolveTeamSide(event.teamName);
    if (!side) return;

    const stats = teamStats[side];
    const xg = resolveEventXgValue(event);
    if (xg != null && ['goal', 'shotontarget', 'shotofftarget'].includes(type)) {
        stats.xG += xg;
    }

    switch (type) {
        case 'goal':
            stats.shots += 1;
            stats.onTarget += 1;
            break;
        case 'penalty':
            stats.shots += 1;
            if (event.scored) stats.onTarget += 1;
            break;
        case 'shotontarget':
            stats.shots += 1;
            stats.onTarget += 1;
            break;
        case 'shotofftarget':
            stats.shots += 1;
            break;
        case 'chance':
            if (event.dangerous) stats.dangerousAttacks += 1;
            break;
        case 'corner':
            stats.corners += 1;
            break;
        case 'yellowcard':
            stats.yellowCards += 1;
            break;
        case 'redcard':
            stats.redCards += 1;
            break;
        default:
            break;
    }
}

function buildTeamSummary(side) {
    const stats = teamStats[side];
    if (!stats) return 'Waiting for simulation...';

    const isEmpty = stats.shots === 0
        && stats.onTarget === 0
        && stats.xG === 0
        && stats.corners === 0
        && stats.yellowCards === 0
        && stats.redCards === 0;

    if (isEmpty && displayMinute === 0) {
        return 'Waiting for simulation...';
    }

    return `xG ${formatNumber(stats.xG)} • Shots ${stats.shots} • OT ${stats.onTarget}`;
}

function renderStatBoard() {
    setText('stat-home-xg', formatNumber(teamStats.HOME.xG));
    setText('stat-away-xg', formatNumber(teamStats.AWAY.xG));
    setText('stat-home-shots', String(teamStats.HOME.shots));
    setText('stat-away-shots', String(teamStats.AWAY.shots));
    setText('stat-home-on-target', String(teamStats.HOME.onTarget));
    setText('stat-away-on-target', String(teamStats.AWAY.onTarget));
    setText('stat-home-big-chances', String(teamStats.HOME.dangerousAttacks));
    setText('stat-away-big-chances', String(teamStats.AWAY.dangerousAttacks));
    setText('stat-home-corners', String(teamStats.HOME.corners));
    setText('stat-away-corners', String(teamStats.AWAY.corners));
    setText('stat-home-cards', formatCards(teamStats.HOME));
    setText('stat-away-cards', formatCards(teamStats.AWAY));
}

function appendKeyMoment(event) {
    if (!isPitchKeyEvent(event)) return;

    keyMoments.unshift({
        clock: resolveEventClockLabel(event),
        label: resolveEventTypeLabel(event),
        text: buildEventHeadline(event),
        importance: resolveEventImportance(event)
    });

    if (keyMoments.length > MAX_KEY_MOMENTS) {
        keyMoments.length = MAX_KEY_MOMENTS;
    }

    renderKeyMoments();
}

function renderKeyMoments() {
    const list = document.getElementById('key-moments-list');
    if (!list) return;

    if (keyMoments.length === 0) {
        list.innerHTML = '<div class="placeholder">Key moments will appear here.</div>';
        return;
    }

    list.innerHTML = keyMoments.map(moment => `
        <div class="moment-item ${moment.importance}">
            <div class="moment-time">${escapeHtml(moment.clock)}</div>
            <div class="moment-body">
                <div class="moment-label">${escapeHtml(moment.label)}</div>
                <div class="moment-text">${escapeHtml(moment.text)}</div>
            </div>
        </div>
    `).join('');
}

function resolveTeamSide(teamName) {
    const normalizedTeam = normalize(teamName);
    if (!normalizedTeam) return null;
    if (normalizedTeam === normalize(matchData.homeTeam)) return 'HOME';
    if (normalizedTeam === normalize(matchData.awayTeam)) return 'AWAY';
    return null;
}

function resolveEventXgValue(event) {
    const xg = Number(event?.xG ?? event?.xg ?? event?.x_g);
    return Number.isFinite(xg) ? xg : null;
}

function formatXgBadge(event) {
    const xg = resolveEventXgValue(event);
    return xg != null ? `xG ${formatNumber(xg)}` : '';
}

function formatCards(stats) {
    return `${stats.yellowCards}Y • ${stats.redCards}R`;
}

function resolveBannerDuration(event) {
    const importance = resolveEventImportance(event);
    if (importance === 'critical') return 2400;
    if (importance === 'high') return 1900;
    return 1400;
}

function normalizeEventType(type) {
    return normalize(type).replace(/\s+/g, '');
}

function formatNumber(value) {
    const numeric = Number(value);
    return Number.isFinite(numeric) ? numeric.toFixed(2) : '0.00';
}

function setText(id, value) {
    const node = document.getElementById(id);
    if (node) node.textContent = value;
}

function escapeHtml(value) {
    return String(value ?? '')
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}

function parseScore(value) {
    if (!value || typeof value !== 'string') return null;
    const match = value.match(/(\d+)\s*[:\-]\s*(\d+)/);
    if (!match) return null;
    return { home: Number(match[1]), away: Number(match[2]) };
}

function normalize(value) {
    return (value || '').trim().toLowerCase();
}

function clamp(value, min, max) {
    return Math.max(min, Math.min(max, value));
}

window.addEventListener('beforeunload', () => {
    clearCanvasAnimation();
    if (playbackFrame) cancelAnimationFrame(playbackFrame);
    if (goalGifTimeout) clearTimeout(goalGifTimeout);
    if (bannerTimeout) clearTimeout(bannerTimeout);
    if (highlightTimeout) clearTimeout(highlightTimeout);
});
