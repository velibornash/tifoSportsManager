import { createStaffDirectoryFeature } from './staff-directory.js';
import { renderOfferButtons, renderOfferTerms } from '../views/transfer-offer-actions.js';
import { readJsonOr } from '../views/utils.js';

export function createClubManagementFeature(deps) {
    const {
        authFetch,
        getTeamId,
        getTeamName,
        escapeHtml,
        buildClubActionsHtml,
        formatBudget,
        formatDateTimeLabel,
        loadPlayer,
        loadLeagueTeamPlayer
    } = deps;
    const staffDirectoryFeature = createStaffDirectoryFeature({ authFetch, getTeamId, escapeHtml, buildClubActionsHtml, formatBudget });

    async function loadStaff() {
        return staffDirectoryFeature.loadStaff();
    }

    /**
     * Club finances, from the server.
     *
     * This used to invent the entire page in the browser: three fictional sponsors, a monthly
     * income of `budget * 0.055`, a wage budget of `squadSize * 1850`, and six months of history
     * that had never been played. None of it existed in the database, so the screen looked
     * identical for a solvent club and a bankrupt one. Everything now comes from the ledger.
     */
    async function loadFinances() {
        const teamId = getTeamId();
        const mainContent = document.getElementById('main-content');

        // Season is a real selector, not a fixed "this year": a manager reviewing last season is
        // the main thing the page exists for, and the ledger is kept per season.
        const requestedSeason = (() => {
            const fromUrl = new URLSearchParams(window.location.search).get('financesSeason');
            if (fromUrl) return Number(fromUrl);
            const stored = sessionStorage.getItem('financesSeason');
            return stored ? Number(stored) : null;
        })();
        const seasonQuery = requestedSeason ? `?seasonYear=${requestedSeason}` : '';

        const [profileRes, financesRes, historyRes, boardRes, playersRes] = await Promise.all([
            authFetch(`/teams/${teamId}/profile`),
            authFetch(`/api/teams/${teamId}/finances${seasonQuery}`),
            authFetch(`/api/teams/${teamId}/finances/history${seasonQuery}`),
            authFetch(`/api/teams/${teamId}/finances/board`),
            authFetch(`/teams/${teamId}/players`)
        ]);

        const profile = profileRes.ok ? await profileRes.json() : {};
        const finances = financesRes.ok ? await financesRes.json() : null;
        const history = historyRes.ok ? await historyRes.json() : [];
        const board = boardRes.ok ? await boardRes.json() : null;
        const players = playersRes.ok ? await playersRes.json() : [];

        // The API is the source of truth. If it is unavailable we say so rather than inventing
        // plausible numbers, because a wrong number here is worse than no number.
        if (!finances) {
            mainContent.innerHTML = `
                <div class="fm-page fm-page--club">
                    <section class="fm-panel">
                        <button class="back-to-dashboard" data-nav-back="dashboard">Back</button>
                        <div class="fm-eyebrow">Club finances</div>
                        <h2>${escapeHtml(profile.name || 'Finances')}</h2>
                        <p class="fm-subtle">The club ledger is not available right now. Figures are
                        never estimated — when the accounts cannot be read, this screen says so.</p>
                    </section>
                </div>`;
            return;
        }

        const squadValue = Number(finances.squadValue || 0);
        const squadSize = Number(finances.squadSize || players.length);
        const budget = Number(finances.budget || 0);
        const wageBill = Number(finances.squadWageBill || 0);
        const incomeToDate = Number(finances.incomeToDate || 0);
        const costsToDate = Number(finances.costsToDate || 0);
        const netToDate = Number(finances.netToDate || 0);
        const topAsset = [...players].sort((a, b) => Number(b.value || 0) - Number(a.value || 0))[0] || null;
        const averageValue = squadSize ? squadValue / squadSize : 0;

        const maxHistoryValue = Math.max(1, ...history.flatMap(h =>
            [Math.abs(Number(h.balance || 0)), Number(h.income || 0), Number(h.expenses || 0)]));

        const unsettled = finances.settled === false;
        const seasons = finances.seasons || [];
        const ffp = board && board.ffpRatio != null ? Number(board.ffpRatio) : null;

        mainContent.innerHTML = `
            <div class="fm-page fm-page--club">
                <section class="fm-panel fm-club-hero">
                    <button class="back-to-dashboard" data-nav-back="dashboard">Back</button>
                    <div class="fm-club-hero-main">
                        <div>
                            <div class="fm-eyebrow">Club finances</div>
                            <h2>${escapeHtml(finances.teamName || profile.name || 'Finances')}</h2>
                            <p class="fm-subtle">Season ${escapeHtml(String(finances.seasonYear))} ·
                            ${escapeHtml(String(finances.ledgerLines))} settled ledger lines.</p>
                        </div>
                        ${buildClubActionsHtml('finances')}
                    </div>
                    <div class="fm-medical-stat-grid team-summary-grid">
                        <div><strong>${escapeHtml(formatBudget(budget))}</strong><span>Budget</span></div>
                        <div><strong>${escapeHtml(formatBudget(squadValue))}</strong><span>Squad value</span></div>
                        <div><strong>${escapeHtml(formatBudget(wageBill))}</strong><span>Weekly wage bill</span></div>
                        <div><strong>${escapeHtml(topAsset?.name || '—')}</strong><span>Top asset</span></div>
                    </div>
                    ${seasons.length > 1 ? `
                        <div class="fm-panel-action" style="margin-top:12px;">
                            <label for="finances-season" class="fm-subtle">Season</label>
                            <select id="finances-season" data-finances-season>
                                ${seasons.map(y => `<option value="${y}" ${Number(finances.seasonYear) === Number(y) ? 'selected' : ''}>${y}</option>`).join('')}
                            </select>
                        </div>` : ''}
                    ${unsettled ? `<p class="fm-subtle">${escapeHtml(finances.notice || '')}</p>` : ''}
                </section>

                <section class="finance-flow-grid">
                    <div class="finance-flow-card is-income">
                        <div class="finance-flow-title">Income to date</div>
                        <strong>${escapeHtml(formatBudget(incomeToDate))}</strong>
                        <span>All settled weeks, this season</span>
                    </div>
                    <div class="finance-flow-card is-expense">
                        <div class="finance-flow-title">Costs to date</div>
                        <strong>${escapeHtml(formatBudget(costsToDate))}</strong>
                        <span>All settled weeks, this season</span>
                    </div>
                    <div class="finance-flow-card ${netToDate >= 0 ? 'is-balance' : 'is-expense'}">
                        <div class="finance-flow-title">Net to date</div>
                        <strong>${escapeHtml(formatBudget(netToDate))}</strong>
                        <span>${netToDate >= 0 ? 'In the black' : 'Spending more than it earns'}</span>
                    </div>
                    <div class="finance-flow-card ${ffp != null && ffp > 1.15 ? 'is-expense' : 'is-balance'}">
                        <div class="finance-flow-title">Wages vs income</div>
                        <strong>${ffp != null ? escapeHtml(ffp.toFixed(2)) + '×' : '—'}</strong>
                        <span>${ffp == null ? 'No settled income yet'
                            : ffp > 1.35 ? 'The board will not fund this'
                            : ffp > 1.15 ? 'Wages close to income'
                            : 'Sustainable'}</span>
                    </div>
                </section>

                ${board ? `
                <section class="fm-panel">
                    <div class="fm-panel-head">
                        <div>
                            <h3>The board</h3>
                            <p class="fm-subtle">${escapeHtml(board.headline || '')}</p>
                        </div>
                        <span class="fm-panel-action">Trust ${escapeHtml(String(Math.round(Number(board.trust || 0))))}/100</span>
                    </div>
                    ${(board.concerns || []).length ? `<ul class="fm-subtle">${board.concerns.map(c => `<li>${escapeHtml(c)}</li>`).join('')}</ul>` : ''}
                    ${(board.plaudits || []).length ? `<ul class="fm-subtle">${board.plaudits.map(c => `<li>${escapeHtml(c)}</li>`).join('')}</ul>` : ''}
                </section>` : ''}

                <section class="fm-panel">
                    <div class="fm-panel-head">
                        <div>
                            <h3>Weekly ledger</h3>
                            <p class="fm-subtle">Real settled weeks, read from the club accounts.</p>
                        </div>
                        <span class="fm-panel-action">${escapeHtml(String(history.length))} weeks</span>
                    </div>
                    ${history.length ? `
                    <div class="finance-legend"><span><i class="finance-dot is-balance"></i>Balance</span><span><i class="finance-dot is-income"></i>Income</span><span><i class="finance-dot is-expense"></i>Expenses</span></div>
                    <div class="finance-chart-list">
                        ${history.map(entry => `
                            <div class="finance-chart-row">
                                <div class="finance-chart-label">Week ${escapeHtml(String(entry.week))}</div>
                                <div class="finance-chart-bars">
                                    <div class="finance-chart-track"><span class="finance-chart-fill is-balance" style="width:${(Math.abs(Number(entry.balance)) / maxHistoryValue) * 100}%;"></span><strong>${escapeHtml(formatBudget(entry.balance))}</strong></div>
                                    <div class="finance-chart-track"><span class="finance-chart-fill is-income" style="width:${(Number(entry.income) / maxHistoryValue) * 100}%;"></span><strong>${escapeHtml(formatBudget(entry.income))}</strong></div>
                                    <div class="finance-chart-track"><span class="finance-chart-fill is-expense" style="width:${(Number(entry.expenses) / maxHistoryValue) * 100}%;"></span><strong>${escapeHtml(formatBudget(entry.expenses))}</strong></div>
                                </div>
                            </div>`).join('')}
                    </div>` : `<p class="fm-subtle">No weeks settled yet. The ledger fills in as the season is played.</p>`}
                </section>
            </div>`;

        // The season picker reloads in place. Stored so the choice survives navigating away and
        // back, which is what a manager comparing two seasons will do repeatedly.
        const seasonPicker = mainContent.querySelector('[data-finances-season]');
        if (seasonPicker) {
            seasonPicker.addEventListener('change', event => {
                const chosen = event.target.value;
                if (chosen) sessionStorage.setItem('financesSeason', chosen);
                else sessionStorage.removeItem('financesSeason');
                loadFinances();
            });
        }
    }


    function getInterestedTeams(transfer) {
        if (!transfer) return [];
        if (Array.isArray(transfer.interestedTeams)) return transfer.interestedTeams.filter(Boolean);
        return Object.values(transfer.interestedTeams || {}).filter(Boolean);
    }

    /**
     * The actionable bids, with their ids.
     *
     * <p>Falls back to nothing rather than to {@code interestedTeams}: those are prose strings
     * ("Rival FC offered EUR 900000") and an id cannot be recovered from one. A screen that shows
     * "there are bids" with nothing to click is what this replaced.
     */
    function getTransferOffers(transfer) {
        if (!transfer || !Array.isArray(transfer.offers)) return [];
        return transfer.offers.filter(offer => offer && offer.id != null);
    }

    function formatMoney(value) {
        return escapeHtml(formatBudget(Math.round(Number(value || 0))));
    }

    function promptTransferPrice(label, fallbackValue) {
        const initial = Math.max(1, Math.round(Number(fallbackValue || 1)));
        const raw = window.prompt(label, String(initial));
        if (raw == null) return null;
        const numeric = Number(raw);
        if (!Number.isFinite(numeric) || numeric <= 0) {
            window.alert('Enter a valid positive price.');
            return null;
        }
        return numeric;
    }

    async function sendTransferRequest(url, options = {}) {
        const method = options.method || 'POST';
        const payload = options.payload;
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

    function maybeShowTransferMessage(result) {
        if (result && typeof result.actionMessage === 'string' && result.actionMessage.trim()) {
            window.alert(result.actionMessage.trim());
        }
    }

    function openTransferPlayer(button) {
        const playerId = Number(button.dataset.playerId || 0);
        const sellerTeamId = Number(button.dataset.sellerTeamId || 0);
        const sellerTeamName = button.dataset.sellerTeamName || 'Team';
        if (!playerId) return;
        if (sellerTeamId && sellerTeamId === Number(getTeamId())) {
            loadPlayer(playerId, 'transfers');
            return;
        }
        loadLeagueTeamPlayer(playerId, sellerTeamId, sellerTeamName);
    }

    function bindTransferCentreActions(mainContent) {
        const teamId = Number(getTeamId() || 0);
        const teamName = getTeamName?.() || '';

        // The market filter, bound here with the rest of the page because innerHTML replaced the
        // select that was listening. Changing it reloads the market; it does not change what a
        // manager is allowed to sign, only which country's clubs are listed.
        const marketSelect = mainContent.querySelector('[data-market-country]');
        if (marketSelect) {
            marketSelect.addEventListener('change', () => {
                transferMarketCountry = marketSelect.value || '';
                loadTransfers();
            });
        }

        mainContent.querySelectorAll('[data-transfer-open]').forEach(button => {
            button.addEventListener('click', () => openTransferPlayer(button));
        });

        mainContent.querySelectorAll('[data-transfer-action]').forEach(button => {
            button.addEventListener('click', async () => {
                const playerId = Number(button.dataset.playerId || 0);
                if (!playerId || !teamId) return;

                try {
                    switch (button.dataset.transferAction) {
                        case 'list': {
                            const price = promptTransferPrice(
                                'Set asking price for this player:',
                                button.dataset.defaultPrice || 1
                            );
                            if (price == null) return;
                            maybeShowTransferMessage(await sendTransferRequest(`/transfers/list/${playerId}`, {
                                payload: { teamId, price }
                            }));
                            break;
                        }
                        case 'remove': {
                            if (!window.confirm('Remove this player from the transfer list?')) return;
                            await sendTransferRequest(`/transfers/remove/${playerId}?teamId=${teamId}`, {
                                method: 'DELETE'
                            });
                            break;
                        }
                        case 'interest': {
                            const params = new URLSearchParams({ teamId: String(teamId) });
                            if (teamName) params.set('club', teamName);
                            maybeShowTransferMessage(await sendTransferRequest(`/transfers/interest/${playerId}?${params.toString()}`));
                            break;
                        }
                        case 'withdraw-interest': {
                            const params = new URLSearchParams({ teamId: String(teamId) });
                            if (teamName) params.set('club', teamName);
                            maybeShowTransferMessage(await sendTransferRequest(`/transfers/interest/${playerId}/withdraw?${params.toString()}`));
                            break;
                        }
                        case 'clear-interest': {
                            if (!window.confirm('Clear every registered interest and offer on this player? He stays on the transfer list.')) return;
                            maybeShowTransferMessage(await sendTransferRequest(`/transfers/interest/${playerId}/clear`, {
                                payload: { teamId }
                            }));
                            break;
                        }
                        case 'buy': {
                            const price = promptTransferPrice(
                                'Enter agreed fee for this listed player:',
                                button.dataset.defaultPrice || 1
                            );
                            if (price == null) return;
                            maybeShowTransferMessage(await sendTransferRequest(`/transfers/buy/${playerId}`, {
                                payload: { teamId, price }
                            }));
                            break;
                        }
                        case 'accept-offer': {
                            maybeShowTransferMessage(await sendTransferRequest(`/transfers/accept-offer/${playerId}`, {
                                payload: { teamId }
                            }));
                            break;
                        }
                        case 'accept-named': {
                            // The bid the manager actually clicked, not the richest one. `offerId`
                            // must be present: without it this would silently fall back to the
                            // backend's choice, which is the behaviour this button replaced.
                            const offerId = button.dataset.offerId;
                            if (!offerId) {
                                window.alert('That offer could not be identified. Reload the page and try again.');
                                return;
                            }
                            if (!window.confirm('Accept this bid? The player signs immediately and every other bid is refused.')) return;
                            maybeShowTransferMessage(await sendTransferRequest(
                                `/transfers/accept-offer/${playerId}/${offerId}`,
                                { payload: { teamId } }
                            ));
                            break;
                        }
                        case 'reject-offers': {
                            maybeShowTransferMessage(await sendTransferRequest(`/transfers/reject-offers/${playerId}`, {
                                payload: { teamId }
                            }));
                            break;
                        }
                        default:
                            return;
                    }

                    await loadTransfers();
                } catch (err) {
                    console.error('Transfer action failed:', err);
                    window.alert(err.message || 'Transfer action failed.');
                }
            });
        });
    }

    /**
     * Which country's market is on screen.
     *
     * <p>A <b>filter, not a restriction</b> (owner, 2026-09-28): a player belongs to a league's country,
     * not necessarily to a manager's nationality, and signing a foreigner is explicitly allowed. The
     * country only decides what the market screen shows. Empty means "mine", which is the server's
     * default anyway.
     */
    let transferMarketCountry = '';

    async function loadTransfers() {
        const teamId = getTeamId();
        const mainContent = document.getElementById('main-content');
        try {
            const marketQuery = transferMarketCountry
                ? `&country=${encodeURIComponent(transferMarketCountry)}`
                : '';
            const [marketResponse, overviewResponse, playersResponse, catalogResponse] = await Promise.all([
                authFetch(`/transfers?teamId=${encodeURIComponent(teamId)}${marketQuery}`),
                authFetch(`/transfers/team/${encodeURIComponent(teamId)}?viewerTeamId=${encodeURIComponent(teamId)}`),
                authFetch(`/teams/${encodeURIComponent(teamId)}/players`),
                authFetch('/countries/catalog')
            ]);
            // Each read falls back. A 403 on the market alone used to reject the whole `Promise.all`,
            // so a manager who could see his own players but not the global market lost the players too.
            // The catalogue is a filter, not data, so an empty one simply offers every country.
            const [transfers, myOverview, players, catalog] = await Promise.all([
                readJsonOr(marketResponse, [], 'The transfer market'),
                readJsonOr(overviewResponse, {}, 'Your transfer overview'),
                readJsonOr(playersResponse, [], 'Your squad'),
                readJsonOr(catalogResponse, [], 'The country catalogue')
            ]);
            // Only countries with clubs can have anything listed in them, so the filter would
            // otherwise offer 40 entries that are all guaranteed to be empty.
            const marketCountries = (Array.isArray(catalog) ? catalog : []).filter(c => c.hasClubs);

            const orderedTransfers = [...transfers].sort((a, b) => new Date(b.listedAt || 0) - new Date(a.listedAt || 0));
            const listedPlayers = Array.isArray(myOverview?.listedPlayers) ? myOverview.listedPlayers : [];
            const incomingOffers = Array.isArray(myOverview?.incomingOffers) ? myOverview.incomingOffers : [];
            const listedIds = new Set(listedPlayers.map(transfer => Number(transfer.playerId)));
            const orderedOwnPlayers = [...players].sort((a, b) => {
                const listedDiff = Number(listedIds.has(Number(a.id))) - Number(listedIds.has(Number(b.id)));
                if (listedDiff !== 0) return listedDiff;
                return Number(b.overall || b.rating || 0) - Number(a.overall || a.rating || 0);
            });
            const averageAsking = orderedTransfers.length
                ? orderedTransfers.reduce((sum, transfer) => sum + Number(transfer.askingPrice || 0), 0) / orderedTransfers.length
                : 0;
            const highestAsking = orderedTransfers.reduce((max, transfer) => Math.max(max, Number(transfer.askingPrice || 0)), 0);
            const interestCount = orderedTransfers.reduce((sum, transfer) => sum + getInterestedTeams(transfer).length, 0);

            mainContent.innerHTML = `
                <div class="fm-page fm-page--club">
                    <section class="fm-panel fm-club-hero">
                        <button class="back-to-dashboard" data-nav-back="dashboard">Back</button>
                        <div class="fm-club-hero-main">
                            <div>
                                <div class="fm-eyebrow">Transfer centre</div>
                                <h2>Transfers</h2>
                                <p class="fm-subtle">Global transfer list, direct club overview, and quick actions for listing, removing, bidding, and buying players.</p>
                            </div>
                            ${buildClubActionsHtml('transfers')}
                        </div>
                        <div class="fm-medical-stat-grid team-summary-grid">
                            <div><strong>${orderedTransfers.length}</strong><span>Listed players</span></div>
                            <div><strong>${formatMoney(averageAsking)}</strong><span>Avg asking</span></div>
                            <div><strong>${formatMoney(highestAsking)}</strong><span>Top asking</span></div>
                            <div><strong>${interestCount}</strong><span>Active interest</span></div>
                        </div>
                    </section>

                    <section class="fm-panel">
                        <div class="fm-panel-head">
                            <div>
                                <h3>Transfer market</h3>
                                <p class="fm-subtle">Browsing which country's clubs you buy from. Signing a player from anywhere is allowed.</p>
                            </div>
                            <label class="fm-market-filter">
                                <span class="fm-detail-label">Market</span>
                                <select data-market-country>
                                    <option value="">My country</option>
                                    ${marketCountries.map(c => `<option value="${escapeHtml(c.code)}"${c.code === transferMarketCountry ? ' selected' : ''}>${escapeHtml(c.name)}</option>`).join('')}
                                </select>
                            </label>
                        </div>
                        <div class="fm-market-hint fm-subtle">
                            ${transferMarketCountry
                                ? `Showing clubs registered in ${escapeHtml((marketCountries.find(c => c.code === transferMarketCountry) || {}).name || transferMarketCountry)}.`
                                : 'Showing your own country. Pick another above to scout abroad.'}
                        </div>
                    </section>

                    <section class="fm-panel">
                        <div class="fm-panel-head">
                            <div>
                                <h3>My transfer desk</h3>
                                <p class="fm-subtle">Budget, listed players, incoming bids, and transfer actions for your club.</p>
                            </div>
                            <span class="fm-panel-action">${escapeHtml(myOverview?.teamName || 'Club')}</span>
                        </div>
                        <div class="fm-medical-stat-grid team-summary-grid" style="margin-bottom:18px;">
                            <div><strong>${formatMoney(myOverview?.budget || 0)}</strong><span>Budget</span></div>
                            <div><strong>${listedPlayers.length}</strong><span>Listed now</span></div>
                            <div><strong>${incomingOffers.length}</strong><span>Incoming offers</span></div>
                            <div><strong>${players.length}</strong><span>Squad size</span></div>
                            <div><strong>${getInterestedTeams(listedPlayers[0] || incomingOffers[0] || null).length || 0}</strong><span>Top listing interest</span></div>
                        </div>
                        ${incomingOffers.length === 0 ? '' : `
                            <div class="fm-panel" style="margin-bottom:18px; background:rgba(255,255,255,0.03);">
                                <div class="fm-panel-head">
                                    <div>
                                        <h3>Incoming offers</h3>
                                        <p class="fm-subtle">Resolve direct bids here before removing or relisting a player.</p>
                                    </div>
                                    <span class="fm-panel-action">${incomingOffers.length}</span>
                                </div>
                                <div class="fm-squad-wrap">
                                    <table class="fm-squad">
                                        <thead><tr><th class="sq-name">Player</th><th>Pos</th><th>Bids</th><th>Listed</th><th>Actions</th></tr></thead>
                                        <tbody>
                                            ${incomingOffers.map(transfer => {
                                                const offers = getTransferOffers(transfer);
                                                return `
                                                    <tr class="fm-squad-row">
                                                        <td class="sq-name">${escapeHtml(transfer.playerName || 'Unknown')}</td>
                                                        <td>${escapeHtml(transfer.position || '-')}</td>
                                                        <td>${offers.length
                                                            ? offers.map(offer => renderOfferTerms(offer, { escapeHtml })).join('')
                                                            : '<span class="fm-subtle">No offers</span>'}</td>
                                                        <td>${escapeHtml(formatDateTimeLabel(transfer.listedAt))}</td>
                                                        <td>
                                                            <div style="display:flex; flex-wrap:wrap; gap:8px;">
                                                                <button type="button" class="fm-action-btn secondary" data-transfer-open="true" data-player-id="${transfer.playerId}" data-seller-team-id="${transfer.sellerTeamId || teamId}" data-seller-team-name="${escapeHtml(transfer.sellerTeamName || myOverview?.teamName || 'Club')}">Open</button>
                                                                ${transfer.canAcceptOffer ? renderOfferButtons(transfer, { escapeHtml }) : ''}
                                                                ${transfer.canRejectOffer ? `<button type="button" class="fm-action-btn secondary" data-transfer-action="reject-offers" data-player-id="${transfer.playerId}">Reject offers</button>` : ''}
                                                            </div>
                                                        </td>
                                                    </tr>`;
                                            }).join('')}
                                        </tbody>
                                    </table>
                                </div>
                            </div>`}
                        ${listedPlayers.length === 0 ? `<div class="fm-empty">No players from your team are currently on the transfer list.</div>` : `
                            <div class="fm-squad-wrap">
                                <table class="fm-squad">
                                    <thead><tr><th class="sq-name">Player</th><th>Pos</th><th>Asking</th><th>Interest</th><th>Listed</th><th>Actions</th></tr></thead>
                                    <tbody>
                                        ${listedPlayers.map(transfer => {
                                            const interests = getInterestedTeams(transfer);
                                            return `
                                                <tr class="fm-squad-row">
                                                    <td class="sq-name">${escapeHtml(transfer.playerName || 'Unknown')}</td>
                                                    <td>${escapeHtml(transfer.position || '-')}</td>
                                                    <td>${formatMoney(transfer.askingPrice)}</td>
                                                    <td>${escapeHtml(interests.length ? interests.join(', ') : 'No interest yet')}</td>
                                                    <td>${escapeHtml(formatDateTimeLabel(transfer.listedAt))}</td>
                                                    <td>
                                                        <div style="display:flex; flex-wrap:wrap; gap:8px;">
                                                            <button type="button" class="fm-action-btn secondary" data-transfer-open="true" data-player-id="${transfer.playerId}" data-seller-team-id="${transfer.sellerTeamId || teamId}" data-seller-team-name="${escapeHtml(transfer.sellerTeamName || myOverview?.teamName || 'Club')}">Open</button>
                                                            ${transfer.canAcceptOffer ? renderOfferButtons(transfer, { escapeHtml }) : ''}
                                                            ${transfer.canRejectOffer ? `<button type="button" class="fm-action-btn secondary" data-transfer-action="reject-offers" data-player-id="${transfer.playerId}">Reject offers</button>` : ''}
                                                            ${transfer.canClearInterest ? `<button type="button" class="fm-action-btn secondary" data-transfer-action="clear-interest" data-player-id="${transfer.playerId}">Clear interest</button>` : ''}
                                                            <button type="button" class="fm-action-btn secondary" data-transfer-action="remove" data-player-id="${transfer.playerId}" ${transfer.removalAllowed ? '' : `disabled title="${transfer.hasPricedOffer ? 'A club has a live offer on this player. Reject or clear it first.' : 'Cannot remove right now.'}"`}>Remove</button>
                                                        </div>
                                                    </td>
                                                </tr>`;
                                        }).join('')}
                                    </tbody>
                                </table>
                            </div>`}
                    </section>

                    <section class="fm-panel">
                        <div class="fm-panel-head">
                            <div>
                                <h3>Transfer market board</h3>
                                <p class="fm-subtle">Browse the global TL, register interest, or complete a listed purchase immediately.</p>
                            </div>
                            <span class="fm-panel-action">Market</span>
                        </div>
                        ${orderedTransfers.length === 0 ? `<div class="fm-empty">No players are currently listed for transfer.</div>` : `
                            <div class="fm-squad-wrap">
                                <table class="fm-squad">
                                    <thead><tr><th class="sq-name">Player</th><th>Club</th><th>Pos</th><th class="sq-age">Age</th><th class="sq-rating">Rating</th><th>Value</th><th>Asking</th><th>Interest</th><th>Actions</th></tr></thead>
                                    <tbody>
                                        ${orderedTransfers.map(transfer => {
                                            const interests = getInterestedTeams(transfer);
                                            const openLabel = Number(transfer.sellerTeamId) === Number(teamId) ? 'Open' : 'Scout';
                                            return `
                                                <tr class="fm-squad-row">
                                                    <td class="sq-name">${escapeHtml(transfer.playerName || 'Unknown')}</td>
                                                    <td>${escapeHtml(transfer.sellerTeamName || '-')}</td>
                                                    <td>${escapeHtml(transfer.position || '-')}</td>
                                                    <td class="sq-age">${transfer.age ?? '-'}</td>
                                                    <td class="sq-rating">${transfer.rating ?? '-'}</td>
                                                    <td>${formatMoney(transfer.playerValue)}</td>
                                                    <td>${formatMoney(transfer.askingPrice)}</td>
                                                    <td>${escapeHtml(interests.length ? interests.join(', ') : 'No interest yet')}</td>
                                                    <td>
                                                        <div style="display:flex; flex-wrap:wrap; gap:8px;">
                                                            <button type="button" class="fm-action-btn secondary" data-transfer-open="true" data-player-id="${transfer.playerId}" data-seller-team-id="${transfer.sellerTeamId || 0}" data-seller-team-name="${escapeHtml(transfer.sellerTeamName || 'Team')}">${openLabel}</button>
                                                            ${transfer.ownedByViewer && transfer.canAcceptOffer ? renderOfferButtons(transfer, { escapeHtml }) : ''}
                                                            ${transfer.ownedByViewer && transfer.canRejectOffer ? `<button type="button" class="fm-action-btn secondary" data-transfer-action="reject-offers" data-player-id="${transfer.playerId}">Reject offers</button>` : ''}
                                                            ${transfer.ownedByViewer ? `<button type="button" class="fm-action-btn secondary" data-transfer-action="remove" data-player-id="${transfer.playerId}" ${transfer.removalAllowed ? '' : 'disabled title="Cannot remove while another club has already registered interest."'}>Remove</button>` : ''}
                                                            ${transfer.buyableByViewer ? `<button type="button" class="fm-action-btn secondary" data-transfer-action="interest" data-player-id="${transfer.playerId}">Interest</button>` : ''}
                                                            ${transfer.buyableByViewer ? `<button type="button" class="fm-action-btn" data-transfer-action="buy" data-player-id="${transfer.playerId}" data-default-price="${Math.round(Number(transfer.askingPrice || 1))}">Buy listed</button>` : ''}
                                                        </div>
                                                    </td>
                                                </tr>`;
                                        }).join('')}
                                    </tbody>
                                </table>
                            </div>`}
                    </section>

                    <section class="fm-panel">
                        <div class="fm-panel-head">
                            <div>
                                <h3>My squad · list for transfer</h3>
                                <p class="fm-subtle">Any promoted junior who lands here can still be listed from the player view or directly from this table.</p>
                            </div>
                            <span class="fm-panel-action">Squad</span>
                        </div>
                        <div class="fm-squad-wrap">
                            <table class="fm-squad">
                                <thead><tr><th class="sq-name">Player</th><th>Pos</th><th class="sq-age">Age</th><th class="sq-rating">OVR</th><th>Value</th><th>Status</th><th>Actions</th></tr></thead>
                                <tbody>
                                    ${orderedOwnPlayers.map(player => {
                                        const isListed = listedIds.has(Number(player.id));
                                        const listedTransfer = listedPlayers.find(item => Number(item.playerId) === Number(player.id)) || null;
                                        return `
                                            <tr class="fm-squad-row">
                                                <td class="sq-name">${escapeHtml(player.name || 'Unknown')}</td>
                                                <td>${escapeHtml(player.position || '-')}</td>
                                                <td class="sq-age">${player.age ?? '-'}</td>
                                                <td class="sq-rating">${player.overall ?? player.rating ?? '-'}</td>
                                                <td>${formatMoney(player.value)}</td>
                                                <td>${escapeHtml(isListed ? `Listed for ${formatBudget(Math.round(Number(listedTransfer?.askingPrice || 0)))}` : 'Available')}</td>
                                                <td>
                                                    <div style="display:flex; flex-wrap:wrap; gap:8px;">
                                                        <button type="button" class="fm-action-btn secondary" data-transfer-open="true" data-player-id="${player.id}" data-seller-team-id="${teamId}" data-seller-team-name="${escapeHtml(myOverview?.teamName || 'Club')}">Open</button>
                                                        ${isListed
                                                            ? `${listedTransfer?.canAcceptOffer ? renderOfferButtons(listedTransfer, { escapeHtml }) : ''}${listedTransfer?.canRejectOffer ? `<button type="button" class="fm-action-btn secondary" data-transfer-action="reject-offers" data-player-id="${player.id}">Reject offers</button>` : ''}${listedTransfer?.canClearInterest ? `<button type="button" class="fm-action-btn secondary" data-transfer-action="clear-interest" data-player-id="${player.id}">Clear interest</button>` : ''}<button type="button" class="fm-action-btn secondary" data-transfer-action="remove" data-player-id="${player.id}" ${(listedTransfer?.removalAllowed ?? false) ? '' : `disabled title="${listedTransfer?.hasPricedOffer ? 'A club has a live offer on this player. Reject or clear it first.' : 'Cannot remove right now.'}"`}>Remove</button>`
                                                            : `<button type="button" class="fm-action-btn" data-transfer-action="list" data-player-id="${player.id}" data-default-price="${Math.round(Number(player.value || 1))}">List</button>`}
                                                    </div>
                                                </td>
                                            </tr>`;
                                    }).join('')}
                                </tbody>
                            </table>
                        </div>
                    </section>
                </div>`;

            bindTransferCentreActions(mainContent);
        } catch (err) {
            console.error('Failed to load transfer centre:', err);
            mainContent.innerHTML = `
                <div class="fm-page fm-page--club">
                    <section class="fm-panel fm-club-hero">
                        <button class="back-to-dashboard" data-nav-back="dashboard">Back</button>
                        <div class="fm-club-hero-main">
                            <div>
                                <div class="fm-eyebrow">Transfer centre</div>
                                <h2>Transfers</h2>
                                <p class="fm-subtle">The transfer centre could not be loaded right now.</p>
                            </div>
                            ${buildClubActionsHtml('transfers')}
                        </div>
                    </section>
                    <section class="fm-panel"><div class="fm-empty">${escapeHtml(err.message || 'Transfer centre unavailable.')}</div></section>
                </div>`;
        }
    }

    return { loadStaff, loadFinances, loadTransfers, loadCoaches: loadStaff, loadStaffMember: (...args) => staffDirectoryFeature.loadStaffMember(...args) };
}
