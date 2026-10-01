// pages.js
import { escapeHtml } from './ui/escape.js';
import { authFetch, handleAuthFailure } from './auth.js';
import { renderPlayersView, renderMatchesView, renderTableView, renderFixturesView, renderLeagueMatchesView, renderLeagueScheduleView, buildSquadTableHtml, bindSquadRowClicks, buildClubActionsHtml, buildTrainingActionsHtml, buildLeagueActionsHtml, buildCommunityActionsHtml } from './pages-renderers.js';
import { createAcademyFeature } from './pages/features/academy.js';
import { createTeamFeature } from './pages/features/team.js';
import { createMatchesFeature } from './pages/features/matches.js';
import { createClubManagementFeature } from './pages/features/club-management.js';
import { createCommunityFeature } from './pages/features/community.js';
import {
    htmlEscape, formatBudget, formatGoalDiff, buildEmptyState, formatPercent,
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
import { createCountryView } from './pages/views/country-view.js';
import { createStatsView } from './pages/views/stats-view.js';
import { createClubView } from './pages/views/club-view.js';
import { createStadiumView } from './pages/views/stadium-view.js';
import { createAdminView } from './pages/views/admin-view.js';
import {
    logout, paintAccountMenu, toggleUserMenu, closeUserMenu, initAccountMenu, renderUserProfile
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

	    function buildPageNavState(page) {
	        if (!isLeaguePage(page)) return { type: 'page', page };
	        return {
	            type: 'page',
	            page,
	            preserveLeagueContext: true,
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
	            await loadPage(state.page, { pushHistory: false });
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
        if (fallback === 'dashboard') {
            currentPageId = 'dashboard';
            currentNavState = { type: 'dashboard' };
            if (typeof window.loadDashboard === 'function') window.loadDashboard();
            return;
        }
	        await loadPage(fallback, { pushHistory: false, preserveLeagueContext: isLeaguePage(fallback) });
        } finally {
            navBusy = false;
        }
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
            console.log("Team ID loaded:", currentUserTeamId, "League:", currentUserCompetitionName || currentUserCompetitionId);
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
                console.log(`Back clicked -> loading: ${target}`);
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
    const communityFeature = createCommunityFeature({
        authFetch,
        getTeamId: () => currentUserTeamId,
        getTeamName: () => currentUserTeamName,
        getUsername: () => currentUsername,
        getUserRole: () => currentUserRole,
        escapeHtml,
        formatDateTimeLabel,
        buildCommunityActionsHtml,
        loadLeagueTeam: (...args) => loadLeagueTeam(...args),
    });

    const matchView = createMatchView({
        authFetch, getTeamId: () => currentUserTeamId, goBackSmart,
        getLeagueNavState: getActiveLeagueNavState,
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
        loadPage: (...args) => loadPage(...args),
        setActiveLeagueContext,
        getCurrentUserCountryIsoCode: () => resolveCountryIsoCode(),
        getActiveLeagueCountryIsoCode: () => activeLeagueCountryIsoCode,
        getCurrentUserCountryName: () => currentUserCountryName,
        getSeasonYear: () => currentSeasonYear,
        buildClubActionsHtml
    });
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
	        if (pushHistory) pushNavState(buildPageNavState(page));
        try {

            switch(page) {

                // TEAM
                case "firstTeam":
                    await loadFirstTeam();
                    break;

                case "juniors":
                    await loadJuniors();
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

                case "coaches":
                    await loadCoaches();
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

                // MATCHES
                case "upcoming":
                    await loadUpcomingMatches();
                    break;

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

                case "friendlies":
                    await loadFriendlies();
                    break;

	                case "world":
	                    await loadWorldPage();
	                    break;

	                case "country":
                    // Options passed through: the world page marks a simulated country so the page
                    // can say what that means instead of showing an empty divisions table that
                    // reads as a broken page.
                    await loadCountryPage(options);
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
                case "forum":
                    await loadForum();
                    break;

                case "chat":
                    await loadChat();
                    break;

                case "events":
                    await loadEvents();
                    break;

                // ADMIN (menu entry is role-gated; the view guards itself too)
                case "admin":
                    await adminView.loadAdmin();
                    break;

                // STATS
                case "playerStats":
                    await loadTopScorersAndAssists("scorers");
                    break;

                case "teamStats":
                    await loadTopScorersAndAssists("assists");
                    break;

                case "topScorers":
                    await loadTopScorersAndAssists("scorers");
                    break;

                case "topAssists":
                    await loadTopScorersAndAssists("assists");
                    break;

                case "analytics":
                    window.location.href = '/zox-match-preview.html';
                    return;

                case "userProfile":
                    return loadUserProfile();

                case "stadium":
                    return stadiumView.loadStadium();

                default:
                    mainContent.innerHTML = buildEmptyState("Page not found");
            }

        } catch (err) {
            console.error(err);
            mainContent.innerHTML = buildEmptyState("API Error");
        }
    }
    // --- Thin wrapper functions that delegate to view modules ---

    async function loadPlayer(playerId, callerPage, options = {}) {
        playerView.setCallerPage(callerPage);
        return playerView.loadPlayer(playerId, callerPage, options);
    }

    async function loadMatch(matchId, caller, options = {}) {
        return matchView.loadMatch(matchId, caller, options);
    }

    async function loadFormations() {
        return formationsView.loadFormations();
    }

    async function loadTacticEditor() {
        return tacticEditorView.loadTacticEditor();
    }

    async function loadTrainingReports() {
        return trainingView.loadTrainingReports();
    }

    async function loadTrainingSetup() {
        return trainingView.loadTrainingSetup();
    }

    async function loadTrainingReportsPage() {
        return trainingView.loadTrainingReportsPage();
    }

    async function loadMedicalCenter() {
        return medicalView.loadMedicalCenter();
    }

    async function loadClubProfile() {
        return clubView.loadClubProfile();
    }

    async function loadUpcomingMatches() {
        return fixtureView.loadUpcomingMatches();
    }

    async function loadFixtures() {
        return fixtureView.loadFixtures();
    }

    async function loadFixture(fixtureId, options = {}) {
        return fixtureView.loadFixture(fixtureId, options);
    }

    async function loadFriendlies() {
        return fixtureView.loadFriendlies();
    }

    async function loadLeagueTable(seasonYear = null) {
        return leagueView.loadLeagueTable(seasonYear);
    }

    async function loadLeagueSchedule(seasonYear = null) {
        return leagueView.loadLeagueSchedule(seasonYear);
    }

    async function loadLeagueMatches(seasonYear = null) {
        return leagueView.loadLeagueMatches(seasonYear);
    }

    async function loadLeagueTeam(teamId, teamName, options = {}) {
        return leagueView.loadLeagueTeam(teamId, teamName, options);
    }

    async function loadLeagueTeamPlayer(playerId, teamId, teamName, options = {}) {
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
    function clubCupRow(name, cups) {
        const found = Array.isArray(cups) ? cups.find(cup => cup && cup.name === name) : null;
        if (!found) {
            return `<button type="button" class="fm-competition" disabled>
                        <span class="fm-competition-name">${escapeHtml(name)}</span>
                        <span class="fm-badge">Unavailable</span>
                    </button>`;
        }
        const qualified = Number(found.qualified || 0);
        return `<div class="fm-competition is-real">
                    <span class="fm-competition-name">${escapeHtml(name)}</span>
                    <span class="fm-badge ${qualified > 0 ? 'fm-badge--ok' : ''}">${
                        qualified > 0 ? `${qualified} qualified` : 'No club has finished a season'
                    }</span>
                </div>`;
    }

    async function loadWorldPage() {
        const mainContent = document.getElementById('main-content');
        try {
            const res = await authFetch('/countries/world');
            if (!res.ok) throw new Error('World unavailable');
            const world = await res.json();
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
                            <div class="fm-country-fact"><dt>Countries</dt><dd>${world.totalCountries}</dd></div>
                            <div class="fm-country-fact"><dt>Active</dt><dd>${world.activeCountries}</dd></div>
                            <div class="fm-country-fact"><dt>Registered</dt><dd>${world.registeredPlayers ?? '-'}</dd></div>
                            <div class="fm-country-fact"><dt>Online now</dt><dd>${world.onlinePlayers ?? '-'}</dd></div>
                            <div class="fm-country-fact"><dt>Starting rating</dt><dd>${world.startRating}</dd></div>
                        </dl>
                    </header>

                    ${!world.complete ? `<div class="fm-callout fm-callout--warn">
                        The world holds ${countries.length} of ${world.expectedCountries} countries.
                        Admin &rarr; World integrity will rebuild the missing ones.
                    </div>` : ''}

                    <section class="fm-panel">
                        <div class="fm-panel-head">
                            <h3>General</h3>
                        </div>
                        <p class="fm-hint">Every country starts on ${world.startRating} and earns its rating
                            from results. This world is not a replica of the real one.
                            Online means a request in the last ${world.onlineWindowMinutes ?? 5} minutes &mdash;
                            registered players is every account that has ever signed in.</p>
                    </section>

                    <section class="fm-panel">
                        <div class="fm-panel-head">
                            <h3>International competitions</h3>
                        </div>
                        <div class="fm-world-competitions">
                            ${clubCupRow('Champions Cup', world.clubCups)}
                            ${clubCupRow('Masters Cup', world.clubCups)}
                            ${clubCupRow('Challenge Cup', world.clubCups)}
                            <button type="button" class="fm-competition" disabled>
                                <span class="fm-competition-name">NT Qualifiers</span>
                                <span class="fm-badge">Not created yet</span>
                            </button>
                            <button type="button" class="fm-competition" disabled>
                                <span class="fm-competition-name">World Cup</span>
                                <span class="fm-badge">Not created yet</span>
                            </button>
                            <button type="button" class="fm-competition" disabled>
                                <span class="fm-competition-name">U-21 Qualifiers</span>
                                <span class="fm-badge">Not created yet</span>
                            </button>
                            <button type="button" class="fm-competition" disabled>
                                <span class="fm-competition-name">U-21 World Cup</span>
                                <span class="fm-badge">Not created yet</span>
                            </button>
                        </div>
                        <p class="fm-hint">These competitions are not built yet. They are listed here so the
                            shape of the world is visible, and each one turns into a link when it is created.</p>
                    </section>

                    <section class="fm-panel">
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
                </div>`;

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

            // The row is the link now, not the button inside it, so the keyboard case has to be handled
            // here: a <tr> with role="link" and tabindex does not get Enter or Space for free, and a row
            // that looks clickable but cannot be reached from the keyboard is a worse regression than the
            // button-in-a-name it replaced.
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
        return countryView.loadCountryPage(options);
    }

    async function loadNationalTeam(level = 'senior') {
        return countryView.loadNationalTeamPage(level);
    }

    async function loadTopScorersAndAssists(mode = "both") {
        return statsView.loadTopScorersAndAssists(mode);
    }

    async function loadPlayerStats() {
        return statsView.loadPlayerStats();
    }

    async function loadTeamStats() {
        return statsView.loadTeamStats();
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
        renderTableView(table, { loadLeagueTeam, loadLeagueTeamPlayer, loadLeagueTable, loadMatch, loadFixture, escapeHtml: htmlEscape, formatGoalDiff });
    }

    async function loadCup() {
        console.log(`Loading cup matches for ${currentUserTeamId}`);
        const response = await authFetch(`/demo/cups/${currentUserTeamId}`);
        console.log(`Response status: ${response.status}`);
        const matches = await response.json();
        renderMatches(matches, "Cup");
    }

    async function loadInternational() {
        console.log(`Loading international matches for ${currentUserTeamId}`);
        const response = await authFetch(`/demo/internationals/${currentUserTeamId}`);
        console.log(`Response status: ${response.status}`);
        const matches = await response.json();
        renderMatches(matches, "International Matches");
    }

    async function loadForum() {
        return communityFeature.loadForum();
    }

    async function loadChat() {
        return communityFeature.loadChat();
    }

    async function loadEvents() {
        return communityFeature.loadEvents();
    }

    async function loadAnalytics() {
        window.location.href = '/zox-match-preview.html';
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
    async function loadStaff() {
        return clubManagementFeature.loadStaff();
    }
    async function loadCoaches() {
        return clubManagementFeature.loadCoaches();
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
            renderUserProfile(await res.json(), htmlEscape, formatBudget);
        } catch (err) {
            document.getElementById('main-content').innerHTML =
                buildEmptyState('Your account could not be loaded. ' + (err && err.message ? err.message : ''));
        }
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
    window.loadPage = loadPage;
    window.logout = logout;
    window.loadUserProfile = loadUserProfile;
    window.toggleUserMenu = toggleUserMenu;
    // dashboard.js bootstraps the session on window.load and pages.js never sees that payload, so it
    // cannot paint the account corner on its own. Exposing the painter lets dashboard.js hand over
    // the /auth/me response it already has instead of either duplicating the fetch or leaving the
    // top bar stuck on "Signed in".
    window.paintAccountMenu = paintAccountMenu;
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
    window.loadFormations = loadFormations;
    window.loadTacticEditor = loadTacticEditor;
    window.loadStaff = loadStaff;
    window.loadFinances = loadFinances;
    window.loadTransfers = loadTransfers;
    window.loadCoaches = loadCoaches;
    window.loadTrainingSetup = loadTrainingSetup;
    window.loadTrainingReports = loadTrainingReports;
    window.loadTrainingReportsPage = loadTrainingReportsPage;
    window.loadClubProfile = loadClubProfile;
    window.loadStadium = (...args) => stadiumView.loadStadium(...args);
    window.loadUpcomingMatches = loadUpcomingMatches;
    window.loadFixtures = loadFixtures;
    window.renderFixtures = renderFixtures;
    window.loadFixture = loadFixture;
    window.loadFriendlies = loadFriendlies;
    window.loadLeagueTable = loadLeagueTable;
    window.loadLeagueSchedule = loadLeagueSchedule;
    window.loadLeagueMatches = loadLeagueMatches;
	    window.openCountryLeague = openCountryLeague;
    window.renderLeagueMatches = renderLeagueMatches;
    window.loadCup = loadCup;
    window.loadInternational = loadInternational;
    window.loadForum = loadForum;
    window.loadChat = loadChat;
    window.loadEvents = loadEvents;
    window.loadPlayerStats = loadPlayerStats;
    window.loadTeamStats = loadTeamStats;
    window.loadTopScorersAndAssists = loadTopScorersAndAssists;
    window.loadAnalytics = loadAnalytics;
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
