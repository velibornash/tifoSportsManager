// pages/views/player-view.js
import {
    htmlEscape, getImageFilename, formatBudget, formatDateTimeLabel, formatFormBadge,
    formatRatingBadge, formatPlayerSkill, getPlayerConditionPercent, getPlayerPositionInfo,
    formatTransferMoney, getTransferInterestedTeams, getPendingJuniorReveal,
    fetchPlayerRatingSummary, buildEmptyState, delay
} from './utils.js';

export function createPlayerView(deps) {
    const { authFetch, getTeamId, goBackSmart } = deps;

    let _callerPage = 'firstTeam';

    function setCallerPage(page) {
        _callerPage = page;
    }

    // --- Transfer helpers ---

    async function fetchPlayerTransferStatus(playerId) {
        try {
            const teamId = getTeamId();
            if (!teamId) return null;
            const response = await authFetch(`/transfers/player/${playerId}?viewerTeamId=${encodeURIComponent(teamId)}`);
            return await response.json();
        } catch (err) {
            console.warn('Failed to load player transfer status:', err);
            return null;
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
        try { return await response.json(); } catch { return null; }
    }

    // --- HTML builders ---

    function buildPlayerProfileHeroHtml(player, options = {}) {
        const {
            backLabel = 'Back', eyebrow = 'Player overview', teamName = 'Club squad',
            ratingSummary = {}, backButtonId = 'player-back-button',
            backButtonAttributes = '', showBackButton = true,
            headerClassName = 'fm-player-header', bannerClassName = ''
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
                            <img src="/images/${filename}.jpg" onerror="this.src='/images/player.jpg'" alt="${htmlEscape(player?.name || 'Player')}">
                        </div>
                    </div>
                    <div class="fm-ph-identity">
                        <div class="fm-eyebrow">${htmlEscape(eyebrow)}</div>
                        <h2>${htmlEscape(player?.name || 'Player')}</h2>
                        <div class="fm-ph-meta-row">
                            <span>${htmlEscape(teamName)}</span>
                            <span class="fm-ph-sep"></span>
                            <span>${htmlEscape(positionInfo.raw || 'Player')}</span>
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
                                    <span class="fm-detail-value">${htmlEscape(injuryText)}</span>
                                </div>
                                <div class="fm-ph-rating-item">
                                    <span class="fm-ph-rlabel">Output</span>
                                    <span class="fm-detail-value">${outputGoals} goals · ${outputAssists} assists</span>
                                </div>
                            </div>
                            <div class="fm-ph-id-col">
                                <div class="fm-player-chip-row">
                                    <span class="fm-player-chip">OVR ${player?.overall ?? '-'}</span>
                                    <span class="fm-player-chip secondary">${htmlEscape(positionInfo.primary)}</span>
                                    <span class="fm-player-chip secondary">${matchesPlayed} matches</span>
                                </div>
                            </div>
                        </div>
                    </div>
                </div>
            </section>`;
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
                        <p class="fm-subtle">${htmlEscape(transferStatus.summary || 'Current transfer status is shown here.')}</p>
                    </div>
                    <span class="fm-panel-action">${htmlEscape(transferStatus.status || (transferStatus.listed ? 'LISTED' : 'NONE'))}</span>
                </div>
                <div class="club-profile-detail-list">
                    <div class="club-profile-detail-row"><span>Current club</span><strong>${htmlEscape(transferStatus.currentTeamName || 'Unassigned')}</strong></div>
                    <div class="club-profile-detail-row"><span>Seller club</span><strong>${htmlEscape(transferStatus.sellerTeamName || transferStatus.currentTeamName || '—')}</strong></div>
                    <div class="club-profile-detail-row"><span>Asking price</span><strong>${formatTransferMoney(transferStatus.askingPrice)}</strong></div>
                    <div class="club-profile-detail-row"><span>Agreed fee</span><strong>${formatTransferMoney(transferStatus.agreedPrice)}</strong></div>
                    <div class="club-profile-detail-row"><span>Listed at</span><strong>${htmlEscape(formatDateTimeLabel(transferStatus.listedAt))}</strong></div>
                    <div class="club-profile-detail-row"><span>Completed at</span><strong>${htmlEscape(formatDateTimeLabel(transferStatus.completedAt))}</strong></div>
                </div>
                <div class="fm-empty" style="text-align:left; margin-top:16px;">
                    ${interestedTeams.length
                        ? `Interested clubs: ${htmlEscape(interestedTeams.join(', '))}`
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
                        <p class="fm-subtle">Quick view of ${htmlEscape(teamName)} transfer activity and listed players.</p>
                    </div>
                    <span class="fm-panel-action">Transfers</span>
                </div>
                <div class="fm-medical-stat-grid team-summary-grid" style="margin-bottom:18px;">
                    <div><strong>${formatTransferMoney(overview.budget)}</strong><span>Budget</span></div>
                    <div><strong>${listedPlayers.length}</strong><span>Listed</span></div>
                    <div><strong>${listedPlayers.reduce((sum, item) => sum + getTransferInterestedTeams(item).length, 0)}</strong><span>Interest</span></div>
                    <div><strong>${listedPlayers.length ? htmlEscape(listedPlayers[0].playerName || '—') : '—'}</strong><span>Top listing</span></div>
                </div>
                ${listedPlayers.length === 0
                    ? `<div class="fm-empty">${isUserTeam ? 'No players from your club are currently listed.' : 'No active transfer listings for this team right now.'}</div>`
                    : `<div class="fm-squad-wrap">
                        <table class="fm-squad">
                            <thead><tr><th class="sq-name">Player</th><th>Pos</th><th>Asking</th><th>Interest</th><th>Listed</th><th>Action</th></tr></thead>
                            <tbody>
                                ${listedPlayers.map(item => `
                                    <tr class="fm-squad-row">
                                        <td class="sq-name">${htmlEscape(item.playerName || 'Unknown')}</td>
                                        <td>${htmlEscape(item.position || '-')}</td>
                                        <td>${formatTransferMoney(item.askingPrice)}</td>
                                        <td>${htmlEscape(getTransferInterestedTeams(item).length ? getTransferInterestedTeams(item).join(', ') : 'No interest yet')}</td>
                                        <td>${htmlEscape(formatDateTimeLabel(item.listedAt))}</td>
                                        <td><button type="button" class="fm-action-btn secondary" data-team-transfer-player-id="${item.playerId}" data-team-transfer-team-id="${overview.teamId}" data-team-transfer-team-name="${htmlEscape(overview.teamName || teamName)}">Open player</button></td>
                                    </tr>`).join('')}
                            </tbody>
                        </table>
                    </div>`}
                ${isUserTeam ? `<div style="margin-top:16px;"><button type="button" class="fm-action-btn" onclick="loadPage('transfers')">Open Transfer Centre</button></div>` : ''}
            </section>`;
    }

    function buildPlayerProfileHtml(player, options = {}) {
        const {
            backLabel = 'Back', eyebrow = 'Player overview', teamName = 'Club squad',
            ratingSummary = {}, transferStatus = null, revealPayload = null,
            placeholderPrefix = 'This tab is prepared', showReveal = false
        } = options;
        const revealActive = showReveal && !!revealPayload;
        const positionInfo = getPlayerPositionInfo(player.position);
        const conditionPercent = getPlayerConditionPercent(player);
        const averageRating = formatRatingBadge(ratingSummary.averageRating10);
        const formBadge2 = formatFormBadge(player.form);
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
                                <span class="fm-detail-value">${htmlEscape(positionInfo.raw || 'Player')}</span>
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
                                        <td>${formBadge2}</td>
                                        <td>${htmlEscape(injuryText)}</td>
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

    async function handlePlayerTransferAction(action, button, options = {}) {
        const teamId = getTeamId();
        if (!teamId) return;
        const { playerId, reloadCurrent, reloadOwned } = options;
        const resolvedPlayerId = Number(button?.dataset?.playerId || playerId || 0);
        if (!resolvedPlayerId) return;

        try {
            switch (action) {
                case 'list': {
                    const price = promptTransferActionPrice('Set asking price for this player:', button?.dataset?.defaultPrice || 1);
                    if (price == null) return;
                    await performTransferJsonAction(`/transfers/list/${resolvedPlayerId}`, { teamId, price });
                    await reloadCurrent?.();
                    return;
                }
                case 'remove': {
                    if (!window.confirm('Remove this player from the transfer list?')) return;
                    await performTransferJsonAction(`/transfers/remove/${resolvedPlayerId}?teamId=${encodeURIComponent(teamId)}`, null, 'DELETE');
                    await reloadCurrent?.();
                    return;
                }
                case 'interest': {
                    const params = new URLSearchParams({ teamId: String(teamId) });
                    await performTransferJsonAction(`/transfers/interest/${resolvedPlayerId}?${params.toString()}`);
                    await reloadCurrent?.();
                    return;
                }
                case 'buy-listed': {
                    const price = promptTransferActionPrice('Enter agreed fee for this listed player:', button?.dataset?.defaultPrice || 1);
                    if (price == null) return;
                    await performTransferJsonAction(`/transfers/buy/${resolvedPlayerId}`, { teamId, price });
                    await (reloadOwned || reloadCurrent)?.();
                    return;
                }
                case 'direct-buy': {
                    const price = promptTransferActionPrice('Enter transfer offer fee for this player:', button?.dataset?.defaultPrice || 1);
                    if (price == null) return;
                    const result = await performTransferJsonAction(`/transfers/direct-buy/${resolvedPlayerId}`, { teamId, price });
                    if (result?.actionMessage) window.alert(result.actionMessage);
                    await (reloadOwned || reloadCurrent)?.();
                    return;
                }
                case 'accept-offer': {
                    const result = await performTransferJsonAction(`/transfers/accept-offer/${resolvedPlayerId}`, { teamId });
                    if (result?.actionMessage) window.alert(result.actionMessage);
                    await (reloadOwned || reloadCurrent)?.();
                    return;
                }
                case 'reject-offers': {
                    if (!window.confirm('Reject all incoming offers for this player?')) return;
                    const result = await performTransferJsonAction(`/transfers/reject-offers/${resolvedPlayerId}`, { teamId });
                    if (result?.actionMessage) window.alert(result.actionMessage);
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

    async function runJuniorRevealAnimation(payload) {
        if (!payload) return;
        const allocated = payload.allocatedSkills || {};
        const sequence = Array.isArray(payload.allocationSequence) ? payload.allocationSequence : [];
        const remainingEl = document.getElementById("junior-reveal-remaining");
        const statusEl = document.getElementById("junior-reveal-status");
        const idByKey = {
            stamina: "skill-stamina-val", goalkeeper: "skill-goalkeeper-val",
            defending: "skill-defending-val", pace: "skill-pace-val",
            technique: "skill-technique-val", playmaker: "skill-playmaker-val",
            passing: "skill-passing-val", shooting: "skill-shooting-val"
        };
        const current = { stamina: 0, goalkeeper: 0, defending: 0, pace: 0, technique: 0, playmaker: 0, passing: 0, shooting: 0 };
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

    // --- Main loadPlayer ---
    async function loadPlayer(playerId, callerPage = _callerPage, options = {}) {
        const pushHistory = options.pushHistory !== false;
        const teamId = getTeamId();
        if (!teamId) return;
        _callerPage = callerPage;

        const depsNav = deps.getNavigationDeps?.();
        if (pushHistory && depsNav?.pushNavState) {
            depsNav.pushNavState({ type: 'player', playerId, callerPage });
        }

        const mainContent = document.getElementById("main-content");
        console.log(`Loading player for team ${teamId} and player ${playerId}`);
        const [response, ratingSummary, transferStatus] = await Promise.all([
            authFetch(`/teams/${teamId}/players/${playerId}`),
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
            juniors: "Back", trainingReports: "Back", trainingSetup: "Back",
            firstTeam: "Back", leagueTable: "Back", leagueMatches: "Back",
            results: "Back", fixtures: "Back"
        };
        const backLabel = backMap[callerPage] || "Back";
        const backTarget = callerPage || "firstTeam";
        const teamLabel = callerPage === 'juniors' ? 'Junior Squad' : 'First Team';
        mainContent.innerHTML = buildPlayerProfileHtml(player, {
            backLabel, eyebrow: 'Player overview', teamName: teamLabel,
            ratingSummary, transferStatus, revealPayload, showReveal: true,
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

    return {
        loadPlayer,
        setCallerPage,
        buildPlayerProfileHeroHtml,
        buildPlayerProfileHtml,
        initPlayerProfilePage,
        buildPlayerTransferPanelHtml,
        buildTeamTransferOverviewHtml,
        fetchPlayerTransferStatus,
        handlePlayerTransferAction,
        runJuniorRevealAnimation,
        fetchPlayerRatingSummary,
    };
}
