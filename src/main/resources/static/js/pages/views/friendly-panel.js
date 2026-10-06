// pages/views/friendly-panel.js
//
// The club's friendly week: what is scheduled, which slots are open, who is
// waiting for an answer, and what this club has asked for.
//
// The backend for all of this already existed and worked - FriendlyRequestService,
// FriendlyController, and a dashboard ticker that listed incoming requests. What
// was missing was any way to *act* on them: the dashboard said a club had asked
// and gave no button to answer it, and there was no way to start a request. That
// is the gap this file fills, and nothing here changes a rule.
//
// Every fetch checks `response.ok`. The 409 that FriendlyRequestService answers
// with is a decision a manager makes by accident - a slot already taken, a week
// that has gone - and it carries a sentence meant to be read. Swallowing it into
// a generic failure is what AGENTS.md calls out as the reason loaders check.

import { htmlEscape } from './utils.js';

const DAY_NAMES = { 1: 'Thursday', 7: 'Sunday' };

export function createFriendlyPanel(deps) {
    const { authFetch } = deps;

    function dayLabel(day) {
        return DAY_NAMES[day] || `Day ${day}`;
    }

    async function loadFriendlyWeek(teamId, week, season) {
        const params = new URLSearchParams();
        if (week != null) params.set('week', String(week));
        if (season != null) params.set('season', String(season));
        const qs = params.toString();
        const response = await authFetch(
            `/api/season/friendlies/${teamId}/week${qs ? `?${qs}` : ''}`);
        if (!response.ok) {
            return { failed: true, status: response.status };
        }
        return response.json();
    }

    async function loadOpponents(teamId, q) {
        const params = new URLSearchParams();
        if (q) params.set('q', q);
        params.set('limit', '60');
        const response = await authFetch(
            `/api/season/friendlies/${teamId}/opponents?${params.toString()}`);
        if (!response.ok) return [];
        return response.json();
    }

    function requestRow(request, { incoming }) {
        const id = request?.id;
        const who = request?.otherName || 'Unknown club';
        const slot = request?.slot;
        const settled = request?.status && request.status !== 'PENDING';
        let actions = '';
        if (incoming && !settled) {
            actions = `<button type="button" class="fm-action-btn primary" data-friendly-accept="${id}">Accept</button>
                       <button type="button" class="fm-action-btn secondary" data-friendly-decline="${id}">Decline</button>`;
        } else if (!incoming && !settled) {
            actions = `<button type="button" class="fm-action-btn secondary" data-friendly-cancel="${id}">Withdraw</button>`;
        } else if (request?.declineReason) {
            actions = `<span class="fm-subtle">${htmlEscape(request.declineReason)}</span>`;
        }
        return `
            <div class="fm-friendly-row">
                <div>
                    <strong>${htmlEscape(String(who))}</strong>
                    <span class="fm-subtle">week ${request?.week ?? '?'}, ${htmlEscape(dayLabel(slot))}${
                        settled ? ' - ' + htmlEscape(String(request.status).toLowerCase()) : ''}</span>
                </div>
                <div class="fm-friendly-row-actions">${actions}</div>
            </div>`;
    }

    function buildHtml(week) {
        if (week.failed) {
            return `<section class="fm-panel">
                <div class="fm-panel-head"><h3>Friendlies</h3></div>
                <div class="fm-empty">Your friendly week could not be loaded (status ${week.status}).</div>
            </section>`;
        }

        const openSlots = week.openSlots || [];
        const incoming = week.incoming || [];
        const outgoing = week.outgoing || [];

        const slotsHtml = openSlots.length
            ? openSlots.map(slot => `
                <div class="fm-friendly-row">
                    <div>
                        <strong>${htmlEscape(dayLabel(slot.day))}</strong>
                        <span class="fm-subtle">${htmlEscape(String(slot.kind || 'open'))}</span>
                    </div>
                    <div class="fm-friendly-row-actions">
                        <button type="button" class="fm-action-btn primary"
                                data-friendly-invite="${slot.slot}">Invite for friendly</button>
                    </div>
                </div>`).join('')
            : '<div class="fm-empty">No open slots this week. A club is not handed a friendly - it asks for one.</div>';

        return `
            <section class="fm-panel" data-friendly-panel data-week="${week.week}" data-season="${week.season}">
                <div class="fm-panel-head">
                    <div>
                        <div class="fm-eyebrow">Friendlies</div>
                        <h3>Week ${week.week}</h3>
                        <p class="fm-subtle">${htmlEscape(week.label || '')}${week.inPlayoff ? ' - you are in the playoff.' : ''}
                            A friendly costs one training session; ${week.trainingSessionsAvailable} remain this week.</p>
                    </div>
                    <span class="fm-panel-action">${week.friendliesAgreed} arranged</span>
                </div>

                ${incoming.length ? `
                <div class="fm-friendly-block">
                    <h4>Waiting for your answer</h4>
                    ${incoming.map(r => requestRow(r, { incoming: true })).join('')}
                </div>` : ''}

                ${outgoing.length ? `
                <div class="fm-friendly-block">
                    <h4>You have asked</h4>
                    ${outgoing.map(r => requestRow(r, { incoming: false })).join('')}
                </div>` : ''}

                <div class="fm-friendly-block">
                    <h4>Open slots</h4>
                    ${slotsHtml}
                </div>

                <div class="fm-friendly-invite-form" data-friendly-form hidden>
                    <div class="fm-friendly-block">
                        <h4>Invite a club</h4>
                        <p class="fm-subtle">Choosing a club sends them a request. They may refuse, and it
                            lapses at the end of the week.</p>
                        <input type="text" class="fm-input" data-friendly-search
                               placeholder="Search clubs in your country" autocomplete="off">
                        <div class="fm-friendly-results" data-friendly-results></div>
                        <button type="button" class="fm-action-btn secondary" data-friendly-cancel-form>Cancel</button>
                    </div>
                </div>

                <div class="fm-friendly-message" data-friendly-message hidden></div>
            </section>`;
    }

    function message(root, text, kind) {
        const box = root.querySelector('[data-friendly-message]');
        if (!box) return;
        box.hidden = false;
        box.className = `fm-friendly-message ${kind === 'error' ? 'is-error' : 'is-ok'}`;
        box.textContent = text;
    }

    async function send(root, path, body) {
        const response = await authFetch(path, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: body ? JSON.stringify(body) : undefined
        });
        if (!response.ok) {
            const payload = await response.json().catch(() => ({}));
            return { ok: false, text: payload.detail || payload.error
                || `That could not be arranged (status ${response.status}).` };
        }
        return { ok: true };
    }

    return { loadFriendlyWeek, loadOpponents, buildHtml };
}