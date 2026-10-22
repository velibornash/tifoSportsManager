// pages/views/medical-view.js
import { htmlEscape, getPlayerConditionPercent } from './utils.js';
import { apiErrorMessage } from '../../auth.js';

export function createMedicalView(deps) {
    const { authFetch, getTeamId, buildClubActionsHtml, loadPlayer } = deps;

    async function loadMedicalCenter() {
        const mainContent = document.getElementById("main-content");
        const teamId = getTeamId();
        try {
            const response = await authFetch(`/teams/${teamId}/medical`);
            const overview = await response.json();
            const queue = Array.isArray(overview?.recoveryQueue) ? overview.recoveryQueue : [];

            // The Recovery queue is a table and not a stack of cards any more (owner, 2026-10-20): a
            // queue of a dozen players was a dozen tall cards the manager had to scroll past, and the
            // numbers that matter - who is hurt, how tired, how fit - were scattered over the card
            // instead of standing in columns. Every value the card showed is still here, one per
            // column, so nothing the manager could read before has been dropped.
            const buildRecoveryRowHtml = (player) => {
                const conditionPercent = getPlayerConditionPercent(player);
                const fatigue = Number(player?.fatigue);
                const fatigueText = Number.isFinite(fatigue) ? `${Math.round(fatigue)} fatigue` : 'Fatigue n/a';
                const injuryText = player?.injured
                    ? `Injured \u00B7 ${player?.injuryDaysRemaining ?? 0} days`
                        + (player?.injuryCause ? ` \u00B7 ${player.injuryCause}` : '')
                    : 'Available \u00B7 managed recovery';
                const actionLabel = player?.injured ? 'Speed up recovery' : 'Recovery session';
                // **The operation, and what it costs.** The doctor decides how much of the absence he
                // can take and what the club pays for it, and a manager cannot decide on a button
                // that does not say either.
                const surgeryNote = player?.injured && !player?.surgeryAvailable && player?.surgeryNote
                    ? `<div class="fm-medical-card-subtle">${htmlEscape(player.surgeryNote)}</div>`
                    : '';
                const surgeryButton = player?.injured && player?.surgeryAvailable
                    ? `<button type="button" class="fm-action-btn secondary"
                        data-medical-surgery-player="${player?.id ?? ''}">Operate \u00B7 ${Math.round(player?.surgeryCost ?? 0).toLocaleString('en-US')} EUR</button>`
                    : '';
                return `
                    <tr>
                        <td class="sq-name"><strong>${htmlEscape(player?.name || 'Player')}</strong></td>
                        <td>${htmlEscape(player?.position || 'Player')}</td>
                        <td>${player?.age ?? '-'}</td>
                        <td>
                            <div class="fm-badge-deck">
                                ${player?.injured ? '<span class="fm-badge fm-badge-inj">INJ</span>' : '<span class="fm-badge fm-badge-fit">REC</span>'}
                            </div>
                            <div class="fm-medical-card-subtle">${htmlEscape(injuryText)}</div>
                        </td>
                        <td>${htmlEscape(fatigueText)}</td>
                        <td>
                            <div class="fm-cond">
                                <div class="fm-cond-bar"><div class="fm-cond-fill" style="width:${conditionPercent}%"></div></div>
                                <span class="fm-cond-val">${conditionPercent}%</span>
                            </div>
                        </td>
                        <td>
                            <div class="fm-medical-actions">
                                <button type="button" class="fm-action-btn secondary" data-medical-recover-player="${player?.id ?? ''}">${actionLabel}</button>
                                ${surgeryButton}
                                <button type="button" class="fm-action-btn secondary" data-medical-open-player="${player?.id ?? ''}">Open player</button>
                            </div>
                            ${surgeryNote}
                        </td>
                    </tr>`;
            };

            // The order the owner asked for on 2026-10-20: the Medical Center hero first, then the
            // Medical summary as a widget under it, and the Recovery queue under that as a table
            // that scrolls sideways. The old page put the summary in a side panel beside the queue,
            // and on a phone that side panel was the part that got cut off.
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
                    <section class="fm-panel fm-medical-panel fm-medical-summary-widget">
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
                        <div class="fm-panel-action">Squad condition recovers on its own each week; this page adds active treatment to speed it up.</div>
                    </section>
                    <section class="fm-panel fm-medical-queue-panel">
                        <div class="fm-panel-head">
                            <div>
                                <h3>Recovery queue</h3>
                                <p class="fm-subtle">Players with injuries or elevated fatigue show up here for treatment planning.</p>
                            </div>
                            <span class="fm-panel-action">${queue.length} cases</span>
                        </div>
                        ${queue.length ? `
                            <div class="fm-squad-wrap">
                                <table class="fm-table fm-squad">
                                    <thead>
                                        <tr>
                                            <th>Player</th>
                                            <th>Position</th>
                                            <th>Age</th>
                                            <th>Status</th>
                                            <th>Fatigue</th>
                                            <th>Condition</th>
                                            <th>Action</th>
                                        </tr>
                                    </thead>
                                    <tbody>
                                        ${queue.map(buildRecoveryRowHtml).join('')}
                                    </tbody>
                                </table>
                            </div>` : '<div class="fm-empty">No players currently need recovery treatment.</div>'}
                    </section>
                </div>`;

            mainContent.querySelectorAll('[data-medical-recover-player]').forEach(button => {
                button.addEventListener('click', async () => {
                    const playerId = Number(button.dataset.medicalRecoverPlayer || 0);
                    if (!playerId) return;
                    button.disabled = true;
                    try {
                        await authFetch(`/teams/${teamId}/medical/recovery/${playerId}`, { method: 'POST' });
                        await loadMedicalCenter();
                    } catch (err) {
                        // The server refused the recovery and says why - the player is not injured, the
                        // treatment is not available. That sentence is shown, not just logged: the
                        // manager pressed a button and deserves to read the answer.
                        console.error('Medical recovery failed:', err);
                        window.alert(apiErrorMessage(err, 'The recovery could not be arranged.'));
                        button.disabled = false;
                    }
                });
            });
            mainContent.querySelectorAll('[data-medical-surgery-player]').forEach(button => {
                button.addEventListener('click', async () => {
                    const playerId = Number(button.dataset.medicalSurgeryPlayer || 0);
                    if (!playerId) return;
                    button.disabled = true;
                    try {
                        await authFetch(`/teams/${teamId}/medical/surgery/${playerId}`, { method: 'POST' });
                        await loadMedicalCenter();
                    } catch (err) {
                        // The server refused and says why - no doctor, no money, or he has already had
                        // this operation. The sentence is shown, because a manager pressed a button.
                        console.error('The operation failed:', err);
                        window.alert(apiErrorMessage(err, 'The operation could not be arranged.'));
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

    return { loadMedicalCenter };
}
