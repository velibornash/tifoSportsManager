// admin-view.js
//
// Admin control room. Reached from the ADMIN tab in the main menu, which is only
// rendered for ADMIN / OWNER / DEV users (see isAdminSession in auth.js).
//
// This is the new home of the DB Tools that used to live inside the Community page.
// The "Season flow controls" block that sat next to those tools was an exact duplicate
// of the dashboard's "Match Week Controls" (same three actions, same handlers) and has
// been removed - the dashboard remains the single entry point for those.
//
// The route is guarded as well as the menu, so typing loadPage('admin') in the console
// as a non-admin still gets a refusal rather than the tools.

import { authFetch, isAdminSession, getSessionRole } from '../../auth.js';
import { buildEmptyState } from './utils.js';
import { escapeHtml } from '../../ui/escape.js';
import { backButtonHtml } from '../../ui/components.js';

export function createAdminView({ getTeamId, getTeamName, getUsername }) {
    function guard() {
        if (isAdminSession()) return null;
        return buildEmptyState('Admins only - you do not have permission to view this page.');
    }

    async function saveDefaultTactics(button) {
        const teamId = getTeamId?.();
        if (!teamId) {
            window.alert('No team assigned.');
            return;
        }
        button.disabled = true;
        try {
            const res = await authFetch(`/teams/${teamId}/tactics-editor`);
            if (!res.ok) throw new Error('Failed to load tactics');
            const current = await res.json();
            const saveRes = await authFetch(`/teams/${teamId}/tactics-editor`, {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    formation: current.formation,
                    style: current.style,
                    starterIds: current.starterIds,
                    benchIds: current.benchIds,
                    movementRules: current.movementRules,
                    setPieceAssignments: current.setPieceAssignments
                })
            });
            window.alert(saveRes.ok
                ? 'Default tactics saved. They will persist after a DB reset.'
                : 'Failed to save default tactics.');
        } catch (err) {
            window.alert(`Error saving tactics: ${err.message}`);
        } finally {
            button.disabled = false;
        }
    }

    /**
     * Asks before doing anything destructive, and says what actually happened afterwards.
     *
     * <p>Every action here can rewrite part of the world, and a silent button that either works or
     * quietly does nothing is the worst of both. So each one confirms first, and then reports the
     * result the server sent - including "nothing to do", which is a normal outcome and not a
     * failure.
     */
    async function runRepair(button, { confirmText, path, successNote }) {
        if (!window.confirm(confirmText)) return;
        button.disabled = true;
        const original = button.textContent;
        button.textContent = 'Working...';
        try {
            const res = await authFetch(path, { method: 'POST' });
            const body = await res.json().catch(() => ({}));
            if (!res.ok) {
                window.alert(`Failed: ${body.error || res.status}`);
                return;
            }
            const detail = Object.entries(body)
                .filter(([k]) => k !== 'action')
                .map(([k, v]) => `${k}: ${v}`)
                .join('\n');
            window.alert(`${body.action || successNote}\n\n${detail}`);
        } catch (err) {
            window.alert(`Error: ${err.message}`);
        } finally {
            button.textContent = original;
            button.disabled = false;
            await showWorldIntegrity();
        }
    }

    /**
     * Reads the world's actual state and shows it on the page.
     *
     * <p>Shown up front so an admin can see whether the world is whole before touching it, rather
     * than finding out afterwards from a failure.
     */
    async function showWorldIntegrity() {
        const box = document.getElementById('fm-integrity');
        if (!box) return;
        try {
            const res = await authFetch('/admin/world-integrity');
            if (!res.ok) throw new Error('unavailable');
            const w = await res.json();
            box.innerHTML = `
                <div class="fm-medical-stat-grid team-summary-grid">
                    <div class="fm-stat-card"><span>Countries</span><strong>${w.countries} / ${w.expectedCountries}</strong></div>
                    <div class="fm-stat-card"><span>National sides</span><strong>${w.nationalSides}</strong></div>
                    <div class="fm-stat-card"><span>Sides with a squad</span><strong>${w.seniorSidesWithSquad} / ${w.seniorSides}</strong></div>
                    <div class="fm-stat-card"><span>Legacy rows</span><strong>${w.legacyRows.length}</strong></div>
                </div>
                ${w.healthy
                    ? '<p class="fm-subtle">World is whole.</p>'
                    : `<p class="fm-subtle">World needs repair${w.legacyRows.length ? ` (legacy rows: ${w.legacyRows.join(', ')})` : ''}.</p>`}`;
        } catch (err) {
            box.innerHTML = '<p class="fm-subtle">Could not read the world state.</p>';
        }
    }

    async function handleTool(button) {
        const action = button?.dataset?.adminAction;
        if (action === 'export-tactics') {
            await saveDefaultTactics(button);
            return;
        }
        if (action === 'repair-world') {
            await runRepair(button, {
                confirmText: 'Check the world and rebuild whatever is missing?\n\nThis tops up missing countries and national squads. Existing data is kept.',
                path: '/admin/world-integrity/repair',
                successNote: 'World repaired'
            });
            return;
        }
        if (action === 'reseed-national-teams') {
            await runRepair(button, {
                confirmText: 'Re-seed the national teams?\n\nAny side with no squad gets one. Sides that already have a squad are left alone.',
                path: '/admin/world-reseed?what=national-teams',
                successNote: 'National teams re-seeded'
            });
            return;
        }
        if (action === 'redraw-cup') {
            await runRepair(button, {
                confirmText: 'Re-draw the cup?\n\nRounds that already have ties are left alone, so this only fills in rounds that never got drawn.',
                path: '/admin/world-reseed?what=cup',
                successNote: 'Cup re-drawn'
            });
            return;
        }
        const handler = action === 'reset' ? window.resetDatabase : window.initializeDatabase;
        if (typeof handler !== 'function') {
            window.alert('This admin action is not available right now.');
            return;
        }
        const warning = action === 'reset'
            ? 'Reset the database?\n\nThis clears all local football data. You will need to Initialize afterwards to rebuild the world.'
            : 'Initialize the database?\n\nThis rebuilds the whole world: 48 countries, 96 national squads, the club pyramid, cups and internationals. It takes about a minute.';
        if (!window.confirm(warning)) return;
        button.disabled = true;
        try {
            await handler();
        } finally {
            button.disabled = false;
            await showWorldIntegrity();
        }
    }

    function toolCard({ title, body, action, label, variant = 'secondary' }) {
        return `
            <article class="community-tool-card">
                <h4>${title}</h4>
                <p class="fm-subtle">${body}</p>
                <button type="button" class="fm-action-btn ${variant}" data-admin-action="${action}">${label}</button>
            </article>`;
    }

    /**
     * The activation list.
     *
     * <p>Loading the countries and activating one are separated deliberately. This panel is the one
     * place in the admin where a click writes 7,750 rows, so the list is cheap to read and the button
     * is the expensive part — with the size stated on the button rather than discovered afterwards.
     */
    async function showCountryActivation() {
        const host = document.getElementById('fm-activation');
        if (!host) return;
        try {
            const res = await authFetch('/admin/countries');
            if (!res.ok) throw new Error(`status ${res.status}`);
            const rows = await res.json();
            if (!Array.isArray(rows) || rows.length === 0) {
                host.innerHTML = '<p class="fm-subtle">No countries found.</p>';
                return;
            }
            const active = rows.filter(row => row.active);
            const dormant = rows.filter(row => !row.active);
            host.innerHTML = `
                <p class="fm-subtle">${active.length} active, ${dormant.length} represented.
                    A represented country plays its national sides only.</p>
                <div class="fm-activation-list">
                    ${dormant.map(row => activationRow(row)).join('')}
                </div>`;
            host.querySelectorAll('.js-activate-country').forEach(button => {
                button.addEventListener('click', () => activateCountry(button));
            });
        } catch (err) {
            host.innerHTML = `<p style="color:#f44336;">Could not load the countries: ${escapeHtml(err.message)}</p>`;
        }
    }

    function activationRow(row) {
        const hasFootball = row.divisions > 0;
        return `
            <div class="fm-activation-row${row.active ? ' is-active' : ''}">
                <span class="fm-activation-name">${escapeHtml(row.name)}</span>
                <span class="fm-subtle">${escapeHtml(row.isoCode)}</span>
                <span class="fm-subtle">${hasFootball ? `${row.divisions} divisions` : 'no divisions'}</span>
                <button type="button" class="fm-action-btn ${row.active ? 'secondary' : ''} js-activate-country"
                        data-iso="${escapeHtml(row.isoCode)}"
                        title="${row.active
                            ? 'Already active. This tops up anything missing and changes nothing else.'
                            : 'Builds 31 divisions, 310 clubs and about 7,750 players.'}">
                    ${row.active ? 'Top up' : 'Activate'}
                </button>
            </div>`;
    }

    async function activateCountry(button) {
        const iso = button?.dataset?.iso;
        if (!iso) return;
        const warning = `Activate ${iso.toUpperCase()}?\n\nThis builds a five-tier pyramid: 31 divisions, 310 clubs and about 7,750 players. It takes a while.`;
        if (!window.confirm(warning)) return;
        button.disabled = true;
        const original = button.textContent;
        button.textContent = 'Building...';
        try {
            const res = await authFetch(`/admin/countries/${encodeURIComponent(iso)}/activate`, { method: 'POST' });
            const body = await res.json().catch(() => ({}));
            if (!res.ok) {
                window.alert(`Activation failed: ${body.message || body.error || res.status}`);
                return;
            }
            const what = body.alreadyBuilt
                ? `${body.name} already had a pyramid - topped up, nothing duplicated.`
                : `${body.name}: ${body.divisions} divisions, ${body.clubs} clubs.`;
            window.alert(what);
        } catch (err) {
            window.alert(`Error: ${err.message}`);
        } finally {
            button.disabled = false;
            button.textContent = original;
            await showCountryActivation();
        }
    }

    async function loadAdmin() {
        const mainContent = document.getElementById('main-content');
        const refusal = guard();
        if (refusal) {
            mainContent.innerHTML = refusal;
            return;
        }

        mainContent.innerHTML = `
            <div class="page-container">
                ${backButtonHtml('Back to dashboard', 'dashboard')}
                <section class="fm-panel">
                    <div class="fm-panel-head">
                        <div>
                            <h2>Admin</h2>
                            <p class="fm-subtle">Operational tools. Visible only to ADMIN, OWNER and DEV accounts.</p>
                        </div>
                        <span class="fm-panel-action">Admin only</span>
                    </div>
                    <div class="fm-medical-stat-grid team-summary-grid">
                        <div class="fm-stat-card"><span>Role</span><strong>${getSessionRole() || 'ADMIN'}</strong></div>
                        <div class="fm-stat-card"><span>Signed in as</span><strong>${getUsername?.() || 'Manager'}</strong></div>
                        <div class="fm-stat-card"><span>Current club</span><strong>${getTeamName?.() || 'Unassigned'}</strong></div>
                        <div class="fm-stat-card"><span>Tool groups</span><strong>3</strong></div>
                    </div>
                </section>

                <section class="fm-panel">
                    <div class="fm-panel-head">
                        <div>
                            <h3>Database controls</h3>
                            <p class="fm-subtle">Destructive. Use only when you intend to clear or rebuild the local data.</p>
                        </div>
                        <span class="fm-panel-action">Destructive</span>
                    </div>
                    <div class="community-tool-grid">
                        ${toolCard({
                            title: 'Reset DB',
                            body: 'Clears local data and rebuilds the usable football baseline so login and dashboard boot work again.',
                            action: 'reset',
                            label: 'Reset DB'
                        })}
                        ${toolCard({
                            title: 'Initialize DB',
                            body: 'Runs the full initializer again and rebuilds the football structure.',
                            action: 'initialize',
                            label: 'Initialize DB',
                            variant: ''
                        })}
                        ${toolCard({
                            title: 'Export Default Tactics',
                            body: 'Saves the current tactical editor setup as the default for your team. Loaded automatically after a DB reset.',
                            action: 'export-tactics',
                            label: 'Save Default Tactics'
                        })}
                    </div>
                </section>

                <section class="fm-panel">
                    <div class="fm-panel-head">
                        <div>
                            <h3>World integrity</h3>
                            <p class="fm-subtle">What the world actually holds right now. Repair tops up
                                what is missing and keeps what is there.</p>
                        </div>
                        <span class="fm-panel-action">Check and repair</span>
                    </div>
                    <div id="fm-integrity"><p class="fm-subtle">Reading...</p></div>
                    <div class="community-tool-grid">
                        ${toolCard({
                            title: 'Repair world',
                            body: 'Checks the world and rebuilds anything missing: countries, national squads, legacy rows.',
                            action: 'repair-world',
                            label: 'Repair world'
                        })}
                        ${toolCard({
                            title: 'Re-seed national teams',
                            body: 'Gives a 25-player squad to any national side that has none. Existing squads are untouched.',
                            action: 'reseed-national-teams',
                            label: 'Re-seed national teams',
                            variant: ''
                        })}
                        ${toolCard({
                            title: 'Re-draw the cup',
                            body: 'Draws any cup round that never got drawn. Rounds that already have ties are left alone.',
                            action: 'redraw-cup',
                            label: 'Re-draw the cup',
                            variant: ''
                        })}
                    </div>
                </section>

                <section class="fm-panel">
                    <div class="fm-panel-head">
                        <div>
                            <h3>Activate a country</h3>
                            <p class="fm-subtle">A country is represented by its national sides. Activating
                                one gives it a real five-tier club pyramid: 31 divisions, 310 clubs and
                                about 7,750 players, built to that tier's strength standard.</p>
                        </div>
                        <span class="fm-panel-action">Owner only</span>
                    </div>
                    <div id="fm-activation"><p class="fm-subtle">Reading...</p></div>
                </section>

                <section class="fm-panel">
                    <div class="fm-panel-head">
                        <div>
                            <h3>Coming next</h3>
                            <p class="fm-subtle">Registration approvals and further admin tooling land here.</p>
                        </div>
                        <span class="fm-panel-action">Planned</span>
                    </div>
                    <div class="fm-empty" style="text-align:left;">
                        Club registration requests (approve / reject) and the remaining admin actions
                        will be surfaced on this page. They currently still live in the Community chat.
                    </div>
                </section>
            </div>`;

        void showCountryActivation();

        mainContent.querySelectorAll('[data-admin-action]').forEach((button) => {
            button.addEventListener('click', () => handleTool(button));
        });

        await showWorldIntegrity();
    }

    return { loadAdmin };
}
