import { htmlEscape, formatPercent } from './utils.js';

/**
 * The stadium page: the eight sections a ground is built from, each with its own seating type, capacity,
 * roof and ticket price; plus the pitch maintenance programme, the colours, and the ground's own picture.
 *
 * <p>None of this existed. The club profile had an "Open Stadium View" button that opened a
 * hard-coded image of somebody else's ground in a new tab, and the endpoints that could tell a
 * manager what their ground cost to run were never called from the frontend at all.
 *
 * <p>The plan is drawn rather than described: a pitch with the four sides and four corners coloured in
 * place, so what you set is what you see. It is the same idea as a tactics board and it costs one
 * <code>conic-gradient</code>.
 *
 * <p>Building is two clicks, never one (owner, 2026-10-07): <b>quote</b> answers what the work costs and
 * how many weeks that one section takes no fans, and only <b>yes</b> spends anything. The old page took the
 * whole budget on the click and reported the closure afterwards, which is the one order in which a manager
 * cannot do anything about it.
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

/** Kept in step with StadiumImageService.DEFAULT_STADIUM_IMAGE on the server. */
const DEFAULT_STADIUM_IMAGE = '/images/dunjareal.png';

export function createStadiumView(deps) {
    const { authFetch, getTeamId, formatBudget } = deps;
    let esc = typeof htmlEscape === 'function' ? htmlEscape : (v) => String(v ?? '');

    /** Which section's build form is open, if any. One at a time: this is a money decision. */
    let openPosition = null;

    /** The server's own list of seating types, so a new one cannot need a change here. */
    let seatingTypes = [];

    /**
     * The last thing that happened, kept across the reload.
     *
     * <p>Writing it into the panel and then reloading the panel threw the message away, so a build
     * finished and the page said nothing at all. Found in the browser, not by a test.
     */
    let lastMessage = null;

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

        lastPayload = data;
        openPosition = null;
        main.innerHTML = render(data, s);
        wire(main, teamId);
    }

    // --- rendering ---

    function render(data, s) {
        const cap = s.capacity ?? 0;
        const seats = s.seatQuality ?? 10;
        const sectionList = s.sections || [];
        const prices = s.prices || {};
        seatingTypes = s.seatingTypes || [];

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
                    <div><strong>${esc(roofedOf(sectionList))}</strong><span>Roofed</span></div>
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
                    <p class="fm-subtle">
                        Eight sections: four sides and four corners. Capacity ${cap.toLocaleString()}${
                            s.expandableTo ? ` of ${Number(s.expandableTo).toLocaleString()}` : ''
                        } is the sum of what you have built.
                    </p>
                    <div class="club-profile-detail-list">
                        <div class="club-profile-detail-row">
                            <span>Built sections</span>
                            <strong>${builtOf(sectionList)} of 8</strong>
                        </div>
                        <div class="club-profile-detail-row">
                            <span>Roofed sections</span>
                            <strong>${esc(roofedOf(sectionList))}</strong>
                        </div>
                        <div class="club-profile-detail-row">
                            <span>Seat quality</span>
                            <strong>${seats}/20 — what is actually built</strong>
                        </div>
                    </div>
                    <p class="fm-subtle">Choose a section under each stand to build it out or price it.</p>
                </section>
            </div>

            <section class="fm-panel">
                <div class="fm-panel-head">The eight sections</div>
                <p class="fm-subtle">
                    Four sides and four corners, each built on its own: a seating type, seats to add, and a
                    roof over that section only. Each one is priced separately, with a recommendation.
                </p>
                <div class="stadium-section-list">
                    ${sectionList.map(section => sectionRow(section, prices)).join('')
                        || '<p class="fm-subtle">No sections yet.</p>'}
                </div>
                <p class="fm-subtle" data-section-note>${esc(lastMessage || '')}</p>
            </section>

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


    /** How many of the eight sections hold seats. */
    function builtOf(list) {
        return list.filter(s => Number(s.capacity) > 0).length;
    }

    /** "3 of 8" — a roof is one section's, so a single number is no longer the answer. */
    function roofedOf(list) {
        return `${list.filter(s => s.roof).length} of ${list.length || 8}`;
    }

    /**
     * One section: what it is, what it charges, and the button that opens its build form.
     *
     * <p>The price sits next to the recommendation rather than in a settings screen, because the owner's
     * decision is eight prices, not one. A section with no seats has no price to sell and says so.
     */
    function sectionRow(section, prices) {
        const built = Number(section.capacity) > 0;
        const standard = Number(prices?.STANDARD ?? 0);
        // A section nobody has priced sells at the ground's own standard price, so that is what the box
        // shows. An empty box beside "Recommended 20" reads as "nothing to sell here", which is a lie.
        const charged = section.ticketPrice != null ? section.ticketPrice : standard;
        const state = [
            built ? `${Number(section.capacity).toLocaleString()} seats` : 'not built',
            section.seatingLabel || '—',
            section.roof ? 'roofed' : 'no roof',
        ].join(' · ');
        const closed = section.closedUntilWeek != null
            ? ` · closed until week ${section.closedUntilWeek}`
            : '';
        const recommended = section.recommendedPrice != null
            ? esc(formatMoney(section.recommendedPrice))
            : '—';
        return `
        <div class="stadium-section" data-section="${esc(section.position)}">
            <div class="stadium-section-head">
                <strong>${esc(section.label)}</strong>
                <span class="fm-subtle">${esc(state)}${esc(closed)}</span>
            </div>
            <div class="stadium-section-controls">
                <label class="stadium-section-price">
                    <span>Price</span>
                    <input type="number" min="0" step="0.5" data-price-input="${esc(section.position)}"
                           value="${built ? esc(charged) : ''}"
                           ${built ? '' : 'disabled'} aria-label="Ticket price for the ${esc(section.label)}">
                </label>
                <span class="fm-subtle">Recommended ${recommended}${
                    built && section.ticketPrice == null ? ' · not set, so the standard price applies' : ''}</span>
                <button type="button" class="fm-action-btn secondary" data-price-save="${esc(section.position)}"
                        ${built ? '' : 'disabled'}>Save price</button>
                <button type="button" class="fm-action-btn" data-section-open="${esc(section.position)}">
                    ${built ? 'Extend' : 'Build'}</button>
            </div>
            ${openPosition === section.position ? buildForm(section) : ''}
        </div>`;
    }

    /**
     * The three choices for one section, and then the quote before the money.
     *
     * <p>Two steps, deliberately. The owner's question is what it costs and how long that stand is out,
     * and a single button cannot answer the second one — it can only tell you afterwards.
     */
    function buildForm(section) {
        const types = seatingTypes.length ? seatingTypes : [{ name: 'SEATS', label: 'Seats' }];
        const selected = section.seatingType || 'SEATS';
        return `
        <div class="stadium-section-form">
            <label class="stadium-section-field">
                <span>Seating</span>
                <select data-seating-input="${esc(section.position)}">
                    ${types.map(t => `
                        <option value="${esc(t.name)}"${t.name === selected ? ' selected' : ''}>${esc(t.label)}</option>
                    `).join('')}
                </select>
            </label>
            <label class="stadium-section-field">
                <span>Seats to add</span>
                <input type="number" min="0" step="250" value="1000"
                       data-capacity-input="${esc(section.position)}"
                       aria-label="Seats to add to the ${esc(section.label)}">
            </label>
            <label class="stadium-section-field stadium-section-field--check">
                <input type="checkbox" data-roof-input="${esc(section.position)}"${section.roof ? ' checked' : ''}>
                <span>Roof this section only</span>
            </label>
            <div class="fm-club-actions">
                <button type="button" class="fm-action-btn" data-section-quote="${esc(section.position)}">Quote it</button>
                <button type="button" class="fm-action-btn secondary" data-section-close>Cancel</button>
            </div>
            <div class="stadium-section-quote" data-quote-for="${esc(section.position)}"></div>
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

    /**
     * The ground's picture, and the control that replaces it (owner, 2026-09-28).
     *
     * <p>The fallback is a photograph of a real ground, not the generated placeholder. A club without
     * artwork should look like a football ground; a grey gradient reads as a broken image, and so did
     * the broken-image glyph before that.
     *
     * <p>The file input is styled rather than hidden, because an invisible input is impossible to
     * use with a keyboard and impossible to explain to anyone who did not build it.
     */
    function picture(s) {
        const src = s.image || DEFAULT_STADIUM_IMAGE;
        return `
        <div class="stadium-picture">
            <img src="${esc(src)}" alt="${esc(s.name || 'Stadium')}"
                 onerror="this.src='${DEFAULT_STADIUM_IMAGE}'">
            <div class="stadium-picture-actions">
                <label class="stadium-upload">
                    <span>Change picture</span>
                    <input type="file" accept="image/png,image/jpeg,image/webp,image/gif"
                           data-stadium-image-input>
                </label>
                <span class="stadium-upload-note" data-stadium-image-note>PNG, JPEG, WEBP or GIF, up to 4&nbsp;MB.</span>
            </div>
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
        wireSections(main, teamId);
        wireColours(main, teamId);
        wirePicture(main, teamId);
        wireMaintenance(main, teamId);
    }

    /** The three choices per section, the quote, the yes/no, and the eight prices. */
    function wireSections(main, teamId) {
        const note = main.querySelector('[data-section-note]');
        const say = (text) => {
            lastMessage = text || null;
            if (note) note.textContent = text || '';
        };

        main.querySelectorAll('[data-section-open]').forEach(btn => {
            btn.addEventListener('click', () => {
                openPosition = openPosition === btn.dataset.sectionOpen ? null : btn.dataset.sectionOpen;
                lastMessage = null;
                rerender();
            });
        });

        const close = main.querySelector('[data-section-close]');
        if (close) {
            close.addEventListener('click', () => {
                openPosition = null;
                lastMessage = null;
                rerender();
            });
        }

        main.querySelectorAll('[data-price-save]').forEach(btn => {
            btn.addEventListener('click', async () => {
                const position = btn.dataset.priceSave;
                const input = main.querySelector(`[data-price-input="${position}"]`);
                const price = Number(input?.value);
                if (!Number.isFinite(price) || price < 0) {
                    say('A ticket price cannot be negative.');
                    return;
                }
                say('Saving the price…');
                const body = await postJson(`/api/teams/${teamId}/stadium/sections/price`, { position, price });
                if (body.error) return say(body.error);
                if (body.ok === false) return say(body.reason);
                say(`The ${body.section || position} section now charges ${formatMoney(body.price)}.`);
                await loadStadium();
            });
        });

        main.querySelectorAll('[data-section-quote]').forEach(btn => {
            btn.addEventListener('click', async () => {
                const position = btn.dataset.sectionQuote;
                const out = main.querySelector(`[data-quote-for="${position}"]`);
                out.innerHTML = '<p class="fm-subtle">Working out the price…</p>';
                const quote = await postJson(`/api/teams/${teamId}/stadium/sections/quote`, choice(main, position));
                // A refusal belongs where the reader is looking. It used to go to a note at the bottom
                // of the panel, which left the form looking like nothing had happened.
                if (quote.error) {
                    out.innerHTML = `<p class="fm-subtle">${esc(quote.error)}</p>`;
                    return say(quote.error);
                }
                if (quote.ok === false) {
                    out.innerHTML = `<p class="fm-subtle">${esc(quote.reason || 'That cannot be quoted.')}</p>`;
                    return say(quote.reason || 'That cannot be quoted.');
                }
                // The quote, and then an explicit yes or no. Nothing is spent on the first click.
                out.innerHTML = quoteHtml(quote) + `
                    <div class="fm-club-actions">
                        <button type="button" class="fm-action-btn" data-section-confirm="${esc(position)}">Yes, build it</button>
                        <button type="button" class="fm-action-btn secondary" data-section-close>No</button>
                    </div>`;
                const confirm = out.querySelector('[data-section-confirm]');
                confirm.addEventListener('click', async () => {
                    confirm.disabled = true;
                    confirm.textContent = 'Building…';
                    const built = await postJson(`/api/teams/${teamId}/stadium/sections/build`, choice(main, position));
                    const result = built.result || {};
                    if (built.error || result.ok === false || result.refused) {
                        const why = built.error || result.reason || result.error || 'That could not be built.';
                        out.innerHTML = `<p class="fm-subtle">${esc(why)}</p>`;
                        confirm.disabled = false;
                        confirm.textContent = 'Yes, build it';
                        return say(why);
                    }
                    say(`${result.section} is closed for ${result.weeksClosed} week(s) and the ground `
                        + `now holds ${Number(result.newCapacity || 0).toLocaleString()} seats.`);
                    openPosition = null;
                    await loadStadium();
                });
            });
        });
    }

    /** The three choices currently shown for one section, as the server wants them. */
    function choice(main, position) {
        return {
            position,
            seatingType: main.querySelector(`[data-seating-input="${position}"]`)?.value || 'SEATS',
            capacityToAdd: Number(main.querySelector(`[data-capacity-input="${position}"]`)?.value || 0),
            roof: !!main.querySelector(`[data-roof-input="${position}"]`)?.checked,
        };
    }

    /** The owner's two answers, before any money moves. */
    function quoteHtml(quote) {
        const lines = [
            ['Price', formatBudget(quote.cost || 0)],
            ['Seats', Number(quote.capacityToAdd || 0).toLocaleString()],
            ['Closed to fans', `${quote.weeksClosed} week(s), back in week ${quote.closedUntilWeek}`],
            ['Ground afterwards', Number(quote.totalCapacityAfter || 0).toLocaleString()],
            ['This section afterwards', Number(quote.sectionCapacityAfter || 0).toLocaleString()],
            ['Recommended price', formatMoney(quote.recommendedPrice)],
        ];
        if (quote.roofCost > 0) lines.push(['of which the roof', formatBudget(quote.roofCost)]);
        return `
        <div class="club-profile-detail-list">
            ${lines.map(([k, v]) => `
                <div class="club-profile-detail-row"><span>${esc(k)}</span><strong>${esc(v)}</strong></div>
            `).join('')}
        </div>
        <p class="fm-subtle">${esc(quote.note || '')}</p>`;
    }

    function wireColours(main, teamId) {
        const btn = main.querySelector('[data-paint]');
        if (!btn) return;
        btn.addEventListener('click', async () => {
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

    /**
     * The ground's picture upload.
     *
     * <p>Its own wiring rather than a block inside the colour handler, where it used to sit: it read a
     * variable that does not exist in this module, so saving a colour threw a ReferenceError before the
     * colours were ever sent, and the picture control was never attached at all.
     */
    function wirePicture(main, teamId) {
        const input = main.querySelector('[data-stadium-image-input]');
        if (!input) return;
        input.addEventListener('change', async () => {
            const file = input.files && input.files[0];
            if (!file) return;
            const note = main.querySelector('[data-stadium-image-note]');
            // FormData, not JSON: the file has to go as multipart so the browser streams it and the
            // server can check its type. Setting Content-Type by hand here would omit the multipart
            // boundary and the request would arrive unparseable.
            const body = new FormData();
            body.append('file', file);
            if (note) note.textContent = 'Uploading…';
            try {
                const res = await authFetch(`/api/teams/${teamId}/stadium/image`, { method: 'POST', body });
                const payload = await res.json().catch(() => ({}));
                if (note) note.textContent = res.ok ? 'Saved.' : (payload.error || 'That picture did not upload.');
                if (res.ok) await loadStadium();
            } catch (err) {
                if (note) note.textContent = `That picture did not upload. ${err.message || ''}`;
            } finally {
                input.value = '';
            }
        });
    }

    function wireMaintenance(main, teamId) {
        const btn = main.querySelector('[data-maintenance]');
        if (!btn) return;
        btn.addEventListener('click', async () => {
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

    /** One POST as JSON, with the status checked. A 403 is a message, not a crash. */
    async function postJson(url, payload) {
        const res = await authFetch(url, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload),
        });
        const body = await res.json().catch(() => ({}));
        if (!res.ok) return { error: body.error || `That did not work (${res.status}).` };
        return body;
    }

    /** The payload this page last loaded, so a re-render does not need another request. */
    let lastPayload = null;

    /** Re-renders the ground from the payload in hand, keeping the open build form open. */
    function rerender() {
        const main = document.getElementById('main-content');
        if (!main || !lastPayload) return;
        main.innerHTML = render(lastPayload, lastPayload.stadium);
        wire(main, getTeamId());
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
