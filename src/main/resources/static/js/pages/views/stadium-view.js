import { htmlEscape, formatPercent } from './utils.js';

/**
 * The stadium page: capacity, expansion, seats, roof, ticket prices, pitch maintenance, the colours,
 * and the ground's own picture.
 *
 * <p>None of this existed. The club profile had an "Open Stadium View" button that opened a
 * hard-coded image of somebody else's ground in a new tab, and the endpoints that could tell a
 * manager what their ground cost to run were never called from the frontend at all.
 *
 * <p>The plan is drawn rather than described: a pitch with the four sides and four corners coloured in
 * place, so what you set is what you see. It is the same idea as a tactics board and it costs one
 * <code>conic-gradient</code>.
 */

const SIDES = [
    ['north', 'North stand'],
    ['east', 'East stand'],
    ['south', 'South stand'],
    ['west', 'West stand'],
];
const CORNERS = [
    ['northWest', 'North-west corner'],
    ['northEast', 'North-east corner'],
    ['southWest', 'South-west corner'],
    ['southEast', 'South-east corner'],
];
const NEUTRAL = '#26303f';

export function createStadiumView(deps) {
    const { authFetch, getTeamId, formatBudget } = deps;
    let esc = typeof htmlEscape === 'function' ? htmlEscape : (v) => String(v ?? '');

    async function loadStadium() {
        const teamId = getTeamId();
        const main = document.getElementById('main-content');
        if (!main) return;
        if (!teamId) {
            main.innerHTML = emptyState('No club is loaded, so there is no ground to look at.');
            return;
        }

        main.innerHTML = loadingState();
        let data;
        try {
            const res = await authFetch(`/api/teams/${teamId}/stadium`);
            if (!res.ok) throw new Error(`the ground could not be loaded (${res.status})`);
            data = await res.json();
        } catch (err) {
            main.innerHTML = emptyState(`The stadium could not be loaded. ${esc(err.message || '')}`);
            return;
        }

        const s = data.stadium;
        if (!s) {
            main.innerHTML = emptyState(`${esc(data.teamName || 'This club')} does not have a ground yet.`);
            return;
        }

        main.innerHTML = render(data, s);
        wire(main, teamId);
    }

    // --- rendering ---

    function render(data, s) {
        const cap = s.capacity ?? 0;
        const seats = s.seatQuality ?? 10;
        const expansion = s.expansionQuote || {};
        const seatQuote = s.seatQuote || {};
        const prices = s.prices || {};

        return `
        <div class="fm-page fm-page--stadium">
            <section class="fm-panel fm-club-hero">
                <button class="back-to-dashboard" data-nav-back="profile">Back</button>
                <div class="fm-club-hero-main">
                    <div>
                        <div class="fm-eyebrow">${esc(data.teamName || 'Club')}</div>
                        <h2>${esc(s.name || 'Stadium')}</h2>
                    </div>
                </div>
                <div class="fm-medical-stat-grid team-summary-grid">
                    <div><strong>${cap.toLocaleString()}</strong><span>Capacity</span></div>
                    <div><strong>${seats}/20</strong><span>Seat quality</span></div>
                    <div><strong>${s.roof ? 'Yes' : 'No'}</strong><span>Roof</span></div>
                    <div><strong>${esc(s.budget != null ? formatBudget(s.budget) : '—')}</strong><span>Budget</span></div>
                </div>
            </section>

            <div class="fm-grid-top fm-grid-top--stadium">
                <section class="fm-panel stadium-plan-panel">
                    <div class="fm-panel-head">The ground</div>
                    ${plan(s)}
                    ${picture(s)}
                </section>

                <section class="fm-panel">
                    <div class="fm-panel-head">Build out</div>
                    <div class="club-profile-detail-list">
                        <div class="club-profile-detail-row">
                            <span>Capacity</span>
                            <strong>${cap.toLocaleString()}${
                                expansion.maxCapacity ? ` <span class="fm-subtle">of ${expansion.maxCapacity.toLocaleString()}</span>` : ''
                            }</strong>
                        </div>
                        <div class="club-profile-detail-row">
                            <span>Next ${(expansion.seatsAdded || 1000).toLocaleString()} seats</span>
                            <strong>${expansion.canExpand ? esc(formatBudget(expansion.cost || 0)) : esc(expansion.reason || 'Fully built out')}</strong>
                        </div>
                        <div class="club-profile-detail-row">
                            <span>Seat quality ${seatQuote.level ?? seats} → ${seatQuote.nextLevel ?? seats}</span>
                            <strong>${seatQuote.canImprove ? esc(formatBudget(seatQuote.cost || 0)) : 'As good as it gets'}</strong>
                        </div>
                        <div class="club-profile-detail-row">
                            <span>Roof</span>
                            <strong>${s.roof ? 'Built' : esc(formatBudget(s.roofCost || 0))}</strong>
                        </div>
                    </div>
                    <div class="fm-club-actions">
                        <button type="button" class="fm-action-btn" data-build="expand"
                            ${expansion.canExpand ? '' : 'disabled'}>Expand ground</button>
                        <button type="button" class="fm-action-btn secondary" data-build="seats"
                            ${seatQuote.canImprove ? '' : 'disabled'}>Better seats</button>
                        <button type="button" class="fm-action-btn secondary" data-build="roof"
                            ${s.roof ? 'disabled' : ''}>Build roof</button>
                    </div>
                    <p class="fm-subtle" data-build-note></p>
                </section>
            </div>

            <div class="fm-grid-bottom fm-grid-bottom--stadium">
                <section class="fm-panel">
                    <div class="fm-panel-head">Colours</div>
                    <p class="fm-subtle">Four sides and four corners. Free — the expensive part was building the thing.</p>
                    <div class="stadium-colour-grid">
                        ${SIDES.map(([key, label]) => colourField(key, label, s[key + 'Colour'])).join('')}
                        ${CORNERS.map(([key, label]) => colourField(key, label, s[key + 'CornerColour'])).join('')}
                    </div>
                    <div class="fm-club-actions">
                        <button type="button" class="fm-action-btn" data-paint>Save colours</button>
                    </div>
                    <p class="fm-subtle" data-paint-note></p>
                </section>

                <section class="fm-panel">
                    <div class="fm-panel-head">Prices and upkeep</div>
                    <div class="club-profile-detail-list">
                        ${Object.entries(prices).map(([tier, price]) => `
                            <div class="club-profile-detail-row">
                                <span>${esc(String(tier).replace(/_/g, ' ').toLowerCase())}</span>
                                <strong>${esc(formatMoney(price))}</strong>
                            </div>`).join('') || '<div class="club-profile-detail-row"><span>Prices</span><strong>—</strong></div>'}
                        <div class="club-profile-detail-row">
                            <span>Projected gate</span>
                            <strong>${esc(formatMoney(s.projectedGateRevenue))}</strong>
                        </div>
                        <div class="club-profile-detail-row">
                            <span>Home ground upkeep</span>
                            <strong>${esc(s.upkeepCategory || 'Facility upkeep')}</strong>
                        </div>
                    </div>
                </section>
            </div>

            <section class="fm-panel">
                <div class="fm-panel-head">The pitch</div>
                <div class="fm-medical-stat-grid team-summary-grid">
                    <div><strong>${esc(s.pitchStatus || '—')}</strong><span>Condition</span></div>
                    <div><strong>${esc(formatPercent(s.maintenanceRemaining, 0))}</strong><span>Programme funded</span></div>
                    <div><strong>${esc(formatPercent(s.pitchQuality))}</strong><span>Surface quality</span></div>
                    <div><strong>${s.awaySectorCapacity ? s.awaySectorCapacity.toLocaleString() : '—'}</strong><span>Away fans</span></div>
                </div>
                <div class="fm-club-actions">
                    <input type="number" class="fm-season-select" id="stadium-maintenance-budget"
                           min="0" step="100" value="500" aria-label="Weekly pitch maintenance budget"
                           style="max-width:140px">
                    <button type="button" class="fm-action-btn secondary" data-maintenance>Fund pitch work</button>
                </div>
                <p class="fm-subtle" data-maintenance-note></p>
            </section>

            <section class="fm-panel">
                <div class="fm-panel-head">Training facilities</div>
                <div class="fm-medical-stat-grid team-summary-grid">
                    ${Object.entries(data.trainingFacilities || {}).map(([k, v]) => `
                        <div><strong>${esc(v)}/20</strong><span>${esc(String(k).toLowerCase())}</span></div>`).join('')}
                </div>
                <p class="fm-subtle">Weekly upkeep ${esc(formatMoney(data.weeklyTrainingUpkeep))} · ${esc(data.upkeepCategory || '')}</p>
            </section>
        </div>`;
    }

    /** A top-down plan of the ground with the four sides and four corners in place. */
    function plan(s) {
        const c = (v) => v || NEUTRAL;
        return `
        <div class="stadium-plan" role="img"
             aria-label="Plan of ${esc(s.name || 'the ground')}, ${s.capacity ?? 0} seats">
            <div class="stadium-plan-pitch">
                <div class="stadium-plan-strip stadium-plan-strip--north" style="background:${c(s.northColour)}"
                     title="North stand"></div>
                <div class="stadium-plan-strip stadium-plan-strip--west" style="background:${c(s.westColour)}"
                     title="West stand"></div>
                <div class="stadium-plan-middle">
                    <div class="stadium-plan-corner stadium-plan-corner--nw" style="background:${c(s.northWestCornerColour)}"
                         title="North-west corner"></div>
                    <div class="stadium-plan-turf"></div>
                    <div class="stadium-plan-corner stadium-plan-corner--ne" style="background:${c(s.northEastCornerColour)}"
                         title="North-east corner"></div>
                </div>
                <div class="stadium-plan-strip stadium-plan-strip--east" style="background:${c(s.eastColour)}"
                     title="East stand"></div>
                <div class="stadium-plan-strip stadium-plan-strip--south" style="background:${c(s.southColour)}"
                     title="South stand"></div>
            </div>
            <div class="stadium-plan-legend">
                ${SIDES.concat(CORNERS).map(([key, label]) => `
                    <span class="stadium-legend-item">
                        <i style="background:${c(s[key + 'Colour'] || s[key + 'CornerColour'])}"></i>${esc(label)}
                    </span>`).join('')}
            </div>
        </div>`;
    }

    function picture(s) {
        const src = s.image || '/images/default-stadium.png';
        return `
        <div class="stadium-picture">
            <img src="${esc(src)}" alt="${esc(s.name || 'Stadium')}"
                 onerror="this.src='/images/default-stadium.png'">
        </div>`;
    }

    function colourField(key, label, value) {
        return `
        <label class="stadium-colour">
            <span>${esc(label)}</span>
            <input type="color" data-colour="${key}" value="${esc(normaliseHex(value))}">
        </label>`;
    }

    /** A colour input only accepts #rrggbb, so an unset part needs a real value to start from. */
    function normaliseHex(value) {
        if (typeof value === 'string' && /^#[0-9a-fA-F]{6}$/.test(value)) return value;
        if (typeof value === 'string' && /^#[0-9a-fA-F]{3}$/.test(value)) {
            return '#' + value.slice(1).split('').map((c) => c + c).join('');
        }
        return NEUTRAL;
    }

    // --- behaviour ---

    function wire(main, teamId) {
        main.querySelectorAll('[data-build]').forEach(btn => {
            btn.addEventListener('click', async () => {
                const note = main.querySelector('[data-build-note]');
                note.textContent = 'Working…';
                btn.disabled = true;
                try {
                    const res = await authFetch(`/api/teams/${teamId}/stadium/build`, {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({ action: btn.dataset.build, seats: 1000 }),
                    });
                    const body = await res.json();
                    const result = body.result || {};
                    note.textContent = result.refused
                        ? result.error
                        : buildMessage(btn.dataset.build, result);
                    await loadStadium();
                } catch (err) {
                    note.textContent = `That did not work. ${err.message || ''}`;
                }
            });
        });

        const paintBtn = main.querySelector('[data-paint]');
        if (paintBtn) {
            paintBtn.addEventListener('click', async () => {
                const note = main.querySelector('[data-paint-note]');
                const colours = {};
                main.querySelectorAll('[data-colour]').forEach(input => {
                    colours[input.dataset.colour] = input.value;
                });
                note.textContent = 'Saving…';
                try {
                    const res = await authFetch(`/api/teams/${teamId}/stadium/paint`, {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify(colours),
                    });
                    const body = await res.json();
                    const result = body.result || {};
                    note.textContent = result.refused ? result.error : `Saved ${result.coloursApplied} colour(s).`;
                    if (!result.refused) await loadStadium();
                } catch (err) {
                    note.textContent = `Those colours did not save. ${err.message || ''}`;
                }
            });
        }

        const maintBtn = main.querySelector('[data-maintenance]');
        if (maintBtn) {
            maintBtn.addEventListener('click', async () => {
                const note = main.querySelector('[data-maintenance-note]');
                const budget = main.querySelector('#stadium-maintenance-budget')?.value;
                note.textContent = 'Working…';
                try {
                    const res = await authFetch(`/api/teams/${teamId}/stadium/maintenance`, {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({ weeklyBudget: Number(budget || 0) }),
                    });
                    const body = await res.json();
                    note.textContent = body.pitchStatus
                        ? `Pitch work: ${esc(body.pitchStatus)}`
                        : 'The pitch work was accepted.';
                    await loadStadium();
                } catch (err) {
                    note.textContent = `That did not work. ${err.message || ''}`;
                }
            });
        }
    }

    function buildMessage(action, result) {
        if (action === 'expand') return `Expanded to ${(result.capacity || 0).toLocaleString()} seats.`;
        if (action === 'seats') return `Seat quality is now ${result.seatQuality}/20.`;
        if (action === 'roof') return 'The roof is on.';
        return 'Done.';
    }

    function loadingState() {
        return `<div class="fm-page fm-page--stadium"><section class="fm-panel"><p class="fm-subtle">Loading the ground…</p></section></div>`;
    }

    function emptyState(message) {
        return `<div class="fm-page fm-page--stadium"><section class="fm-panel"><div class="fm-empty">${esc(message)}</div></section></div>`;
    }

    return { loadStadium };
}

function formatMoney(value) {
    const n = Number(value);
    if (!Number.isFinite(n)) return '—';
    return n.toLocaleString(undefined, { maximumFractionDigits: 0 });
}
