// dashboard.js
import { escapeHtml } from './ui/escape.js';
import { authFetch, handleAuthFailure, setSessionRole, isAdminSession, applyAdminVisibility, canSeeAdvancedCommands } from './auth.js';
import { startNotificationPolling, wireNotificationBell, readUnreadCount } from './notifications.js';
import { widgetRailHtml, nextMatchesHtml, updateWidget, bindWidgets, loadWidgetData, setStadiumFallback } from './dashboard-widgets.js';
// The recent matches are the club's own match card, built by the same builder the club's Matches page
// uses, so the two cannot drift apart (owner, 2026-10-21: "prosto prepisi da izgleda kao u Club -
// Matches").
import { buildScheduleFixtureCardHtml, bindScheduleInteractions } from './pages-renderers.js';

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
/**
 * No league yet: the position tile says so, and says it plainly.
 *
 * <p>The four stat boxes and the summary line this used to fill are gone with the club overview;
 * the rail's tile is where the answer lives now.
 */
function setLeagueStatsUnavailable() {
    updateWidget('position', { value: '—', meta: 'No league yet' });
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
/**
 * The game's own calendar, as one line: **Season · Week · Day · Hour**.
 *
 * <p>There is no date in this world - a season is twelve weeks - so a tile that printed "Oct 23"
 * was showing the manager a date that does not exist in his game. The server sends the label; this
 * is the fallback for the rows that arrive without one.
 */
function seasonDayLabelFor(row) {
    if (row?.seasonDayLabel) return row.seasonDayLabel;
    const parts = [];
    if (row?.seasonYear != null) parts.push(`Season ${row.seasonYear}`);
    if (row?.week != null) parts.push(`Week ${row.week}`);
    if (row?.day != null) parts.push(`Day ${row.day}`);
    const when = parseDashboardDate(row?.matchDate);
    if (when) {
        parts.push(`${String(when.getHours()).padStart(2, '0')}:${String(when.getMinutes()).padStart(2, '0')}`);
    }
    return parts.join(' · ');
}

/*
 * The next match, as a widget.
 *
 * <p>The dashboard's big "Next Match" card is gone (owner, 2026-10-19: the overview was in the way
 * and the five tiles say the same things in a glance), so the fixture is rendered into the rail's
 * next-match tile instead. The tile is the card now - same facts, less space.
 *
 * <p>The kick-off is printed as the season says it: **Season · Week · Day · Hour**. There is no
 * calendar date anywhere in this game, and a tile that printed "Oct 23" was telling the manager
 * something that does not exist in his world.
 */
function renderNextMatchEmpty(message, meta) {
    updateWidget('nextMatch', {
        value: 'No fixture',
        meta: message,
        note: meta || 'The next fixture will appear here once the schedule is ready.'
    });
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
window.isAdminUser = isAdminUser;

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

function canSeeAdvancedDashboardCommands() {
    return typeof window.canSeeAdvancedCommands === 'function' ? window.canSeeAdvancedCommands() : isAdminSession();
}
window.canSeeAdvancedDashboardCommands = canSeeAdvancedDashboardCommands;

function buildSeasonFlowPanel() {
    const flash = readDashboardFlowFlash();
    const statusTone = flash?.tone || 'info';
    // The panel is the calendar now, and nothing else (owner, 2026-10-21: *"Watch your match i Simulate
    // all results iz Match Week Controls sad nemaju smisla jer pomeramo dan pa on pokrene odgovarajuci
    // job pa ih skloni i sa deska i sa moba"*). The two buttons were the only thing that ever set the
    // week-consumed flag, and the only thing the watch status was read for, so both are gone: moving the
    // clock runs the jobs that are due, and a button that prepares a week by hand is a second way to do
    // what Advance Day already does.
    const statusMessage = flash?.message
        || 'Advance the calendar. Each move runs the jobs that fall due, so the match week plays out '
         + 'on its own.';

    // **Match Week Controls are visible to admin and TEST roles.** Non-admin managers do not get
    // the calendar-advance controls. TEST role can see them for testing purposes.
    if (!canSeeAdvancedDashboardCommands()) {
        return '';
    }

    return `
        <div class="recent-matches-section fm-season-flow-panel">
            <div class="fm-season-flow-head">
                <div>
                    <h3>Match Week Controls</h3>
                    <div class="fm-season-flow-copy">Advance the calendar one hour, one day or one week.
                        Every move runs the jobs that are due, so the fixtures play and the tables move
                        without a second button.</div>
                </div>
                <span class="fm-season-flow-badge">Admin</span>
            </div>
            <div class="dashboard-actions fm-season-flow-buttons">
                <button id="advance-week-btn" data-label="📅 Advance Week" class="fm-action-btn fm-dashboard-cta" onclick="advanceWeekTest()">📅 Advance Week</button>
                <button id="advance-day-btn" data-label="📆 Advance Day" class="fm-action-btn secondary" onclick="advanceDayTest()">📆 Advance Day</button>
                <button id="advance-hour-btn" data-label="⏩ Advance Hour" class="fm-action-btn secondary" onclick="advanceHourTest()">⏩ Advance Hour</button>
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
function buildTimeboxedUpdates(friendlyWeek) {
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

    /*
     * No "weekly training not run yet" here (owner, 2026-10-20: "pomeri iz tickera na dashboardu
     * training is not run yet recenicu jer je netacna svakako" - it is inaccurate anyway).
     *
     * <p>It was a warning about a week the training job had not filed a report for, dressed as
     * something the manager had to do. Training runs itself; a manager who cannot act on a message
     * should not be shown it in the one strip that carries the things they can.
     */
    return updates;
}

function buildImportantUpdates(medical, lineupTemplate, transferOverview, notifications, windowState,
                               friendlyWeek, clock, trainingReports) {
    const updates = [];

    // The time-boxed ones go in first: they are the only entries that stop being true.
    updates.push(...buildTimeboxedUpdates(friendlyWeek));
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

        // The rail's condition tile, from the medical overview this load already made (R-13).
        if (medical) {
            const condition = Number(medical.averageConditionPercent);
            const injured = Number(medical.injuredCount || 0);
            updateWidget('condition', {
                value: Number.isFinite(condition) ? `${Math.round(condition)}%` : '—',
                meta: injured > 0 ? `${injured} injured` : 'Squad fit',
                tone: Number.isFinite(condition) ? (condition < 60 ? 'neg' : condition < 80 ? 'warn' : 'pos') : null
            });
        }
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
        window.fmCountryName = currentUserCountryName;
        // The nav bar's User entry carries the manager's name instead of a generic 'User'.
        window.fmUserName = user.displayName || user.username || user.email || 'Manager';
        window.fmCountryIsoCode = currentUserCountryIsoCode;
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
        if (typeof window.renderDesktopBottomNav === 'function') window.renderDesktopBottomNav('dashboard');

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

        ${widgetRailHtml()}

        ${nextMatchesHtml()}

        <div class="fm-dashboard-main">
            <section class="fm-dashboard-section fm-dashboard-section--recent">
                <div class="fm-dashboard-section-head">
                    <h3 class="fm-dashboard-section-title">Recent Matches</h3>
                    <!-- The badge's final home (owner, 2026-10-19, final decision): the top right corner
                         of the recent matches, on the phone as well as on the desktop. -->
                    <div class="fm-dashboard-crest">
                        <img src="${teamImagePath}" class="team-logo" onerror="this.src='/images/default-team.png'">
                    </div>
                </div>
                <div id="recent-matches-list" class="fm-dashboard-cards fm-dashboard-cards--single">
                    <div class="loading">Loading recent matches...</div>
                </div>
            </section>

            <!--
                No Club Milestones here (owner, 2026-10-20: "izbaciti skroz Club milestones deo jer
                on pomera sve ostale"). Five cards of season history pushed everything else down the
                page, and the milestones belong on the club's own profile, where the manager goes to
                read them.
            -->
        </div>

        <!--
            The week controls and the admin strip, full width under both columns: the owner's
            decision (2026-10-19) - not under the widgets, not under the recent matches, but under
            all of it, where a full-width panel belongs.
        -->
        <div class="fm-dashboard-admin">
            ${buildSeasonFlowPanel()}
        </div>
    </div>`;

    wireNextMatchStrip(document.querySelector('[data-next-matches]'));
    // Bound on the whole dashboard view, not on the rail: the next-match tiles live in their own
    // container now, and a delegation that only listened to the rail left them dead (owner,
    // 2026-10-20: "next matches nije klikabilan ni na desktopu").
    bindWidgets(document.querySelector('.fm-dashboard-view'), {
        // The next-match tile opens the match itself, on the Preview tab - the same surface the
        // old next-match card opened (owner, 2026-10-01), because "my next match" and "the match I
        // am about to play" are the same question.
        openMatch: fixtureId => {
            if (typeof window.loadMatch === 'function') {
                window.loadMatch(Number(fixtureId), 'dashboard', { initialTab: 'preview', fixture: true });
            } else if (typeof window.loadFixture === 'function') {
                window.loadFixture(Number(fixtureId));
            }
        }
    });
    loadWidgetData({ teamId: currentUserTeamId, authFetch, seasonYear: currentSeasonYear });

    loadRecentMatches();
    loadHomeTeamStats();
    if (typeof window.renderDesktopBottomNav === 'function') try { window.renderDesktopBottomNav('dashboard'); } catch (e) {}
    loadNextMatch();
	loadImportantUpdates();
}

/**
 * Comes back for the world when the world moves.
 *
 * <p>The matchday jobs fire on the hour, and the publish job is what plays the day's matches — so a
 * match that was in "next matches" is in the results a moment later, and the dashboard has to say so
 * without the manager reloading the page (owner, 2026-10-21: "proveri takodje da kad publishing job
 * okine da refreshuje dashboard i da mec iz next predje u recents").
 *
 * <p>The clock's sync says when the hour turned over and when the world stopped syncing, and this is
 * what it is for. The re-read is skipped when the dashboard is not on the screen: the loaders write
 * into the page, and there is no page to write into.
 */
window.addEventListener('fm-world-moved', () => {
    if (!document.getElementById('recent-matches-list')) return;
    loadRecentMatches();
    loadHomeTeamStats();
    loadNextMatch();
});

/**
 * The next-fixture strip on a phone: three tiles, one per screen, and the swipe goes **round**.
 *
 * <p>Owner, 2026-10-20: "moze da se swajpuje za oba u krug 1/3, 2/3, 3/3, 1/3..." - a strip that
 * stops at the third tile is a strip with a wall at the end of it. So a swipe past the last tile
 * goes back to the first, and a swipe back before the first goes to the last.
 *
 * <p>It is a touch gesture rather than a scroll handler on purpose: a scroll handler cannot tell
 * "the user flicked past the end" from "the user is still dragging", and it would fight the
 * browser's own snapping.
 */
function wireNextMatchStrip(strip) {
    if (!strip) return;
    const tiles = [...strip.querySelectorAll('.fm-widget')].filter(tile => !tile.classList.contains('is-hidden'));
    if (tiles.length < 2) return;

    let startX = null;
    strip.addEventListener('touchstart', event => {
        startX = event.touches[0]?.clientX ?? null;
    }, { passive: true });

    strip.addEventListener('touchend', event => {
        if (startX === null) return;
        const endX = event.changedTouches[0]?.clientX ?? startX;
        const delta = endX - startX;
        startX = null;
        if (Math.abs(delta) < 40) return;

        const index = tiles.findIndex(tile => {
            const rect = tile.getBoundingClientRect();
            return rect.left <= strip.getBoundingClientRect().left + 4
                && rect.right > strip.getBoundingClientRect().left + 4;
        });
        if (index === -1) return;

        if (delta < 0) {
            // Forward: past the last tile, back to the first.
            const next = index + 1 >= tiles.length ? 0 : index + 1;
            tiles[next].scrollIntoView({ behavior: 'smooth', inline: 'start', block: 'nearest' });
        } else {
            // Back: before the first tile, to the last.
            const previous = index - 1 < 0 ? tiles.length - 1 : index - 1;
            tiles[previous].scrollIntoView({ behavior: 'smooth', inline: 'start', block: 'nearest' });
        }
    }, { passive: true });
}

async function loadNextMatch() {
    try {
        // **Every competition, not the league.** "Next Match" is not a league question: a Champions Cup
        // round can fall between two league rounds, and a manager told "no next match" while holding
        // five group fixtures is being told something false (owner, 2026-10-10). Measured on a club in
        // the Champions Cup: this route returned 0 without the flag and 5 with it.
        const response = await authFetch(`/teams/${currentUserTeamId}/schedule?allCompetitions=true`);

        const schedule = await response.json();
        // Ordered by the season calendar, not the wall clock. The two agree most of the time, which
        // is why this looked right until it did not: a cup tie and a league round can share a date,
        // and the wall clock alone has no way to say which of them is the next matchday. Season, then
        // week, then day is the order the fixtures were generated in, so it is the order a manager
        // means by "next". The timestamp is the tiebreaker, and also the only key when the calendar
        // columns are missing on an old row.
        const calendarOrder = (a, b) => {
            // The schedule endpoint carries `seasonYear`, `week` and `day`, not `seasonNumber`,
            // `weekNumber` and `dayNumber`. Reading the latter three gave NaN for every row, so the
            // calendar sort fell through to the timestamp and picked a later fixture over an earlier
            // one — which is why "Next Match" showed a day-7 tie while a day-3 tie was still unplayed.
            // Owner, 2026-10-17. Both spellings are accepted because the payload has changed shape once
            // already and a row from an older build must not become unsortable.
            const pick = (row, key) => {
                const v = row?.[key];
                const n = Number(v);
                return Number.isFinite(n) ? n : null;
            };
            for (const key of ['seasonYear', 'week', 'day']) {
                const left = pick(a, key);
                const right = pick(b, key);
                if (left === null && right === null) continue;
                const leftRank = left === null ? Number.MAX_SAFE_INTEGER : left;
                const rightRank = right === null ? Number.MAX_SAFE_INTEGER : right;
                if (leftRank !== rightRank) return leftRank - rightRank;
            }
            const leftTime = parseDashboardDate(a.matchDate)?.getTime() ?? Number.MAX_SAFE_INTEGER;
            const rightTime = parseDashboardDate(b.matchDate)?.getTime() ?? Number.MAX_SAFE_INTEGER;
            return leftTime - rightTime;
        };
        // The next three fixtures, not one (owner, 2026-10-20: "stavi da se ispisuju 3 naredna
        // meca"). One fixture is a question; three is a week.
        const nextMatches = (Array.isArray(schedule) ? schedule : [])
            .filter(match => !match.played)
            .sort(calendarOrder)
            .slice(0, 3);
        const nextMatch = nextMatches[0];

        if (!nextMatch) {
            renderNextMatchEmpty('Schedule updating', 'Your next fixture will appear here as soon as the current season calendar is ready.');
            updateWidget('nextMatch', { value: 'No fixture', meta: 'Season calendar is being built' });
            return;
        }

        // The rail's next-match tile, from the fixture this load already has (R-13). The tile says
        // the one thing a manager checks on the way in - who, where, and when - and it says it in
        // the game's own calendar: season, week, day, hour. Never a date.
        /*
         * The next three fixtures, as three tiles that are exactly alike (owner, 2026-10-20: "ostane
         * onakav kakav je bio widget i jos dva ista takva ispod").
         *
         * <p>Each tile carries: the opponent, the game's own calendar line, the **kind of match**
         * (league, cup, international - because that is what tells the manager whether missing it
         * costs three points or a whole competition) under the date and above the head-to-head.
         */
        nextMatches.forEach((match, index) => {
            const opponent = match.isHome ? match.awayTeam : match.homeTeam;
            const calendar = seasonDayLabelFor(match);
            const kind = [match.competitionName || null, match.competitionType || null]
                .filter(Boolean).join(' · ');
            const h2h = match.h2h
                ? (match.h2h.summary || match.h2h.lastMeetingSummary || '')
                : '';
            updateWidget(index === 0 ? 'nextMatch' : `nextMatch${index + 1}`, {
                value: opponent || 'Next fixture',
                meta: [
                    calendar,
                    match.isHome ? 'Home' : 'Away'
                ].filter(Boolean).join(' · '),
                type: kind,
                note: h2h ? `H2H: ${h2h}` : '',
                fixtureId: match.fixtureId || null
            });
        });

        // The club's own ground, as a fallback for the stadium tile: the venue of a home fixture
        // is the club's ground, and the stadium endpoint has no record for some clubs even though
        // they play in one (owner, 2026-10-19: "mora da ima").
        if (nextMatch.isHome && nextMatch.stadium) {
            setStadiumFallback(nextMatch.stadium);
        }
    } catch (err) {
        console.error('Error loading next match:', err);
        renderNextMatchEmpty('Schedule unavailable', 'We could not refresh your club schedule right now.');
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

async function repairStrandedMatchdays() {
    await startAdminDatabaseJob('/admin/repair-stranded', 'Preparing and publishing stranded matchdays in progress...');
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
    // Fifteen minutes, not four. The rebuild builds thirty-one divisions, fourteen thousand clubs and
    // half a million players, and it writes a tactic onto every club that has one; on a slow machine
    // that is minutes of real work, and a poll that gives up mid-way told the owner the rebuild had
    // failed when it was still running (owner, 2026-10-20: "Database operation failed. Error:
    // Database rebuild timed out.").
    for (let attempt = 0; attempt < 900; attempt += 1) {
        await new Promise(resolve => setTimeout(resolve, 1000));
        const response = await authFetch('/admin/database-job/status');
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
        // **The club's schedule, not the club's match rows** (owner, 2026-10-21: the strip should look
        // like the club's Matches page, and that page reads the schedule - which carries the ground and
        // the head-to-head line the match rows do not).
        //
        // **With every competition**, like the page it has to match. Without the flag the schedule is
        // the league's fixtures only, so a match in the cup or a friendly was not in the strip at all:
        // the manager's first game of the season was a Challenge Cup tie, and the strip said "No played
        // matches yet" while the club's Matches page said "1 Played" (owner, 2026-10-21: "ne pojavi se
        // match na dashboardu u recents ... a mozes uci i sam na Oracle i pogledati").
        const response = await authFetch(`/teams/${currentUserTeamId}/schedule?allCompetitions=true`);

        const matches = await response.json();
        // Order by the game calendar (season, week, day), not by wall-clock date.
        // The game has no calendar - only season/week/day. Wall-clock dates can be misleading
        // when a cup tie and league round share a day.
        const calendarOrder = (a, b) => {
            for (const key of ['seasonNumber', 'weekNumber', 'dayNumber']) {
                const left = Number(a?.[key]);
                const right = Number(b?.[key]);
                const leftRank = Number.isFinite(left) ? left : Number.MAX_SAFE_INTEGER;
                const rightRank = Number.isFinite(right) ? right : Number.MAX_SAFE_INTEGER;
                if (leftRank !== rightRank) return rightRank - leftRank; // descending = most recent first
            }
            const leftTime = parseDashboardDate(a.matchDate)?.getTime() ?? Number.MAX_SAFE_INTEGER;
            const rightTime = parseDashboardDate(b.matchDate)?.getTime() ?? Number.MAX_SAFE_INTEGER;
            return rightTime - leftTime;
        };
        const recent = (Array.isArray(matches) ? matches : [])
            .filter(match => match.played)
            .sort(calendarOrder)
            .slice(0, 3);

        const list = document.getElementById('recent-matches-list');
        if (!list) return;

        if (recent.length === 0) {
            // One line, no badge (owner, 2026-10-20: "izmedju ova dva neki neprecizan krug/elipsu!
            // to skloni obavezno kao i 'Play a match to populate this section', dovoljno je 'No played
            // matches yet'"). The empty badge was a pill around the words, and with the sentence under
            // it the section read as three things instead of one.
            list.innerHTML = `<p class="fm-empty-line">No played matches yet</p>`;
            return;
        }

        /*
         * **The strip is the club's own match card** (owner, 2026-10-21: "Recent matches na dashboardu:
         * ne izgleda dobro (slika1) uopste se ne vidi ko je igrao, prosto prepisi da izgleda kao u
         * Club - Matches (slika2)").
         *
         * <p>It was its own row: the date on the left, the score in the middle and the two club names
         * on the sides, **cut to "OFK..." and "ŽFK..."** - so the strip could not say who had played,
         * which is the one thing a list of finished matches is for.
         *
         * <p>Now it is the same card the club's Matches page builds, by the same builder, so the two
         * cannot drift apart: the date, the competition, the venue, both names whole, the score and
         * the head-to-head line.
         */
        const recentCards = recent.map(match => buildScheduleFixtureCardHtml(match, {
            matchCaller: 'match',
            pendingLabel: 'VS',
            allowFixtureClick: false,
            safe: escapeHtml
        })).join('');

        list.innerHTML = `<div class="fm-fixtures">${recentCards}</div>`;
        // One implementation, shared with the club schedule and the league results. Three copies of
        // "reveal then navigate" is three places for them to disagree, and they already did: the
        // dashboard revealed through its own helper and opened the report tab, the other two surfaces
        // had no buttons at all and leaked the score instead.
        bindScheduleInteractions(list, { loadMatch: window.loadMatch });
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

/**
 * The league table, for the rail's position tile.
 *
 * <p>The dashboard's own "Club overview" block - the title, the four stats and the summary line -
 * is gone (owner, 2026-10-19: it was in the way, and the tiles say the same things), so this
 * function now answers one question and answers it in one place: where the club is in its league.
 */
async function loadHomeTeamStats() {
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

        const table = await response.json();

        const currentName = currentUserTeamName || 'Unknown';
        const entry = table.find(t => t.name === currentName);
        if (!entry) {
            console.warn('Team not found in league table:', currentName);
            return;
        }

        // **A result the manager has not seen yet must not be given away here.** The league table
        // already reflects every played match, including the ones whose result is still hidden, so
        // the goals, the points and the W-D-L line would say what the match card refuses to say.
        // Read the schedule alongside the table and, when any of the club's own matches is still
        // hidden, keep the numbers back until the manager opens the match or asks for the result.
        // The Elo stays: it is the owner's ruling that it is not touched here.
        let hasHiddenResult = false;
        try {
            const scheduleResponse = await authFetch(`/teams/${currentUserTeamId}/schedule?allCompetitions=true`);
            if (scheduleResponse.ok) {
                const schedule = await scheduleResponse.json();
                hasHiddenResult = Array.isArray(schedule)
                    && schedule.some(m => m?.resultHidden === true);
            }
        } catch (err) {
            // The check is a courtesy, not a gate: if it fails, the table's numbers are shown.
        }

        const goalsFor = Number(entry.goalsScored ?? 0);
        const goalsAgainst = Number(entry.goalsConceded ?? 0);
        const goalDifference = Number(entry.goalDifference ?? (goalsFor - goalsAgainst));
        // The Elo, the same number the club page shows and the same one the ranking tables read -
        // one source, so the three can never disagree (owner, 2026-10-20: "odakle ga citas na club
        // profile da bude jedinstveno svuda i u ligi i u ranking tabelama").
        const elo = Number(entry.rating);
        const eloDelta = Number(entry.ratingDelta);
        const eloChip = Number.isFinite(elo) && elo > 0
            ? `Elo ${Math.round(elo)}${Number.isFinite(eloDelta) && eloDelta !== 0
                ? ` <span class="${eloDelta > 0 ? 'up' : 'down'}">${eloDelta > 0 ? '+' : ''}${Math.round(eloDelta)}</span>`
                : ''}`
            : '';
        updateWidget('position', {
            value: entry.position ? `${entry.position}` : '—',
            meta: hasHiddenResult
                ? 'Result pending'
                : `${entry.points || 0} points · ${entry.wins || 0}-${entry.draws || 0}-${entry.losses || 0}`,
            // The goals, because a position without them says nothing about the team: scored,
            // conceded and the difference between them (owner, 2026-10-20).
            note: hasHiddenResult
                ? 'Result pending'
                : `${goalsFor} scored · ${goalsAgainst} conceded · GD ${goalDifference > 0 ? '+' : ''}${goalDifference}`,
            chipHtml: eloChip,
            tone: Number(entry.position) <= 3 ? 'pos' : Number(entry.position) >= 16 ? 'neg' : null
        });
    } catch (err) {
        console.error('Error loading team stats:', err);
    }
}

window.loadDashboard = loadDashboard;
window.resetDatabase = resetDatabase;
window.initializeDatabase = initializeDatabase;
window.seedOtherNations = seedOtherNations;
window.seedDefaultTactics = seedDefaultTactics;
window.repairStrandedMatchdays = repairStrandedMatchdays;
window.loadRecentMatches = loadRecentMatches;
window.loadHomeTeamStats = loadHomeTeamStats;
