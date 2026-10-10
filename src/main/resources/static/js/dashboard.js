// dashboard.js
import { escapeHtml } from './ui/escape.js';
import { authFetch, handleAuthFailure, setSessionRole, isAdminSession, applyAdminVisibility } from './auth.js';
import { startNotificationPolling, wireNotificationBell, readUnreadCount } from './notifications.js';

let currentUserTeamId = null;
let currentUserTeamName = null;
// The club badge comes from the API now. It used to be a hardcoded
// name === 'OFK Omladinac' check, which had no room for a second badge.
let currentUserTeamLogoUrl = null;
let currentUserCompetitionId = null;
let currentUserCompetitionName = null;
let currentSeasonYear = null;
let currentUserCountryName = null;
let currentUserCountryIsoCode = null;
let currentUserRole = null;

const DASHBOARD_FLOW_FLASH_KEY = 'dashboardSeasonFlowFlash';
const DASHBOARD_WEEK_CONSUMED_KEY = 'dashboardWeekConsumed';

function updateCountryMenuLabels() {
    const countryLabel = currentUserCountryName || 'Country';
    const desktopButton = document.getElementById('country-menu-button');
    const mobileButton = document.getElementById('country-mobile-button');

    if (desktopButton) {
        desktopButton.classList.add('fm-menu-country');
        desktopButton.innerHTML = buildCountryMenuLabelHtml(countryLabel);
    }
    if (mobileButton) {
        mobileButton.classList.add('fm-menu-country');
        mobileButton.innerHTML = buildCountryMenuLabelHtml(countryLabel);
    }
}

function getCountryMenuFlagPath() {
    return String(currentUserCountryIsoCode || '').trim().toUpperCase() === 'SRB'
        ? '/images/serbiaflag.png'
        : '';
}

function buildCountryMenuLabelHtml(countryLabel) {
    const safeLabel = escapeHtml(countryLabel || 'Country');
    const flagPath = getCountryMenuFlagPath();
    const iconMarkup = flagPath
        ? `<img class="fm-menu-country-icon" src="${escapeHtml(flagPath)}" alt="${safeLabel} flag">`
        : '<span class="fm-menu-country-emoji">🌎</span>';
    return `<span class="fm-menu-country-content">${iconMarkup}<span class="fm-menu-country-label">${safeLabel}</span></span>`;
}

function getCurrentTeamImagePath() {
    return currentUserTeamLogoUrl || '/images/default-team.png';
}

function getCurrentLeagueId() {
    const leagueId = Number(currentUserCompetitionId);
    // No default: a club without a competition has no league, and guessing league 1 would put
    // another club's table in front of this manager.
    return Number.isFinite(leagueId) && leagueId > 0 ? leagueId : null;
}

/**
 * A club with no competition has no league table.
 *
 * <p>Says so, rather than leaving the previous manager's table or a spinner. A world that is still
 * being built looks like this, so the wording points at the cause instead of at the club.
 */
function setLeagueStatsUnavailable() {
    document.querySelectorAll('.stat-value').forEach(el => { el.textContent = '—'; });
    const subtitle = document.querySelector('.stat-subtitle');
    if (subtitle && !subtitle.textContent.trim()) {
        subtitle.textContent = 'No league yet';
    }
}

function getCurrentLeagueName() {
    return currentUserCompetitionName || 'League';
}

function formatSeasonLabel(seasonYear) {
    // A season is a number counted from 1, not a calendar year. This used to render "2025/26",
    // which cannot be a season at all: a manager's season is twelve weeks, so four of them run in
    // a year and no two of them share a year.
    const season = Number(seasonYear);
    if (!Number.isFinite(season)) return 'Current season';
    return String(season);
}

function buildDashboardSubtitle() {
    const seasonLabel = currentSeasonYear ? `Season ${formatSeasonLabel(currentSeasonYear)}` : 'Current season';
    return `<span class="cs-clickable" onclick="loadLeagueTable()">${escapeHtml(getCurrentLeagueName())}</span> · ${escapeHtml(seasonLabel)}`;
}


function parseDashboardDate(value) {
    if (!value || value === 'N/A') return null;
    const normalized = String(value).includes('T') ? String(value) : String(value).replace(' ', 'T');
    const parsed = new Date(normalized);
    return Number.isNaN(parsed.getTime()) ? null : parsed;
}

/**
 * When a fixture is, in the game's own terms.
 *
 * <p><b>No calendar.</b> This printed "1. 10. 2026. - 17:16" - a wall-clock date the owner does not have
 * any use for, because there is no calendar in this game: there is a season, a week, a day and an hour,
 * and the season is twelve weeks so no month or year can name one.
 *
 * <p>So it prints the day and the kickoff hour, which is the whole of what exists. The hour comes from
 * the schedule template (day 3 is 19:00, day 7 is 16:00); the minute is dropped because a fixture now
 * carries a template hour and a zero minute, and printing ":00" on every one of them is noise.
 */
function formatDashboardDate(value) {
    const parsed = parseDashboardDate(value);
    if (!parsed) return 'Date TBD';
    const hour = String(parsed.getHours()).padStart(2, '0');
    return `${hour}:00`;
}

function renderNextMatchCard(bodyHtml) {
    const host = document.getElementById('next-match-card');
    if (!host) return null;
    host.innerHTML = `<h3>Next Match</h3>${bodyHtml}`;
    return host;
}

function renderNextMatchEmpty(message, meta) {
    renderNextMatchCard(`
        <div class="match-info">
            <div class="empty-badge-wrap"><span class="empty-badge">${escapeHtml(message)}</span></div>
        </div>
        <div class="match-date">${escapeHtml(meta || 'The next fixture will appear here once the schedule is ready.')}</div>`);
}

function buildImportantTickerMarkup(message) {
    const safeMessage = escapeHtml(message || 'No urgent club updates right now.');
    return `<div class="fm-dashboard-ticker-move"><span>${safeMessage}</span></div>`;
}

// Single source of truth lives in auth.js (isAdminSession), which matches the
// backend /admin/** gate. This wrapper is kept because the inline markup calls it.
function isAdminUser() {
    return isAdminSession();
}

function readDashboardFlowFlash() {
    try {
        const raw = sessionStorage.getItem(DASHBOARD_FLOW_FLASH_KEY);
        if (!raw) return null;
        sessionStorage.removeItem(DASHBOARD_FLOW_FLASH_KEY);
        const parsed = JSON.parse(raw);
        return parsed && typeof parsed.message === 'string' ? parsed : null;
    } catch (err) {
        console.warn('Failed to read dashboard flow flash:', err);
        sessionStorage.removeItem(DASHBOARD_FLOW_FLASH_KEY);
        return null;
    }
}

function isWeekConsumed() {
    try {
        return sessionStorage.getItem(DASHBOARD_WEEK_CONSUMED_KEY) === 'true';
    } catch (err) {
        console.warn('Failed to read week consumed flag:', err);
        return false;
    }
}

/**
 * The server's answer on whether watching is available right now (owner, 2026-09-29).
 *
 * <p>Cached on window so the dashboard does not call it on every render, and deliberately tolerant:
 * if the endpoint is unreachable the gate falls back to the old week rule rather than locking the
 * manager out of watching entirely.
 */
function readWatchStatus() {
    if (readWatchStatus._cached) return readWatchStatus._cached;
    if (typeof window === 'undefined' || !window.__fmWatchStatus) return null;
    readWatchStatus._cached = window.__fmWatchStatus;
    return readWatchStatus._cached;
}

function buildSeasonFlowPanel() {
    const flash = readDashboardFlowFlash();
    const weekConsumed = isWeekConsumed();
    // Gated on the server, not on a week boolean. The server knows the current game day and the
    // kickoff hour for it; the browser used to guess with "is the week consumed", which made Watch
    // available at 08:00 on a match day and unavailable on a day that had a fixture on it.
    const watch = readWatchStatus();
    const watchDisabled = watch && watch.available === false
        ? ' disabled aria-disabled="true"'
        : (weekConsumed ? ' disabled aria-disabled="true"' : '');
    const watchTitle = watch && !watch.available ? ` title="${escapeHtml(watch.reason || '')}"` : '';
    const statusTone = flash?.tone || 'info';
    const statusMessage = flash?.message || (watch && !watch.available
        ? (watch.reason || 'Your match is not available yet.')
        : weekConsumed
        ? 'Current match week is already locked in. Use Advance Week to move forward and unlock the next replay/results cycle.'
        : 'Prepare the current week once, then either watch your match or open the live results desk before advancing.');
    const simulateDisabled = weekConsumed ? ' disabled aria-disabled="true"' : '';

    return `
        <div class="recent-matches-section fm-season-flow-panel">
            <div class="fm-season-flow-head">
                <div>
                    <h3>Match Week Controls</h3>
                    <div class="fm-season-flow-copy">${escapeHtml(weekConsumed
                        ? 'Your replay and live results desk are now closed for this week. Advance the calendar to unlock the next match week.'
                        : 'Current week can be prepared once, then viewed either through your replay or the live results desk. After that, advance the calendar.')}</div>
                </div>
                ${isAdminUser() ? '<span class="fm-season-flow-badge">Admin</span>' : ''}
            </div>
            <div class="dashboard-actions fm-season-flow-buttons">
                <button id="start-realistic-demo-btn"${watchTitle} data-label="⚽ Watch Your Match" class="fm-action-btn fm-dashboard-cta" onclick="startRealisticDemoTest()"${watchDisabled}>⚽ Watch Your Match</button>
                <button id="simulate-current-round-btn" data-label="🧮 Simulate All Results" class="fm-action-btn secondary" onclick="simulateCurrentRoundTest()"${simulateDisabled}>🧮 Simulate All Results</button>
                ${isAdminUser() ? '<button id="advance-week-btn" data-label="📅 Advance Week" class="fm-action-btn secondary" onclick="advanceWeekTest()">📅 Advance Week</button>' : ''}
                ${isAdminUser() ? '<button id="advance-day-btn" data-label="📆 Advance Day" class="fm-action-btn secondary" onclick="advanceDayTest()">📆 Advance Day</button>' : ''}
                ${isAdminUser() ? '<button id="advance-hour-btn" data-label="⏩ Advance Hour" class="fm-action-btn secondary" onclick="advanceHourTest()">⏩ Advance Hour</button>' : ''}
            </div>
            <div id="dashboard-season-flow-status" class="fm-season-flow-status is-${escapeHtml(statusTone)}">${escapeHtml(statusMessage)}</div>
        </div>`;
}

function buildImportantTickerLine(updates) {
    return updates.map(update => {
        const severity = update?.severity === 'alert' ? 'Urgent' : update?.severity === 'warning' ? 'Watch' : 'Info';
        const title = update?.title || 'Update';
        const meta = update?.meta || 'No extra context available.';
        return `${severity}: ${title} — ${meta}`;
    }).join(' ✦ ');
}

/**
 * The transfer window, in the manager's language (owner, 2026-09-27).
 *
 * <p>Three states, because they are three different urgencies. "Opened" is an opportunity and must
 * not shout. "Closes soon" is the one that matters, because the manager has no other way of finding
 * out. "Closed" is worth saying once, loudly, so nobody spends a week trying to make a deal that
 * cannot happen - while still naming what stays possible, or they will assume everything is
 * frozen.
 */
function buildWindowUpdates(windowState) {
    if (!windowState || typeof windowState.week !== 'number') return [];
    const updates = [];
    const name = windowState.window === 'SUMMER' ? 'Mid-season' : 'End-of-season';

    if (windowState.open) {
        const weeksLeft = Number(windowState.weeksLeft ?? 0);
        if (windowState.deadlineDay) {
            updates.push({
                severity: 'alert',
                title: 'Transfer deadline today',
                meta: `The ${name.toLowerCase()} window shuts at the end of week ${windowState.closesOnWeek}. Free agents, released players and unlisted players can still be approached.`
            });
        } else if (weeksLeft <= 1) {
            updates.push({
                severity: 'alert',
                title: 'Transfer window closes tomorrow',
                meta: `One week left on the ${name.toLowerCase()} window (closes week ${windowState.closesOnWeek}).`
            });
        } else {
            updates.push({
                severity: 'warning',
                title: `Transfer window open - ${weeksLeft} week${weeksLeft === 1 ? '' : 's'} left`,
                meta: `The ${name.toLowerCase()} window closes at the end of week ${windowState.closesOnWeek}.`
            });
        }
        return updates;
    }

    // Closed. Only worth saying if the window is about to reopen, or has just shut - otherwise it
    // is a permanent fact and belongs on the transfer centre, not in a news bar.
    const next = windowState.nextOpensOnWeek;
    if (next != null && Number(windowState.week) >= (next - 1)) {
        updates.push({
            severity: 'info',
            title: 'Transfer window closed',
            meta: `It reopens in week ${next}. Free agents, released players and players with no asking price can still be signed.`
        });
    }
    return updates;
}

/**
 * Time-boxed items that currently expire without a sound (owner, 2026-09-27).
 *
 * <p>Both were picked first because they share the same failure: nothing happens, no error, and the
 * opportunity is simply gone by the time anyone noticed. A friendly request lapses after its week; a
 * week's training is a once-a-week action that simply does not happen on a busy week.
 */
function buildTimeboxedUpdates(friendlyWeek, clock, trainingReports) {
    const updates = [];

    // A club has asked us to play a friendly and is waiting for an answer.
    const incoming = Array.isArray(friendlyWeek?.incoming) ? friendlyWeek.incoming : [];
    if (incoming.length > 0) {
        const request = incoming[0];
        const extra = incoming.length > 1 ? ` (and ${incoming.length - 1} more)` : '';
        updates.push({
            severity: 'alert',
            title: `Friendly request awaiting your answer${extra}`,
            meta: `A club has asked for week ${request?.week} slot ${request?.slot}. It lapses at the end of that week.`
        });
    }

    // Training is once a week and produces nothing visible when skipped.
    if (clock && Array.isArray(trainingReports)) {
        const season = Number(clock.currentSeason ?? clock.seasonNumber);
        const week = Number(clock.currentWeek ?? clock.weekNumber);
        const done = trainingReports.some(r =>
            Number(r?.seasonNumber) === season && Number(r?.weekNumber) === week);
        if (Number.isFinite(season) && Number.isFinite(week) && !done) {
            updates.push({
                severity: 'warning',
                title: 'Weekly training not run yet',
                meta: `Season ${season}, week ${week}. Training is once a week and will not run itself.`
            });
        }
    }

    return updates;
}

function buildImportantUpdates(medical, lineupTemplate, transferOverview, notifications, windowState,
                               friendlyWeek, clock, trainingReports) {
    const updates = [];

    // The time-boxed ones go in first: they are the only entries that stop being true.
    updates.push(...buildTimeboxedUpdates(friendlyWeek, clock, trainingReports));
    const recoveryQueue = Array.isArray(medical?.recoveryQueue) ? medical.recoveryQueue.filter(Boolean) : [];
    const starterIds = Array.isArray(lineupTemplate?.starterIds) ? lineupTemplate.starterIds.filter(Boolean) : [];
    const benchIds = Array.isArray(lineupTemplate?.benchIds) ? lineupTemplate.benchIds.filter(Boolean) : [];
    const listedPlayers = Array.isArray(transferOverview?.listedPlayers) ? transferOverview.listedPlayers : [];
    const interestedListings = listedPlayers.filter(player => Array.isArray(player?.interestedTeams) && player.interestedTeams.length > 0);

    // The unread notifications, not a recomputation.
    //
    // <p>Was `/community/summary`, which counted chat rows newer than one timestamp on the account. A
    // single cursor cannot answer "which of my four unread messages did I see", and it is gone in Phase
    // 6 along with the chat. What is here now reads a real per-notification store.
    //
    // <p>Only the newest is put in the ticker, because the bar is one flat string and six summaries from
    // a busy forum would crowd out a transfer deadline. The badge carries the count.
    const unread = Array.isArray(notifications?.notifications)
        ? notifications.notifications.filter(row => row && row.read === false)
        : [];
    if (unread.length) {
        const latest = unread[0];
        updates.push({
            severity: 'alert',
            title: unread.length === 1 ? 'New notification' : `${unread.length} new notifications`,
            meta: latest.summary || 'Open the bell to read it.'
        });
    }

    if (lineupTemplate?.saved === false) {
        updates.push({
            severity: 'alert',
            title: 'Lineup template not saved',
            meta: `Current setup is still draft-only (${starterIds.length}/11 starters, ${benchIds.length}/7 bench).`
        });
    }
    if (starterIds.length < 11) {
        updates.push({
            severity: 'alert',
            title: 'Starting XI is incomplete',
            meta: `You currently have ${starterIds.length}/11 starters selected in the saved lineup template.`
        });
    }
    if (benchIds.length < 7) {
        updates.push({
            severity: 'warning',
            title: 'Bench depth missing',
            meta: `Only ${benchIds.length}/7 bench slots are filled right now.`
        });
    }
    if (Number(medical?.injuredCount || 0) > 0) {
        const recoverySample = recoveryQueue.slice(0, 2)
            .map(player => `${player?.name || 'Player'}${player?.injuryDaysRemaining ? ` (${player.injuryDaysRemaining}d)` : ''}`)
            .join(', ');
        updates.push({
            severity: Number(medical?.criticalInjuryCount || 0) > 0 ? 'alert' : 'warning',
            title: `${Number(medical?.injuredCount || 0)} player${Number(medical?.injuredCount || 0) === 1 ? '' : 's'} unavailable`,
            meta: recoverySample || 'Medical Center has active injury cases and recovery work pending.'
        });
    }
    // Window news goes first: it is the only entry here with a deadline attached.
    const windowUpdates = buildWindowUpdates(windowState);
    updates.push(...windowUpdates);

    if (interestedListings.length > 0) {
        const topListing = interestedListings[0];
        updates.push({
            severity: 'info',
            title: 'Transfer interest received',
            meta: `${topListing?.playerName || 'Listed player'} has ${topListing?.interestedTeams?.length || 0} interested club${(topListing?.interestedTeams?.length || 0) === 1 ? '' : 's'} on the market.`
        });
    } else if (Number(transferOverview?.listedCount || 0) > 0) {
        updates.push({
            severity: 'info',
            title: 'Players active on the market',
            meta: `${Number(transferOverview?.listedCount || 0)} player${Number(transferOverview?.listedCount || 0) === 1 ? '' : 's'} currently listed in your Transfer Centre.`
        });
    }

    // Six, not four. A capped bar that drops a transfer deadline in favour of a chat notification
    // is worse than a longer bar, and the cap exists only to stop the line running off the screen.
    return updates.slice(0, 6);
}

async function loadImportantUpdates() {
    const host = document.getElementById('dashboard-important-updates');
    if (!host) return;
    const ticker = host.closest('.fm-dashboard-ticker');

    try {
        const [medical, lineupTemplate, transferOverview, notifications, windowState,
               friendlyWeek, clock, trainingReports] = await Promise.all([
            authFetch(`/teams/${currentUserTeamId}/medical`).then(response => response.ok ? response.json() : null).catch(() => null),
            authFetch(`/teams/${currentUserTeamId}/lineup-template`).then(response => response.ok ? response.json() : null).catch(() => null),
            authFetch(`/transfers/team/${currentUserTeamId}`).then(response => response.ok ? response.json() : null).catch(() => null),
            // The notification page rather than the cheap count, because the ticker needs the summary
            // text and the badge needs the count, and one response cannot disagree with itself.
            authFetch('/notifications?size=6').then(response => response.ok ? response.json() : null).catch(() => null),
            authFetch('/transfers/window').then(response => response.ok ? response.json() : null).catch(() => null),
            // The two time-boxed items the owner ranked first, because both expire silently.
            authFetch(`/api/season/friendlies/${currentUserTeamId}/week`).then(response => response.ok ? response.json() : null).catch(() => null),
            authFetch('/api/game-clock').then(response => response.ok ? response.json() : null).catch(() => null),
            authFetch(`/training/weekly/team/${currentUserTeamId}/reports`).then(response => response.ok ? response.json() : null).catch(() => null)
        ]);

        const updates = buildImportantUpdates(medical, lineupTemplate, transferOverview,
            notifications, windowState, friendlyWeek, clock, trainingReports);
        ticker?.classList.toggle('is-community-alert', readUnreadCount(notifications) > 0);
        if (!updates.length) {
            host.innerHTML = buildImportantTickerMarkup('No urgent club updates right now.');
            return;
        }

        host.innerHTML = buildImportantTickerMarkup(buildImportantTickerLine(updates));
    } catch (err) {
        console.error('Error loading important updates:', err);
        host.innerHTML = buildImportantTickerMarkup('Important updates are temporarily unavailable.');
    }
}

function buildHeadToHeadText(h2h) {
    if (!h2h) return escapeHtml('No head-to-head data yet.');
    const summary = escapeHtml(h2h.summary || 'No head-to-head data yet.');
    const lastMeeting = h2h.lastMeetingSummary ? `<br>${escapeHtml(h2h.lastMeetingSummary)}` : '';
    return `${summary}${lastMeeting}`;
}

function extractTeamId(team) {
    if (team === null || team === undefined) return null;
    if (typeof team === 'object') {
        return team.id ?? team.teamId ?? team._id ?? null;
    }
    return team;
}

function extractTeamName(team) {
    if (team === null || team === undefined) return null;
    if (typeof team === 'object') {
        return team.name ?? team.teamName ?? null;
    }
    return typeof team === 'string' ? team : null;
}

function isCurrentUserTeam(team) {
    const teamId = extractTeamId(team);
    if (teamId !== null && teamId !== undefined && currentUserTeamId !== null && currentUserTeamId !== undefined) {
        if (Number(teamId) === Number(currentUserTeamId)) {
            return true;
        }
    }

    const teamName = extractTeamName(team);
    if (teamName && currentUserTeamName) {
        return teamName.trim().toLowerCase() === currentUserTeamName.trim().toLowerCase();
    }

    return false;
}

window.addEventListener('load', async () => {
    const token = sessionStorage.getItem('token');
    if (!token) {
        console.warn('No token on load - redirecting');
        window.location.href = '/login.html';
        return;
    }

    try {
        const res = await authFetch('/auth/me');
        const user = await res.json();

        currentUserTeamId = user.footballTeamId || user.teamId;
        currentUserTeamName = user.footballTeamName || user.teamName;
        currentUserTeamLogoUrl = user.footballTeamLogoUrl || null;
        currentUserCompetitionId = user.competitionId ?? null;
        currentUserCompetitionName = user.competitionName ?? null;
        currentSeasonYear = user.seasonYear ?? null;
        currentUserCountryName = user.countryName ?? null;
        currentUserCountryIsoCode = user.countryIsoCode ?? null;
        currentUserRole = user.role ?? null;
        setSessionRole(currentUserRole);
        applyAdminVisibility(document);
        updateCountryMenuLabels();
        // Same payload, same moment as the top-bar country label above: whoever is signed in, their
        // club and their league. Guarded because pages.js is a module and a failure there must not
        // take the dashboard down with it.
        if (typeof window.paintAccountMenu === 'function') {
            window.paintAccountMenu(user, currentUserCompetitionName || '');
        }
        // Started here rather than at module load, and after /auth/me has answered: a poll running
        // against an anonymous session gets a 401 every 30 seconds and, on the login page, that is a
        // redirect loop that looks like the app is broken.
        wireNotificationBell();
        startNotificationPolling();

        loadDashboard();
    } catch (err) {
        console.error('Error loading /auth/me:', err);
        if (!handleAuthFailure(err, 'Session expired while loading dashboard.')) {
            const mainContent = document.getElementById('main-content');
            if (mainContent) {
                mainContent.innerHTML = `<div class="manager-card" style="padding:32px; text-align:center;"><h2>Dashboard unavailable</h2><p>${escapeHtml(err?.message || 'Could not load your session data.')}</p></div>`;
            }
        }
    }
});

function loadDashboard() {
    if (!currentUserTeamId) {
        console.warn('Team ID not loaded yet - waiting for /auth/me');
        return;
    }

    const teamName = currentUserTeamName || 'Your Team';
    const teamImagePath = getCurrentTeamImagePath();

    const mainContent = document.getElementById('main-content');
    mainContent.innerHTML = `
    <div class="fm-dashboard-view">
        <section class="fm-dashboard-ticker" aria-label="Important updates">
            <div class="fm-dashboard-ticker-label">Important updates</div>
            <div id="dashboard-important-updates" class="fm-dashboard-ticker-host">
                ${buildImportantTickerMarkup('Loading important updates — checking lineup, medical status, transfers, and community messages.')}
            </div>
        </section>

        <div class="team-card fm-panel fm-dashboard-shell">
            <div class="team-header">
                <img src="${teamImagePath}" class="team-logo" onerror="this.src='/images/default-team.png'">
                <div class="team-name-wrapper">
                    <div class="fm-eyebrow">Club overview</div>
                    <h1>${teamName}</h1>
                    <p class="team-subtitle">${buildDashboardSubtitle()}</p>
                </div>
            </div>

            <div class="stats-grid clickable" onclick="loadLeagueTable()">
                <div class="stat-item">
                    <div class="stat-value">—</div>
                    <div class="stat-label">Position</div>
                </div>
                <div class="stat-item">
                    <div class="stat-value">—</div>
                    <div class="stat-label">Points</div>
                </div>
                <div class="stat-item">
                    <div class="stat-value">—</div>
                    <div class="stat-label">W-D-L</div>
                </div>
                <div class="stat-item">
                    <div class="stat-value">—</div>
                    <div class="stat-label">Goal Diff</div>
                </div>
            </div>

            <div class="next-match" id="next-match-card">
                <h3>Next Match</h3>
                <div class="match-info">
                    <div class="loading">Loading next match...</div>
                </div>
                <div class="match-date">Preparing your live club schedule…</div>
            </div>

            <div class="recent-matches-section">
                <h3>Recent Matches</h3>
                <div id="recent-matches-list" class="match-list">
                    <div class="loading">Loading recent matches...</div>
                </div>
            </div>

            <div class="recent-matches-section fm-milestone-board">
                <h3>Club Milestones</h3>
                <div id="dashboard-milestones" class="fm-milestone-grid">
                    <div class="fm-milestone-card"><div class="fm-milestone-kicker">Season board</div><div class="fm-milestone-value">Loading...</div><div class="fm-milestone-meta">Collecting current season milestones for your club.</div></div>
                </div>
            </div>


	            ${buildSeasonFlowPanel()}
        </div>
    </div>`;

    loadRecentMatches();
    loadHomeTeamStats();
    loadDashboardMilestones();
    loadNextMatch();
	loadImportantUpdates();
}

async function loadNextMatch() {
    try {
        const response = await authFetch(`/teams/${currentUserTeamId}/schedule`);
        if (!response.ok) throw new Error(`Failed to load schedule: ${response.status}`);

        const schedule = await response.json();
        // Ordered by the season calendar, not the wall clock. The two agree most of the time, which
        // is why this looked right until it did not: a cup tie and a league round can share a date,
        // and the wall clock alone has no way to say which of them is the next matchday. Season, then
        // week, then day is the order the fixtures were generated in, so it is the order a manager
        // means by "next". The timestamp is the tiebreaker, and also the only key when the calendar
        // columns are missing on an old row.
        const calendarOrder = (a, b) => {
            for (const key of ['seasonYear', 'week', 'day']) {
                const left = Number(a?.[key]);
                const right = Number(b?.[key]);
                const leftRank = Number.isFinite(left) ? left : Number.MAX_SAFE_INTEGER;
                const rightRank = Number.isFinite(right) ? right : Number.MAX_SAFE_INTEGER;
                if (leftRank !== rightRank) return leftRank - rightRank;
            }
            const leftTime = parseDashboardDate(a.matchDate)?.getTime() ?? Number.MAX_SAFE_INTEGER;
            const rightTime = parseDashboardDate(b.matchDate)?.getTime() ?? Number.MAX_SAFE_INTEGER;
            return leftTime - rightTime;
        };
        const nextMatch = (Array.isArray(schedule) ? schedule : [])
            .filter(match => !match.played)
            .sort(calendarOrder)[0];

        if (!nextMatch) {
            renderNextMatchEmpty('Schedule updating', 'Your next fixture will appear here as soon as the current season calendar is ready.');
            return;
        }

        const teamImagePath = getCurrentTeamImagePath();
        const clickableClass = nextMatch.fixtureId ? 'clickable' : '';
        const venueLabel = nextMatch.stadium || 'Venue TBD';
        // The type is here because a manager reads a fixture card to answer "what is this?", and
        // "Premier League" alone does not say whether missing it costs three points.
        const detailBits = [
            nextMatch.competitionName || 'Competition',
            nextMatch.competitionType || null,
            nextMatch.round ? `Round ${nextMatch.round}` : null,
            nextMatch.isHome ? 'Home' : 'Away'
        ].filter(Boolean).join(' · ');
        const homeOvr = Number(nextMatch.homeTeamStrength);
        const awayOvr = Number(nextMatch.awayTeamStrength);
        const ovrLine = Number.isFinite(homeOvr) || Number.isFinite(awayOvr)
            ? `OVR ${Number.isFinite(homeOvr) ? Math.round(homeOvr) : '—'} · ${Number.isFinite(awayOvr) ? Math.round(awayOvr) : '—'}`
            : '';

        const host = renderNextMatchCard(`
            <div class="match-info ${clickableClass}" data-fixture-id="${escapeHtml(nextMatch.fixtureId ?? '')}">
                <div class="team-away-home">
                    <img src="${escapeHtml(nextMatch.homeTeamLogoUrl) || (nextMatch.isHome ? teamImagePath : '/images/default-team.png')}" class="match-team-logo small" onerror="this.src='/images/default-team.png'">
                    <span>${escapeHtml(nextMatch.homeTeam || 'Home')}</span>
                </div>
                <span class="vs">VS</span>
                <div class="team-away-home" id="nextMatchHome">
                    <img src="${escapeHtml(nextMatch.awayTeamLogoUrl) || (nextMatch.isHome ? '/images/default-team.png' : teamImagePath)}" class="match-team-logo small" onerror="this.src='/images/default-team.png'">
                    <span>${escapeHtml(nextMatch.awayTeam || 'Away')}</span>
                </div>
            </div>
            <div class="match-date">
                ${escapeHtml(formatDashboardDate(nextMatch.matchDate))}<br>
                ${nextMatch.seasonDayLabel ? `${escapeHtml(nextMatch.seasonDayLabel)}<br>` : ''}
                ${escapeHtml(venueLabel)}<br>
                ${escapeHtml(detailBits)}<br>
                ${ovrLine ? `${escapeHtml(ovrLine)}<br>` : ''}
                ${buildHeadToHeadText(nextMatch.h2h || {})}
            </div>`);

        const clickable = host?.querySelector('[data-fixture-id]');
        if (clickable && nextMatch.fixtureId) {
            clickable.addEventListener('click', () => {
                // The match view on its Preview tab, not the fixture sheet (owner, 2026-10-01).
                //
                // It went to the fixture view, which is a different page showing a fixture card rather
                // than the pre-match screen - so "my next match" and "the match I am about to play" were
                // two different surfaces. The Preview tab is now loadable from a fixture id, which it was
                // not before, because a Match row is born when a match is *played* and this one has not
                // been.
                if (typeof window.loadMatch === 'function') {
                    // `fixture: true` - this is a fixture id and the two id spaces overlap.
                    window.loadMatch(Number(nextMatch.fixtureId), 'dashboard',
                        { initialTab: 'preview', fixture: true });
                } else if (typeof window.loadFixture === 'function') {
                    window.loadFixture(Number(nextMatch.fixtureId));
                }
            });
        }
    } catch (err) {
        console.error('Error loading next match:', err);
        renderNextMatchEmpty('Schedule unavailable', 'We could not refresh your club schedule right now.');
    }
}

function formatMilestoneAttendance(value) {
    const numeric = Number(value || 0);
    return numeric > 0 ? numeric.toLocaleString() : '—';
}

function milestoneCard(title, value, meta, extraClass = '') {
    return `
        <article class="fm-milestone-card ${extraClass}">
            <div class="fm-milestone-kicker">${title}</div>
            <div class="fm-milestone-value">${value || '—'}</div>
            <div class="fm-milestone-meta">${meta || 'No milestone logged yet.'}</div>
        </article>`;
}

async function loadDashboardMilestones() {
    const host = document.getElementById('dashboard-milestones');
    if (!host) return;

    try {
        const response = await authFetch(`/teams/${currentUserTeamId}/milestones`);
        if (!response.ok) throw new Error(`Failed to load club milestones: ${response.status}`);

        const data = await response.json();
        const attendance = data?.attendance || {};
        host.innerHTML = [
            milestoneCard(
                'Top scorer',
                data?.topScorer?.playerName || '—',
                data?.topScorer?.playerName ? `${data.topScorer.teamName || 'No team'} · ${Number(data.topScorer.value || 0)} goals` : 'No goals filed yet.'
            ),
            milestoneCard(
                'Top assist',
                data?.topAssist?.playerName || '—',
                data?.topAssist?.playerName ? `${data.topAssist.teamName || 'No team'} · ${Number(data.topAssist.value || 0)} assists` : 'No assists filed yet.'
            ),
            milestoneCard(
                'Biggest win',
                data?.biggestWin?.summary || '—',
                data?.biggestWin?.context || 'Waiting for a standout result.'
            ),
            milestoneCard(
                'Heaviest loss',
                data?.biggestLoss?.summary || '—',
                data?.biggestLoss?.context || 'No heavy defeat registered yet.'
            ),
            milestoneCard(
                'Attendance',
                formatMilestoneAttendance(attendance.averageAttendance),
                attendance.averageAttendance
                    ? `High ${formatMilestoneAttendance(attendance.highestAttendance)} (${attendance.highestMatchLabel || '—'}) · Low ${formatMilestoneAttendance(attendance.lowestAttendance)} (${attendance.lowestMatchLabel || '—'}) · ${attendance.insight || ''}`
                    : (attendance.insight || 'Crowd data will appear once played fixtures start filing gates.'),
                'attendance'
            )
        ].join('');
    } catch (err) {
        console.error('Error loading club milestones:', err);
        host.innerHTML = milestoneCard('Club Milestones', 'Unavailable', 'Could not load the current season milestones for your club.');
    }
}

async function resetDatabase() {
    const confirmReset = confirm('This will delete the entire database. Continue?');
    if (!confirmReset) return;

    await startAdminDatabaseJob('/admin/reset-db', 'Database reset and rebuild in progress...');
}

async function seedOtherNations() {
    await startAdminDatabaseJob('/admin/seed-other-nations', 'Seeding the other nations in progress...');
}

/**
 * Gives every club a default tactic (owner ruling, 2026-10-10).
 *
 * A job like the other world-building buttons rather than a repair: it writes a row per club, so it is one
 * of the longer writes in this panel and must not block the request that asked for it.
 */
async function seedDefaultTactics() {
    await startAdminDatabaseJob('/admin/seed-tactics', 'Giving every club a default tactic in progress...');
}

async function initializeDatabase() {
    const confirmInit = confirm('Initialize database now? This may take a few seconds.');
    if (!confirmInit) return;

    await startAdminDatabaseJob('/admin/initialize-db', 'Database initialization in progress...');
}

function createDatabaseLoadingPopup(title) {
    const loadingPopup = document.createElement('div');
    loadingPopup.id = 'loading-popup';
    loadingPopup.style.position = 'fixed';
    loadingPopup.style.top = '0';
    loadingPopup.style.left = '0';
    loadingPopup.style.width = '100%';
    loadingPopup.style.height = '100%';
    loadingPopup.style.background = 'rgba(0,0,0,0.6)';
    loadingPopup.style.display = 'flex';
    loadingPopup.style.alignItems = 'center';
    loadingPopup.style.justifyContent = 'center';
    loadingPopup.style.zIndex = '9999';

    loadingPopup.innerHTML = `
        <div style="
            background: linear-gradient(180deg, rgba(17, 23, 37, 0.98), rgba(11, 16, 26, 0.96));
            border: 1px solid rgba(143, 211, 255, 0.16);
            color: #eef4ff;
            padding: 30px 50px;
            border-radius: 18px;
            box-shadow: 0 22px 48px rgba(0,0,0,0.34);
            text-align: center;
            font-family: Arial, sans-serif;
            min-width: min(92vw, 440px);
        ">
            <div style="margin: 0 0 8px 0; color: #8fd3ff; font-size: 0.8rem; font-weight: 800; letter-spacing: 0.08em; text-transform: uppercase;">Database</div>
            <h2 id="loading-popup-title" style="margin: 0 0 15px 0; color: #eef4ff;">${escapeHtml(title)}</h2>
            <div id="loading-popup-message" style="font-size: 1.05em; color: #99a6bb;">Please wait and keep this page open.</div>
            <div id="loading-popup-progress" style="margin-top: 14px; color: #8fd3ff; font-size: 0.95rem;">Starting...</div>
            <div style="margin-top: 20px; font-size: 2em; color: #8fd3ff;">...</div>
        </div>
    `;
    return loadingPopup;
}

function updateDatabaseLoadingPopup(loadingPopup, snapshot) {
    if (!loadingPopup) return;
    const messageNode = loadingPopup.querySelector('#loading-popup-message');
    const progressNode = loadingPopup.querySelector('#loading-popup-progress');
    if (messageNode) {
        messageNode.textContent = snapshot?.message || 'Please wait and keep this page open.';
    }
    if (progressNode) {
        const completed = Number(snapshot?.completedSteps || 0);
        const total = Number(snapshot?.totalSteps || 0);
        progressNode.textContent = total > 0
            ? `Step ${Math.min(completed, total)}/${total}`
            : 'Working...';
    }
}

async function startAdminDatabaseJob(endpoint, title) {
    const loadingPopup = createDatabaseLoadingPopup(title);
    document.body.appendChild(loadingPopup);
    try {
        const response = await authFetch(endpoint, { method: 'POST' });
        const startPayload = await response.json();
        updateDatabaseLoadingPopup(loadingPopup, startPayload);
        const finalPayload = await pollAdminDatabaseJob(loadingPopup);
        if (document.body.contains(loadingPopup)) document.body.removeChild(loadingPopup);
        alert(`${finalPayload.message || 'Database rebuild completed.'}\n\nThe page will reload now.`);
        window.location.reload();
    } catch (err) {
        if (document.body.contains(loadingPopup)) document.body.removeChild(loadingPopup);
        console.error('DB init error:', err);
        alert(`Database operation failed.\n\nError: ${err.message}`);
    }
}

async function pollAdminDatabaseJob(loadingPopup) {
    for (let attempt = 0; attempt < 240; attempt += 1) {
        await new Promise(resolve => setTimeout(resolve, 1000));
        const response = await authFetch('/admin/database-job/status');
        if (!response.ok) {
            // A long rebuild outlives the session that started it, and the admin can press Reset DB
            // again. Without this, one 500 in the middle of the poll threw out of the loop and left the
            // loading popup on screen for ever with no way to tell whether the job was still running.
            updateDatabaseLoadingPopup(loadingPopup, { status: 'error',
                message: `Lost contact with the server (${response.status}). The job may still be running.` });
            throw new Error(`Could not read the job status (${response.status}).`);
        }
        const snapshot = await response.json();
        updateDatabaseLoadingPopup(loadingPopup, snapshot);

        if (snapshot.status === 'completed') {
            return snapshot;
        }
        if (snapshot.status === 'failed') {
            throw new Error(snapshot.message || 'Database rebuild failed.');
        }
    }
    throw new Error('Database rebuild timed out.');
}

async function loadRecentMatches() {
    try {
        const response = await authFetch(`/teams/${currentUserTeamId}/matches`);
        if (!response.ok) throw new Error('Failed to load matches');

        const matches = await response.json();
        const recent = matches
            .sort((a, b) => new Date(b.matchDate) - new Date(a.matchDate))
            .slice(0, 3);

        const list = document.getElementById('recent-matches-list');
        if (!list) return;

        if (recent.length === 0) {
            list.innerHTML = `
                <div class="empty-badge-wrap">
                    <span class="empty-badge">No played matches yet</span>
                </div>
                <p style="text-align:center; color:#aaa;">Play a match to populate this section.</p>`;
            return;
        }

        let html = '';
        recent.forEach(match => {
            const isHiddenResult = Boolean(match.resultHidden);
            const isHomeTeam = isCurrentUserTeam(match.homeTeam);
            const isAwayTeam = isCurrentUserTeam(match.awayTeam);
            const homeTeamLabel = escapeHtml(match.homeTeam?.name || match.homeTeam || 'Home');
            const awayTeamLabel = escapeHtml(match.awayTeam?.name || match.awayTeam || 'Away');

            let resultBadge = '';
            let badgeText = '';

            if (!isHiddenResult && (isHomeTeam || isAwayTeam)) {
                const myTeamGoals = isHomeTeam ? match.homeGoals : match.awayGoals;
                const opponentGoals = isHomeTeam ? match.awayGoals : match.homeGoals;

                if (myTeamGoals > opponentGoals) {
                    resultBadge = 'win';
                } else if (myTeamGoals === opponentGoals) {
                    resultBadge = 'draw';
                } else {
                    resultBadge = 'loss';
                }

                badgeText = resultBadge === 'win' ? 'W' : resultBadge === 'draw' ? 'D' : 'L';
            }

            if (isHiddenResult) {
                html += `
            <div class="match-row recent-match is-hidden-result" data-match-id="${match.id}">
                <div class="match-date-small">${escapeHtml(match.seasonDayLabel || match.matchDate || 'N/A')}</div>
                <div class="match-teams">
                    <span class="team-home">${homeTeamLabel}</span>
                    <span class="score fm-hidden-score">Result hidden</span>
                    <span class="team-away">${awayTeamLabel}</span>
                </div>
                ${TifoReveal.hiddenResultActions(match.id, match.replayId)}
            </div>`;
                return;
            }

            html += `
            <div class="match-row recent-match is-clickable" data-match-id="${match.id}">
                <div class="match-date-small">${escapeHtml(match.seasonDayLabel || match.matchDate || 'N/A')}</div>
                <div class="match-teams">
                    <span class="team-home">${homeTeamLabel}</span>
                    <span class="match-score-stack">
                        <span class="score">${match.homeGoals ?? '-'} : ${match.awayGoals ?? '-'}</span>
                        ${badgeText ? `<span class="result-badge ${resultBadge}">${badgeText}</span>` : '<span class="result-badge result-badge--placeholder" aria-hidden="true">&nbsp;</span>'}
                    </span>
                    <span class="team-away">${awayTeamLabel}</span>
                </div>
            </div>`;
        });

        list.innerHTML = html;
        list.querySelectorAll('.recent-match.is-clickable').forEach(row => {
            row.addEventListener('click', () => {
                const matchId = Number(row.dataset.matchId);
                if (matchId && typeof window.loadMatch === 'function') {
                    window.loadMatch(matchId, 'match');
                }
            });
        });
        // One implementation, shared with the club schedule and the league results. Three copies of
        // "reveal then navigate" is three places for them to disagree, and they already did: the
        // dashboard revealed through its own helper and opened the report tab, the other two surfaces
        // had no buttons at all and leaked the score instead.
        TifoReveal.bindHiddenResultActions(list, window.loadMatch);
    } catch (err) {
        console.error('Error loading recent matches:', err);
        document.getElementById('recent-matches-list').innerHTML =
            '<p style="text-align:center; color:#f44336;">Failed to load recent matches.</p>';
    }
}

async function revealMatchResult(matchId) {
    return TifoReveal.revealMatch(matchId);
}

async function loadHomeTeamStats() {
    // Resolve the nodes before the fetch, not after it. A navigation can replace the dashboard
    // while the league table is in flight, and re-querying the document afterwards returned null
    // and threw on the first write. A dashboard that is no longer on screen has nothing to fill
    // in, so a missing node means there is simply no work left to do.
    const heading = document.querySelector('.team-name-wrapper h1');
    const subtitle = document.querySelector('.team-subtitle');
    const statValues = document.querySelectorAll('.stat-value');
    if (!heading || !subtitle || statValues.length < 4) return;

    try {
        const leagueId = getCurrentLeagueId();
        // No league means no table, and this used to ask for one anyway: the id went into the URL as
        // the string "null" and the server replied 500 "For input string: null". So a manager whose
        // club is not in a competition - which is every manager on a freshly rebuilt world, and
        // exactly what the owner hit - saw a stack trace instead of being told they have no league.
        //
        // Asked for nothing, and says why.
        if (leagueId === null) {
            setLeagueStatsUnavailable();
            return;
        }
        const seasonParam = currentSeasonYear ? `?seasonYear=${currentSeasonYear}` : '';
        const response = await authFetch(`/countries/leagues/${leagueId}/table${seasonParam}`);
        if (!response.ok) throw new Error('Failed to load league table');

        const table = await response.json();

        const currentName = currentUserTeamName || heading.textContent?.trim() || 'Unknown';
        const entry = table.find(t => t.name === currentName);
        if (!entry) {
            console.warn('Team not found in league table:', currentName);
            return;
        }

        heading.textContent = entry.name;
        subtitle.innerHTML = buildDashboardSubtitle();

        statValues[0].textContent = entry.position || '?';
        statValues[1].textContent = entry.points || '0';
        statValues[2].textContent = `${entry.wins || 0}-${entry.draws || 0}-${entry.losses || 0}`;
        statValues[3].textContent = entry.goalDifference || '0';
    } catch (err) {
        console.error('Error loading team stats:', err);
    }
}

window.loadDashboard = loadDashboard;
window.resetDatabase = resetDatabase;
window.initializeDatabase = initializeDatabase;
window.seedOtherNations = seedOtherNations;
window.seedDefaultTactics = seedDefaultTactics;
window.loadRecentMatches = loadRecentMatches;
window.loadHomeTeamStats = loadHomeTeamStats;
