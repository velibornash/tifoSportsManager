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
import { isAdminSession, getSessionRole, authFetch, apiErrorMessage } from '../../auth.js';

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
                ${backButtonHtml('Back', 'dashboard')}
                <div class="fm-club-hero-main">
                    <div>
                        <div class="fm-eyebrow">${isSelf ? 'Your account' : 'Manager'}</div>
                        <h2>${escapeHtml(profile.displayName || 'Manager')}</h2>
                    </div>
                </div>
                <div class="fm-medical-stat-grid team-summary-grid">
                    <div><strong>${escapeHtml(role)}</strong><span>Role</span></div>
                    <div><strong><button type="button" class="fm-link-btn js-profile-club" data-club-id="${escapeHtml(profile.clubId)}">${escapeHtml(profile.clubName || '—')}</button></strong><span>Club</span></div>
                    <div><strong><button type="button" class="fm-link-btn js-profile-league" data-league-id="${escapeHtml(profile.leagueId)}">${escapeHtml(profile.leagueName || '—')}</button></strong><span>League</span></div>
                    <div><strong><button type="button" class="fm-link-btn js-profile-country" data-country-code="${escapeHtml(profile.countryCode)}">${escapeHtml(profile.countryName || '—')}</button></strong><span>Country</span></div>
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
    const displayName = profile.displayName || 'Manager';
    const rows = [
        ['Name', isSelf ? nameCellHtml(displayName) : escapeHtml(displayName)],
        ['Role', escapeHtml(PRETTY_ROLE[profile.role] || profile.role || 'Manager')],
        ['Club', profile.clubId
            ? `<button type="button" class="fm-link-btn js-profile-club" data-club-id="${escapeHtml(profile.clubId)}">${escapeHtml(profile.clubName || 'Open club')}</button>`
            : 'AI-run or no club'],
        ['League', profile.leagueId
            ? `<button type="button" class="fm-link-btn js-profile-league" data-league-id="${escapeHtml(profile.leagueId)}">${escapeHtml(profile.leagueName || '—')}</button>`
            : escapeHtml(profile.leagueName || '—')],
        ['Country', profile.countryCode
            ? `<button type="button" class="fm-link-btn js-profile-country" data-country-code="${escapeHtml(profile.countryCode)}">${escapeHtml(profile.countryName || '—')}</button>`
            : escapeHtml(profile.countryCode || '—')],
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
            ${isSelf ? '<div class="user-name-status fm-subtle" id="display-name-status" role="status"></div>' : ''}
        </section>`;
}

/**
 * The Name row on your own profile, editable in place.
 *
 * <p>Owner, 2026-10-14: the editor was its own panel further down the page, so saving your name
 * meant scrolling away from the row that shows it and then scrolling back to check it. The edit now
 * happens in the row itself — pencil to open, then the field with X to cancel and a check to save,
 * all on the same line.
 */
function nameCellHtml(displayName) {
    return `
        <span class="user-name-view js-name-view">
            <span class="js-name-value">${escapeHtml(displayName)}</span>
            <button type="button" class="fm-icon-btn js-name-edit" title="Edit your name"
                    aria-label="Edit your name">&#9998;</button>
        </span>
        <span class="user-name-edit js-name-edit-cell" hidden>
            <input type="text" id="display-name-input" class="js-name-input" maxlength="40"
                   value="${escapeHtml(displayName)}" placeholder="Your name"
                   aria-label="Your name" />
            <button type="button" class="fm-icon-btn js-name-cancel" title="Cancel"
                    aria-label="Cancel">&#215;</button>
            <button type="button" class="fm-icon-btn fm-icon-btn--ok js-save-name" title="Save"
                    aria-label="Save">&#10003;</button>
        </span>`;
}

function renderActions(profile, isSelf, viewer) {
    // The name editor lives in the Name row now (see nameCellHtml), so your own profile has nothing
    // left to say down here. Only someone else's profile still has actions to offer.
    if (isSelf) {
        return '';
    }

    return `
        <section class="fm-panel">
            <div class="fm-panel-head">Contact</div>
            <div class="fm-club-actions">
                <button type="button" class="fm-action-btn js-message-user" data-user-id="${escapeHtml(profile.id)}">
                    Send a message
                </button>
                <!--
                    Owner request, 2026-10-13: every Back says just "Back" and goes to the previous screen;
                    the logo is what always returns to the dashboard.

                    This one was the worst of the set - it was hand-written and called loadPage('dashboard')
                    directly, so it ignored the navigation history entirely and there was no path back
                    from here at all. It now uses the shared button, which is the same button every other
                    page uses.
                -->
                ${backButtonHtml()}
            </div>
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

    const isAdmin = ['ADMIN', 'OWNER', 'DEV'].includes(String(role).toUpperCase());

    // **Login history (owner, 2026-10-17).** Admin only: where this account has been accessed from,
    // grouped by IP, with the date and time of every login. A manager's own logins are not his to
    // read - that is an operations view, and it stays on the moderation side of the profile.
    let loginHistoryHtml = '';
    if (isAdmin) {
        try {
            const res = await authFetch(`/auth/admin/users/${profile.id}/login-history`);
            const rows = res.ok ? await res.json() : [];
            // Group by IP, newest login first within each group.
            const byIp = new Map();
            for (const row of (Array.isArray(rows) ? rows : [])) {
                const ip = row.ipAddress || 'unknown';
                if (!byIp.has(ip)) byIp.set(ip, []);
                byIp.get(ip).push(row);
            }
            const groups = Array.from(byIp.entries()).map(([ip, entries]) => {
                entries.sort((a, b) => new Date(b.loginAt) - new Date(a.loginAt));
                const first = entries[0];
                const latest = first?.loginAt ? new Date(first.loginAt).toLocaleString() : '—';
                const detail = entries.map(e => `
                    <li class="fm-subtle" style="padding:2px 0;">
                        ${escapeHtml(new Date(e.loginAt).toLocaleString())}
                        ${e.userAgent ? ` · <span style="opacity:0.7;">${escapeHtml(e.userAgent.slice(0, 80))}</span>` : ''}
                    </li>`).join('');
                return `
                    <details class="fm-club-actions" style="margin-top:8px;">
                        <summary class="fm-club-actions-summary">
                            ${escapeHtml(ip)} — ${entries.length} login${entries.length === 1 ? '' : 's'}, last ${escapeHtml(latest)}
                        </summary>
                        <ul style="list-style:none; padding:8px 0 0; margin:0;">${detail}</ul>
                    </details>`;
            }).join('');
            loginHistoryHtml = `
                <section class="fm-panel" style="margin-top:16px;">
                    <div class="fm-panel-head">Login history</div>
                    <p class="fm-subtle">Where this account has been accessed from. Grouped by IP; expand
                        a row to see every login with its date, time and browser.</p>
                    ${groups || '<p class="fm-subtle">No logins recorded yet.</p>'}
                </section>`;
        } catch (err) {
            loginHistoryHtml = `<section class="fm-panel" style="margin-top:16px;">
                <div class="fm-panel-head">Login history</div>
                <p class="fm-subtle">Could not load login history (${escapeHtml(err?.message || '')}).</p>
            </section>`;
        }
    }

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
        </section>
        ${loginHistoryHtml}`;
}

/** Wires the buttons. Re-bound on every render because the markup is replaced wholesale. */
function bind(profile, isSelf, viewer) {
    const main = document.getElementById('main-content');
    if (!main) return;

    main.querySelectorAll('.js-profile-club').forEach(button => {
        button.addEventListener('click', () => {
            const clubId = button.dataset.clubId;
            if (!clubId) return;
            window.loadPage('clubProfile', { teamId: parseInt(clubId, 10) });
        });
    });

    main.querySelectorAll('.js-profile-league').forEach(button => {
        button.addEventListener('click', () => {
            const leagueId = button.dataset.leagueId;
            if (!leagueId) return;
            window.openLeagueById(leagueId);
        });
    });

    main.querySelectorAll('.js-profile-country').forEach(button => {
        button.addEventListener('click', () => {
            const countryCode = button.dataset.countryCode;
            if (!countryCode) return;
            window.loadPage('country', { countryCode: countryCode });
        });
    });

    if (isSelf) {
        const view = main.querySelector('.js-name-view');
        const editCell = main.querySelector('.js-name-edit-cell');
        const input = main.querySelector('.js-name-input');
        const value = main.querySelector('.js-name-value');
        const showEditing = (editing) => {
            if (view) view.hidden = editing;
            if (editCell) editCell.hidden = !editing;
            if (editing && input) {
                input.value = value ? value.textContent.trim() : '';
                input.focus();
                input.select();
            }
        };

        main.querySelector('.js-name-edit')?.addEventListener('click', () => showEditing(true));
        // Cancel puts the field back to what the server last accepted, so abandoning an edit can never
        // leave a typed-but-unsaved name sitting in the field.
        main.querySelector('.js-name-cancel')?.addEventListener('click', () => showEditing(false));
        main.querySelectorAll('.js-save-name').forEach(button => {
            button.addEventListener('click', () => saveDisplayName(button, profile));
        });
        return;
    }

    main.querySelectorAll('.js-message-user').forEach(button => {
        button.addEventListener('click', () => {
            // **The button opens the inbox with the recipient already chosen** (owner, 2026-10-20).
            // It used to open an alert that said direct messages were not here yet - and they are:
            // the message page has a recipient picker, a subject and a body.
            const userId = button.dataset.userId;
            if (!userId) return;
            if (typeof window.loadPage === 'function') {
                window.loadPage('messages', {
                    recipientUserId: userId,
                    recipientName: profile?.displayName || profile?.username || ''
                });
            }
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
        const body = await res.json();
        await loadPublicUserProfile(profile.id, { ...(profile), id: profile.id, displayName: body.displayName });
        // The top bar carries the name too, and it is the first place anyone looks. Repainting it here
        // stops the page saying "Saved ... Velja" while the corner still says the previous name.
        if (typeof window.loadUserTeamId === 'function') {
            await window.loadUserTeamId();
        }
        const after = document.getElementById('display-name-status');
        if (after) after.textContent = `Saved. You are now shown as "${body.displayName}".`;
    } catch (err) {
        if (status) status.textContent = `Could not save: ${apiErrorMessage(err, '')}`;
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
        await authFetch(`/admin/users/${encodeURIComponent(userId)}/forum-ban`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ days: parsed, reason: reason.trim() })
        });
        window.alert(`${who} cannot post in the forum for ${parsed} day(s).\n\n`
            + 'Reading the forum, messaging and playing the game are unaffected.');
    } catch (err) {
        window.alert(`Ban failed: ${apiErrorMessage(err, '')}`);
    } finally {
        button.disabled = false;
    }
}