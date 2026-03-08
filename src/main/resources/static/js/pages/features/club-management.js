import { createStaffDirectoryFeature } from './staff-directory.js';

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
    const staffDirectoryFeature = createStaffDirectoryFeature({ authFetch, getTeamId, escapeHtml, buildClubActionsHtml });

    async function loadStaff() {
        return staffDirectoryFeature.loadStaff();
    }

    async function loadFinances() {
        const teamId = getTeamId();
        console.log(`Loading finances for ${teamId}`);
        const [profileRes, playersRes] = await Promise.all([
            authFetch(`/demo/teams/${teamId}/profile`),
            authFetch(`/teams/${teamId}/players`)
        ]);
        const profile = profileRes.ok ? await profileRes.json() : {};
        const players = playersRes.ok ? await playersRes.json() : [];
        const mainContent = document.getElementById('main-content');

        const squadValue = players.reduce((sum, player) => sum + Number(player.value || 0), 0);
        const squadSize = players.length;
        const averageValue = squadSize ? squadValue / squadSize : 0;
        const topAssets = [...players].sort((a, b) => Number(b.value || 0) - Number(a.value || 0)).slice(0, 5);
        const topAsset = topAssets[0] || null;
        const injuredCount = players.filter(player => player.injured).length;
        const budget = Number(profile.budget || 0);
        const transferBudget = Math.round(Math.max(budget * 0.38, squadValue * 0.04, 50000));
        const weeklyWageBudget = Math.round(Math.max(squadSize * 1850, averageValue * 0.0015, 12000));
        const annualWages = weeklyWageBudget * 52;
        const monthlyIncome = Math.round(Math.max(budget * 0.055, squadValue * 0.018) + squadSize * 4500);
        const monthlyExpenses = Math.round((annualWages / 12) + injuredCount * 18000 + squadSize * 3200);
        const netMonthly = monthlyIncome - monthlyExpenses;
        const sponsors = [
            { name: `${profile.name || 'Club'} Main Partner`, annualIncome: Math.round(monthlyIncome * 3.4) },
            { name: 'Regional Media Deal', annualIncome: Math.round(monthlyIncome * 2.1) },
            { name: 'Matchday Hospitality', annualIncome: Math.round(monthlyIncome * 1.35) }
        ];
        const monthNames = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
        const draftHistory = Array.from({ length: 6 }, (_, idx) => {
            const date = new Date();
            date.setMonth(date.getMonth() - (5 - idx));
            const income = Math.round(monthlyIncome * (0.9 + idx * 0.035));
            const expenses = Math.round(monthlyExpenses * (1.05 - idx * 0.02 + (injuredCount > 0 ? 0.015 : 0)));
            return {
                month: `${monthNames[date.getMonth()]} ${date.getFullYear()}`,
                income,
                expenses,
                net: income - expenses
            };
        });
        let rollingBalance = budget - draftHistory.reduce((sum, entry) => sum + entry.net, 0);
        const historyEntries = draftHistory.map(entry => {
            rollingBalance += entry.net;
            return { ...entry, balance: rollingBalance };
        });
        const maxHistoryValue = Math.max(1, ...historyEntries.flatMap(entry => [Math.abs(entry.balance), entry.income, entry.expenses]));

        mainContent.innerHTML = `
            <div class="fm-page fm-page--club">
                <section class="fm-panel fm-club-hero">
                    <button class="back-to-dashboard" data-nav-back="dashboard">Back</button>
                    <div class="fm-club-hero-main">
                        <div>
                            <div class="fm-eyebrow">Club finances</div>
                            <h2>${escapeHtml(profile.name || 'Finances')}</h2>
                            <p class="fm-subtle">Budget and squad asset view are now presented in the same wide club shell, using the data already available in the app.</p>
                        </div>
                        ${buildClubActionsHtml('finances')}
                    </div>
                    <div class="fm-medical-stat-grid team-summary-grid">
                        <div><strong>${escapeHtml(formatBudget(profile.budget))}</strong><span>Budget</span></div>
                        <div><strong>${escapeHtml(formatBudget(Math.round(squadValue)))}</strong><span>Squad value</span></div>
                        <div><strong>${escapeHtml(formatBudget(Math.round(averageValue)))}</strong><span>Avg asset</span></div>
                        <div><strong>${escapeHtml(topAsset?.name || '—')}</strong><span>Top asset</span></div>
                    </div>
                </section>
                <section class="finance-flow-grid">
                    <div class="finance-flow-card is-income"><div class="finance-flow-title">Transfer budget</div><strong>${escapeHtml(formatBudget(transferBudget))}</strong><span>Available for incoming business</span></div>
                    <div class="finance-flow-card is-expense"><div class="finance-flow-title">Wage budget</div><strong>${escapeHtml(formatBudget(weeklyWageBudget))}</strong><span>Estimated weekly payroll room</span></div>
                    <div class="finance-flow-card is-balance"><div class="finance-flow-title">Annual wages</div><strong>${escapeHtml(formatBudget(annualWages))}</strong><span>Projected full-season cost</span></div>
                    <div class="finance-flow-card ${netMonthly >= 0 ? 'is-income' : 'is-expense'}"><div class="finance-flow-title">Net monthly</div><strong>${escapeHtml(formatBudget(netMonthly))}</strong><span>${netMonthly >= 0 ? 'Positive trend' : 'Negative trend'}</span></div>
                </section>
                <section class="fm-panel">
                    <div class="fm-panel-head">
                        <div>
                            <h3>Balance history</h3>
                            <p class="fm-subtle">Safer local version of the open-football finance screen: same structure, but driven by current club budget plus derived monthly projections.</p>
                        </div>
                        <span class="fm-panel-action">6 months</span>
                    </div>
                    <div class="finance-legend"><span><i class="finance-dot is-balance"></i>Balance</span><span><i class="finance-dot is-income"></i>Income</span><span><i class="finance-dot is-expense"></i>Expenses</span></div>
                    <div class="finance-chart-list">
                        ${historyEntries.map(entry => `
                            <div class="finance-chart-row">
                                <div class="finance-chart-label">${escapeHtml(entry.month)}</div>
                                <div class="finance-chart-bars">
                                    <div class="finance-chart-track"><span class="finance-chart-fill is-balance" style="width:${(Math.abs(entry.balance) / maxHistoryValue) * 100}%;"></span><strong>${escapeHtml(formatBudget(entry.balance))}</strong></div>
                                    <div class="finance-chart-track"><span class="finance-chart-fill is-income" style="width:${(entry.income / maxHistoryValue) * 100}%;"></span><strong>${escapeHtml(formatBudget(entry.income))}</strong></div>
                                    <div class="finance-chart-track"><span class="finance-chart-fill is-expense" style="width:${(entry.expenses / maxHistoryValue) * 100}%;"></span><strong>${escapeHtml(formatBudget(entry.expenses))}</strong></div>
                                </div>
                            </div>`).join('')}
                    </div>
                </section>
                <div class="finance-bottom-grid">
                    <section class="fm-panel">
                        <div class="fm-panel-head">
                            <div>
                                <h3>Income & expenses</h3>
                                <p class="fm-subtle">Monthly summary table inspired by the reference finance page.</p>
                            </div>
                            <span class="fm-panel-action">Ledger</span>
                        </div>
                        <div class="fm-squad-wrap">
                            <table class="fm-squad finance-table">
                                <thead><tr><th>Month</th><th>Income</th><th>Expenses</th><th>Net</th><th>Balance</th></tr></thead>
                                <tbody>
                                    ${historyEntries.map(entry => `
                                        <tr class="fm-squad-row">
                                            <td>${escapeHtml(entry.month)}</td>
                                            <td class="finance-income-text">${escapeHtml(formatBudget(entry.income))}</td>
                                            <td class="finance-expense-text">${escapeHtml(formatBudget(entry.expenses))}</td>
                                            <td class="${entry.net >= 0 ? 'finance-income-text' : 'finance-expense-text'}">${escapeHtml(formatBudget(entry.net))}</td>
                                            <td>${escapeHtml(formatBudget(entry.balance))}</td>
                                        </tr>`).join('')}
                                </tbody>
                            </table>
                        </div>
                    </section>
                    <section class="fm-panel">
                        <div class="fm-panel-head">
                            <div>
                                <h3>Sponsorship</h3>
                                <p class="fm-subtle">Mock sponsorship block added to match the reference structure without touching backend finance models.</p>
                            </div>
                            <span class="fm-panel-action">Partners</span>
                        </div>
                        <div class="finance-sponsor-list">
                            ${sponsors.map(sponsor => `
                                <div class="finance-sponsor-item">
                                    <div>
                                        <strong>${escapeHtml(sponsor.name)}</strong>
                                        <span>Annual commitment</span>
                                    </div>
                                    <strong class="finance-income-text">${escapeHtml(formatBudget(sponsor.annualIncome))}</strong>
                                </div>`).join('')}
                        </div>
                        <div class="club-profile-detail-list" style="margin-top:14px;">
                            <div class="club-profile-detail-row"><span>Squad size</span><strong>${squadSize}</strong></div>
                            <div class="club-profile-detail-row"><span>Unavailable players</span><strong>${injuredCount}</strong></div>
                            <div class="club-profile-detail-row"><span>Highest-value player</span><strong>${escapeHtml(topAsset ? `${topAsset.name} (${formatBudget(Math.round(topAsset.value || 0))})` : 'N/A')}</strong></div>
                            <div class="club-profile-detail-row"><span>Average player value</span><strong>${escapeHtml(formatBudget(Math.round(averageValue)))}</strong></div>
                            <div class="club-profile-detail-row"><span>Top assets tracked</span><strong>${topAssets.length}</strong></div>
                        </div>
                    </section>
                </div>
            </div>`;
    }

    function getInterestedTeams(transfer) {
        if (!transfer) return [];
        if (Array.isArray(transfer.interestedTeams)) return transfer.interestedTeams.filter(Boolean);
        return Object.values(transfer.interestedTeams || {}).filter(Boolean);
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
                            await sendTransferRequest(`/transfers/list/${playerId}`, {
                                payload: { teamId, price }
                            });
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
                            await sendTransferRequest(`/transfers/interest/${playerId}?${params.toString()}`);
                            break;
                        }
                        case 'buy': {
                            const price = promptTransferPrice(
                                'Enter agreed fee for this listed player:',
                                button.dataset.defaultPrice || 1
                            );
                            if (price == null) return;
                            await sendTransferRequest(`/transfers/buy/${playerId}`, {
                                payload: { teamId, price }
                            });
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

    async function loadTransfers() {
        const teamId = getTeamId();
        console.log(`Loading transfers for ${teamId}`);
        const mainContent = document.getElementById('main-content');
        try {
            const [marketResponse, overviewResponse, playersResponse] = await Promise.all([
                authFetch(`/transfers?teamId=${encodeURIComponent(teamId)}`),
                authFetch(`/transfers/team/${encodeURIComponent(teamId)}?viewerTeamId=${encodeURIComponent(teamId)}`),
                authFetch(`/teams/${encodeURIComponent(teamId)}/players`)
            ]);
            const [transfers, myOverview, players] = await Promise.all([
                marketResponse.json(),
                overviewResponse.json(),
                playersResponse.json()
            ]);

            const orderedTransfers = [...transfers].sort((a, b) => new Date(b.listedAt || 0) - new Date(a.listedAt || 0));
            const listedPlayers = Array.isArray(myOverview?.listedPlayers) ? myOverview.listedPlayers : [];
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
                                <h3>My transfer desk</h3>
                                <p class="fm-subtle">Budget, listed players, and removal controls for your club.</p>
                            </div>
                            <span class="fm-panel-action">${escapeHtml(myOverview?.teamName || 'Club')}</span>
                        </div>
                        <div class="fm-medical-stat-grid team-summary-grid" style="margin-bottom:18px;">
                            <div><strong>${formatMoney(myOverview?.budget || 0)}</strong><span>Budget</span></div>
                            <div><strong>${listedPlayers.length}</strong><span>Listed now</span></div>
                            <div><strong>${players.length}</strong><span>Squad size</span></div>
                            <div><strong>${getInterestedTeams(listedPlayers[0] || null).length || 0}</strong><span>Top-listing interest</span></div>
                        </div>
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
                                                            <button type="button" class="fm-action-btn secondary" data-transfer-action="remove" data-player-id="${transfer.playerId}" ${transfer.removalAllowed ? '' : 'disabled title="Cannot remove while another club has already registered interest."'}>Remove</button>
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
                                                            ? `<button type="button" class="fm-action-btn secondary" data-transfer-action="remove" data-player-id="${player.id}" ${(listedTransfer?.removalAllowed ?? false) ? '' : 'disabled title="Cannot remove while another club has already registered interest."'}>Remove</button>`
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