// pages.js
import { authFetch } from './auth.js';
import { renderPlayersView, renderMatchesView, renderTableView, renderFixturesView, renderLeagueMatchesView, renderLeagueScheduleView, buildSquadTableHtml, bindSquadRowClicks, buildClubActionsHtml, buildTrainingActionsHtml, buildCommunityActionsHtml } from './pages-renderers.js';
import { createAcademyFeature } from './pages/features/academy.js';
import { createTeamFeature } from './pages/features/team.js';
import { createMatchesFeature } from './pages/features/matches.js';
import { createClubManagementFeature } from './pages/features/club-management.js';
import { createCommunityFeature } from './pages/features/community.js';
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

	    function normalizeLeagueId(value) {
	        const numeric = Number(value);
	        return Number.isFinite(numeric) && numeric > 0 ? numeric : null;
	    }

	    function isLeaguePage(page) {
	        return page === 'leagueTable' || page === 'leagueSchedule' || page === 'leagueMatches';
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
            localStorage.removeItem('token');
            window.location.href = '/login.html';
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

    function buildEmptyState(message) {
        return `<div class="manager-card" style="text-align:center; padding:40px;">
                    <h2>${message}</h2>
                </div>`;
    }

	    function formatSeasonShortLabel(seasonYear) {
	        const startYear = Number(seasonYear);
	        if (!Number.isFinite(startYear)) return 'Current season';
	        return `${startYear}/${String((startYear + 1) % 100).padStart(2, '0')}`;
	    }

		    const alpha3ToAlpha2CountryCode = {
		        SRB: 'RS',
		        BIH: 'BA',
		        MNE: 'ME',
		        HRV: 'HR',
		        SVN: 'SI',
		        MKD: 'MK',
		        DEU: 'DE',
		        GBR: 'GB',
		        BRA: 'BR'
		    };

		    function countryFlagEmojiFromIso(isoCode) {
		        const normalized = String(isoCode || '').trim().toUpperCase();
		        const alpha2 = /^[A-Z]{2}$/.test(normalized)
		            ? normalized
		            : alpha3ToAlpha2CountryCode[normalized] || '';
		        if (!/^[A-Z]{2}$/.test(alpha2)) return '';
		        return Array.from(alpha2)
		            .map(letter => String.fromCodePoint(127397 + letter.charCodeAt(0)))
		            .join('');
		    }

		    function getCountryFlagImagePath(country) {
		        const explicitPath = String(country?.flagImagePath || '').trim();
		        if (explicitPath) return explicitPath;
		        return String(country?.isoCode || '').trim().toUpperCase() === 'SRB'
		            ? '/images/serbiaflag.png'
		            : '';
		    }

		    function buildCountryFlagBadgeHtml(country, countryName) {
		        const imagePath = getCountryFlagImagePath(country);
		        if (imagePath) {
		            return `<div class="fm-country-badge fm-country-badge--image"><img src="${escapeHtml(imagePath)}" alt="${escapeHtml(countryName)} flag"></div>`;
		        }
		        const flagEmoji = countryFlagEmojiFromIso(country?.isoCode);
		        return `<div class="fm-country-badge">${flagEmoji || '🌍'}</div>`;
		    }

	    function sortCountryLeagues(leagues) {
	        return [...(Array.isArray(leagues) ? leagues : [])].sort((left, right) => {
	            const tierDiff = Number(left?.tier || 999) - Number(right?.tier || 999);
	            if (tierDiff !== 0) return tierDiff;
	            const divisionDiff = Number(left?.divisionLevel || 999) - Number(right?.divisionLevel || 999);
	            if (divisionDiff !== 0) return divisionDiff;
	            return String(left?.name || '').localeCompare(String(right?.name || ''), undefined, { sensitivity: 'base' });
	        });
	    }

	    function buildLeagueMetaLabel(league) {
	        const bits = [];
	        const tier = Number(league?.tier);
	        const divisionLevel = Number(league?.divisionLevel);
	        if (Number.isFinite(tier)) bits.push(`Tier ${tier}`);
	        if (Number.isFinite(divisionLevel) && divisionLevel > 1) bits.push(`Division ${divisionLevel}`);
	        return bits.join(' · ') || 'League';
	    }

	    async function openCountryLeague(leagueId, leagueName) {
	        setActiveLeagueContext({
	            leagueId,
	            leagueName,
	            countryIsoCode: currentUserCountryIsoCode || activeLeagueCountryIsoCode || '',
	            backTarget: 'country'
	        });
	        await loadPage('leagueTable', { preserveLeagueContext: true });
	    }

	    async function loadCountryPage() {
	        const mainContent = document.getElementById('main-content');
	        if (!currentUserCountryIsoCode) {
	            mainContent.innerHTML = buildEmptyState('Country data is not available for this manager yet.');
	            return;
	        }

	        try {
	            const countryIso = String(currentUserCountryIsoCode).toUpperCase();
	            const [countriesResponse, leaguesResponse] = await Promise.all([
	                authFetch('/countries'),
	                authFetch(`/countries/${encodeURIComponent(countryIso)}/leagues`)
	            ]);

	            if (!leaguesResponse.ok) throw new Error(`Country leagues load failed: ${leaguesResponse.status}`);

	            const countries = countriesResponse.ok ? await countriesResponse.json() : [];
	            const leagues = await leaguesResponse.json();
	            const sortedLeagues = sortCountryLeagues(leagues);
	            const quickLeagues = sortedLeagues.slice(0, 2);
	            const country = (Array.isArray(countries) ? countries : []).find(item => String(item?.isoCode || '').toUpperCase() === countryIso) || {
	                name: currentUserCountryName || countryIso,
	                isoCode: countryIso,
	                flagImagePath: '',
	                currencyCode: '',
	                reputation: null,
	                youthRating: null,
	                seniorNationalTeam: null,
	                u21NationalTeam: null
	            };
		            const countryName = country?.name || currentUserCountryName || countryIso;
		            const countryTitle = escapeHtml(countryName);
		            const countryBadgeHtml = buildCountryFlagBadgeHtml(country, countryName);

	            mainContent.innerHTML = `
	                <div class="fm-page fm-page--country">
	                    <div class="fm-page-toolbar">
	                        <button class="back-to-dashboard" data-nav-back="dashboard">Back</button>
	                        <div class="fm-page-title-block">
	                            <div class="fm-eyebrow">Country overview</div>
		                            <h2>${countryTitle}</h2>
			                            <div class="fm-subtle">Browse your federation, jump into any league table, and keep the main League button tied to your club context.</div>
	                        </div>
	                    </div>

	                    <div class="fm-grid-top fm-grid-top--country">
	                        <section class="fm-panel fm-country-hero">
	                            <div class="fm-country-hero-main">
	                                ${countryBadgeHtml}
	                                <div class="fm-country-meta">
	                                    <div class="fm-eyebrow">Federation</div>
		                                    <h3>${countryTitle}</h3>
	                                    <div class="fm-subtle">ISO ${escapeHtml(country?.isoCode || countryIso)}${country?.currencyCode ? ` · Currency ${escapeHtml(country.currencyCode)}` : ''}</div>
	                                    <div class="fm-country-note">Season ${escapeHtml(formatSeasonShortLabel(currentSeasonYear))}</div>
	                                </div>
	                            </div>
	                            <div class="fm-medical-stat-grid team-summary-grid fm-country-stat-grid">
	                                <div><strong>${country?.reputation ?? '—'}</strong><span>Reputation</span></div>
	                                <div><strong>${country?.youthRating ?? '—'}</strong><span>Youth rating</span></div>
	                                <div><strong>${sortedLeagues.length}</strong><span>Leagues</span></div>
	                                <div><strong>${escapeHtml(country?.currencyCode || '—')}</strong><span>Currency</span></div>
	                            </div>
	                        </section>

	                        <section class="fm-panel">
	                            <div class="fm-panel-head">
	                                <div>
	                                    <h3>Quick leagues</h3>
		                                    <p class="fm-subtle">Fast access for the top levels, plus a dropdown for the full league list.</p>
	                                </div>
	                                <span class="fm-panel-action">Country browse</span>
	                            </div>
	                            <div class="fm-country-league-grid">
	                                ${quickLeagues.map(league => `
	                                    <article class="fm-country-league-card">
	                                        <div class="fm-milestone-kicker">${escapeHtml(buildLeagueMetaLabel(league))}</div>
	                                        <div class="fm-update-title">${escapeHtml(league?.name || 'League')}</div>
	                                        <div class="fm-update-meta">Open the same standings/fixtures/scorers shell used for your main league view.</div>
	                                        <button type="button" class="fm-action-btn secondary fm-country-card-action" data-country-league-id="${league?.id || ''}" data-country-league-name="${escapeHtml(league?.name || 'League')}">Open table</button>
	                                    </article>`).join('') || `<div class="fm-empty">No leagues found for this country yet.</div>`}
	                            </div>
	                            ${sortedLeagues.length ? `
	                                <div class="fm-country-select-row">
	                                    <label class="fm-season-select-wrap fm-country-select-control">
	                                        <span>All leagues</span>
	                                        <select id="country-league-select" class="fm-season-select">
	                                            ${sortedLeagues.map(league => `<option value="${league?.id || ''}" data-league-name="${escapeHtml(league?.name || 'League')}">${escapeHtml(league?.name || 'League')} · ${escapeHtml(buildLeagueMetaLabel(league))}</option>`).join('')}
	                                        </select>
	                                    </label>
	                                    <button type="button" id="country-open-selected-league" class="fm-action-btn">Open selected league</button>
	                                </div>` : ''}
	                        </section>
	                    </div>

		                    <div class="fm-grid-bottom fm-grid-bottom--single">
	                        <section class="fm-panel">
	                            <div class="fm-panel-head">
	                                <div>
	                                    <h3>National teams</h3>
	                                    <p class="fm-subtle">Navigation placeholders are ready now; backend data can be connected later.</p>
	                                </div>
	                                <span class="fm-panel-action">Placeholder</span>
	                            </div>
	                            <div class="fm-country-team-grid">
	                                <article class="fm-country-team-card">
	                                    <div class="fm-milestone-kicker">Senior</div>
	                                    <div class="fm-update-title">${escapeHtml(country?.seniorNationalTeam?.name || `${country?.name || currentUserCountryName || 'Country'} National Team`)}</div>
	                                    <div class="fm-update-meta">Top-level squad hub placeholder.</div>
	                                    <button type="button" class="fm-action-btn secondary fm-country-card-action" data-country-placeholder="nationalTeam">Open</button>
	                                </article>
	                                <article class="fm-country-team-card">
	                                    <div class="fm-milestone-kicker">U-21</div>
	                                    <div class="fm-update-title">${escapeHtml(country?.u21NationalTeam?.name || `${country?.name || currentUserCountryName || 'Country'} U-21`)}</div>
	                                    <div class="fm-update-meta">Youth national setup placeholder.</div>
	                                    <button type="button" class="fm-action-btn secondary fm-country-card-action" data-country-placeholder="u21Team">Open</button>
	                                </article>
	                            </div>
	                        </section>
	                    </div>
	                </div>`;

	            mainContent.querySelectorAll('[data-country-league-id]').forEach(button => {
	                button.addEventListener('click', () => {
	                    openCountryLeague(Number(button.dataset.countryLeagueId), button.dataset.countryLeagueName || 'League');
	                });
	            });

	            const countryLeagueSelect = document.getElementById('country-league-select');
	            const openSelectedLeagueButton = document.getElementById('country-open-selected-league');
	            if (countryLeagueSelect && openSelectedLeagueButton) {
	                openSelectedLeagueButton.addEventListener('click', () => {
	                    const selectedOption = countryLeagueSelect.options[countryLeagueSelect.selectedIndex];
	                    const selectedLeagueId = Number(countryLeagueSelect.value);
	                    if (!selectedLeagueId) return;
	                    openCountryLeague(selectedLeagueId, selectedOption?.dataset?.leagueName || selectedOption?.textContent || 'League');
	                });
	            }

	            mainContent.querySelectorAll('[data-country-placeholder]').forEach(button => {
	                button.addEventListener('click', () => loadPage(button.dataset.countryPlaceholder || 'nationalTeam'));
	            });
	        } catch (err) {
	            console.error('Failed to load country page:', err);
	            mainContent.innerHTML = `
	                <div class="manager-card">
	                    <button class="back-to-dashboard" data-nav-back="dashboard">Back</button>
	                    <h2>Error</h2>
	                    <p>Could not load your country overview.</p>
	                </div>`;
	        }
	    }

	    async function loadNationalTeamPlaceholder(level = 'senior') {
	        const isU21 = level === 'u21';
	        const mainContent = document.getElementById('main-content');
	        const title = isU21
	            ? `${currentUserCountryName || 'Country'} U-21`
	            : `${currentUserCountryName || 'Country'} National Team`;
	        const currentActionPage = isU21 ? 'u21Team' : 'nationalTeam';

	        mainContent.innerHTML = `
	            <div class="fm-page fm-page--club">
	                <section class="fm-panel fm-club-hero fm-placeholder-hero">
	                    <button class="back-to-dashboard" data-nav-back="dashboard">Back</button>
	                    <div class="fm-club-hero-main">
	                        <div>
	                            <div class="fm-eyebrow">National setup</div>
	                            <h2>${escapeHtml(title)}</h2>
	                            <p class="fm-subtle">This route is intentionally a frontend placeholder for now, so the action-row buttons already have a clean destination before BE national-team payloads are wired.</p>
	                        </div>
	                        ${buildClubActionsHtml(currentActionPage)}
	                    </div>
	                    <div class="fm-medical-stat-grid team-summary-grid">
	                        <div><strong>${escapeHtml(currentUserCountryName || '—')}</strong><span>Country</span></div>
	                        <div><strong>${isU21 ? 'U-21' : 'Senior'}</strong><span>Level</span></div>
	                        <div><strong>Placeholder</strong><span>Status</span></div>
	                        <div><strong>Later</strong><span>Backend data</span></div>
	                    </div>
	                </section>
	                <section class="fm-panel">
	                    <div class="fm-empty">National-team squad, schedule, call-ups, and staff will be added here once backend endpoints are ready.</div>
	                </section>
	            </div>`;
	    }
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
                    window.location.href = '/zox-match-preview.html';
                    return;

                default:
                    mainContent.innerHTML = buildEmptyState("Page not found");
            }

        } catch (err) {
            console.error(err);
            mainContent.innerHTML = buildEmptyState("API Error");
        }
    }
    function parseMatchDate(dateArr) {
        if(Array.isArray(dateArr)) {
            const [year, month, day, hour, minute, second, nano] = dateArr;
            const ms = nano ? Math.floor(nano / 1000000) : 0; // pretvori nanosekunde u milisekunde
            return new Date(year, month - 1, day, hour, minute, second, ms);
        }
        return new Date(dateArr); // fallback za stringove ili timestamps
    }
    function getImageFilename(name) {
        return name
            .normalize("NFD")
            .replace(/[\u0300-\u036f]/g, "")
            .replace(/đ/g, "dj")
            .replace(/Đ/g, "Dj")
            .replace(/\s+/g, '_')
            .replace(/[^a-zA-Z0-9_-]/g, '');
    }
    function normalizeTeamKey(name) {
        return (name || "")
            .toLowerCase()
            .normalize("NFD")
            .replace(/[\u0300-\u036f]/g, "")
            .replace(/[^a-z0-9]/g, "");
    }
    function normalizePlayerKey(name) {
        return (name || "")
            .toLowerCase()
            .normalize("NFD")
            .replace(/[\u0300-\u036f]/g, "")
            .replace(/[^a-z0-9]/g, "");
    }
    async function openTeamByName(teamName) {
        try {
	        const leagueId = await ensureCurrentLeagueId();
	        if (!leagueId) return;
		        const seasonParam = currentLeagueSeasonYear ? `?seasonYear=${encodeURIComponent(currentLeagueSeasonYear)}` : '';
		        const res = await authFetch(`/countries/leagues/${leagueId}/teams${seasonParam}`);
            if (!res.ok) return;
            const teams = await res.json();
            const key = normalizeTeamKey(teamName);
            const found = teams.find(t => normalizeTeamKey(t.name) === key);
            if (found) {
                loadLeagueTeam(found.id, found.name);
            }
        } catch (e) {
            console.warn('Team navigation failed:', e);
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
    function formatBudget(value) {
        return `EUR ${Number(value || 0).toLocaleString()}`;
    }
    function formatMilestoneAttendanceValue(value) {
        const numeric = Number(value || 0);
        return numeric > 0 ? numeric.toLocaleString() : '—';
    }
    function buildMilestoneCardHtml(title, value, meta, extraClass = '') {
        const safeValue = value == null || value === '' ? '—' : escapeHtml(String(value));
        const safeMeta = meta == null || meta === '' ? 'No milestone logged yet.' : escapeHtml(String(meta));
        return `
            <article class="fm-milestone-card ${extraClass}">
                <div class="fm-milestone-kicker">${escapeHtml(String(title || 'Milestone'))}</div>
                <div class="fm-milestone-value">${safeValue}</div>
                <div class="fm-milestone-meta">${safeMeta}</div>
            </article>`;
    }
    function buildMilestoneBoardHtml(milestones) {
        const scorer = milestones?.topScorer || null;
        const assist = milestones?.topAssist || null;
        const biggestWin = milestones?.biggestWin || null;
        const biggestLoss = milestones?.biggestLoss || null;
        const attendance = milestones?.attendance || null;

        return `
            <div class="fm-milestone-grid">
                ${buildMilestoneCardHtml(
                    'Top scorer',
                    scorer?.playerName || '—',
                    scorer?.playerName ? `${scorer.teamName || 'No team'} · ${Number(scorer.value || 0)} goals` : 'No goals filed yet.'
                )}
                ${buildMilestoneCardHtml(
                    'Top assist',
                    assist?.playerName || '—',
                    assist?.playerName ? `${assist.teamName || 'No team'} · ${Number(assist.value || 0)} assists` : 'No assists filed yet.'
                )}
                ${buildMilestoneCardHtml(
                    'Biggest win',
                    biggestWin?.summary || '—',
                    biggestWin?.context || 'Waiting for a standout result.'
                )}
                ${buildMilestoneCardHtml(
                    'Heaviest loss',
                    biggestLoss?.summary || '—',
                    biggestLoss?.context || 'No heavy defeat registered yet.'
                )}
                ${buildMilestoneCardHtml(
                    'Attendance',
                    formatMilestoneAttendanceValue(attendance?.averageAttendance),
                    attendance?.averageAttendance
                        ? `High ${formatMilestoneAttendanceValue(attendance.highestAttendance)} (${attendance.highestMatchLabel || '—'}) · Low ${formatMilestoneAttendanceValue(attendance.lowestAttendance)} (${attendance.lowestMatchLabel || '—'}) · ${attendance.insight || ''}`
                        : (attendance?.insight || 'Crowd data will appear once played fixtures start filing gates.'),
                    'attendance'
                )}
            </div>`;
    }
    function formatDateTimeLabel(value) {
        if (!value) return '-';
        const date = new Date(value);
        if (Number.isNaN(date.getTime())) return escapeHtml(String(value));
        return `${date.toLocaleDateString()} ${date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}`;
    }
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
    function formatGoalDiff(value) {
        const number = Number(value || 0);
        return `${number > 0 ? "+" : ""}${number}`;
    }
    function getRatingColor(rating) {
        const value = Number(rating);
        if (!Number.isFinite(value)) return "#9aa0a6";
        if (value >= 7.5) return "#4caf50";
        if (value >= 6.5) return "#ffd700";
        if (value >= 5.5) return "#ff9800";
        return "#f44336";
    }
    function formatFormBadge(formValue) {
        const value = Number(formValue);
        if (!Number.isFinite(value)) return `<span class="form-badge neutral">-</span>`;
        if (value >= 7.8) return `<span class="form-badge hot">&#128293; ${value.toFixed(1)}</span>`;
        if (value <= 5.8) return `<span class="form-badge cold">&#129482; ${value.toFixed(1)}</span>`;
        return `<span class="form-badge neutral">${value.toFixed(1)}</span>`;
    }
    function formatRatingBadge(ratingValue) {
        const value = Number(ratingValue);
        if (!Number.isFinite(value)) return `<span style="color:#9aa0a6;">-</span>`;
        return `<span style="color:${getRatingColor(value)}; font-weight:700;">${value.toFixed(1)}</span>`;
    }
    function formatCompactPlayerName(value) {
        const safeName = String(value ?? '').trim();
        if (!safeName) return 'Unknown';
        const parts = safeName.split(/\s+/).filter(Boolean);
        if (parts.length <= 1) return safeName;
        return `${parts[0].charAt(0)}. ${parts[parts.length - 1]}`;
    }
    function buildRepeatedLineupBadge(count, badgeClass, icon, label) {
        const total = Math.max(0, Number(count) || 0);
        return Array.from({ length: total }, () => (
            `<span class="fm-badge fm-badge-icon ${badgeClass}" title="${label}" aria-label="${label}">${icon}</span>`
        )).join('');
    }
    function buildLineupEventBadges(player) {
        const goals = Number(player?.goals || 0);
        const assists = Number(player?.assists || 0);
        const rawYellowCards = Math.max(0, Number(player?.yellowCards || 0));
        const rawRedCards = Math.max(0, Number(player?.redCards || 0));
        let yellowCards = Math.min(rawYellowCards, 1);
        let redCards = Math.min(rawRedCards, 1);

        if (rawYellowCards >= 2 && redCards === 0) {
            yellowCards = 1;
            redCards = 1;
        }

        if (redCards > 0) {
            yellowCards = Math.min(yellowCards, 1);
        }

        const badges = [
            buildRepeatedLineupBadge(goals, 'fm-badge-goal', '⚽', 'Goal'),
            buildRepeatedLineupBadge(assists, 'fm-badge-ast', '🎯', 'Assist'),
            buildRepeatedLineupBadge(yellowCards, 'fm-badge-card-yellow', '🟨', 'Yellow card'),
            buildRepeatedLineupBadge(redCards, 'fm-badge-card-red', '🟥', 'Red card')
        ].filter(Boolean);
        return badges.length
            ? `<div class="fm-match-lineup-badges">${badges.join('')}</div>`
            : `<span class="fm-match-lineup-badges is-empty">—</span>`;
    }
    function getPendingJuniorReveal(playerId) {
        try {
            const raw = sessionStorage.getItem("junior_promotion_reveal");
            if (!raw) return null;
            const payload = JSON.parse(raw);
            if (!payload || Number(payload.playerId) !== Number(playerId)) return null;
            return payload;
        } catch (e) {
            return null;
        }
    }
    function delay(ms) {
        return new Promise(resolve => setTimeout(resolve, ms));
    }
    async function runJuniorRevealAnimation(payload) {
        if (!payload) return;
        const allocated = payload.allocatedSkills || {};
        const sequence = Array.isArray(payload.allocationSequence) ? payload.allocationSequence : [];
        const remainingEl = document.getElementById("junior-reveal-remaining");
        const statusEl = document.getElementById("junior-reveal-status");
        const idByKey = {
            stamina: "skill-stamina-val",
            goalkeeper: "skill-goalkeeper-val",
            defending: "skill-defending-val",
            pace: "skill-pace-val",
            technique: "skill-technique-val",
            playmaker: "skill-playmaker-val",
            passing: "skill-passing-val",
            shooting: "skill-shooting-val"
        };
        const current = {
            stamina: 0,
            goalkeeper: 0,
            defending: 0,
            pace: 0,
            technique: 0,
            playmaker: 0,
            passing: 0,
            shooting: 0
        };
        const resetSkillHighlight = () => {
            Object.values(idByKey).forEach(id => {
                const node = document.getElementById(id);
                if (node) node.style.color = "#ffffff";
            });
        };
        let remaining = Number(payload.totalSkillBudget || 0);
        if (remainingEl) remainingEl.textContent = String(remaining);
        if (statusEl) statusEl.textContent = "Allocating 1 point every second...";

        if (sequence.length > 0) {
            for (const skillKey of sequence) {
                await delay(1000);
                if (Object.prototype.hasOwnProperty.call(current, skillKey)) {
                    current[skillKey] += 1;
                    resetSkillHighlight();
                    const node = document.getElementById(idByKey[skillKey]);
                    if (node) {
                        node.textContent = `${current[skillKey].toFixed(2)}`;
                        node.style.color = "#6fcf97";
                    }
                }
                remaining = Math.max(0, remaining - 1);
                if (remainingEl) remainingEl.textContent = String(remaining);
            }
        } else {
            const order = ["goalkeeper", "defending", "pace", "technique", "playmaker", "passing", "shooting", "stamina"];
            for (const key of order) {
                await delay(1000);
                current[key] = Number(allocated[key] || 0);
                resetSkillHighlight();
                const node = document.getElementById(idByKey[key]);
                if (node) {
                    node.textContent = `${current[key].toFixed(2)}`;
                    node.style.color = "#6fcf97";
                }
            }
            if (remainingEl) remainingEl.textContent = String(Math.max(0, Number(payload.remainingAfterFill || 0)));
        }

        if (statusEl) statusEl.textContent = "Promotion reveal completed.";
        sessionStorage.removeItem("junior_promotion_reveal");
    }
    async function fetchPlayerRatingSummary(playerId) {
        try {
            const response = await authFetch(`/match-stats/player/${playerId}`);
            if (!response.ok) return { averageRating10: null, averageRating100: null, matchesPlayed: 0 };
            const payload = await response.json();
            return {
                averageRating10: payload.averageRating10 ?? null,
                averageRating100: payload.averageRating100 ?? null,
                matchesPlayed: payload.matchesPlayed ?? 0
            };
        } catch (err) {
            return { averageRating10: null, averageRating100: null, matchesPlayed: 0 };
        }
    }
    function formatPlayerSkill(exact, visible) {
        if (exact != null && Number.isFinite(Number(exact))) return Number(exact).toFixed(2);
        if (visible != null && Number.isFinite(Number(visible))) return Number(visible).toFixed(2);
        return "-";
    }
    function clampPercent(value) {
        const number = Number(value);
        if (!Number.isFinite(number)) return 0;
        return Math.max(0, Math.min(100, Math.round(number)));
    }
    function getPlayerConditionPercent(player) {
        const fatigue = Number(player?.fatigue);
        if (!Number.isFinite(fatigue)) return 100;
        return clampPercent(100 - fatigue);
    }
    function getPlayerPositionInfo(position) {
        const raw = String(position ?? '').trim();
        const upper = raw.toUpperCase();
        const active = new Set();

        if (/GK|GOALKEEPER/.test(upper)) active.add('GK');
        if (/(LB|DL|LWB|LEFT BACK)/.test(upper)) active.add('DL');
        if (/(RB|DR|RWB|RIGHT BACK)/.test(upper)) active.add('DR');
        if (/(CB|DC|STOPPER|DEFENDER)/.test(upper)) active.add('DC');
        if (/(DM|CDM|DMC)/.test(upper)) active.add('DM');
        if (/(CM|MC|MIDFIELDER)/.test(upper) && !/(AMC|AMR|AML|DM)/.test(upper)) active.add('MC');
        if (/(CAM|AMC|AM)/.test(upper)) active.add('AMC');
        if (/(LM|LW|AML|ML|LEFT WING)/.test(upper)) active.add('WL');
        if (/(RM|RW|AMR|MR|RIGHT WING)/.test(upper)) active.add('WR');
        if (/(ST|CF|FC|FW|STRIKER|FORWARD)/.test(upper)) active.add('ST');

        if (!active.size) {
            if (/KEEPER/.test(upper)) active.add('GK');
            else if (/BACK|DEF/.test(upper)) active.add('DC');
            else if (/WING/.test(upper)) active.add('WL');
            else if (/ATT/.test(upper)) active.add('AMC');
            else active.add('MC');
        }

        const primary = active.has('GK') ? 'GK'
            : active.has('ST') ? 'ST'
            : active.has('AMC') ? 'AMC'
            : active.has('MC') ? 'MC'
            : active.has('DM') ? 'DM'
            : active.has('DC') ? 'DC'
            : active.has('DL') ? 'DL'
            : active.has('DR') ? 'DR'
            : active.has('WL') ? 'WL'
            : active.has('WR') ? 'WR'
            : 'MC';

        return {
            raw,
            primary,
            items: [
                { key: 'GK', label: 'GK', top: '86%', left: '50%' },
                { key: 'DL', label: 'DL', top: '69%', left: '20%' },
                { key: 'DC', label: 'DC', top: '68%', left: '50%' },
                { key: 'DR', label: 'DR', top: '69%', left: '80%' },
                { key: 'DM', label: 'DM', top: '54%', left: '50%' },
                { key: 'WL', label: 'WL', top: '40%', left: '18%' },
                { key: 'MC', label: 'MC', top: '40%', left: '50%' },
                { key: 'WR', label: 'WR', top: '40%', left: '82%' },
                { key: 'AMC', label: 'AMC', top: '24%', left: '50%' },
                { key: 'ST', label: 'ST', top: '11%', left: '50%' }
            ].map(item => ({
                ...item,
                active: active.has(item.key),
                primary: item.key === primary
            }))
        };
    }
    function buildPlayerProfileHeroHtml(player, options = {}) {
        const {
            backLabel = 'Back',
            eyebrow = 'Player overview',
            teamName = 'Club squad',
            ratingSummary = {},
            backButtonId = 'player-back-button',
            backButtonAttributes = '',
            showBackButton = true,
            headerClassName = 'fm-player-header',
            bannerClassName = ''
        } = options;
        const positionInfo = getPlayerPositionInfo(player.position);
        const filename = getImageFilename(player.name || 'player');
        const conditionPercent = getPlayerConditionPercent(player);
        const averageRating = formatRatingBadge(ratingSummary.averageRating10 ?? player?.rating);
        const formBadge = formatFormBadge(player?.form);
        const injuryText = player?.injured
            ? `Injured${player.injuryDaysRemaining ? ` · ${player.injuryDaysRemaining} days` : ''}`
            : 'Available';
        const matchesPlayed = ratingSummary.matchesPlayed ?? player?.played ?? player?.matchesPlayed ?? 0;
        const outputGoals = player?.totalGoals ?? player?.goals ?? 0;
        const outputAssists = player?.totalAssists ?? player?.assists ?? 0;
        const backButtonAttrText = [
            backButtonId ? `id="${backButtonId}"` : '',
            'class="back-to-dashboard"',
            backButtonAttributes
        ].filter(Boolean).join(' ');

        return `
            <section class="${headerClassName}">
                ${showBackButton ? `<button ${backButtonAttrText}>${backLabel}</button>` : ''}
                <div class="fm-ph-banner fm-panel${bannerClassName ? ` ${bannerClassName}` : ''}">
                    <div class="fm-ph-photo">
                        <div class="fm-ph-photo-frame">
                            <img src="/images/${filename}.jpg" onerror="this.src='/images/player.jpg'" alt="${escapeHtml(player?.name || 'Player')}">
                        </div>
                    </div>
                    <div class="fm-ph-identity">
                        <div class="fm-eyebrow">${escapeHtml(eyebrow)}</div>
                        <h2>${escapeHtml(player?.name || 'Player')}</h2>
                        <div class="fm-ph-meta-row">
                            <span>${escapeHtml(teamName)}</span>
                            <span class="fm-ph-sep"></span>
                            <span>${escapeHtml(positionInfo.raw || 'Player')}</span>
                            <span class="fm-ph-sep"></span>
                            <span>${player?.age ?? '-'} years</span>
                        </div>
                        <div class="fm-ph-id-cols">
                            <div class="fm-ph-id-col">
                                <div class="fm-ph-rating-item">
                                    <span class="fm-ph-rlabel">Condition</span>
                                    <div class="fm-cond">
                                        <div class="fm-cond-bar"><div class="fm-cond-fill" style="width:${conditionPercent}%"></div></div>
                                        <span class="fm-cond-val">${conditionPercent}%</span>
                                    </div>
                                </div>
                                <div class="fm-ph-rating-item">
                                    <span class="fm-ph-rlabel">Match rating</span>
                                    <span class="fm-detail-value">${averageRating}</span>
                                </div>
                                <div class="fm-ph-rating-item">
                                    <span class="fm-ph-rlabel">Form</span>
                                    <span class="fm-detail-value">${formBadge}</span>
                                </div>
                            </div>
                            <div class="fm-ph-id-col">
                                <div class="fm-ph-rating-item">
                                    <span class="fm-ph-rlabel">Value</span>
                                    <span class="fm-detail-value">${player?.value != null ? Math.round(player.value).toLocaleString() : '-'}</span>
                                </div>
                                <div class="fm-ph-rating-item">
                                    <span class="fm-ph-rlabel">Status</span>
                                    <span class="fm-detail-value">${escapeHtml(injuryText)}</span>
                                </div>
                                <div class="fm-ph-rating-item">
                                    <span class="fm-ph-rlabel">Output</span>
                                    <span class="fm-detail-value">${outputGoals} goals · ${outputAssists} assists</span>
                                </div>
                            </div>
                            <div class="fm-ph-id-col">
                                <div class="fm-player-chip-row">
                                    <span class="fm-player-chip">OVR ${player?.overall ?? '-'}</span>
                                    <span class="fm-player-chip secondary">${escapeHtml(positionInfo.primary)}</span>
                                    <span class="fm-player-chip secondary">${matchesPlayed} matches</span>
                                </div>
                            </div>
                        </div>
                    </div>
                </div>
            </section>`;
    }

    async function fetchPlayerTransferStatus(playerId) {
        try {
            if (!await ensureUserTeamId()) return null;
            const response = await authFetch(`/transfers/player/${playerId}?viewerTeamId=${encodeURIComponent(currentUserTeamId)}`);
            return await response.json();
        } catch (err) {
            console.warn('Failed to load player transfer status:', err);
            return null;
        }
    }

    function getTransferInterestedTeams(transferStatus) {
        if (!transferStatus) return [];
        if (Array.isArray(transferStatus.interestedTeams)) return transferStatus.interestedTeams.filter(Boolean);
        return Object.values(transferStatus.interestedTeams || {}).filter(Boolean);
    }

    function formatTransferMoney(value) {
        if (value == null || !Number.isFinite(Number(value))) return '—';
        return escapeHtml(formatBudget(Math.round(Number(value))));
    }

    function buildPlayerTransferPanelHtml(player, transferStatus, options = {}) {
        const placeholderPrefix = options.placeholderPrefix || 'This tab is prepared';
        if (!transferStatus) {
            return `
                <section class="fm-panel fm-player-tab-panel" data-player-tab-panel="transfer">
                    <div class="fm-panel-head">
                        <h3>Transfer</h3>
                    </div>
                    <div class="fm-empty">${placeholderPrefix}: transfer listing / interest history will fit here when we wire the API</div>
                </section>`;
        }

        const interestedTeams = getTransferInterestedTeams(transferStatus);
        const actionButtons = [];
        const defaultValue = Math.max(1, Math.round(Number(player?.value || transferStatus.askingPrice || 1)));

        if (transferStatus.canList) {
            actionButtons.push(`<button type="button" class="fm-action-btn" data-transfer-panel-action="list" data-player-id="${player.id}" data-default-price="${defaultValue}">List player</button>`);
        }
        if (transferStatus.canRemove) {
            actionButtons.push(`<button type="button" class="fm-action-btn secondary" data-transfer-panel-action="remove" data-player-id="${player.id}">Remove from TL</button>`);
        } else if (transferStatus.ownedByViewer && transferStatus.listed) {
            actionButtons.push(`<button type="button" class="fm-action-btn secondary" disabled title="Cannot remove while another club has registered interest.">Remove from TL</button>`);
        }
        if (transferStatus.canBuyListed) {
            actionButtons.push(`<button type="button" class="fm-action-btn secondary" data-transfer-panel-action="interest" data-player-id="${player.id}">Register interest</button>`);
            actionButtons.push(`<button type="button" class="fm-action-btn" data-transfer-panel-action="buy-listed" data-player-id="${player.id}" data-default-price="${Math.max(1, Math.round(Number(transferStatus.askingPrice || defaultValue)))}">Buy listed</button>`);
        }
        if (transferStatus.canDirectBuy && !transferStatus.listed) {
	            actionButtons.push(`<button type="button" class="fm-action-btn" data-transfer-panel-action="direct-buy" data-player-id="${player.id}" data-default-price="${defaultValue}">Send offer</button>`);
        }
        if (transferStatus.canAcceptOffer) {
            actionButtons.push(`<button type="button" class="fm-action-btn" data-transfer-panel-action="accept-offer" data-player-id="${player.id}">Accept best offer</button>`);
        }
        if (transferStatus.canRejectOffer) {
            actionButtons.push(`<button type="button" class="fm-action-btn secondary" data-transfer-panel-action="reject-offers" data-player-id="${player.id}">Reject offers</button>`);
        }

        return `
            <section class="fm-panel fm-player-tab-panel" data-player-tab-panel="transfer">
                <div class="fm-panel-head">
                    <div>
                        <h3>Transfer</h3>
                        <p class="fm-subtle">${escapeHtml(transferStatus.summary || 'Current transfer status is shown here.')}</p>
                    </div>
                    <span class="fm-panel-action">${escapeHtml(transferStatus.status || (transferStatus.listed ? 'LISTED' : 'NONE'))}</span>
                </div>
                <div class="club-profile-detail-list">
                    <div class="club-profile-detail-row"><span>Current club</span><strong>${escapeHtml(transferStatus.currentTeamName || 'Unassigned')}</strong></div>
                    <div class="club-profile-detail-row"><span>Seller club</span><strong>${escapeHtml(transferStatus.sellerTeamName || transferStatus.currentTeamName || '—')}</strong></div>
                    <div class="club-profile-detail-row"><span>Asking price</span><strong>${formatTransferMoney(transferStatus.askingPrice)}</strong></div>
                    <div class="club-profile-detail-row"><span>Agreed fee</span><strong>${formatTransferMoney(transferStatus.agreedPrice)}</strong></div>
                    <div class="club-profile-detail-row"><span>Listed at</span><strong>${escapeHtml(formatDateTimeLabel(transferStatus.listedAt))}</strong></div>
                    <div class="club-profile-detail-row"><span>Completed at</span><strong>${escapeHtml(formatDateTimeLabel(transferStatus.completedAt))}</strong></div>
                </div>
                <div class="fm-empty" style="text-align:left; margin-top:16px;">
                    ${interestedTeams.length
                        ? `Interested clubs: ${escapeHtml(interestedTeams.join(', '))}`
                        : 'No bids / registered interest yet.'}
                </div>
                ${actionButtons.length ? `<div style="display:flex; flex-wrap:wrap; gap:10px; margin-top:16px;">${actionButtons.join('')}</div>` : ''}
            </section>`;
    }

    function buildTeamTransferOverviewHtml(overview, options = {}) {
        if (!overview) return '';
        const { isUserTeam = false, teamName = 'Team' } = options;
        const listedPlayers = Array.isArray(overview.listedPlayers) ? overview.listedPlayers : [];

        return `
            <section class="fm-panel" style="margin-top:18px;">
                <div class="fm-panel-head">
                    <div>
                        <h3>Transfer overview</h3>
                        <p class="fm-subtle">Quick view of ${escapeHtml(teamName)} transfer activity and listed players.</p>
                    </div>
                    <span class="fm-panel-action">Transfers</span>
                </div>
                <div class="fm-medical-stat-grid team-summary-grid" style="margin-bottom:18px;">
                    <div><strong>${formatTransferMoney(overview.budget)}</strong><span>Budget</span></div>
                    <div><strong>${listedPlayers.length}</strong><span>Listed</span></div>
                    <div><strong>${listedPlayers.reduce((sum, item) => sum + getTransferInterestedTeams(item).length, 0)}</strong><span>Interest</span></div>
                    <div><strong>${listedPlayers.length ? escapeHtml(listedPlayers[0].playerName || '—') : '—'}</strong><span>Top listing</span></div>
                </div>
                ${listedPlayers.length === 0
                    ? `<div class="fm-empty">${isUserTeam ? 'No players from your club are currently listed.' : 'No active transfer listings for this team right now.'}</div>`
                    : `<div class="fm-squad-wrap">
                        <table class="fm-squad">
                            <thead><tr><th class="sq-name">Player</th><th>Pos</th><th>Asking</th><th>Interest</th><th>Listed</th><th>Action</th></tr></thead>
                            <tbody>
                                ${listedPlayers.map(item => `
                                    <tr class="fm-squad-row">
                                        <td class="sq-name">${escapeHtml(item.playerName || 'Unknown')}</td>
                                        <td>${escapeHtml(item.position || '-')}</td>
                                        <td>${formatTransferMoney(item.askingPrice)}</td>
                                        <td>${escapeHtml(getTransferInterestedTeams(item).length ? getTransferInterestedTeams(item).join(', ') : 'No interest yet')}</td>
                                        <td>${escapeHtml(formatDateTimeLabel(item.listedAt))}</td>
                                        <td><button type="button" class="fm-action-btn secondary" data-team-transfer-player-id="${item.playerId}" data-team-transfer-team-id="${overview.teamId}" data-team-transfer-team-name="${escapeHtml(overview.teamName || teamName)}">Open player</button></td>
                                    </tr>`).join('')}
                            </tbody>
                        </table>
                    </div>`}
                ${isUserTeam ? `<div style="margin-top:16px;"><button type="button" class="fm-action-btn" onclick="loadPage('transfers')">Open Transfer Centre</button></div>` : ''}
            </section>`;
    }

    function buildPlayerProfileHtml(player, options = {}) {
        const {
            backLabel = 'Back',
            eyebrow = 'Player overview',
            teamName = 'Club squad',
            ratingSummary = {},
            transferStatus = null,
            revealPayload = null,
            placeholderPrefix = 'This tab is prepared',
            showReveal = false
        } = options;
        const revealActive = showReveal && !!revealPayload;
        const positionInfo = getPlayerPositionInfo(player.position);
        const filename = getImageFilename(player.name);
        const conditionPercent = getPlayerConditionPercent(player);
        const averageRating = formatRatingBadge(ratingSummary.averageRating10);
        const formBadge = formatFormBadge(player.form);
        const injuryText = player.injured
            ? `Injured${player.injuryDaysRemaining ? ` · ${player.injuryDaysRemaining} days` : ''}`
            : 'Available';
        const skillSections = [
            {
                title: 'Ball Skills',
                items: [
                    ['Technique', formatPlayerSkill(player.techniqueExact, player.technique), 'skill-technique-val'],
                    ['Passing', formatPlayerSkill(player.passingExact, player.passing), 'skill-passing-val'],
                    ['Shooting', formatPlayerSkill(player.shootingExact, player.shooting), 'skill-shooting-val']
                ]
            },
            {
                title: 'Athletic & Duels',
                items: [
                    ['Pace', formatPlayerSkill(player.paceExact, player.pace), 'skill-pace-val'],
                    ['Stamina', formatPlayerSkill(player.staminaExact, player.stamina), 'skill-stamina-val'],
                    ['Defending', formatPlayerSkill(player.defendingExact, player.defending), 'skill-defending-val']
                ]
            },
            {
                title: 'Role Profile',
                items: [
                    ['Playmaker', formatPlayerSkill(player.playmakerExact, player.playmaker), 'skill-playmaker-val'],
                    ['Goalkeeper', formatPlayerSkill(player.goalkeeperExact, player.goalkeeper), 'skill-goalkeeper-val'],
                    ['Overall', player.overall ?? '-', null]
                ]
            }
        ];

        const renderSkillValue = (value, id) => `<td${id ? ` id="${id}"` : ''}>${revealActive && id ? '0.00' : value}</td>`;
        const renderPlaceholder = (title, text) => `
            <section class="fm-panel fm-player-tab-panel" data-player-tab-panel="${title.toLowerCase()}">
                <div class="fm-panel-head">
                    <h3>${title}</h3>
                </div>
                <div class="fm-empty">${placeholderPrefix}: ${text}</div>
            </section>`;

        return `
            <div class="fm-page fm-player-page">
                ${buildPlayerProfileHeroHtml(player, { backLabel, eyebrow, teamName, ratingSummary, backButtonId: 'player-back-button' })}

                <section class="fm-panel fm-player-tabs-panel">
                    <div class="fm-player-tabs">
                        <button type="button" class="fm-player-tab is-active" data-player-tab="overview">Overview</button>
                        <button type="button" class="fm-player-tab" data-player-tab="matches">Matches</button>
                        <button type="button" class="fm-player-tab" data-player-tab="transfer">Transfer</button>
                        <button type="button" class="fm-player-tab" data-player-tab="history">History</button>
                    </div>
                </section>

                ${revealActive ? `
                <section class="fm-panel fm-player-reveal">
                    <h3>Junior Promotion Reveal</h3>
                    <p class="fm-subtle">Skills are being generated from academy potential.</p>
                    <p class="fm-subtle">Remaining skill budget: <strong id="junior-reveal-remaining">${Number(revealPayload.totalSkillBudget || 0)}</strong></p>
                    <p class="fm-subtle" id="junior-reveal-status">Allocating 1 point every second...</p>
                </section>` : ''}

                <div class="fm-player-grid">
                    <div class="fm-player-grid-left">
                        <section class="fm-panel fm-player-tab-panel is-active" data-player-tab-panel="overview">
                            <div class="fm-panel-head">
                                <h3>Positions</h3>
                            </div>
                            <div class="fp-pitch">
                                <div class="fp-field">
                                    <div class="fp-half-line"></div>
                                    <div class="fp-center-circle"></div>
                                    <div class="fp-penalty-area fp-pa-top"></div>
                                    <div class="fp-goal-area fp-ga-top"></div>
                                    <div class="fp-penalty-area fp-pa-bot"></div>
                                    <div class="fp-goal-area fp-ga-bot"></div>
                                    ${positionInfo.items.map(item => `<span class="fp-dot fp-${item.key.toLowerCase()}${item.active ? ' fp-on' : ''}${item.primary ? ' fp-primary' : ''}" style="top:${item.top}; left:${item.left};">${item.label}</span>`).join('')}
                                </div>
                            </div>
                            <div class="fm-pref-foot">
                                <span class="fm-detail-label">Current role</span>
                                <span class="fm-detail-value">${escapeHtml(positionInfo.raw || 'Player')}</span>
                            </div>
                            <div class="fm-player-overview-strip">
                                <div><strong>${player.age ?? '-'}</strong><span>Age</span></div>
                                <div><strong>${player.overall ?? '-'}</strong><span>OVR</span></div>
                                <div><strong>${ratingSummary.matchesPlayed ?? 0}</strong><span>Apps</span></div>
                            </div>
                        </section>
                        ${renderPlaceholder('Matches', 'player-by-player match log can be wired later')}
                        ${buildPlayerTransferPanelHtml(player, transferStatus, { placeholderPrefix })}
                        ${renderPlaceholder('History', 'career timeline UI is ready for later API expansion')}
                    </div>
                    <div class="fm-player-grid-right">
                        <section class="fm-panel fm-player-tab-panel is-active" data-player-tab-panel="overview">
                            <div class="fm-panel-head">
                                <h3>Attributes</h3>
                                <span class="fm-panel-action">Open-football inspired overview with our current skills</span>
                            </div>
                            <div class="fm-skills-grid">
                                ${skillSections.map(section => `
                                    <div class="fm-skill-col">
                                        <h4>${section.title}</h4>
                                        <table class="fm-skills">
                                            <tbody>
                                                ${section.items.map(([label, value, id]) => `<tr><td>${label}</td>${renderSkillValue(value, id)}</tr>`).join('')}
                                            </tbody>
                                        </table>
                                    </div>`).join('')}
                            </div>
                        </section>
                        <section class="fm-panel fm-player-tab-panel is-active" data-player-tab-panel="overview">
                            <div class="fm-panel-head">
                                <h3>Statistics</h3>
                            </div>
                            <table class="fm-player-profile-stats">
                                <thead>
                                    <tr>
                                        <th>Scope</th>
                                        <th>Apps</th>
                                        <th>Goals</th>
                                        <th>Assists</th>
                                        <th>Avg rating</th>
                                        <th>Form</th>
                                        <th>Status</th>
                                    </tr>
                                </thead>
                                <tbody>
                                    <tr>
                                        <td>Current save</td>
                                        <td>${ratingSummary.matchesPlayed ?? 0}</td>
                                        <td>${player.totalGoals ?? 0}</td>
                                        <td>${player.totalAssists ?? 0}</td>
                                        <td>${averageRating}</td>
                                        <td>${formBadge}</td>
                                        <td>${escapeHtml(injuryText)}</td>
                                    </tr>
                                </tbody>
                            </table>
                        </section>
                    </div>
                </div>
            </div>`;
    }
    function initPlayerProfilePage(options = {}) {
        const { onBack, onTransferAction } = options;
        const page = document.querySelector('.fm-player-page');
        if (!page) return;

        const backButton = page.querySelector('#player-back-button');
        if (backButton && typeof onBack === 'function') {
            backButton.addEventListener('click', onBack);
        }

        const tabs = page.querySelectorAll('[data-player-tab]');
        const panels = page.querySelectorAll('[data-player-tab-panel]');
        tabs.forEach(tab => {
            tab.addEventListener('click', () => {
                const target = tab.dataset.playerTab;
                tabs.forEach(button => button.classList.toggle('is-active', button === tab));
                panels.forEach(panel => panel.classList.toggle('is-active', panel.dataset.playerTabPanel === target));
            });
        });

        if (typeof onTransferAction === 'function') {
            page.querySelectorAll('[data-transfer-panel-action]').forEach(button => {
                button.addEventListener('click', () => onTransferAction(button.dataset.transferPanelAction, button));
            });
        }
    }

    function promptTransferActionPrice(label, fallbackValue) {
        const suggested = Math.max(1, Math.round(Number(fallbackValue || 1)));
        const raw = window.prompt(label, String(suggested));
        if (raw == null) return null;
        const numeric = Number(raw);
        if (!Number.isFinite(numeric) || numeric <= 0) {
            window.alert('Enter a valid positive price.');
            return null;
        }
        return numeric;
    }

    async function performTransferJsonAction(url, payload, method = 'POST') {
        const response = await authFetch(url, {
            method,
            headers: payload ? { 'Content-Type': 'application/json' } : undefined,
            body: payload ? JSON.stringify(payload) : undefined
        });
        if (method === 'DELETE') return null;
        try {
            return await response.json();
        } catch {
            return null;
        }
    }

    async function handlePlayerTransferAction(action, button, options = {}) {
        if (!await ensureUserTeamId()) return;
        const { playerId, reloadCurrent, reloadOwned } = options;
        const resolvedPlayerId = Number(button?.dataset?.playerId || playerId || 0);
        if (!resolvedPlayerId) return;

        try {
            switch (action) {
                case 'list': {
                    const price = promptTransferActionPrice('Set asking price for this player:', button?.dataset?.defaultPrice || 1);
                    if (price == null) return;
                    await performTransferJsonAction(`/transfers/list/${resolvedPlayerId}`, { teamId: currentUserTeamId, price });
                    await reloadCurrent?.();
                    return;
                }
                case 'remove': {
                    if (!window.confirm('Remove this player from the transfer list?')) return;
                    await performTransferJsonAction(`/transfers/remove/${resolvedPlayerId}?teamId=${encodeURIComponent(currentUserTeamId)}`, null, 'DELETE');
                    await reloadCurrent?.();
                    return;
                }
                case 'interest': {
                    const params = new URLSearchParams({ teamId: String(currentUserTeamId) });
                    if (currentUserTeamName) params.set('club', currentUserTeamName);
                    await performTransferJsonAction(`/transfers/interest/${resolvedPlayerId}?${params.toString()}`);
                    await reloadCurrent?.();
                    return;
                }
                case 'buy-listed': {
                    const price = promptTransferActionPrice('Enter agreed fee for this listed player:', button?.dataset?.defaultPrice || 1);
                    if (price == null) return;
                    await performTransferJsonAction(`/transfers/buy/${resolvedPlayerId}`, { teamId: currentUserTeamId, price });
                    await (reloadOwned || reloadCurrent)?.();
                    return;
                }
                case 'direct-buy': {
	                    const price = promptTransferActionPrice('Enter transfer offer fee for this player:', button?.dataset?.defaultPrice || 1);
                    if (price == null) return;
	                    const result = await performTransferJsonAction(`/transfers/direct-buy/${resolvedPlayerId}`, { teamId: currentUserTeamId, price });
	                    if (result?.actionMessage) {
	                        window.alert(result.actionMessage);
	                    }
                    await (reloadOwned || reloadCurrent)?.();
                    return;
                }
                case 'accept-offer': {
                    const result = await performTransferJsonAction(`/transfers/accept-offer/${resolvedPlayerId}`, { teamId: currentUserTeamId });
                    if (result?.actionMessage) {
                        window.alert(result.actionMessage);
                    }
                    await (reloadOwned || reloadCurrent)?.();
                    return;
                }
                case 'reject-offers': {
                    if (!window.confirm('Reject all incoming offers for this player?')) return;
                    const result = await performTransferJsonAction(`/transfers/reject-offers/${resolvedPlayerId}`, { teamId: currentUserTeamId });
                    if (result?.actionMessage) {
                        window.alert(result.actionMessage);
                    }
                    await (reloadOwned || reloadCurrent)?.();
                    return;
                }
                default:
                    return;
            }
        } catch (err) {
            console.error('Player transfer action failed:', err);
            window.alert(err.message || 'Transfer action failed.');
        }
    }

    async function loadPlayer(playerId, callerPage = currentPageId, options = {}) {
        const pushHistory = options.pushHistory !== false;
        if (pushHistory) pushNavState({ type: 'player', playerId, callerPage });
        const mainContent = document.getElementById("main-content");
        console.log(`Loading player for team ${currentUserTeamId} and player ${playerId}`);
        const [response, ratingSummary, transferStatus] = await Promise.all([
            authFetch(`/teams/${currentUserTeamId}/players/${playerId}`),
            fetchPlayerRatingSummary(playerId),
            fetchPlayerTransferStatus(playerId)
        ]);
        console.log(`Response status: ${response.status}`);
        if (!response.ok) {
            const backTarget = callerPage || "firstTeam";
            mainContent.innerHTML = `<div class="team-card"><p>Player not found.</p><button onclick="loadPage('${backTarget}')">Back</button></div>`;
            return;
        }

        const player = await response.json();
        const revealPayload = getPendingJuniorReveal(playerId);
        const backMap = {
            juniors: "Back",
            trainingReports: "Back",
            trainingSetup: "Back",
            firstTeam: "Back",
            leagueTable: "Back",
            leagueMatches: "Back",
            results: "Back",
            fixtures: "Back"
        };
        const backLabel = backMap[callerPage] || "Back";
        const backTarget = callerPage || "firstTeam";
        const teamLabel = callerPage === 'juniors' ? 'Junior Squad' : 'First Team';
        mainContent.innerHTML = buildPlayerProfileHtml(player, {
            backLabel,
            eyebrow: 'Player overview',
            teamName: teamLabel,
            ratingSummary,
            transferStatus,
            revealPayload,
            showReveal: true,
            placeholderPrefix: 'This tab UI is ready'
        });
        initPlayerProfilePage({
            onBack: () => goBackSmart(backTarget),
            onTransferAction: (action, button) => handlePlayerTransferAction(action, button, {
                playerId,
                reloadCurrent: () => loadPlayer(playerId, callerPage, { pushHistory: false })
            })
        });
        if (revealPayload) await runJuniorRevealAnimation(revealPayload);
    }
    async function loadMatch(matchId, caller, options = {}) {
        const pushHistory = options.pushHistory !== false;
	        if (pushHistory) pushNavState({ type: 'match', matchId, caller, ...getActiveLeagueNavState() });
        const mainContent = document.getElementById("main-content");
        const initialTab = options.initialTab === 'report' ? 'report' : 'preview';
        console.log(`Loading match ID: ${matchId}, caller: ${caller}`);
        if(caller==="undefined"){
           console.log(`Match not found.`);
           mainContent.innerHTML = `<div class="team-card"><p>Match not found.</p></div>`;
           return;
        }
        try {
            const response = await authFetch(`/matches/${matchId}/detail`);
            console.log(`Status: ${response.status}`);

            if (!response.ok) {
                const text = await response.text();
                console.error(`Error ${response.status}: ${text}`);
                mainContent.innerHTML = `<div class="team-card"><p>Match not found.</p></div>`;
                return;
            }

            const [events, lineupsPayload] = await Promise.all([
                response.json(),
                authFetch(`/match-stats/lineups/${matchId}`)
                    .then(r => r.ok ? r.json() : null)
                    .catch(() => null)
            ]);
            console.log("MATCH EVENTS:", events);
            console.log("Event count:", events.length);

            if (events.length === 0) {
                mainContent.innerHTML = `<div class="team-card"><p>No data available for this match.</p></div>`;
                return;
            }

            // Osnovni podaci meÄa
            const first = events[0];
            const homeTeamName = first.homeTeam || "Home";
            const awayTeamName = first.awayTeam || "Away";
            const homeGoals = first.homeGoals ?? 0;
            const awayGoals = first.awayGoals ?? 0;
            const homeTeamId = lineupsPayload?.homeTeamId || null;
            const awayTeamId = lineupsPayload?.awayTeamId || null;

            const matchDate = parseMatchDate(first.matchDate);
            const formattedDate = matchDate.toLocaleString('en-US', {
                weekday: 'short', year: 'numeric', month: 'short', day: 'numeric',
                hour: '2-digit', minute: '2-digit'
            });

            // Match details layout with a single Back button
            mainContent.innerHTML = `
            <div class="team-card">
                <h2 style="text-align:center;">Match Details</h2>

                <div class="fm-match-scoreline" style="font-size:1.3em; margin:20px 0; font-weight:bold;">
                    <div class="fm-match-score-team">
                        <div class="fm-match-score-name">${homeTeamId ? `<span class="cs-clickable" onclick="loadLeagueTeam(${homeTeamId}, '${escapeHtml(homeTeamName)}')">${homeTeamName}</span>` : homeTeamName}</div>
                        <div>${homeGoals}</div>
                    </div>
                    <div class="fm-match-score-separator" style="font-size:1.6em;">-</div>
                    <div class="fm-match-score-team">
                        <div class="fm-match-score-name">${awayTeamId ? `<span class="cs-clickable" onclick="loadLeagueTeam(${awayTeamId}, '${escapeHtml(awayTeamName)}')">${awayTeamName}</span>` : awayTeamName}</div>
                        <div>${awayGoals}</div>
                    </div>
                </div>

                <div style="text-align:center; color:#aaa; margin-bottom:25px;">
                    &#128197; ${formattedDate}
                </div>

                <div id="match-buttons-container" class="fm-match-actions">
                    <button type="button" id="view-preview" class="fm-action-btn secondary fm-match-action-btn">Preview</button>
                    <button type="button" id="view-lineups" class="fm-action-btn secondary fm-match-action-btn">Lineups</button>
                    <button type="button" id="view-stats" class="fm-action-btn secondary fm-match-action-btn">Stats</button>
                    <button type="button" id="view-goals" class="fm-action-btn secondary fm-match-action-btn">Goals</button>
                    <button type="button" id="view-replay" class="fm-action-btn secondary fm-match-action-btn">Replay</button>
                    <button type="button" id="view-report" class="fm-action-btn secondary fm-match-action-btn">Match Report</button>
                </div>

                <div id="match-info" style="margin-top:15px; min-height:200px;"></div>

                <!-- Jedno zajedniÄko Back dugme -->
                <div style="text-align:center; margin-top:30px;">
                    <button id="back-button" style="padding:10px 24px; font-size:1.1em;">
                        Back
                    </button>
                </div>
            </div>`;

            // Postavi ponaÅ¡anje Back dugmeta u zavisnosti od caller-a
            const backButton = document.getElementById('back-button');
            let backTarget = 'results';

            if (caller === 'match') {
                backTarget = 'results';
                backButton.textContent = 'Back';
            } else if (caller === 'leagueMatches') {
                backTarget = 'leagueMatches';
                backButton.textContent = 'Back';
	            } else if (caller === 'leagueTable') {
	                backTarget = 'leagueTable';
	                backButton.textContent = 'Back';
	            } else if (caller === 'leagueSchedule') {
	                backTarget = 'leagueSchedule';
	                backButton.textContent = 'Back';
            } else {
                console.warn(`Unknown caller: ${caller} -> fallback to 'results'`);
            }

            backButton.dataset.target = backTarget;
            //backButton.textContent = backText;
            backButton.style.display = 'inline-block';

             const infoDiv = document.getElementById("match-info");
             let cachedMatchPreview = null;
             let cachedMatchReport = null;

            async function revealMatchResultIfAllowed() {
                try {
                    await authFetch(`/matches/${matchId}/reveal`, { method: 'POST' });
                } catch (error) {
                    console.warn(`Reveal skipped for match ${matchId}:`, error);
                }
            }

            function renderMatchPreview(previewPayload) {
                const prediction = previewPayload?.prediction || {};
                const h2h = previewPayload?.h2h || {};
                const meetings = Array.isArray(previewPayload?.meetings) ? previewPayload.meetings : [];

                const homeWin = Number(prediction.homeWinProbability ?? 0);
                const draw = Number(prediction.drawProbability ?? 0);
                const awayWin = Number(prediction.awayWinProbability ?? 0);
                const confidence = Number(prediction.confidence ?? 0);
                const expectedHomeGoals = Number(prediction.expectedHomeGoals ?? 0);
                const expectedAwayGoals = Number(prediction.expectedAwayGoals ?? 0);
                const homeStrength = Number(previewPayload?.homeTeamStrength ?? 0);
                const awayStrength = Number(previewPayload?.awayTeamStrength ?? 0);
                const homeForm = Number(previewPayload?.homeTeamForm ?? 0);
                const awayForm = Number(previewPayload?.awayTeamForm ?? 0);
                const analysis = escapeHtml(String(prediction.analysis || 'No extra preview analysis available.'));
                const h2hSummary = escapeHtml(String(h2h.summary || 'No head-to-head history yet.'));
                const lastMeetingSummary = escapeHtml(String(h2h.lastMeetingSummary || 'First recorded meeting.'));
                const lastMeetingDate = escapeHtml(String(h2h.lastMeetingDate || 'N/A'));

                const meetingsHtml = meetings.length
                    ? meetings.map(meeting => {
                        const meetingId = Number(meeting?.matchId ?? 0);
                        const line = `${escapeHtml(String(meeting.homeTeam || 'Home'))} ${Number(meeting.homeGoals ?? 0)} - ${Number(meeting.awayGoals ?? 0)} ${escapeHtml(String(meeting.awayTeam || 'Away'))}`;
                        const meta = `${escapeHtml(formatDateTimeLabel(meeting.matchDate))} · ${escapeHtml(String(meeting.summary || ''))}`;
                        if (meetingId) {
                            return `<button type="button" class="fm-action-btn secondary" style="width:100%; text-align:left; justify-content:space-between; gap:12px; margin-bottom:10px;" onclick="loadMatch(${meetingId}, 'match')"><span>${line}</span><span style="color:#9aa0a6; font-size:0.9em;">${meta}</span></button>`;
                        }
                        return `<div style="padding:10px 12px; margin-bottom:10px; border-radius:10px; background:rgba(255,255,255,0.05);"><div>${line}</div><div style="color:#9aa0a6; font-size:0.9em; margin-top:4px;">${meta}</div></div>`;
                    }).join('')
                    : `<div style="color:#aaa; text-align:center; padding:14px 0;">No previous meetings recorded.</div>`;

                infoDiv.innerHTML = `
                    <div class="fm-match-report-shell">
                        <h3 style="text-align:center; margin:0 0 16px; color:#4CAF50;">Match Preview</h3>
                        <div style="display:grid; grid-template-columns:repeat(auto-fit, minmax(220px, 1fr)); gap:14px; margin-bottom:16px;">
                            <div style="padding:16px; border-radius:14px; background:rgba(255,255,255,0.05); text-align:center;">
                                <div style="color:#9aa0a6; font-size:0.9em; margin-bottom:8px;">Prediction</div>
                                <div style="font-size:0.92em; color:#cfd8dc; margin-bottom:8px;">${confidence}% confidence</div>
                                <div style="display:grid; grid-template-columns:repeat(3, 1fr); gap:8px; margin-bottom:10px;">
                                    <div><div style="font-size:0.8em; color:#9aa0a6;">1</div><div style="font-size:1.35em; font-weight:700;">${homeWin}%</div></div>
                                    <div><div style="font-size:0.8em; color:#9aa0a6;">X</div><div style="font-size:1.35em; font-weight:700;">${draw}%</div></div>
                                    <div><div style="font-size:0.8em; color:#9aa0a6;">2</div><div style="font-size:1.35em; font-weight:700;">${awayWin}%</div></div>
                                </div>
                                <div style="font-size:0.92em; color:#dfe6eb;">xG ${expectedHomeGoals.toFixed(2)} : ${expectedAwayGoals.toFixed(2)}</div>
                                <div style="font-size:0.88em; color:#9aa0a6; margin-top:8px;">${analysis}</div>
                            </div>
                            <div style="padding:16px; border-radius:14px; background:rgba(255,255,255,0.05);">
                                <div style="color:#9aa0a6; font-size:0.9em; margin-bottom:10px;">Team edge</div>
                                <div style="display:flex; justify-content:space-between; gap:12px; margin-bottom:10px;">
                                    <div><div style="font-size:0.82em; color:#9aa0a6;">${escapeHtml(homeTeamName)}</div><div style="font-weight:700;">OVR ${homeStrength}</div><div style="color:#9aa0a6; font-size:0.88em;">Form ${homeForm.toFixed(1)}</div></div>
                                    <div style="text-align:right;"><div style="font-size:0.82em; color:#9aa0a6;">${escapeHtml(awayTeamName)}</div><div style="font-weight:700;">OVR ${awayStrength}</div><div style="color:#9aa0a6; font-size:0.88em;">Form ${awayForm.toFixed(1)}</div></div>
                                </div>
                                <div style="padding-top:10px; border-top:1px solid rgba(255,255,255,0.08); font-size:0.92em; color:#dfe6eb;">${h2hSummary}</div>
                                <div style="margin-top:8px; color:#9aa0a6; font-size:0.88em;">${lastMeetingSummary}</div>
                                <div style="margin-top:4px; color:#7f8c8d; font-size:0.82em;">${lastMeetingDate}</div>
                            </div>
                        </div>
                        <div style="padding:16px; border-radius:14px; background:rgba(255,255,255,0.04);">
                            <h4 style="margin:0 0 12px; color:#dfe6eb;">Recent H2H meetings</h4>
                            ${meetingsHtml}
                        </div>
                    </div>`;
            }

            async function showPreview() {
                if (cachedMatchPreview) {
                    renderMatchPreview(cachedMatchPreview);
                    return;
                }

                infoDiv.innerHTML = `<p style="color:#aaa; text-align:center; padding:30px;">Loading preview...</p>`;

                try {
                    const response = await authFetch(`/matches/${matchId}/preview`);
                    if (!response.ok) {
                        throw new Error(`Preview unavailable (${response.status})`);
                    }
                    cachedMatchPreview = await response.json();
                    renderMatchPreview(cachedMatchPreview);
                } catch (error) {
                    console.error('Failed to load match preview:', error);
                    infoDiv.innerHTML = `<p style="color:#ffb3b3; text-align:center; padding:30px;">Match preview is not available for this match.</p>`;
                }
            }

            function renderMatchReport(reportPayload) {
                const headline = escapeHtml(String(reportPayload?.headline || 'Match Report'));
                const reportText = escapeHtml(String(reportPayload?.report || 'No match report available.'));
                const motm = reportPayload?.manOfTheMatch || null;
                const motmFacts = [];
                if (Number.isFinite(Number(motm?.rating10))) motmFacts.push(`${Number(motm.rating10).toFixed(1)} rating`);
                if (Number(motm?.goals) > 0) motmFacts.push(`${Number(motm.goals)} goal${Number(motm.goals) === 1 ? '' : 's'}`);
                if (Number(motm?.assists) > 0) motmFacts.push(`${Number(motm.assists)} assist${Number(motm.assists) === 1 ? '' : 's'}`);
                if (Number(motm?.saves) > 0) motmFacts.push(`${Number(motm.saves)} save${Number(motm.saves) === 1 ? '' : 's'}`);
                if (Number(motm?.interceptions) > 0) motmFacts.push(`${Number(motm.interceptions)} interceptions`);
                if (Number(motm?.minutesPlayed) > 0) motmFacts.push(`${Number(motm.minutesPlayed)} min`);
                if (motm?.cleanSheet) motmFacts.push('clean sheet');

                const motmPlayerLabel = motm?.playerId && motm?.teamId
                    ? `<span class="cs-clickable" onclick="loadLeagueTeamPlayer(${Number(motm.playerId)}, ${Number(motm.teamId)}, '${escapeHtml(motm.teamName || 'Team')}')">${escapeHtml(motm.playerName || 'Unknown')}</span>`
                    : escapeHtml(String(motm?.playerName || 'Unknown'));
                const motmTeamLabel = motm?.teamId
                    ? `<span class="cs-clickable" onclick="loadLeagueTeam(${Number(motm.teamId)}, '${escapeHtml(motm.teamName || 'Team')}')">${escapeHtml(motm.teamName || 'Unknown')}</span>`
                    : escapeHtml(String(motm?.teamName || 'Unknown'));
                const motmBlock = motm ? `
                    <div class="fm-match-report-motm">
                        <div class="fm-match-report-motm-top">
                            <div>
                                <div class="fm-milestone-kicker">Man of the Match</div>
                                <div class="fm-match-report-motm-name">${motmPlayerLabel}</div>
                            </div>
                            <div class="fm-match-report-motm-team">${motmTeamLabel}</div>
                        </div>
                        <div class="fm-match-report-motm-meta">${escapeHtml(motmFacts.join(' · ') || 'Best overall performance recorded for this match.')}</div>
                    </div>` : '';

                infoDiv.innerHTML = `
                    <div class="fm-match-report-shell">
                        <h3 style="text-align:center; margin:0 0 14px; color:#4CAF50;">Match Report</h3>
                        <div class="fm-match-report-headline">${headline}</div>
                        ${motmBlock}
                        <div class="fm-match-report-body">${reportText}</div>
                    </div>`;
            }

            async function showMatchReport() {
                await revealMatchResultIfAllowed();

                if (cachedMatchReport) {
                    renderMatchReport(cachedMatchReport);
                    return;
                }

                infoDiv.innerHTML = `<p style="color:#aaa; text-align:center; padding:30px;">Loading match report...</p>`;

                try {
                    const response = await authFetch(`/matches/${matchId}/report`);
                    if (!response.ok) {
                        throw new Error(`Report unavailable (${response.status})`);
                    }
                    cachedMatchReport = await response.json();
                    renderMatchReport(cachedMatchReport);
                } catch (error) {
                    console.error('Failed to load match report:', error);
                    infoDiv.innerHTML = `<p style="color:#ffb3b3; text-align:center; padding:30px;">Match report is not available for this match.</p>`;
                }
            }

            // Funkcija za prikaz statistike
            function showStats() {
                const homeShotsOn  = events.filter(e => e.eventType === "ShotOnTargetEvent" && e.shotOnTargetTeam === homeTeamName).length;
                const awayShotsOn  = events.filter(e => e.eventType === "ShotOnTargetEvent" && e.shotOnTargetTeam === awayTeamName).length;
                const homeShotsOff = events.filter(e => e.eventType === "ShotOffTargetEvent" && e.shotOffTargetTeam === homeTeamName).length;
                const awayShotsOff = events.filter(e => e.eventType === "ShotOffTargetEvent" && e.shotOffTargetTeam === awayTeamName).length;
                const homeGoalsCount = events.filter(e => e.eventType === "GoalEvent" && e.scoreTeam === homeTeamName && e.goalScored !== false).length;
                const awayGoalsCount = events.filter(e => e.eventType === "GoalEvent" && e.scoreTeam === awayTeamName && e.goalScored !== false).length;

                const adjHomeShotsOn = homeShotsOn + homeGoalsCount;
                const adjAwayShotsOn = awayShotsOn + awayGoalsCount;
                const homeTotalShots = adjHomeShotsOn + homeShotsOff;
                const awayTotalShots = adjAwayShotsOn + awayShotsOff;

                const homeCorners = events.filter(e => e.eventType === "CornerEvent" && e.eventTeam === homeTeamName).length;
                const awayCorners = events.filter(e => e.eventType === "CornerEvent" && e.eventTeam === awayTeamName).length;

                const homeYellows = events.filter(e => e.eventType === "YellowCardEvent" && e.eventTeam === homeTeamName).length;
                const awayYellows = events.filter(e => e.eventType === "YellowCardEvent" && e.eventTeam === awayTeamName).length;

                const homeReds = events.filter(e => e.eventType === "RedCardEvent" && e.eventTeam === homeTeamName).length;
                const awayReds = events.filter(e => e.eventType === "RedCardEvent" && e.eventTeam === awayTeamName).length;

                const homePenalties = events.filter(e => e.eventType === "PenaltyEvent" && e.eventTeam === homeTeamName).length;
                const awayPenalties = events.filter(e => e.eventType === "PenaltyEvent" && e.eventTeam === awayTeamName).length;

                const extractEventXg = event => {
                    const rawValue = Number(event?.xG ?? event?.xg ?? 0);
                    return Number.isFinite(rawValue) ? rawValue : 0;
                };

                const isXgEvent = event =>
                    event.eventType === "ShotOnTargetEvent" ||
                    event.eventType === "ShotOffTargetEvent" ||
                    (event.eventType === "GoalEvent" && event.goalScored !== false);

                const resolveXgTeam = event => {
                    if (event.eventType === "GoalEvent") return event.scoreTeam || event.eventTeam;
                    if (event.eventType === "ShotOnTargetEvent") return event.shotOnTargetTeam || event.eventTeam;
                    if (event.eventType === "ShotOffTargetEvent") return event.shotOffTargetTeam || event.eventTeam;
                    return event.eventTeam;
                };

                const sumTeamXg = teamName =>
                    events.reduce((sum, event) => {
                        if (!isXgEvent(event) || resolveXgTeam(event) !== teamName) {
                            return sum;
                        }
                        return sum + extractEventXg(event);
                    }, 0);

                const homeXg = sumTeamXg(homeTeamName);
                const awayXg = sumTeamXg(awayTeamName);

                const countTeamEvents = (type, teamName) =>
                    events.filter(e => e.eventType === type && e.eventTeam === teamName).length;

                // Possession proxy: weighted attacking/control events to avoid constant 50-50 when ChanceEvent is sparse.
                const homePossWeight =
                    (countTeamEvents("ChanceEvent", homeTeamName) * 3.0) +
                    (homeShotsOn * 2.0) +
                    (homeShotsOff * 1.4) +
                    (homeCorners * 1.2) +
                    (countTeamEvents("FreeKickEvent", homeTeamName) * 0.9) +
                    (homePenalties * 1.3) +
                    (countTeamEvents("GoalEvent", homeTeamName) * 1.1);

                const awayPossWeight =
                    (countTeamEvents("ChanceEvent", awayTeamName) * 3.0) +
                    (awayShotsOn * 2.0) +
                    (awayShotsOff * 1.4) +
                    (awayCorners * 1.2) +
                    (countTeamEvents("FreeKickEvent", awayTeamName) * 0.9) +
                    (awayPenalties * 1.3) +
                    (countTeamEvents("GoalEvent", awayTeamName) * 1.1);

                const baselineWeight = 18.0;
                const totalPoss = (homePossWeight + baselineWeight) + (awayPossWeight + baselineWeight);
                let homePossPct = totalPoss > 0
                    ? Math.round(((homePossWeight + baselineWeight) / totalPoss) * 100)
                    : 50;
                homePossPct = Math.max(32, Math.min(68, homePossPct));
                const awayPossPct = 100 - homePossPct;

                let html = `<h3 style="text-align:center; margin:0 0 20px; color:#4CAF50;">Match Stats</h3>`;

                html += `
                <table style="width:100%; border-collapse:collapse; font-size:0.95em;">
                    <thead>
                        <tr style="background:rgba(76,175,80,0.15);">
                            <th style="padding:12px; text-align:left;">Stats</th>
                            <th style="padding:12px; text-align:center;">${homeTeamId ? `<span class="cs-clickable" onclick="loadLeagueTeam(${homeTeamId}, '${escapeHtml(homeTeamName)}')">${homeTeamName}</span>` : homeTeamName}</th>
                            <th style="padding:12px; text-align:center;">${awayTeamId ? `<span class="cs-clickable" onclick="loadLeagueTeam(${awayTeamId}, '${escapeHtml(awayTeamName)}')">${awayTeamName}</span>` : awayTeamName}</th>
                        </tr>
                    </thead>
                    <tbody>
                        <tr><td style="padding:10px;">Possession</td><td style="text-align:center;font-weight:bold;">${homePossPct}%</td><td style="text-align:center;font-weight:bold;">${awayPossPct}%</td></tr>
                        <tr style="background:rgba(255,255,255,0.04);"><td style="padding:10px;">xG</td><td style="text-align:center;">${homeXg.toFixed(2)}</td><td style="text-align:center;">${awayXg.toFixed(2)}</td></tr>
                        <tr><td style="padding:10px;">Shots</td><td style="text-align:center;">${homeTotalShots}</td><td style="text-align:center;">${awayTotalShots}</td></tr>
                        <tr><td style="padding:10px;">Shots on target</td><td style="text-align:center;">${adjHomeShotsOn}</td><td style="text-align:center;">${adjAwayShotsOn}</td></tr>
                        <tr style="background:rgba(255,255,255,0.04);"><td style="padding:10px;">Shots off target</td><td style="text-align:center;">${homeShotsOff}</td><td style="text-align:center;">${awayShotsOff}</td></tr>
                        <tr><td style="padding:10px;">Corners</td><td style="text-align:center;">${homeCorners}</td><td style="text-align:center;">${awayCorners}</td></tr>
                        <tr style="background:rgba(255,255,255,0.04);"><td style="padding:10px;">Yellow cards</td><td style="text-align:center;color:#ff9800;">${homeYellows}</td><td style="text-align:center;color:#ff9800;">${awayYellows}</td></tr>
                        <tr><td style="padding:10px;">Red cards</td><td style="text-align:center;color:#f44336;">${homeReds}</td><td style="text-align:center;color:#f44336;">${awayReds}</td></tr>
                        <tr style="background:rgba(255,255,255,0.04);"><td style="padding:10px;">Penalties</td><td style="text-align:center;">${homePenalties}</td><td style="text-align:center;">${awayPenalties}</td></tr>
                    </tbody>
                </table>`;

                infoDiv.innerHTML = html;
            }

            if (initialTab === 'report') {
                void showMatchReport();
            } else {
                void showPreview();
            }

            // Listener-i za ostala dugmad
            document.getElementById("view-preview").addEventListener("click", () => {
                void showPreview();
            });
            document.getElementById("view-lineups").addEventListener("click", () => {
                if (!lineupsPayload || (!lineupsPayload.homeLineup && !lineupsPayload.awayLineup)) {
                    infoDiv.innerHTML = `<p style="color:#aaa; text-align:center; padding:30px;">Lineups are not available for this match.</p>`;
                    return;
                }

                const seasonYear = currentLeagueSeasonYear;
                const renderLineup = (teamName, teamId, players) => {
                    const sorted = [...(players || [])].sort((a, b) => {
                        const posOrder = { GK: 0, DEF: 1, MID: 2, WNG: 3, ATT: 4 };
                        return (posOrder[a.position] ?? 9) - (posOrder[b.position] ?? 9);
                    });
                    if (sorted.length === 0) return `<p class="fm-subtle">No lineup data.</p>`;

                    let html = `
                        <section class="fm-match-lineup-team">
                            <h4 class="fm-match-lineup-title">${escapeHtml(teamName)}</h4>
                            <div class="fm-match-lineup-head">
                                <div>POS</div>
                                <div>Player</div>
                                <div>Rate</div>
                                <div>Impact</div>
                                <div>Min</div>
                            </div>
                            <div class="fm-match-lineup-body">`;
                    sorted.forEach(p => {
                        const gradeValue = Number(p.grade);
                        const compactName = escapeHtml(formatCompactPlayerName(p.playerName));
                        html += `
                            <div class="fm-match-lineup-row">
                                <div class="fm-match-lineup-pos">${escapeHtml(p.position || '-')}</div>
                                <div class="fm-match-lineup-player-cell">
                                    ${p.playerId && teamId
                                        ? `<button type="button" class="fm-match-lineup-player js-load-lineup-player" data-player-id="${p.playerId}" data-team-id="${teamId}" data-team-name="${escapeHtml(teamName)}" data-season-year="${seasonYear ?? ''}">${compactName}</button>`
                                        : `<span class="fm-match-lineup-player is-static">${compactName}</span>`}
                                </div>
                                <div class="fm-match-lineup-grade">${formatRatingBadge(gradeValue)}</div>
                                <div class="fm-match-lineup-badge-cell">${buildLineupEventBadges(p)}</div>
                                <div class="fm-match-lineup-min">${Number(p.minutesPlayed ?? 0)}</div>
                            </div>`;
                    });
                    return `${html}</div></section>`;
                };

                infoDiv.innerHTML = `
                    <div class="fm-match-lineups">
                        <h3 class="fm-match-lineups-title">Lineups & Grades</h3>
                        <div class="fm-match-lineups-grid">
                            ${renderLineup(lineupsPayload.homeTeam || homeTeamName, lineupsPayload.homeTeamId || 0, lineupsPayload.homeLineup || [])}
                            ${renderLineup(lineupsPayload.awayTeam || awayTeamName, lineupsPayload.awayTeamId || 0, lineupsPayload.awayLineup || [])}
                        </div>
                    </div>
                `;
                infoDiv.querySelectorAll('.js-load-lineup-player').forEach(node => {
                    node.addEventListener('click', () => {
                        const playerId = Number(node.dataset.playerId);
                        const teamId = Number(node.dataset.teamId);
                        const teamName = node.dataset.teamName || 'Team';
                        const lineupSeasonYear = node.dataset.seasonYear ? Number(node.dataset.seasonYear) : currentLeagueSeasonYear;
                        if (playerId && teamId) {
                            loadLeagueTeamPlayer(playerId, teamId, teamName, { seasonYear: lineupSeasonYear });
                        }
                    });
                });
            });
            document.getElementById("view-stats").addEventListener("click", showStats);
            document.getElementById("view-replay").addEventListener("click", () => {
                void (async () => {
                    await revealMatchResultIfAllowed();
                    window.location.href = `/realisticDemo.html?matchId=${encodeURIComponent(matchId)}&mode=replay`;
                })();
            });
            document.getElementById("view-report").addEventListener("click", () => {
                void showMatchReport();
            });

            document.getElementById("view-goals").addEventListener("click", () => {
                const goals = events.filter(e => e.eventType === "GoalEvent");
                if (goals.length === 0) {
                    infoDiv.innerHTML = `<p style="color:#aaa; text-align:center; padding:30px;">No goals in this match.</p>`;
                    return;
                }

                let html = `<h3 style="text-align:center; margin:0 0 20px; color:#4CAF50;">Goals</h3><ul style="list-style:none; padding:0;">`;

                goals.forEach(g => {
                    const disallowed = g.goalScored === false;
                    const lineColor = disallowed ? "#ffb3b3" : "inherit";
                    const verdict = disallowed ? ` <span style="color:#ff6b6b; font-weight:600;">DISALLOWED (VAR)</span>` : "";
                    const scorerTeamId = g.scoreTeam === homeTeamName ? homeTeamId : (g.scoreTeam === awayTeamName ? awayTeamId : null);
                    const scorerStat = (g.scoreTeam === homeTeamName ? (lineupsPayload?.homeLineup || []) : (lineupsPayload?.awayLineup || []))
                        .find(p => p.playerName === g.scorer);
                    const assistStat = (g.scoreTeam === homeTeamName ? (lineupsPayload?.homeLineup || []) : (lineupsPayload?.awayLineup || []))
                        .find(p => p.playerName === g.assistant);
                    const scorerLabel = scorerStat?.playerId && scorerTeamId
                        ? `<span class="cs-clickable" onclick="loadLeagueTeamPlayer(${scorerStat.playerId}, ${scorerTeamId}, '${escapeHtml(g.scoreTeam || '')}')">${g.scorer || "?"}</span>`
                        : (g.scorer || "?");
                    const assistLabel = assistStat?.playerId && scorerTeamId
                        ? `<span class="cs-clickable" onclick="loadLeagueTeamPlayer(${assistStat.playerId}, ${scorerTeamId}, '${escapeHtml(g.scoreTeam || '')}')">${g.assistant}</span>`
                        : (g.assistant || "");
                    const assistHtml = g.assistant ? ` <span style="color:#888;">(assist: ${assistLabel})</span>` : '';
                    html += `
                    <li style="padding:12px; margin:8px 0; background:rgba(255,255,255,0.05); border-radius:8px;">
                        <strong>${g.matchMinute}'</strong> <span style="color:${lineColor};">&#9917; ${scorerLabel} ${assistHtml}${verdict}</span>
                        <span style="float:right; color:#aaa;">${g.scoreAfterGoal || ""}</span>
                    </li>`;
                });

                html += `</ul>`;
                infoDiv.innerHTML = html;
            });

        } catch (err) {
            console.error("Error loading match:", err);
            mainContent.innerHTML = `<div class="team-card"><p>Error loading match: ${err.message}</p></div>`;
        }
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
    async function loadMedicalCenter() {
        const mainContent = document.getElementById("main-content");
        try {
            const response = await authFetch(`/teams/${currentUserTeamId}/medical`);
            if (!response.ok) throw new Error(`Medical overview load failed: ${response.status}`);
            const overview = await response.json();
            const queue = Array.isArray(overview?.recoveryQueue) ? overview.recoveryQueue : [];

            const buildRecoveryCardHtml = (player) => {
                const conditionPercent = getPlayerConditionPercent(player);
                const fatigue = Number(player?.fatigue);
                const fatigueText = Number.isFinite(fatigue) ? `${Math.round(fatigue)} fatigue` : 'Fatigue n/a';
                const injuryText = player?.injured
                    ? `Injured · ${player?.injuryDaysRemaining ?? 0} days`
                    : 'Available · managed recovery';
                const actionLabel = player?.injured ? 'Speed up recovery' : 'Recovery session';
                return `
                    <article class="fm-medical-card">
                        <div class="fm-medical-card-head">
                            <div>
                                <h4>${escapeHtml(player?.name || 'Player')}</h4>
                                <div class="fm-medical-card-subtle">${escapeHtml(player?.position || 'Player')} · ${player?.age ?? '-'} years</div>
                            </div>
                            <div class="fm-badge-deck">
                                ${player?.injured ? '<span class="fm-badge fm-badge-inj">INJ</span>' : '<span class="fm-badge fm-badge-fit">REC</span>'}
                            </div>
                        </div>
                        <div class="fm-medical-chip-row">
                            <span class="fm-medical-chip">${escapeHtml(injuryText)}</span>
                            <span class="fm-medical-chip">${escapeHtml(fatigueText)}</span>
                            <span class="fm-medical-chip">Condition ${conditionPercent}%</span>
                        </div>
                        <div class="fm-cond">
                            <div class="fm-cond-bar"><div class="fm-cond-fill" style="width:${conditionPercent}%"></div></div>
                            <span class="fm-cond-val">${conditionPercent}%</span>
                        </div>
                        <div class="fm-medical-actions">
                            <button type="button" class="fm-action-btn secondary" data-medical-recover-player="${player?.id ?? ''}">${actionLabel}</button>
                            <button type="button" class="fm-action-btn secondary" data-medical-open-player="${player?.id ?? ''}">Open player</button>
                        </div>
                    </article>`;
            };

            mainContent.innerHTML = `
                <div class="fm-page fm-page--club">
                    <section class="fm-panel fm-club-hero">
                        <button class="back-to-dashboard" data-nav-back="dashboard">Back</button>
                        <div class="fm-club-hero-main">
                            <div>
                                <div class="fm-eyebrow">Club support</div>
                                <h2>Medical Center</h2>
                                <p class="fm-subtle">Recovery queue is now live: match fatigue feeds the same condition bar used across squad and player pages, and injuries can be actively shortened here.</p>
                            </div>
                            ${buildClubActionsHtml('medicalCenter')}
                        </div>
                        <div class="fm-medical-stat-grid team-summary-grid">
                            <div><strong>${overview?.criticalInjuryCount ?? 0}</strong><span>Critical injuries</span></div>
                            <div><strong>${overview?.rehabCount ?? 0}</strong><span>Recovery queue</span></div>
                            <div><strong>${overview?.availableCount ?? 0}</strong><span>Available now</span></div>
                            <div><strong>${overview?.averageConditionPercent ?? 100}%</strong><span>Avg condition</span></div>
                        </div>
                    </section>
                    <div class="fm-team-layout has-side-panel">
                        <section class="fm-panel">
                            <div class="fm-panel-head">
                                <div>
                                    <h3>Recovery queue</h3>
                                    <p class="fm-subtle">Players with injuries or elevated fatigue show up here for treatment planning.</p>
                                </div>
                                <span class="fm-panel-action">${queue.length} cases</span>
                            </div>
                            <div class="fm-medical-roster">
                                ${queue.length ? queue.map(buildRecoveryCardHtml).join('') : '<div class="fm-empty">No players currently need recovery treatment.</div>'}
                            </div>
                        </section>
                        <aside class="fm-panel fm-medical-panel">
                            <div class="fm-panel-head">
                                <h3>Medical summary</h3>
                                <span class="fm-panel-action">Club area</span>
                            </div>
                            <div class="fm-medical-icon">&#10010; &#129658;</div>
                            <p class="fm-subtle">Recovery sessions reduce fatigue immediately and, when a player is injured, also cut a few days off the rehab timeline.</p>
                            <div class="fm-medical-summary-list">
                                <div><strong>${overview?.injuredCount ?? 0}</strong><span>Current injuries</span></div>
                                <div><strong>${overview?.rehabCount ?? 0}</strong><span>Managed cases</span></div>
                                <div><strong>${overview?.totalPlayers ?? 0}</strong><span>Total squad size</span></div>
                            </div>
                            <div class="fm-panel-action">Weekly passive healing still applies; this page adds active intervention.</div>
                        </aside>
                    </div>
                </div>`;

            mainContent.querySelectorAll('[data-medical-recover-player]').forEach(button => {
                button.addEventListener('click', async () => {
                    const playerId = Number(button.dataset.medicalRecoverPlayer || 0);
                    if (!playerId) return;
                    button.disabled = true;
                    try {
                        const recoveryResponse = await authFetch(`/teams/${currentUserTeamId}/medical/recovery/${playerId}`, { method: 'POST' });
                        if (!recoveryResponse.ok) throw new Error(`Medical recovery failed: ${recoveryResponse.status}`);
                        await loadMedicalCenter();
                    } catch (err) {
                        console.error('Medical recovery failed:', err);
                        button.disabled = false;
                    }
                });
            });
            mainContent.querySelectorAll('[data-medical-open-player]').forEach(button => {
                button.addEventListener('click', () => {
                    const playerId = Number(button.dataset.medicalOpenPlayer || 0);
                    if (playerId) loadPlayer(playerId, 'medicalCenter');
                });
            });
        } catch (err) {
            console.error('Failed to load medical center:', err);
            mainContent.innerHTML = `
                <div class="fm-page fm-page--club">
                    <section class="fm-panel fm-club-hero">
                        <button class="back-to-dashboard" data-nav-back="dashboard">Back</button>
                        <div class="fm-club-hero-main">
                            <div>
                                <div class="fm-eyebrow">Club support</div>
                                <h2>Medical Center</h2>
                                <p class="fm-subtle">Could not load the current medical overview.</p>
                            </div>
                            ${buildClubActionsHtml('medicalCenter')}
                        </div>
                    </section>
                    <section class="fm-panel">
                        <div class="fm-empty">Medical data is temporarily unavailable.</div>
                    </section>
                </div>`;
        }
    }
    async function loadFormations() {
        const mainContent = document.getElementById("main-content");
        const [formationsRes, playersRes, templateRes] = await Promise.all([
            authFetch(`/demo/teams/${currentUserTeamId}/formations`),
            authFetch(`/teams/${currentUserTeamId}/players`),
            authFetch(`/teams/${currentUserTeamId}/lineup-template`)
        ]);
        const formations = formationsRes.ok ? await formationsRes.json() : [];
        const players = playersRes.ok ? await playersRes.json() : [];
        const template = templateRes.ok ? await templateRes.json() : { formation: "4-4-2", starterIds: [], benchIds: [], saved: false };

        const availableFormations = Array.from(new Set([
            ...(Array.isArray(formations) ? formations.map(f => f.name) : []),
            "4-4-2", "4-3-3", "4-2-3-1", "4-1-4-1", "4-5-1", "3-5-2", "3-4-3", "3-4-2-1", "5-3-2", "5-4-1"
        ]));
        const availableStyles = ["BALANCED", "ATTACKING", "DEFENSIVE", "COUNTER", "POSSESSION", "HIGH_PRESS", "DIRECT"];
        const isMobile = window.matchMedia("(max-width: 768px)").matches;

        const formationToSlots = (formation) => {
            const parts = String(formation || "4-4-2")
                .split("-")
                .map(v => Number(v))
                .filter(Number.isFinite);
            const def = parts[0] ?? 4;
            const mid = parts[1] ?? 4;
            const att = parts[2] ?? 2;
            const slots = [{ label: "GK", role: "GK" }];
            for (let i = 0; i < def; i++) slots.push({ label: `DEF ${i + 1}`, role: "DEF" });
            for (let i = 0; i < mid; i++) slots.push({ label: `MID ${i + 1}`, role: "MID" });
            for (let i = 0; i < att; i++) slots.push({ label: `ATT ${i + 1}`, role: "ATT" });
            return slots.slice(0, 11);
        };

        const roleOrder = { GK: 0, DEF: 1, MID: 2, WNG: 3, ATT: 4 };
        const sortedPlayers = [...players].sort((a, b) => {
            const left = roleOrder[String(a.position || "").toUpperCase()] ?? 9;
            const right = roleOrder[String(b.position || "").toUpperCase()] ?? 9;
            if (left !== right) return left - right;
            return Number(b.overall || 0) - Number(a.overall || 0);
        });

        const localFormation = localStorage.getItem('main_app_tactics_formation');
        const localStyle = localStorage.getItem('main_app_tactics_style');
        const hasSavedTemplate = template?.saved === true || (Array.isArray(template?.starterIds) && template.starterIds.length > 0);
        const state = {
            formation: hasSavedTemplate
                ? (template.formation || availableFormations[0] || "4-4-2")
                : (localFormation || template.formation || availableFormations[0] || "4-4-2"),
            style: hasSavedTemplate
                ? (template.style || localStyle || "BALANCED")
                : (localStyle || template.style || "BALANCED"),
            starterIds: Array.isArray(template.starterIds) ? template.starterIds.map(Number).filter(Number.isFinite).slice(0, 11) : [],
            benchIds: Array.isArray(template.benchIds) ? template.benchIds.map(Number).filter(Number.isFinite).slice(0, 7) : []
        };

        if (hasSavedTemplate && template?.formation) {
            localStorage.setItem('main_app_tactics_formation', template.formation);
        }
        if (hasSavedTemplate && template?.style) {
            localStorage.setItem('main_app_tactics_style', template.style);
        }

        const canPlayRole = (player, role) => {
            const pos = String(player?.position || "").toUpperCase();
            if (role === "GK") return pos === "GK";
            if (role === "DEF") return pos === "DEF";
            if (role === "MID") return pos === "MID" || pos === "WNG";
            if (role === "ATT") return pos === "ATT" || pos === "WNG" || pos === "MID";
            return false;
        };

        const pickDefaultStarters = (slots) => {
            if (!players.length) return [];
            const byPos = (role) => sortedPlayers.filter(p => canPlayRole(p, role) && !p.injured)
                .sort((a, b) => Number(b.overall || 0) - Number(a.overall || 0));
            const picks = [];
            const used = new Set();
            slots.forEach(slot => {
                const candidate = byPos(slot.role).find(p => !used.has(p.id));
                if (candidate) {
                    picks.push(candidate.id);
                    used.add(candidate.id);
                }
            });
            sortedPlayers.filter(p => !p.injured && !used.has(p.id))
                .sort((a, b) => Number(b.overall || 0) - Number(a.overall || 0))
                .forEach(p => { if (picks.length < 11) picks.push(p.id); });
            return picks.slice(0, 11);
        };

        const fillBenchDefaults = () => {
            const used = new Set(state.starterIds.filter(Boolean).map(Number));
            const bench = state.benchIds.filter(id => id && !used.has(Number(id))).map(Number).slice(0, 7);
            bench.forEach(id => used.add(id));
            sortedPlayers
                .filter(p => !p.injured && !used.has(Number(p.id)))
                .forEach(p => {
                    if (bench.length < 7) {
                        bench.push(Number(p.id));
                        used.add(Number(p.id));
                    }
                });
            state.benchIds = bench;
        };

        const normalizeSelectionState = ({ autofillStarters = false, autofillBench = false } = {}) => {
            const slots = formationToSlots(state.formation);
            state.starterIds = Array.from({ length: 11 }, (_, idx) => Number(state.starterIds[idx] || 0) || null);
            state.benchIds = Array.from({ length: 7 }, (_, idx) => Number(state.benchIds[idx] || 0) || null);

            if (autofillStarters && state.starterIds.filter(Boolean).length < 11) {
                state.starterIds = pickDefaultStarters(slots);
            }
            const used = new Set();
            state.starterIds = slots.map((slot, idx) => {
                const id = Number(state.starterIds[idx] || 0);
                const p = sortedPlayers.find(sp => Number(sp.id) === id);
                if (!id || !p || p.injured || used.has(id) || !canPlayRole(p, slot.role)) {
                    return null;
                }
                used.add(id);
                return id;
            });

            if (autofillStarters) {
                slots.forEach((slot, idx) => {
                    if (state.starterIds[idx]) return;
                    const fallback = sortedPlayers.find(p =>
                        !p.injured &&
                        !used.has(Number(p.id)) &&
                        canPlayRole(p, slot.role));
                    if (fallback) {
                        state.starterIds[idx] = Number(fallback.id);
                        used.add(Number(fallback.id));
                    }
                });
            }

            const benchUsed = new Set(state.starterIds.filter(Boolean).map(Number));
            state.benchIds = state.benchIds.map(id => {
                const numericId = Number(id || 0);
                const p = getPlayerById(numericId);
                if (!numericId || !p || p.injured || benchUsed.has(numericId)) {
                    return null;
                }
                benchUsed.add(numericId);
                return numericId;
            });

            if (autofillBench) {
                fillBenchDefaults();
            }
        };

        const getPlayerById = id => sortedPlayers.find(p => Number(p.id) === Number(id));
        const selectedIds = () => new Set([
            ...state.starterIds.filter(Boolean).map(Number),
            ...state.benchIds.filter(Boolean).map(Number)
        ]);
        const candidatesForSlot = (role, currentId) => {
            const used = selectedIds();
            if (currentId) used.delete(Number(currentId));
            return sortedPlayers.filter(p => !p.injured && !used.has(Number(p.id)) && canPlayRole(p, role));
        };
        const benchCandidates = (currentId) => {
            const used = selectedIds();
            if (currentId) used.delete(Number(currentId));
            return sortedPlayers.filter(p => !p.injured && !used.has(Number(p.id)));
        };
        const poolCandidates = () => {
            const used = selectedIds();
            return sortedPlayers.filter(p => !p.injured && !used.has(Number(p.id)));
        };

        const clearPlayerFromState = (playerId) => {
            state.starterIds = state.starterIds.map(id => Number(id) === Number(playerId) ? null : id);
            state.benchIds = state.benchIds.map(id => Number(id) === Number(playerId) ? null : id);
        };

        const assignToBench = (playerId, targetIndex) => {
            const previous = Number(state.benchIds[targetIndex] || 0) || null;
            state.benchIds[targetIndex] = Number(playerId);
            return previous;
        };

        const assignToStarter = (playerId, targetIndex) => {
            const previous = Number(state.starterIds[targetIndex] || 0) || null;
            state.starterIds[targetIndex] = Number(playerId);
            return previous;
        };

        const renderDesktopDnD = (slots) => {
            const slotCards = slots.map((slot, idx) => {
                const selected = Number(state.starterIds[idx] || 0);
                const p = getPlayerById(selected);
                return `
                    <div class="lineup-slot-drop" data-zone="starter" data-index="${idx}" data-role="${slot.role}" style="padding:10px; border:1px dashed #466; border-radius:8px; background:rgba(255,255,255,0.03); min-height:62px;">
                        <div style="font-size:0.8em; color:#97a6a9; margin-bottom:6px;">${slot.label}</div>
                        ${p ? `<div class="lineup-draggable" draggable="true" data-player-id="${p.id}" data-from-zone="starter" data-from-index="${idx}" style="padding:7px; border-radius:6px; background:#1f2d3a; cursor:grab;">${escapeHtml(p.name)} (${escapeHtml(p.position)}, OVR ${p.overall ?? "-"})</div>`
                    : `<div style="color:#6f8188; font-size:0.86em;">Drop ${slot.role} player</div>`}
                    </div>
                `;
            }).join("");

            const benchSlots = Array.from({ length: 7 }).map((_, idx) => {
                const selected = Number(state.benchIds[idx] || 0);
                const p = getPlayerById(selected);
                return `
                    <div class="lineup-slot-drop" data-zone="bench" data-index="${idx}" style="padding:10px; border:1px dashed #5a5a4a; border-radius:8px; background:rgba(255,255,255,0.03); min-height:62px;">
                        <div style="font-size:0.8em; color:#97a6a9; margin-bottom:6px;">Bench ${idx + 1}</div>
                        ${p ? `<div class="lineup-draggable" draggable="true" data-player-id="${p.id}" data-from-zone="bench" data-from-index="${idx}" style="padding:7px; border-radius:6px; background:#2a2d1f; cursor:grab;">${escapeHtml(p.name)} (${escapeHtml(p.position)}, OVR ${p.overall ?? "-"})</div>`
                    : `<div style="color:#6f8188; font-size:0.86em;">Drop player</div>`}
                    </div>
                `;
            }).join("");

            const pool = poolCandidates().map(p => `
                <div class="lineup-draggable" draggable="true" data-player-id="${p.id}" data-from-zone="pool" data-from-index="-1" style="padding:7px; border-radius:6px; background:#25303d; cursor:grab; margin-bottom:6px;">
                    ${escapeHtml(p.name)} (${escapeHtml(p.position)}, OVR ${p.overall ?? "-"})
                </div>
            `).join("");

            return `
                <div style="display:grid; grid-template-columns:1fr 1fr; gap:14px;">
                    <div class="training-block">
                        <h4>Starting XI (Drag and drop)</h4>
                        <div style="display:grid; gap:8px;">${slotCards}</div>
                    </div>
                    <div class="training-block">
                        <h4>Bench (7)</h4>
                        <div style="display:grid; gap:8px; margin-bottom:10px;">${benchSlots}</div>
                        <h4>Available pool</h4>
                        <div class="lineup-slot-drop" data-zone="pool" data-index="-1" style="padding:10px; border:1px dashed #344; border-radius:8px; min-height:120px; max-height:320px; overflow:auto;">${pool || `<div style="color:#6f8188; font-size:0.86em;">No available players</div>`}</div>
                    </div>
                </div>
            `;
        };

        const renderMobileDropdown = (slots) => {
            const startersHtml = slots.map((slot, idx) => {
                const selected = Number(state.starterIds[idx] || 0);
                const options = candidatesForSlot(slot.role, selected);
                return `
                    <label class="training-group-row" style="margin-bottom:6px;">
                        <span class="group-tag">${slot.label}</span>
                        <select class="starter-select" data-slot="${idx}">
                            <option value="">-- Empty --</option>
                            ${options.map(p => `<option value="${p.id}" ${Number(p.id) === selected ? "selected" : ""}>${escapeHtml(p.name)} (${escapeHtml(p.position)}, OVR ${p.overall ?? "-"})</option>`).join("")}
                        </select>
                    </label>
                `;
            }).join("");

            const benchHtml = Array.from({ length: 7 }).map((_, idx) => {
                const selected = Number(state.benchIds[idx] || 0);
                const options = benchCandidates(selected);
                return `
                    <label class="training-group-row" style="margin-bottom:6px;">
                        <span class="group-tag">Bench ${idx + 1}</span>
                        <select class="bench-select" data-slot="${idx}">
                            <option value="">-- Empty --</option>
                            ${options.map(p => `<option value="${p.id}" ${Number(p.id) === selected ? "selected" : ""}>${escapeHtml(p.name)} (${escapeHtml(p.position)}, OVR ${p.overall ?? "-"})</option>`).join("")}
                        </select>
                    </label>
                `;
            }).join("");

            return `
                <div class="training-block" style="margin-top:14px;">
                    <h3>Starting XI</h3>
                    <p class="training-note">Mobile: dropdown selection with position filters and unique player lock.</p>
                    ${startersHtml}
                </div>
                <div class="training-block" style="margin-top:14px;">
                    <h3>Bench</h3>
                    ${benchHtml}
                </div>
            `;
        };

        const bindDesktopDnD = () => {
            let dragData = null;
            mainContent.querySelectorAll(".lineup-draggable").forEach(el => {
                el.addEventListener("dragstart", () => {
                    dragData = {
                        playerId: Number(el.dataset.playerId),
                        fromZone: el.dataset.fromZone,
                        fromIndex: Number(el.dataset.fromIndex)
                    };
                });
            });

            mainContent.querySelectorAll(".lineup-slot-drop").forEach(zone => {
                zone.addEventListener("dragover", (e) => {
                    e.preventDefault();
                });
                zone.addEventListener("drop", (e) => {
                    e.preventDefault();
                    if (!dragData || !dragData.playerId) return;
                    const targetZone = zone.dataset.zone;
                    const targetIndex = Number(zone.dataset.index);
                    const targetRole = zone.dataset.role || null;
                    const player = getPlayerById(dragData.playerId);
                    if (!player || player.injured) return;
                    const sourceStarterRole = dragData.fromZone === "starter"
                        ? formationToSlots(state.formation)[dragData.fromIndex]?.role
                        : null;

                    if (targetZone === "starter") {
                        if (!canPlayRole(player, targetRole)) return;
                        clearPlayerFromState(dragData.playerId);
                        const displaced = assignToStarter(dragData.playerId, targetIndex);
                        if (dragData.fromZone === "starter" && dragData.fromIndex >= 0 && targetIndex !== dragData.fromIndex) {
                            if (displaced && canPlayRole(getPlayerById(displaced), sourceStarterRole)) {
                                state.starterIds[dragData.fromIndex] = displaced;
                            } else {
                                state.starterIds[dragData.fromIndex] = null;
                            }
                        } else if (dragData.fromZone === "bench" && dragData.fromIndex >= 0) {
                            state.benchIds[dragData.fromIndex] = displaced || null;
                        }
                    } else if (targetZone === "bench") {
                        clearPlayerFromState(dragData.playerId);
                        const displaced = assignToBench(dragData.playerId, targetIndex);
                        if (dragData.fromZone === "starter" && dragData.fromIndex >= 0) {
                            const displacedPlayer = getPlayerById(displaced);
                            if (displacedPlayer && canPlayRole(displacedPlayer, sourceStarterRole)) {
                                state.starterIds[dragData.fromIndex] = displaced;
                            } else {
                                state.starterIds[dragData.fromIndex] = null;
                            }
                        } else if (dragData.fromZone === "bench" && dragData.fromIndex >= 0 && targetIndex !== dragData.fromIndex) {
                            state.benchIds[dragData.fromIndex] = displaced || null;
                        }
                    } else if (targetZone === "pool") {
                        if (dragData.fromZone === "starter" && dragData.fromIndex >= 0) {
                            state.starterIds[dragData.fromIndex] = null;
                        }
                        if (dragData.fromZone === "bench" && dragData.fromIndex >= 0) {
                            state.benchIds[dragData.fromIndex] = null;
                        }
                    }
                    normalizeSelectionState();
                    render();
                });
            });
        };

        const bindMobileSelects = () => {
            mainContent.querySelectorAll('.starter-select').forEach(sel => {
                sel.addEventListener("change", () => {
                    const slot = Number(sel.getAttribute("data-slot"));
                    const id = Number(sel.value || 0) || null;
                    if (id) {
                        state.starterIds = state.starterIds.map((val, idx) => idx !== slot && Number(val) === id ? null : val);
                        state.benchIds = state.benchIds.map(val => Number(val) === id ? null : val);
                    }
                    state.starterIds[slot] = id;
                    normalizeSelectionState();
                    render();
                });
            });
            mainContent.querySelectorAll('.bench-select').forEach(sel => {
                sel.addEventListener("change", () => {
                    const slot = Number(sel.getAttribute("data-slot"));
                    const id = Number(sel.value || 0) || null;
                    if (id) {
                        state.starterIds = state.starterIds.map(val => Number(val) === id ? null : val);
                        state.benchIds = state.benchIds.map((val, idx) => idx !== slot && Number(val) === id ? null : val);
                    }
                    state.benchIds[slot] = id;
                    normalizeSelectionState();
                    render();
                });
            });
        };

        normalizeSelectionState({ autofillStarters: true, autofillBench: true });

        const render = () => {
            normalizeSelectionState();
            const slots = formationToSlots(state.formation);
            const injuredCount = players.filter(p => p.injured).length;

            let html = `<div class="fm-page fm-page--club">
                <section class="fm-panel fm-club-hero">
                    <button class="back-to-dashboard" data-nav-back="dashboard">Back</button>
                    <div class="fm-club-hero-main">
                        <div>
                            <div class="fm-eyebrow">Club tactics</div>
                            <h2>Tactic Editor</h2>
                            <p class="fm-subtle">Manage the base shape, XI, bench, and the advanced tactics editor flow from one club screen.</p>
                        </div>
                        ${buildClubActionsHtml(currentPageId === 'tacticEditor' ? 'tacticEditor' : 'formations')}
                    </div>
                    <div class="fm-medical-stat-grid team-summary-grid">
                        <div><strong>${state.starterIds.filter(Boolean).length}/11</strong><span>Starting XI</span></div>
                        <div><strong>${state.benchIds.filter(Boolean).length}/7</strong><span>Bench</span></div>
                        <div><strong>${escapeHtml(state.formation)}</strong><span>Shape</span></div>
                        <div><strong>${injuredCount}</strong><span>Unavailable</span></div>
                    </div>
                </section>
                <section class="fm-panel">
                <div class="fm-panel-head">
                    <div>
                        <h3>Tactics board</h3>
                        <p class="fm-subtle">Pick the base shape, choose style, and save the exact XI + bench order.</p>
                    </div>
                    <span class="fm-panel-action">${escapeHtml(state.style)}</span>
                </div>
                <h3>Formation: <span id="currentFormation">${escapeHtml(state.formation)}</span></h3>
                <div class="cs-tactics-grid">`;

            availableFormations.forEach(f => {
                const active = f === state.formation ? "active" : "";
                html += `<div class="cs-tactics-btn ${active}" data-formation="${escapeHtml(f)}">${escapeHtml(f)}</div>`;
            });
            html += `</div>
                <h3 style="margin-top:20px;">Style: <span id="currentStyle">${escapeHtml(state.style)}</span></h3>
                <div class="cs-tactics-grid">`;
            availableStyles.forEach(s => {
                const active = s === state.style ? "active" : "";
                html += `<div class="cs-tactics-btn ${active}" data-style="${escapeHtml(s)}">${escapeHtml(s)}</div>`;
            });
            html += `</div>
                <p class="training-note" style="margin-top:12px;">Formation slots: DEF ${slots.filter(s => s.role === "DEF").length}, MID ${slots.filter(s => s.role === "MID").length}, ATT ${slots.filter(s => s.role === "ATT").length}. Injured unavailable: ${injuredCount}.</p>`;

            html += isMobile ? renderMobileDropdown(slots) : renderDesktopDnD(slots);
            html += `
                <div class="training-actions" style="margin-top:14px;">
                    <button id="save-tactics-main" class="big-button">Save Tactics + XI + Bench</button>
                </div>
                <p style="margin-top:14px; color:#9aa0a6;">Desktop uses drag & drop. Mobile uses filtered dropdowns with unique player lock.</p>
            </section>
            </div>`;
            mainContent.innerHTML = html;

            mainContent.querySelectorAll('.cs-tactics-btn[data-formation]').forEach(btn => {
                btn.addEventListener('click', () => {
                    state.formation = btn.getAttribute("data-formation");
                    state.starterIds = new Array(11).fill(null);
                    normalizeSelectionState({ autofillStarters: true, autofillBench: true });
                    render();
                });
            });
            mainContent.querySelectorAll('.cs-tactics-btn[data-style]').forEach(btn => {
                btn.addEventListener('click', () => {
                    state.style = btn.getAttribute("data-style");
                    render();
                });
            });
            if (isMobile) bindMobileSelects();
            else bindDesktopDnD();

            const saveBtn = document.getElementById("save-tactics-main");
            if (saveBtn) {
                saveBtn.addEventListener("click", async () => {
                    saveBtn.disabled = true;
                    const dedupStarter = [];
                    const used = new Set();
                    state.starterIds.forEach(id => {
                        if (!id || used.has(id) || !getPlayerById(id)) return;
                        used.add(id);
                        dedupStarter.push(id);
                    });
                    const dedupBench = [];
                    state.benchIds.forEach(id => {
                        if (!id || used.has(id) || !getPlayerById(id)) return;
                        used.add(id);
                        dedupBench.push(id);
                    });

                    const res = await authFetch(`/teams/${currentUserTeamId}/lineup-template`, {
                        method: "PUT",
                        headers: { "Content-Type": "application/json" },
                        body: JSON.stringify({ formation: state.formation, style: state.style, starterIds: dedupStarter, benchIds: dedupBench })
                    });
                    let savedPayload = null;
                    if (res.ok) {
                        savedPayload = await res.json();
                        state.formation = savedPayload?.formation || state.formation;
                        state.style = savedPayload?.style || state.style;
                        state.starterIds = Array.isArray(savedPayload?.starterIds)
                            ? savedPayload.starterIds.map(Number).filter(Number.isFinite).slice(0, 11)
                            : state.starterIds;
                        state.benchIds = Array.isArray(savedPayload?.benchIds)
                            ? savedPayload.benchIds.map(Number).filter(Number.isFinite).slice(0, 7)
                            : state.benchIds;
                        localStorage.setItem("main_app_tactics_formation", state.formation);
                        localStorage.setItem("main_app_tactics_style", state.style);
                    }
                    saveBtn.disabled = false;
                    saveBtn.textContent = res.ok ? "Saved" : "Save failed";
                    setTimeout(() => { saveBtn.textContent = "Save Tactics + XI + Bench"; }, 1400);
                });
            }
        };

        render();
    }
    async function loadTacticEditor() {
        const mainContent = document.getElementById("main-content");
        const DRAFT_KEY = `te_draft_${currentUserTeamId}`;
        const FORMATION_KEY = `te_requested_formation_${currentUserTeamId}`;
        const POSSESSION_OPTS = ['WE_HAVE_BALL', 'OPPONENT_HAS_BALL'];
        const availableStyles = ["BALANCED", "ATTACKING", "DEFENSIVE", "COUNTER", "POSSESSION", "HIGH_PRESS", "DIRECT"];
        const availableFormations = ["4-4-2", "4-3-3", "4-2-3-1", "4-1-4-1", "4-5-1", "3-5-2", "3-4-3", "3-4-2-1", "5-3-2", "5-4-1"];
        const requestedFormation = localStorage.getItem(FORMATION_KEY) || '';
        const editorUrl = requestedFormation
            ? `/teams/${currentUserTeamId}/tactics-editor?formation=${encodeURIComponent(requestedFormation)}`
            : `/teams/${currentUserTeamId}/tactics-editor`;

        const editorRes = await authFetch(editorUrl);
        if (!editorRes.ok) {
            mainContent.innerHTML = `<div class="fm-page"><section class="fm-panel"><div class="fm-empty">Failed to load tactic editor data.</div></section></div>`;
            return;
        }
        const editor = await editorRes.json();

        let draft = null;
        try {
            const raw = localStorage.getItem(DRAFT_KEY);
            if (raw) draft = JSON.parse(raw);
        } catch (_) {
            draft = null;
        }

        const slots = Array.isArray(editor.slotDefinitions) ? editor.slotDefinitions : [];
        const slotKeys = slots.map(slot => slot.slotKey);
        const slotByKey = new Map(slots.map(slot => [slot.slotKey, slot]));
        const ballStates = Array.isArray(editor.supportedBallStates) ? editor.supportedBallStates : [];
        const targetCells = Array.isArray(editor.supportedTargetCells) ? editor.supportedTargetCells : [];
        const cornerStates = [
            'ATTACK_LEFT_CORNER',
            'ATTACK_RIGHT_CORNER',
            'DEFEND_LEFT_CORNER',
            'DEFEND_RIGHT_CORNER'
        ].filter(stateKey => ballStates.includes(stateKey));
        const centerBallState = ballStates.includes('CELL_2_2') ? 'CELL_2_2' : (ballStates[0] || 'CELL_2_2');
        const currentStarterIds = Array.isArray(editor.starterIds) ? editor.starterIds : [];
        const currentBenchIds = Array.isArray(editor.benchIds) ? editor.benchIds : [];

        const buildRulesMap = (rules) => {
            const map = {};
            (rules || []).forEach(rule => {
                if (rule?.slotKey && rule?.ballStateKey && rule?.possessionContext && rule?.targetCellKey) {
                    map[`${rule.slotKey}|${rule.ballStateKey}|${rule.possessionContext}`] = rule.targetCellKey;
                }
            });
            return map;
        };

        const useDraft = !!(draft
            && draft.formation === editor.formation
            && typeof draft.draftVersion === 'number'
            && draft.draftVersion >= (editor.version || 0));

        const setPiecesSource = useDraft
            ? (draft.setPieceAssignments || editor.setPieceAssignments || {})
            : (editor.setPieceAssignments || {});

        const state = {
            formation: useDraft ? (draft.formation || editor.formation) : editor.formation,
            style: useDraft ? (draft.style || editor.style) : editor.style,
            rulesMap: buildRulesMap(useDraft ? draft.movementRules : editor.movementRules),
            setPieces: {
                penaltyTakerSlot: setPiecesSource.penaltyTakerSlot || '',
                freeKickLeftTakerSlot: setPiecesSource.freeKickLeftTakerSlot || '',
                freeKickRightTakerSlot: setPiecesSource.freeKickRightTakerSlot || '',
                cornerLeftTakerSlot: setPiecesSource.cornerLeftTakerSlot || '',
                cornerRightTakerSlot: setPiecesSource.cornerRightTakerSlot || '',
            },
            draftVersion: useDraft ? draft.draftVersion : (editor.version || 0),
            activePossession: useDraft && POSSESSION_OPTS.includes(draft.activePossession) ? draft.activePossession : 'WE_HAVE_BALL',
            activeBallState: useDraft && ballStates.includes(draft.activeBallState) ? draft.activeBallState : centerBallState,
            focusedSlot: useDraft && slotKeys.includes(draft.focusedSlot) ? draft.focusedSlot : (slotKeys[0] || ''),
        };

        const serializeRules = () => Object.entries(state.rulesMap).map(([compoundKey, targetCellKey]) => {
            const [slotKey, ballStateKey, possessionContext] = compoundKey.split('|');
            return { slotKey, ballStateKey, possessionContext, targetCellKey };
        });

        const saveDraft = () => {
            try {
                localStorage.setItem(DRAFT_KEY, JSON.stringify({
                    formation: state.formation,
                    style: state.style,
                    movementRules: serializeRules(),
                    setPieceAssignments: state.setPieces,
                    draftVersion: state.draftVersion,
                    activePossession: state.activePossession,
                    activeBallState: state.activeBallState,
                    focusedSlot: state.focusedSlot,
                }));
            } catch (_) {
                // ignore localStorage failures
            }
        };

        const getRule = (slotKey, ballStateKey, possessionContext) => state.rulesMap[`${slotKey}|${ballStateKey}|${possessionContext}`] || '';
        const getSlotTarget = (slotKey, ballStateKey, possessionContext) => getRule(slotKey, ballStateKey, possessionContext)
            || slotByKey.get(slotKey)?.anchorCellKey
            || 'CELL_2_2';
        const setRule = (slotKey, ballStateKey, possessionContext, targetCellKey) => {
            const key = `${slotKey}|${ballStateKey}|${possessionContext}`;
            if (targetCellKey) state.rulesMap[key] = targetCellKey;
            else delete state.rulesMap[key];
            saveDraft();
        };

        const clearFocusedRule = () => {
            if (!state.focusedSlot || !state.activeBallState) return;
            setRule(state.focusedSlot, state.activeBallState, state.activePossession, '');
        };

        const clearFocusedSlotRules = () => {
            if (!state.focusedSlot) return;
            Object.keys(state.rulesMap).forEach(key => {
                if (key.startsWith(`${state.focusedSlot}|`)) delete state.rulesMap[key];
            });
            saveDraft();
        };

        const hasDraft = () => useDraft;
        const parseCellKey = (cellKey) => {
            const match = String(cellKey || '').match(/^CELL_([0-4])_([0-4])$/);
            return match ? [Number(match[1]), Number(match[2])] : [2, 2];
        };
        const cellLabel = (key) => {
            if (!key) return '—';
            if (key === 'ATTACK_LEFT_CORNER') return 'Attack left corner';
            if (key === 'ATTACK_RIGHT_CORNER') return 'Attack right corner';
            if (key === 'DEFEND_LEFT_CORNER') return 'Defend left corner';
            if (key === 'DEFEND_RIGHT_CORNER') return 'Defend right corner';
            const [progress, width] = parseCellKey(key);
            return `${['DEF', 'DEF+', 'MID', 'ATK+', 'ATK'][progress]} · ${['L', 'CL', 'C', 'CR', 'R'][width]}`;
        };
        const toPitchPosition = (cellKey) => {
            const [progress, width] = parseCellKey(cellKey);
            return {
                left: `${(width + 0.5) * 20}%`,
                top: `${(4.5 - progress) * 20}%`
            };
        };
        const focusedSlotMeta = () => slotByKey.get(state.focusedSlot) || null;

        const buildCellZonesHtml = () => targetCells.map(cellKey => {
            const [progress, width] = parseCellKey(cellKey);
            const row = 4 - progress;
            const isActiveBall = state.activeBallState === cellKey;
            return `
                <div class="te-drop-cell ${isActiveBall ? 'is-ball-active' : ''}"
                     data-te-drop="cell"
                     data-ball-state="${cellKey}"
                     data-target-cell="${cellKey}"
                     style="--te-col:${width}; --te-row:${row};">
                    <span class="te-cell-label">${escapeHtml(cellLabel(cellKey))}</span>
                </div>`;
        }).join('');

        const cornerMeta = {
            ATTACK_LEFT_CORNER: { title: 'Atk left corner', cls: 'is-attack-left' },
            ATTACK_RIGHT_CORNER: { title: 'Atk right corner', cls: 'is-attack-right' },
            DEFEND_LEFT_CORNER: { title: 'Def left corner', cls: 'is-defend-left' },
            DEFEND_RIGHT_CORNER: { title: 'Def right corner', cls: 'is-defend-right' },
        };

        const buildCornerZonesHtml = () => cornerStates.map(ballStateKey => `
            <div class="te-corner-zone ${cornerMeta[ballStateKey]?.cls || ''} ${state.activeBallState === ballStateKey ? 'is-ball-active' : ''}"
                 data-te-drop="corner"
                 data-ball-state="${ballStateKey}">
                <span>${escapeHtml(cornerMeta[ballStateKey]?.title || ballStateKey)}</span>
                ${state.activeBallState === ballStateKey ? `<div class="te-ball-marker" draggable="true" data-te-ball="true" title="Drag ball to change ball state"></div>` : ''}
            </div>`).join('');

        const buildMarkerOffsets = (memberCount, reserveCenter) => {
            const defaultOffsets = {
                1: [{ x: 0, y: 0 }],
                2: [{ x: -18, y: 0 }, { x: 18, y: 0 }],
                3: [{ x: 0, y: -16 }, { x: -18, y: 14 }, { x: 18, y: 14 }],
                4: [{ x: -18, y: -14 }, { x: 18, y: -14 }, { x: -18, y: 14 }, { x: 18, y: 14 }],
                many: [
                    { x: 0, y: 0 },
                    { x: -18, y: 0 },
                    { x: 18, y: 0 },
                    { x: 0, y: -18 },
                    { x: 0, y: 18 },
                    { x: -18, y: -18 },
                    { x: 18, y: -18 },
                    { x: -18, y: 18 },
                    { x: 18, y: 18 },
                    { x: -30, y: 0 },
                    { x: 30, y: 0 },
                ],
            };
            const ballSafeOffsets = {
                1: [{ x: -18, y: 0 }],
                2: [{ x: -18, y: 0 }, { x: 18, y: 0 }],
                3: [{ x: -18, y: -12 }, { x: 18, y: -12 }, { x: 0, y: 18 }],
                4: [{ x: -18, y: -12 }, { x: 18, y: -12 }, { x: -18, y: 16 }, { x: 18, y: 16 }],
                many: [
                    { x: -18, y: 0 },
                    { x: 18, y: 0 },
                    { x: -18, y: 16 },
                    { x: 18, y: 16 },
                    { x: -18, y: -16 },
                    { x: 18, y: -16 },
                    { x: 0, y: 24 },
                    { x: 0, y: -24 },
                    { x: -30, y: 0 },
                    { x: 30, y: 0 },
                    { x: 0, y: 36 },
                ],
            };
            const source = reserveCenter ? ballSafeOffsets : defaultOffsets;
            return source[memberCount] || source.many.slice(0, memberCount);
        };

        const buildBallMarkerHtml = () => {
            if (!targetCells.includes(state.activeBallState)) return '';
            const pos = toPitchPosition(state.activeBallState);
            return `
                <div class="te-ball-marker"
                     draggable="true"
                     data-te-ball="true"
                     style="left:${pos.left}; top:${pos.top};"
                     title="Drag ball to change ball state"></div>`;
        };

        const buildPlayerMarkersHtml = () => {
            const byCell = {};
            slots.forEach(slot => {
                const cellKey = getSlotTarget(slot.slotKey, state.activeBallState, state.activePossession);
                if (!byCell[cellKey]) byCell[cellKey] = [];
                byCell[cellKey].push(slot);
            });
            return Object.entries(byCell).flatMap(([cellKey, members]) => {
                const pos = toPitchPosition(cellKey);
                const offsets = buildMarkerOffsets(members.length, cellKey === state.activeBallState);
                return members
                    .sort((left, right) => Number(left.order || 0) - Number(right.order || 0))
                    .map((slot, idx) => {
                        const offset = offsets[idx] || { x: idx % 2 === 0 ? -30 : 30, y: 18 * Math.floor(idx / 2) };
                        const isFocused = slot.slotKey === state.focusedSlot;
                        return `
                            <button type="button"
                                    class="te-player-marker ${isFocused ? 'is-focused' : ''}"
                                    draggable="true"
                                    data-te-slot-marker="${slot.slotKey}"
                                    style="left:${pos.left}; top:${pos.top}; --te-offset-x:${offset.x}px; --te-offset-y:${offset.y}px;"
                                    title="${escapeHtml(slot.slotKey)} · ${escapeHtml(cellLabel(cellKey))}">
                                <span class="te-player-marker-label">${escapeHtml(slot.slotKey)}</span>
                            </button>`;
                    });
            }).join('');
        };

        const renderSetPieceSelects = () => {
            const renderSelect = (field, label) => {
                const options = ['', ...slotKeys].map(slotKey => {
                    const selected = state.setPieces[field] === slotKey ? 'selected' : '';
                    return `<option value="${escapeHtml(slotKey)}" ${selected}>${escapeHtml(slotKey || '-- None --')}</option>`;
                }).join('');
                return `<label class="te-sp-label">${escapeHtml(label)}<select class="te-sp-select" data-sp-field="${field}">${options}</select></label>`;
            };
            return `
                <div class="te-sp-grid">
                    ${renderSelect('penaltyTakerSlot', 'Penalty')}
                    ${renderSelect('freeKickLeftTakerSlot', 'FK Left')}
                    ${renderSelect('freeKickRightTakerSlot', 'FK Right')}
                    ${renderSelect('cornerLeftTakerSlot', 'Corner Left')}
                    ${renderSelect('cornerRightTakerSlot', 'Corner Right')}
                </div>`;
        };

        const readDragPayload = (event) => {
            try {
                return JSON.parse(event.dataTransfer?.getData('text/plain') || '{}');
            } catch (_) {
                return null;
            }
        };

        const bindEvents = () => {
            mainContent.querySelectorAll('[data-te-formation]').forEach(btn => {
                btn.addEventListener('click', async () => {
                    const nextFormation = btn.getAttribute('data-te-formation') || state.formation;
                    localStorage.setItem(FORMATION_KEY, nextFormation);
                    await loadTacticEditor();
                });
            });

            mainContent.querySelectorAll('[data-te-style]').forEach(btn => {
                btn.addEventListener('click', () => {
                    state.style = btn.getAttribute('data-te-style') || state.style;
                    saveDraft();
                    render();
                });
            });

            mainContent.querySelectorAll('[data-te-possession]').forEach(btn => {
                btn.addEventListener('click', () => {
                    state.activePossession = btn.getAttribute('data-te-possession') || state.activePossession;
                    saveDraft();
                    render();
                });
            });

            mainContent.querySelectorAll('[data-te-slot-marker]').forEach(marker => {
                marker.addEventListener('click', () => {
                    state.focusedSlot = marker.getAttribute('data-te-slot-marker') || state.focusedSlot;
                    render();
                });
                marker.addEventListener('dragstart', event => {
                    state.focusedSlot = marker.getAttribute('data-te-slot-marker') || state.focusedSlot;
                    event.dataTransfer?.setData('text/plain', JSON.stringify({
                        type: 'slot',
                        slotKey: state.focusedSlot,
                    }));
                });
            });

            mainContent.querySelectorAll('[data-te-ball="true"]').forEach(ball => {
                ball.addEventListener('dragstart', event => {
                    event.dataTransfer?.setData('text/plain', JSON.stringify({ type: 'ball' }));
                });
            });

            mainContent.querySelectorAll('[data-te-drop]').forEach(zone => {
                zone.addEventListener('click', () => {
                    const ballStateKey = zone.getAttribute('data-ball-state');
                    if (ballStateKey) {
                        state.activeBallState = ballStateKey;
                        saveDraft();
                        render();
                    }
                });
                zone.addEventListener('dragover', event => {
                    event.preventDefault();
                    zone.classList.add('is-drag-over');
                });
                zone.addEventListener('dragleave', () => zone.classList.remove('is-drag-over'));
                zone.addEventListener('drop', event => {
                    event.preventDefault();
                    zone.classList.remove('is-drag-over');
                    const payload = readDragPayload(event);
                    if (!payload?.type) return;
                    const ballStateKey = zone.getAttribute('data-ball-state') || '';
                    const targetCellKey = zone.getAttribute('data-target-cell') || '';
                    if (payload.type === 'ball' && ballStateKey) {
                        state.activeBallState = ballStateKey;
                        saveDraft();
                        render();
                        return;
                    }
                    if (payload.type === 'slot' && payload.slotKey && targetCellKey) {
                        state.focusedSlot = payload.slotKey;
                        setRule(payload.slotKey, state.activeBallState, state.activePossession, targetCellKey);
                        render();
                    }
                });
            });

            mainContent.querySelectorAll('[data-sp-field]').forEach(select => {
                select.addEventListener('change', () => {
                    const field = select.getAttribute('data-sp-field');
                    if (!field) return;
                    state.setPieces[field] = select.value || '';
                    saveDraft();
                });
            });

            const clearActiveBtn = document.getElementById('te-clear-active');
            if (clearActiveBtn) {
                clearActiveBtn.addEventListener('click', () => {
                    clearFocusedRule();
                    render();
                });
            }

            const clearSlotBtn = document.getElementById('te-clear-slot');
            if (clearSlotBtn) {
                clearSlotBtn.addEventListener('click', () => {
                    clearFocusedSlotRules();
                    render();
                });
            }

            const discardDraftBtn = document.getElementById('te-discard-draft');
            if (discardDraftBtn) {
                discardDraftBtn.addEventListener('click', async () => {
                    localStorage.removeItem(DRAFT_KEY);
                    await loadTacticEditor();
                });
            }

            const saveBtn = document.getElementById('te-save-btn');
            if (saveBtn) {
                saveBtn.addEventListener('click', async () => {
                    saveBtn.disabled = true;
                    try {
                        await authFetch(`/teams/${currentUserTeamId}/tactics-editor`, {
                            method: 'PUT',
                            headers: { 'Content-Type': 'application/json' },
                            body: JSON.stringify({
                                formation: state.formation,
                                style: state.style,
                                starterIds: currentStarterIds,
                                benchIds: currentBenchIds,
                                movementRules: serializeRules(),
                                setPieceAssignments: state.setPieces,
                            })
                        });
                        localStorage.removeItem(DRAFT_KEY);
                        localStorage.setItem(FORMATION_KEY, state.formation);
                        saveBtn.textContent = 'Saved';
                        await loadTacticEditor();
                    } catch (error) {
                        console.error('Failed to save tactic editor', error);
                        saveBtn.disabled = false;
                        saveBtn.textContent = 'Save failed';
                        setTimeout(() => { saveBtn.textContent = 'Save Tactic Editor'; }, 1400);
                    }
                });
            }
        };

        const render = () => {
            const draftLoaded = hasDraft();
            const focusedMeta = focusedSlotMeta();
            const focusedTarget = state.focusedSlot
                ? getSlotTarget(state.focusedSlot, state.activeBallState, state.activePossession)
                : '';
            const ruleCount = Object.keys(state.rulesMap).length;
            const formationButtons = availableFormations.map(formation => `
                <button type="button" class="cs-tactics-btn ${formation === state.formation ? 'active' : ''}" data-te-formation="${escapeHtml(formation)}">${escapeHtml(formation)}</button>`).join('');
            const styleButtons = availableStyles.map(style => `
                <button type="button" class="cs-tactics-btn ${style === state.style ? 'active' : ''}" data-te-style="${escapeHtml(style)}">${escapeHtml(style)}</button>`).join('');
            const possessionButtons = POSSESSION_OPTS.map(ctx => `
                <button type="button" class="te-toggle ${ctx === state.activePossession ? 'active' : ''}" data-te-possession="${ctx}">${ctx === 'WE_HAVE_BALL' ? 'We have ball' : 'Opponent has ball'}</button>`).join('');

            mainContent.innerHTML = `
                <div class="fm-page fm-page--club">
                    <section class="fm-panel fm-club-hero">
                        <button class="back-to-dashboard" data-nav-back="dashboard">Back</button>
                        <div class="fm-club-hero-main">
                            <div>
                                <div class="fm-eyebrow">Club tactics</div>
                                <h2>Tactic Editor</h2>
                                <p class="fm-subtle">Drag the ball to any 5x5 zone or corner state, then drag slot circles to redraw the team shape for that exact situation.</p>
                            </div>
                            ${buildClubActionsHtml('tacticEditor')}
                        </div>
                        <div class="fm-medical-stat-grid team-summary-grid">
                            <div><strong>${escapeHtml(state.formation || '4-4-2')}</strong><span>Shape</span></div>
                            <div><strong>${slots.length}</strong><span>Slots</span></div>
                            <div><strong>${ruleCount}</strong><span>Rules</span></div>
                            <div><strong>${draftLoaded ? 'Draft' : `v${editor.version || 0}`}</strong><span>Status</span></div>
                        </div>
                    </section>

                    <section class="fm-panel">
                        <div class="fm-panel-head">
                            <div>
                                <h3>Base setup</h3>
                                <p class="fm-subtle">Changing formation reloads slot anchors from the backend. Style and visual edits autosave locally until you press Save.</p>
                            </div>
                            <span class="fm-panel-action">${escapeHtml(state.style || 'BALANCED')}</span>
                        </div>
                        <div class="cs-tactics-grid">${formationButtons}</div>
                        <div class="cs-tactics-grid te-style-grid">${styleButtons}</div>
                    </section>

                    <div class="te-board-layout">
                        <section class="fm-panel te-pitch-card">
                            <div class="fm-panel-head">
                                <div>
                                    <h3>Visual movement editor</h3>
                                    <p class="fm-subtle">Attack corners are on top, defend corners at the bottom. Multiple slots may share one zone.</p>
                                </div>
                                <span class="fm-panel-action">${escapeHtml(cellLabel(state.activeBallState))}</span>
                            </div>

                            <div class="te-toggle-row te-toggle-row--top">${possessionButtons}</div>

                            <div class="te-info-strip">
                                <div class="te-info-pill"><strong>Ball state</strong><span>${escapeHtml(cellLabel(state.activeBallState))}</span></div>
                                <div class="te-info-pill"><strong>Focused slot</strong><span>${escapeHtml(state.focusedSlot || '—')}</span></div>
                                <div class="te-info-pill"><strong>Focused target</strong><span>${escapeHtml(cellLabel(focusedTarget))}</span></div>
                            </div>

                            <div class="te-pitch-stage">
                                ${buildCornerZonesHtml()}
                                <div class="te-pitch-board">
                                    <div class="te-pitch-surface">
                                        <div class="te-pitch-lines te-pitch-line--mid"></div>
                                        <div class="te-pitch-lines te-pitch-line--circle"></div>
                                        <div class="te-pitch-lines te-pitch-line--top-box"></div>
                                        <div class="te-pitch-lines te-pitch-line--top-six"></div>
                                        <div class="te-pitch-lines te-pitch-line--bottom-box"></div>
                                        <div class="te-pitch-lines te-pitch-line--bottom-six"></div>
                                    </div>
                                    <div class="te-drop-grid">${buildCellZonesHtml()}</div>
                                    <div class="te-player-layer">${buildPlayerMarkersHtml()}</div>
                                    <div class="te-ball-layer">${buildBallMarkerHtml()}</div>
                                </div>
                            </div>

                            <div class="te-instruction-row">
                                <span><strong>Ball:</strong> drag to 25 zones + 4 corners</span>
                                <span><strong>Slots:</strong> drag any circle to a new 5x5 zone for the active ball state</span>
                                <span><strong>Focus:</strong> click a slot circle to inspect / clear it</span>
                            </div>
                        </section>

                        <section class="fm-panel te-side-card">
                            <div class="fm-panel-head">
                                <div>
                                    <h3>Focused slot & save</h3>
                                    <p class="fm-subtle">Slot-based set-pieces stay separate from the visual pitch editor.</p>
                                </div>
                                <span class="fm-panel-action">${draftLoaded ? 'Local draft loaded' : 'Server profile'}</span>
                            </div>

                            <div class="te-note-box">
                                <strong>Focused slot:</strong> ${escapeHtml(focusedMeta?.slotKey || '—')}<br>
                                <strong>Line / role:</strong> ${escapeHtml(focusedMeta?.line || '—')} / ${escapeHtml(focusedMeta?.role || '—')}<br>
                                <strong>Anchor:</strong> ${escapeHtml(focusedMeta?.anchorCellKey || '—')}<br>
                                <strong>Current target:</strong> ${escapeHtml(cellLabel(focusedTarget))}
                            </div>

                            <div class="training-actions te-actions-stack">
                                <button type="button" id="te-clear-active" class="fm-action-btn secondary">Clear focused slot in this ball state</button>
                                <button type="button" id="te-clear-slot" class="fm-action-btn secondary">Clear all rules for focused slot</button>
                            </div>

                            <div class="te-side-divider"></div>
                            ${renderSetPieceSelects()}

                            <div class="training-actions te-actions-stack">
                                <button type="button" id="te-save-btn" class="big-button">Save Tactic Editor</button>
                                <button type="button" id="te-discard-draft" class="fm-action-btn secondary">Discard local draft</button>
                            </div>
                        </section>
                    </div>
                </div>`;

            bindEvents();
        };

        render();
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
    async function loadTrainingReports() {
        const mainContent = document.getElementById("main-content");
        const playersRes = await authFetch(`/teams/${currentUserTeamId}/players`);
        if (!playersRes.ok) {
            mainContent.innerHTML = `
                <div class="fm-page fm-page--club">
                    <section class="fm-panel fm-club-hero">
                        <button class="back-to-dashboard" data-nav-back="dashboard">Back</button>
                        <div class="fm-club-hero-main">
                            <div>
                                <div class="fm-eyebrow">Training ground</div>
                                <h2>Training Setup</h2>
                                <p class="fm-subtle">Could not load player data for the training setup page.</p>
                            </div>
                            ${buildTrainingActionsHtml('trainingSetup')}
                        </div>
                        <div class="fm-medical-stat-grid team-summary-grid">
                            <div><strong>0</strong><span>Players</span></div>
                            <div><strong>0</strong><span>Advanced slots</span></div>
                            <div><strong>0</strong><span>Formation pool</span></div>
                            <div><strong>4</strong><span>Training groups</span></div>
                        </div>
                    </section>
                </div>`;
            return;
        }
        const players = await playersRes.json();

        const GROUPS = ["GK", "DEF", "MID", "ATT"];
        const SKILLS_ALL = ["pace", "defending", "technique", "passing"];
        const SKILLS_BY_GROUP = {
            GK: ["goalkeeper", ...SKILLS_ALL],
            DEF: ["defending", ...SKILLS_ALL.filter(s => s !== "defending")],
            MID: ["playmaker", ...SKILLS_ALL],
            ATT: ["shooting", ...SKILLS_ALL]
        };
        const ROLE_OPTIONS = ["GK", "DEF", "MID", "ATT"];
        const defaultGroupSkills = { GK: "goalkeeper", DEF: "defending", MID: "playmaker", ATT: "shooting" };

        const setupRes = await authFetch(`/training/setup/team/${currentUserTeamId}`);
        const setup = setupRes.ok ? await setupRes.json() : null;

        const state = {
            groupSkills: {
                GK: setup?.groupSkills?.GK || defaultGroupSkills.GK,
                DEF: setup?.groupSkills?.DEF || defaultGroupSkills.DEF,
                MID: setup?.groupSkills?.MID || defaultGroupSkills.MID,
                ATT: setup?.groupSkills?.ATT || defaultGroupSkills.ATT
            },
            advanced: Array.isArray(setup?.advancedAssignments) ? setup.advancedAssignments.slice(0, 10).map(a => ({
                playerId: Number(a.playerId),
                role: ROLE_OPTIONS.includes((a.role || "").toUpperCase()) ? a.role.toUpperCase() : "MID"
            })) : [],
            general: [],
            selectedReport: null,
            selectedPlayerGraph: null,
            loadingReport: false
        };

        const allIds = new Set(players.map(p => p.id));
        state.advanced = state.advanced.filter(a => allIds.has(a.playerId));
        const advancedIds = new Set(state.advanced.map(a => a.playerId));
        state.general = players.map(p => p.id).filter(id => !advancedIds.has(id));

        const getPlayer = id => players.find(p => p.id === id);
        const skillLabel = skill => skill.charAt(0).toUpperCase() + skill.slice(1);
        const playerBadge = p => `${p.name} (${p.position}, OVR ${p.overall ?? "-"})`;

        function colorByIntDelta(delta) {
            if (delta > 0) return "#4caf50";
            if (delta < 0) return "#f44336";
            return "#b7bec9";
        }

        function moveToAdvanced(playerId, role = "MID") {
            if (state.advanced.some(a => a.playerId === playerId) || state.advanced.length >= 10) return;
            state.general = state.general.filter(id => id !== playerId);
            state.advanced.push({ playerId, role });
        }

        function moveToGeneral(playerId) {
            state.advanced = state.advanced.filter(a => a.playerId !== playerId);
            if (!state.general.includes(playerId)) state.general.push(playerId);
        }

        async function loadSummaries() {
            const res = await authFetch(`/training/weekly/team/${currentUserTeamId}/reports`);
            if (!res.ok) return [];
            return await res.json();
        }

        async function saveSetup() {
            const payload = {
                teamId: currentUserTeamId,
                groupSkills: state.groupSkills,
                advancedAssignments: state.advanced.map(a => ({ playerId: a.playerId, role: a.role }))
            };
            const res = await authFetch(`/training/setup/team/${currentUserTeamId}`, {
                method: "PUT",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify(payload)
            });
            return res.ok;
        }

        async function runTrainingWeek() {
            await saveSetup();
            const res = await authFetch(`/training/weekly/team/${currentUserTeamId}/run`, { method: "POST" });
            if (!res.ok) return null;
            return await res.json();
        }

        async function openWeekReport(season, week) {
            state.loadingReport = true;
            await render();
            const res = await authFetch(`/training/weekly/team/${currentUserTeamId}/reports/${season}/${week}`);
            state.loadingReport = false;
            if (!res.ok) return;
            state.selectedReport = await res.json();
            state.selectedPlayerGraph = null;
            await render();
        }

        async function openPlayerGraph(playerId) {
            const res = await authFetch(`/training/weekly/team/${currentUserTeamId}/player/${playerId}/graph`);
            if (!res.ok) return;
            state.selectedPlayerGraph = {
                playerId,
                player: getPlayer(playerId),
                points: await res.json()
            };
            await render();
        }

        function renderReportTable() {
            if (state.loadingReport) return `<p>Loading report...</p>`;
            if (!state.selectedReport) return `<p class="training-empty">Select a week report.</p>`;

            const report = state.selectedReport;
            let html = `<h3>Report: Season ${report.seasonNumber} • Week ${report.weekNumber}</h3>`;
            html += `<div class="training-report-table-wrap"><table class="training-report-table"><thead><tr><th>Player</th><th>Role</th><th>DT Skill</th><th>Advanced</th><th>Skills (after / weekly delta / int delta)</th></tr></thead><tbody>`;
            (report.players || []).forEach(p => {
                const skillsText = (p.skills || []).map(s => {
                    const intDelta = Number(s.integerChange || 0);
                    const decDelta = Number(s.decimalChange ?? (Number(s.after || 0) - Number(s.before || 0)));
                    const decDeltaText = `${decDelta >= 0 ? "+ " : "- "}${Math.abs(decDelta).toFixed(2)}`;
                    const intDeltaText = `${intDelta >= 0 ? "+ " : "- "}${Math.abs(intDelta)}`;
                    return `<span style="color:${colorByIntDelta(intDelta)}; font-weight:700;">${skillLabel(s.skill)} ${Number(s.after).toFixed(2)} (Delta ${decDeltaText} | int ${intDeltaText})</span>`;
                }).join(" | ");
                html += `<tr>
                    <td><span class="cs-clickable" data-training-player-graph="${p.playerId}">${escapeHtml(p.playerName)}</span></td>
                    <td>${escapeHtml(p.role)}</td>
                    <td>${escapeHtml(skillLabel(p.directTrainingSkill || "-"))}</td>
                    <td>${p.advancedTraining ? "Yes" : "No"}</td>
                    <td>${skillsText}</td>
                </tr>`;
            });
            html += `</tbody></table></div>`;
            return html;
        }

        function renderGraph() {
            if (!state.selectedPlayerGraph) return "";
            const graph = state.selectedPlayerGraph;
            const points = Array.isArray(graph.points) ? graph.points : [];
            if (points.length === 0) {
                return `<div class="training-block"><h3>${escapeHtml(graph.player?.name || "Player")} - Training Graph</h3><p class="training-empty">No graph data.</p></div>`;
            }

            const weekKeys = Array.from(new Set(points.map(p => `${p.seasonNumber}-${p.weekNumber}`)))
                .sort((a, b) => {
                    const [sa, wa] = a.split("-").map(Number);
                    const [sb, wb] = b.split("-").map(Number);
                    return sa === sb ? wa - wb : sa - sb;
                });

            const bySkill = {};
            points.forEach(p => {
                if (!bySkill[p.skill]) bySkill[p.skill] = {};
                bySkill[p.skill][`${p.seasonNumber}-${p.weekNumber}`] = p.value;
            });

            let html = `<div class="training-block" style="margin-top:14px;"><h3>${escapeHtml(graph.player?.name || "Player")} - Training Graph</h3>`;
            html += `<div class="training-report-table-wrap"><table class="training-report-table"><thead><tr><th>Skill</th>`;
            weekKeys.forEach(k => {
                const [s, w] = k.split("-");
                html += `<th>S${s}W${w}</th>`;
            });
            html += `</tr></thead><tbody>`;

            Object.keys(bySkill).forEach(skill => {
                let prevInt = null;
                html += `<tr><td>${escapeHtml(skillLabel(skill))}</td>`;
                weekKeys.forEach(k => {
                    const val = bySkill[skill][k];
                    if (typeof val !== "number") {
                        html += `<td>-</td>`;
                        return;
                    }
                    const currInt = Math.floor(val);
                    const delta = prevInt == null ? 0 : currInt - prevInt;
                    prevInt = currInt;
                    html += `<td style="color:${colorByIntDelta(delta)}; font-weight:700;">${Number(val).toFixed(2)}</td>`;
                });
                html += `</tr>`;
            });

            html += `</tbody></table></div></div>`;
            return html;
        }

        async function render() {
            let html = `
            <div class="fm-page fm-page--club">
                <section class="fm-panel fm-club-hero">
                    <button class="back-to-dashboard" data-nav-back="dashboard">Back</button>
                    <div class="fm-club-hero-main">
                        <div>
                            <div class="fm-eyebrow">Training ground</div>
                            <h2>Training Setup</h2>
                            <p class="fm-subtle">Advanced and formation training stay intact, but the page now opens directly with the same action-row language as Club.</p>
                        </div>
                        ${buildTrainingActionsHtml('trainingSetup')}
                    </div>
                    <div class="fm-medical-stat-grid team-summary-grid">
                        <div><strong>${players.length}</strong><span>Players</span></div>
                        <div><strong>${state.advanced.length}</strong><span>Advanced slots</span></div>
                        <div><strong>${state.general.length}</strong><span>Formation pool</span></div>
                        <div><strong>${GROUPS.length}</strong><span>Training groups</span></div>
                    </div>
                </section>
                <section class="fm-panel">
                    <div class="fm-panel-head">
                        <div>
                            <h3>Weekly plan</h3>
                            <p class="fm-subtle">Advanced + Formation training. Wingers are under MID group. Stamina is automatic.</p>
                        </div>
                        <span class="fm-panel-action">Setup</span>
                    </div>
                    <div class="training-grid training-grid-setup">
                    <div class="training-block training-block-groups">
                        <h3>Formation Training Groups</h3>
                        <div class="training-groups">`;

            GROUPS.forEach(group => {
                html += `
                    <label class="training-group-row">
                        <span class="group-tag">${group}</span>
                        <select data-group="${group}" class="group-skill-select">
                            ${(SKILLS_BY_GROUP[group] || []).map(opt => `<option value="${opt}" ${state.groupSkills[group] === opt ? "selected" : ""}>${skillLabel(opt)}</option>`).join("")}
                        </select>
                    </label>`;
            });

            html += `
                        </div>
                    </div>

                    <div class="training-side-grid">
                        <div class="training-block">
                            <h3>Advanced Training (max 10 players)</h3>
                            <div id="advanced-drop" class="training-dropzone">
                                ${state.advanced.length === 0 ? `<div class="training-empty">Drop players here</div>` : ""}
                                ${state.advanced.map((entry, idx) => {
                                    const p = getPlayer(entry.playerId);
                                    if (!p) return "";
                                    return `
                                    <div class="training-player-card" draggable="true" data-player-id="${p.id}" data-origin="advanced">
                                        <div class="training-player-main">
                                            <strong>${escapeHtml(p.name)}</strong>
                                            <small>${escapeHtml(playerBadge(p))}</small>
                                        </div>
                                        <select class="adv-role-select" data-player-id="${p.id}">
                                            ${ROLE_OPTIONS.map(role => `<option value="${role}" ${entry.role === role ? "selected" : ""}>${role}</option>`).join("")}
                                        </select>
                                        <button class="mini-btn" data-remove-adv="${idx}">Remove</button>
                                    </div>`;
                                }).join("")}
                            </div>
                            <div class="quick-add-wrap" style="margin-top:10px;">
                                <label style="font-size:0.88rem; color:#9aa7bc;">Mobile fallback: add player to advanced</label>
                                <select id="quick-player-select">
                                    <option value="">Select player...</option>
                                    ${state.general.map(id => {
                                        const p = getPlayer(id);
                                        if (!p) return "";
                                        return `<option value="${p.id}">${escapeHtml(playerBadge(p))}</option>`;
                                    }).join("")}
                                </select>
                                <select id="quick-role-select">
                                    ${ROLE_OPTIONS.map(role => `<option value="${role}">${role}</option>`).join("")}
                                </select>
                                <button id="quick-add-advanced" class="big-button" style="padding:8px 12px;">Add to Advanced</button>
                            </div>
                        </div>

                        <div class="training-block training-block-pool">
                            <h3>Player Pool</h3>
                            <div class="training-pools">
                                <div>
                                    <h4>Formation Training Pool</h4>
                                    <div id="general-drop" class="training-dropzone">
                                        ${state.general.length === 0 ? `<div class="training-empty">No players in formation pool</div>` : ""}
                                        ${state.general.map(id => {
                                            const p = getPlayer(id);
                                            if (!p) return "";
                                            return `
                                            <div class="training-player-card" draggable="true" data-player-id="${p.id}" data-origin="general">
                                                <div class="training-player-main">
                                                    <strong>${escapeHtml(p.name)}</strong>
                                                    <small>${escapeHtml(playerBadge(p))}</small>
                                                </div>
                                            </div>`;
                                        }).join("")}
                                    </div>
                                </div>
                            </div>
                        </div>
                    </div>
                </div>

                <div class="training-actions">
                    <button id="save-training-setup" class="big-button">Save Setup</button>
                    <button id="run-training-week" class="big-button" style="margin-left:10px; background:#145d39;">Run Weekly Training</button>
                </div>

                    <div class="training-note" style="margin-top:12px;">
                        Weekly report history is available in <strong>Training Reports</strong>.
                    </div>
                </section>
            </div>`;

            mainContent.innerHTML = html;
            bindUi();
        }

        function bindUi() {
            mainContent.querySelectorAll(".group-skill-select").forEach(sel => {
                sel.addEventListener("change", () => {
                    state.groupSkills[sel.getAttribute("data-group")] = sel.value;
                });
            });
            mainContent.querySelectorAll(".adv-role-select").forEach(sel => {
                sel.addEventListener("change", () => {
                    const playerId = Number(sel.getAttribute("data-player-id"));
                    const row = state.advanced.find(a => a.playerId === playerId);
                    if (row) row.role = sel.value;
                });
            });
            mainContent.querySelectorAll("[data-remove-adv]").forEach(btn => {
                btn.addEventListener("click", () => {
                    const idx = Number(btn.getAttribute("data-remove-adv"));
                    const entry = state.advanced[idx];
                    if (entry) moveToGeneral(entry.playerId);
                    render();
                });
            });

            let dragPlayerId = null;
            let dragOrigin = null;
            mainContent.querySelectorAll('.training-player-card[draggable="true"]').forEach(card => {
                card.addEventListener("dragstart", () => {
                    dragPlayerId = Number(card.getAttribute("data-player-id"));
                    dragOrigin = card.getAttribute("data-origin");
                });
            });

            const advancedDrop = document.getElementById("advanced-drop");
            const generalDrop = document.getElementById("general-drop");
            [advancedDrop, generalDrop].forEach(zone => zone && zone.addEventListener("dragover", e => e.preventDefault()));

            if (advancedDrop) {
                advancedDrop.addEventListener("drop", e => {
                    e.preventDefault();
                    if (dragPlayerId && dragOrigin === "general") moveToAdvanced(dragPlayerId, "MID");
                    render();
                });
            }
            if (generalDrop) {
                generalDrop.addEventListener("drop", e => {
                    e.preventDefault();
                    if (dragPlayerId && dragOrigin === "advanced") moveToGeneral(dragPlayerId);
                    render();
                });
            }

            const saveBtn = document.getElementById("save-training-setup");
            if (saveBtn) {
                saveBtn.addEventListener("click", async () => {
                    saveBtn.disabled = true;
                    const ok = await saveSetup();
                    saveBtn.disabled = false;
                    saveBtn.textContent = ok ? "Saved" : "Save failed";
                    setTimeout(() => { saveBtn.textContent = "Save Setup"; }, 1100);
                });
            }

            const runBtn = document.getElementById("run-training-week");
            if (runBtn) {
                runBtn.addEventListener("click", async () => {
                    runBtn.disabled = true;
                    runBtn.textContent = "Running...";
                    const report = await runTrainingWeek();
                    runBtn.disabled = false;
                    runBtn.textContent = "Run Weekly Training";
                    if (report && Number.isFinite(report.seasonNumber) && Number.isFinite(report.weekNumber)) {
                        sessionStorage.setItem("training_report_focus", `${report.seasonNumber}|${report.weekNumber}`);
                        await loadTrainingReportsPage();
                        return;
                    }
                    await render();
                });
            }

            const quickAddBtn = document.getElementById("quick-add-advanced");
            if (quickAddBtn) {
                quickAddBtn.addEventListener("click", () => {
                    const playerId = Number(document.getElementById("quick-player-select")?.value || 0);
                    const role = (document.getElementById("quick-role-select")?.value || "MID").toUpperCase();
                    if (!playerId) return;
                    moveToAdvanced(playerId, ROLE_OPTIONS.includes(role) ? role : "MID");
                    render();
                });
            }

            mainContent.querySelectorAll("[data-open-week]").forEach(item => {
                item.addEventListener("click", async () => {
                    const [season, week] = (item.getAttribute("data-open-week") || "").split("|").map(Number);
                    if (Number.isFinite(season) && Number.isFinite(week)) {
                        await openWeekReport(season, week);
                    }
                });
            });

            mainContent.querySelectorAll("[data-training-player-graph]").forEach(item => {
                item.addEventListener("click", async () => {
                    const playerId = Number(item.getAttribute("data-training-player-graph"));
                    if (playerId) {
                        await openPlayerGraph(playerId);
                    }
                });
            });
        }

        await render();
    }
    async function loadTrainingReportsPage() {
        const mainContent = document.getElementById("main-content");
        const playersRes = await authFetch(`/teams/${currentUserTeamId}/players`);
        if (!playersRes.ok) {
            mainContent.innerHTML = `
                <div class="fm-page fm-page--club">
                    <section class="fm-panel fm-club-hero">
                        <button class="back-to-dashboard" data-nav-back="dashboard">Back</button>
                        <div class="fm-club-hero-main">
                            <div>
                                <div class="fm-eyebrow">Training reports</div>
                                <h2>Training Reports</h2>
                                <p class="fm-subtle">Could not load player data for the reports page.</p>
                            </div>
                            ${buildTrainingActionsHtml('trainingReports')}
                        </div>
                        <div class="fm-medical-stat-grid team-summary-grid">
                            <div><strong>0</strong><span>Weeks</span></div>
                            <div><strong>0</strong><span>Players</span></div>
                            <div><strong>0</strong><span>Reports</span></div>
                            <div><strong>0</strong><span>Graphs</span></div>
                        </div>
                    </section>
                </div>`;
            return;
        }
        const players = await playersRes.json();
        const playerById = new Map(players.map(p => [p.id, p]));

        let selectedReport = null;
        let selectedPlayerGraph = null;

        const colorByIntDelta = (delta) => {
            if (delta > 0) return "#4caf50";
            if (delta < 0) return "#f44336";
            return "#b7bec9";
        };
        const skillLabel = (skill) => skill.charAt(0).toUpperCase() + skill.slice(1);
        const skillShortLabel = (skill) => {
            switch ((skill || "").toLowerCase()) {
                case "goalkeeper": return "GK";
                case "defending": return "DEF";
                case "pace": return "PAC";
                case "technique": return "TEC";
                case "playmaker": return "PLY";
                case "passing": return "PAS";
                case "shooting": return "SHT";
                case "stamina": return "STA";
                default: return skillLabel(skill).slice(0, 3).toUpperCase();
            }
        };
        const skillIcon = (skill) => {
            switch ((skill || "").toLowerCase()) {
                case "goalkeeper": return "🧤";
                case "defending": return "🛡️";
                case "pace": return "💨";
                case "technique": return "⚒️";
                case "playmaker": return "🧠";
                case "passing": return "🎁";
                case "shooting": return "🎯";
                case "stamina": return "🔋";
                default: return "•";
            }
        };
        const normalizeWeekKey = (season, week) => `${Number(season)}|${Number(week)}`;
        const weekShort = (season, week) => `S${Number(season)}W${Number(week)}`;
        const weekLabel = (season, week) => `Season ${Number(season)} Week ${Number(week)}`;
        const skillTone = (delta, isDirectTraining) => {
            if (delta > 0) return "#4caf50";
            if (delta < 0) return "#f44336";
            if (isDirectTraining) return "#9d4edd";
            return "#dce6f5";
        };
        const trackedSkills = ["goalkeeper", "defending", "pace", "technique", "playmaker", "passing", "shooting", "stamina"];
        const skillHeaderCells = () => trackedSkills.map(skill => `
            <th class="training-skill-col" title="${escapeHtml(skillLabel(skill))}">
                <div class="training-skill-head">
                    <span class="training-skill-head-icon">${skillIcon(skill)}</span>
                    <span class="training-skill-head-text">${escapeHtml(skillShortLabel(skill))}</span>
                </div>
            </th>`).join('');
        const buildSkillMetricCell = ({ valueText = '-', deltaText = '—', tone = '#dce6f5', isDirect = false, isEmpty = false, title = '' } = {}) => `
            <td class="training-skill-col${isDirect ? ' is-direct-focus' : ''}${isEmpty ? ' is-empty' : ''}"${title ? ` title="${escapeHtml(title)}"` : ''}>
                <div class="training-skill-metric" style="--skill-tone:${tone};">
                    <span class="training-skill-value">${escapeHtml(String(valueText))}</span>
                    <span class="training-skill-delta">${escapeHtml(String(deltaText))}</span>
                </div>
            </td>`;

        async function fetchSummaries() {
            const res = await authFetch(`/training/weekly/team/${currentUserTeamId}/reports`);
            if (!res.ok) return [];
            const all = await res.json();
            const unique = [];
            const seen = new Set();
            all.forEach(s => {
                const key = normalizeWeekKey(s.seasonNumber, s.weekNumber);
                if (seen.has(key)) return;
                seen.add(key);
                unique.push(s);
            });
            return unique;
        }

        async function fetchReport(season, week) {
            const res = await authFetch(`/training/weekly/team/${currentUserTeamId}/reports/${season}/${week}`);
            if (!res.ok) return null;
            return await res.json();
        }

        async function openPlayerGraph(playerId) {
            const res = await authFetch(`/training/weekly/team/${currentUserTeamId}/player/${playerId}/graph`);
            if (!res.ok) return;
            selectedPlayerGraph = {
                playerId,
                player: playerById.get(playerId),
                points: await res.json()
            };
            await render();
        }

        function renderReportCards(report) {
            if (!report) return `<p class="training-empty">Select a week report.</p>`;
            const posOrder = { GK: 0, DEF: 1, MID: 2, WNG: 3, ATT: 4 };
            const reportPlayers = (report.players || [])
                .sort((a, b) => {
                    const aPlayer = playerById.get(a.playerId);
                    const bPlayer = playerById.get(b.playerId);
                    const posDiff = (posOrder[aPlayer?.position] ?? 5) - (posOrder[bPlayer?.position] ?? 5);
                    if (posDiff !== 0) return posDiff;
                    return String(a.playerName || "").localeCompare(String(b.playerName || ""));
                })
                .map(p => {
                const player = playerById.get(p.playerId);
                const playerName = player?.name || p.playerName || `#${p.playerId}`;
                const skillByName = new Map((p.skills || []).map(s => [String(s.skill || "").toLowerCase(), s]));
                const pos = player?.position || "-";
                const age = Number.isFinite(Number(player?.age)) ? Number(player.age) : "-";
                const rating = Number.isFinite(Number(player?.rating)) ? Number(player.rating) : "-";
                const form = Number.isFinite(Number(player?.form)) ? Number(player.form).toFixed(1) : "-";
                const goals = Number(player?.goals || 0);
                const assists = Number(player?.assists || 0);
                const skillCells = trackedSkills.map(skill => {
                    const s = skillByName.get(skill);
                    if (!s) {
                        return buildSkillMetricCell({
                            isEmpty: true,
                            title: `${skillLabel(skill)}: no report data for this week`
                        });
                    }
                    const intDelta = Number(s.integerChange || 0);
                    const decDelta = Number(s.decimalChange ?? (Number(s.after || 0) - Number(s.before || 0)));
                    const decSign = decDelta > 0 ? "+" : decDelta < 0 ? "-" : "";
                    const title = `${skillLabel(skill)}: ${Number(s.after || 0).toFixed(2)} | Delta ${decSign}${Math.abs(decDelta).toFixed(2)} | Int ${intDelta >= 0 ? "+" : "-"}${Math.abs(intDelta)}`;
                    const tone = skillTone(intDelta, skill === String(p.directTrainingSkill || "").toLowerCase());
                    const isDirect = skill === String(p.directTrainingSkill || "").toLowerCase();
                    return buildSkillMetricCell({
                        valueText: Number(s.after || 0).toFixed(2),
                        deltaText: decDelta === 0 ? '±0.00' : `${decSign}${Math.abs(decDelta).toFixed(2)}`,
                        tone,
                        isDirect,
                        title
                    });
                }).join("");
                return `
                    <tr class="fm-squad-row training-report-player-row" data-open-training-player="${p.playerId}">
                        <td class="sq-name">
                            <span class="sq-player-link">${escapeHtml(playerName)}</span>
                            <span class="ps-team">Form ${form} • G ${goals} • A ${assists}</span>
                        </td>
                        <td class="sq-pos">${escapeHtml(pos)}</td>
                        <td class="sq-age">${age}</td>
                        <td class="sq-rating">${rating}</td>
                        <td class="sq-role">${escapeHtml(p.role || '-')}</td>
                        <td class="sq-focus">${escapeHtml(skillShortLabel(p.directTrainingSkill || '-'))}</td>
                        <td class="sq-mode">${p.advancedTraining ? 'ADV' : 'FORM'}</td>
                        ${skillCells}
                    </tr>`;
            }).join('');

            const playerCount = report.players?.length || 0;
            const advancedCount = (report.players || []).filter(player => player.advancedTraining).length;
            return `
                <div class="fm-page training-report-shell">
                    <section class="fm-panel fm-club-hero training-report-hero">
                        <button class="back-to-dashboard" data-training-week-back="1">Back</button>
                        <div class="fm-club-hero-main">
                            <div>
                                <div class="fm-eyebrow">Training report</div>
                                <h2>${weekLabel(report.seasonNumber, report.weekNumber)}</h2>
                                <p class="fm-subtle">Week list is hidden while this squad-style report is open. Click any player row for the detailed progress view.</p>
                            </div>
                            <div>
                                ${buildTrainingActionsHtml('trainingReports')}
                            </div>
                        </div>
                        <div class="training-report-actions">
                                <span class="fm-player-chip secondary">${playerCount} players</span>
                                <span class="fm-player-chip secondary">${advancedCount} ADV</span>
                                <span class="fm-player-chip secondary">Focus skill accented</span>
                        </div>
                    </section>
                    <section class="fm-panel">
                        <div class="fm-panel-head">
                            <h3>Squad report</h3>
                            <span class="fm-panel-action">First-team inspired layout</span>
                        </div>
                        <div class="fm-squad-wrap">
                            <table class="fm-squad training-report-squad">
                                <thead>
                                    <tr>
                                        <th class="sq-name">Name</th>
                                        <th>Pos</th>
                                        <th class="sq-age">Age</th>
                                        <th class="sq-rating">Rating</th>
                                        <th>Role</th>
                                        <th>Focus</th>
                                        <th>Mode</th>
                                        ${skillHeaderCells()}
                                    </tr>
                                </thead>
                                <tbody>${reportPlayers}</tbody>
                            </table>
                        </div>
                    </section>
                </div>`;
        }

        function renderGraph() {
            if (!selectedPlayerGraph) return "";
            const player = selectedPlayerGraph.player;
            const points = Array.isArray(selectedPlayerGraph.points) ? selectedPlayerGraph.points : [];
            const currentPlayer = playerById.get(selectedPlayerGraph.playerId) || player || {};
            const headerHtml = buildPlayerProfileHeroHtml(currentPlayer, {
                backLabel: 'Back',
                eyebrow: 'Training progress',
                teamName: 'Training reports',
                ratingSummary: {
                    averageRating10: currentPlayer?.rating,
                    matchesPlayed: currentPlayer?.played ?? currentPlayer?.matchesPlayed ?? 0
                },
                backButtonId: 'training-player-back-button',
                backButtonAttributes: 'data-training-report-back="1"',
                bannerClassName: 'training-player-banner'
            });
            if (points.length === 0) {
                return `${headerHtml}
                    <section class="fm-panel">
                        ${buildTrainingActionsHtml('trainingReports')}
                    </section>
                    <section class="fm-panel">
                        <div class="fm-empty">No graph data.</div>
                    </section>`;
            }

            const weekMap = new Map();
            points.forEach(point => {
                const key = normalizeWeekKey(point.seasonNumber, point.weekNumber);
                if (!weekMap.has(key)) {
                    weekMap.set(key, {
                        seasonNumber: point.seasonNumber,
                        weekNumber: point.weekNumber,
                        role: point.role || "-",
                        directTrainingSkill: point.directTrainingSkill || "-",
                        advancedTraining: !!point.advancedTraining,
                        skills: {}
                    });
                }
                weekMap.get(key).skills[String(point.skill || "").toLowerCase()] = point;
            });

            const weeksAsc = [...weekMap.values()].sort((a, b) =>
                a.seasonNumber === b.seasonNumber ? a.weekNumber - b.weekNumber : a.seasonNumber - b.seasonNumber
            );
            const prevInts = {};
            weeksAsc.forEach(week => {
                week.skillMeta = {};
                trackedSkills.forEach(skill => {
                    const point = week.skills[skill];
                    if (!point) return;
                    const prevInt = prevInts[skill];
                    const delta = prevInt == null ? 0 : Number(point.integerValue) - Number(prevInt);
                    prevInts[skill] = Number(point.integerValue);
                    week.skillMeta[skill] = { point, delta };
                });
            });
            const weeks = [...weeksAsc].reverse();
            let html = `<div class="training-player-shell">
                ${headerHtml}
                <section class="fm-panel">
                    ${buildTrainingActionsHtml('trainingReports')}
                </section>
                <section class="fm-panel training-player-detail">
                    <div class="fm-panel-head">
                        <h3>Weekly progression</h3>
                        <span class="fm-panel-action">${weeks.length} tracked weeks</span>
                    </div>
                    <p class="training-note">Skill columns show exact values, with the week delta beneath. Focus skill stays accented; green means growth and red means decline.</p>
                    <div class="fm-squad-wrap">
                        <table class="fm-squad training-graph-squad">
                            <thead>
                                <tr>
                                    <th>Week</th>
                                    <th>Role</th>
                                    <th>Focus</th>
                                    <th>Mode</th>
                                    ${skillHeaderCells()}
                                </tr>
                            </thead>
                            <tbody>`;

            weeks.forEach(week => {
                const skillHtml = trackedSkills.map(skill => {
                    const meta = week.skillMeta?.[skill];
                    if (!meta?.point) {
                        return buildSkillMetricCell({
                            isEmpty: true,
                            title: `${skillLabel(skill)}: no tracked value`
                        });
                    }
                    const isDirect = skill === String(week.directTrainingSkill || "").toLowerCase();
                    const tone = skillTone(meta.delta, isDirect);
                    const deltaText = meta.delta === 0 ? '±0' : `${meta.delta > 0 ? '+' : '-'}${Math.abs(meta.delta)}`;
                    return buildSkillMetricCell({
                        valueText: Number(meta.point.value).toFixed(2),
                        deltaText,
                        tone,
                        isDirect,
                        title: `${skillLabel(skill)}: ${Number(meta.point.value).toFixed(2)} | Weekly int delta ${deltaText}`
                    });
                }).join("");

                html += `<tr>
                    <td>${weekShort(week.seasonNumber, week.weekNumber)}</td>
                    <td>${escapeHtml(week.role || '-')}</td>
                    <td>${escapeHtml(skillShortLabel(week.directTrainingSkill || '-'))}</td>
                    <td>${week.advancedTraining ? 'ADV' : 'FORM'}</td>
                    ${skillHtml}
                </tr>`;
            });

            html += `</tbody></table></div></section></div>`;
            return html;
        }

        async function render() {
            const summaries = await fetchSummaries();
            const focusRaw = sessionStorage.getItem("training_report_focus");
            let focusSeason = null;
            let focusWeek = null;
            if (focusRaw && focusRaw.includes("|")) {
                const [s, w] = focusRaw.split("|").map(Number);
                if (Number.isFinite(s) && Number.isFinite(w)) {
                    focusSeason = s;
                    focusWeek = w;
                }
            }
            if (!selectedReport && focusSeason != null && focusWeek != null) {
                selectedReport = await fetchReport(focusSeason, focusWeek);
                sessionStorage.removeItem("training_report_focus");
            }

            if (selectedPlayerGraph) {
                mainContent.innerHTML = renderGraph();
            } else if (selectedReport) {
                mainContent.innerHTML = renderReportCards(selectedReport);
            } else {
                mainContent.innerHTML = `
                    <div class="fm-page fm-page--club">
                        <section class="fm-panel fm-club-hero">
                            <button class="back-to-dashboard" data-nav-back="dashboard">Back</button>
                            <div class="fm-club-hero-main">
                                <div>
                                    <div class="fm-eyebrow">Training reports</div>
                                    <h2>Training Reports</h2>
                                    <p class="fm-subtle">Open any week to keep the current report/player drill-down flow, now wrapped in the same page shell as the rest of Training.</p>
                                </div>
                                ${buildTrainingActionsHtml('trainingReports')}
                            </div>
                            <div class="fm-medical-stat-grid team-summary-grid">
                                <div><strong>${summaries.length}</strong><span>Weeks logged</span></div>
                                <div><strong>${players.length}</strong><span>Players</span></div>
                                <div><strong>${summaries[0] ? weekLabel(summaries[0].seasonNumber, summaries[0].weekNumber) : '—'}</strong><span>Latest report</span></div>
                                <div><strong>Live</strong><span>Click-through</span></div>
                            </div>
                        </section>
                        <section class="fm-panel">
                            <div class="fm-panel-head">
                                <div>
                                    <h3>Week archive</h3>
                                    <p class="fm-subtle">Purple marks the skill trained that week. Click a week to open the detailed report.</p>
                                </div>
                                <span class="fm-panel-action">Archive</span>
                            </div>
                            <div class="training-grid">
                                <div class="training-block">
                                    <h3>Weeks</h3>
                                    <div class="training-week-list">
                                        ${summaries.length === 0 ? `<div class="training-empty">No reports yet.</div>` : summaries.map(s => `<button type="button" class="training-week-item" data-open-week="${s.seasonNumber}|${s.weekNumber}"><strong>${weekLabel(s.seasonNumber, s.weekNumber)}</strong><span>Open detailed report</span></button>`).join("")}
                                    </div>
                                </div>
                                <div class="training-block">
                                    <div class="training-empty">Select a week to open the report.</div>
                                </div>
                            </div>
                        </section>
                    </div>
                `;
            }

            mainContent.onclick = async (event) => {
                const backEl = event.target.closest("[data-training-report-back]");
                if (backEl && mainContent.contains(backEl)) {
                    selectedPlayerGraph = null;
                    await render();
                    return;
                }

                const weekBackEl = event.target.closest("[data-training-week-back]");
                if (weekBackEl && mainContent.contains(weekBackEl)) {
                    selectedReport = null;
                    selectedPlayerGraph = null;
                    await render();
                    return;
                }

                const weekEl = event.target.closest("[data-open-week]");
                if (weekEl && mainContent.contains(weekEl)) {
                    const [season, week] = (weekEl.getAttribute("data-open-week") || "").split("|").map(Number);
                    const report = await fetchReport(season, week);
                    if (report) {
                        selectedReport = report;
                        selectedPlayerGraph = null;
                        await render();
                    }
                    return;
                }

                const playerEl = event.target.closest("[data-open-training-player]");
                if (playerEl && mainContent.contains(playerEl)) {
                    event.preventDefault();
                    event.stopPropagation();
                    const playerId = Number(playerEl.getAttribute("data-open-training-player"));
                    if (playerId) {
                        await openPlayerGraph(playerId);
                    }
                }
            };
        }

        await render();
    }
    async function loadClubProfile() {
        console.log(`Loading club profile for ${currentUserTeamId}`);
        const [response, milestones] = await Promise.all([
            authFetch(`/demo/teams/${currentUserTeamId}/profile`),
            (async () => {
                try {
                    const milestoneResponse = await authFetch(`/teams/${currentUserTeamId}/milestones`);
                    return milestoneResponse.ok ? await milestoneResponse.json() : null;
                } catch {
                    return null;
                }
            })()
        ]);
        console.log(`Response status: ${response.status}`);
        const profile = await response.json();

        const mainContent = document.getElementById("main-content");

        // Dodajemo sliku stadiona (pretpostavljamo da je u /images/stadion.jpg ili sliÄno)
        // MoÅ¾eÅ¡ promeniti putanju ili dodati logiku po imenu stadiona ako imaÅ¡ viÅ¡e
        const stadiumImage = "/images/dunjareal.png"; // default, ili po profil.stadium

        mainContent.innerHTML = `
        <div class="fm-page fm-page--club">
            <section class="fm-panel fm-club-hero">
                <button class="back-to-dashboard" data-nav-back="dashboard">Back</button>
                <div class="fm-club-hero-main">
                    <div>
                        <div class="fm-eyebrow">Club overview</div>
                        <h2>${escapeHtml(profile.name || 'Club Profile')}</h2>
                        <p class="fm-subtle">Same club shell as First Team, with profile data, stadium access, budget, and reputation.</p>
                    </div>
                    ${buildClubActionsHtml('profile')}
                </div>
                <div class="fm-medical-stat-grid team-summary-grid">
                    <div><strong>${escapeHtml(profile.founded || 'N/A')}</strong><span>Founded</span></div>
                    <div><strong>${escapeHtml(profile.stadium || 'N/A')}</strong><span>Home stadium</span></div>
                    <div><strong>${escapeHtml(profile.reputation || 'N/A')}</strong><span>Reputation</span></div>
                    <div><strong>${escapeHtml(formatBudget(profile.budget))}</strong><span>Budget</span></div>
                </div>
            </section>
            <div class="fm-grid-top fm-grid-top--club-profile">
                <section class="fm-panel club-profile-brand-card">
                    <div class="club-profile-brand-mark">
                        <img src="${profile.logo || '/images/logoside.jpg'}"
                             class="club-logo"
                             alt="${escapeHtml(profile.name)}"
                             onerror="this.src='/images/logoside.jpg'">
                    </div>
                    <h3>${escapeHtml(profile.name || 'Club')}</h3>
                    <p class="fm-subtle">Serbian club profile with open-football-inspired presentation and our existing app data.</p>
                    <button type="button" class="fm-action-btn secondary club-profile-stadium-btn" data-stadium-image="${escapeHtml(stadiumImage)}" data-stadium-name="${escapeHtml(profile.stadium || 'Stadium')}">Open Stadium View</button>
                </section>
                <section class="fm-panel club-profile-detail-card">
                    <div class="fm-panel-head">
                        <div>
                            <h3>Club details</h3>
                            <p class="fm-subtle">Profile data stays concise, wide, and visually aligned with the rest of the club area.</p>
                        </div>
                        <span class="fm-panel-action">Profile</span>
                    </div>
                    <div class="club-profile-detail-list">
                        <div class="club-profile-detail-row"><span>Founded</span><strong>${escapeHtml(profile.founded || 'N/A')}</strong></div>
                        <div class="club-profile-detail-row"><span>Stadium</span><strong>${escapeHtml(profile.stadium || 'N/A')}</strong></div>
                        <div class="club-profile-detail-row"><span>Budget</span><strong>${escapeHtml(formatBudget(profile.budget))}</strong></div>
                        <div class="club-profile-detail-row"><span>Reputation</span><strong>${escapeHtml(profile.reputation || 'N/A')}</strong></div>
                    </div>
                </section>
            </div>
            <section class="fm-panel fm-milestone-board-panel">
                <div class="fm-panel-head">
                    <div>
                        <h3>Club milestones</h3>
                        <p class="fm-subtle">Current season snapshot for your club, kept alongside the league-wide milestone boards.</p>
                    </div>
                    <span class="fm-panel-action">Team board</span>
                </div>
                ${buildMilestoneBoardHtml(milestones)}
            </section>
        </div>`;

        const stadiumButton = mainContent.querySelector('.club-profile-stadium-btn');
        if (stadiumButton) {
            stadiumButton.addEventListener('click', () => {
                showStadiumModal(stadiumButton.dataset.stadiumImage, stadiumButton.dataset.stadiumName);
            });
        }
    }
    async function loadUpcomingMatches() {
        if (!await ensureUserTeamId()) return;
        console.log(`Loading upcoming matches for ${currentUserTeamId}`);
        const response = await authFetch(`/demo/matches/teams/${currentUserTeamId}/upcoming`);
        console.log(`Response status: ${response.status}`);
        const matches = await response.json();
        renderMatches(matches, "Upcoming Matches");
    }
    async function loadFixtures() {
        if (!await ensureUserTeamId()) return;
        return matchesFeature.loadFixtures();
    }
    function resolveFixtureStadiumImage(fixture) {
        const venueKey = String(fixture?.stadium || fixture?.stadiumName || '').toLowerCase();
        if (venueKey.includes('livadice')) return '/images/livadice.png';
        if (venueKey.includes('dunjareal')) return '/images/dunjareal.png';
        if (venueKey.includes('bilino')) return '/images/bilinopolje.png';
        return '/images/default-stadium.png';
    }
    async function findFixtureRow(fixtureId) {
        const numericFixtureId = Number(fixtureId);
        if (!Number.isFinite(numericFixtureId)) return null;

        const scheduleLoaders = [
            async () => {
                const response = await authFetch(`/teams/${currentUserTeamId}/schedule`);
                return response.ok ? response.json() : [];
            },
            async () => {
	            const leagueId = await ensureCurrentLeagueId();
	            if (!leagueId) return [];
                const seasonParam = currentLeagueSeasonYear ? `?seasonYear=${currentLeagueSeasonYear}` : '';
	            const response = await authFetch(`/countries/leagues/${leagueId}/schedule${seasonParam}`);
                return response.ok ? response.json() : [];
            }
        ];

        for (const loadSchedule of scheduleLoaders) {
            try {
                const rows = await loadSchedule();
                const fixture = (Array.isArray(rows) ? rows : [])
                    .find(row => Number(row?.fixtureId ?? row?.id) === numericFixtureId);
                if (fixture) return fixture;
            } catch (err) {
                console.warn(`Fixture lookup failed for ${numericFixtureId}:`, err);
            }
        }

        return null;
    }
    async function loadFixture(fixtureId, options = {}) {
        const pushHistory = options.pushHistory !== false;
	        const backTarget = options.backTarget || 'fixtures';
	        if (pushHistory) pushNavState({ type: 'fixture', fixtureId, backTarget, ...getActiveLeagueNavState() });
        const mainContent = document.getElementById("main-content");
        if (!await ensureUserTeamId()) return;
        console.log(`Loading fixture ID: ${fixtureId}`);

        try {
            const fixture = await findFixtureRow(fixtureId);
            if (!fixture) {
                mainContent.innerHTML = `
                    <div class="fm-page fm-page--club">
                        <section class="fm-panel fm-club-hero">
	                            <button class="back-to-dashboard" data-nav-back="${backTarget}">Back</button>
                            <div class="fm-club-hero-main">
                                <div>
                                    <div class="fm-eyebrow">Club schedule</div>
                                    <h2>Fixture unavailable</h2>
                                    <p class="fm-subtle">We could not load the requested fixture.</p>
                                </div>
                                ${buildClubActionsHtml('schedule')}
                            </div>
                        </section>
                        <section class="fm-panel"><div class="fm-empty">Fixture not found.</div></section>
                    </div>`;
                return;
            }

            const parsedMatchDate = fixture?.matchDate
                ? new Date(String(fixture.matchDate).includes('T') ? String(fixture.matchDate) : String(fixture.matchDate).replace(' ', 'T'))
                : null;
            const hasParsedDate = parsedMatchDate instanceof Date && !Number.isNaN(parsedMatchDate.getTime());
            const homeTeamName = fixture.homeTeam || "Home";
            const awayTeamName = fixture.awayTeam || "Away";
            const matchDate = hasParsedDate ? parsedMatchDate.toLocaleDateString('sr-RS') : (fixture.matchDate || 'N/A');
            const matchTime = hasParsedDate
                ? parsedMatchDate.toLocaleTimeString('sr-RS', { hour: '2-digit', minute: '2-digit' })
                : 'TBD';
            const matchDateTime = hasParsedDate ? `${matchDate} • ${matchTime}` : escapeHtml(formatDateTimeLabel(fixture.matchDate));
            const venue = fixture.stadium || fixture.stadiumName || "N/A";
            const homeStrength = Number(fixture.homeTeamStrength);
            const awayStrength = Number(fixture.awayTeamStrength);
            const homeForm = Number(fixture.homeTeamForm);
            const awayForm = Number(fixture.awayTeamForm);
            const prediction = fixture.prediction || {};
            const confidence = Number(prediction.confidence);
            const expectedHomeGoals = Number(prediction.expectedHomeGoals);
            const expectedAwayGoals = Number(prediction.expectedAwayGoals);
            const h2h = fixture.h2h || {};
            const statusLabel = fixture.played ? 'Played' : 'Scheduled';
            const previewLabel = prediction.mostLikelyResult
                ? `${prediction.mostLikelyResult.replace(/_/g, ' ')}${Number.isFinite(confidence) ? ` · ${Math.round(confidence)}% conf` : ''}`
                : 'Preview pending';
            const mostLikelyLabel = prediction.mostLikelyResult
                ? prediction.mostLikelyResult.replace(/_/g, ' ')
                : 'Pending';
            const previewAnalysis = escapeHtml(String(prediction.analysis || 'No extra preview analysis available yet.'));

            mainContent.innerHTML = `
            <div class="fm-page fm-page--club">
                <section class="fm-panel fm-club-hero">
	                    <button class="back-to-dashboard" data-nav-back="${backTarget}">Back</button>
                    <div class="fm-club-hero-main">
                        <div>
                            <div class="fm-eyebrow">Fixture preview</div>
                            <h2>${escapeHtml(homeTeamName)} vs ${escapeHtml(awayTeamName)}</h2>
                            <p class="fm-subtle">Fixture detail now resolves from the real schedule payload, so club and league schedule clicks stay in-app.</p>
                        </div>
                        ${buildClubActionsHtml('schedule')}
                    </div>
                    <div class="fm-medical-stat-grid team-summary-grid">
                        <div><strong>${escapeHtml(matchDate)}</strong><span>Date</span></div>
                        <div><strong>${escapeHtml(matchTime)}</strong><span>Kick-off</span></div>
                        <div><strong>${escapeHtml(fixture.competitionName || 'Competition')}</strong><span>Competition</span></div>
                        <div><strong>${fixture.round ? `Round ${escapeHtml(String(fixture.round))}` : '—'}</strong><span>Status · ${escapeHtml(statusLabel)}</span></div>
                    </div>
                </section>
                <div class="fm-grid-top fm-grid-top--fixture-preview">
                    <!-- Stadium preview panel intentionally hidden for now. -->
                    <section class="fm-panel club-profile-detail-card">
                        <div class="fm-panel-head">
                            <div>
                                <h3>Fixture details</h3>
                                <p class="fm-subtle">Core schedule metadata, OVR edge, and preview summary for the selected fixture.</p>
                            </div>
                            <span class="fm-panel-action">Preview</span>
                        </div>
                        <div class="club-profile-detail-list">
                            <div class="club-profile-detail-row"><span>Home team</span><strong>${escapeHtml(homeTeamName)}</strong></div>
                            <div class="club-profile-detail-row"><span>Away team</span><strong>${escapeHtml(awayTeamName)}</strong></div>
                            <div class="club-profile-detail-row"><span>Date & time</span><strong>${escapeHtml(matchDateTime)}</strong></div>
                            <div class="club-profile-detail-row"><span>Venue</span><strong>${escapeHtml(venue)}</strong></div>
                            <div class="club-profile-detail-row"><span>Status</span><strong>${escapeHtml(statusLabel)}</strong></div>
                            <div class="club-profile-detail-row"><span>Home OVR / form</span><strong>${Number.isFinite(homeStrength) ? Math.round(homeStrength) : '—'} · ${Number.isFinite(homeForm) ? homeForm.toFixed(1) : '—'}</strong></div>
                            <div class="club-profile-detail-row"><span>Away OVR / form</span><strong>${Number.isFinite(awayStrength) ? Math.round(awayStrength) : '—'} · ${Number.isFinite(awayForm) ? awayForm.toFixed(1) : '—'}</strong></div>
                            <div class="club-profile-detail-row"><span>Model lean</span><strong>${escapeHtml(previewLabel)}</strong></div>
                        </div>
                    </section>
                    <section class="fm-panel club-profile-detail-card fm-fixture-preview-card">
                        <div class="fm-panel-head">
                            <div>
                                <h3>Match preview</h3>
                                <p class="fm-subtle">Prediction, projected xG, and head-to-head summary from the real schedule insight flow.</p>
                            </div>
                            <span class="fm-panel-action">${Number.isFinite(confidence) ? `${Math.round(confidence)}%` : 'Heuristic'}</span>
                        </div>
                        <div class="fm-fixture-preview-hero fx-prediction">
                            <div class="fx-prediction-head">
                                <div>
                                    <div class="fx-prediction-title">Prediction</div>
                                    <div class="fm-fixture-preview-result">${escapeHtml(mostLikelyLabel)}</div>
                                </div>
                                <span class="fx-confidence">${Number.isFinite(confidence) ? `${Math.round(confidence)}% conf` : 'Heuristic lean'}</span>
                            </div>
                            <div class="fm-fixture-preview-xg-chip">xG ${Number.isFinite(expectedHomeGoals) ? expectedHomeGoals.toFixed(2) : '—'} : ${Number.isFinite(expectedAwayGoals) ? expectedAwayGoals.toFixed(2) : '—'}</div>
                            <div class="fx-prediction-note">${previewAnalysis}</div>
                        </div>
                        <div class="fm-medical-stat-grid team-summary-grid fm-fixture-preview-stats">
                            <div><strong>${Number.isFinite(homeStrength) ? Math.round(homeStrength) : '—'}</strong><span>${escapeHtml(homeTeamName)} OVR</span></div>
                            <div><strong>${Number.isFinite(awayStrength) ? Math.round(awayStrength) : '—'}</strong><span>${escapeHtml(awayTeamName)} OVR</span></div>
                            <div><strong>${Number.isFinite(homeForm) ? homeForm.toFixed(1) : '—'}</strong><span>${escapeHtml(homeTeamName)} form</span></div>
                            <div><strong>${Number.isFinite(awayForm) ? awayForm.toFixed(1) : '—'}</strong><span>${escapeHtml(awayTeamName)} form</span></div>
                        </div>
                        <div class="club-profile-detail-list">
                            <div class="club-profile-detail-row fm-fixture-preview-row--accent"><span>Model lean</span><strong>${escapeHtml(previewLabel)}</strong></div>
                            <div class="club-profile-detail-row"><span>Head-to-head</span><strong>${escapeHtml(String(h2h.summary || 'No head-to-head history yet.'))}</strong></div>
                            <div class="club-profile-detail-row"><span>Last meeting</span><strong>${escapeHtml(String(h2h.lastMeetingSummary || 'First recorded meeting.'))}</strong></div>
                        </div>
                    </section>
                </div>
            </div>`;
        } catch (err) {
            console.error("Error loading fixture:", err);
            mainContent.innerHTML = `
                <div class="fm-page fm-page--club">
                    <section class="fm-panel fm-club-hero">
	                        <button class="back-to-dashboard" data-nav-back="${backTarget}">Back</button>
                        <div class="fm-club-hero-main">
                            <div>
                                <div class="fm-eyebrow">Club schedule</div>
                                <h2>Fixture error</h2>
                                <p class="fm-subtle">There was a problem while loading this fixture.</p>
                            </div>
                            ${buildClubActionsHtml('schedule')}
                        </div>
                    </section>
                    <section class="fm-panel"><div class="fm-empty">Error loading fixture: ${escapeHtml(err.message)}</div></section>
                </div>`;
        }
    }
    async function loadFriendlies() {
        console.log(`Loading friendlies for ${currentUserTeamId}`);
        const response = await authFetch(`/demo/matches/teams/${currentUserTeamId}/friendlies`);
        console.log(`Response status: ${response.status}`);
        const matches = await response.json();
        renderMatches(matches, "Friendlies");
    }
    async function loadLeagueTable(seasonYear = null) {
        try {
	        const leagueId = await ensureCurrentLeagueId();
	        if (!leagueId) return;
	            const backTarget = getCurrentLeagueBackTarget();
	            const leagueName = getCurrentLeagueName();
            const seasonsResponse = await authFetch(`/countries/leagues/${leagueId}/seasons`);
            const seasons = seasonsResponse.ok ? await seasonsResponse.json() : [];
	        const selectedSeason = seasonYear || currentLeagueSeasonYear || currentSeasonYear || seasons[seasons.length - 1]?.seasonYear || null;
            currentLeagueSeasonYear = selectedSeason;
            const selectedSeasonNumber = seasons.find(s => s.seasonYear === selectedSeason)?.seasonNumber
                || (selectedSeason ? Math.max(1, selectedSeason - 2025 + 1) : 1);
            const seasonParam = selectedSeason ? `?seasonYear=${selectedSeason}` : "";

            const [tableResponse, teamsResponse, scheduleResponse, scorersResponse, assistsResponse, milestonesResponse, seasonSummaryResponse] = await Promise.all([
                authFetch(`/countries/leagues/${leagueId}/table${seasonParam}`),
                authFetch(`/countries/leagues/${leagueId}/teams${seasonParam}`),
                authFetch(`/countries/leagues/${leagueId}/schedule${seasonParam}`),
                authFetch(`/stats/leagues/${leagueId}/topscorers${seasonParam}`),
                authFetch(`/stats/leagues/${leagueId}/topassists${seasonParam}`),
                authFetch(`/stats/leagues/${leagueId}/milestones${seasonParam}`),
                authFetch(`/countries/leagues/${leagueId}/season-summary${seasonParam}`)
            ]);
            if (!tableResponse.ok) throw new Error(`League table load failed: ${tableResponse.status}`);
            if (!teamsResponse.ok) throw new Error(`League teams load failed: ${teamsResponse.status}`);

            const table = await tableResponse.json();
            const leagueTeams = await teamsResponse.json();
            const schedule = scheduleResponse.ok ? await scheduleResponse.json() : [];
            const scorers = scorersResponse.ok ? await scorersResponse.json() : [];
            const assists = assistsResponse.ok ? await assistsResponse.json() : [];
            const milestones = milestonesResponse.ok ? await milestonesResponse.json() : null;
            const seasonSummary = seasonSummaryResponse.ok ? await seasonSummaryResponse.json() : null;
	            const teamMetaByName = new Map();
	            const teamIdByName = new Map();
	            leagueTeams.forEach(team => {
	                const key = normalizeTeamKey(team.name);
	                teamMetaByName.set(key, team);
	                teamIdByName.set(key, team.id);
	            });

            const enhancedTable = table.map(row => ({
                ...row,
	                teamId: row.teamId ?? teamIdByName.get(normalizeTeamKey(row.name)) ?? null,
	                humanControlled: typeof row.humanControlled === 'boolean'
	                    ? row.humanControlled
	                    : (typeof teamMetaByName.get(normalizeTeamKey(row.name))?.humanControlled === 'boolean'
	                        ? teamMetaByName.get(normalizeTeamKey(row.name)).humanControlled
	                        : null)
            }));

            const byRound = new Map();
            schedule.forEach(match => {
                const round = Number(match.round || 1);
                if (!byRound.has(round)) byRound.set(round, []);
                byRound.get(round).push({
                    ...match,
                    homeTeamId: teamIdByName.get(normalizeTeamKey(match.homeTeam)) ?? null,
                    awayTeamId: teamIdByName.get(normalizeTeamKey(match.awayTeam)) ?? null
                });
            });
            const rounds = [...byRound.keys()].sort((a, b) => a - b);
            const currentRound = rounds.find(round => (byRound.get(round) || []).some(match => !match.played))
                || rounds[rounds.length - 1]
                || 1;

            const playerIdByKey = new Map();
            try {
                const response = await authFetch(`/countries/leagues/${leagueId}/player-directory${seasonParam}`);
                if (response.ok) {
                    const directory = await response.json();
                    directory.forEach(player => {
                        playerIdByKey.set(
                            `${normalizeTeamKey(player.teamName)}|${normalizePlayerKey(player.name)}`,
                            player.id
                        );
                    });
                }
            } catch (e) {
                console.warn('League player directory fetch failed:', e);
            }

            const visibleRounds = rounds.map(round => ({
                round,
                label: round === currentRound
                    ? 'Current focus'
                    : round === currentRound - 1
                        ? 'Latest results'
                        : round === currentRound + 1
                            ? 'Next fixtures'
                            : '',
                isFocusRound: round === currentRound,
                matches: byRound.get(round)
            }));

            const mappedScorers = scorers.map(item => ({
                ...item,
                teamId: teamIdByName.get(normalizeTeamKey(item.teamName)) ?? null,
                playerId: playerIdByKey.get(`${normalizeTeamKey(item.teamName)}|${normalizePlayerKey(item.playerName || item.name)}`) ?? null
            }));
            const mappedAssists = assists.map(item => ({
                ...item,
                teamId: teamIdByName.get(normalizeTeamKey(item.teamName)) ?? null,
                playerId: playerIdByKey.get(`${normalizeTeamKey(item.teamName)}|${normalizePlayerKey(item.playerName || item.name)}`) ?? null
            }));

            renderTable({
	            leagueId,
		            leagueName,
	                backTarget,
	                fixtureBackTarget: 'leagueTable',
	                matchCaller: 'leagueTable',
                table: enhancedTable,
                fixtures: visibleRounds,
                topScorers: mappedScorers,
                topAssists: mappedAssists,
                milestones,
                seasonSummary,
                seasons,
                selectedSeason,
                selectedSeasonNumber
            });
        } catch (err) {
            console.error("Failed to load league table:", err);
            document.getElementById("main-content").innerHTML = `
                <div class="manager-card">
	                    <button class="back-to-dashboard" data-nav-back="${backTarget}">Back</button>
                    <h2>Error</h2>
                    <p>Could not load league table.</p>
                </div>`;
        }
    }
    async function loadLeagueMatches(seasonYear = null) {
        try {
	        const leagueId = await ensureCurrentLeagueId();
	        if (!leagueId) return;
	            const backTarget = getCurrentLeagueBackTarget();
            console.log(`Loading league matches...`);
	        const selectedSeason = seasonYear || currentLeagueSeasonYear || currentSeasonYear || null;
	        currentLeagueSeasonYear = selectedSeason ?? currentLeagueSeasonYear;
	        const seasonParam = selectedSeason ? `?seasonYear=${selectedSeason}` : "";
            const response = await authFetch(`/countries/leagues/${leagueId}/matches${seasonParam}`);
            console.log(`Response status: ${response.status}`);
            if (!response.ok) throw new Error("Failed to load league matches");
            const matches = await response.json();
            const results = matches.sort((a, b) => new Date(b.matchDate) - new Date(a.matchDate));
	        const seasonNumber = selectedSeason ? Math.max(1, selectedSeason - 2025 + 1) : null;
	        const titleBase = `${getCurrentLeagueName()} Results`;
		        renderLeagueMatches(results, seasonNumber ? `${titleBase} - Season ${seasonNumber}` : titleBase, { backTarget, caller: 'leagueMatches' });
        } catch (err) {
            console.error(err);
            document.getElementById("main-content").innerHTML = `
                <div class="manager-card">
	                    <button data-nav-back="${getCurrentLeagueBackTarget()}">Back</button>
                    <h2>Error</h2>
                    <p>Could not load league matches.</p>
                </div>`;
        }
    }
    async function loadLeagueSchedule(seasonYear = null) {
        try {
	        const leagueId = await ensureCurrentLeagueId();
	        if (!leagueId) return;
	            const backTarget = getCurrentLeagueBackTarget();
	            const selectedSeasonParam = seasonYear || currentLeagueSeasonYear || currentSeasonYear || null;
	            const teamsSeasonParam = selectedSeasonParam ? `?seasonYear=${encodeURIComponent(selectedSeasonParam)}` : '';
	            const [seasonsResponse, teamsResponse] = await Promise.all([
	                authFetch(`/countries/leagues/${leagueId}/seasons`),
	                authFetch(`/countries/leagues/${leagueId}/teams${teamsSeasonParam}`)
	            ]);
            const seasons = seasonsResponse.ok ? await seasonsResponse.json() : [];
	            const leagueTeams = teamsResponse.ok ? await teamsResponse.json() : [];
	            const teamIdByName = new Map();
	            leagueTeams.forEach(team => {
	                teamIdByName.set(normalizeTeamKey(team.name), team.id);
	            });
		        const selectedSeason = selectedSeasonParam || seasons[seasons.length - 1]?.seasonYear || null;
            currentLeagueSeasonYear = selectedSeason;
            const selectedSeasonNumber = seasons.find(s => s.seasonYear === selectedSeason)?.seasonNumber || 1;
            const seasonParam = selectedSeason ? `?seasonYear=${selectedSeason}` : "";
            const response = await authFetch(`/countries/leagues/${leagueId}/schedule${seasonParam}`);
            if (!response.ok) throw new Error(`Failed to load schedule: ${response.status}`);
            const schedule = await response.json();

            const byRound = new Map();
            let currentRound = 1;
            schedule.forEach(m => {
                const round = Number(m.round || 1);
                if (!byRound.has(round)) byRound.set(round, []);
	                byRound.get(round).push({
	                    ...m,
	                    homeTeamId: teamIdByName.get(normalizeTeamKey(m.homeTeam)) ?? null,
	                    awayTeamId: teamIdByName.get(normalizeTeamKey(m.awayTeam)) ?? null
	                });
                if (!m.played && round < currentRound) currentRound = round;
            });
            const rounds = [...byRound.keys()].sort((a, b) => a - b);
            const firstUnplayed = rounds.find(r => byRound.get(r).some(m => !m.played));
            if (firstUnplayed) currentRound = firstUnplayed;
            renderLeagueScheduleView({
                rounds: rounds.map(round => ({
                    round,
                    label: round === currentRound
                        ? 'Current focus'
                        : round === currentRound - 1
                            ? 'Latest results'
                            : round === currentRound + 1
                                ? 'Next fixtures'
                                : '',
                    isFocusRound: round === currentRound,
                    matches: byRound.get(round) || []
                })),
	                leagueName: getCurrentLeagueName(),
	                backTarget,
	                fixtureBackTarget: 'leagueSchedule',
	                matchCaller: 'leagueSchedule',
                seasons,
                selectedSeason,
                selectedSeasonNumber
            }, {
                loadLeagueSchedule,
                loadMatch,
                loadLeagueTeam,
                loadFixture
            });
        } catch (err) {
            console.error("Failed to load league schedule:", err);
            document.getElementById("main-content").innerHTML = `
                <div class="manager-card">
	                    <button class="back-to-dashboard" data-nav-back="${getCurrentLeagueBackTarget()}">Back</button>
                    <h2>Error</h2>
                    <p>Could not load league schedule.</p>
                </div>`;
        }
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
    async function loadPlayerStats() {
        console.log(`Loading player stats for userTeamId ${currentUserTeamId}`);
        const response = await authFetch(`/demo/stats/teams/${currentUserTeamId}/players`);
        console.log(`Response status: ${response.status}`);
        const players = await response.json();
        renderPlayers(players, "Player Stats");
    }
    async function loadTeamStats() {
        console.log(`Loading team stats for ${currentUserTeamId}`);
        const response = await authFetch(`/demo/stats/teams/${currentUserTeamId}`);
        console.log(`Response status: ${response.status}`);
        const stats = await response.json();

        const mainContent = document.getElementById("main-content");

        let html = `
        <div class="manager-card">
            <button class="back-to-dashboard" data-nav-back="dashboard">Back</button>
            <h2>Team Stats</h2>
            <p>Goals: ${stats.goals}</p>
            <p>Conceded: ${stats.conceded}</p>
            <p>Possession: ${stats.possession}%</p>
            <p>Shots per game: ${stats.shots}</p>
        </div>`;
        mainContent.innerHTML = html;
    }
    async function loadTopScorersAndAssists(mode = "both") {
        try {
            console.log(`Loading top scorers for ${currentUserTeamId}`);
	        const leagueId = await ensureCurrentLeagueId();
	        if (!leagueId) return;
	        const seasonParam = currentLeagueSeasonYear || currentSeasonYear
	            ? `?seasonYear=${currentLeagueSeasonYear || currentSeasonYear}`
	            : '';
            const [scorersRes, assistsRes, leagueTeamsRes] = await Promise.all([
	            authFetch(`/stats/leagues/${leagueId}/topscorers${seasonParam}`),
	            authFetch(`/stats/leagues/${leagueId}/topassists${seasonParam}`),
	            authFetch(`/countries/leagues/${leagueId}/teams${seasonParam}`)
            ]);
            console.log(`Response status: ${scorersRes.status}`);
            console.log(`Response status: ${assistsRes.status}`);
            const scorers = await scorersRes.json();
            const assists = await assistsRes.json();
            const leagueTeams = leagueTeamsRes.ok ? await leagueTeamsRes.json() : [];
            const teamIdByName = new Map();
            leagueTeams.forEach(t => teamIdByName.set(t.name, t.id));
            const playerIdByKey = new Map();
            try {
                const directoryRes = await authFetch(`/countries/leagues/${leagueId}/player-directory${seasonParam}`);
                if (directoryRes.ok) {
                    const directory = await directoryRes.json();
                    directory.forEach(player => {
                        playerIdByKey.set(`${player.teamName}|${player.name}`, player.id);
                    });
                }
            } catch (e) {}

            const mainContent = document.getElementById("main-content");

            let html = `
            <div class="manager-card" style="padding: 25px;">
                <button class="back-to-dashboard" data-nav-back="dashboard">Back</button>
                <h2 style="text-align: center; margin: 20px 0 30px; color: #e94560;">League Stats - Top Lists</h2>

                <div class="top-lists">

                    ${mode !== "assists" ? `
                    <div class="top-scorers top-list-panel">
                        <h3 style="text-align: center; color: #ffd700; margin-bottom: 15px;">Top Scorers</h3>
                        <ul style="list-style: none; padding: 0; margin: 0;">` : ""}`;

            if (mode !== "assists") {
                scorers.forEach((s, i) => {
                    const rankColor = i < 3 ? '#ffd700' : '#aaa';
                    const bgColor = i % 2 === 0 ? 'rgba(255,255,255,0.05)' : 'rgba(0,0,0,0.1)';
                    html += `
                    <li class="top-list-entry" style="background: ${bgColor};">
                        <span class="top-list-rank" style="color: ${rankColor};">${i+1}.</span>
                        <span class="top-list-player">
                            ${playerIdByKey.get(`${s.teamName}|${s.playerName}`) && teamIdByName.get(s.teamName)
                                ? `<span class="cs-clickable" onclick="loadLeagueTeamPlayer(${playerIdByKey.get(`${s.teamName}|${s.playerName}`)}, ${teamIdByName.get(s.teamName)}, '${escapeHtml(s.teamName)}')">${s.playerName}</span>`
                                : s.playerName}
                            <small style="color: #888;">(${teamIdByName.get(s.teamName) ? `<span class="cs-clickable" onclick="loadLeagueTeam(${teamIdByName.get(s.teamName)}, '${escapeHtml(s.teamName)}')">${s.teamName}</span>` : s.teamName})</small>
                        </span>
                        <span class="top-list-value" style="color: #ff7582;">
                            ${s.goals} goals
                        </span>
                    </li>`;
                });
            }

            if (mode !== "assists") {
                html += `</ul></div>`;
            }

            if (mode !== "scorers") {
                html += `<div class="top-assists top-list-panel">
                        <h3 style="text-align: center; color: #9d4edd; margin-bottom: 15px;">Top Assists</h3>
                        <ul style="list-style: none; padding: 0; margin: 0;">`;
            }

            if (mode !== "scorers") {
                assists.forEach((a, i) => {
                    const rankColor = i < 3 ? '#9d4edd' : '#aaa';
                    const bgColor = i % 2 === 0 ? 'rgba(255,255,255,0.05)' : 'rgba(0,0,0,0.1)';
                    html += `
                    <li class="top-list-entry" style="background: ${bgColor};">
                        <span class="top-list-rank" style="color: ${rankColor};">${i+1}.</span>
                        <span class="top-list-player">
                            ${playerIdByKey.get(`${a.teamName}|${a.playerName}`) && teamIdByName.get(a.teamName)
                                ? `<span class="cs-clickable" onclick="loadLeagueTeamPlayer(${playerIdByKey.get(`${a.teamName}|${a.playerName}`)}, ${teamIdByName.get(a.teamName)}, '${escapeHtml(a.teamName)}')">${a.playerName}</span>`
                                : a.playerName}
                            <small style="color: #888;">(${teamIdByName.get(a.teamName) ? `<span class="cs-clickable" onclick="loadLeagueTeam(${teamIdByName.get(a.teamName)}, '${escapeHtml(a.teamName)}')">${a.teamName}</span>` : a.teamName})</small>
                        </span>
                        <span class="top-list-value" style="color: #4fc3f7;">
                            ${a.assists} assists
                        </span>
                    </li>`;
                });
            }

            if (mode !== "scorers") {
                html += `</ul></div>`;
            }
            html += `</div></div>`;

            mainContent.innerHTML = html;

            // Dodatni hover efekat (moÅ¾eÅ¡ i CSS-om, ali ovde inline za brzinu)
            document.querySelectorAll('.top-lists li').forEach(li => {
                li.addEventListener('mouseenter', () => {
                    li.style.background = 'rgba(157, 78, 221, 0.15)'; // ljubiÄasto hover
                    li.style.transform = 'translateX(5px)';
                });
                li.addEventListener('mouseleave', () => {
                    li.style.background = li.style.background.includes('0.05') ? 'rgba(255,255,255,0.05)' : 'rgba(0,0,0,0.1)';
                    li.style.transform = 'translateX(0)';
                });
            });

        } catch (err) {
            console.error("Error loading top lists:", err);
            document.getElementById("main-content").innerHTML = `
                <div class="manager-card">
                    <button data-nav-back="dashboard">Back</button>
                    <h2>Error</h2>
                    <p>Could not load top lists. Check connection or backend.</p>
                </div>`;
        }
    }
    async function loadAnalytics() {
            window.location.href = '/zox-match-preview.html';
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
        renderTableView(table, { loadLeagueTeam, loadLeagueTeamPlayer, loadLeagueTable, loadMatch, loadFixture, escapeHtml, formatGoalDiff });
    }
    async function loadLeagueTeam(teamId, teamName, options = {}) {
        const seasonYear = options.seasonYear ?? currentLeagueSeasonYear ?? null;
        const pushHistory = options.pushHistory !== false;
        currentLeagueSeasonYear = seasonYear ?? currentLeagueSeasonYear;
	        if (pushHistory) pushNavState({ type: 'leagueTeam', teamId, teamName, seasonYear, ...getActiveLeagueNavState() });
        const mainContent = document.getElementById("main-content");
        try {
            const [response, transferOverview, milestones] = await Promise.all([
                authFetch(`/teams/${teamId}/players`),
                (async () => {
                    try {
                        const transferResponse = await authFetch(`/transfers/team/${teamId}?viewerTeamId=${encodeURIComponent(currentUserTeamId)}`);
                        return await transferResponse.json();
                    } catch {
                        return null;
                    }
                })(),
                (async () => {
                    try {
                        const milestoneResponse = await authFetch(`/teams/${teamId}/milestones`);
                        return milestoneResponse.ok ? await milestoneResponse.json() : null;
                    } catch {
                        return null;
                    }
                })()
            ]);
            if (!response.ok) throw new Error(`Team players load failed: ${response.status}`);
            const players = await response.json();
            const isUserTeam = Number(teamId) === Number(currentUserTeamId);

            const avgOverall = players.length
                ? (players.reduce((sum, player) => sum + Number(player.overall || 0), 0) / players.length).toFixed(1)
                : '-';
            const avgAge = players.length
                ? (players.reduce((sum, player) => sum + Number(player.age || 0), 0) / players.length).toFixed(1)
                : '-';
            const totalGoals = players.reduce((sum, player) => sum + Number(player.totalGoals ?? player.goals ?? 0), 0);
            const totalAssists = players.reduce((sum, player) => sum + Number(player.totalAssists ?? player.assists ?? 0), 0);
            const injuryCount = players.filter(player => player.injured).length;
            const poorFormCount = players.filter(player => Number(player.form) <= 5.8).length;

            let html = `
            <div class="fm-page fm-page--team-detail">
                <section class="fm-panel fm-club-hero">
                    <button class="back-to-dashboard" onclick="goBackSmart('leagueTable')">Back</button>
                    <div class="fm-club-hero-main">
                        <div>
                            <div class="fm-eyebrow">Team overview</div>
                            <h2>${escapeHtml(teamName)}</h2>
                            <p class="fm-subtle">Open-football inspired squad screen. Click any row to open the player profile.</p>
                        </div>
                        ${isUserTeam ? buildClubActionsHtml('firstTeam') : ''}
                    </div>
                    <div class="fm-medical-stat-grid team-summary-grid">
                        <div><strong>${players.length}</strong><span>Players</span></div>
                        <div><strong>${avgOverall}</strong><span>Avg OVR</span></div>
                        <div><strong>${avgAge}</strong><span>Avg age</span></div>
                        <div><strong>${totalGoals}/${totalAssists}</strong><span>Goals / assists</span></div>
                    </div>
                </section>
                <section class="fm-panel fm-milestone-board-panel">
                    <div class="fm-panel-head">
                        <div>
                            <h3>${isUserTeam ? 'Club milestones' : 'Team milestones'}</h3>
                            <p class="fm-subtle">Season board for ${escapeHtml(teamName)}, keeping club context visible without losing the main league board.</p>
                        </div>
                        <span class="fm-panel-action">${isUserTeam ? 'Club area' : 'Season board'}</span>
                    </div>
                    ${buildMilestoneBoardHtml(milestones)}
                </section>
                <div class="fm-team-layout ${isUserTeam ? 'has-side-panel' : ''}">
                    <section class="fm-panel">
                        <div class="fm-panel-head">
                            <h3>Squad</h3>
                            <span class="fm-panel-action">${players.length} registered</span>
                        </div>
                        ${buildSquadTableHtml(players, {
                            rowClass: 'league-player-card',
                            teamId,
                            teamName,
                            emptyText: 'No registered players found for this team.'
                        })}
                    </section>
                    ${isUserTeam ? `
                    <aside class="fm-panel fm-medical-panel">
                        <div class="fm-panel-head">
                            <h3>Medical Center</h3>
                            <span class="fm-panel-action">Club area</span>
                        </div>
                        <div class="fm-medical-icon">&#10010; &#129658;</div>
                        <p class="fm-subtle">Keep the open-football squad view, but retain our app-specific medical workflow for injuries and recovery.</p>
                        <div class="fm-medical-stat-grid">
                            <div><strong>${injuryCount}</strong><span>Injuries</span></div>
                            <div><strong>${poorFormCount}</strong><span>Low morale</span></div>
                            <div><strong>${players.length - injuryCount}</strong><span>Available</span></div>
                        </div>
                        <button type="button" class="fm-action-btn secondary" onclick="loadPage('medicalCenter')">Open Medical Center</button>
                    </aside>` : ''}
                </div>
                ${buildTeamTransferOverviewHtml(transferOverview, { isUserTeam, teamName })}
            </div>`;
            mainContent.innerHTML = html;

            bindSquadRowClicks(mainContent, row => {
                const playerId = Number(row.dataset.playerId);
                const playerTeamId = Number(row.dataset.teamId);
                const playerTeamName = row.dataset.teamName || "Team";
                loadLeagueTeamPlayer(playerId, playerTeamId, playerTeamName, { seasonYear });
            });
            mainContent.querySelectorAll('[data-team-transfer-player-id]').forEach(button => {
                button.addEventListener('click', () => {
                    loadLeagueTeamPlayer(
                        Number(button.dataset.teamTransferPlayerId || 0),
                        Number(button.dataset.teamTransferTeamId || teamId),
                        button.dataset.teamTransferTeamName || teamName || 'Team',
                        { seasonYear }
                    );
                });
            });
        } catch (err) {
            console.error("Failed to load team details:", err);
            mainContent.innerHTML = `
            <div class="manager-card">
                <button class="back-to-dashboard" onclick="goBackSmart('leagueTable')">Back</button>
                <h2>Error</h2>
                <p>Could not load team details.</p>
            </div>`;
        }
    }
    async function loadLeagueTeamPlayer(playerId, teamId, teamName, options = {}) {
        const seasonYear = options.seasonYear ?? currentLeagueSeasonYear ?? null;
        const pushHistory = options.pushHistory !== false;
        currentLeagueSeasonYear = seasonYear ?? currentLeagueSeasonYear;
	        if (pushHistory) pushNavState({ type: 'leagueTeamPlayer', playerId, teamId, teamName, seasonYear, ...getActiveLeagueNavState() });
        const mainContent = document.getElementById("main-content");
        try {
            const [playerResponse, ratingSummary, transferStatus] = await Promise.all([
                authFetch(`/players/${playerId}`),
                fetchPlayerRatingSummary(playerId),
                fetchPlayerTransferStatus(playerId)
            ]);

            if (!playerResponse.ok) throw new Error(`Player load failed: ${playerResponse.status}`);
            const player = await playerResponse.json();
            mainContent.innerHTML = buildPlayerProfileHtml(player, {
                backLabel: 'Back',
                eyebrow: 'Team player',
                teamName: teamName || 'Team',
                ratingSummary,
                transferStatus,
                placeholderPrefix: 'This tab UI is ready'
            });
            initPlayerProfilePage({
                onBack: () => goBackSmart('leagueTable'),
                onTransferAction: (action, button) => handlePlayerTransferAction(action, button, {
                    playerId,
                    reloadCurrent: () => loadLeagueTeamPlayer(playerId, teamId, teamName, { pushHistory: false, seasonYear }),
                    reloadOwned: () => loadPlayer(playerId, 'leagueTable', { pushHistory: false })
                })
            });
        } catch (err) {
            console.error("Failed to load player profile:", err);
            mainContent.innerHTML = `
                <div class="manager-card">
                    <button id="back-to-league-team-fallback" class="back-to-dashboard">Back</button>
                    <h2>Error</h2>
                    <p>Could not load player profile.</p>
                </div>`;
            const backButton = document.getElementById("back-to-league-team-fallback");
            if (backButton) {
                backButton.addEventListener("click", () => goBackSmart('leagueTable'));
            }
        }
    }
        function renderFixtures(fixtures, title, options = {}) {
        renderFixturesView(fixtures, title, options);
    }
	    function renderLeagueMatches(matches, title = "League Results", options = {}) {
	        renderLeagueMatchesView(matches, title, { loadMatch, ...options });
    }
    function openStadiumImage(imageUrl) {
        // Otvara sliku u novom tabu ili modalu
        window.open(imageUrl, '_blank');
        // Alternativa: modal (ako Å¾eliÅ¡ lepÅ¡e)
         const modal = document.createElement('div');
         modal.innerHTML = `<img src="${imageUrl}" style="max-width:90vw; max-height:90vh;">`;
         modal.style.position = 'fixed'; modal.style.top='5%'; modal.style.left='5%'; etc.
         document.body.appendChild(modal);
    }
    function showStadiumModal(imageUrl, stadiumName) {
        const modal = document.createElement('div');
        modal.style.position = 'fixed';
        modal.style.inset = '0';
        modal.style.background = 'rgba(0,0,0,0.85)';
        modal.style.display = 'flex';
        modal.style.alignItems = 'center';
        modal.style.justifyContent = 'center';
        modal.style.zIndex = '9999';
        modal.innerHTML = `
            <div style="position: relative; max-width: 90vw; max-height: 90vh;">
                <button onclick="this.parentElement.parentElement.remove()"
                        style="position: absolute; top: -40px; right: 0; background: #f44336; color: white; border: none; border-radius: 50%; width: 36px; height: 36px; font-size: 1.4em; cursor: pointer;">
                    &times;
                </button>
                <img src="${imageUrl}" alt="${stadiumName}" style="max-width: 100%; max-height: 85vh; border-radius: 12px; box-shadow: 0 10px 40px rgba(0,0,0,0.7);">
                <p style="color: white; text-align: center; margin-top: 12px; font-size: 1.2em;">
                    ${stadiumName}
                </p>
            </div>
        `;
        // Zatvaranje klika van slike
        modal.onclick = (e) => {
            if (e.target === modal) modal.remove();
        };
        document.body.appendChild(modal);
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



























