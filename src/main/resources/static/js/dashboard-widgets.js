/**
 * The dashboard widget rail (redesign.md R-13).
 *
 * <p>Five tiles in one system: Next Match, Balance, League position, Squad condition, Stadium.
 * Each answers one question about the club without a click, and **every tile opens the page that
 * answers it properly** - the owner's rule, and the reason a tile is a shortcut rather than a
 * decoration:
 *
 * <ul>
 *   <li>Next Match → the match itself, on its Preview tab.</li>
 *   <li>Balance → the finances.</li>
 *   <li>League position → the league table.</li>
 *   <li>Squad condition → the medical centre.</li>
 *   <li>Stadium → the ground.</li>
 * </ul>
 *
 * <p>Three of the five are fed by loads the dashboard was already making - the next match, the
 * league table and the medical overview - so the rail adds **two** requests, not five. The two it
 * adds (finances and the stadium) are the two the dashboard had no data for at all.
 *
 * <p>Nothing here blocks the dashboard. A tile that cannot load shows a dash and stays a dash:
 * a widget that waits for a slow endpoint holds the whole page hostage, and the page behind it is
 * the one the manager came for.
 */

/** The tiles, in rail order. */
export const DASHBOARD_WIDGETS = [
    { key: 'position', label: 'League position', icon: '🏆', page: 'leagueTable' },
    { key: 'stadium', label: 'Stadium', icon: '🏟', page: 'stadium' },
    { key: 'budget', label: 'Budget', icon: '€', page: 'finances' },
    { key: 'condition', label: 'Squad condition', icon: '✚', page: 'medicalCenter' }
];

/**
 * The next three fixtures, as three tiles that are exactly alike (owner, 2026-10-20: "ostane onakav
 * kakav je bio widget i jos dva ista takva ispod").
 *
 * <p>They live in their own container rather than in the rail, because the two screens want them in
 * different shapes: on a desktop they are three rows of the first column, and on a phone they are a
 * swipeable strip - one fixture per screen, with the counter saying which one.
 */
export const NEXT_MATCH_WIDGETS = [
    { key: 'nextMatch', label: 'Next match', icon: '⚽', page: 'schedule', counter: '1/3' },
    { key: 'nextMatch2', label: 'Next match', icon: '⚽', page: 'schedule', counter: '2/3', hidden: true },
    { key: 'nextMatch3', label: 'Next match', icon: '⚽', page: 'schedule', counter: '3/3', hidden: true }
];

/**
 * The rail's markup.
 *
 * @returns {string} HTML
 */
function widgetHtml(widget) {
    return `
        <article class="fm-widget${widget.wide ? ' fm-widget--wide' : ''}${widget.hidden ? ' is-hidden' : ''}"
                 data-widget="${widget.key}"
                 data-widget-page="${widget.page}">
            <header class="fm-widget-head">
                <span class="fm-widget-icon" aria-hidden="true">${widget.icon}</span>
                <span class="fm-widget-label">${widget.label}</span>
                ${widget.counter ? `<span class="fm-widget-counter">${widget.counter}</span>` : ''}
                <span class="fm-widget-chip" data-widget-chip hidden></span>
            </header>
            <div class="fm-widget-value" data-widget-value>—</div>
            <div class="fm-widget-meta" data-widget-meta>Loading…</div>
            <div class="fm-widget-type" data-widget-type hidden></div>
            <div class="fm-widget-note" data-widget-note hidden></div>
            <div class="fm-widget-extra" data-widget-extra hidden></div>
        </article>`;
}

export function widgetRailHtml() {
    return `
        <aside class="fm-widget-rail" aria-label="Club at a glance">
            ${DASHBOARD_WIDGETS.map(widgetHtml).join('')}
        </aside>`;
}

/**
 * The next three fixtures, in their own container.
 *
 * <p>On a desktop the container dissolves and the three tiles become rows of the dashboard's first
 * column; on a phone it is a horizontal strip and the tiles are one screen each.
 */
export function nextMatchesHtml() {
    return `
        <div class="fm-dashboard-next" data-next-matches aria-label="The next fixtures">
            ${NEXT_MATCH_WIDGETS.map(widgetHtml).join('')}
        </div>`;
}

/**
 * Fills one tile.
 *
 * @param {string} key the widget key
 * @param {object} value
 * @param {string} value.value the big number
 * @param {string} [value.meta] the line under it
 * @param {string} [value.note] a second line - the head-to-head history, for instance
 * @param {string} [value.noteTone] 'pos' | 'neg' - the note's own colour
 * @param {string} [value.type] the kind of match - league, cup, international
 * @param {string} [value.extra] extra rows under the tile (the next fixtures, say)
 * @param {string} [value.chipHtml] a small chip in the tile's head (the Elo, say)
 * @param {string} [value.tone] 'pos' | 'neg' | 'warn' | null
 * @param {string} [value.page] where the tile opens
 * @param {number} [value.fixtureId] opens that match instead of a page
 */
export function updateWidget(key, value = {}) {
    const tile = document.querySelector(`[data-widget="${key}"]`);
    if (!tile) return;
    // A tile with nothing to say is not shown at all: the second and the third next-match tiles
    // only appear when there is a second and a third fixture.
    if (value.value == null || value.value === '' || value.value === '—') {
        tile.classList.add('is-hidden');
        return;
    }
    tile.classList.remove('is-hidden');
    const valueNode = tile.querySelector('[data-widget-value]');
    const metaNode = tile.querySelector('[data-widget-meta]');
    const typeNode = tile.querySelector('[data-widget-type]');
    const noteNode = tile.querySelector('[data-widget-note]');
    const extraNode = tile.querySelector('[data-widget-extra]');
    const chipNode = tile.querySelector('[data-widget-chip]');
    if (valueNode) valueNode.textContent = value.value ?? '—';
    if (metaNode) metaNode.textContent = value.meta ?? '';
    if (typeNode) {
        const kind = value.type ?? '';
        typeNode.textContent = kind;
        typeNode.hidden = !kind;
    }
    if (noteNode) {
        const note = value.note ?? '';
        noteNode.textContent = note;
        noteNode.hidden = !note;
        // The note carries its own tone, which is not the tile's: the balance line is green or red by
        // its sign while the budget above it stays white (owner, 2026-10-20: "budget je BELE boje bez
        // obzira na weekly balance ... a balans je zelen/crven u zavisnosti je li plus ili minus").
        noteNode.classList.remove('fm-widget-note--pos', 'fm-widget-note--neg');
        if (value.noteTone === 'pos' || value.noteTone === 'neg') {
            noteNode.classList.add(`fm-widget-note--${value.noteTone}`);
        }
    }
    if (extraNode) {
        extraNode.innerHTML = value.extra ?? '';
        extraNode.hidden = !value.extra;
    }
    if (chipNode) {
        // The chip carries a number and a sign, and it is written as HTML because the sign is
        // coloured - the same green and red the rating deltas use everywhere else.
        chipNode.innerHTML = value.chipHtml ?? '';
        chipNode.hidden = !value.chipHtml;
    }
    tile.classList.remove('fm-widget--pos', 'fm-widget--neg', 'fm-widget--warn');
    if (value.tone) tile.classList.add(`fm-widget--${value.tone}`);
    if (value.page) tile.dataset.widgetPage = value.page;
    if (value.fixtureId) {
        tile.dataset.widgetFixture = String(value.fixtureId);
    } else {
        delete tile.dataset.widgetFixture;
    }
    tile.classList.add('is-loaded');
}

/**
 * Wires the tiles: a tap opens what the tile shows.
 *
 * <p>Delegated from the rail, because the rail is re-rendered with the dashboard and a listener per
 * tile would be a listener per tile per render.
 *
 * @param {HTMLElement} rail
 * @param {object} [options]
 * @param {Function} [options.navigate] called with a page key; defaults to the global loadPage
 * @param {Function} [options.openMatch] called with a fixture id
 */
export function bindWidgets(rail, options = {}) {
    if (!rail) return;
    const { navigate = null, openMatch = null } = options;
    rail.addEventListener('click', event => {
        const tile = event.target.closest?.('.fm-widget');
        if (!tile) return;

        const fixtureId = Number(tile.dataset.widgetFixture);
        if (fixtureId && typeof openMatch === 'function') {
            openMatch(fixtureId);
            return;
        }

        const page = tile.dataset.widgetPage;
        if (!page) return;
        const go = navigate || (typeof window.loadPage === 'function' ? window.loadPage : null);
        if (go) go(page);
    });
}

/**
 * The club's ground, when the stadium endpoint has no record of one.
 *
 * <p>Some clubs play in a ground that has no stadium record, and the tile then said it had no
 * ground - which the owner knows to be false (owner, 2026-10-19: "mora da ima, znam da ima").
 * The venue of a home fixture is the club's ground, so that is the fallback, and it only fills a
 * tile that is still empty: a real stadium record always wins.
 *
 * @param {string} name the ground's name
 */
export function setStadiumFallback(name) {
    const tile = document.querySelector('[data-widget="stadium"]');
    if (!tile || !name) return;
    const valueNode = tile.querySelector('[data-widget-value]');
    if (!valueNode || valueNode.textContent.trim() !== '—') return;
    updateWidget('stadium', { value: name, meta: 'Your ground' });
}

/**
 * The two loads the dashboard had no data for: the finances and the ground.
 *
 * <p>Both are optional. A failure leaves the tile on a dash and says so, because a tile that
 * lies about a missing number is worse than a tile that admits it.
 *
 * @param {object} options
 * @param {number} options.teamId
 * @param {Function} options.authFetch
 * @param {number} [options.seasonYear]
 * @returns {Promise<void>} resolves when both tiles have been attempted
 */
export async function loadWidgetData({ teamId, authFetch, seasonYear = null } = {}) {
    if (!teamId || typeof authFetch !== 'function') return;

    const seasonQuery = seasonYear ? `?seasonYear=${seasonYear}` : '';
    const get = async url => {
        try {
            const response = await authFetch(url);
            return response.ok ? await response.json() : null;
        } catch (error) {
            return null;
        }
    };

    const [finances, stadium, ledger] = await Promise.all([
        get(`/api/teams/${teamId}/finances${seasonQuery}`),
        get(`/api/teams/${teamId}/stadium`),
        // The week's own lines, for the weekly balance the tile shows under the budget.
        get(`/api/teams/${teamId}/finances/ledger${seasonQuery}`)
    ]);

    if (finances) {
        const budget = Number(finances.budget);
        const week = Number(finances.currentWeek);
        const season = Number(finances.seasonYear);
        // The weekly balance: this week's income minus this week's costs, from the ledger lines
        // the finances page already reads.
        const weekLines = (Array.isArray(ledger) ? ledger : []).filter(line =>
            Number(line.week) === week && Number(line.seasonYear) === season);
        const weekIn = weekLines.filter(line => line.income)
            .reduce((sum, line) => sum + Number(line.amount || 0), 0);
        const weekOut = weekLines.filter(line => !line.income)
            .reduce((sum, line) => sum + Math.abs(Number(line.amount || 0)), 0);
        const weeklyBalance = weekLines.length ? weekIn - weekOut : null;

        // The owner, 2026-10-20: the big letters say **Budget** (not Balance), in bold, and under
        // them the weekly balance - red when it is minus, green when it is zero or plus - and the
        // season and week.
        updateWidget('budget', {
            value: Number.isFinite(budget) ? formatMoney(budget) : '—',
            meta: [
                Number.isFinite(season) ? `Season ${season}` : null,
                Number.isFinite(week) ? `Week ${week}` : null
            ].filter(Boolean).join(' · '),
            note: weeklyBalance === null
                ? 'Weekly balance not settled yet'
                : `Weekly balance ${formatMoney(weeklyBalance)}`,
            // The balance line is green or red by its sign; the budget above it stays white (owner,
            // 2026-10-20: "budget je BELE boje bez obzira na weekly balance ... a balans je
            // zelen/crven u zavisnosti je li plus ili minus").
            noteTone: weeklyBalance === null ? null : (weeklyBalance < 0 ? 'neg' : 'pos'),
            // And the **budget** above it is white whatever the balance says, red only when the
            // budget itself is negative (owner, 2026-10-20).
            tone: Number.isFinite(budget) && budget < 0 ? 'neg' : null
        });
    } else {
        updateWidget('budget', { value: '—', meta: 'Budget unavailable' });
    }

    if (stadium && stadium.stadium) {
        const ground = stadium.stadium;
        const capacity = Number(ground.capacity);
        updateWidget('stadium', {
            value: ground.name || 'Your ground',
            meta: Number.isFinite(capacity) && capacity > 0
                ? `${capacity.toLocaleString()} seats`
                : 'Your ground'
        });
    }
}

/** Compact money: 1.2M, 850K, 940. */
function formatMoney(value) {
    const numeric = Number(value);
    if (!Number.isFinite(numeric)) return '—';
    const sign = numeric < 0 ? '-' : '';
    const absolute = Math.abs(numeric);
    if (absolute >= 1_000_000) return `${sign}${trimZero(absolute / 1_000_000)}M`;
    if (absolute >= 1_000) return `${sign}${trimZero(absolute / 1_000)}K`;
    return `${sign}${trimZero(absolute)}`;
}

function trimZero(value) {
    const rounded = Math.round(value * 10) / 10;
    return Number.isInteger(rounded) ? String(rounded) : String(rounded);
}
