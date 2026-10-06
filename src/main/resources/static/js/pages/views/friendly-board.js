// pages/views/friendly-board.js
//
// The free-slot board.
//
// A friendly request names one opponent and waits for that opponent's answer. This is the other
// door: a club puts its slot up for anyone to take. The difference is the whole page - the request
// screen could only ever be reached from two people who already knew each other, and this one is
// reachable by the world.
//
// Rules come from FriendlyOfferService and are not restated here: only a human club can post or take,
// and a posting expires with its own slot. The page only answers "what is on offer" and performs the
// two actions the owner specified - post a free slot, and accept one.

import { htmlEscape } from './utils.js';

const WEEKDAY = { 1: 'First', 3: 'Second', 5: 'Third', 7: 'Fourth' };

function slotLabel(day) {
    const names = { 1: 'Day 1', 3: 'Day 3', 5: 'Day 5', 7: 'Day 7' };
    return names[day] || `Day ${day}`;
}

export function createFriendlyBoard(deps) {
    const { authFetch } = deps;

    async function loadBoard(season, week) {
        const params = new URLSearchParams();
        if (season != null) params.set('season', String(season));
        if (week != null) params.set('week', String(week));
        const response = await authFetch(`/api/season/friendly-offers${params.toString() ? `?${params}` : ''}`);
        if (!response.ok) return { failed: true, status: response.status };
        return response.json();
    }

    async function loadMine(teamId, season, week) {
        const params = new URLSearchParams();
        if (season != null) params.set('season', String(season));
        if (week != null) params.set('week', String(week));
        const response = await authFetch(`/api/season/friendly-offers/mine/${teamId}${params.toString() ? `?${params}` : ''}`);
        if (!response.ok) return { failed: true, status: response.status };
        return response.json();
    }

    function offerRow(offer, teamId) {
        const isMine = offer.teamId != null && String(offer.teamId) === String(teamId);
        const claim = isMine || offer.status !== 'OPEN'
            ? `<span class="fm-subtle">${isMine ? 'yours' : offer.status}</span>`
            : `<button type="button" class="fm-action-btn primary" data-offer-claim="${offer.id}">Take this slot</button>`;
        return `
            <div class="fm-friendly-row">
                <div>
                    <strong>${htmlEscape(offer.teamName || 'A club')}</strong>
                    <span class="fm-subtle">${slotLabel(offer.day)} - week ${offer.week} - season ${offer.season}</span>
                </div>
                <div class="fm-friendly-row-actions">${claim}</div>
            </div>`;
    }

    function buildHtml(board, mine, teamId) {
        if (board.failed) {
            return `<section class="fm-panel"><div class="fm-empty">The board could not be loaded (status ${board.status}).</div></section>`;
        }
        const list = board.offers || [];
        const mineList = mine && !mine.failed ? (mine.offers || []) : [];
        return `
            <section class="fm-panel" data-friendly-board data-season="${board.season}" data-week="${board.week}">
                <div class="fm-panel-head">
                    <div>
                        <div class="fm-eyebrow">Free-slot board</div>
                        <h3>Open slots, week ${board.week}</h3>
                        <p class="fm-subtle">Post a free slot and any club may take it, or take one that is up.
                            A posting says exactly which week, season and day it is for, and it closes when that slot passes.</p>
                    </div>
                    <button type="button" class="fm-action-btn primary" data-offer-post>Post my slot</button>
                </div>

                <div class="fm-friendly-invite-form" data-offer-form hidden>
                    <div class="fm-friendly-block">
                        <h4>Advertise a slot</h4>
                        <p class="fm-subtle">Pick the slot. It will be published until that day ends.</p>
                        <select class="fm-input" data-offer-slot>
                            <option value="1">Day 1</option>
                            <option value="3">Day 5</option>
                        </select>
                        <button type="button" class="fm-action-btn primary" data-offer-post-confirm>Advertise</button>
                        <button type="button" class="fm-action-btn secondary" data-offer-cancel>Cancel</button>
                    </div>
                </div>

                ${mineList.length ? `
                <div class="fm-friendly-block">
                    <h4>Yours</h4>
                    ${mineList.map(o => offerRow(o, teamId)).join('')}
                </div>` : ''}

                <div class="fm-friendly-block">
                    <h4>Available from other clubs</h4>
                    ${list.length ? list.map(o => offerRow(o, teamId)).join('')
                                 : '<div class="fm-empty">Nothing posted this week yet.</div>'}
                </div>

                <div class="fm-friendly-message" data-offer-message hidden></div>
            </section>`;
    }

    return { loadBoard, loadMine, buildHtml };
}