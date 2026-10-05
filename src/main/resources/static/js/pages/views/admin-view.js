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
    /**
     * The roles offered in the account list, in ascending order of privilege.
     *
     * <p>Written out rather than fetched, because it is the same list the server validates against and a
     * dropdown offering a value the backend refuses is a dropdown that lies. STAFF and PLUS are here
     * because they are real {@code UserRole} values; neither grants anything yet, which is a product
     * decision rather than an oversight in this list.
     */
    const ROLE_OPTIONS = ['REGULAR', 'PLUS', 'STAFF', 'MOD', 'ADMIN', 'DEV', 'OWNER'];

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
        if (action === 'seed-other-nations') {
            // A job, not a repair: this one takes minutes, so it goes through the polling job path the
            // reset and initialise buttons use rather than runRepair(), which expects a reply now.
            const confirmSeed = window.confirm(
                'Seed the other nations?\n\n' +
                'This builds every country that is not activated: its divisions, clubs, ratings and a ' +
                'standing table. No players are generated and no matches are played.\n\n' +
                'It takes a few minutes and it is the longest job in this panel. Countries that already ' +
                'exist are left alone, so it is safe to run twice.');
            if (!confirmSeed) return;
            if (typeof window.seedOtherNations === 'function') {
                await window.seedOtherNations();
            } else {
                window.alert('This admin action is not available right now.');
            }
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
        if (action === 'repair-club-links') {
            // Not runRepair(): that ends by re-reading world integrity, which is the wrong thing to
            // refresh after touching accounts. The count comes back in the response instead.
            if (!window.confirm(
                'Repair club links?\n\n' +
                'Fills in the club foreign key for any account created before it existed. ' +
                'Nothing is duplicated and nothing else changes.')) return;
            button.disabled = true;
            try {
                const res = await authFetch('/admin/users/repair-club-links', { method: 'POST' });
                const body = await res.json().catch(() => ({}));
                if (!res.ok) {
                    window.alert(`Failed: ${body.error || body.message || res.status}`);
                    return;
                }
                window.alert(body.repaired === 0
                    ? 'Nothing to repair — every account already has a club link.'
                    : `Repaired ${body.repaired} account(s).`);
            } catch (err) {
                window.alert(`Error: ${err.message}`);
            } finally {
                button.disabled = false;
                await showUserManagement();
            }
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

    /**
     * The account list: roles and forum write bans.
     *
     * <p>This panel is the only way a MOD, ADMIN or DEV account can be appointed. Before it, those three
     * roles were enum constants with no writer anywhere in the codebase — the forum's own moderator
     * office could not be handed to a person through the product at all.
     *
     * <p>The ban is deliberately described as a <i>forum</i> ban on the button and in the prompt, because
     * that is exactly what it does. A moderator reading "ban" and expecting the manager's account to
     * disappear would be wrong, and the difference matters to the person being banned.
     */
    async function showUserManagement() {
        const host = document.getElementById('fm-users');
        if (!host) return;
        try {
            const res = await authFetch('/admin/users');
            if (!res.ok) throw new Error(`status ${res.status}`);
            const rows = await res.json();
            if (!Array.isArray(rows) || rows.length === 0) {
                host.innerHTML = '<p class="fm-subtle">No accounts.</p>';
                return;
            }
            host.innerHTML = `
                <p class="fm-subtle">${rows.length} accounts. A forum ban stops a manager posting and
                    replying. He can still read the forum, message anyone, and play.</p>
                <div class="fm-activation-list">
                    ${rows.map(userRow).join('')}
                </div>`;
            host.querySelectorAll('.js-ban-user').forEach(button => {
                button.addEventListener('click', () => banUser(button));
            });
            host.querySelectorAll('.js-lift-ban').forEach(button => {
                button.addEventListener('click', () => liftBan(button));
            });
            host.querySelectorAll('.js-change-role').forEach(select => {
                select.addEventListener('change', () => changeRole(select));
            });
        } catch (err) {
            host.innerHTML = `<p style="color:#f44336;">Could not load accounts: ${escapeHtml(err.message)}</p>`;
        }
    }

    function userRow(user) {
        const who = user.displayName || user.username || `Account ${user.id}`;
        const banned = user.forumBanned === true;
        return `
            <div class="fm-activation-row${banned ? ' is-active' : ''}">
                <span class="fm-activation-name">${escapeHtml(who)}</span>
                <span class="fm-subtle">${escapeHtml(user.clubName || 'No club')}</span>
                <select class="js-change-role" data-user-id="${escapeHtml(user.id)}"
                        aria-label="Role for ${escapeHtml(who)}">
                    ${ROLE_OPTIONS.map(role => `
                        <option value="${role}"${user.role === role ? ' selected' : ''}>${escapeHtml(role)}</option>
                    `).join('')}
                </select>
                ${banned
                    ? `<button type="button" class="fm-action-btn secondary js-lift-ban"
                               data-user-id="${escapeHtml(user.id)}">Lift ban</button>`
                    : `<button type="button" class="fm-action-btn js-ban-user"
                               data-user-id="${escapeHtml(user.id)}"
                               data-user-name="${escapeHtml(who)}">Ban from forum</button>`}
            </div>
            ${banned ? `
                <div class="fm-subtle">
                    Banned for ${escapeHtml(user.forumBanDaysLeft)} more day(s) by
                    ${escapeHtml(user.forumBanBy || 'a moderator')}: ${escapeHtml(user.forumBanReason || 'no reason recorded')}
                </div>` : ''}`;
    }

    async function banUser(button) {
        const userId = button?.dataset?.userId;
        const who = button?.dataset?.userName || 'this manager';
        if (!userId) return;
        const days = window.prompt(`Ban ${who} from the forum for how many days?`, '7');
        if (days === null) return;
        const parsed = Number.parseInt(days, 10);
        if (!Number.isFinite(parsed) || parsed < 1) {
            window.alert('Enter a whole number of days.');
            return;
        }
        const reason = window.prompt(`Why? ${who} is told this.`);
        if (reason === null) return;
        button.disabled = true;
        try {
            const res = await authFetch(`/admin/users/${encodeURIComponent(userId)}/forum-ban`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ days: parsed, reason })
            });
            const body = await res.json().catch(() => ({}));
            if (!res.ok) {
                window.alert(`Ban failed: ${body.error || body.message || res.status}`);
                return;
            }
            window.alert(`${who} cannot post in the forum for ${parsed} day(s). Reading and messaging are unaffected.`);
        } catch (err) {
            window.alert(`Error: ${err.message}`);
        } finally {
            button.disabled = false;
            await showUserManagement();
        }
    }

    async function liftBan(button) {
        const userId = button?.dataset?.userId;
        if (!userId) return;
        button.disabled = true;
        try {
            const res = await authFetch(`/admin/users/${encodeURIComponent(userId)}/forum-ban/lift`, { method: 'POST' });
            if (!res.ok) {
                const body = await res.json().catch(() => ({}));
                window.alert(`Could not lift the ban: ${body.error || body.message || res.status}`);
                return;
            }
            window.alert('Ban lifted. He can post in the forum again.');
        } catch (err) {
            window.alert(`Error: ${err.message}`);
        } finally {
            button.disabled = false;
            await showUserManagement();
        }
    }

    async function changeRole(select) {
        const userId = select?.dataset?.userId;
        const role = select?.value;
        if (!userId || !role) return;
        const previous = select.dataset.previousRole;
        try {
            const res = await authFetch(`/admin/users/${encodeURIComponent(userId)}/role`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ role })
            });
            const body = await res.json().catch(() => ({}));
            if (!res.ok) {
                window.alert(`Role change failed: ${body.error || body.message || res.status}`);
                if (previous) select.value = previous;
                return;
            }
            select.dataset.previousRole = role;
        } catch (err) {
            window.alert(`Error: ${err.message}`);
            if (previous) select.value = previous;
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
                            body: 'Clears the football data. Your user accounts and tactic editor setups are kept. It does not rebuild anything — press Initialize DB or Seed other nations afterwards.',
                            action: 'reset',
                            label: 'Reset DB'
                        })}
                        ${toolCard({
                            title: 'Initialize DB',
                            body: 'Builds the Serbian structure: all 31 divisions, the fixture list and the players. Takes a minute or two. The other 46 countries are a separate button.',
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
                            title: 'Seed other nations',
                            body: 'Builds every country that is not activated: divisions, clubs, ratings and a standing table. No players, no matches. Safe to run twice.',
                            action: 'seed-other-nations',
                            label: 'Seed other nations',
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
                            <h3>Accounts</h3>
                            <p class="fm-subtle">Appoint moderators and control forum write bans.
                                A ban stops a manager posting; it does not touch his club or his account.</p>
                        </div>
                        <span class="fm-panel-action">Roles and bans</span>
                    </div>
                    <div id="fm-users"><p class="fm-subtle">Reading...</p></div>
                    <div class="community-tool-grid">
                        ${toolCard({
                            title: 'Repair club links',
                            body: 'Fills in the club foreign key for accounts created before it existed. Idempotent, and only needed once.',
                            action: 'repair-club-links',
                            label: 'Repair club links',
                            variant: ''
                        })}
                    </div>
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
        void showUserManagement();

        mainContent.querySelectorAll('[data-admin-action]').forEach((button) => {
            button.addEventListener('click', () => handleTool(button));
        });

        await showWorldIntegrity();
    }

    return { loadAdmin };
}
