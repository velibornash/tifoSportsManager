export function createClubManagementFeature(deps) {
    const { authFetch, getTeamId, escapeHtml, buildClubActionsHtml, formatBudget, formatDateTimeLabel } = deps;

    async function loadStaff() {
        const teamId = getTeamId();
        console.log(`Loading staff for ${teamId}`);
        const [staffRes, profileRes] = await Promise.all([
            authFetch(`/demo/teams/${teamId}/coaches`),
            authFetch(`/demo/teams/${teamId}/profile`)
        ]);
        const coaches = staffRes.ok ? await staffRes.json() : [];
        const profile = profileRes.ok ? await profileRes.json() : {};
        const mainContent = document.getElementById('main-content');

        const uniqueRoles = [...new Set((coaches || []).map(coach => coach.role).filter(Boolean))];
        const averageRatingValue = coaches.length
            ? coaches.reduce((sum, coach) => sum + Number(coach.rating || 0), 0) / coaches.length
            : null;
        const averageRating = Number.isFinite(averageRatingValue) ? averageRatingValue.toFixed(1) : '-';
        const topCoach = [...coaches].sort((a, b) => Number(b.rating || 0) - Number(a.rating || 0))[0];

        mainContent.innerHTML = `
            <div class="fm-page fm-page--club">
                <section class="fm-panel fm-club-hero">
                    <button class="back-to-dashboard" data-nav-back="dashboard">Back</button>
                    <div class="fm-club-hero-main">
                        <div>
                            <div class="fm-eyebrow">Club staff</div>
                            <h2>${escapeHtml(profile.name || 'Staff')}</h2>
                            <p class="fm-subtle">Staff page now lives in the same club shell as the rest of the open-football-inspired team area.</p>
                        </div>
                        ${buildClubActionsHtml('staff')}
                    </div>
                    <div class="fm-medical-stat-grid team-summary-grid">
                        <div><strong>${coaches.length}</strong><span>Staff members</span></div>
                        <div><strong>${averageRating}</strong><span>Average rating</span></div>
                        <div><strong>${uniqueRoles.length}</strong><span>Departments</span></div>
                        <div><strong>${escapeHtml(topCoach?.name || '—')}</strong><span>Lead staffer</span></div>
                    </div>
                </section>
                <section class="fm-panel">
                    <div class="fm-panel-head">
                        <div>
                            <h3>Staff room</h3>
                            <p class="fm-subtle">Role cards stay compact, with the same dark shell and spacing language as the rest of Club.</p>
                        </div>
                        <span class="fm-panel-action">${escapeHtml(uniqueRoles.join(' · ') || 'Staff')}</span>
                    </div>
                    ${coaches.length === 0 ? `<div class="fm-empty">No staff data available right now.</div>` : `
                        <div class="manager-grid">
                            ${coaches.map(coach => `
                                <div class="manager-player-card">
                                    <div class="player-name">${escapeHtml(coach.name || 'Unknown')}</div>
                                    <div class="player-meta">${escapeHtml(coach.role || 'Staff')}</div>
                                    <div class="player-rating">Rating ${Number(coach.rating || 0)}</div>
                                </div>`).join('')}
                        </div>`}
                </section>
            </div>`;
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
                <div class="fm-grid-top fm-grid-top--club-profile">
                    <section class="fm-panel club-profile-detail-card">
                        <div class="fm-panel-head">
                            <div>
                                <h3>Financial overview</h3>
                                <p class="fm-subtle">This version reads from club budget plus current player valuations, so the page is already useful without a dedicated finance backend.</p>
                            </div>
                            <span class="fm-panel-action">Snapshot</span>
                        </div>
                        <div class="club-profile-detail-list">
                            <div class="club-profile-detail-row"><span>Available budget</span><strong>${escapeHtml(formatBudget(profile.budget))}</strong></div>
                            <div class="club-profile-detail-row"><span>Squad market value</span><strong>${escapeHtml(formatBudget(Math.round(squadValue)))}</strong></div>
                            <div class="club-profile-detail-row"><span>Average player value</span><strong>${escapeHtml(formatBudget(Math.round(averageValue)))}</strong></div>
                            <div class="club-profile-detail-row"><span>Highest-value player</span><strong>${escapeHtml(topAsset ? `${topAsset.name} (${formatBudget(Math.round(topAsset.value || 0))})` : 'N/A')}</strong></div>
                            <div class="club-profile-detail-row"><span>Squad size</span><strong>${squadSize}</strong></div>
                            <div class="club-profile-detail-row"><span>Unavailable players</span><strong>${injuredCount}</strong></div>
                        </div>
                    </section>
                    <section class="fm-panel">
                        <div class="fm-panel-head">
                            <div>
                                <h3>Value leaders</h3>
                                <p class="fm-subtle">Top assets by current player value.</p>
                            </div>
                            <span class="fm-panel-action">Top 5</span>
                        </div>
                        ${topAssets.length === 0 ? `<div class="fm-empty">No squad valuation data available.</div>` : `
                            <div class="fm-squad-wrap">
                                <table class="fm-squad">
                                    <thead><tr><th class="sq-name">Player</th><th>Pos</th><th class="sq-age">Age</th><th class="sq-rating">OVR</th><th>Form</th><th>Value</th></tr></thead>
                                    <tbody>
                                        ${topAssets.map(player => `
                                            <tr class="fm-squad-row">
                                                <td class="sq-name">${escapeHtml(player.name || 'Unknown')}</td>
                                                <td>${escapeHtml(player.position || '-')}</td>
                                                <td class="sq-age">${player.age ?? '-'}</td>
                                                <td class="sq-rating">${player.overall ?? '-'}</td>
                                                <td>${Number.isFinite(Number(player.form)) ? Number(player.form).toFixed(1) : '-'}</td>
                                                <td>${escapeHtml(formatBudget(Math.round(player.value || 0)))}</td>
                                            </tr>`).join('')}
                                    </tbody>
                                </table>
                            </div>`}
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

    return { loadStaff, loadFinances, loadTransfers, loadCoaches: loadStaff };
}