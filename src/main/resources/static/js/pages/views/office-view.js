// office-view.js
//
// The club's front desk (owner, 2026-10-18): *"napravi stranicu Office … nesto izmedju te stranice office
// sa sokkera i inboxa iz FM i overview … na njoj se samo briefly vidi finansijsko stanje (na Finance
// detaljno ovde samo kao widget), mozda trenutni transferi, inbox kao takav u koji ce nam stizati nedeljni
// update plata, priliva, odliva, stavljanje igraca na tl, ponuda da se preuzme drugi klub … prihod od
// poslednje utakmice itd dodavacemo vremenom."*
//
// WHAT THIS IS, AND WHAT IT IS NOT
//
// It is a landing page: the state of the club in six numbers, and an inbox of what has happened to it. The
// Finance page stays the place where money is examined in detail; this shows enough to know whether to go
// there. The inbox is the part that makes it worth opening.
//
// **The inbox is not a new table.** Everything a manager needs told — the weekly wage bill, gate money, a
// sponsor's payment, a listing fee, a transfer fee either way — is already written to
// `finance_ledger_entry` by the finance job, with a week, a category, an amount and a note. An inbox of its
// own would be a second record of the same events and the two would disagree the first time one was
// written without the other. So the inbox *is* the ledger, newest first.
//
// Two things the owner asked for are deliberately absent, because the data does not exist yet: a takeover
// offer for another club, and current transfer activity. Both are additions to the same list, not a
// different page.

import { escapeHtml } from '../../ui/escape.js';
import { authFetch } from '../../auth.js';

export function createOfficeView({ getTeamId, formatBudget }) {
    const money = typeof formatBudget === 'function'
        ? formatBudget
        : (value) => (Number(value) || 0).toLocaleString();

    async function loadOffice() {
        const main = document.getElementById('main-content');
        if (!main) return;

        const teamId = getTeamId();
        if (!teamId) {
            main.innerHTML = `<section class="fm-panel"><div class="fm-empty">No club is assigned to this
                manager yet.</div></section>`;
            return;
        }

        main.innerHTML = `<section class="fm-panel"><p class="fm-subtle">Opening the office…</p></section>`;

        // Three reads, and none of them is allowed to take the page down with it: a club with no ledger
        // and a club whose board cannot be read still have an office.
        const [summaryResult, ledgerResult, boardResult, offersResult, milestonesResult] = await Promise.allSettled([
            authFetch(`/api/teams/${teamId}/finances`),
            authFetch(`/api/teams/${teamId}/finances/ledger`),
            authFetch(`/api/teams/${teamId}/finances/board`),
            authFetch(`/api/job-offers`),
            // The season's milestones live here, not on the dashboard: they are the club's history,
            // and the dashboard is for what is about to happen (owner, 2026-10-20: the milestones go
            // to the club profile).
            authFetch(`/teams/${teamId}/milestones`),
        ]);

        const read = async (result) => {
            if (result.status !== 'fulfilled' || !result.value.ok) return null;
            try {
                return await result.value.json();
            } catch (err) {
                return null;
            }
        };

        const summary = await read(summaryResult);
        const ledger = (await read(ledgerResult)) || [];
        const board = await read(boardResult);
        const offers = (await read(offersResult)) || [];
        const milestones = await read(milestonesResult);

        main.innerHTML = `
            <div class="fm-page fm-page--office">
                ${header(summary)}
                ${offersPanel(offers)}
                ${books(summary, board)}
                ${milestonesPanel(milestones)}
                ${inbox(ledger, summary)}
            </div>`;
        wireOffers(main);
    }

    /**
     * A club offering the manager its job (owner, 2026-10-18).
     *
     * <p>First on the page, above the money, because it is the only thing here that is **asking for an
     * answer** rather than reporting. Everything below it can wait; this cannot.
     */
    function offersPanel(offers) {
        if (!offers.length) return '';
        return offers.map(offer => {
            const hoursLeft = hoursUntil(offer.expiresAt);
            const where = offer.position
                ? `${ordinal(offer.position)}${offer.leagueSize ? ` of ${offer.leagueSize}` : ''}`
                : 'not ranked yet';
            return `
            <section class="fm-panel fm-office-offer" data-offer-id="${offer.id}">
                <div class="fm-panel-head">
                    <div>
                        <div class="fm-eyebrow">Job offer</div>
                        <h3>${escapeHtml(offer.clubName)}</h3>
                        <p class="fm-subtle">${escapeHtml(offer.leagueName || 'Unknown league')}${offer.tier ? ` · tier ${offer.tier}` : ''}
                            ${offer.fromClubName ? ` · you are at ${escapeHtml(offer.fromClubName)}` : ''}</p>
                    </div>
                </div>
                <p class="fm-subtle">They want you as their manager. You have
                    <strong>${hoursLeft === null ? '24 hours' : hoursLeft + ' hour' + (hoursLeft === 1 ? '' : 's')}</strong>
                    left to answer${offer.expiresAt ? ` — the offer lapses ${escapeHtml(whenText(offer.expiresAt))}` : ''}.</p>
                <div class="fm-office-figures" style="margin-top:12px;">
                    ${figure('Balance', money(offer.balance))}
                    ${figure('League position', where)}
                    ${figure('Tier', offer.tier ? String(offer.tier) : '—')}
                </div>
                ${offer.leagueId ? `<p class="fm-subtle" style="margin-top:10px;">
                    <button type="button" class="fm-action-btn secondary js-offer-table"
                            data-league-id="${offer.leagueId}"
                            data-league-name="${escapeHtml(offer.leagueName || '')}">View the table</button>
                </p>` : ''}
                ${offer.topPlayers && offer.topPlayers.length ? `
                    <div class="fm-panel-head" style="margin-top:16px;"><h3>Their best three</h3></div>
                    <div class="fm-squad-wrap">
                        <table class="fm-table fm-office-offer-players">
                            <thead><tr><th>Player</th><th>Pos</th><th>Rating</th><th>Value</th>${skillNames(offer.topPlayers)}</tr></thead>
                            <tbody>
                                ${offer.topPlayers.map(p => `<tr>
                                    <td>${escapeHtml(p.name || '—')}</td>
                                    <td>${escapeHtml(p.position || '—')}</td>
                                    <td><strong>${escapeHtml(String(p.rating))}</strong></td>
                                    <td>${escapeHtml(money(p.value))}</td>
                                    ${skillNames(offer.topPlayers).length ? Object.keys(p.skills || {}).map(k => `<td>${escapeHtml(String(p.skills[k]))}</td>`).join('') : ''}
                                </tr>`).join('')}
                            </tbody>
                        </table>
                    </div>` : ''}
                <div class="fm-fantasy-actions" style="margin-top:14px;">
                    <button type="button" class="fm-action-btn js-offer-accept" data-offer-id="${offer.id}">Accept the job</button>
                    <button type="button" class="fm-action-btn secondary js-offer-decline" data-offer-id="${offer.id}">Decline</button>
                </div>
                <p class="fm-subtle js-offer-note" style="margin-top:10px;"></p>
            </section>`;
        }).join('');
    }

    /** The skill columns, taken from whichever player has them so every row lines up. */
    function skillNames(players) {
        const withSkills = players.find(p => p.skills && Object.keys(p.skills).length);
        if (!withSkills) return '';
        return Object.keys(withSkills.skills).map(k => `<th>${escapeHtml(k)}</th>`).join('');
    }

    function wireOffers(root) {
        root.querySelectorAll('.js-offer-table').forEach(button => {
            button.addEventListener('click', () => {
                if (typeof window.openLeagueById === 'function') {
                    window.openLeagueById(Number(button.dataset.leagueId), button.dataset.leagueName || '');
                }
            });
        });
        root.querySelectorAll('.js-offer-accept, .js-offer-decline').forEach(button => {
            button.addEventListener('click', async () => {
                const accept = button.classList.contains('js-offer-accept');
                const id = button.dataset.offerId;
                const note = button.closest('.fm-office-offer')?.querySelector('.js-offer-note');
                button.disabled = true;
                if (note) note.textContent = accept ? 'Taking the job…' : 'Turning it down…';
                try {
                    await authFetch(`/api/job-offers/${id}/${accept ? 'accept' : 'decline'}`, { method: 'POST' });
                    // Reload the page rather than patching it: accepting changes which club this page is
                    // about, and every number on it. Patching six widgets from one response is how a page
                    // ends up showing the new club's name over the old club's money.
                    await loadOffice();
                } catch (err) {
                    // A day-old page is a normal thing to press, and the server answers 409 with a reason.
                    if (note) note.textContent = err?.details?.message || err?.message || 'That did not work.';
                    button.disabled = false;
                }
            });
        });
    }

    function hoursUntil(iso) {
        if (!iso) return null;
        const ms = new Date(iso).getTime() - Date.now();
        if (!Number.isFinite(ms)) return null;
        return Math.max(0, Math.round(ms / 3600000));
    }

    function whenText(iso) {
        const d = new Date(iso);
        return Number.isFinite(d.getTime()) ? d.toLocaleString() : '';
    }

    function ordinal(n) {
        const value = Number(n);
        const rest = value % 100;
        if (rest >= 11 && rest <= 13) return value + 'th';
        return value + ({ 1: 'st', 2: 'nd', 3: 'rd' }[value % 10] || 'th');
    }

    function header(summary) {
        const name = summary?.teamName || 'Your club';
        const budget = summary ? money(summary.budget) : '—';
        return `
            <header class="fm-country-header">
                <div class="fm-country-header-main">
                    <div>
                        <div class="fm-eyebrow">Office</div>
                        <h2 class="fm-country-header-title">${escapeHtml(name)}</h2>
                        <p class="fm-subtle">The state of the club, and what has happened to it.</p>
                    </div>
                </div>
                <dl class="fm-country-facts">
                    <div class="fm-country-fact"><dt>Balance:</dt><dd><span class="fm-world-fact-number">${escapeHtml(budget)}</span></dd></div>
                </dl>
            </header>`;
    }

    /**
     * The season's milestones: the club's own history (owner, 2026-10-20 - they came off the
     * dashboard, where five cards of history pushed the live tiles down the page).
     *
     * <p>Five cards: the season's top scorer and top assist, the biggest win and the heaviest
     * loss, and the crowd - the attendance belongs here, where the club's story is told, rather
     * than on the dashboard's ticker where it was a warning about a week that had not been trained.
     */
    function milestonesPanel(data) {
        if (!data) return '';
        const attendance = data.attendance || {};
        const cards = [
            {
                title: 'Top scorer',
                value: data.topScorer?.playerName || '—',
                meta: data.topScorer?.playerName
                    ? `${data.topScorer.teamName || 'No team'} · ${Number(data.topScorer.value || 0)} goals`
                    : 'No goals filed yet.'
            },
            {
                title: 'Top assist',
                value: data.topAssist?.playerName || '—',
                meta: data.topAssist?.playerName
                    ? `${data.topAssist.teamName || 'No team'} · ${Number(data.topAssist.value || 0)} assists`
                    : 'No assists filed yet.'
            },
            {
                title: 'Biggest win',
                value: data.biggestWin?.summary || '—',
                meta: data.biggestWin?.context || 'Waiting for a standout result.'
            },
            {
                title: 'Heaviest loss',
                value: data.biggestLoss?.summary || '—',
                meta: data.biggestLoss?.context || 'No heavy defeat registered yet.'
            },
            {
                title: 'Attendance',
                value: attendance.averageAttendance != null ? formatAttendance(attendance.averageAttendance) : '—',
                meta: attendance.averageAttendance
                    ? `High ${formatAttendance(attendance.highestAttendance)} (${attendance.highestMatchLabel || '—'})`
                        + ` · Low ${formatAttendance(attendance.lowestAttendance)} (${attendance.lowestMatchLabel || '—'})`
                        + (attendance.insight ? ` · ${attendance.insight}` : '')
                    : (attendance.insight || 'Crowd data appears once played fixtures start filing gates.')
            }
        ];
        return `
            <section class="fm-panel fm-office-milestones">
                <div class="fm-panel-head">
                    <h3>Club Milestones</h3>
                    <span class="fm-panel-action">This season</span>
                </div>
                <div class="fm-milestone-grid">
                    ${cards.map(card => `
                        <article class="fm-milestone-card">
                            <div class="fm-milestone-kicker">${escapeHtml(card.title)}</div>
                            <div class="fm-milestone-value">${escapeHtml(card.value)}</div>
                            <div class="fm-milestone-meta">${escapeHtml(card.meta)}</div>
                        </article>`).join('')}
                </div>
            </section>`;
    }

    function formatAttendance(value) {
        const numeric = Number(value);
        if (!Number.isFinite(numeric)) return '—';
        const rounded = Math.round(numeric);
        return rounded.toLocaleString();
    }

    /**
     * Six numbers, and no more.
     *
     * <p>A club with nothing settled says so rather than showing zeroes, because a zero balance and a
     * season with no weeks played yet look identical on a widget and mean opposite things. The finance
     * endpoint sends the sentence; this uses it.
     */
    function books(summary, board) {
        if (!summary) {
            return `<section class="fm-panel"><div class="fm-panel-head">The books</div>
                <div class="fm-empty">The club's finances could not be read.</div></section>`;
        }
        const settled = summary.settled === true;
        const net = Number(summary.netToDate) || 0;
        const trust = board ? Number(board.trust) : null;

        return `
            <section class="fm-panel">
                <div class="fm-panel-head">
                    <div>
                        <h3>The books</h3>
                        <p class="fm-subtle">Season ${escapeHtml(String(summary.seasonYear ?? '—'))}, week
                            ${escapeHtml(String(summary.currentWeek ?? '—'))} of
                            ${escapeHtml(String(summary.weeksPerSeason ?? '—'))}.
                            The Finance page has the detail.</p>
                    </div>
                </div>
                ${!settled && summary.notice
                    ? `<div class="fm-callout fm-callout--warn">${escapeHtml(summary.notice)}</div>`
                    : ''}
                <div class="fm-office-figures">
                    ${figure('Balance', money(summary.budget))}
                    ${figure('Weekly wages', money(summary.squadWageBill))}
                    ${figure('Income to date', money(summary.incomeToDate))}
                    ${figure('Costs to date', money(summary.costsToDate))}
                    ${figure('Net to date', money(net), net < 0 ? 'is-bad' : net > 0 ? 'is-good' : '')}
                    ${trust === null ? '' : figure('Board trust', String(trust),
                        trust < 40 ? 'is-bad' : trust > 60 ? 'is-good' : '')}
                </div>
                ${board && board.headline
                    ? `<p class="fm-subtle" style="margin-top:12px;">${escapeHtml(board.headline)}</p>`
                    : ''}
                ${board && Array.isArray(board.concerns) && board.concerns.length
                    ? `<ul class="fm-office-list">${board.concerns.map(c => `<li>${escapeHtml(String(c))}</li>`).join('')}</ul>`
                    : ''}
            </section>`;
    }

    function figure(label, value, modifier = '') {
        return `<div class="fm-office-figure ${modifier}">
            <span class="fm-office-figure-label">${escapeHtml(label)}</span>
            <span class="fm-office-figure-value">${escapeHtml(String(value))}</span>
        </div>`;
    }

    /**
     * The inbox: the ledger, newest first, grouped by the week it belongs to.
     *
     * <p>Grouped by week because that is how a manager reads it — "what did last week cost me" is one
     * question, not eight lines — and because the finance job settles a week at a time.
     */
    function inbox(ledger, summary) {
        if (!ledger.length) {
            return `<section class="fm-panel">
                <div class="fm-panel-head"><h3>Inbox</h3></div>
                <div class="fm-empty">Nothing has arrived yet. The weekly wage bill, gate money and
                    sponsorship all land here as the weeks are played.</div>
            </section>`;
        }

        const byWeek = new Map();
        ledger.forEach(entry => {
            const week = entry.week ?? 0;
            if (!byWeek.has(week)) byWeek.set(week, []);
            byWeek.get(week).push(entry);
        });
        const weeks = Array.from(byWeek.keys()).sort((a, b) => b - a);
        const season = summary?.seasonYear ?? '';

        return `<section class="fm-panel">
            <div class="fm-panel-head">
                <div>
                    <h3>Inbox</h3>
                    <p class="fm-subtle">${ledger.length} entr${ledger.length === 1 ? 'y' : 'ies'} this
                        season, newest first. Everything the club has been paid and everything it has paid.</p>
                </div>
            </div>
            ${weeks.map(week => `
                <div class="fm-office-week">
                    <div class="fm-office-week-head">Week ${escapeHtml(String(week))}${season ? ` · season ${escapeHtml(String(season))}` : ''}</div>
                    ${byWeek.get(week).map(entry => {
                        const amount = Number(entry.amount) || 0;
                        const incoming = entry.income === true;
                        return `<div class="fm-office-entry">
                            <span class="fm-office-entry-label">${escapeHtml(entry.label || entry.category || 'Entry')}</span>
                            <span class="fm-office-entry-amount ${incoming ? 'is-good' : 'is-bad'}">${incoming ? '+' : ''}${escapeHtml(money(amount))}</span>
                            ${entry.note ? `<span class="fm-office-entry-note">${escapeHtml(entry.note)}</span>` : ''}
                        </div>`;
                    }).join('')}
                </div>`).join('')}
        </section>`;
    }

    return { loadOffice };
}
