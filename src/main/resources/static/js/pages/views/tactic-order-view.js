/**
 * The mid-match tactic orders (T-MATCH-6).
 *
 * <p>Owner ruling, 2026-10-12: *"Mogu se dodati 3 razlicite TAKTIKE. Za te 3 taktike mogu se dodati 10
 * REDOVA SA INSTRUKCIJAMA kad na koju da se predje tj 10 puta u toku utakmice igrac moze preci na drugu
 * taktiku."*
 *
 * <p>So this is the second half of that sentence and deliberately not the first. The three tactics live in
 * the game plan; these are the ten rows saying when to switch between them. The caps are separate numbers
 * and a separate screen, because one constant holding both is how "three tactics" quietly becomes ten.
 *
 * <p>Also stated on the screen, and not only in the refusal: a cap that is only enforced is a cap the
 * manager finds out about by being refused.
 */

const ENDPOINT = (fixtureId) => `/api/sim/fixtures/${fixtureId}/tactic-orders`;

/** The engine's conditions. The same six the plan uses, so an order and a slot are asked alike. */
export const ORDER_CONDITIONS = [
    { value: 'ALWAYS', label: 'Always' },
    { value: 'LEADING_BY_ONE', label: 'Leading by one' },
    { value: 'LEADING_BY_THREE', label: 'Leading by three or more' },
    { value: 'DRAWING', label: 'Drawing' },
    { value: 'TRAILING_BY_ONE', label: 'Trailing by one' },
    { value: 'TRAILING_BY_THREE', label: 'Trailing by three or more' },
];

import { apiErrorMessage } from '../../auth.js';

const esc = (value) => String(value ?? '').replace(/[&<>"']/g, (c) => (
    { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]
));

export function createTacticOrderView({ authFetch }) {

    /**
     * The club's tactics, held between renders.
     *
     * <p>Adding or removing an order repaints the list, and the repaint needs the tactic names again for
     * the dropdown. Keeping them in a closure is why re-rendering does not have to be handed the whole
     * context a second time - and why the first version, which stashed them in a data attribute it never
     * wrote, re-rendered into an empty dropdown.
     */
    let knownTactics = [];

    async function render(host, { fixtureId, tactics = [] }) {
        if (!host) return;
        if (tactics.length) knownTactics = tactics;
        const repaint = () => render(host, { fixtureId });
        let data;
        try {
            const res = await authFetch(ENDPOINT(fixtureId));
            data = await res.json();
        } catch (error) {
            host.innerHTML = `<p class="fm-setup-error">Orders could not be loaded:
                ${esc(error?.message || error)}</p>`;
            return;
        }

        const orders = data.orders || [];
        const cap = data.cap || 10;
        const full = orders.length >= cap;

        host.innerHTML = `
            <p class="fm-subtle" style="margin-bottom:12px;">
                Up to ${cap} orders, each switching to one of your own tactics. An order fires once, and
                the last matching order wins.
            </p>

            ${full ? `<p class="fm-setup-error">These are all ${cap} orders a match holds. Remove one
                before adding another.</p>` : ''}
            ${knownTactics.length === 0 ? `<p class="fm-setup-error">Your club has no tactics yet. Build
                one on the Tactics page and it can be ordered here.</p>` : ''}

            ${orders.length === 0
                ? `<p class="fm-empty">No orders. You play the tactic in your game plan all match.</p>`
                : `<ol class="fm-list">${orders.map(order => `
                    <li class="fm-tactic-order">
                        <span class="fm-tactic-order-text">
                            <strong>From minute ${esc(order.minuteFrom)}</strong>,
                            ${esc(order.conditionLabel || order.condition)} &rarr;
                            <strong>${esc(order.tacticName || 'a deleted tactic')}</strong>
                            <span class="fm-subtle">(${esc(order.formation || '—')})</span>
                        </span>
                        <button type="button" class="fm-action-btn secondary" data-order-remove="${order.id}">
                            Remove
                        </button>
                    </li>`).join('')}</ol>`}

            <div class="fm-tactic-order-add">
                <select class="fm-input" data-order-tactic>
                    ${knownTactics.map(t => `<option value="${t.id}">${esc(t.name)} (${esc(t.formation)})</option>`)
                        .join('')}
                </select>
                <select class="fm-input" data-order-condition>
                    ${ORDER_CONDITIONS.map(c => `<option value="${c.value}">${esc(c.label)}</option>`).join('')}
                </select>
                <input class="fm-input" type="number" min="0" max="90" value="60" data-order-minute
                       aria-label="Minute the order fires">
                <button type="button" class="fm-action-btn" data-order-add
                    ${full || knownTactics.length === 0 ? 'disabled' : ''}>
                    Add order
                </button>
            </div>
            <p class="fm-subtle" data-order-note></p>`;

        wire(host, fixtureId, repaint);
    }

    function wire(host, fixtureId, repaint) {
        const add = host.querySelector('[data-order-add]');
        if (add) {
            add.addEventListener('click', async () => {
                const tactic = host.querySelector('[data-order-tactic]').value;
                const condition = host.querySelector('[data-order-condition]').value;
                const minute = host.querySelector('[data-order-minute]').value;
                const note = host.querySelector('[data-order-note]');
                add.disabled = true;
                note.textContent = 'Saving…';
                try {
                    await authFetch(ENDPOINT(fixtureId), {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({
                            tacticId: Number(tactic), condition, minuteFrom: Number(minute),
                        }),
                    });
                    await repaint();
                } catch (error) {
                    // The refusal names the limit, and it is shown rather than swallowed: the cap is
                    // the point of this screen and a silent failure teaches nothing.
                    note.textContent = `Not saved: ${apiErrorMessage(error, '')}`;
                    add.disabled = false;
                }
            });
        }

        host.querySelectorAll('[data-order-remove]').forEach(button => {
            button.addEventListener('click', async () => {
                const id = button.dataset.orderRemove;
                button.disabled = true;
                try {
                    await authFetch(`${ENDPOINT(fixtureId)}/${id}`, { method: 'DELETE' });
                    await repaint();
                } catch (error) {
                    host.querySelector('[data-order-note]').textContent =
                        `Not removed: ${apiErrorMessage(error, '')}`;
                    button.disabled = false;
                }
            });
        });
    }

    return { render };
}