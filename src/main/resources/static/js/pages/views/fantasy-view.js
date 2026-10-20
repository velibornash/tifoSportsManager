// fantasy-view.js
//
// The fantasy league for one country: a Setup tab and a League tab (owner, 2026-10-17).
//
// WHY THIS IS NOT THE SHARED lineup-board.js
//
// Match Setup and Tactics Setup share one board on purpose — they are the same job, picking an eleven for
// a club, and two renderers for one job is how they drift apart. Fantasy is not that job:
//
//   * the bench is four, not seven — a reserve goalkeeper and three substitutes;
//   * the formation is chosen from a fixed list, and the eleven is eleven slots that are always on screen
//     rather than a set of players that happens to have eleven of them;
//   * every player carries a price and the squad has a budget, which the club board knows nothing about;
//   * the pool is a whole country's pyramid — 7,730 players in Serbia — not one club's twenty-five.
//
// Forcing the club board to do all four would mean a component whose every branch asks which game it is
// in, which is the drift the shared board exists to prevent.
//
// THE SLOTS ARE THE POINT (owner, 2026-10-18)
//
// "slotovi za 11 igraca plus 4 zamene bi trebalo uvek da stoje pa da ih popunjava kako dodajemo kojeg
// igraca." So the pitch draws eleven empty slots and four empty bench slots from the first render, and
// adding a player fills one rather than redrawing the shape. The formation picker above rearranges which
// slots exist; the eleven is then read off the slots.
//
// A formation is not stored anywhere. It is the *shape of the eleven* — 4-4-2 and 4-3-3 are different
// counts of defenders, midfielders and attackers — so it is derived from the starters on load and is
// lossless. Storing it as well would be a second copy of something already written down.
//
// The client-side checks are a COURTESY. The server validates every save against the pool it just built,
// and its refusals are what is shown when they arrive. Nothing here is a gate.

import { escapeHtml } from '../../ui/escape.js';
import { authFetch } from '../../auth.js';

const POSITIONS = ['GK', 'DEF', 'MID', 'ATT'];
const POSITION_LABEL = { GK: 'Goalkeepers', DEF: 'Defenders', MID: 'Midfielders', ATT: 'Attackers' };
const POSITION_SHORT = { GK: 'GK', DEF: 'DEF', MID: 'MID', ATT: 'ATT' };

/** The squad shape, mirroring FantasyRules. 2 goalkeepers, 5 defenders, 5 midfielders, 3 attackers. */
const SQUAD_SLOTS = { GK: 2, DEF: 5, MID: 5, ATT: 3 };
const SQUAD_SIZE = 15;
const STARTERS = 11;
const BENCH = 4;
const PER_TIER = 3;
const MAX_PER_CLUB = 2;

/**
 * The formations a manager may choose, and every one of them is legal against the XI minimums
 * (1 GK, 3 DEF, 2 MID, 1 ATT): the smallest number of midfielders here is 2 and the smallest number of
 * attackers is 1. A formation that could not be fielded would be a slot grid that cannot be filled.
 */
const FORMATIONS = ['3-4-3', '3-5-2', '4-4-2', '4-3-3', '4-5-1', '5-2-3', '5-3-2', '5-4-1'];

/** One colour a tier, so a squad's spread across the pyramid can be read at a glance. */
const TIER_COLOUR = {
    1: '#f5c542',
    2: '#7dd3fc',
    3: '#a78bfa',
    4: '#34d399',
    5: '#94a3b8',
};

const STYLE_ID = 'fm-fantasy-styles';

export function createFantasyView() {
    let state = {
        iso: '',
        pool: null,
        squad: null,
        // { playerId, starter, captain }
        picks: [],
        formation: '4-4-2',
        tab: 'setup',
        filters: { position: 'ALL', tier: 'ALL', club: 'ALL', query: '' },
        // Sorted by rating, dearest first, which is the order a manager scans a list of players in. The
        // sort survives a filter change and a re-render because it is state, not a DOM artefact.
        sort: { key: 'rating', dir: -1 },
        menuFor: null,
        swapFor: null,
        standings: null,
        playerPoints: [],
        busy: false,
        message: '',
        error: '',
        violations: [],
    };

    async function loadFantasy(options = {}) {
        state.tab = options.tab === 'league' ? 'league' : 'setup';
        state.message = '';
        state.error = '';
        state.violations = [];
        state.menuFor = null;
        state.swapFor = null;
        ensureStyles();

        const iso = String(window.fmCountryIsoCode || '').toUpperCase();
        state.iso = iso;
        if (!iso) {
            renderEmpty('Your account has no country, so there is no fantasy league to show.');
            return;
        }

        render(`<section class="fm-panel"><div class="fm-panel-head">Fantasy</div>
            <p class="fm-subtle">Loading the ${escapeHtml(iso)} pyramid…</p></section>`);

        let poolRes;
        try {
            poolRes = await authFetch(`/api/fantasy/${encodeURIComponent(iso)}/pool`);
        } catch (err) {
            renderEmpty('Could not load the fantasy pool (' + escapeHtml(err.message) + ').');
            return;
        }
        if (poolRes.status === 404) {
            renderEmpty('There is no pyramid to pick from in ' + escapeHtml(iso) + ' yet.');
            return;
        }
        state.pool = await poolRes.json();

        if (!state.pool.eligible) {
            render(`<section class="fm-panel"><div class="fm-panel-head">Fantasy</div>
                <p class="fm-subtle">${escapeHtml(state.pool.reason || 'This country cannot host a fantasy league.')}</p>
                <p class="fm-subtle">The pool here has ${escapeHtml(String(state.pool.tierCount))} tier(s).</p>
            </section>`);
            return;
        }

        try {
            const squadRes = await authFetch(`/api/fantasy/${encodeURIComponent(iso)}/squad`);
            state.squad = squadRes.ok ? await squadRes.json() : null;
        } catch (err) {
            state.squad = null;
        }
        state.picks = (state.squad?.picks || []).map(p => ({
            playerId: p.player.playerId,
            starter: !!p.starter,
            captain: !!p.captain,
        }));
        state.formation = formationOfStarters() || '4-4-2';

        if (state.tab === 'league') {
            await loadLeagueTab();
        }
        renderTab();
    }

    // ------------------------------------------------------------------ the tabs

    function renderTab() {
        if (state.tab === 'league') {
            renderLeagueTab();
            return;
        }
        renderSetupTab();
    }

    /** True when a squad that already exists cannot be changed right now. */
    function lockedOut() {
        return !!state.pool && !state.pool.weekOpen && !!state.squad;
    }

    function renderSetupTab() {
        const pool = state.pool;
        const squad = squadPlayers();
        const pickOf = pickMap();
        const spent = squad.reduce((sum, p) => sum + p.price, 0);
        const remaining = Math.round((pool.budget - spent) * 10) / 10;

        render(`
            ${tabBar('setup')}
            ${lockBanner()}
            ${budgetBar(spent, remaining, pool.budget, squad.length)}
            ${transferPanel()}
            ${squadNeeds(squad)}
            ${state.error ? errorPanel(state.error) : ''}
            ${state.violations.length ? violationsPanel(state.violations) : ''}
            ${state.message ? `<section class="fm-panel fm-fantasy-ok"><p class="fm-subtle">${escapeHtml(state.message)}</p></section>` : ''}
            <section class="fm-panel">
                <div class="fm-panel-head">Formation</div>
                <div class="fm-fantasy-formations">
                    ${FORMATIONS.map(f => `<button type="button"
                        class="fm-action-btn ${state.formation === f ? '' : 'secondary'} js-fantasy-formation"
                        data-formation="${f}">${f}</button>`).join('')}
                </div>
                <p class="fm-subtle" style="margin-top:10px;">Changing the formation rearranges the eleven.
                    Anyone who no longer fits moves to the bench.</p>
            </section>
            <section class="fm-panel">
                <div class="fm-panel-head">Your squad</div>
                ${state.swapFor !== null ? `<p class="fm-subtle fm-fantasy-swapping">Pick the player who comes on.
                    <button type="button" class="fm-action-btn secondary js-fantasy-cancel-swap">Cancel</button></p>` : ''}
                ${tierLegend(squad)}
                ${pitch(pickOf)}
                <div class="fm-fantasy-actions">
                    <button type="button" class="fm-action-btn js-fantasy-save" ${state.busy || lockedOut() ? 'disabled' : ''}>
                        ${state.busy ? 'Saving…' : 'Save squad'}
                    </button>
                    <button type="button" class="fm-action-btn secondary js-fantasy-clear" ${state.busy || lockedOut() ? 'disabled' : ''}>
                        Clear
                    </button>
                </div>
                <p class="fm-subtle" style="margin-top:10px;">
                    Click a filled slot for captain, a substitution or to drop him. Click an empty slot to
                    filter the list below by that position.
                    ${lockedOut() ? 'Nothing here can be saved until the next gameweek opens.' : ''}
                </p>
            </section>
            ${playerList(squad)}
        `);
        bindSetup();
    }

    async function loadLeagueTab() {
        const [standingsRes, pointsRes] = await Promise.allSettled([
            authFetch(`/api/fantasy/${encodeURIComponent(state.iso)}/league`),
            authFetch(`/api/fantasy/${encodeURIComponent(state.iso)}/player-points?limit=20`),
        ]);
        state.standings = standingsRes.status === 'fulfilled' && standingsRes.value.ok
            ? await standingsRes.value.json() : null;
        state.playerPoints = pointsRes.status === 'fulfilled' && pointsRes.value.ok
            ? await pointsRes.value.json() : [];
    }

    function renderLeagueTab() {
        const rows = state.standings?.standings || [];
        const points = state.playerPoints || [];

        render(`
            ${tabBar('league')}
            <section class="fm-panel">
                <div class="fm-panel-head">${escapeHtml(state.standings?.countryName || state.iso)} fantasy league</div>
                ${rows.length ? `
                    <div class="fm-squad-wrap fm-fantasy-table-wrap">
                    <table class="fm-table">
                        <thead><tr><th>#</th><th>Manager</th><th>Points</th><th title="Points lost to extra changes">Churn</th></tr></thead>
                        <tbody>
                            ${rows.map((row, i) => `<tr>
                                <td>${i + 1}</td>
                                <td>${escapeHtml(row.manager || '—')}</td>
                                <td><strong>${escapeHtml(String(row.points))}</strong></td>
                                <td>${row.transferCost ? '−' + escapeHtml(String(row.transferCost)) : '—'}</td>
                            </tr>`).join('')}
                        </tbody>
                    </table>
                    </div>` : `<p class="fm-subtle">Nobody has entered this league yet.</p>`}
            </section>
            <section class="fm-panel">
                <div class="fm-panel-head">Top players</div>
                ${points.length ? `
                    <div class="fm-squad-wrap fm-fantasy-table-wrap">
                    <table class="fm-table">
                        <thead><tr><th>Player</th><th>Club</th><th>Pos</th><th>Tier</th><th>Points</th></tr></thead>
                        <tbody>
                            ${points.map(p => `<tr>
                                <td>${escapeHtml(p.name || '—')}</td>
                                <td>${escapeHtml(p.clubName || '—')}</td>
                                <td>${escapeHtml(p.position || '—')}</td>
                                <td>${escapeHtml(String(p.tier ?? '—'))}</td>
                                <td><strong>${escapeHtml(String(p.points))}</strong></td>
                            </tr>`).join('')}
                        </tbody>
                    </table>
                    </div>` : `<p class="fm-subtle">No gameweek has been scored yet.</p>`}
            </section>
        `);
        bindTabBar();
    }

    // ------------------------------------------------------------------ pieces

    function tabBar(active) {
        return `<section class="fm-panel fm-fantasy-tabs">
            <button type="button" class="fm-action-btn ${active === 'setup' ? '' : 'secondary'} js-fantasy-tab" data-tab="setup">Setup</button>
            <button type="button" class="fm-action-btn ${active === 'league' ? '' : 'secondary'} js-fantasy-tab" data-tab="league">League</button>
        </section>`;
    }

    /**
     * The deadline, said out loud and before anything is picked.
     *
     * <p>A manager who builds a whole squad and is only then told the gameweek is locked has been wasted,
     * and he learns it from a red error rather than from the calendar.
     */
    function lockBanner() {
        if (!state.pool || state.pool.weekOpen) {
            return '';
        }
        if (!state.squad) {
            return `<section class="fm-panel fm-fantasy-msg"><p class="fm-subtle">
                This gameweek has already kicked off, so a new squad starts scoring <strong>next</strong> week.
                You can still pick one now.</p></section>`;
        }
        // Editing stays open so the picker can be explored, but Save is off — so the banner has to say
        // plainly that anything done now is not kept, or a manager would do a gameweek's work for nothing.
        return `<section class="fm-panel fm-fantasy-bad"><p class="fm-subtle">
            ${escapeHtml(state.pool.lockReason || 'This gameweek is locked.')}</p>
            <p class="fm-subtle"><strong>You can still look and try things</strong>, but nothing can be saved
            until the next gameweek opens.</p></section>`;
    }

    function budgetBar(spent, remaining, budget, count) {
        const pct = Math.max(0, Math.min(100, (spent / budget) * 100));
        const over = remaining < 0;
        return `<section class="fm-panel">
            <div class="fm-panel-head">Budget</div>
            <div class="fm-fantasy-budget ${over ? 'is-over' : ''}">
                <span><strong>${fmt(spent)}</strong> spent</span>
                <span class="${over ? 'fm-fantasy-over' : ''}">${over ? fmt(-remaining) + ' over' : fmt(remaining) + ' left'}</span>
                <span>${count}/${SQUAD_SIZE} players</span>
            </div>
            <div class="fm-fantasy-bar"><span style="width:${pct}%"></span></div>
        </section>`;
    }

    /**
     * What this gameweek's changes cost.
     *
     * <p>Read from the server's numbers and never recomputed here. A second implementation of the rule in
     * the browser would be a second answer to "what do I owe", and the one the manager believes would not
     * be the one he is charged.
     */
    function transferPanel() {
        const saved = state.squad;
        if (!saved) {
            return `<section class="fm-panel"><div class="fm-panel-head">Changes this gameweek</div>
                <p class="fm-subtle">Joining is free — your first squad costs nothing.</p></section>`;
        }
        const changes = Number(saved.changes || 0);
        const cost = Number(saved.transferCost || 0);
        const free = Number(saved.freeChangesPerWeek ?? 1);
        const per = Number(saved.costPerExtraChange ?? 4);
        return `<section class="fm-panel ${cost > 0 ? 'fm-fantasy-bad' : 'fm-fantasy-ok'}">
            <div class="fm-panel-head">Changes this gameweek</div>
            <p class="fm-subtle">${changes} change${changes === 1 ? '' : 's'} against the squad you started the
                week with. The first ${free} ${free === 1 ? 'is' : 'are'} free; each one after that costs ${per}
                points.</p>
            <p class="fm-subtle">${cost > 0
                ? `That is <strong>${cost} points</strong> off this gameweek.`
                : 'Nothing to pay.'}</p>
        </section>`;
    }

    /**
     * The colour legend: one colour a tier, and how many of each are in the squad.
     *
     * <p>The counts are the reason it exists. "Three from each tier" is the rule that sends a manager to the
     * fifth division, and the only way to know how many he has of each is to count colours.
     */
    function tierLegend(squad) {
        const byTier = count(squad, p => p.tier);
        const tiers = Array.from({ length: state.pool.tierCount }, (_, i) => i + 1);
        return `<div class="fm-fantasy-legend">
            ${tiers.map(tier => {
                const have = byTier[tier] || 0;
                const full = have === PER_TIER;
                return `<span class="fm-fantasy-legend-item ${full ? 'is-full' : ''}">
                    <i style="background:${TIER_COLOUR[tier] || '#94a3b8'}"></i>Tier ${tier} · ${have}/${PER_TIER}
                </span>`;
            }).join('')}
        </div>`;
    }

    /** What is still missing, so a manager is not told only after he presses save. */
    function squadNeeds(squad) {
        const byPos = count(squad, p => p.fantasyPosition);
        const problems = [];

        const wrongPos = POSITIONS.filter(pos => (byPos[pos] || 0) !== SQUAD_SLOTS[pos]);
        if (wrongPos.length) {
            problems.push(wrongPos.map(pos =>
                `${POSITION_LABEL[pos]}: ${byPos[pos] || 0}/${SQUAD_SLOTS[pos]}`).join(' · '));
        }
        const byTier = count(squad, p => p.tier);
        const wrongTier = [];
        for (let tier = 1; tier <= state.pool.tierCount; tier++) {
            if ((byTier[tier] || 0) !== PER_TIER) {
                wrongTier.push(`T${tier}: ${byTier[tier] || 0}/${PER_TIER}`);
            }
        }
        if (wrongTier.length) {
            problems.push(wrongTier.join(' · '));
        }
        const byClub = count(squad, p => p.clubName);
        const overClub = Object.entries(byClub).filter(([, n]) => n > MAX_PER_CLUB);
        if (overClub.length) {
            problems.push(overClub.map(([club, n]) => `${club} ${n}/${MAX_PER_CLUB}`).join(' · '));
        }

        if (!problems.length && squad.length === SQUAD_SIZE) {
            return `<section class="fm-panel fm-fantasy-ok"><p class="fm-subtle">The squad is legal. Press Save.</p></section>`;
        }
        return `<section class="fm-panel">
            <div class="fm-panel-head">Still to do</div>
            <p class="fm-subtle">${escapeHtml(problems.join('  •  ') || `Pick ${SQUAD_SIZE - squad.length} more.`)}</p>
        </section>`;
    }

    function errorPanel(text) {
        return `<section class="fm-panel fm-fantasy-bad">
            <div class="fm-panel-head">That one cannot go in</div>
            <p class="fm-subtle">${escapeHtml(text)}</p>
        </section>`;
    }

    function violationsPanel(violations) {
        return `<section class="fm-panel fm-fantasy-bad">
            <div class="fm-panel-head">The server refused the squad</div>
            <ul class="fm-fantasy-list">
                ${violations.map(v => `<li>${escapeHtml(v.message)}</li>`).join('')}
            </ul>
        </section>`;
    }

    // ------------------------------------------------------------------ the pitch

    /** The eleven slots for the chosen formation, in the order they are drawn. */
    function formationSlots() {
        const [def, mid, att] = state.formation.split('-').map(Number);
        return ['GK', ...Array(def).fill('DEF'), ...Array(mid).fill('MID'), ...Array(att).fill('ATT')];
    }

    /** The shape of the eleven that is actually picked, or null when it is not eleven. */
    function formationOfStarters() {
        const pickOf = pickMap();
        const starters = squadPlayers().filter(p => pickOf.get(p.playerId)?.starter);
        if (starters.length !== STARTERS) {
            return null;
        }
        const byPos = count(starters, p => p.fantasyPosition);
        return `${byPos.DEF || 0}-${byPos.MID || 0}-${byPos.ATT || 0}`;
    }

    /**
     * The players laid out in the slots.
     *
     * <p>Slots are filled in order — for each slot, the next unclaimed starter of that position — so the
     * layout is deterministic and needs no slot index stored on a pick. A manager chooses his eleven; the
     * pitch decides where on it they stand, which is also what FPL does.
     */
    function layout() {
        const pickOf = pickMap();
        const starters = squadPlayers()
            .filter(p => pickOf.get(p.playerId)?.starter)
            .sort((a, b) => b.rating - a.rating);

        const remaining = { GK: [], DEF: [], MID: [], ATT: [] };
        starters.forEach(p => (remaining[p.fantasyPosition] || (remaining[p.fantasyPosition] = [])).push(p));

        const xi = formationSlots().map(position => {
            const player = (remaining[position] || []).shift() || null;
            return { position, player };
        });

        const starterIds = new Set(starters.map(p => p.playerId));
        const bench = squadPlayers()
            .filter(p => !starterIds.has(p.playerId))
            .sort((a, b) => (a.fantasyPosition === 'GK' ? -1 : 1) - (b.fantasyPosition === 'GK' ? -1 : 1));

        // The bench is the reserve goalkeeper and three outfield substitutes, in that order, always four
        // slots whether or not they are filled.
        const benchSlots = [{ position: 'GK', player: null }, ...Array(BENCH - 1).fill(null).map(() => ({ position: 'ANY', player: null }))];
        const benchPool = [...bench];
        benchSlots.forEach(slot => {
            if (slot.position === 'GK') {
                slot.player = benchPool.find(p => p.fantasyPosition === 'GK') || null;
            } else {
                slot.player = benchPool.find(p => p.fantasyPosition !== 'GK') || null;
            }
            if (slot.player) {
                benchPool.splice(benchPool.indexOf(slot.player), 1);
            }
        });

        return { xi, benchSlots, pickOf };
    }

    function pitch(pickOf) {
        const { xi, benchSlots } = layout();
        const rows = ['ATT', 'MID', 'DEF', 'GK']
            .map(pos => {
                const line = xi.filter(slot => slot.position === pos);
                if (!line.length) return '';
                return `<div class="fm-fantasy-line">${line.map(slot => slotCard(slot, pickOf, false)).join('')}</div>`;
            })
            .filter(Boolean)
            .join('');

        return `<div class="fm-fantasy-pitch">
            <div class="fm-fantasy-xi">${rows}</div>
            <div class="fm-fantasy-bench-label">Bench — the reserve goalkeeper and three substitutes. They score nothing.</div>
            <div class="fm-fantasy-bench">${benchSlots.map(slot => slotCard(slot, pickOf, true)).join('')}</div>
        </div>`;
    }

    function slotCard(slot, pickOf, bench) {
        const player = slot.player;
        if (!player) {
            return `<button type="button" class="fm-fantasy-card is-empty js-fantasy-empty-slot"
                    data-position="${slot.position === 'ANY' ? 'ALL' : slot.position}"
                    title="Empty ${slot.position === 'ANY' ? 'bench' : slot.position} slot">
                <span class="fm-fantasy-card-pos">${escapeHtml(slot.position === 'ANY' ? 'SUB' : POSITION_SHORT[slot.position])}</span>
                <span class="fm-fantasy-card-empty">+</span>
            </button>`;
        }

        const pick = pickOf.get(player.playerId);
        const captain = !!pick?.captain;
        const swapping = state.swapFor !== null;
        const isSwapTarget = swapping && state.swapFor !== player.playerId
            && !!pick?.starter !== !bench;
        const open = state.menuFor === player.playerId;
        const colour = TIER_COLOUR[player.tier] || '#94a3b8';

        return `<div class="fm-fantasy-card-wrap">
            <button type="button" class="fm-fantasy-card ${bench ? 'is-bench' : ''} ${captain ? 'is-captain' : ''}
                    ${isSwapTarget ? 'is-swap-target' : ''} js-fantasy-pitch-card"
                    style="border-left:4px solid ${colour}"
                    data-player-id="${player.playerId}" data-bench="${bench ? '1' : '0'}"
                    title="${escapeHtml(player.name)} · ${escapeHtml(player.clubName)} · tier ${escapeHtml(String(player.tier))} · rating ${escapeHtml(String(player.rating))}">
                <span class="fm-fantasy-card-pos" style="color:${colour}">${escapeHtml(player.fantasyPosition)}${captain ? ' ©' : ''}</span>
                <span class="fm-fantasy-card-name">${escapeHtml(shortName(player.name))}</span>
                <span class="fm-fantasy-card-price">${fmt(player.price)}</span>
            </button>
            ${open ? playerMenu(player, bench) : ''}
        </div>`;
    }

    /** FPL's menu: captain, substitute, remove. Only the actions that make sense for the slot. */
    function playerMenu(player, bench) {
        const id = player.playerId;
        return `<div class="fm-fantasy-menu" role="menu">
            ${!bench ? `<button type="button" role="menuitem" class="fm-fantasy-menu-item js-fantasy-captain" data-player-id="${id}">Captain</button>` : ''}
            <button type="button" role="menuitem" class="fm-fantasy-menu-item js-fantasy-substitute" data-player-id="${id}">Substitute</button>
            <button type="button" role="menuitem" class="fm-fantasy-menu-item js-fantasy-drop" data-player-id="${id}" title="A change against this gameweek's baseline">Remove</button>
            <button type="button" role="menuitem" class="fm-fantasy-menu-item fm-fantasy-menu-cancel js-fantasy-close-menu">Cancel</button>
        </div>`;
    }

    // ------------------------------------------------------------------ the list

    /**
     * The player list, with filters that narrow each other.
     *
     * <p>Owner, 2026-10-18: *"ako izaberem tier 1 - u club filteru vidim samo timove iz tier 1"*. So the
     * club choices come from the players the other filters already allow, and a club that is no longer
     * reachable is dropped from the filter rather than left selected and showing nothing.
     */
    function playerList(squad) {
        const chosen = new Set(squad.map(p => p.playerId));
        const filters = state.filters;

        const matching = state.pool.players.filter(p => {
            if (filters.position !== 'ALL' && p.fantasyPosition !== filters.position) return false;
            if (filters.tier !== 'ALL' && String(p.tier) !== String(filters.tier)) return false;
            if (filters.query && !p.name.toLowerCase().includes(filters.query.toLowerCase())) return false;
            return true;
        });

        // A club is offered only when the current tier and search still have players at it, so the second
        // dropdown can never select something the first has excluded.
        const clubs = Array.from(new Set(matching.map(p => p.clubName))).sort();
        if (filters.club !== 'ALL' && !clubs.includes(filters.club)) {
            state.filters.club = 'ALL';
        }

        const rows = sortRows(matching.filter(p => filters.club === 'ALL' || p.clubName === filters.club));
        const shown = rows.slice(0, 150);
        const tierOf = (club) => {
            const found = matching.find(p => p.clubName === club);
            return found ? found.tier : null;
        };

        return `<section class="fm-panel">
            <div class="fm-panel-head">Players (${rows.length} match${rows.length === 1 ? '' : 'es'})</div>
            <div class="fm-fantasy-filters">
                <select class="js-fantasy-filter" data-filter="position">
                    <option value="ALL">All positions</option>
                    ${POSITIONS.map(p => `<option value="${p}" ${filters.position === p ? 'selected' : ''}>${POSITION_LABEL[p]}</option>`).join('')}
                </select>
                <select class="js-fantasy-filter" data-filter="tier">
                    <option value="ALL">All tiers</option>
                    ${Array.from({ length: state.pool.tierCount }, (_, i) => i + 1).map(t =>
                        `<option value="${t}" ${String(filters.tier) === String(t) ? 'selected' : ''}>Tier ${t}</option>`).join('')}
                </select>
                <select class="js-fantasy-filter" data-filter="club">
                    <option value="ALL">All clubs${filters.tier === 'ALL' ? '' : ' in tier ' + escapeHtml(String(filters.tier))}</option>
                    ${clubs.map(c => {
                        const tier = tierOf(c);
                        const colour = TIER_COLOUR[tier] || '#94a3b8';
                        return `<option value="${escapeHtml(c)}" ${filters.club === c ? 'selected' : ''}>T${tier} · ${escapeHtml(c)}</option>`;
                    }).join('')}
                </select>
                <input class="js-fantasy-filter" data-filter="query" type="search" placeholder="Search a name"
                       value="${escapeHtml(filters.query)}">
            </div>
            ${rows.length > shown.length
                ? `<p class="fm-subtle">Showing the first ${shown.length}. Narrow the filters to see the rest.</p>`
                : ''}
                    <div class="fm-squad-wrap fm-fantasy-table-wrap">
                    <table class="fm-table">
                <thead><tr>
                    ${sortableTh('name', 'Player')}
                    ${sortableTh('position', 'Pos')}
                    ${sortableTh('club', 'Club')}
                    ${sortableTh('tier', 'Tier')}
                    ${sortableTh('rating', 'Rating')}
                    ${sortableTh('price', 'Price')}
                    ${sortableTh('points', 'Points')}
                    <th></th>
                </tr></thead>
                <tbody>
                    ${shown.map(p => {
                        const picked = chosen.has(p.playerId);
                        const colour = TIER_COLOUR[p.tier] || '#94a3b8';
                        return `<tr class="${picked ? 'fm-fantasy-picked' : ''}">
                            <td>${escapeHtml(p.name)}</td>
                            <td>${escapeHtml(p.fantasyPosition)}</td>
                            <td>${escapeHtml(p.clubName)}</td>
                            <td><span class="fm-fantasy-tier-dot" style="background:${colour}"></span>${escapeHtml(String(p.tier))}</td>
                            <td>${escapeHtml(String(p.rating))}</td>
                            <td>${fmt(p.price)}</td>
                            <td>${escapeHtml(String(p.points ?? 0))}</td>
                            <td>${picked
                                ? `<button type="button" class="fm-action-btn secondary js-fantasy-remove" data-player-id="${p.playerId}">Drop</button>`
                                : `<button type="button" class="fm-action-btn js-fantasy-add" data-player-id="${p.playerId}">Add</button>`}</td>
                        </tr>`;
                    }).join('')}
                </tbody>
            </table>
            </div>
        </section>`;
    }

    // ------------------------------------------------------------------ behaviour

    function bindSetup() {
        bindTabBar();

        document.querySelectorAll('.js-fantasy-filter').forEach(el => {
            el.addEventListener(el.tagName === 'SELECT' ? 'change' : 'input', () => {
                state.filters[el.dataset.filter] = el.value;
                state.error = '';
                renderSetupTab();
            });
        });

        document.querySelectorAll('.js-fantasy-sort').forEach(el =>
            el.addEventListener('click', () => {
                const key = el.dataset.sortKey;
                if (state.sort.key === key) {
                    state.sort.dir = -state.sort.dir;
                } else {
                    // A new column starts descending, because every column here is a number a manager wants
                    // the best of first, and the two that are words read fine either way.
                    state.sort = { key, dir: -1 };
                }
                state.error = '';
                renderSetupTab();
            }));

        document.querySelectorAll('.js-fantasy-formation').forEach(el =>
            el.addEventListener('click', () => applyFormation(el.dataset.formation)));

        document.querySelectorAll('.js-fantasy-empty-slot').forEach(el =>
            el.addEventListener('click', () => {
                state.filters.position = el.dataset.position === 'ALL' ? 'ALL' : el.dataset.position;
                state.filters.club = 'ALL';
                state.error = '';
                renderSetupTab();
            }));

        document.querySelectorAll('.js-fantasy-add').forEach(el =>
            el.addEventListener('click', () => addPlayer(Number(el.dataset.playerId))));
        document.querySelectorAll('.js-fantasy-remove').forEach(el =>
            el.addEventListener('click', () => removePlayer(Number(el.dataset.playerId))));

        document.querySelectorAll('.js-fantasy-pitch-card').forEach(el =>
            el.addEventListener('click', () => onPitchCardClick(Number(el.dataset.playerId))));

        document.querySelectorAll('.js-fantasy-captain').forEach(el =>
            el.addEventListener('click', () => setCaptain(Number(el.dataset.playerId))));
        document.querySelectorAll('.js-fantasy-substitute').forEach(el =>
            el.addEventListener('click', () => startSwap(Number(el.dataset.playerId))));
        document.querySelectorAll('.js-fantasy-drop').forEach(el =>
            el.addEventListener('click', () => removePlayer(Number(el.dataset.playerId))));
        document.querySelectorAll('.js-fantasy-close-menu').forEach(el =>
            el.addEventListener('click', () => { state.menuFor = null; renderSetupTab(); }));
        document.querySelector('.js-fantasy-cancel-swap')?.addEventListener('click', () => {
            state.swapFor = null;
            state.menuFor = null;
            renderSetupTab();
        });

        document.querySelector('.js-fantasy-save')?.addEventListener('click', () => void save());
        document.querySelector('.js-fantasy-clear')?.addEventListener('click', () => {
            state.picks = [];
            state.violations = [];
            state.message = '';
            state.error = '';
            state.menuFor = null;
            state.swapFor = null;
            renderSetupTab();
        });
    }

    function bindTabBar() {
        document.querySelectorAll('.js-fantasy-tab').forEach(el => {
            el.addEventListener('click', async () => {
                state.tab = el.dataset.tab;
                if (state.tab === 'league') {
                    await loadLeagueTab();
                }
                renderTab();
            });
        });
    }

    /**
     * Changing the formation rearranges the eleven.
     *
     * <p>Players who still have a slot keep it; anyone whose position has fewer slots than before moves to
     * the bench rather than being dropped, because a formation is a choice about the shape and not about who
     * is in the squad. The armband follows the eleven.
     */
    function applyFormation(formation) {
        if (!FORMATIONS.includes(formation)) return;
        state.formation = formation;
        state.menuFor = null;
        state.swapFor = null;
        state.error = '';

        const pickOf = pickMap();
        const squad = squadPlayers();

        // **The whole squad, not just the eleven.** A new formation can want more of a position than the
        // old one had, and taking only the current starters would leave that slot empty while a suitable
        // player sat one row below it. Current starters come first, so a rearrangement keeps the eleven
        // the manager already chose and only fills the gaps from the bench.
        const byPos = { GK: [], DEF: [], MID: [], ATT: [] };
        POSITIONS.forEach(pos => {
            byPos[pos] = squad
                .filter(p => p.fantasyPosition === pos)
                .sort((a, b) => {
                    const aStart = pickOf.get(a.playerId)?.starter ? 1 : 0;
                    const bStart = pickOf.get(b.playerId)?.starter ? 1 : 0;
                    if (aStart !== bStart) return bStart - aStart;
                    return b.rating - a.rating;
                });
        });

        const wanted = count(formationSlots(), pos => pos);
        const starters = new Set();
        POSITIONS.forEach(pos => {
            (byPos[pos] || []).slice(0, wanted[pos] || 0).forEach(p => starters.add(p.playerId));
        });

        state.picks.forEach(p => { p.starter = starters.has(p.playerId); });
        ensureCaptain();
        state.message = '';
        renderSetupTab();
    }

    /**
     * A click on a slot: completes a substitution if one is in progress, opens the menu otherwise.
     *
     * <p>The swap state is checked first, because during a swap every click on the bench is an answer to
     * the question the page is asking, and opening a menu instead would be the page ignoring its own
     * instruction.
     */
    function onPitchCardClick(playerId) {
        if (state.swapFor !== null) {
            if (playerId === state.swapFor) {
                state.swapFor = null;
                renderSetupTab();
                return;
            }
            completeSwap(state.swapFor, playerId);
            return;
        }
        state.menuFor = state.menuFor === playerId ? null : playerId;
        state.error = '';
        renderSetupTab();
    }

    /**
     * Swaps a starter and a substitute.
     *
     * <p><b>The formation is the constraint, so a swap has to be like for like.</b> The eleven is eleven
     * slots of named positions, so bringing a midfielder on for a defender would silently change the shape
     * the manager chose — which is a formation change, and he has a picker for that. Anything else is
     * refused with the reason.
     */
    function completeSwap(outId, inId) {
        const a = state.picks.find(p => p.playerId === outId);
        const b = state.picks.find(p => p.playerId === inId);
        if (!a || !b || a.starter === b.starter) {
            state.swapFor = null;
            renderSetupTab();
            return;
        }
        const byId = new Map(state.pool.players.map(p => [p.playerId, p]));
        const out = byId.get(outId);
        const on = byId.get(inId);
        if (out && on && out.fantasyPosition !== on.fantasyPosition) {
            state.swapFor = null;
            state.menuFor = null;
            state.error = `Your formation is ${state.formation}: a ${POSITION_SHORT[out.fantasyPosition]} slot can `
                + `only be filled by a ${POSITION_SHORT[out.fantasyPosition]}. Pick another formation first, or `
                + `bring on a ${POSITION_SHORT[out.fantasyPosition]}.`;
            renderSetupTab();
            return;
        }

        const aStarter = a.starter;
        a.starter = b.starter;
        b.starter = aStarter;

        // The armband follows the eleven: a captain who has just been taken off cannot keep it.
        const outgoing = state.picks.find(p => p.playerId === outId);
        if (outgoing?.captain && !outgoing.starter) {
            state.picks.forEach(p => { p.captain = p.playerId === inId; });
        }
        state.message = '';
        state.error = '';
        state.swapFor = null;
        state.menuFor = null;
        renderSetupTab();
    }

    function startSwap(playerId) {
        state.swapFor = playerId;
        state.menuFor = null;
        state.error = '';
        state.message = '';
        renderSetupTab();
    }

    /**
     * Adds a player, or says exactly why he cannot go in.
     *
     * <p>Owner, 2026-10-18: *"kada izaberemo nedozvoljenu opciju … javiti jasnu gresku"*. So each rule is
     * checked on its own and named — the third goalkeeper, the fourth attacker, the sixth defender, the
     * third player from one club, the fourth from one tier, a player who would break the budget — rather
     * than one generic refusal. The order matters: the most specific reason a manager can act on comes
     * first, and "your squad is full" would hide all of them.
     */
    function addPlayer(playerId) {
        const player = state.pool.players.find(p => p.playerId === playerId);
        if (!player) return;
        state.error = '';
        state.message = '';

        const squad = squadPlayers();
        const pickOf = pickMap();

        if (state.picks.some(p => p.playerId === playerId)) {
            return refuse(`${player.name} is already in your squad.`);
        }

        const samePos = squad.filter(p => p.fantasyPosition === player.fantasyPosition).length;
        const posSlots = SQUAD_SLOTS[player.fantasyPosition];
        if (samePos >= posSlots) {
            const ordinals = { GK: 'goalkeepers', DEF: 'defenders', MID: 'midfielders', ATT: 'attackers' };
            return refuse(samePos === 1
                ? `You already have ${samePos} ${player.fantasyPosition === 'ATT' ? 'attacker' : POSITION_LABEL[player.fantasyPosition].toLowerCase()}. `
                    + `A squad holds ${posSlots} ${ordinals[player.fantasyPosition]}, so ${player.name} cannot go in.`
                : `You already have ${samePos} ${ordinals[player.fantasyPosition]}, which is the maximum. `
                    + `Drop one before adding ${player.name}.`);
        }

        const fromClub = squad.filter(p => p.clubName === player.clubName).length;
        if (fromClub >= MAX_PER_CLUB) {
            return refuse(`You already have ${fromClub} players from ${player.clubName}. `
                + `The limit is ${MAX_PER_CLUB} from any one club.`);
        }

        const fromTier = squad.filter(p => p.tier === player.tier).length;
        if (fromTier >= PER_TIER) {
            return refuse(`You already have ${fromTier} players from tier ${player.tier}. `
                + `Exactly ${PER_TIER} must come from each tier, so drop one from tier ${player.tier} first.`);
        }

        if (state.picks.length >= SQUAD_SIZE) {
            return refuse(`A squad is ${SQUAD_SIZE} players and yours is full. Drop someone first.`);
        }

        const spent = squad.reduce((sum, p) => sum + p.price, 0);
        const budget = Number(state.pool.budget);
        if (spent + player.price > budget) {
            const over = Math.round((spent + player.price - budget) * 10) / 10;
            return refuse(`${player.name} costs ${fmt(player.price)} and that would put you ${fmt(over)} over `
                + `the ${fmt(budget)} budget. You have ${fmt(budget - spent)} left.`);
        }

        state.picks.push({ playerId, starter: false, captain: false });
        // A new player fills an empty slot of his position if there is one, which is what makes the slots
        // fill up as they are added rather than all at the end.
        fillEmptySlot(player);
        ensureCaptain();
        renderSetupTab();
    }

    /** Puts a newly added player into an empty eleven slot of his position, if the formation has one. */
    function fillEmptySlot(player) {
        const pickOf = pickMap();
        const starters = squadPlayers().filter(p => pickOf.get(p.playerId)?.starter);
        const wanted = count(formationSlots(), pos => pos);
        const have = count(starters, p => p.fantasyPosition);
        if ((have[player.fantasyPosition] || 0) < (wanted[player.fantasyPosition] || 0)) {
            const pick = state.picks.find(p => p.playerId === player.playerId);
            if (pick) {
                pick.starter = true;
            }
        }
    }

    function removePlayer(playerId) {
        state.picks = state.picks.filter(p => p.playerId !== playerId);
        state.message = '';
        state.error = '';
        state.menuFor = null;
        state.swapFor = null;
        ensureCaptain();
        renderSetupTab();
    }

    function setCaptain(playerId) {
        const pick = state.picks.find(p => p.playerId === playerId);
        if (pick && !pick.starter) {
            state.menuFor = null;
            return refuse('Only a starter can wear the armband. Bring him on first.');
        }
        state.picks.forEach(p => { p.captain = p.playerId === playerId; });
        state.message = '';
        state.error = '';
        state.menuFor = null;
        renderSetupTab();
    }

    function refuse(text) {
        state.error = text;
        state.message = '';
        state.menuFor = null;
        state.swapFor = null;
        renderSetupTab();
    }

    /** Exactly one captain whenever the eleven can hold one. */
    function ensureCaptain() {
        if (!state.picks.length) return;
        if (state.picks.some(p => p.captain && p.starter)) return;
        const first = state.picks
            .filter(p => p.starter)
            .map(p => state.pool.players.find(x => x.playerId === p.playerId))
            .filter(Boolean)
            .sort((a, b) => b.rating - a.rating)[0];
        state.picks.forEach(p => { p.captain = !!first && p.playerId === first.playerId; });
    }

    async function save() {
        state.busy = true;
        state.violations = [];
        state.message = '';
        state.error = '';
        state.menuFor = null;
        state.swapFor = null;
        renderSetupTab();

        const body = {
            picks: state.picks.map(p => ({
                playerId: p.playerId,
                starter: !!p.starter,
                captain: !!p.captain,
            })),
        };

        try {
            const res = await authFetch(`/api/fantasy/${encodeURIComponent(state.iso)}/squad`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(body),
            });
            const payload = await res.json();
            state.squad = payload;
            state.message = `Saved. ${fmt(payload.currentPrice)} spent, ${fmt(payload.remaining)} left.`;
        } catch (err) {
            // authFetch throws on every non-2xx, so a refusal arrives here and not as a response. The
            // server's reasons are on the error's details, and they are what a manager needs to read — an
            // uncaught rejection in the console helps nobody.
            const violations = err?.details?.violations;
            state.violations = Array.isArray(violations) && violations.length
                ? violations
                : [{ code: 'error', message: err?.message || 'The save was refused.' }];
        } finally {
            state.busy = false;
            renderSetupTab();
        }
    }

    // ------------------------------------------------------------------ pure helpers

    function squadPlayers() {
        return state.picks
            .map(p => state.pool.players.find(x => x.playerId === p.playerId))
            .filter(Boolean);
    }

    function pickMap() {
        return new Map(state.picks.map(p => [p.playerId, p]));
    }

    function count(list, keyOf) {
        const out = {};
        list.forEach(item => {
            const key = keyOf(item);
            out[key] = (out[key] || 0) + 1;
        });
        return out;
    }

    /**
     * A column header that sorts.
     *
     * <p>The owner, 2026-10-18: *"stavi listu igraca da mogu da se soritraju po kolonama (pogotovo rating i
     * price)"*. Clicking a header sorts by it and clicking it again reverses, which is the only behaviour
     * anybody expects from a table header and the only one worth writing down.
     */
    function sortableTh(key, label) {
        const active = state.sort.key === key;
        const mark = active ? (state.sort.dir === -1 ? ' ▾' : ' ▴') : '';
        return `<th class="fm-fantasy-sortable ${active ? 'is-sorted' : ''}">
            <button type="button" class="js-fantasy-sort" data-sort-key="${key}">${escapeHtml(label)}${mark}</button>
        </th>`;
    }

    /** The rows in the chosen order. Numbers compare as numbers, words as words, in the reader's locale. */
    function sortRows(rows) {
        const { key, dir } = state.sort;
        const out = [...rows];
        out.sort((a, b) => {
            const av = sortValue(a, key);
            const bv = sortValue(b, key);
            let cmp;
            if (typeof av === 'string' || typeof bv === 'string') {
                cmp = String(av).localeCompare(String(bv));
            } else {
                cmp = (av ?? 0) - (bv ?? 0);
            }
            if (cmp === 0) {
                // A stable tie-break, so two players on the same rating do not swap places between
                // renders — a list that reshuffles on equal values looks broken.
                cmp = String(a.name || '').localeCompare(String(b.name || ''));
            }
            return cmp * dir;
        });
        return out;
    }

    function sortValue(player, key) {
        switch (key) {
            case 'name': return player.name || '';
            case 'position': return POSITIONS.indexOf(player.fantasyPosition);
            case 'club': return player.clubName || '';
            case 'tier': return player.tier ?? 0;
            case 'price': return player.price ?? 0;
            case 'points': return player.points ?? 0;
            case 'rating':
            default: return player.rating ?? 0;
        }
    }

    function shortName(name) {
        const parts = String(name || '').split(' ').filter(Boolean);
        if (parts.length <= 1) return name || '';
        return parts[0][0] + '. ' + parts[parts.length - 1];
    }

    function fmt(value) {
        const n = Math.round(Number(value) * 10) / 10;
        return Number.isInteger(n) ? n.toFixed(1) : String(n);
    }

    // ------------------------------------------------------------------ rendering

    function renderEmpty(text) {
        render(`<section class="fm-panel"><div class="fm-panel-head">Fantasy</div>
            <p class="fm-subtle">${escapeHtml(text)}</p></section>`);
    }

    function render(html) {
        const main = document.getElementById('main-content');
        if (main) main.innerHTML = html;
    }

    function ensureStyles() {
        if (document.getElementById(STYLE_ID)) return;
        const style = document.createElement('style');
        style.id = STYLE_ID;
        style.textContent = `
            .fm-fantasy-tabs { display:flex; gap:8px; }
            .fm-fantasy-formations { display:flex; gap:8px; flex-wrap:wrap; }
            .fm-fantasy-budget { display:flex; justify-content:space-between; gap:12px; flex-wrap:wrap; }
            .fm-fantasy-over { color:#e05555; font-weight:700; }
            .fm-fantasy-bar { height:8px; border-radius:4px; background:rgba(255,255,255,0.12); margin-top:10px; overflow:hidden; }
            .fm-fantasy-bar span { display:block; height:100%; background:#f5c542; }
            .fm-fantasy-budget.is-over .fm-fantasy-bar span { background:#e05555; }
            .fm-fantasy-legend { display:flex; gap:14px; flex-wrap:wrap; margin-bottom:12px; font-size:12px; }
            .fm-fantasy-legend-item { display:inline-flex; align-items:center; gap:6px; opacity:0.75; }
            .fm-fantasy-legend-item.is-full { opacity:1; font-weight:600; }
            .fm-fantasy-legend-item i { width:12px; height:12px; border-radius:3px; display:inline-block; }
            .fm-fantasy-tier-dot { width:9px; height:9px; border-radius:2px; display:inline-block; margin-right:6px; }
            .fm-fantasy-pitch { border-radius:12px; padding:14px 10px;
                background:linear-gradient(180deg,#12305a,#0b1f3a); border:1px solid rgba(255,255,255,0.08); }
            .fm-fantasy-line { display:flex; justify-content:center; gap:8px; flex-wrap:wrap; margin-bottom:10px; }
            .fm-fantasy-bench-label { font-size:12px; opacity:0.7; margin:12px 0 6px; }
            .fm-fantasy-bench { display:flex; gap:8px; flex-wrap:wrap; }
            .fm-fantasy-card-wrap { position:relative; }
            .fm-fantasy-card { display:flex; flex-direction:column; align-items:center; gap:2px;
                min-width:76px; padding:8px 6px; border-radius:10px; cursor:pointer;
                background:rgba(255,255,255,0.08); border:1px solid rgba(255,255,255,0.12); color:inherit; }
            .fm-fantasy-card.is-empty { border-style:dashed; opacity:0.55; }
            .fm-fantasy-card-empty { font-size:18px; line-height:1; opacity:0.7; }
            .fm-fantasy-card.is-bench { opacity:0.72; }
            .fm-fantasy-card.is-captain { box-shadow:0 0 0 1px #f5c542 inset; }
            .fm-fantasy-card.is-swap-target { box-shadow:0 0 0 1px #2dd4bf inset; }
            .fm-fantasy-card-pos { font-size:10px; letter-spacing:0.06em; opacity:0.9; }
            .fm-fantasy-card-name { font-size:12px; font-weight:600; text-align:center; }
            .fm-fantasy-card-price { font-size:11px; opacity:0.8; }
            .fm-fantasy-menu { position:absolute; z-index:20; top:calc(100% + 4px); left:50%; transform:translateX(-50%);
                min-width:130px; border-radius:10px; overflow:hidden; display:flex; flex-direction:column;
                background:#0f2747; border:1px solid rgba(255,255,255,0.18); box-shadow:0 8px 22px rgba(0,0,0,0.45); }
            .fm-fantasy-menu-item { padding:8px 12px; text-align:left; background:transparent; border:0;
                color:inherit; cursor:pointer; font-size:13px; }
            .fm-fantasy-menu-item:hover { background:rgba(255,255,255,0.10); }
            .fm-fantasy-menu-cancel { opacity:0.7; border-top:1px solid rgba(255,255,255,0.10); }
            .fm-fantasy-swapping { display:flex; align-items:center; gap:10px; flex-wrap:wrap; }
            .fm-fantasy-filters { display:flex; gap:8px; flex-wrap:wrap; margin-bottom:12px; }
            .fm-fantasy-filters select, .fm-fantasy-filters input {
                padding:6px 8px; border-radius:8px; border:1px solid rgba(255,255,255,0.15);
                background:rgba(0,0,0,0.25); color:inherit; }
            .fm-fantasy-picked { opacity:0.55; }
            .fm-fantasy-actions { display:flex; gap:8px; margin-top:12px; flex-wrap:wrap; }
            .fm-fantasy-ok { border-left:3px solid #2dd4bf; }
            .fm-fantasy-bad { border-left:3px solid #e05555; }
            .fm-fantasy-msg { border-left:3px solid #f5c542; }
            .fm-fantasy-list { margin:8px 0 0 18px; }
        `;
        document.head.appendChild(style);
    }

    return { loadFantasy };
}
