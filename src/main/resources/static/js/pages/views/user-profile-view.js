// user-profile-view.js
//
// Somebody else's profile: the page you land on by clicking a club and then the name of the person
// who runs it (owner, 2026-10-05).
//
// WHY A SEPARATE FILE RATHER THAN A FLAG ON renderUserProfile
//
// The own-account page in ui/account-menu.js shows an email address, and it must: it is the
// manager's own account and he knows his address. This page is read by other people, so it shows
// nothing that identifies him beyond what he chose to publish. One renderer with a flag is how a
// field ends up gated by a boolean somebody forgets, which is how the old community chat came to
// expose a pending applicant's username while correctly gating his email (P0-17).
//
// The backend enforces this too: /users/{id}/profile has no email field to gate. This file simply
// does not ask for one.

import { escapeHtml } from '../../ui/escape.js';
import { buildEmptyState } from './utils.js';
import { backButtonHtml } from '../../ui/components.js';
import { isAdminSession, getSessionRole, authFetch } from '../../auth.js';

const PRETTY_ROLE = {
    OWNER: 'Owner', DEV: 'Developer', ADMIN: 'Administrator', MOD: 'Moderator',
    STAFF: 'Staff', PLUS: 'Plus', REGULAR: 'Manager',
};

/**
 * Renders one manager's public profile.
 *
 * @param userId  whose profile to show
 * @param viewer  the signed-in manager, for the "is this you?" branch and the moderator tools
 */
export async function loadPublicUserProfile(userId, viewer) {
    const main = document.getElementById('main-content');
    if (!main) return;

    const id = Number(userId);
    if (!Number.isFinite(id) || id <= 0) {
        main.innerHTML = buildEmptyState('That is not a manager.');
        return;
    }

    let profile;
    try {
        const res = await authFetch(`/users/${id}/profile`);
        if (!res.ok) {
            throw new Error(res.status === 404
                ? 'No manager with that number is registered.'
                : `The profile could not be loaded (status ${res.status}).`);
        }
        profile = await res.json();
    } catch (err) {
        main.innerHTML = buildEmptyState('This profile could not be loaded. ' + (err?.message || ''));
        return;
    }

    const isSelf = viewer && Number(viewer.id) === Number(profile.id);
    const role = PRETTY_ROLE[profile.role] || profile.role || 'Manager';

    main.innerHTML = `
        <div class="fm-page fm-page--club">
            <section class="fm-panel fm-club-hero">
                ${backButtonHtml('Back', isSelf ? 'userProfile' : 'dashboard')}
                <div class="fm-club-hero-main">
                    <div>
                        <div class="fm-eyebrow">${isSelf ? 'Your account' : 'Manager'}</div>
                        <h2>${escapeHtml(profile.displayName || 'Manager')}</h2>
                    </div>
                </div>
                <div class="fm-medical-stat-grid team-summary-grid">
                    <div><strong>${escapeHtml(role)}</strong><span>Role</span></div>
                    <div><strong>${escapeHtml(profile.clubName || '—')}</strong><span>Club</span></div>
                    <div><strong>${escapeHtml(profile.leagueName || '—')}</strong><span>League</span></div>
                    <div><strong>${profile.plusSubscriber ? 'PLUS' : '—'}</strong><span>Subscription</span></div>
                </div>
            </section>

            ${renderDetails(profile, isSelf)}
            ${renderActions(profile, isSelf, viewer)}
            ${await renderModeration(profile, viewer)}
        </div>`;

    bind(profile, isSelf, viewer);
}

/**
 * The details list.
 *
 * <p>No email, no username, no last-seen-for-a-stranger. Deliberately a shorter list than the
 * own-account page: everything here is read by other people, and a field nobody asked for is a field
 * somebody has to decide about later.
 */
function renderDetails(profile, isSelf) {
    const rows = [
        ['Name', escapeHtml(profile.displayName || 'Manager')],
        ['Role', escapeHtml(PRETTY_ROLE[profile.role] || profile.role || 'Manager')],
        ['Club', profile.clubId
            ? `<button type="button" class="fm-link-btn js-open-club" data-club-id="${escapeHtml(profile.clubId)}">${escapeHtml(profile.clubName || 'Open club')}</button>`
            : 'AI-run or no club'],
        ['League', escapeHtml(profile.leagueName || '—')],
        ['Country', escapeHtml(profile.countryCode || '—')],
    ];

    // A manager who never chose a name is shown an email address, because that is his username and
    // there is nothing else to call him. Saying so is the honest presentation, and it is the same
    // reason the own-account page exists: the field was not writable until P2-20.
    if (!profile.hasChosenName) {
        rows.push(['Name', `<span class="fm-subtle">Not set — showing his login address. `
            + `Only he can change this.</span>`]);
    }

    return `
        <section class="fm-panel">
            <div class="fm-panel-head">Details</div>
            <div class="club-profile-detail-list">
                ${rows.map(([label, value]) => `
                    <div class="club-profile-detail-row">
                        <span>${escapeHtml(label)}</span><strong>${value}</strong>
                    </div>`).join('')}
            </div>
        </section>`;
}

function renderActions(profile, isSelf, viewer) {
    if (isSelf) {
        return `
            <section class="fm-panel">
                <div class="fm-panel-head">Your name</div>
                <p class="fm-subtle">Your name is what other managers see beside your posts and messages.
                    It is not your login address.</p>
                <div class="community-compose-form">
                    <div class="community-compose-textarea">
                        <input type="text" id="display-name-input" maxlength="40"
                               value="${escapeHtml(profile.displayName || '')}"
                               placeholder="Your name" />
                    </div>
                    <div class="community-compose-actions">
                        <button type="button" class="fm-action-btn js-save-name">Save name</button>
                    </div>
                    <div class="community-compose-toolbar fm-subtle" id="display-name-status"></div>
                </div>
            </section>`;
    }

    return `
        <section class="fm-panel">
            <div class="fm-panel-head">Contact</div>
            <div class="fm-club-actions">
                <button type="button" class="fm-action-btn js-message-user" data-user-id="${escapeHtml(profile.id)}">
                    Send a message
                </button>
                <button type="button" class="fm-action-btn secondary" onclick="loadPage('dashboard')">
                    Back to dashboard
                </button>
            </div>
            <p class="fm-subtle" style="margin-top:12px;">
                Nothing here is private: other managers can see the same page.
            </p>
        </section>`;
}

/**
 * The moderator panel.
 *
 * <p>Rendered only for a moderator, and gated on the server as well — the buttons are a courtesy, not
 * the gate. {@code /admin/users/{id}/forum-ban} is refused to a REGULAR manager by the
 * {@code /admin/**} matcher.
 *
 * <p>The wording is precise on purpose. It says a <i>forum</i> ban, because that is what it is:
 * the manager keeps his club, his matches and his private messages. A moderator who expects
 * "ban" to suspend an account would be wrong, and so would the manager receiving it.
 */
async function renderModeration(profile, viewer) {
    const role = (viewer && viewer.role) || getSessionRole();
    const moderator = ['MOD', 'ADMIN', 'OWNER', 'DEV'].includes(String(role).toUpperCase());
    if (!moderator || Number(viewer.id) === Number(profile.id)) {
        return '';
    }

    // The public profile carries no ban state — deliberately, it is not the moderator's business to
    // be on a public page. The Admin tab is where an active ban is visible and lifted.
    const note = role === 'MOD'
        ? 'As a moderator you can ban this manager from posting in the forum.'
        : 'You can change this manager\'s role from the Admin tab.';

    return `
        <section class="fm-panel">
            <div class="fm-panel-head">Moderation</div>
            <p class="fm-subtle">${escapeHtml(note)}</p>
            <div class="fm-club-actions">
                <button type="button" class="fm-action-btn js-ban-user"
                        data-user-id="${escapeHtml(profile.id)}"
                        data-user-name="${escapeHtml(profile.displayName || 'this manager')}">
                    Ban from forum
                </button>
                <button type="button" class="fm-action-btn secondary" onclick="loadPage('admin')">
                    Open Admin tab
                </button>
            </div>
            <p class="fm-subtle" style="margin-top:12px;">
                A forum ban stops him posting and replying. He can still read the forum, message
                anyone, and play the game. Days and reason are asked for next.
            </p>
        </section>`;
}

/** Wires the buttons. Re-bound on every render because the markup is replaced wholesale. */
function bind(profile, isSelf, viewer) {
    const main = document.getElementById('main-content');
    if (!main) return;

    main.querySelectorAll('.js-open-club').forEach(button => {
        button.addEventListener('click', () => {
            const clubId = button.dataset.clubId;
            if (!clubId) return;
            // The club profile page resolves the viewer's own club, not this one, so opening a
            // rival's club here would show the wrong club's details. Saying so is better than
            // navigating somewhere that looks right and is not.
            if (viewer && viewer.footballTeamId && Number(viewer.footballTeamId) === Number(clubId)) {
                window.loadPage('profile');
                return;
            }
            window.alert('The club profile page shows your own club. Open a league table and '
                + 'click the club there to see how it is doing.');
        });
    });

    if (isSelf) {
        main.querySelectorAll('.js-save-name').forEach(button => {
            button.addEventListener('click', () => saveDisplayName(button, profile));
        });
        return;
    }

    main.querySelectorAll('.js-message-user').forEach(button => {
        button.addEventListener('click', () => {
            window.alert('Direct messages arrive with the Community rebuild. Until then, this is the '
                + 'page you reached the manager from.');
        });
    });

    main.querySelectorAll('.js-ban-user').forEach(button => {
        button.addEventListener('click', () => banFromForum(button));
    });
}

/**
 * Saves the caller's own display name.
 *
 * <p>PATCHes {@code /users/me/display-name} and then reloads the profile, rather than patching the
 * name into the DOM. Reloading means the page cannot show a name the server did not accept, which is
 * the failure mode where a form says "saved" and the next page load reverts it.
 */
async function saveDisplayName(button, profile) {
    const input = document.getElementById('display-name-input');
    const status = document.getElementById('display-name-status');
    if (!input) return;

    const wanted = input.value.trim();
    if (!wanted) {
        if (status) status.textContent = 'A name cannot be empty. Leave it as your login instead.';
        return;
    }

    button.disabled = true;
    try {
        const res = await authFetch('/users/me/display-name', {
            method: 'PATCH',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ displayName: wanted })
        });
        const body = await res.json().catch(() => ({}));
        if (!res.ok) {
            if (status) status.textContent = body.error || body.message || `Not saved (status ${res.status}).`;
            return;
        }
        await loadPublicUserProfile(profile.id, { ...(profile), id: profile.id, displayName: body.displayName });
        const after = document.getElementById('display-name-status');
        if (after) after.textContent = `Saved. You are now shown as "${body.displayName}".`;
    } catch (err) {
        if (status) status.textContent = `Could not save: ${err?.message || ''}`;
    } finally {
        button.disabled = false;
    }
}

/**
 * Applies a forum write ban from the profile, asking for days and a reason first.
 *
 * <p>Both are asked for rather than defaulted. The service refuses a blank reason, and a moderator
 * who types one without reading is the only way that refusal ever fires — so the prompt states that
 * the manager is told what it is.
 */
async function banFromForum(button) {
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

    const reason = window.prompt(`Why? ${who} is told this.`, '');
    if (reason === null) return;
    if (!reason.trim()) {
        window.alert('A reason is required — the manager is shown it.');
        return;
    }

    button.disabled = true;
    try {
        const res = await authFetch(`/admin/users/${encodeURIComponent(userId)}/forum-ban`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ days: parsed, reason: reason.trim() })
        });
        const body = await res.json().catch(() => ({}));
        if (!res.ok) {
            window.alert(`Ban failed: ${body.error || body.message || res.status}`);
            return;
        }
        window.alert(`${who} cannot post in the forum for ${parsed} day(s).\n\n`
            + 'Reading the forum, messaging and playing the game are unaffected.');
    } catch (err) {
        window.alert(`Error: ${err?.message || err}`);
    } finally {
        button.disabled = false;
    }
}