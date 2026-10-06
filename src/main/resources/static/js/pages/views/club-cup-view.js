/**
 * The three international club cups, one page each — P1-CUPS-4 (owner, 2026-10-06).
 *
 * <p>The World page listed Champions Cup, Masters Cup and Challenge Cup as three lines of text and had no
 * way into any of them. The owner asked for a page each, linked from the World page, with <b>a tab per
 * tier</b> and the results and the table on it.
 *
 * <p>Five tiers, three cups, fifteen competitions. Each cup page carries five tabs and each tab carries
 * that tier's group tables, its results and its bracket.
 *
 * <p>Every fetch goes through `authFetch` and every one checks `response.ok` before reading the body.
 * Several loaders in this codebase `await response.json()` without it, so a 404 or a 500 becomes a blank
 * panel rather than a message, and an error that looks like "no data yet" is the harder one to notice.
 */
// pages/views/club-cup-view.js
//
// A factory, not an IIFE: `authFetch`, `escapeHtml` and `loadPage` are injected by `pages.js`, because
// none of them is a global here. A module that reaches for `window.authFetch` is a module that works on
// the page where someone remembered to define it and nowhere else.

export const CUPS = [
    { key: 'champions', name: 'Champions Cup' },
    { key: 'masters', name: 'Masters Cup' },
    { key: 'challenge', name: 'Challenge Cup' }
];

export const TIERS = [1, 2, 3, 4, 5];

export function createClubCupView({ authFetch, escapeHtml, loadPage }) {
    /** Read the payload, or say why it could not be read. Never both. */
    async function readJson(url) {
        const response = await authFetch(url);
        if (!response.ok) {
            throw new Error(`The server answered ${response.status} for ${url}.`);
        }
        return response.json();
    }

    const escape = value => escapeHtml(value);

    // ------------------------------------------------------------------ markup

    function tierTabs(cupKey, activeTier, tiers) {
        return `
            <nav class="fm-player-tabs">
                ${TIERS.map(tier => {
                    const summary = tiers.find(t => Number(t.tier) === tier) || {};
                    const fixtures = Number(summary.fixtures || 0);
                    const state = String(summary.state || 'not-drawn');
                    const note = state === 'missing' ? 'missing'
                        : fixtures > 0 ? `${fixtures} ties`
                        : state === 'no-finished-season' ? 'not qualified yet'
                        : 'not drawn';
                    const active = tier === activeTier ? ' is-active' : '';
                    return `<button type="button" class="fm-player-tab${active}"
                                data-cup-tier="${tier}"
                                title="${escape(note)}">Tier ${tier}</button>`;
                }).join('')}
            </nav>`;
    }

    /** One group: its table and its five matchdays. */
    function groupPanel(group) {
        const rows = Array.isArray(group.table) ? group.table : [];
        const ties = Array.isArray(group.fixtures) ? group.fixtures : [];

        return `
            <section class="fm-panel">
                <div class="fm-panel-head">
                    <div>
                        <h3>Group ${escape(group.code)}</h3>
                        <p class="fm-subtle">${ties.length} ties · ${
                            ties.filter(t => t.played).length} played</p>
                    </div>
                </div>
                ${tableOf(rows)}
                <div class="fm-squad-wrap">
                    <table class="fm-squad">
                        <thead>
                            <tr>
                                <th>Round</th><th>Home</th><th class="st-rating">Score</th><th>Away</th>
                            </tr>
                        </thead>
                        <tbody>
                            ${ties.length === 0
                                ? '<tr><td colspan="4" class="fm-empty">No ties drawn.</td></tr>'
                                : ties.map(tieRow).join('')}
                        </tbody>
                    </table>
                </div>
            </section>`;
    }

    function tableOf(rows) {
        if (!rows.length) {
            return '<div class="fm-empty">No clubs in this group yet.</div>';
        }
        return `
            <div class="fm-squad-wrap">
                <table class="fm-squad fm-league-table">
                    <thead>
                        <tr>
                            <th class="st-pos">#</th><th>Club</th><th>Country</th>
                            <th>P</th><th>W</th><th>D</th><th>L</th>
                            <th class="st-rating">GF</th><th class="st-rating">GA</th>
                            <th class="st-rating">GD</th><th class="st-rating">Pts</th>
                        </tr>
                    </thead>
                    <tbody>
                        ${rows.map(row => `
                            <tr>
                                <td class="st-pos">${escape(row.position)}</td>
                                <td class="sq-name">${escape(row.team)}</td>
                                <td>${escape(row.country || '—')}</td>
                                <td>${escape(row.played)}</td>
                                <td>${escape(row.wins)}</td>
                                <td>${escape(row.draws)}</td>
                                <td>${escape(row.losses)}</td>
                                <td class="st-rating">${escape(row.goalsScored)}</td>
                                <td class="st-rating">${escape(row.goalsConceded)}</td>
                                <td class="st-rating">${escape(row.goalDifference)}</td>
                                <td class="st-rating"><strong>${escape(row.points)}</strong></td>
                            </tr>`).join('')}
                    </tbody>
                </table>
            </div>`;
    }

    function tieRow(tie) {
        const score = tie.played
            ? `${escape(tie.homeGoals ?? '')} – ${escape(tie.awayGoals ?? '')}`
            : 'v';
        return `
            <tr>
                <td>Matchday ${escape(tie.round)}</td>
                <td>${escape(tie.home || 'TBC')}</td>
                <td class="fm-cup-final-score">${score}</td>
                <td>${escape(tie.away || 'TBC')}</td>
            </tr>`;
    }

    /** The bracket: one block per round, final last. */
    function knockoutPanel(rounds) {
        if (!rounds.length) {
            return '';
        }
        return `
            <section class="fm-panel">
                <div class="fm-panel-head">
                    <div>
                        <h3>Knockout</h3>
                        <p class="fm-subtle">Round of 16, quarter-finals, semi-finals, third place and the
                            final. Week 10 carries the last two.</p>
                    </div>
                </div>
                ${rounds.map(round => `
                    <div class="fm-cup-round">
                        <div class="fm-cup-round-head">
                            <span>${escape(round.name || `Round ${round.round}`)}</span>
                            <span class="fm-cup-round-count">Week ${escape(round.week ?? '—')} ·
                                ${round.fixtures.length} ${round.fixtures.length === 1 ? 'tie' : 'ties'}</span>
                        </div>
                        <div class="fm-cup-ties">
                            ${round.fixtures.length === 0
                                ? '<div class="fm-empty">Not drawn yet.</div>'
                                : round.fixtures.map(tie => `
                                    <div class="fm-cup-tie">
                                        <span>${escape(tie.home || 'TBC')}</span>
                                        <span class="fm-cup-tie-sep">${tie.played
                                            ? `${escape(tie.homeGoals ?? '')} – ${escape(tie.awayGoals ?? '')}`
                                            : 'v'}</span>
                                        <span>${escape(tie.away || 'TBC')}</span>
                                    </div>`).join('')}
                        </div>
                    </div>`).join('')}
            </section>`;
    }

    // ------------------------------------------------------------------ page

    async function loadClubCup(cupKey, tier) {
        const cup = CUPS.find(c => c.key === cupKey);
        const mainContent = document.getElementById('main-content');
        if (!cup) {
            mainContent.innerHTML = `<div class="manager-card"><h2>Unknown cup</h2>
                <p>These are the ${CUPS.map(c => c.name).join(', ')}.</p></div>`;
            return;
        }
        const activeTier = TIERS.includes(Number(tier)) ? Number(tier) : 1;

        try {
            const index = await readJson('/club-cups');
            const tiers = (index.cups || []).find(c => c.key === cupKey)?.tiers || [];
            const detail = await readJson(`/club-cups/${encodeURIComponent(cupKey)}?tier=${activeTier}`);

            const groups = Array.isArray(detail.groups) ? detail.groups : [];
            const rounds = Array.isArray(detail.knockoutRounds) ? detail.knockoutRounds : [];
            const played = Number(detail.playedFixtures || 0);
            const total = Number(detail.totalFixtures || 0);

            mainContent.innerHTML = `
                <div class="fm-page">
                    <div class="fm-page-head">
                        <button type="button" class="back-to-dashboard" data-nav-back>Back</button>
                        <div>
                            <h2>${escape(cup.name)}</h2>
                            <p class="fm-subtle">Tier ${activeTier} · season ${escape(detail.seasonYear)} ·
                                ${total} ties, ${played} played</p>
                        </div>
                    </div>
                    ${tierTabs(cupKey, activeTier, tiers)}
                    ${groups.length === 0 && rounds.length === 0
                        ? `<section class="fm-panel"><div class="fm-empty">
                               This tier has not been drawn yet. It is filled in week 1, from the table
                               each country's divisions finished on.
                           </div></section>`
                        : ''}
                    ${groups.map(groupPanel).join('')}
                    ${knockoutPanel(rounds)}
                </div>`;

            mainContent.querySelectorAll('[data-cup-tier]').forEach(button => {
                button.addEventListener('click', () => {
                    loadPage('clubCup', { cupKey, tier: Number(button.dataset.cupTier) });
                });
            });
        } catch (err) {
            console.error('Failed to load the club cup page:', err);
            mainContent.innerHTML = `<div class="manager-card"><h2>${escape(cup.name)}</h2>
                <p>Could not load this competition. ${escape(err.message)}</p></div>`;
        }
    }

    return { loadClubCup };
}