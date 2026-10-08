export function createLoansFeature(deps) {
    const {
        authFetch,
        escapeHtml,
        buildClubActionsHtml,
        loadPlayer,
    } = deps;

    /**
     * The API explains itself, and its explanation is the feature.
     *
     * <p>Every refusal here names a rule — "a club may only loan to a lower tier", "only players younger
     * than 24" — because those sentences are what the owner asked for. Swallowing them into "Action
     * failed" would throw away the only useful thing on the wire and leave the manager guessing which of
     * five rules he broke. The same reason and the same shape as the junior school panel.
     */
    async function failureMessage(res, fallback) {
        let msg = fallback;
        try {
            const text = await res.text();
            if (text) {
                try {
                    const payload = JSON.parse(text);
                    msg = payload.message || payload.error || text;
                } catch (e) {
                    msg = text;
                }
            }
        } catch (e) { /* the body is gone; the fallback is all there is */ }
        return msg;
    }

    function tierLabel(team) {
        if (team.tier == null) return 'no tier';
        return `Tier ${team.tier}`;
    }

    /**
     * Destination options, split so the screen cannot offer a button that will be refused.
     *
     * <p>The server returns every club with a reason when it is ineligible, so the refusals are shown
     * rather than hidden: a manager who cannot loan to anybody needs to read *why* — "every club in your
     * country is a tier below yours and has room" is a completely different screen from "no club in your
     * country is a tier below yours", and only one of them is a bug.
     */
    /**
     * The dropdown, from the eligible list only.
     *
     * <p>The server sends the clubs you may loan to and nothing else. It used to send all 14,626 of them
     * with a reason attached — 2.38 MB to deliver one option — and a screen that reads every row it is
     * given is how that happened. Refusals now arrive as counts by reason, below.
     */
    function destinationOptions(payload) {
        const eligible = (payload && Array.isArray(payload.eligible)) ? payload.eligible : [];
        if (!eligible.length) {
            return `<option value="">No eligible club</option>`;
        }
        return eligible.map(d =>
            `<option value="${d.teamId}">${escapeHtml(d.name)} — ${escapeHtml(tierLabel(d))}</option>`
        ).join('');
    }

    /**
     * Why the rest could not be loaned to, as counts rather than as 14,000 names.
     *
     * <p>"1,625 clubs are not managed by a person" tells a manager what to do next. A list of 1,625
     * club names tells him nothing he can act on, and costs more to draw than the rest of the page.
     */
    function refusalSummary(payload) {
        if (!payload) return '';
        const rows = [];
        if (payload.note) rows.push(escapeHtml(payload.note));
        const refused = Array.isArray(payload.refused) ? payload.refused : [];
        if (refused.length) {
            rows.push(refused
                .map(r => `${r.count} club${Number(r.count) === 1 ? '' : 's'}: ${escapeHtml(r.reason)}`)
                .join('; ') + '.');
        }
        if (payload.eligibleTotal > payload.shown) {
            rows.push(escapeHtml(`Showing ${payload.shown} of ${payload.eligibleTotal} clubs you could loan to.`));
        }
        if (!rows.length) return '';
        return `<p class="fm-subtle academy-panel-copy">${rows.join(' ')}</p>`;
    }

    function loanRow(loan, side) {
        // side: 'in' = this club borrowed him, 'out' = this club lent him
        const actions = [];

        // An offer nobody has answered has no actions. "Request return" on a loan that has not started
        // would be refused by the service with LOAN_NOT_ACTIVE, and a button whose only outcome is an
        // error is worse than no button.
        // **The status decides the action, then the side.** It was decided the other way round, and every
        // borrowing row fell through to the last branch: a loan that had been AGREED but not yet started
        // offered "Send him back", which the service refuses with LOAN_NOT_ACTIVE, and the button the
        // owner needed — **Take him in** — was nowhere on the row. Owner, 2026-10-08: "poslajem ga nazad
        // al ne stigne niti imam opciju da prihvatim".
        //
        // A notice is answered before anything else: it is the only state where the other club is waiting
        // on an answer from this one.
        if (loan.noticeOutstanding && loan.status === 'ACTIVE') {
            // The other club has asked for this loan to end. Accepting ends it at once; leaving it alone
            // means it ends a week later anyway, so the button is "yes" and the copy says what happens
            // if they do nothing.
            actions.push(`<button class="mini-btn" data-loan-action="accept-termination" data-loan-id="${loan.loanId}">Accept return</button>`);
        } else if (loan.status === 'AGREED') {
            // Agreed, not started. Only the borrowing club starts it, and that is the moment the room is
            // checked — the offer was not a promise of a place.
            if (side === 'in') {
                actions.push(`<button class="mini-btn" data-loan-action="activate" data-loan-id="${loan.loanId}">Take him in</button>`);
            } else {
                actions.push('<span class="fm-subtle">Waiting for them to take him in</span>');
            }
        } else if (loan.status === 'ACTIVE') {
            if (side === 'out') {
                actions.push(`<button class="mini-btn" data-loan-action="terminate" data-loan-id="${loan.loanId}">Request return</button>`);
            } else {
                actions.push(`<button class="mini-btn" data-loan-action="terminate" data-loan-id="${loan.loanId}">Send him back</button>`);
            }
        } else {
            // Ended, recalled or returned: nothing to do.
            actions.push('<span class="fm-subtle">—</span>');
        }
        return `
            <tr>
                <td class="sq-name">
                    <span class="sq-player-link" data-open-player="${loan.playerId}">${escapeHtml(loan.playerName || '—')}</span>
                </td>
                <td>${loan.playerId}</td>
                <td>${escapeHtml(side === 'out' ? `To #${loan.borrowingClubId}` : `From #${loan.lendingClubId}`)}</td>
                <td>${escapeHtml(loan.status || '—')}</td>
                <td>S${loan.season ?? '—'} W${loan.startWeek ?? '—'} → ${escapeHtml(loan.returnsAt || '')}</td>
                <td>${loan.noticeOutstanding
                    ? `<span class="academy-status-pill" style="--academy-status:#f5b041;">Notice — ends week ${loan.noticeWeek}</span>`
                    : '<span class="fm-subtle">—</span>'}</td>
                <td><div class="academy-action-cell">${actions.join('')}</div></td>
            </tr>`;
    }

    function section(title, count, bodyHtml, description, emptyText) {
        return `
            <section class="fm-panel academy-panel">
                <div class="fm-panel-head">
                    <div>
                        <h3>${title}</h3>
                        ${description ? `<p class="fm-subtle academy-panel-copy">${description}</p>` : ''}
                    </div>
                    <span class="fm-panel-action">${count}</span>
                </div>
                ${bodyHtml || `<div class="fm-empty">${escapeHtml(emptyText)}</div>`}
            </section>`;
    }

    /**
     * The rules, in their own panel.
     *
     * <p>They were in the hero first, and the hero's text column is narrow — the club action row sits
     * beside it — so four paragraphs of rules wrapped into a column about twenty characters wide and
     * pushed the counters off the bottom of the panel. The hero carries one line; the rules get the
     * width of the page, which is what a manager reads them at.
     */
    function rulesPanel(rules) {
        const items = [
            rules.ageRule,
            rules.tierLadder,
            rules.wage,
            rules.runsTo,
            rules.domesticOnly ? 'A loan can only be made between clubs in the same country.' : null,
            rules.lowerTierOnly ? 'A loan can only go down the tiers, never up and never across.' : null,
            rules.noticeWeeks != null
                ? `Ending a loan: either club can ask. If the other agrees it ends at once; if nobody answers it ends after ${rules.noticeWeeks} week(s).`
                : null,
        ].filter(Boolean);
        return `
            <section class="fm-panel academy-panel">
                <div class="fm-panel-head">
                    <div>
                        <h3>How loans work</h3>
                        <p class="fm-subtle academy-panel-copy">Every rule the service enforces, in words, so nothing here has to be discovered by being refused.</p>
                    </div>
                </div>
                <ul class="fm-subtle academy-panel-copy" style="margin:0;padding-left:18px;line-height:1.7;">
                    ${items.map(r => `<li>${escapeHtml(r)}</li>`).join('')}
                </ul>
            </section>`;
    }

    function loanTable(loans, side) {
        if (!loans.length) return '';
        return `
            <div class="fm-squad-wrap">
                <table class="fm-squad academy-squad">
                    <thead>
                        <tr>
                            <th class="sq-name">Player</th>
                            <th>Id</th>
                            <th>Club</th>
                            <th>Status</th>
                            <th>Runs</th>
                            <th>Notice</th>
                            <th>Actions</th>
                        </tr>
                    </thead>
                    <tbody>${loans.map(l => loanRow(l, side)).join('')}</tbody>
                </table>
            </div>`;
    }

    async function loadLoans() {
        const mainContent = document.getElementById("main-content");

        // Rules first and on their own: it is the one call that cannot fail for an ordinary reason, and
        // the copy the whole screen is built on. If it fails, everything else would too, so there is
        // nothing gained by racing them.
        let rules;
        try {
            const res = await authFetch('/loans/rules');
            if (!res.ok) throw new Error('rules');
            rules = await res.json();
        } catch (e) {
            mainContent.innerHTML = `<div class="fm-page fm-page--club">
                <section class="fm-panel fm-club-hero">
                    <button class="back-to-dashboard" data-nav-back="dashboard">Back</button>
                    <div class="fm-club-hero-main"><div>
                        <div class="fm-eyebrow">Loans</div>
                        <h2>Loans</h2>
                        <p class="fm-subtle">Could not load the loan rules.</p>
                    </div>${buildClubActionsHtml('loans')}</div>
                </section>
                <section class="fm-panel"><div class="fm-empty">Could not load loan data.</div></section>
            </div>`;
            return;
        }

        const [outgoing, incoming, offers, available, destinations] = await Promise.all([
            authFetch('/loans/outgoing').then(r => r.json()).catch(() => []),
            authFetch('/loans/incoming').then(r => r.json()).catch(() => []),
            authFetch('/loans/offers').then(r => r.json()).catch(() => []),
            authFetch('/loans/available').then(r => r.json()).catch(() => []),
            authFetch('/loans/destinations').then(r => r.json()).catch(() => []),
        ]);

        const loanable = (available || []).filter(p => p.loanable);
        const notLoanable = (available || []).filter(p => !p.loanable);
        const eligibleCount = destinations && Array.isArray(destinations.eligible) ? destinations.eligible.length : 0;

        const lendRows = loanable.map(p => `
            <tr>
                <td class="sq-name">${escapeHtml(p.name)}</td>
                <td>${p.age}</td>
                <td>${escapeHtml(p.position || '—')}</td>
                <td>${p.rating}</td>
                <td>
                    <div class="fm-season-select-wrap">
                        <select class="fm-season-select" data-loan-destination-for="${p.playerId}">
                            ${destinationOptions(destinations)}
                        </select>
                    </div>
                </td>
                <td>
                    <div class="academy-action-cell">
                        <button class="mini-btn" data-loan-action="offer"
                                data-loan-player="${p.playerId}"
                                ${eligibleCount ? '' : 'disabled'}>Lend out</button>
                    </div>
                </td>
            </tr>`).join('');

        mainContent.innerHTML = `<div class="fm-page fm-page--club fm-page--loans">
            <section class="fm-panel fm-club-hero academy-hero">
                <button class="back-to-dashboard" data-nav-back="dashboard">Back</button>
                <div class="fm-club-hero-main">
                    <div>
                        <div class="fm-eyebrow">Club overview</div>
                        <h2>Loans</h2>
                        <p class="fm-subtle">A player too young for this tier goes down to a weaker club for minutes, and comes back at the end of the season.</p>
                    </div>
                    ${buildClubActionsHtml('loans')}
                </div>
                <div class="fm-medical-stat-grid academy-summary-grid">
                    <div><strong>${outgoing.length}</strong><span>Out</span></div>
                    <div><strong>${incoming.length}</strong><span>In</span></div>
                    <div><strong>${offers.length}</strong><span>Offers</span></div>
                    <div><strong>${loanable.length}</strong><span>Loanable</span></div>
                </div>
            </section>

            ${rulesPanel(rules)}

            ${section('Players loaned in', incoming.length,
                loanTable(incoming, 'in'),
                'They play for this club and count against its thirty. The wage and the training stay with the club that owns them.',
                'Nobody is on loan to this club.')}

            ${section('Players loaned out', outgoing.length,
                loanTable(outgoing, 'out'),
                'Still this club\'s players: still in its thirty, still on its wage bill, still trained by its coaches. They just cannot be picked for this club. An offer they have not answered yet is not running.',
                'This club has nobody out on loan.')}

            ${offers.length ? section('Offers to take a player on', offers.length,
                loanTable(offers, 'in'),
                'Accepting ends the question of room — the refusal happens at the moment you accept, not when the offer is made.',
                'No offers.') : ''}

            ${section('Lend a player out', loanable.length, lendRows ? `
                <div class="fm-squad-wrap">
                    <table class="fm-squad academy-squad">
                        <thead>
                            <tr>
                                <th class="sq-name">Player</th>
                                <th>Age</th>
                                <th>Pos</th>
                                <th>Rating</th>
                                <th>Destination</th>
                                <th>Actions</th>
                            </tr>
                        </thead>
                        <tbody>${lendRows}</tbody>
                    </table>
                </div>
                ${refusalSummary(destinations)}
                ${notLoanable.length ? `<p class="fm-subtle academy-panel-copy">Not loanable: `
                    + `${notLoanable.map(p => `${escapeHtml(p.name)} (${escapeHtml(p.reason || 'no')})`).join('; ')}.</p>` : ''}
            ` : '', '', 'Nobody here can be loaned out.')}

            <p class="fm-subtle academy-footnote">A loan cannot be made in the final week of the season, and
                a full squad cannot take anybody on — free a place first.</p>
        </div>`;

        wireActions(mainContent);
        mainContent.querySelectorAll('[data-open-player]').forEach(link => {
            link.addEventListener('click', async () => {
                const playerId = Number(link.getAttribute('data-open-player'));
                if (playerId) await loadPlayer(playerId, 'loans');
            });
        });
    }

    function wireActions(mainContent) {
        mainContent.querySelectorAll('[data-loan-action]').forEach(btn => {
            btn.addEventListener('click', async () => {
                const action = btn.getAttribute('data-loan-action');
                const loanId = Number(btn.getAttribute('data-loan-id'));

                if (action === 'offer') {
                    const playerId = Number(btn.getAttribute('data-loan-player'));
                    const select = mainContent.querySelector(`[data-loan-destination-for="${playerId}"]`);
                    const destinationId = select ? Number(select.value) : NaN;
                    if (!playerId || !destinationId) {
                        alert('Choose a club first.');
                        return;
                    }
                    if (!window.confirm('Lend this player out until the end of the season?\n\n'
                        + 'He stays yours — the wage and the training are still charged to this club — '
                        + 'but the other club plays him, and he still counts against your thirty.')) return;

                    btn.disabled = true;
                    const res = await authFetch('/loans', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({ playerId, borrowingClubId: destinationId }),
                    });
                    if (!res.ok) {
                        alert(await failureMessage(res, 'Could not lend him out.'));
                        btn.disabled = false;
                        return;
                    }
                    // The offer is created and the other club has to answer it, so the screen must not
                    // pretend it is running yet.
                    await loadLoans();
                    return;
                }

                if (!loanId) return;

                if (action === 'activate') {
                    // The button the row never had: an AGREED loan is not a running one, so the player
                    // is not at the club yet, and this is the moment the room is checked. That is the
                    // owner's rule - the refusal happens when you accept, not when the offer is made.
                    btn.disabled = true;
                    const res = await authFetch(`/loans/${loanId}/activate`, { method: 'POST' });
                    if (!res.ok) {
                        alert(await failureMessage(res, 'Could not take him in.'));
                        btn.disabled = false;
                        return;
                    }
                    await loadLoans();
                    return;
                }

                if (action === 'terminate') {
                    const reason = window.prompt(
                        'Why? (optional)\n\nThe other club can agree and end it now, or the loan ends after '
                        + 'a week either way.',
                        '');
                    if (reason === null) return;
                    btn.disabled = true;
                    const res = await authFetch(`/loans/${loanId}/terminate`, {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({ reason: reason || '' }),
                    });
                    if (!res.ok) {
                        alert(await failureMessage(res, 'Could not request that.'));
                        btn.disabled = false;
                        return;
                    }
                    await loadLoans();
                    return;
                }

                if (action === 'accept-termination') {
                    if (!window.confirm('End this loan now?')) return;
                    btn.disabled = true;
                    const res = await authFetch(`/loans/${loanId}/accept-termination`, { method: 'POST' });
                    if (!res.ok) {
                        alert(await failureMessage(res, 'Could not accept.'));
                        btn.disabled = false;
                        return;
                    }
                    await loadLoans();
                }
            });
        });
    }

    return { loadLoans };
}