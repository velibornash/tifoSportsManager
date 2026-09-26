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

    async function handleTool(button) {
        const action = button?.dataset?.adminAction;
        if (action === 'export-tactics') {
            await saveDefaultTactics(button);
            return;
        }
        const handler = action === 'reset' ? window.resetDatabase : window.initializeDatabase;
        if (typeof handler !== 'function') {
            window.alert('This admin action is not available right now.');
            return;
        }
        button.disabled = true;
        try {
            await handler();
        } finally {
            button.disabled = false;
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
                        <div class="fm-stat-card"><span>Tool groups</span><strong>1</strong></div>
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

        mainContent.querySelectorAll('[data-admin-action]').forEach((button) => {
            button.addEventListener('click', () => handleTool(button));
        });
    }

    return { loadAdmin };
}
