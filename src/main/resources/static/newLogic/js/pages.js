// pages.js
import { authFetch, handleAuthFailure } from './auth.js';
import { renderPlayersView, renderMatchesView, renderTableView, renderFixturesView, renderLeagueMatchesView, renderLeagueScheduleView, buildSquadTableHtml, bindSquadRowClicks, buildClubActionsHtml, buildTrainingActionsHtml, buildCommunityActionsHtml } from './pages-renderers.js';
import { createAcademyFeature } from './pages/features/academy.js';
import { createTeamFeature } from './pages/features/team.js';
import { createMatchesFeature } from './pages/features/matches.js';
import { createClubManagementFeature } from './pages/features/club-management.js';
import { createCommunityFeature } from './pages/features/community.js';
import {
    htmlEscape, formatBudget, formatGoalDiff, buildEmptyState,
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
            currentUserTeamId = user.teamId;
            currentUserTeamName = user.teamName || '';
	            currentUsername = user.username || '';
	            currentUserRole = user.role || '';
	            currentUserTeamHumanControlled = typeof user.teamHumanControlled === 'boolean' ? user.teamHumanControlled : null;
	        currentUserCompetitionId = user.competitionId ?? null;
	        currentUserCompetitionName = user.competitionName || '';
	        currentUserCompetitionTier = user.competitionTier ?? null;
	        currentSeasonYear = user.seasonYear ?? null;
	            currentUserCountryName = user.countryName || '';
	            currentUserCountryIsoCode = user.countryIsoCode || '';
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
		    return normalizeLeagueId(activeLeagueId) || normalizeLeagueId(currentUserCompetitionId) || 1;
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
        authFetch, getTeamId: () => currentUserTeamId, goBackSmart
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
        matchesFeature,
        renderFixturesView, renderMatches: (...args) => renderMatches(...args)
    });
    const countryView = createCountryView({
        authFetch,
        loadPage: (...args) => loadPage(...args),
        setActiveLeagueContext,
        getCurrentUserCountryIsoCode: () => currentUserCountryIsoCode,
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
        loadLeagueTeamPlayer: (...args) => leagueView.loadLeagueTeamPlayer(...args)
    });
    const clubView = createClubView({
        authFetch, getTeamId: () => currentUserTeamId, buildClubActionsHtml
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
                    await loadTrainingReports();
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

	                case "country":
	                    await loadCountryPage();
	                    break;

	                case "nationalTeam":
	                    await loadNationalTeamPlaceholder('senior');
	                    break;

	                case "u21Team":
	                    await loadNationalTeamPlaceholder('u21');
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
                    window.location.href = '/newLogic/zox-match-preview.html';
                    return;

                default:
                    mainContent.innerHTML = buildEmptyState("Page not found");
            }

        } catch (err) {
            console.error(err);
            mainContent.innerHTML = buildEmptyState("API Error");
        }
    }
    function escapeHtml(value) {
        return String(value ?? "")
            .replace(/&/g, "&amp;")
            .replace(/</g, "&lt;")
            .replace(/>/g, "&gt;")
            .replace(/\"/g, "&quot;")
            .replace(/'/g, "&#39;");
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

    async function loadCountryPage() {
        return countryView.loadCountryPage();
    }

    async function loadNationalTeamPlaceholder(level = 'senior') {
        return countryView.loadNationalTeamPlaceholder(level);
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
        window.location.href = '/newLogic/zox-match-preview.html';
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
    window.loadPage = loadPage;
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
    window.loadTrainingSetup = loadTrainingReports;
    window.loadTrainingReports = loadTrainingReports;
    window.loadTrainingReportsPage = loadTrainingReportsPage;
    window.loadClubProfile = loadClubProfile;
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






















