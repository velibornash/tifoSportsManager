// pages.js
import { escapeHtml } from './ui/escape.js';
import { authFetch, handleAuthFailure } from './auth.js';
import { renderPlayersView, renderMatchesView, renderTableView, renderFixturesView, renderLeagueMatchesView, renderLeagueScheduleView, buildSquadTableHtml, bindSquadRowClicks, buildClubActionsHtml, buildTrainingActionsHtml, buildLeagueActionsHtml, buildCommunityActionsHtml } from './pages-renderers.js';
import { createAcademyFeature } from './pages/features/academy.js';
import { createLoansFeature } from './pages/features/loans.js';
import { createTeamFeature } from './pages/features/team.js';
import { createMatchesFeature } from './pages/features/matches.js';
import { createClubManagementFeature } from './pages/features/club-management.js';
import { createForumView } from './pages/views/forum-view.js';
import { createMessagesView } from './pages/views/messages-view.js';
import {
    htmlEscape, formatBudget, formatGoalDiff, buildEmptyState, buildErrorState, formatPercent,
    parseMatchDate, getImageFilename, formatMilestoneAttendanceValue,
    buildMilestoneCardHtml, buildMilestoneBoardHtml, formatDateTimeLabel,
    formatFormBadge, formatRatingBadge, getRatingColor, formatCompactPlayerName,
    formatSeasonShortLabel, countryFlagEmojiFromIso, getCountryFlagImagePath,
    buildCountryFlagBadgeHtml, sortCountryLeagues, buildLeagueMetaLabel,
    normalizeTeamKey, normalizePlayerKey, normalizeLeagueId, isLeaguePage,
    getPlayerConditionPercent, fetchPlayerRatingSummary, delay
} from './pages/views/utils.js';
import { createMatchView } from './pages/views/match-view.js';
import { createPlayerView } from './pages/views/player-view.js';
import { createFormationsView } from './pages/views/formations-view.js';
import { createTacticEditorView } from './pages/views/tactic-editor-view.js';
import { createTrainingView } from './pages/views/training-view.js';
import { createMedicalView } from './pages/views/medical-view.js';
import { createLeagueView } from './pages/views/league-view.js';
import { createFixtureView } from './pages/views/fixture-view.js';
import { createSubstitutionPlanView } from './pages/views/substitution-plan-view.js';
import { createMatchTacticPlanView } from './pages/views/match-tactic-plan-view.js';
import { createMatchLineupView } from './pages/views/match-lineup-view.js';
import { createTacticOrderView } from './pages/views/tactic-order-view.js';
import { createCountryView } from './pages/views/country-view.js';
import { createClubCupView } from './pages/views/club-cup-view.js';
import { createNationalTournamentView, NATIONAL_COMPETITIONS } from './pages/views/national-tournament-view.js';
import { createStatsView } from './pages/views/stats-view.js';
import { createSearchView } from './pages/views/search-view.js';
import { createFriendlyBoard } from './pages/views/friendly-board.js';
import { createClubView } from './pages/views/club-view.js';
import { loadPublicUserProfile } from './pages/views/user-profile-view.js';
import { createStadiumView } from './pages/views/stadium-view.js';
import { createAdminView } from './pages/views/admin-view.js';
// `renderUserProfile` is deliberately NOT imported. It was the second renderer for the profile page,
// showing the name read-only while the page that can edit it was unreachable from the menu. "My
// profile" now uses `loadPublicUserProfile`, which is the one that has the editor. It is still exported
// from account-menu.js for callers outside this module, but nothing here uses it, and an unused import
// is a dead end that reads as a live path.
import {
    logout, paintAccountMenu, toggleUserMenu, closeUserMenu, initAccountMenu
} from './ui/account-menu.js';
    let currentUserTeamId = null;
    let currentUserTeamName = '';
	    let currentUsername = '';
	    let currentUserRole = '';
	    let currentUserTeamHumanControlled = null;
	let currentUserCompetitionId = null;
	let currentUserCompetitionName = '';
	let currentUserCompetitionTier = null;
	let currentSeasonYear = null;
	    let currentUserCountryName = '';
		let currentUserCountryIsoCode = '';
    let currentPageId = 'dashboard';
    let currentNavState = { type: 'dashboard' };
    const navHistoryStack = [];
    let navReplayMode = false;
    let navBusy = false;
    let currentLeagueSeasonYear = null;
	    let activeLeagueId = null;
	    let activeLeagueName = '';
	    let activeLeagueCountryIsoCode = '';
	    let activeLeagueBackTarget = 'dashboard';

	    /**
	     * The country the manager is *looking at*, as opposed to the country he plays in.
	     *
	     * <p>Clicking a country in the World page used to land on the manager's own country every time.
	     * The World page set the league context, and the country page then asked
	     * {@code getCurrentUserCountryIsoCode()} — the manager's own country — and ignored the context
	     * entirely. So Croatia, Japan and Brazil all showed Serbia. The two functions disagreed about what
	     * the click meant, and the one that was consulted was the wrong one.
	     *
	     * <p>It is separate state rather than the league context because the Country menu button has to
	     * mean "my country": if the world click reused the league context, opening Country after looking at
	     * Croatia would keep showing Croatia, and the manager would have no way back to their own side.
	     * So the World page sets this, and the Country button clears it.
	     */
	    let selectedCountryIsoCode = '';

	    /** Reads the country to show: an explicit choice first, then the manager's own. */
	    function resolveCountryIsoCode() {
	        return (selectedCountryIsoCode || currentUserCountryIsoCode || '').toUpperCase();
	    }

	    function setSelectedCountry(isoCode) {
	        selectedCountryIsoCode = isoCode ? String(isoCode).toUpperCase() : '';
	    }

	    /**
	     * The Country menu button: the manager's own country, always.
	     *
	     * <p>It clears the World page's selection first. Without that, looking at Croatia from the world
	     * list would follow you to the Country button, and there would be no way back to your own side
	     * except reloading the page.
	     */
	    function showMyCountry() {
	        setSelectedCountry('');
	        setActiveLeagueContext({ countryIsoCode: currentUserCountryIsoCode || '', backTarget: 'dashboard' });
	        loadPage('country');
	    }

	    function setActiveLeagueContext({ leagueId = null, leagueName = '', countryIsoCode = '', backTarget = 'dashboard', seasonYear } = {}) {
	        activeLeagueId = normalizeLeagueId(leagueId);
	        activeLeagueName = leagueName || '';
	        activeLeagueCountryIsoCode = countryIsoCode || '';
	        activeLeagueBackTarget = backTarget || 'dashboard';
	        if (seasonYear !== undefined) currentLeagueSeasonYear = seasonYear ?? null;
	    }

	    function syncUserLeagueContext() {
	        setActiveLeagueContext({
	            leagueId: currentUserCompetitionId,
	            leagueName: currentUserCompetitionName || 'League',
	            countryIsoCode: currentUserCountryIsoCode || '',
	            backTarget: 'dashboard',
	            seasonYear: currentSeasonYear ?? currentLeagueSeasonYear ?? null
	        });
	    }

	    function getActiveLeagueNavState() {
	        return {
	            leagueId: activeLeagueId,
	            leagueName: activeLeagueName,
	            leagueCountryIsoCode: activeLeagueCountryIsoCode,
	            leagueBackTarget: activeLeagueBackTarget,
	            seasonYear: currentLeagueSeasonYear ?? null
	        };
	    }

	    function restoreLeagueNavState(state) {
	        if (!state) return;
	        if (state.leagueId || state.leagueName || state.leagueCountryIsoCode || state.leagueBackTarget) {
	            setActiveLeagueContext({
	                leagueId: state.leagueId,
	                leagueName: state.leagueName,
	                countryIsoCode: state.leagueCountryIsoCode,
	                backTarget: state.leagueBackTarget,
	                seasonYear: state.seasonYear ?? null
	            });
	        }
	    }

function buildPageNavState(page, options = {}) {
        // A manager's id is part of which page this is. `publicUserProfile` without it renders
        // nothing, so a Back that dropped the id would land on an empty page rather than the
        // profile — and `sameNavState` compares by value, so two different managers' profiles would
        // otherwise look like the same entry and the second would not push its own history.
        const identity = page === 'publicUserProfile' && options.userId != null
            ? { userId: options.userId }
            : {};
        if (!isLeaguePage(page)) return { type: 'page', page, ...identity };
        return {
            type: 'page',
            page,
            preserveLeagueContext: true,
            ...identity,
            ...getActiveLeagueNavState()
        };
    }

    function sameNavState(a, b) {
        if (!a || !b) return false;
        if (a.type !== b.type) return false;
        return JSON.stringify(a) === JSON.stringify(b);
    }

    function pushNavState(nextState) {
        if (navReplayMode) return;
        if (sameNavState(currentNavState, nextState)) return;
        if (currentNavState) navHistoryStack.push(currentNavState);
        if (navHistoryStack.length > 50) navHistoryStack.shift();
        currentNavState = nextState;
    }
    async function renderNavState(state) {
        if (!state) return;
        if (state.type === 'dashboard') {
            currentPageId = 'dashboard';
            currentNavState = { type: 'dashboard' };
            if (typeof window.loadDashboard === 'function') window.loadDashboard();
            return;
        }
        if (state.type === 'page') {
	            if (isLeaguePage(state.page) && state.preserveLeagueContext) {
	                restoreLeagueNavState(state);
	                await loadPage(state.page, { pushHistory: false, preserveLeagueContext: true });
	                return;
	            }
	            // The manager's id travels with the history entry. Without it Back on a profile
	            // lands on an empty page, because the route has no default subject.
	            await loadPage(state.page, { pushHistory: false, userId: state.userId });
            return;
        }
        if (state.type === 'player') {
            await loadPlayer(state.playerId, state.callerPage, { pushHistory: false });
            return;
        }
        if (state.type === 'match') {
	            restoreLeagueNavState(state);
            await loadMatch(state.matchId, state.caller, { pushHistory: false });
            return;
        }
        if (state.type === 'fixture') {
	            restoreLeagueNavState(state);
	            await loadFixture(state.fixtureId, { pushHistory: false, backTarget: state.backTarget || 'fixtures' });
            return;
        }
        if (state.type === 'leagueTeam') {
	            restoreLeagueNavState(state);
            await loadLeagueTeam(state.teamId, state.teamName, { pushHistory: false, seasonYear: state.seasonYear ?? null });
            return;
        }
        if (state.type === 'leagueTeamPlayer') {
	            restoreLeagueNavState(state);
            await loadLeagueTeamPlayer(state.playerId, state.teamId, state.teamName, { pushHistory: false, seasonYear: state.seasonYear ?? null });
        }
    }
    async function goBackSmart(fallback = 'dashboard') {
        if (navBusy) return;
        navBusy = true;
        try {
        if (navHistoryStack.length > 0) {
            const previous = navHistoryStack.pop();
            currentNavState = previous;
            navReplayMode = true;
            try {
                await renderNavState(previous);
            } finally {
                navReplayMode = false;
            }
            return;
        }
        // No fallback was named, which means "just go back". A caller the history stack has no entry
        // for and a fallback the caller did not choose both land here, and loadPage(null) would render
        // nothing at all - so a missing target goes to the dashboard rather than to a blank page.
        const target = fallback || 'dashboard';
        if (target === 'dashboard') {
            currentPageId = 'dashboard';
            currentNavState = { type: 'dashboard' };
            if (typeof window.loadDashboard === 'function') window.loadDashboard();
            return;
        }
	        await loadPage(target, { pushHistory: false, preserveLeagueContext: isLeaguePage(target) });
        } finally {
            navBusy = false;
            try { renderDesktopBottomNav(currentPageId); } catch (e) { /* no-op outside dashboard layout */ }
        }
    }

    /**
     * The manager's club id, loaded if it is not in hand yet.
     *
     * <p>`loadPage` has always awaited this before rendering, which is why the whole application is
     * safe on a normal click. Everything reached through a deep link or a refresh goes somewhere else:
     * `loadMatch`, `loadPlayer`, `loadFixture`, `loadFormations` and the rest below all call their view
     * directly, and a view that asked `getTeamId()` before `/auth/me` had answered got `null` and built
     * a URL out of it.
     *
     * <p>It is best effort and deliberately does not stop the render. A caller that cannot be resolved
     * behaved as it did before; a caller that can now waits one request instead of building
     * `/teams/null/players`.
     */
    async function settleTeamId() {
        if (currentUserTeamId) return currentUserTeamId;
        return await loadUserTeamId();
    }

    async function loadUserTeamId() {
        try {
            const res = await authFetch('/auth/me');
            const user = await res.json();
            currentUserTeamId = user.footballTeamId || user.teamId;
            currentUserTeamName = user.footballTeamName || user.teamName || '';
            currentUsername = user.username || '';
            currentUserRole = user.role || '';
            currentUserTeamHumanControlled = typeof user.teamHumanControlled === 'boolean' ? user.teamHumanControlled : null;
            currentUserCompetitionId = user.competitionId ?? null;
            currentUserCompetitionName = user.competitionName || '';
            currentUserCompetitionTier = user.competitionTier ?? null;
            currentSeasonYear = user.seasonYear ?? null;
            currentUserCountryName = user.countryName || '';
            currentUserCountryIsoCode = user.countryIsoCode || '';
            // Paint the account corner from the same payload everything else uses, so the name in
            // the top bar cannot disagree with the club the rest of the page is showing.
            paintAccountMenu(user, currentUserCompetitionName || '');
            currentLeagueSeasonYear = currentSeasonYear || currentLeagueSeasonYear;
            if (!normalizeLeagueId(activeLeagueId)) {
                syncUserLeagueContext();
            }
            return currentUserTeamId;
        } catch (err) {
            console.error("Error /auth/me:", err);
            handleAuthFailure(err, 'Session expired while loading user context.');
            return null;
        }
    }
    async function ensureUserTeamId() {
        if (currentUserTeamId) return currentUserTeamId;
        return await loadUserTeamId();
    }

	async function ensureCurrentLeagueId() {
	    if (!await ensureUserTeamId()) return null;
		    const resolved = normalizeLeagueId(activeLeagueId) || normalizeLeagueId(currentUserCompetitionId);
		    if (!resolved) {
		        // No fallback. Defaulting to league 1 silently showed one manager another club's
		        // table; a club with no competition is a data problem, and the views render an
		        // explicit empty state rather than somebody else's league.
		        console.warn('[league] this manager has no competition; showing the no-league state.');
		    }
		    return resolved;
		}

	function getCurrentLeagueName() {
		    return activeLeagueName || currentUserCompetitionName || 'League';
		}

		function getCurrentLeagueBackTarget() {
		    return activeLeagueBackTarget || 'dashboard';
	}

    // Event delegation za back-button (radi i posle svakog innerHTML overwrite-a)
    document.addEventListener('click', function(e) {
            const dataBackBtn = e.target.closest('[data-nav-back]');
            if (dataBackBtn) {
                e.preventDefault();
                const target = dataBackBtn.dataset.navBack || 'dashboard';
                goBackSmart(target);
                return;
            }
            if (e.target.id === 'back-button' || e.target.closest('#back-button')) {
                const button = e.target.closest('#back-button');
                const target = button.dataset.target || 'results';
                goBackSmart(target);
            }
        });

    const academyFeature = createAcademyFeature({
        authFetch,
        getTeamId: () => currentUserTeamId,
        escapeHtml,
        buildClubActionsHtml,
        loadPlayer: (...args) => loadPlayer(...args),
        goBackSmart: (...args) => goBackSmart(...args),
        // The academy renders talent as a band, and the house rule is two decimals on any decimal
        // (Sprint 5.2). Without this the column would fall back to raw server strings.
        formatPercent,
        // The junior school panel is priced in money.
        formatBudget,
    });
    const loansFeature = createLoansFeature({
        authFetch,
        escapeHtml,
        buildClubActionsHtml,
        loadPlayer: (...args) => loadPlayer(...args),
    });
    const teamFeature = createTeamFeature({
        authFetch,
        getTeamId: () => currentUserTeamId,
        getTeamName: () => currentUserTeamName,
        renderPlayers: (...args) => renderPlayers(...args),
    });
    const matchesFeature = createMatchesFeature({
        authFetch,
        getTeamId: () => currentUserTeamId,
        renderMatches: (...args) => renderMatches(...args),
        renderFixtures: (...args) => renderFixtures(...args),
        // For the results page's own error state, so a failed load reads as a failed load rather
        // than as the router's generic API Error card.
        htmlEscape,
        buildClubActionsHtml,
    });
    const clubManagementFeature = createClubManagementFeature({
        authFetch,
        getTeamId: () => currentUserTeamId,
        getTeamName: () => currentUserTeamName,
        escapeHtml,
        buildClubActionsHtml,
        formatBudget,
        formatDateTimeLabel,
        loadPlayer: (...args) => loadPlayer(...args),
        loadLeagueTeamPlayer: (...args) => loadLeagueTeamPlayer(...args),
    });
    // The forum. Replaces `createCommunityFeature`, whose `forum`, `chat` and `events` routes all
    // rendered the same screen (community.js:314-320) - the forum route pointed at a chat.
    // Both Community screens carry the same Forum / Messages option row, so the injected builder is the
    // one place that decides which of the two is lit.
    const forumView = createForumView({
        authFetch,
        getUsername: () => currentUsername,
        buildCommunityActionsHtml,
    });
    const messagesView = createMessagesView({
        authFetch,
        buildCommunityActionsHtml,
    });

    // The conditional-substitution plan, built here and handed to the match view as a factory.
    //
    // It was mounted by fixture-view.js, which no route reaches any more (T0-UI-4b), so the plan and
    // everything behind it — the save validation, the fired/void report after the whistle — existed for
    // no manager. The match view is where a manager opens a fixture and where they stand after the
    // whistle, so that is where it belongs.
    const substitutionPlanView = createSubstitutionPlanView({ authFetch, getTeamId: () => currentUserTeamId });

    const matchView = createMatchView({
        authFetch, getTeamId: () => currentUserTeamId, goBackSmart,
        // The match view's three panels are gated on the manager's club id, and reading it alone gave
        // null on a cold page - which mounted nothing, silently. This is the existing resolver.
        ensureTeamId: () => ensureUserTeamId(),
        getLeagueNavState: getActiveLeagueNavState,
        createSubstitutionPlanView: () => substitutionPlanView,
        createMatchTacticPlanView: () => createMatchTacticPlanView({ authFetch, getTeamId: () => currentUserTeamId }),
        createMatchLineupView: () => createMatchLineupView({ authFetch, getTeamId: () => currentUserTeamId }),
        createTacticOrderView: () => createTacticOrderView({ authFetch }),
        // The Match Setup tab carries the same club menu as the club pages, so a manager can reach
        // Tactics and Tactic Editor from the screen where he chooses his three tactics.
        buildClubActionsHtml,
        getNavigationDeps: () => ({ pushNavState })
    });
    const playerView = createPlayerView({
        authFetch, getTeamId: () => currentUserTeamId, goBackSmart,
        // Opens a Match from the player's match log. `fixture: false` is set at the call site, because a
        // stat line only exists for a match that was played and the two id spaces overlap.
        loadMatch: (...args) => loadMatch(...args)
    });
    const formationsView = createFormationsView({
        authFetch, getTeamId: () => currentUserTeamId, buildClubActionsHtml
    });
    const tacticEditorView = createTacticEditorView({
        authFetch, getTeamId: () => currentUserTeamId, buildClubActionsHtml
    });
    const trainingView = createTrainingView({
        authFetch, getTeamId: () => currentUserTeamId, buildTrainingActionsHtml,
        buildPlayerProfileHeroHtml: (...args) => playerView.buildPlayerProfileHeroHtml(...args)
    });
    const medicalView = createMedicalView({
        authFetch, getTeamId: () => currentUserTeamId, buildClubActionsHtml,
        loadPlayer: (...args) => loadPlayer(...args)
    });
    const leagueView = createLeagueView({
        authFetch, getTeamId: () => currentUserTeamId,
        ensureCurrentLeagueId, getCurrentLeagueBackTarget, getCurrentLeagueName,
        getLeagueSeasonYear: () => currentLeagueSeasonYear,
        getSeasonYear: () => currentSeasonYear,
        setLeagueSeasonYear: (v) => { if (v !== undefined) currentLeagueSeasonYear = v; },
        pushNavState, getActiveLeagueNavState, goBackSmart,
        loadMatch: (...args) => loadMatch(...args),
        loadFixture: (...args) => loadFixture(...args),
        loadPlayer: (...args) => loadPlayer(...args),
        loadPage: (...args) => loadPage(...args),
        syncUserLeagueContext, setActiveLeagueContext, normalizeLeagueId,
        activeLeagueId: () => activeLeagueId,
        currentUserCompetitionId: () => currentUserCompetitionId,
        buildSquadTableHtml, buildTeamTransferOverviewHtml: (...args) => playerView.buildTeamTransferOverviewHtml(...args),
        bindSquadRowClicks,
        buildPlayerProfileHtml: (...args) => playerView.buildPlayerProfileHtml(...args),
        initPlayerProfilePage: (...args) => playerView.initPlayerProfilePage(...args),
        handlePlayerTransferAction: (...args) => playerView.handlePlayerTransferAction(...args),
        fetchPlayerRatingSummary: (...args) => playerView.fetchPlayerRatingSummary(...args),
        fetchPlayerTransferStatus: (...args) => playerView.fetchPlayerTransferStatus(...args),
        buildMilestoneBoardHtml, buildClubActionsHtml,
        renderTableView, renderLeagueScheduleView, renderLeagueMatchesView
    });
    const fixtureView = createFixtureView({
        authFetch, getTeamId: () => currentUserTeamId,
        ensureCurrentLeagueId, getCurrentLeagueBackTarget, getCurrentLeagueName,
        getLeagueSeasonYear: () => currentLeagueSeasonYear,
        getSeasonYear: () => currentSeasonYear,
        pushNavState, getActiveLeagueNavState, goBackSmart,
        buildClubActionsHtml,
        loadMatch: (...args) => loadMatch(...args),
        // The fixture detail links its venue to the club that plays there, which is the same
        // club-loading routine the league view uses.
        loadLeagueTeam: (...args) => leagueView.loadLeagueTeam(...args),
        matchesFeature,
        renderFixturesView, renderMatches: (...args) => renderMatches(...args)
    });
    const stadiumView = createStadiumView({
        authFetch, getTeamId: () => currentUserTeamId, formatBudget
    });
    const countryView = createCountryView({
        authFetch,
        // T1-13b: the manager's own club id, so the qualifying table can mark it without
        // comparing names. Name comparison has been burned four times in this codebase.
        getTeamId: () => currentUserTeamId,
        loadPage: (...args) => loadPage(...args),
        // The shared match view, so a national-cup tie opens the same screen a league fixture does
        // (owner, 2026-10-07: "svaki generisan mec ... mora da ima mogucnost da se udje na mec i vidi
        // preview kao sto se sada vidi na liga mecevima"). It was missing here, which is why the cup
        // needed a bespoke sheet of its own.
        loadMatch: (...args) => loadMatch(...args),
        setActiveLeagueContext,
        getCurrentUserCountryIsoCode: () => resolveCountryIsoCode(),
        getActiveLeagueCountryIsoCode: () => activeLeagueCountryIsoCode,
        getCurrentUserCountryName: () => currentUserCountryName,
        getSeasonYear: () => currentSeasonYear,
        buildClubActionsHtml
    });
    const clubCupView = createClubCupView({
        authFetch,
        escapeHtml,
        loadPage: (...args) => loadPage(...args),
        // The manager's own club id, so a tie row can be marked as theirs. By id, never by name: two
        // clubs can share a name and four defects in this codebase came from joining on one.
        getTeamId: () => currentUserTeamId,
        // The shared match view, so a Champions / Masters / Challenge Cup tie opens the same screen a
        // league fixture does - prediction before, lineups, stats, goals and a report after (P0-PREV-2).
        loadMatch: (...args) => loadMatch(...args)
    });
    const nationalTournamentView = createNationalTournamentView({
        authFetch,
        escapeHtml,
        // The manager's own country, so the row that is theirs in a qualifying group is marked. By ISO
        // code and never by name - a country and its U-21 side share a family of names.
        currentCountryIso: () => currentUserCountryIsoCode,
        // The shared match view, so a senior international opens the same screen a league fixture does -
        // prediction before, lineups, stats, goals and a report after (P0-PREV-3).
        loadMatch: (...args) => loadMatch(...args)
    });
    const searchView = createSearchView({ authFetch, getTeamId: () => currentUserTeamId });
    const worldFriendlyBoard = createFriendlyBoard({ authFetch });

    const statsView = createStatsView({
        authFetch, getTeamId: () => currentUserTeamId,
        ensureCurrentLeagueId,
        getLeagueSeasonYear: () => currentLeagueSeasonYear,
        getSeasonYear: () => currentSeasonYear,
        goBackSmart,
        renderPlayers: (...args) => renderPlayers(...args),
        loadLeagueTeam: (...args) => leagueView.loadLeagueTeam(...args),
        loadLeagueTeamPlayer: (...args) => leagueView.loadLeagueTeamPlayer(...args),
        buildLeagueActionsHtml
    });
    const clubView = createClubView({
        authFetch, getTeamId: () => currentUserTeamId, buildClubActionsHtml,
        // Takes you to a named league and remembers that you came from the club profile, so Back
        // returns there instead of the dashboard.
        openLeagueById: (leagueId, leagueName) => openLeagueById(leagueId, leagueName, 'profile'),
        loadPage: (...args) => loadPage(...args),
    });
    const adminView = createAdminView({
        getTeamId: () => currentUserTeamId,
        getTeamName: () => currentUserTeamName,
        getUsername: () => currentUsername
    });
    async function loadPage(page, options = {}) {
        const pushHistory = options.pushHistory !== false;
        const mainContent = document.getElementById("main-content");
        currentPageId = page;
    if (!currentUserTeamId) {
            await loadUserTeamId();
            if (!currentUserTeamId) return;
        }
	        const preserveLeagueContext = options.preserveLeagueContext === true;
	        if (isLeaguePage(page) && !preserveLeagueContext) {
	            syncUserLeagueContext();
	        }
	        if (pushHistory) pushNavState(buildPageNavState(page, options));
        try {

            switch(page) {

                case "dashboard":
                    if (typeof window.loadDashboard === 'function') window.loadDashboard();
                    break;

                // TEAM
                case "firstTeam":
                    await loadFirstTeam();
                    break;

                case "juniors":
                    await loadJuniors();
                    break;

                case "loans":
                    await loadLoans();
                    break;
                case "medicalCenter":
                    await loadMedicalCenter();
                    break;

                case "formations":
                case "tactics":
                    await loadFormations();
                    break;

                case "tacticEditor":
                    await loadTacticEditor();
                    break;

                case "staff":
                    await loadStaff();
                    break;

                case "finances":
                    await loadFinances();
                    break;

                case "transfers":
                    await loadTransfers();
                    break;

                case "training":
                case "trainingSetup":
                    await loadTrainingSetup();
                    break;
                case "trainingReports":
                    await loadTrainingReportsPage();
                    break;

                case "profile":
                    await loadClubProfile();
                    break;

                // Somebody else's club. Owner, 2026-10-14: "AI klubови треба да имају profile страну".
                // `teamId` is required and has no fallback: falling back to the viewer's own club would
                // show a page that looks right and is about somebody else, which is the one thing a
                // profile must never do.
                case "clubProfile":
                    if (options.teamId == null) {
                        await loadClubProfile();
                    } else {
                        await loadClubProfile(options.teamId);
                    }
                    break;

                // Global search. Not the friendly picker: this finds anything in the world, and it is
                // reached from the magnifying glass in the top bar rather than from inside a panel.
                case "search":
                    searchView.loadSearch('');
                    break;

                // Desktop navigation preference. Preference is device-local; there is no reason to
                // push this to the backend and every reason not to.
                case "settings":
                    await loadSettingsPage();
                    break;
                case "clubProfileSettings":
                    await loadSettingsPage();
                    break;


                // MATCHES
                case "results":
                    await loadResults();
                    break;

                case "schedule":
                    await loadFixtures();
                    break;

                case "fixtures":
                    await loadFixtures();
                    break;

                // COMPETITIONS
                case "leagueTable":
                    await loadLeagueTable();
                    break;

                case "leagueSchedule":
                    await loadLeagueSchedule();
                    break;

                case "leagueMatches":
                    await loadLeagueMatches();
                    break;

                case "cup":
                    await loadCup();
                    break;

                case "international":
                    await loadInternational();
                    break;

	                case "world":
	                    await loadWorldPage(options);
	                    break;

	                case "country":
                    // Options passed through: the world page marks a simulated country so the page
                    // can say what that means instead of showing an empty divisions table that
                    // reads as a broken page.
                    await loadCountryPage(options);
	                    break;

	                case "clubCup":
	                    // Options: { cupKey, tier }. The World page's three rows link here and the
	                    // tier tabs link here too, so this is one route rather than one per tier.
	                    await clubCupView.loadClubCup(options.cupKey || 'champions', options.tier || 1);
	                    break;

	                case "nationalTournament":
	                    await nationalTournamentView.load(options.level, options.stage);
	                    break;

	                case "countryCup":
	                    await countryView.loadCupPage();
	                    break;

	                case "countryPlayoffs":
	                    await countryView.loadPlayoffPage();
	                    break;

	                case "nationalTeam":
	                    await countryView.loadNationalTeamPage('senior');
	                    break;

	                case "u21Team":
	                    await countryView.loadNationalTeamPage('u21');
	                    break;

                // COMMUNITY
                case "messages":
                    return loadMessages();

                case "messageThread":
                    return loadMessageThread(options.threadId);

                case "forum":
                    await loadForum();
                    break;

                // One section of the forum. The section travels in the page name rather than as an
                // option because the Back button and the pager both rebuild the route from the DOM,
                // and a string there is one less thing that can go missing.
                case "forumSection":
                    await loadForumSection((options.section || 'TIFO').toUpperCase());
                    break;

                case "forumTopic":
                    return loadForumTopic(options.topicId);

                // ADMIN (menu entry is role-gated; the view guards itself too)
                case "admin":
                    await adminView.loadAdmin();
                    break;

                // STATS
                case "topScorers":
                    await loadTopScorersAndAssists("scorers");
                    break;

                case "topAssists":
                    await loadTopScorersAndAssists("assists");
                    break;

                case "userProfile":
                    return loadUserProfile();

                // Somebody else's profile, reached by clicking a club and then its manager.
                // `options.userId` is required and there is no fallback: rendering your own
                // profile under a manager's name would be a page that quietly lies about who it
                // is showing, which is the one thing a profile must never do.
                case "publicUserProfile":
                    return loadPublicUserProfilePage(options.userId);

                case "stadium":
                    return stadiumView.loadStadium();

                default:
                    mainContent.innerHTML = buildEmptyState("Page not found");
            }

        } catch (err) {
            console.error(err);
            // Was: buildEmptyState("API Error"). One string, no status, no code, no explanation, and
            // it replaced whatever the page had already rendered — so a 403 saying "Only the owning
            // club can accept incoming offers" and a 500 saying the database was unreachable both
            // reached the manager as the same two words. The backend already writes a message for the
            // person reading it, and authFetch carries it; show it.
            mainContent.innerHTML = buildErrorState(err, describePage(page));
        } finally {
            scrollToTopOfPage();
            try { renderDesktopBottomNav(page); } catch (e) { /* no-op outside the dashboard layout */ }
        }
    }

    /**
     * Every page opens at its own top.
     *
     * <p>Nothing scrolled, so a page inherited the scroll position of the one before it. Leaving a long
     * country page halfway down and opening World landed you halfway down World - the owner's words:
     * *"(skrol na top) a ne dole pri dnu kao sasa"*.
     *
     * <p>A `try/finally` and not a line at the end of the `try`, so an error card is not the one page
     * that stays where it was. Instant, because a smooth scroll animates the manager through a page
     * they have not read yet. `document.scrollingElement` is the element that actually scrolls in every
     * browser this runs in; `window.scrollTo` alone does not move it everywhere.
     */
    function scrollToTopOfPage() {
        if (window.scrollTo) {
            window.scrollTo({ top: 0, left: 0, behavior: 'instant' });
        }
        const scroller = document.scrollingElement || document.documentElement;
        if (scroller && scroller.scrollTop !== 0) {
            scroller.scrollTop = 0;
        }
    }

    /**
     * What the manager was trying to reach, in words.
     *
     * <p>An error card that says "page: firstTeam" has told the manager nothing they did not know,
     * and has told whoever reads the bug report nothing at all. These are the page ids the router
     * switches on, so an id without an entry here falls back to the id rather than to nothing.
     */
    const PAGE_NAMES = {
        firstTeam: 'the first team',
        juniors: 'the academy',
        medicalCenter: 'the medical centre',
        formations: 'formations',
        tactics: 'team tactics',
        tacticEditor: 'the tactics editor',
        staff: 'the staff',
        finances: 'the finances',
        transfers: 'the transfer centre',
        training: 'training',
        trainingSetup: 'the training setup',
        trainingReports: 'the training reports',
        profile: 'the club profile',
        results: 'the results',
        schedule: 'the schedule',
        fixtures: 'the fixtures',
        leagueTable: 'the league table',
        leagueSchedule: 'the league schedule',
        leagueMatches: 'the league matches',
        cup: 'the cup',
        international: 'international competitions',
        world: 'the world',
        country: 'the country',
        countryCup: 'the national cup',
        countryPlayoffs: 'the promotion playoffs',
        nationalTeam: 'the national team',
        u21Team: 'the under-21s',
        forum: 'the forum',
        admin: 'the admin page',
        topScorers: 'the top scorers',
        topAssists: 'the top assists',
        stadium: 'the stadium',
        userProfile: 'your profile'
    };

    function describePage(page) {
        return PAGE_NAMES[page] || null;
    }

    // --- Thin wrapper functions that delegate to view modules ---

    async function loadPlayer(playerId, callerPage, options = {}) {
        await settleTeamId();
        playerView.setCallerPage(callerPage);
        return playerView.loadPlayer(playerId, callerPage, options);
    }

    async function loadMatch(matchId, caller, options = {}) {
        await settleTeamId();
        return matchView.loadMatch(matchId, caller, options);
    }

    async function loadFormations() {
        await settleTeamId();
        return formationsView.loadFormations();
    }

    async function loadTacticEditor() {
        await settleTeamId();
        return tacticEditorView.loadTacticEditor();
    }

    async function loadTrainingReports() {
        await settleTeamId();
        return trainingView.loadTrainingReports();
    }

    async function loadTrainingSetup() {
        await settleTeamId();
        return trainingView.loadTrainingSetup();
    }

    async function loadTrainingReportsPage() {
        await settleTeamId();
        return trainingView.loadTrainingReportsPage();
    }

    async function loadMedicalCenter() {
        await settleTeamId();
        return medicalView.loadMedicalCenter();
    }

    async function loadClubProfile(teamId) {
        // A teamId means somebody else's club. Settling the viewer's own team id is only needed when
        // no club was named, and doing it anyway would have been harmless before and is a wasted
        // request now — the club asked for is the club shown.
        if (teamId == null) {
            await settleTeamId();
            return clubView.loadClubProfile();
        }
        return clubView.loadClubProfile(teamId);
    }

    async function loadUpcomingMatches() {
        await settleTeamId();
        return fixtureView.loadUpcomingMatches();
    }

    async function loadFixtures() {
        await settleTeamId();
        return fixtureView.loadFixtures();
    }

    async function loadFixture(fixtureId, options = {}) {
        await settleTeamId();
        return fixtureView.loadFixture(fixtureId, options);
    }

    async function loadLeagueTable(seasonYear = null) {
        await settleTeamId();
        return leagueView.loadLeagueTable(seasonYear);
    }

    async function loadLeagueSchedule(seasonYear = null) {
        await settleTeamId();
        return leagueView.loadLeagueSchedule(seasonYear);
    }

    async function loadLeagueMatches(seasonYear = null) {
        await settleTeamId();
        return leagueView.loadLeagueMatches(seasonYear);
    }

    async function loadLeagueTeam(teamId, teamName, options = {}) {
        await settleTeamId();
        return leagueView.loadLeagueTeam(teamId, teamName, options);
    }

    async function loadLeagueTeamPlayer(playerId, teamId, teamName, options = {}) {
        await settleTeamId();
        return leagueView.loadLeagueTeamPlayer(playerId, teamId, teamName, options);
    }

    async function openTeamByName(teamName) {
        return leagueView.openTeamByName(teamName);
    }

    async function openCountryLeague(leagueId, leagueName) {
        return countryView.openCountryLeague(leagueId, leagueName);
    }

    /**
     * The world page: how big the game is, and every country in it (owner, 2026-09-29).
     *
     * <p>The hub the country page hangs off. A country is not a tab here - it is a row you click,
     * which is why the existing Country page and its four tabs stay exactly as they are. The Sokker
     * page the owner showed has this same shape, and it is the right one: the map is the index, a
     * country is a page.
     */
    /**
     * The world: what the game is, and every country in it (owner, 2026-09-29).
     *
     * <p>A hub, not a duplicate of the country page. A country is a row you click, which is why the
     * country page and its tabs are untouched.
     *
     * <p>Every country in the catalogue is listed, and a SIMULATED one is clickable too. The rule is
     * ACTIVE means playable, so a country that is simulated today and activated tomorrow starts
     * working with no change here - hard-coding "only Serbia" would bake today's state into the
     * page and quietly break the moment a second country goes live.
     */
    /**
     * One of the three international club cups.
     *
     * <p>These three used to be a hard-coded disabled row reading "Not created yet", which is a claim
     * about the database being empty. They exist, they have entrants, and the number is what decides
     * between "no club has finished a season yet" and "not created".
     */
    function clubCupRow(name, key, cups) {
        // **The row is a link now (P1-CUPS-4).** It was a <div>, so the World page had three lines of
        // text about the cups and no way into any of them - and it matched the literal string
        // 'Champions Cup', so only tier 1 could ever render and tiers 2-5 were invisible from here.
        //
        // The count is the sum over all five tiers, because from this page the cup is one competition
        // with five levels, and "Champions Cup 1 qualified" meant "one of the 48 that exist".
        const tiers = Array.isArray(cups)
            ? cups.filter(cup => cup && typeof cup.name === 'string' && cup.name.endsWith(name))
            : [];
        const known = tiers.length > 0;
        const qualified = tiers.reduce((total, cup) => total + Number(cup.qualified || 0), 0);
        const tiersWithClubs = tiers.filter(cup => Number(cup.qualified || 0) > 0).length;
        const expected = name === 'Masters Cup' ? 96 : 48;

        // **A cup that has clubs in it has been drawn.** The old test was `qualified === expected`, so
        // a single country failing to qualify flipped every tier from "48 qualified" to "Not drawn yet" -
        // which is what the owner saw on the World page, on cups that plainly were drawn. One country's
        // missing tier-4 division (the Netherlands has none) took out tiers 4 and 5 of all three cups at
        // once, and the screen said nothing had happened.
        //
        // "Not drawn yet" is now only ever true when no tier has a single entrant, which is the only
        // state in which it is honest.
        // **Whether a cup is drawn is a question about the cup, not about the previous season.**
        //
        // `qualified` counts clubs that came out of the finished season's tables, and in the opening
        // season there is no finished season, so it is 0 for all three cups no matter what has actually
        // been drawn. The World page read that zero as "Not drawn yet" on cups that plainly were drawn -
        // the Champions Cup had its groups and its 120 ties on screen two clicks away.
        //
        // `ties` is the honest signal: it is what the cup itself holds.
        const drawn = tiers.reduce((total, cup) => total + Number(cup.ties || 0), 0);
        const badge = !known
            ? '<span class="fm-badge">Unavailable</span>'
            : drawn === 0 && qualified === 0
                ? '<span class="fm-badge">Not drawn yet</span>'
                : drawn > 0
                    ? `<span class="fm-badge fm-badge--ok">Drawn${drawn > 1 ? '' : ''} \u00b7 ${drawn} tie${drawn === 1 ? '' : 's'}${
                        tiersWithClubs > 1 ? ` \u00b7 ${tiersWithClubs} tiers` : ''}</span>`
                    : qualified === expected
                    ? `<span class="fm-badge fm-badge--ok">${expected} qualified${
                        tiersWithClubs > 1 ? ` · ${tiersWithClubs} tiers` : ''}</span>`
                    : `<span class="fm-badge fm-badge--ok">${qualified} qualified${
                        tiersWithClubs > 1 ? ` · ${tiersWithClubs} tiers` : ''}</span>`
                        + `<span class="fm-subtle"> of ${expected}</span>`;

        return `<tr class="fm-world-competition-row is-real" data-club-cup="${escapeHtml(key)}"
                    tabindex="0" role="link">
                    <td class="sq-name"><strong>${escapeHtml(name)}</strong></td>
                    <td>Club competition</td>
                    <td>${badge}</td>
                </tr>`;
    }

    async function loadWorldPage(options = {}) {
        const initialWorldTab = options?.tab || 'countries';
        const mainContent = document.getElementById('main-content');
        try {
            const teamId = typeof currentUserTeamId !== 'undefined' ? currentUserTeamId : null;
            const [res, nationalRes, clubsRes, boardRes, mineRes] = await Promise.all([
                authFetch('/countries/world'), authFetch('/api/national-tournaments'),
                authFetch('/countries/world/clubs/ranking?limit=500'),
                // Week 6 is the break week: no club matches, so clubs can actually advertise
                // and take a slot without being blocked by a league fixture.
                worldFriendlyBoard.loadBoard(null, 6),
                teamId ? worldFriendlyBoard.loadMine(teamId, null, 6) : Promise.resolve(null)
            ]);
            if (!res.ok || !nationalRes.ok) throw new Error('World unavailable');
            const world = await res.json();
            const national = await nationalRes.json();
            const worldClubsPayload = clubsRes && clubsRes.ok ? await clubsRes.json() : null;
            const worldFriendlyBoardData = boardRes && !boardRes.failed ? boardRes : null;
            const worldFriendlyMineData = mineRes && !mineRes.failed ? mineRes : null;
            const nationalRows = Array.isArray(national.competitions) ? national.competitions : [];
            const countries = Array.isArray(world.countries) ? world.countries : [];

            // Ranked by rating, because the column beside the name is a rating and a ranking table with no
            // order is just a list. Every country starts level, so this is alphabetical until anything has
            // been played - which is the honest state, not a tie to be broken arbitrarily.
            const ranked = [...countries].sort((a, b) => {
                const left = Number(a.reputation ?? 0);
                const right = Number(b.reputation ?? 0);
                if (right !== left) return right - left;
                return String(a.name || '').localeCompare(String(b.name || ''));
            });

            const rows = ranked.map((country, index) => {
                const active = country.state === 'ACTIVE';
                // The whole row is the link, and the name is plain text inside it.
                //
                // It was a button wrapped around the country name, so the row read as one name-shaped
                // target: no position, so no way to see where a country sits in the field, and the ordinal
                // - the one piece of information a ranking table exists to carry - had nowhere to go.
                // The button also could not be reached by keyboard row selection, and its hit area was the
                // text alone rather than the row.
                return `
                    <tr class="fm-world-row${active ? ' fm-world-row--active' : ''} js-load-world-country"
                        data-world-country="${escapeHtml(country.isoCode || '')}"
                        data-world-state="${escapeHtml(country.state || '')}"
                        tabindex="0" role="link">
                        <td class="st-pos">${index + 1}</td>
                        <td class="sq-name">
                            <span class="fm-world-country">
                                ${country.flagImagePath ? `<img class="fm-world-flag" src="${escapeHtml(country.flagImagePath)}" alt="" />` : ''}
                                <span class="fm-world-country-name">${escapeHtml(country.name || '')}</span>
                            </span>
                        </td>
                        <td class="st-rating">${country.reputation ?? '—'}</td>
                        <td>${active
                            ? '<span class="fm-badge fm-badge--ok">Active</span>'
                            : '<span class="fm-badge">Simulated</span>'}</td>
                    </tr>`;
            }).join('');

            mainContent.innerHTML = `
                <div class="fm-page fm-page--world">
                    <header class="fm-country-header">
                        <div class="fm-country-header-main">
                            <div>
                                <div class="fm-eyebrow">World</div>
                                <h2 class="fm-country-header-title">Every country in the game</h2>
                            </div>
                        </div>
                        <button class="back-to-dashboard fm-country-header-back" data-nav-back="dashboard">Back</button>
                        <dl class="fm-country-facts">
                            <div class="fm-country-fact fm-world-fact"><dt>Countries:</dt><dd><span class="fm-world-fact-number">${world.totalCountries}</span></dd></div>
                            <div class="fm-country-fact fm-world-fact"><dt>Registered user:</dt><dd><span class="fm-world-fact-number">${world.registeredPlayers ?? '-'}</span> · Online now: <span class="fm-world-fact-number">${world.onlinePlayers ?? '-'}</span></dd></div>
                        </dl>
                    </header>

                    ${!world.complete ? `<div class="fm-callout fm-callout--warn">
                        The world holds ${countries.length} of ${world.expectedCountries} countries.
                        Admin &rarr; World integrity will rebuild the missing ones.
                    </div>` : ''}

                    <section data-world-panel="competitions" class="fm-panel" hidden>
                        <div class="fm-panel-head">
                            <h3>International competitions</h3>
                        </div>
                        <div class="fm-squad-wrap fm-world-competitions-table-wrap">
                            <table class="fm-squad fm-world-competitions-table">
                                <thead>
                                    <tr><th>Competition</th><th>Type</th><th>Field</th></tr>
                                </thead>
                                <tbody>
                                    ${clubCupRow('Champions Cup', 'champions', world.clubCups)}
                                    ${clubCupRow('Masters Cup', 'masters', world.clubCups)}
                                    ${clubCupRow('Challenge Cup', 'challenge', world.clubCups)}
                                    ${NATIONAL_COMPETITIONS.map(definition => {
                                        const item = nationalRows.find(row => row.level === definition.level && row.stage === definition.stage) || {};
                                        // The season the fixtures belong to, not the season it is now. A tile
                                        // that says "120 fixtures" with no season invites the reader to assume
                                        // it is this season's — which was the confusion behind the whole thing
                                        // reporting "Not drawn yet" while holding 120 fixtures.
                                        const status = item.exists && Number(item.fixtures || 0) > 0
                                            ? `${item.fixtures} fixtures · week ${item.week}`
                                              + (item.drawnSeason ? ` · season ${item.drawnSeason}` : '')
                                            : 'Not drawn yet';
                                        return `<tr class="fm-world-competition-row ${item.exists ? 'is-real' : 'is-disabled'}"
                                            data-national-level="${definition.level}" data-national-stage="${definition.stage}"
                                            tabindex="0" role="link">
                                            <td class="sq-name"><strong>${definition.label}</strong></td>
                                            <td>National teams</td><td><span class="fm-badge">${status}</span></td></tr>`;
                                    }).join('')}
                                </tbody>
                            </table>
                        </div>
                        <p class="fm-hint">Club cups and drawn national-team competitions open into their
                            groups, results and bracket. Undrawn competitions remain visible with their
                            planned week.</p>
                    </section>

                    <div class="fm-player-tabs" role="tablist" aria-label="World sections">
                        <button type="button" class="fm-player-tab is-active" data-world-tab="countries">Countries</button>
                        <button type="button" class="fm-player-tab" data-world-tab="clubs">Clubs</button>
                        <button type="button" class="fm-player-tab" data-world-tab="competitions">Competitions</button>
                        <button type="button" class="fm-player-tab" data-world-tab="friendlies">Friendlies</button>
                    </div>

                    <section data-world-panel="countries" class="fm-panel">
                        <div class="fm-panel-head">
                            <h3>Countries</h3>
                        </div>
                        <div class="fm-squad-wrap">
                            <table class="fm-squad fm-world-table">
                                <thead>
                                    <tr>
                                        <th class="st-pos">#</th>
                                        <th class="sq-name">Country</th>
                                        <th class="st-rating">Rating</th>
                                        <th>State</th>
                                    </tr>
                                </thead>
                                <tbody>${rows || '<tr><td colspan="4">No countries yet.</td></tr>'}</tbody>
                            </table>
                        </div>
                    </section>

                    <section data-world-panel="clubs" class="fm-panel fm-clubs-ranking" hidden>
                        <div class="fm-panel-head">
                            <div>
                                <h3>World club ranking</h3>
                                <p class="fm-subtle">${Number(worldClubsPayload?.totalClubs ?? 0)} club${Number(worldClubsPayload?.totalClubs ?? 0) === 1 ? '' : 's'}, strongest first</p>
                            </div>
                        </div>
                        <div class="fm-squad-wrap">
                            <table class="fm-squad fm-world-table fm-clubs-ranking-table">
                                <thead>
                                    <tr>
                                        <th class="st-pos">#</th>
                                        <th class="sq-name">Club</th>
                                        <th>Country</th>
                                        <th>Division</th>
                                        <th>Tier</th>
                                        <th class="st-rating">Rating</th>
                                    </tr>
                                </thead>
                                <tbody data-clubs-body>${(worldClubsPayload?.clubs ?? []).map(club => `
                                    <tr class="${Number(club.teamId) === Number(typeof getTeamId === 'function' ? getTeamId() : null) ? 'is-own-club' : ''}" data-club-team-id="${club.teamId}">
                                        <td class="st-pos">${club.position ?? '—'}</td>
                                        <td class="sq-name"><strong>${club.teamId ? `<span class="fm-CTeam-link js-world-club" data-club-team-id="${club.teamId}">${escapeHtml(club.name || 'Unknown')}</span>` : escapeHtml(club.name || 'Unknown')}</strong></td>
                                        <td>${escapeHtml(club.country || '—')}</td>
                                        <td>${escapeHtml(club.division || '—')}</td>
                                        <td>${club.tier ?? '—'}</td>
                                        <td class="st-rating">${typeof club.points === 'number' ? club.points.toFixed(2) : '—'}${club.rated ? '' : ' <span class="fm-badge fm-badge--warn">not yet rated</span>'}</td>
                                    </tr>`).join('')}</tbody>
                            </table>
                        </div>
                        <div class="fm-pager" data-clubs-pager hidden>
                            <button type="button" class="fm-action-btn secondary fm-pager-btn" data-clubs-prev>Previous</button>
                            <span class="fm-pager-status" data-clubs-status></span>
                            <button type="button" class="fm-action-btn secondary fm-pager-btn" data-clubs-next>Next</button>
                        </div>
                    </section>
                </div>`;

            // World ranking tabs: county vs clubs (owner, 2026-10-14).
            const setWorldTab = (tab) => {
                mainContent.querySelectorAll('[data-world-tab]').forEach(button => {
                    button.classList.toggle('is-active', button.dataset.worldTab === tab);
                });
                mainContent.querySelectorAll('[data-world-panel]').forEach(panel => {
                    panel.hidden = panel.dataset.worldPanel !== tab;
                });
            };
            mainContent.querySelectorAll('[data-world-tab]').forEach(button => {
                button.addEventListener('click', () => setWorldTab(button.dataset.worldTab));
            });
            setWorldTab(initialWorldTab);

            const freeSlotHtml = worldFriendlyBoard && typeof worldFriendlyBoard.buildHtml === 'function'
                ? worldFriendlyBoard.buildHtml(worldFriendlyBoardData == null ? { failed: true, status: 0 } : worldFriendlyBoardData,
                                               worldFriendlyMineData == null ? { failed: true, status: 0 } : worldFriendlyMineData,
                                               teamId)
                : '';
            const freeSlotSection = freeSlotHtml
                ? `<section data-world-panel="friendlies" class="fm-panel fm-friendly-board-wrap" hidden>${freeSlotHtml}</section>`
                : '';
            // Put it after clubs panel by appending to mainContent after render.

            if (freeSlotSection) {
                const host = mainContent.querySelector('.fm-page--world') || mainContent;
                host.insertAdjacentHTML('beforeend', freeSlotSection);
                worldFriendlyBoard.wire(mainContent, teamId);
                // The Friendlies panel is created after the initial tab switch above, so apply the
                // requested tab once more now that every panel exists.
                setWorldTab(initialWorldTab);
            }

            const openCountry = row => {
                const iso = String(row.dataset.worldCountry || '').toUpperCase();
                if (!iso) return;
                // A simulated country has no clubs, so its page is the national-team record and
                // nothing else. Saying so is better than showing an empty divisions table that reads
                // as a broken page.
                const isSimulated = row.dataset.worldState !== 'ACTIVE';
                setSelectedCountry(iso);
                setActiveLeagueContext({ countryIsoCode: iso, backTarget: 'world' });
                loadPage('country', { simulatedCountry: isSimulated ? iso : '' });
            };

            // The three cups are links now (P1-CUPS-4). They were <div>s, so the World page had three
            // lines of text about the cups and no way into any of them.
            mainContent.querySelectorAll('[data-club-cup]').forEach(button => {
                button.addEventListener('click', () => {
                    loadPage('clubCup', { cupKey: button.dataset.clubCup, tier: 1 });
                });
                button.addEventListener('keydown', event => {
                    if (event.key === 'Enter' || event.key === ' ') {
                        event.preventDefault();
                        loadPage('clubCup', { cupKey: button.dataset.clubCup, tier: 1 });
                    }
                });
            });

            mainContent.querySelectorAll('[data-national-level][tabindex]').forEach(row => {
                const open = () => loadPage('nationalTournament', {
                    level: row.dataset.nationalLevel, stage: row.dataset.nationalStage
                });
                row.addEventListener('click', open);
                row.addEventListener('keydown', event => {
                    if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); open(); }
                });
            });

            // The row is the link now, not the button inside it, so the keyboard case has to be handled
            // here: a <tr> with role="link" and tabindex does not get Enter or Space for free, and a row
            // that looks clickable but cannot be reached from the keyboard is a worse regression than the
            // button-in-a-name it replaced.
            // World club ranking pager (25 per page, like country view).
            const worldClubsBody = mainContent.querySelector('[data-clubs-body]');
            const worldClubsPager = mainContent.querySelector('[data-clubs-pager]');
            if (worldClubsBody && worldClubsPager) {
                const all = Array.from(worldClubsBody.querySelectorAll('tr'));
                if (all.length > 0) {
                    const perPage = 25;
                    const pages = Math.max(1, Math.ceil(all.length / perPage));
                    if (pages === 1) {
                        worldClubsPager.hidden = true;
                    } else {
                        worldClubsPager.hidden = false;
                        const prev = worldClubsPager.querySelector('[data-clubs-prev]');
                        const next = worldClubsPager.querySelector('[data-clubs-next]');
                        const status = worldClubsPager.querySelector('[data-clubs-status]');
                        let page = 1;
                        const show = () => {
                            const from = (page - 1) * perPage;
                            all.forEach((row, index) => { row.hidden = index < from || index >= from + perPage; });
                            const first = all.findIndex(row => !row.hidden) + 1;
                            const last = from + Math.min(perPage, all.length - from);
                            status.textContent = `${first}-${last} of ${all.length}`;
                            prev.disabled = page === 1;
                            next.disabled = page === pages;
                        };
                        prev.addEventListener('click', () => { if (page > 1) { page -= 1; show(); } });
                        next.addEventListener('click', () => { if (page < pages) { page += 1; show(); } });
                        show();
                    }
                }
            }
            mainContent.querySelectorAll('.js-world-club').forEach(node => {
                node.addEventListener('click', (event) => {
                    event.stopPropagation();
                    const teamId = Number(node.dataset.clubTeamId);
                    if (teamId) window.loadClubProfile(teamId);
                });
            });

            mainContent.querySelectorAll('[data-world-country]').forEach(row => {
                row.addEventListener('click', () => openCountry(row));
                row.addEventListener('keydown', event => {
                    if (event.key === 'Enter' || event.key === ' ') {
                        event.preventDefault();
                        openCountry(row);
                    }
                });
            });
        } catch (err) {
            console.error('Failed to load the world page:', err);
            mainContent.innerHTML = '<div class="manager-card"><h2>Error</h2><p>Could not load the world.</p></div>';
        }
    }

    /**
     * Forwards its options.
     *
     * <p>It used to take none and drop whatever the router passed, so the world page's
     * "this country is represented, not played" note never arrived even though the router had set it
     * and the view had code to render it. The option existed in three of the four places between the
     * click and the screen, which is the most annoying shape a dropped argument can have.
     */
    async function loadCountryPage(options) {
        await settleTeamId();
        return countryView.loadCountryPage(options);
    }

    async function loadNationalTeam(level = 'senior') {
        await settleTeamId();
        return countryView.loadNationalTeamPage(level);
    }

    async function loadTopScorersAndAssists(mode = "both") {
        await settleTeamId();
        return statsView.loadTopScorersAndAssists(mode);
    }

    async function loadPlayerStats() {
        await settleTeamId();
        return statsView.loadPlayerStats();
    }

    function renderFixtures(fixtures, title, options = {}) {
        return fixtureView.renderFixtures(fixtures, title, options);
    }

    function renderLeagueMatches(matches, title = "League Results", options = {}) {
        renderLeagueMatchesView(matches, title, { loadMatch, ...options });
    }

    function renderPlayers(players, title, options = {}) {
        renderPlayersView(players, title, {
            loadPlayer,
            getImageFilename,
            ...options,
            milestonesHtml: options?.milestones ? buildMilestoneBoardHtml(options.milestones) : options?.milestonesHtml
        });
    }

    function renderMatches(matches, title, options = {}) {
        renderMatchesView(matches, title, { loadMatch, currentTeamName: currentUserTeamName, ...options });
    }

    function renderTable(table) {
        renderTableView(table, { loadLeagueTeam, loadLeagueTeamPlayer, loadLeagueTable, loadMatch, loadFixture, escapeHtml: htmlEscape, formatGoalDiff, getTeamId: () => currentUserTeamId });
    }

    /**
     * A competition's matches, for the two menu entries that read one competition's fixtures.
     *
     * <p>Both were two `console.log` lines and an unguarded `await response.json()`. `authFetch` throws on
     * every non-2xx, so a failure replaced the whole page with the two-word **"API Error"** card — which
     * is what the manager saw instead of the cup draw he had just clicked a menu item for.
     */
    async function loadCup() {
        await loadCompetitionMatches('CUP', "Cup");
    }

    async function loadInternational() {
        await loadCompetitionMatches('INTERNATIONAL', "International Matches");
    }

    async function loadCompetitionMatches(competitionType, title) {
        if (!currentUserTeamId) return;
        try {
            const response = await authFetch(
                `/teams/${currentUserTeamId}/schedule?competitionType=${encodeURIComponent(competitionType)}`);
            if (!response.ok) throw new Error(`${title} load failed (${response.status})`);
            renderMatches(await response.json(), title);
        } catch (err) {
            buildErrorState(err, title);
        }
    }

    async function loadForum() {
        await settleTeamId();
        return forumView.loadForum();
    }
    async function loadForumSection(section) {
        await settleTeamId();
        return forumView.loadForumSection(section);
    }
    async function loadForumTopic(topicId) {
        await settleTeamId();
        return forumView.loadForumTopic(topicId);
    }

    /**
     * Private messages.
     *
     * <p>The `loadChat` and `loadEvents` delegates are gone with `createCommunityFeature`: both were
     * `return loadChat()` inside that module, so a console `loadPage('events')` rendered a chat. A
     * console call to `loadPage('chat')` now lands on "Page not found", which is honest about what
     * exists rather than quietly showing something else.
     */
    async function loadMessages() {
        await settleTeamId();
        return messagesView.loadMessages();
    }
    async function loadMessageThread(threadId) {
        await settleTeamId();
        return messagesView.loadMessageThread(threadId);
    }

    function openStadiumImage(imageUrl) {
        return clubView.openStadiumImage(imageUrl);
    }

    function showStadiumModal(imageUrl, stadiumName) {
        return clubView.showStadiumModal(imageUrl, stadiumName);
    }

    async function loadFirstTeam() {
        return teamFeature.loadFirstTeam();
    }
    async function loadResults() {
        return matchesFeature.loadResults();
    }
    async function loadJuniors() {
        return academyFeature.loadJuniors();
    }
    async function loadLoans() {
        return loansFeature.loadLoans();
    }
    async function loadStaff() {
        return clubManagementFeature.loadStaff();
    }
    async function loadFinances() {
        return clubManagementFeature.loadFinances();
    }
    async function loadTransfers() {
        return clubManagementFeature.loadTransfers();
    }
    /**
     * The signed-in user's own page, off the /auth/me payload that is already loaded.
     *
     * <p>Re-fetched rather than read from a module variable, because a page you are looking at should
     * never be showing a stale copy of who you are, and the request is one small call.
     */
async function loadUserProfile() {
        await ensureUserTeamId();
        try {
            const res = await authFetch('/auth/me');
            if (!res.ok) throw new Error('could not load the account');
            const me = await res.json();
            // "My profile" and a public profile are now one renderer. `viewer` is the signed-in manager,
            // and it is what decides the "is this you?" branch — without it the page renders as somebody
            // else's profile and the name editor is hidden.
            await loadPublicUserProfile(me.id, me);
        } catch (err) {
            document.getElementById('main-content').innerHTML =
                buildEmptyState('Your account could not be loaded. ' + (err && err.message ? err.message : ''));
        }
    }

    /**
     * Opens another manager's public profile.
     *
     * <p>Re-reads {@code /auth/me} for the viewer rather than trusting a module variable, for the same
     * reason {@code loadUserProfile} does: a page you are looking at should not be deciding "is this
     * me?" and "may I moderate?" from a copy of who you were when the app loaded.
     */
    async function loadPublicUserProfilePage(userId) {
        if (userId === undefined || userId === null) {
            document.getElementById('main-content').innerHTML =
                buildEmptyState('No manager was named.');
            return;
        }
        try {
            const res = await authFetch('/auth/me');
            const viewer = res.ok ? await res.json() : null;
            await loadPublicUserProfile(userId, viewer);
        } catch (err) {
            document.getElementById('main-content').innerHTML =
                buildEmptyState('This profile could not be loaded. ' + (err?.message || ''));
        }
    }

    /**
     * Opens a manager's profile from anywhere, including from inline markup.
     *
     * <p>On {@code window} because club profiles and league tables are shared renderers with no other
     * route to the router, and an inline {@code onclick} is the only handler those can carry — the
     * same reason {@code openLeagueById} is exposed.
     */
    function openUserProfile(userId) {
        return loadPage('publicUserProfile', { userId });
    }

    /**
     * Open a named league from anywhere, remembering where the user came from.
     *
     * <p>On window because it is used by the schedule screen, which is a set of shared renderers and
     * has no other way to reach the router's league context. The back target is what makes the club
     * profile's league link land on the league and then come back to the profile.
     */
    function openLeagueById(leagueId, leagueName, backTarget) {
        setActiveLeagueContext({
            leagueId,
            leagueName: leagueName || 'League',
            countryIsoCode: currentUserCountryIsoCode || '',
            backTarget: backTarget || 'dashboard'
        });
        return loadPage('leagueTable', { preserveLeagueContext: true });
    }

    window.openLeagueById = openLeagueById;
    async function loadSettingsPage() {
        const mainContent = document.getElementById("main-content");
        const pref = localStorage.getItem("fm_desktop_bottom_nav");
        const enabled = pref === null || pref === "true";
        mainContent.innerHTML = `
            <div class="fm-page fm-page--settings">
                <section class="fm-panel">
                    <div class="fm-panel-head">
                        <div>
                            <h2>Settings</h2>
                            <p class="fm-subtle">Device-local preferences. None of this touches team data.</p>
                        </div>
                        <span class="fm-panel-action">Settings</span>
                    </div>
                    <label class="fm-field" style="display:flex; align-items:center; gap:10px;">
                        <input type="checkbox" id="settings-desktop-nav" ${enabled ? "checked" : ""}>
                        <span>Show the bottom navigation on desktop</span>
                    </label>
                    <p class="fm-subtle" style="margin-top:10px;">Default: yes. Mobile navigation is unaffected.</p>
                </section>
            </div>`;
        const checkbox = document.getElementById("settings-desktop-nav");
        if (checkbox) {
            checkbox.addEventListener("change", () => {
                const next = !!checkbox.checked;
                localStorage.setItem("fm_desktop_bottom_nav", String(next));
                document.documentElement.classList.toggle("desktop-bottom-nav-on", next);
            });
        }
    }

    function desktopBottomNavGroup(page) {
        if (!page) return 'main';
        if (page === 'dashboard') return 'main';
        if (['firstTeam', 'juniors'].includes(page)) return 'club:squad';
        if (['formations', 'tactics', 'tacticEditor'].includes(page)) return 'club:tactics';
        if (['training', 'trainingSetup', 'trainingReports'].includes(page)) return 'club:training';
        if (['schedule', 'results', 'fixtures'].includes(page)) return 'club:matches';
        if (['clubProfile', 'loans', 'staff', 'finances', 'transfers', 'medicalCenter', 'profile', 'clubProfileSettings', 'stadium'].includes(page)) return 'club';
        if (['leagueTable', 'leagueSchedule', 'leagueMatches', 'topScorers', 'topAssists'].includes(page)) return 'league';
        if (page.startsWith('country') || page === 'nationalTournament') return 'country';
        if (page === 'world' || page === 'clubCup' || page === 'nationalTournament' || page === 'countryCup' || page === 'countryPlayoffs') return 'world';
        if (page === 'forum' || page === 'forumSection' || page === 'forumTopic' || page === 'messages' || page === 'messageThread') return 'community';
        if (page === 'search') return 'search';
        if (page === 'admin') return 'admin';
        if (page === 'settings' || page === 'publicUserProfile' || page === 'loadPublicUserProfile') return 'user';
        return 'main';
    }

    function desktopBottomNavItems(group) {
        if (group === 'club:squad') {
            return [
                { navBack: true, label: 'Back', icon: '◀' },
                { label: 'First team', page: 'firstTeam' },
                { label: 'Juniors', page: 'juniors' },
            ];
        }
        if (group === 'club:tactics') {
            return [
                { navBack: true, label: 'Back', icon: '◀' },
                { label: 'Tactics setup', page: 'formations' },
                { label: 'Tactic Editor', page: 'tacticEditor' },
            ];
        }
        if (group === 'club:training') {
            return [
                { navBack: true, label: 'Back', icon: '◀' },
                { label: 'Training Setup', page: 'trainingSetup' },
                { label: 'Training Reports', page: 'trainingReports' },
            ];
        }
        if (group === 'club:matches') {
            return [
                { navBack: true, label: 'Back', icon: '◀' },
                { label: 'Schedule', page: 'schedule' },
                { label: 'Results', page: 'results' },
            ];
        }
        if (group === 'club') {
            return [
                { navBack: true, label: 'Back', icon: '◀' },
                { label: 'Squad', gotoGroup: 'club:squad', icon: '👥' },
                { label: 'Matches', gotoGroup: 'club:matches', icon: '⚽' },
                { label: 'Loans', page: 'loans' },
                { label: 'Tactics', gotoGroup: 'club:tactics', icon: '🧠' },
                { label: 'Staff', page: 'staff' },
                { label: 'Finances', page: 'finances' },
                { label: 'Transfers', page: 'transfers' },
                { label: 'Training', gotoGroup: 'club:training', icon: '⚙️' },
                { label: 'Medical', page: 'medicalCenter' },
                { label: 'Profile', page: 'profile' },
                { label: 'Stadium', page: 'stadium' },
            ];
        }
        if (group === 'league') {
            return [
                { icon: '&#9664;', label: 'Back', navBack: true },
                { label: 'Table', page: 'leagueTable' },
                { label: 'Schedule', page: 'leagueSchedule' },
                { label: 'Matches', page: 'leagueMatches' },
                { label: 'Top Scorers', page: 'topScorers' },
                { label: 'Top Assists', page: 'topAssists' },
            ];
        }
        if (group === 'community') {
            return [
                { icon: '&#9664;', label: 'Back', navBack: true },
                { label: 'Forum', page: 'forum' },
                { label: 'Messages', page: 'messages' },
            ];
        }
        if (group === 'admin') {
            return [
                { icon: '&#9664;', label: 'Back', navBack: true },
                { label: 'Admin', page: 'admin' },
            ];
        }
        if (group === 'country') {
            return [
                { icon: '&#9664;', label: 'Back', navBack: true },
                { label: 'General', page: 'country', options: { tab: 'general' } },
                { label: 'Calendar', page: 'country', options: { tab: 'calendar' } },
                { label: 'Clubs', page: 'country', options: { tab: 'clubs' } },
                { label: 'Qualifying', page: 'country', options: { tab: 'qualifying' } },
                { label: 'National Team', page: 'country', options: { tab: 'senior' } },
                { label: 'U-21', page: 'country', options: { tab: 'u21' } },
            ];
        }
        if (group === 'world') {
            return [
                { icon: '&#9664;', label: 'Back', navBack: true },
                { label: 'Countries', page: 'world', options: { tab: 'countries' } },
                { label: 'Clubs', page: 'world', options: { tab: 'clubs' } },
                { label: 'Competitions', page: 'world', options: { tab: 'competitions' } },
                { label: 'Friendlies', page: 'world', options: { tab: 'friendlies' } },
            ];
        }
        if (group === 'search') {
            return [
                { icon: '&#9664;', label: 'Back', navBack: true },
                { label: 'Search', page: 'search' },
            ];
        }
        if (group === 'user') {
            return [
                { icon: '&#9664;', label: 'Back', navBack: true },
                { label: 'My profile', onclick: 'window.loadUserProfile()' },
                { label: 'User Settings', page: 'settings' },
                { label: 'Sign out', onclick: 'window.logout()' },
            ];
        }
        return [
            { label: 'Sports Lobby', onclick: "window.location.href='/home.html'" },
            { label: 'Club', page: 'clubProfile' },
            { label: 'League', page: 'leagueTable' },
            { label: 'Country', page: 'country' },
            { label: 'World', page: 'world' },
            { label: 'Community', page: 'forum' },
            { label: 'Search', page: 'search' },
            { label: 'Notifications', onclick: 'window.openNotificationPanel(event)', stopsClick: true },
            { label: 'Admin', page: 'admin', adminOnly: true },
            { label: 'User Settings', page: 'settings' },
        ];
    }


    function openNotificationPanel(event) {
        // The dropdown closes itself on any document click outside the bell, so a nav entry that opens
        // it has to stop the very click that opened it or the panel opens and shuts in the same tick.
        if (event && typeof event.stopPropagation === 'function') {
            event.stopPropagation();
        }
        if (typeof window.toggleNotificationDropdown === 'function') {
            void window.toggleNotificationDropdown();
            return;
        }
        document.querySelector('#notification-bell')?.click();
    }

    // Desktop bottom navigation keeps its own return stack. The app history stack is shared with
    // match/route replay and does not represent a menu drill-down reliably.
    const desktopNavReturnStack = [];
    let desktopNavOverrideGroup = null;
    function desktopNavGo(page, options = {}) {
        desktopNavOverrideGroup = null;
        const from = typeof currentPageId === 'string' ? currentPageId : 'dashboard';
        if (from !== page) desktopNavReturnStack.push(from);
        return loadPage(page, options);
    }
    function desktopNavBack() {
        if (desktopNavOverrideGroup) {
            desktopNavOverrideGroup = null;
            renderDesktopBottomNav(currentPageId);
            return;
        }
        const previous = desktopNavReturnStack.pop() || 'dashboard';
        desktopNavOverrideGroup = null;
        return loadPage(previous, { pushHistory: false });
    }
    function setDesktopNavGroup(group) {
        desktopNavOverrideGroup = group || null;
        renderDesktopBottomNav(currentPageId);
    }

    function renderDesktopBottomNav(activePage) {
        const icons = {
            'Back': '◀', 'Club': '⚽', 'Club profile': '🏟', 'League': '🏆', 'First team': '👥', 'Schedule': '📅', 'Juniors': '🎓',
            'Loans': '💸', 'Tactics': '🧠', 'Tactic Editor': '📋', 'Staff': '👥', 'Finances': '💰',
            'Transfers': '💼', 'Training': '⚙️', 'Reports': '📊', 'Medical': '🏥', 'Profile': '👤',
            'Club settings': '⚙️', 'Stadium': '🏟', 'Results': '📊', 'Table': '🏆', 'Schedule': '📅',
            'Matches': '⚽', 'Top Scorers': '🥅', 'Top Assists': '🎯', 'Forum': '💬', 'Messages': '✉️',
            'Country': '🇷🇸', 'World': '🌍', 'Community': '👥', 'Search': '🔍', 'Notifications': '🔔',
            'Clubs': '🏟', 'Competitions': '🏆', 'Friendlies': '🤝', 'General': '🌍', 'Calendar': '📅',
            'Countries': '🌍', 'First team': '👥', 'Juniors': '🎓',
            'Tactics setup': '🧠', 'Training Setup': '⚙️', 'Training Reports': '📊',
            'Qualifying': '🎯', 'National Team': '🇷🇸', 'U-21': '👶',
            'Admin': '🔧', 'My profile': '👤', 'Settings': '⚙️', 'User Settings': '⚙️', 'Sports Lobby': '🏠', 'Club settings': '⚙️',
            'Sign out': '🚪'
        };
        const countryFlag = countryFlagEmojiFromIso(window.fmCountryIsoCode || currentUserCountryIsoCode) || '🌍';
        icons.Country = countryFlag;
        icons['National Team'] = countryFlag;
        const grid = document.getElementById('desktop-bottom-nav-grid');
        if (!grid) return;
        const group = desktopNavOverrideGroup || desktopBottomNavGroup(activePage || (typeof currentPageId === 'string' ? currentPageId : 'dashboard'));
        const items = desktopBottomNavItems(group);
        const isAdmin = typeof isAdminUser === 'function' ? isAdminUser() : false;
        grid.innerHTML = items
            .filter(item => !item.adminOnly || isAdmin)
            .map(item => {
                const optionsLiteral = item.options
                    ? `, ${JSON.stringify(item.options).replace(/"/g, '&quot;')}`
                    : '';
                const onclick = item.navBack
                    ? 'window.desktopNavBack()'
                    : (item.gotoGroup
                        ? `window.setDesktopNavGroup('${item.gotoGroup}')`
                        : (item.isExternal
                            ? `window.location.href = "${item.isExternal}"`
                            : (item.page
                                ? `window.desktopNavGo('${item.page}'${optionsLiteral})`
                                : (item.onclick
                                ? `${item.stopsClick ? 'event.preventDefault(); event.stopPropagation(); ' : ''}${item.onclick}`
                                : ''))));
                const icon = item.icon || icons[item.label] || '•';
                return `<button type="button" onclick="${onclick}">
                    <span class="desktop-nav-icon">${icon}</span>
                    <span>${htmlEscape(item.label)}</span>
                </button>`;
            }).join('');
    }

    window.loadPage = loadPage;
    window.openNotificationPanel = openNotificationPanel;
    window.desktopNavGo = desktopNavGo;
    window.desktopNavBack = desktopNavBack;
    window.setDesktopNavGroup = setDesktopNavGroup;
    window.renderDesktopBottomNav = renderDesktopBottomNav;
    window.loadSettingsPage = loadSettingsPage;
    window.logout = logout;
    window.loadUserProfile = loadUserProfile;
    window.openUserProfile = openUserProfile;
    window.toggleUserMenu = toggleUserMenu;
    // dashboard.js bootstraps the session on window.load and pages.js never sees that payload, so it
    // cannot paint the account corner on its own. Exposing the painter lets dashboard.js hand over
    // the /auth/me response it already has instead of either duplicating the fetch or leaving the
    // top bar stuck on "Signed in".
    window.paintAccountMenu = paintAccountMenu;
    // Re-reads /auth/me and repaints the account corner. Exposed so that anything which changes the
    // name a manager goes by can refresh the top bar without waiting for a full page load — the top
    // bar is the first place the old name is looked at. Owner, 2026-10-14.
    window.loadUserTeamId = loadUserTeamId;
    try { renderDesktopBottomNav('dashboard'); } catch (e) { /* layout may not exist yet */ }
    initAccountMenu();
    // Signing out from anywhere must also close the account panel, or the next person at this
    // machine opens it and still sees the previous user's name in it.
    window.addEventListener('pagehide', closeUserMenu);
    window.parseMatchDate = parseMatchDate;
    window.getImageFilename = getImageFilename;
    window.loadPlayer = loadPlayer;
    window.loadMatch = loadMatch;
    window.loadFirstTeam = loadFirstTeam;
    window.loadResults = loadResults;
    window.loadJuniors = loadJuniors;
    window.loadLoans = loadLoans;
    window.loadFormations = loadFormations;
    window.loadTacticEditor = loadTacticEditor;
    window.loadStaff = loadStaff;
    window.loadFinances = loadFinances;
    window.loadTransfers = loadTransfers;
    window.loadTrainingSetup = loadTrainingSetup;
    window.loadTrainingReports = loadTrainingReports;
    window.loadTrainingReportsPage = loadTrainingReportsPage;
    window.loadClubProfile = loadClubProfile;
    // The one window export that reaches a view directly. Every other one points at a guarded wrapper.
    window.loadStadium = async (...args) => { await settleTeamId(); return stadiumView.loadStadium(...args); };
    window.loadUpcomingMatches = loadUpcomingMatches;
    window.loadFixtures = loadFixtures;
    window.renderFixtures = renderFixtures;
    window.loadFixture = loadFixture;
    window.loadLeagueTable = loadLeagueTable;
    window.loadLeagueSchedule = loadLeagueSchedule;
    window.loadLeagueMatches = loadLeagueMatches;
	    window.openCountryLeague = openCountryLeague;
    window.renderLeagueMatches = renderLeagueMatches;
    window.loadCup = loadCup;
    window.loadInternational = loadInternational;
    window.loadMessages = loadMessages;
    window.openMessageThread = (threadId) => loadPage('messageThread', { threadId });
    window.loadForum = loadForum;
    window.loadForumSection = loadForumSection;
    window.loadForumTopic = loadForumTopic;
    window.openForumTopic = (topicId) => loadPage('forumTopic', { topicId });
    window.loadPlayerStats = loadPlayerStats;
    window.loadTopScorersAndAssists = loadTopScorersAndAssists;
    window.renderPlayers = renderPlayers;
    window.renderMatches = renderMatches;
    window.renderTable = renderTable;
    window.loadLeagueTeam = loadLeagueTeam;
    window.loadLeagueTeamPlayer = loadLeagueTeamPlayer;
    window.openTeamByName = openTeamByName;
    window.openStadiumImage = openStadiumImage;
    window.showStadiumModal = showStadiumModal;
    window.goBackSmart = goBackSmart;

    // The dashboard's nav items are inline onclick handlers, so the two functions that are not part of
    // the page router's own surface are published rather than threaded through six dependency
    // objects. showMyCountry is one: the Country button means the manager's own country, and it has to
    // clear the World page's selection to mean it.
    window.showMyCountry = showMyCountry;
    window.setSelectedCountry = setSelectedCountry;
