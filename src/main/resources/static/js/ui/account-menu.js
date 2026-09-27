/**
 * The account corner: who is signed in, their profile, and the way out.
 *
 * <p>Signout used to exist only on the lobby page, which meant that once you were inside the SPA
 * there was no way to end a session — least of all on a phone, where the lobby is a page you have to
 * navigate back to. This puts the account in the top bar on both layouts, which is where every other
 * product puts it.
 *
 * <p>It is one delegated document listener rather than a listener per element, because the markup is
 * re-rendered by the router and a listener bound to a node that no longer exists silently stops
 * working.
 */

/** Signs the user out and returns to the login page. */
export function logout() {
    // Both stores, deliberately. sessionStorage is what this app reads, but a stale copy in
    // localStorage would come back to life on the next visit and look like the signout failed.
    try {
        sessionStorage.removeItem('token');
        localStorage.removeItem('token');
    } catch {
        // A browser with storage disabled cannot sign out server-side either; the redirect below is
        // still the right thing to do, so there is nothing to recover from here.
    }
    window.location.href = '/login.html';
}

/**
 * Paints the account corner: avatar initial, name, club, role, country.
 *
 * @param {object} user the /auth/me payload
 * @param {string} leagueName what to call the league
 */
export function paintAccountMenu(user, leagueName) {
    const username = (user && user.username) || '';
    const initial = (username.trim()[0] || '?').toUpperCase();
    const club = (user && (user.footballTeamName || user.teamName)) || 'No club';
    const role = (user && user.role) || '';
    const country = (user && user.countryName) || '';

    // "Manager", not "REGULAR": the same label the profile page uses, so the two cannot disagree.
    const lines = [club, leagueName, country, prettyRole(role)].filter(Boolean);

    setText('user-menu-avatar', initial);
    setText('user-menu-name', username || 'Signed in');
    setText('user-menu-panel-name', username || 'Signed in');
    setText('user-menu-panel-meta', lines.join(' · '));
    setText('user-menu-avatar-mobile', initial);
    setText('user-menu-name-mobile', username || 'Signed in');
    setText('user-menu-meta-mobile', lines.join(' · '));

    // The panel and the trigger carry the accessible name too, so the button is not read as bare
    // "question mark" by a screen reader.
    const trigger = document.getElementById('user-menu-trigger');
    if (trigger) {
        trigger.setAttribute('title', username ? `Signed in as ${username}` : 'Account');
    }
}

function setText(id, value) {
    const el = document.getElementById(id);
    if (el) el.textContent = value;
}

/** Opens or closes the account panel. */
export function toggleUserMenu(force) {
    const panel = document.getElementById('user-menu-panel');
    const trigger = document.getElementById('user-menu-trigger');
    if (!panel || !trigger) return;
    const willOpen = typeof force === 'boolean' ? force : panel.hasAttribute('hidden');
    if (willOpen) {
        panel.removeAttribute('hidden');
        trigger.setAttribute('aria-expanded', 'true');
    } else {
        panel.setAttribute('hidden', '');
        trigger.setAttribute('aria-expanded', 'false');
    }
}

export function closeUserMenu() {
    toggleUserMenu(false);
}

/**
 * Binds the account corner. Called once, from the app's load handler.
 *
 * <p>One listener on the document handles the trigger, the outside click and Escape, so none of it
 * depends on the elements surviving a re-render.
 */
export function initAccountMenu() {
    if (document.body.dataset.accountMenuBound === 'true') return;
    document.body.dataset.accountMenuBound = 'true';

    document.addEventListener('click', (event) => {
        const trigger = event.target.closest('#user-menu-trigger');
        if (trigger) {
            event.preventDefault();
            event.stopPropagation();
            toggleUserMenu();
            return;
        }
        // A click anywhere else closes it. stopPropagation on the trigger above is what stops that
        // same click from immediately closing the panel it just opened.
        if (!event.target.closest('#user-menu-panel')) {
            closeUserMenu();
        }
    });

    document.addEventListener('keydown', (event) => {
        if (event.key === 'Escape') {
            const panel = document.getElementById('user-menu-panel');
            if (panel && !panel.hasAttribute('hidden')) {
                closeUserMenu();
                event.preventDefault();
            }
        }
    });
}

/**
 * The user's own profile — who they are, what they run, and what they can see.
 *
 * <p>There was no profile page at all: the only thing the game knew about the person playing it was
 * an email on the login form. This is the classic user page, built from the /auth/me payload that is
 * already loaded, so it cannot disagree with the session.
 */
export function renderUserProfile(user, escapeHtml, formatBudget) {
    const main = document.getElementById('main-content');
    if (!main) return;

    const esc = typeof escapeHtml === 'function' ? escapeHtml : (v) => String(v ?? '');
    const club = user.footballTeamName || user.teamName || 'No club';
    const league = user.competitionName || 'No league';
    const country = user.countryName || 'Not set';

    const rows = [
        ['Username', user.username || '—'],
        ['Email', user.email || '—'],
        ['Role', prettyRole(user.role)],
        ['Club', club],
        ['League', league],
        ['Country', country],
    ];

    main.innerHTML = `
        <div class="fm-page fm-page--club">
            <section class="fm-panel fm-club-hero">
                <button class="back-to-dashboard" data-nav-back="dashboard">Back</button>
                <div class="fm-club-hero-main">
                    <div>
                        <div class="fm-eyebrow">Your account</div>
                        <h2>${esc(user.username || 'Profile')}</h2>
                    </div>
                </div>
                <div class="fm-medical-stat-grid team-summary-grid">
                    <div><strong>${esc(prettyRole(user.role))}</strong><span>Role</span></div>
                    <div><strong>${esc(user.competitionTier ?? '—')}</strong><span>League tier</span></div>
                    <div><strong>${esc(country)}</strong><span>Country</span></div>
                    <div><strong>${esc(user.competitionName || '—')}</strong><span>League</span></div>
                </div>
            </section>

            <section class="fm-panel">
                <div class="fm-panel-head">Details</div>
                <div class="club-profile-detail-list">
                    ${rows.map(([label, value]) => `
                        <div class="club-profile-detail-row">
                            <span>${esc(label)}</span><strong>${esc(value)}</strong>
                        </div>`).join('')}
                </div>
            </section>

            <section class="fm-panel">
                <div class="fm-panel-head">Session</div>
                <div class="fm-club-actions">
                    <button type="button" class="fm-action-btn" onclick="loadPage('profile')">Club profile</button>
                    <button type="button" class="fm-action-btn secondary" onclick="loadPage('country')">Country</button>
                    <button type="button" class="fm-action-btn secondary" onclick="logout()">Sign out</button>
                </div>
            </section>
        </div>`;
}

function prettyRole(role) {
    if (!role) return '—';
    const names = {
        OWNER: 'Owner', DEV: 'Developer', ADMIN: 'Administrator', MOD: 'Moderator',
        STAFF: 'Staff', PLUS: 'Plus', REGULAR: 'Manager',
    };
    return names[role] || role;
}
