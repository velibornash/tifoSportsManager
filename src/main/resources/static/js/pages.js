// pages.js
import { authFetch } from './auth.js';
import { renderPlayersView, renderMatchesView, renderTableView, renderFixturesView, renderLeagueMatchesView, buildSquadTableHtml, bindSquadRowClicks, buildClubActionsHtml, buildTrainingActionsHtml, buildCommunityActionsHtml } from './pages-renderers.js';
import { createAcademyFeature } from './pages/features/academy.js';
import { createTeamFeature } from './pages/features/team.js';
import { createMatchesFeature } from './pages/features/matches.js';
import { createClubManagementFeature } from './pages/features/club-management.js';
import { createCommunityFeature } from './pages/features/community.js';
    let currentUserTeamId = null;
    let currentUserTeamName = '';
    let currentPageId = 'dashboard';
    let currentNavState = { type: 'dashboard' };
    const navHistoryStack = [];
    let navReplayMode = false;
    let navBusy = false;

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
            await loadPage(state.page, { pushHistory: false });
            return;
        }
        if (state.type === 'player') {
            await loadPlayer(state.playerId, state.callerPage, { pushHistory: false });
            return;
        }
        if (state.type === 'match') {
            await loadMatch(state.matchId, state.caller, { pushHistory: false });
            return;
        }
        if (state.type === 'fixture') {
            await loadFixture(state.fixtureId, { pushHistory: false });
            return;
        }
        if (state.type === 'leagueTeam') {
            await loadLeagueTeam(state.teamId, state.teamName, { pushHistory: false });
            return;
        }
        if (state.type === 'leagueTeamPlayer') {
            await loadLeagueTeamPlayer(state.playerId, state.teamId, state.teamName, { pushHistory: false });
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
        await loadPage(fallback, { pushHistory: false });
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
            console.log("Team ID loaded:", currentUserTeamId);
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
    async function loadPage(page, options = {}) {
        const pushHistory = options.pushHistory !== false;
        const mainContent = document.getElementById("main-content");
        currentPageId = page;
        if (pushHistory) pushNavState({ type: 'page', page });
    if (!currentUserTeamId) {
            await loadUserTeamId();
            if (!currentUserTeamId) return;
        }
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
                    await loadResults();
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
            const res = await authFetch('/countries/leagues/1/teams');
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
        escapeHtml,
        formatDateTimeLabel,
        buildCommunityActionsHtml,
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
            actionButtons.push(`<button type="button" class="fm-action-btn" data-transfer-panel-action="direct-buy" data-player-id="${player.id}" data-default-price="${defaultValue}">Direct buy</button>`);
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
                    const price = promptTransferActionPrice('Enter direct-buy fee for this player:', button?.dataset?.defaultPrice || 1);
                    if (price == null) return;
                    await performTransferJsonAction(`/transfers/direct-buy/${resolvedPlayerId}`, { teamId: currentUserTeamId, price });
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
        if (pushHistory) pushNavState({ type: 'match', matchId, caller });
        const mainContent = document.getElementById("main-content");
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

                <div style="display:flex; justify-content:space-around; font-size:1.3em; margin:20px 0; font-weight:bold;">
                    <div style="text-align:center;">
                        <div>${homeTeamId ? `<span class="cs-clickable" onclick="loadLeagueTeam(${homeTeamId}, '${escapeHtml(homeTeamName)}')">${homeTeamName}</span>` : homeTeamName}</div>
                        <div>${homeGoals}</div>
                    </div>
                    <div style="align-self:center; font-size:1.6em;">-</div>
                    <div style="text-align:center;">
                        <div>${awayTeamId ? `<span class="cs-clickable" onclick="loadLeagueTeam(${awayTeamId}, '${escapeHtml(awayTeamName)}')">${awayTeamName}</span>` : awayTeamName}</div>
                        <div>${awayGoals}</div>
                    </div>
                </div>

                <div style="text-align:center; color:#aaa; margin-bottom:25px;">
                    &#128197; ${formattedDate}
                </div>

                <div id="match-buttons-container" style="display:flex; justify-content:center; gap:12px; margin-bottom:25px; flex-wrap:wrap;">
                    <button id="view-lineups" style="padding:8px 16px; font-weight:bold;">Lineups</button>
                    <button id="view-stats" style="padding:8px 16px; font-weight:bold;">Stats</button>
                    <button id="view-goals" style="padding:8px 16px; font-weight:bold;">Goals</button>
                    <button id="view-replay" style="padding:8px 16px; font-weight:bold;">Replay</button>
                    <button id="view-report" style="padding:8px 16px; font-weight:bold;">Match Report</button>
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
            } else {
                console.warn(`Unknown caller: ${caller} -> fallback to 'results'`);
            }

            backButton.dataset.target = backTarget;
            //backButton.textContent = backText;
            backButton.style.display = 'inline-block';

             const infoDiv = document.getElementById("match-info");
             let cachedMatchReport = null;

            function renderMatchReport(reportPayload) {
                const headline = escapeHtml(String(reportPayload?.headline || 'Match Report'));
                const reportText = escapeHtml(String(reportPayload?.report || 'No match report available.'));

                infoDiv.innerHTML = `
                    <div style="max-width:920px; margin:0 auto; padding:18px; background:rgba(255,255,255,0.04); border:1px solid rgba(255,255,255,0.08); border-radius:12px;">
                        <h3 style="text-align:center; margin:0 0 14px; color:#4CAF50;">Match Report</h3>
                        <div style="font-size:1.02em; font-weight:700; text-align:center; margin-bottom:16px; color:#f3f3f3;">${headline}</div>
                        <div style="white-space:pre-line; line-height:1.7; color:#ddd;">${reportText}</div>
                    </div>`;
            }

            async function showMatchReport() {
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

                const adjHomeShotsOn = Math.max(homeShotsOn, homeGoalsCount);
                const adjAwayShotsOn = Math.max(awayShotsOn, awayGoalsCount);
                const homeTotalShots = Math.max(adjHomeShotsOn + homeShotsOff, homeGoalsCount);
                const awayTotalShots = Math.max(adjAwayShotsOn + awayShotsOff, awayGoalsCount);

                const homeCorners = events.filter(e => e.eventType === "CornerEvent" && e.eventTeam === homeTeamName).length;
                const awayCorners = events.filter(e => e.eventType === "CornerEvent" && e.eventTeam === awayTeamName).length;

                const homeYellows = events.filter(e => e.eventType === "YellowCardEvent" && e.eventTeam === homeTeamName).length;
                const awayYellows = events.filter(e => e.eventType === "YellowCardEvent" && e.eventTeam === awayTeamName).length;

                const homeReds = events.filter(e => e.eventType === "RedCardEvent" && e.eventTeam === homeTeamName).length;
                const awayReds = events.filter(e => e.eventType === "RedCardEvent" && e.eventTeam === awayTeamName).length;

                const homePenalties = events.filter(e => e.eventType === "PenaltyEvent" && e.eventTeam === homeTeamName).length;
                const awayPenalties = events.filter(e => e.eventType === "PenaltyEvent" && e.eventTeam === awayTeamName).length;

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
                        <tr style="background:rgba(255,255,255,0.04);"><td style="padding:10px;">Shots</td><td style="text-align:center;">${homeTotalShots}</td><td style="text-align:center;">${awayTotalShots}</td></tr>
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

            // Automatski prikaÅ¾i statistiku odmah
            showStats();

            // Listener-i za ostala dugmad
            document.getElementById("view-lineups").addEventListener("click", () => {
                if (!lineupsPayload || (!lineupsPayload.homeLineup && !lineupsPayload.awayLineup)) {
                    infoDiv.innerHTML = `<p style="color:#aaa; text-align:center; padding:30px;">Lineups are not available for this match.</p>`;
                    return;
                }

                const renderLineup = (teamName, players) => {
                    const sorted = [...(players || [])].sort((a, b) => {
                        const posOrder = { GK: 0, DEF: 1, MID: 2, WNG: 3, ATT: 4 };
                        return (posOrder[a.position] ?? 9) - (posOrder[b.position] ?? 9);
                    });
                    if (sorted.length === 0) return `<p style="color:#aaa;">No lineup data.</p>`;

                    let html = `
                        <h4 style="margin: 16px 0 8px; color:#ddd;">${teamName}</h4>
                        <div style="display:flex; gap:10px; padding:4px 10px; color:#888; font-size:0.82em;">
                            <div style="width:42px; text-align:center;">POS</div>
                            <div style="flex:1;">Name</div>
                            <div style="width:56px; text-align:center;">Grade</div>
                            <div style="width:42px; text-align:center;">G</div>
                            <div style="width:42px; text-align:center;">A</div>
                            <div style="width:64px; text-align:center;">Min</div>
                        </div>`;
                    sorted.forEach((p, i) => {
                        const rowBg = i % 2 === 0 ? "rgba(255,255,255,0.03)" : "transparent";
                        const gradeValue = Number(p.grade);
                        const gradeText = Number.isFinite(gradeValue) ? gradeValue.toFixed(1) : "-";
                        const gradeColor = getRatingColor(gradeValue);
                        html += `
                            <div style="display:flex; gap:10px; padding:8px 10px; border-radius:6px; background:${rowBg};">
                                <div style="width:42px; text-align:center; color:#2a8c4a; font-weight:700;">${p.position}</div>
                                <div style="flex:1;">${p.playerId ? `<span class="cs-clickable" onclick="loadLeagueTeamPlayer(${teamName === (lineupsPayload.homeTeam || homeTeamName) ? (lineupsPayload.homeTeamId || 0) : (lineupsPayload.awayTeamId || 0)}, ${p.playerId}, '${escapeHtml(teamName)}')">${p.playerName}</span>` : p.playerName}</div>
                                <div style="width:56px; text-align:center; font-weight:700; color:${gradeColor};">${gradeText}</div>
                                <div style="width:42px; text-align:center;">${p.goals ?? 0}</div>
                                <div style="width:42px; text-align:center;">${p.assists ?? 0}</div>
                                <div style="width:64px; text-align:center;">${p.minutesPlayed ?? 0}</div>
                            </div>`;
                    });
                    return html;
                };

                infoDiv.innerHTML = `
                    <h3 style="text-align:center; margin:0 0 16px; color:#4CAF50;">Lineups & Grades</h3>
                    ${renderLineup(lineupsPayload.homeTeam || homeTeamName, lineupsPayload.homeLineup || [])}
                    ${renderLineup(lineupsPayload.awayTeam || awayTeamName, lineupsPayload.awayLineup || [])}
                `;
            });
            document.getElementById("view-stats").addEventListener("click", showStats);
            document.getElementById("view-replay").addEventListener("click", () => {
                window.location.href = `/realisticDemo.html?matchId=${encodeURIComponent(matchId)}&mode=replay`;
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
                        ? `<span class="cs-clickable" onclick="loadLeagueTeamPlayer(${scorerTeamId}, ${scorerStat.playerId}, '${escapeHtml(g.scoreTeam || '')}')">${g.scorer || "?"}</span>`
                        : (g.scorer || "?");
                    const assistLabel = assistStat?.playerId && scorerTeamId
                        ? `<span class="cs-clickable" onclick="loadLeagueTeamPlayer(${scorerTeamId}, ${assistStat.playerId}, '${escapeHtml(g.scoreTeam || '')}')">${g.assistant}</span>`
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
        mainContent.innerHTML = `
            <div class="fm-page fm-page--club">
                <section class="fm-panel fm-club-hero">
                    <button class="back-to-dashboard" data-nav-back="dashboard">Back</button>
                    <div class="fm-club-hero-main">
                        <div>
                            <div class="fm-eyebrow">Club support</div>
                            <h2>Medical Center</h2>
                            <p class="fm-subtle">Same club shell as First Team, while keeping our medical workflow area ready for injuries, rehab, and staff expansion.</p>
                        </div>
                        ${buildClubActionsHtml('medicalCenter')}
                    </div>
                    <div class="fm-medical-stat-grid team-summary-grid">
                        <div><strong>0</strong><span>Critical injuries</span></div>
                        <div><strong>0</strong><span>Rehab cases</span></div>
                        <div><strong>100%</strong><span>Squad availability</span></div>
                        <div><strong>Soon</strong><span>Treatment planner</span></div>
                    </div>
                </section>
                <section class="fm-panel fm-medical-panel is-standalone">
                    <div class="fm-medical-icon">&#10010; &#129658;</div>
                    <h3>Medical dashboard</h3>
                    <p class="fm-subtle" style="max-width:560px; text-align:center;">
                        Injury diagnosis, recovery plans, and medical staff management stay part of our club area and will be expanded here next.
                    </p>
                    <div class="fm-medical-stat-grid">
                        <div><strong>0</strong><span>Critical injuries</span></div>
                        <div><strong>0</strong><span>Rehab cases</span></div>
                        <div><strong>0</strong><span>Return-to-play checks</span></div>
                    </div>
                    <div class="fm-panel-action">Coming soon</div>
                </section>
            </div>`;
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
                            <h2>Formations</h2>
                            <p class="fm-subtle">Desktop uses drag & drop. Mobile uses filtered dropdowns with unique player lock.</p>
                        </div>
                        ${buildClubActionsHtml('formations')}
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
        const response = await authFetch(`/demo/teams/${currentUserTeamId}/profile`);
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
                        <img src="${profile.logo || '/images/omladinac.png'}"
                             class="club-logo"
                             alt="${escapeHtml(profile.name)}"
                             onerror="this.src='/images/omladinac.png'">
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
    async function loadFixture(fixtureId, options = {}) {
        const pushHistory = options.pushHistory !== false;
        if (pushHistory) pushNavState({ type: 'fixture', fixtureId });
        const mainContent = document.getElementById("main-content");
        if (!await ensureUserTeamId()) return;
        console.log(`Loading fixture ID: ${fixtureId}`);

        try {
            const response = await authFetch(`/demo/matches/teams/${currentUserTeamId}/fixtures/${fixtureId}`);
            console.log(`Response status: ${response.status}`);
        // Fixture to stadium image mapping (extend as needed)
            let stadiumImage = "/images/default-stadium.png"; // fallback
            if (fixtureId == 1) {
                stadiumImage = "/images/livadice.png";
            } else if (fixtureId == 2) {
                stadiumImage = "/images/dunjareal.png";
            } else if (fixtureId == 3) {
                stadiumImage = "/images/bilinopolje.png";
            }
            if (!response.ok) {
                const text = await response.text();
                console.error(`Error ${response.status}: ${text}`);
                mainContent.innerHTML = `
                    <div class="fm-page fm-page--club">
                        <section class="fm-panel fm-club-hero">
                            <button class="back-to-dashboard" data-nav-back="fixtures">Back</button>
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

            const fixture = await response.json();

            const homeTeamName = fixture.homeTeam || "Home";
            const awayTeamName = fixture.awayTeam || "Away";
            const matchDate = fixture.matchDate || 'N/A';
            const matchTime = fixture.matchTime || 'TBD';
            const matchDateTime = `${matchDate}${matchTime ? ` • ${matchTime}` : ''}`;
            const venue = fixture.stadiumName || "N/A";

            mainContent.innerHTML = `
            <div class="fm-page fm-page--club">
                <section class="fm-panel fm-club-hero">
                    <button class="back-to-dashboard" data-nav-back="fixtures">Back</button>
                    <div class="fm-club-hero-main">
                        <div>
                            <div class="fm-eyebrow">Club schedule</div>
                            <h2>Upcoming Fixture</h2>
                            <p class="fm-subtle">Detailed fixture view now stays inside the same Club shell instead of falling back to a back-only page.</p>
                        </div>
                        ${buildClubActionsHtml('schedule')}
                    </div>
                    <div class="fm-medical-stat-grid team-summary-grid">
                        <div><strong>${escapeHtml(homeTeamName)}</strong><span>Home</span></div>
                        <div><strong>${escapeHtml(awayTeamName)}</strong><span>Away</span></div>
                        <div><strong>${escapeHtml(matchDate)}</strong><span>Date</span></div>
                        <div><strong>${escapeHtml(matchTime)}</strong><span>Kick-off</span></div>
                    </div>
                </section>
                <div class="fm-grid-top fm-grid-top--club-profile">
                    <section class="fm-panel club-profile-brand-card">
                        <div class="club-profile-brand-mark">
                            <img src="${stadiumImage}" class="club-logo" alt="${escapeHtml(venue)}" onerror="this.src='/images/default-stadium.png'">
                        </div>
                        <h3>${escapeHtml(venue)}</h3>
                        <p class="fm-subtle">Venue preview for the next scheduled match.</p>
                        <button type="button" class="fm-action-btn secondary fixture-stadium-btn" data-stadium-image="${escapeHtml(stadiumImage)}" data-stadium-name="${escapeHtml(venue)}">Open Stadium View</button>
                    </section>
                    <section class="fm-panel club-profile-detail-card">
                        <div class="fm-panel-head">
                            <div>
                                <h3>Fixture details</h3>
                                <p class="fm-subtle">Full preview, lineups, and deeper statistics can continue to plug into this card later.</p>
                            </div>
                            <span class="fm-panel-action">Preview</span>
                        </div>
                        <div class="club-profile-detail-list">
                            <div class="club-profile-detail-row"><span>Home team</span><strong>${escapeHtml(homeTeamName)}</strong></div>
                            <div class="club-profile-detail-row"><span>Away team</span><strong>${escapeHtml(awayTeamName)}</strong></div>
                            <div class="club-profile-detail-row"><span>Date & time</span><strong>${escapeHtml(matchDateTime)}</strong></div>
                            <div class="club-profile-detail-row"><span>Venue</span><strong>${escapeHtml(venue)}</strong></div>
                            <div class="club-profile-detail-row"><span>Status</span><strong>Scheduled</strong></div>
                        </div>
                    </section>
                </div>
            </div>`;

            const stadiumButton = mainContent.querySelector('.fixture-stadium-btn');
            if (stadiumButton) {
                stadiumButton.addEventListener('click', () => {
                    showStadiumModal(stadiumButton.dataset.stadiumImage, stadiumButton.dataset.stadiumName);
                });
            }
        } catch (err) {
            console.error("Error loading fixture:", err);
            mainContent.innerHTML = `
                <div class="fm-page fm-page--club">
                    <section class="fm-panel fm-club-hero">
                        <button class="back-to-dashboard" data-nav-back="fixtures">Back</button>
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
        const leagueId = 1;
        try {
            const seasonsResponse = await authFetch(`/countries/leagues/${leagueId}/seasons`);
            const seasons = seasonsResponse.ok ? await seasonsResponse.json() : [];
            const selectedSeason = seasonYear || seasons[seasons.length - 1]?.seasonYear || null;
            const selectedSeasonNumber = seasons.find(s => s.seasonYear === selectedSeason)?.seasonNumber
                || (selectedSeason ? Math.max(1, selectedSeason - 2025 + 1) : 1);
            const seasonParam = selectedSeason ? `?seasonYear=${selectedSeason}` : "";

            const [tableResponse, teamsResponse, scheduleResponse, scorersResponse, assistsResponse] = await Promise.all([
                authFetch(`/countries/leagues/${leagueId}/table${seasonParam}`),
                authFetch(`/countries/leagues/${leagueId}/teams`),
                authFetch(`/countries/leagues/${leagueId}/schedule${seasonParam}`),
                authFetch(`/stats/leagues/${leagueId}/topscorers${seasonParam}`),
                authFetch(`/stats/leagues/${leagueId}/topassists${seasonParam}`)
            ]);
            if (!tableResponse.ok) throw new Error(`League table load failed: ${tableResponse.status}`);
            if (!teamsResponse.ok) throw new Error(`League teams load failed: ${teamsResponse.status}`);

            const table = await tableResponse.json();
            const leagueTeams = await teamsResponse.json();
            const schedule = scheduleResponse.ok ? await scheduleResponse.json() : [];
            const scorers = scorersResponse.ok ? await scorersResponse.json() : [];
            const assists = assistsResponse.ok ? await assistsResponse.json() : [];
            const teamIdByName = new Map();
            leagueTeams.forEach(team => {
                teamIdByName.set(normalizeTeamKey(team.name), team.id);
            });

            const enhancedTable = table.map(row => ({
                ...row,
                teamId: teamIdByName.get(normalizeTeamKey(row.name)) ?? null
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
            await Promise.all(leagueTeams.map(async team => {
                try {
                    const response = await authFetch(`/countries/teams/${team.id}/players`);
                    if (!response.ok) return;
                    const players = await response.json();
                    players.forEach(player => {
                        playerIdByKey.set(
                            `${normalizeTeamKey(team.name)}|${normalizePlayerKey(player.name)}`,
                            player.id
                        );
                    });
                } catch (e) {
                    console.warn('League player map fetch failed for team:', team?.name, e);
                }
            }));

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
                table: enhancedTable,
                fixtures: visibleRounds,
                topScorers: mappedScorers,
                topAssists: mappedAssists,
                seasons,
                selectedSeason,
                selectedSeasonNumber
            });
        } catch (err) {
            console.error("Failed to load league table:", err);
            document.getElementById("main-content").innerHTML = `
                <div class="manager-card">
                    <button class="back-to-dashboard" data-nav-back="dashboard">Back</button>
                    <h2>Error</h2>
                    <p>Could not load league table.</p>
                </div>`;
        }
    }
    async function loadLeagueMatches(seasonYear = null) {
        const leagueId = 1; // Superliga, can be parameterized later
        try {
            console.log(`Loading league matches...`);
            const seasonParam = seasonYear ? `?seasonYear=${seasonYear}` : "";
            const response = await authFetch(`/countries/leagues/${leagueId}/matches${seasonParam}`);
            console.log(`Response status: ${response.status}`);
            if (!response.ok) throw new Error("Failed to load league matches");
            const matches = await response.json();
            const results = matches.sort((a, b) => new Date(b.matchDate) - new Date(a.matchDate));
            const seasonNumber = seasonYear ? Math.max(1, seasonYear - 2025 + 1) : null;
            renderLeagueMatches(results, seasonNumber ? `League Results - Season ${seasonNumber}` : "League Results");
        } catch (err) {
            console.error(err);
            document.getElementById("main-content").innerHTML = `
                <div class="manager-card">
                    <button data-nav-back="dashboard">Back</button>
                    <h2>Error</h2>
                    <p>Could not load league matches.</p>
                </div>`;
        }
    }
    async function loadLeagueSchedule(seasonYear = null) {
        const mainContent = document.getElementById("main-content");
        const leagueId = 1;
        try {
            const seasonsResponse = await authFetch(`/countries/leagues/${leagueId}/seasons`);
            const seasons = seasonsResponse.ok ? await seasonsResponse.json() : [];
            const selectedSeason = seasonYear || seasons[seasons.length - 1]?.seasonYear || null;
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
                byRound.get(round).push(m);
                if (!m.played && round < currentRound) currentRound = round;
            });
            const rounds = [...byRound.keys()].sort((a, b) => a - b);
            const firstUnplayed = rounds.find(r => byRound.get(r).some(m => !m.played));
            if (firstUnplayed) currentRound = firstUnplayed;

            let html = `
            <div class="manager-card">
                <button class="back-to-dashboard" data-nav-back="dashboard">Back</button>
                <h2>League Schedule ${selectedSeason ? `- Season ${selectedSeasonNumber}` : ""}</h2>
                ${seasons.length ? `
                <div style="margin:8px 0 14px;">
                    <label for="season-select">Season:</label>
                    <select id="season-select" style="margin-left:8px;">
                        ${seasons.map(s => `<option value="${s.seasonYear}" ${s.seasonYear === selectedSeason ? "selected" : ""}>Season ${s.seasonNumber}</option>`).join("")}
                    </select>
                </div>` : ""}
                <div id="schedule-rounds">`;

            rounds.forEach(round => {
                const matches = byRound.get(round) || [];
                const currentTag = round === currentRound ? `<span style="color:#4caf50; font-size:0.9em;">(Current)</span>` : "";
                html += `<div id="round-${round}" style="margin:14px 0 18px;"><h3 style="margin-bottom:8px;">Round ${round} ${currentTag}</h3>`;
                matches.forEach(match => {
                    const score = match.played ? `${match.homeGoals} : ${match.awayGoals}` : "vs";
                    const playedClass = match.played ? "" : "opacity:0.86; cursor:default;";
                    const homeEsc = String(match.homeTeam || "").replace(/'/g, "\\'");
                    const awayEsc = String(match.awayTeam || "").replace(/'/g, "\\'");
                    const matchIdAttr = match.played && match.id ? `data-match-id="${match.id}"` : "";
                    html += `
                        <div class="match-row" style="${playedClass}" ${matchIdAttr} data-caller="leagueMatches">
                            <div style="font-size:0.88em; color:#aaa;">${match.matchDate || "N/A"}</div>
                            <div class="match-teams">
                                <span class="team-home"><span class="cs-clickable" onclick="event.stopPropagation(); openTeamByName('${homeEsc}')">${escapeHtml(match.homeTeam)}</span></span>
                                <span class="score">${score}</span>
                                <span class="team-away"><span class="cs-clickable" onclick="event.stopPropagation(); openTeamByName('${awayEsc}')">${escapeHtml(match.awayTeam)}</span></span>
                            </div>
                        </div>`;
                });
                html += `</div>`;
            });

            html += `</div></div>`;
            mainContent.innerHTML = html;
            mainContent.querySelectorAll('.match-row[data-match-id]').forEach(row => {
                row.addEventListener('click', () => {
                    const matchId = row.getAttribute('data-match-id');
                    if (matchId) loadMatch(matchId, 'leagueMatches');
                });
            });
            const seasonSelect = document.getElementById('season-select');
            if (seasonSelect) {
                seasonSelect.addEventListener('change', () => loadLeagueSchedule(Number(seasonSelect.value)));
            }

            setTimeout(() => {
                const currentEl = document.getElementById(`round-${currentRound}`);
                if (currentEl) currentEl.scrollIntoView({ behavior: "smooth", block: "start" });
            }, 20);
        } catch (err) {
            console.error("Failed to load league schedule:", err);
            mainContent.innerHTML = `
                <div class="manager-card">
                    <button class="back-to-dashboard" data-nav-back="dashboard">Back</button>
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
            const leagueId = 1;
            const [scorersRes, assistsRes, leagueTeamsRes] = await Promise.all([
                authFetch(`/stats/leagues/${leagueId}/topscorers`),
                authFetch(`/stats/leagues/${leagueId}/topassists`),
                authFetch(`/countries/leagues/${leagueId}/teams`)
            ]);
            console.log(`Response status: ${scorersRes.status}`);
            console.log(`Response status: ${assistsRes.status}`);
            const scorers = await scorersRes.json();
            const assists = await assistsRes.json();
            const leagueTeams = leagueTeamsRes.ok ? await leagueTeamsRes.json() : [];
            const teamIdByName = new Map();
            leagueTeams.forEach(t => teamIdByName.set(t.name, t.id));
            const playerIdByKey = new Map();
            await Promise.all(leagueTeams.map(async team => {
                try {
                    const r = await authFetch(`/countries/teams/${team.id}/players`);
                    if (!r.ok) return;
                    const players = await r.json();
                    players.forEach(p => playerIdByKey.set(`${team.name}|${p.name}`, p.id));
                } catch (e) {}
            }));

            const mainContent = document.getElementById("main-content");

            let html = `
            <div class="manager-card" style="padding: 25px;">
                <button class="back-to-dashboard" data-nav-back="dashboard">Back</button>
                <h2 style="text-align: center; margin: 20px 0 30px; color: #e94560;">League Stats - Top Lists</h2>

                <div class="top-lists" style="display: flex; gap: 40px; justify-content: center; flex-wrap: wrap;">

                    ${mode !== "assists" ? `
                    <div class="top-scorers" style="min-width: 340px; flex: 1;">
                        <h3 style="text-align: center; color: #ffd700; margin-bottom: 15px;">Top Scorers</h3>
                        <ul style="list-style: none; padding: 0; margin: 0;">` : ""}`;

            if (mode !== "assists") {
                scorers.forEach((s, i) => {
                    const rankColor = i < 3 ? '#ffd700' : '#aaa';
                    const bgColor = i % 2 === 0 ? 'rgba(255,255,255,0.05)' : 'rgba(0,0,0,0.1)';
                    html += `
                    <li style="padding: 12px 15px; background: ${bgColor}; border-radius: 8px; margin: 6px 0;
                               transition: all 0.2s; display: flex; justify-content: space-between; align-items: center;">
                        <span style="color: ${rankColor}; font-weight: bold; min-width: 30px;">${i+1}.</span>
                        <span style="flex: 1; text-align: left; padding-left: 10px;">
                            ${playerIdByKey.get(`${s.teamName}|${s.playerName}`) && teamIdByName.get(s.teamName)
                                ? `<span class="cs-clickable" onclick="loadLeagueTeamPlayer(${playerIdByKey.get(`${s.teamName}|${s.playerName}`)}, ${teamIdByName.get(s.teamName)}, '${escapeHtml(s.teamName)}')">${s.playerName}</span>`
                                : s.playerName}
                            <small style="color: #888;">(${teamIdByName.get(s.teamName) ? `<span class="cs-clickable" onclick="loadLeagueTeam(${teamIdByName.get(s.teamName)}, '${escapeHtml(s.teamName)}')">${s.teamName}</span>` : s.teamName})</small>
                        </span>
                        <span style="font-weight: bold; color: #ff7582; min-width: 60px; text-align: right;">
                            ${s.goals} goals
                        </span>
                    </li>`;
                });
            }

            if (mode !== "assists") {
                html += `</ul></div>`;
            }

            if (mode !== "scorers") {
                html += `<div class="top-assists" style="min-width: 340px; flex: 1;">
                        <h3 style="text-align: center; color: #9d4edd; margin-bottom: 15px;">Top Assists</h3>
                        <ul style="list-style: none; padding: 0; margin: 0;">`;
            }

            if (mode !== "scorers") {
                assists.forEach((a, i) => {
                    const rankColor = i < 3 ? '#9d4edd' : '#aaa';
                    const bgColor = i % 2 === 0 ? 'rgba(255,255,255,0.05)' : 'rgba(0,0,0,0.1)';
                    html += `
                    <li style="padding: 12px 15px; background: ${bgColor}; border-radius: 8px; margin: 6px 0;
                               transition: all 0.2s; display: flex; justify-content: space-between; align-items: center;">
                        <span style="color: ${rankColor}; font-weight: bold; min-width: 30px;">${i+1}.</span>
                        <span style="flex: 1; text-align: left; padding-left: 10px;">
                            ${playerIdByKey.get(`${a.teamName}|${a.playerName}`) && teamIdByName.get(a.teamName)
                                ? `<span class="cs-clickable" onclick="loadLeagueTeamPlayer(${playerIdByKey.get(`${a.teamName}|${a.playerName}`)}, ${teamIdByName.get(a.teamName)}, '${escapeHtml(a.teamName)}')">${a.playerName}</span>`
                                : a.playerName}
                            <small style="color: #888;">(${teamIdByName.get(a.teamName) ? `<span class="cs-clickable" onclick="loadLeagueTeam(${teamIdByName.get(a.teamName)}, '${escapeHtml(a.teamName)}')">${a.teamName}</span>` : a.teamName})</small>
                        </span>
                        <span style="font-weight: bold; color: #4fc3f7; min-width: 60px; text-align: right;">
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
    function renderPlayers(players, title) {
        renderPlayersView(players, title, { loadPlayer, getImageFilename });
    }
        function renderMatches(matches, title, options = {}) {
        renderMatchesView(matches, title, { loadMatch, ...options });
    }
    function renderTable(table) {
        renderTableView(table, { loadLeagueTeam, loadLeagueTeamPlayer, loadLeagueTable, loadMatch, escapeHtml, formatGoalDiff });
    }
    async function loadLeagueTeam(teamId, teamName, options = {}) {
        const pushHistory = options.pushHistory !== false;
        if (pushHistory) pushNavState({ type: 'leagueTeam', teamId, teamName });
        const mainContent = document.getElementById("main-content");
        try {
            const [response, transferOverview] = await Promise.all([
                authFetch(`/teams/${teamId}/players`),
                (async () => {
                    try {
                        const transferResponse = await authFetch(`/transfers/team/${teamId}?viewerTeamId=${encodeURIComponent(currentUserTeamId)}`);
                        return await transferResponse.json();
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
                    <button class="back-to-dashboard" onclick="loadLeagueTable()">Back</button>
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
                loadLeagueTeamPlayer(playerId, playerTeamId, playerTeamName);
            });
            mainContent.querySelectorAll('[data-team-transfer-player-id]').forEach(button => {
                button.addEventListener('click', () => {
                    loadLeagueTeamPlayer(
                        Number(button.dataset.teamTransferPlayerId || 0),
                        Number(button.dataset.teamTransferTeamId || teamId),
                        button.dataset.teamTransferTeamName || teamName || 'Team'
                    );
                });
            });
        } catch (err) {
            console.error("Failed to load team details:", err);
            mainContent.innerHTML = `
            <div class="manager-card">
                <button class="back-to-dashboard" onclick="loadLeagueTable()">Back</button>
                <h2>Error</h2>
                <p>Could not load team details.</p>
            </div>`;
        }
    }
    async function loadLeagueTeamPlayer(playerId, teamId, teamName, options = {}) {
        const pushHistory = options.pushHistory !== false;
        if (pushHistory) pushNavState({ type: 'leagueTeamPlayer', playerId, teamId, teamName });
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
                    reloadCurrent: () => loadLeagueTeamPlayer(playerId, teamId, teamName, { pushHistory: false }),
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
    function renderLeagueMatches(matches, title = "League Results") {
        renderLeagueMatchesView(matches, title, { loadMatch });
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






























