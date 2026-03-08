import { createStaffDirectoryFeature } from './staff-directory.js';

export function createClubManagementFeature(deps) {
    const { authFetch, getTeamId, escapeHtml, buildClubActionsHtml, formatBudget, formatDateTimeLabel } = deps;
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

    async function loadTransfers() {
        const teamId = getTeamId();
        console.log(`Loading transfers for ${teamId}`);
        const response = await authFetch('/transfers');
        const transfers = response.ok ? await response.json() : [];
        const mainContent = document.getElementById('main-content');
        const orderedTransfers = [...transfers].sort((a, b) => new Date(b.listedAt || 0) - new Date(a.listedAt || 0));
        const averageAsking = orderedTransfers.length ? orderedTransfers.reduce((sum, transfer) => sum + Number(transfer.askingPrice || 0), 0) / orderedTransfers.length : 0;
        const highestAsking = orderedTransfers.reduce((max, transfer) => Math.max(max, Number(transfer.askingPrice || 0)), 0);
        const interestCount = orderedTransfers.reduce((sum, transfer) => {
            const interestedTeams = Array.isArray(transfer.interestedTeams) ? transfer.interestedTeams : Object.values(transfer.interestedTeams || {});
            return sum + interestedTeams.length;
        }, 0);

        mainContent.innerHTML = `
            <div class="fm-page fm-page--club">
                <section class="fm-panel fm-club-hero">
                    <button class="back-to-dashboard" data-nav-back="dashboard">Back</button>
                    <div class="fm-club-hero-main">
                        <div>
                            <div class="fm-eyebrow">Transfer centre</div>
                            <h2>Transfers</h2>
                            <p class="fm-subtle">Same club shell, now with a live transfer board fed by the current backend transfer list endpoint.</p>
                        </div>
                        ${buildClubActionsHtml('transfers')}
                    </div>
                    <div class="fm-medical-stat-grid team-summary-grid">
                        <div><strong>${orderedTransfers.length}</strong><span>Listed players</span></div>
                        <div><strong>${escapeHtml(formatBudget(Math.round(averageAsking)))}</strong><span>Avg asking</span></div>
                        <div><strong>${escapeHtml(formatBudget(Math.round(highestAsking)))}</strong><span>Top asking</span></div>
                        <div><strong>${interestCount}</strong><span>Interested clubs</span></div>
                    </div>
                </section>
                <section class="fm-panel">
                    <div class="fm-panel-head">
                        <div>
                            <h3>Transfer market board</h3>
                            <p class="fm-subtle">Read-only for now, with listed time, asking price, and interest already visible.</p>
                        </div>
                        <span class="fm-panel-action">Market</span>
                    </div>
                    ${orderedTransfers.length === 0 ? `<div class="fm-empty">No players are currently listed for transfer.</div>` : `
                        <div class="fm-squad-wrap">
                            <table class="fm-squad">
                                <thead><tr><th class="sq-name">Player</th><th>Pos</th><th class="sq-age">Age</th><th class="sq-rating">Rating</th><th>Value</th><th>Asking</th><th>Interest</th><th>Listed</th></tr></thead>
                                <tbody>
                                    ${orderedTransfers.map(transfer => {
                                        const player = transfer.player || {};
                                        const interestedTeams = Array.isArray(transfer.interestedTeams) ? transfer.interestedTeams : Object.values(transfer.interestedTeams || {});
                                        return `
                                            <tr class="fm-squad-row">
                                                <td class="sq-name">${escapeHtml(player.name || 'Unknown')}</td>
                                                <td>${escapeHtml(player.position || '-')}</td>
                                                <td class="sq-age">${player.age ?? '-'}</td>
                                                <td class="sq-rating">${player.rating ?? '-'}</td>
                                                <td>${escapeHtml(formatBudget(Math.round(player.playerValue ?? player.value ?? 0)))}</td>
                                                <td>${escapeHtml(formatBudget(Math.round(transfer.askingPrice || 0)))}</td>
                                                <td>${escapeHtml(interestedTeams.length ? interestedTeams.join(', ') : 'No interest yet')}</td>
                                                <td>${escapeHtml(formatDateTimeLabel(transfer.listedAt))}</td>
                                            </tr>`;
                                    }).join('')}
                                </tbody>
                            </table>
                        </div>`}
                </section>
            </div>`;
    }

    return { loadStaff, loadFinances, loadTransfers, loadCoaches: loadStaff, loadStaffMember: (...args) => staffDirectoryFeature.loadStaffMember(...args) };
}