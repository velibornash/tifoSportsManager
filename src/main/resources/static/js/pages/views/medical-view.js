// pages/views/medical-view.js
import { htmlEscape, getPlayerConditionPercent } from './utils.js';

export function createMedicalView(deps) {
    const { authFetch, getTeamId, buildClubActionsHtml, loadPlayer } = deps;

    async function loadMedicalCenter() {
        const mainContent = document.getElementById("main-content");
        const teamId = getTeamId();
        try {
            const response = await authFetch(`/teams/${teamId}/medical`);
            if (!response.ok) throw new Error(`Medical overview load failed: ${response.status}`);
            const overview = await response.json();
            const queue = Array.isArray(overview?.recoveryQueue) ? overview.recoveryQueue : [];

            const buildRecoveryCardHtml = (player) => {
                const conditionPercent = getPlayerConditionPercent(player);
                const fatigue = Number(player?.fatigue);
                const fatigueText = Number.isFinite(fatigue) ? `${Math.round(fatigue)} fatigue` : 'Fatigue n/a';
                const injuryText = player?.injured
                    ? `Injured \u00B7 ${player?.injuryDaysRemaining ?? 0} days`
                    : 'Available \u00B7 managed recovery';
                const actionLabel = player?.injured ? 'Speed up recovery' : 'Recovery session';
                return `
                    <article class="fm-medical-card">
                        <div class="fm-medical-card-head">
                            <div>
                                <h4>${htmlEscape(player?.name || 'Player')}</h4>
                                <div class="fm-medical-card-subtle">${htmlEscape(player?.position || 'Player')} \u00B7 ${player?.age ?? '-'} years</div>
                            </div>
                            <div class="fm-badge-deck">
                                ${player?.injured ? '<span class="fm-badge fm-badge-inj">INJ</span>' : '<span class="fm-badge fm-badge-fit">REC</span>'}
                            </div>
                        </div>
                        <div class="fm-medical-chip-row">
                            <span class="fm-medical-chip">${htmlEscape(injuryText)}</span>
                            <span class="fm-medical-chip">${htmlEscape(fatigueText)}</span>
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
                        const recoveryResponse = await authFetch(`/teams/${teamId}/medical/recovery/${playerId}`, { method: 'POST' });
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

    return { loadMedicalCenter };
}
