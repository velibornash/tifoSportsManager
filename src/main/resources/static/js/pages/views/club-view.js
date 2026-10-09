// pages/views/club-view.js
import { htmlEscape, formatBudget, buildMilestoneBoardHtml, buildEmptyState } from './utils.js';
import { createFriendlyPanel } from './friendly-panel.js';
import { createFriendlyBoard } from './friendly-board.js';

export function createClubView(deps) {
    const { authFetch, getTeamId, buildClubActionsHtml, openLeagueById, loadPage } = deps;
    const friendlyPanel = createFriendlyPanel({ authFetch });
    const friendlyBoard = createFriendlyBoard({ authFetch });

    async function loadClubProfile() {
        const teamId = getTeamId();
        const [response, milestones, friendlyWeek, board, mine] = await Promise.all([
            authFetch(`/teams/${teamId}/profile`),
            (async () => {
                try {
                    const milestoneResponse = await authFetch(`/teams/${teamId}/milestones`);
                    return milestoneResponse.ok ? await milestoneResponse.json() : null;
                } catch {
                    return null;
                }
            })(),
            // Read beside the profile rather than after it, so the page is not rendered twice. A failed
            // read is a rendered empty panel, never a page that fails: the club profile is still worth
            // showing to a manager whose friendly week could not be loaded.
            friendlyPanel.loadFriendlyWeek(teamId).catch(() => ({ failed: true, status: 0 })),
            friendlyBoard.loadBoard().catch(() => ({ failed: true, status: 0 })),
            friendlyBoard.loadMine(teamId).catch(() => ({ failed: true, status: 0 }))
        ]);
        // Was `await response.json()` with no check. A 404 or a 403 then threw a parse error and the
        // page reported "Failed to load" — which is the exact trap AGENTS.md warns about, and it hid
        // the server's own message. Checked, and the status is reported rather than swallowed.
        if (!response.ok) {
            const body = await response.json().catch(() => ({}));
            document.getElementById("main-content").innerHTML = buildEmptyState(
                body.message || body.error || `This club profile could not be loaded (status ${response.status}).`
            );
            return;
        }
        const profile = await response.json();

        const mainContent = document.getElementById("main-content");

        const stadiumImage = "/images/dunjareal.png";

        mainContent.innerHTML = `
        <div class="fm-page fm-page--club">
            <section class="fm-panel fm-club-hero">
                <button class="back-to-dashboard" data-nav-back="dashboard">Back</button>
                <div class="fm-club-hero-main">
                    <div>
                        <div class="fm-eyebrow">Club overview</div>
                        <h2>${htmlEscape(profile.name || 'Club Profile')}</h2>
                        <p class="fm-subtle">Same club shell as First Team, with profile data, stadium access, budget, and reputation.</p>
                    </div>
                    ${buildClubActionsHtml('profile')}
                </div>
                <div class="fm-medical-stat-grid team-summary-grid">
                    <div><strong>${htmlEscape(profile.founded || 'N/A')}</strong><span>Founded</span></div>
                    <div><strong>${htmlEscape(profile.stadium || 'N/A')}</strong><span>Home stadium</span></div>
                    <div><strong>${htmlEscape(profile.reputation || 'N/A')}</strong><span>Reputation</span></div>
                    <div><strong>${htmlEscape(formatBudget(profile.budget))}</strong><span>Budget</span></div>
                </div>
            </section>
            <div class="fm-grid-top fm-grid-top--club-profile">
                <section class="fm-panel club-profile-brand-card">
                    <div class="club-profile-brand-mark">
                        <img src="${profile.logo || '/images/logoside.jpg'}"
                             class="club-logo"
                             alt="${htmlEscape(profile.name)}"
                             onerror="this.src='/images/logoside.jpg'">
                    </div>
                    <h3>${htmlEscape(profile.name || 'Club')}</h3>
                    <p class="fm-subtle">Serbian club profile with our existing app data.</p>
                    <button type="button" class="fm-action-btn secondary club-profile-stadium-btn" data-stadium-image="${htmlEscape(stadiumImage)}" data-stadium-name="${htmlEscape(profile.stadium || 'Stadium')}">Open Stadium View</button>
                </section>
                <section class="fm-panel club-profile-detail-card">
                    <div class="fm-panel-head">
                        <div>
                            <h3>Club details</h3>
                            <p class="fm-subtle">Profile data stays concise, wide, and visually aligned with the rest of the club area.</p>
                        </div>
                        <span class="fm-panel-action">Profile</span>
                    </div>
                    <div class="club-profile-detail-list">
                        <div class="club-profile-detail-row"><span>Founded</span><strong>${htmlEscape(profile.founded || 'N/A')}</strong></div>
                        <div class="club-profile-detail-row"><span>Stadium</span><strong>${htmlEscape(profile.stadium || 'N/A')}</strong></div>
                        <div class="club-profile-detail-row"><span>Budget</span><strong>${htmlEscape(formatBudget(profile.budget))}</strong></div>
                        <div class="club-profile-detail-row"><span>Reputation</span><strong>${htmlEscape(profile.reputation || 'N/A')}</strong></div>
                        <!-- The league, as a link. It was missing entirely: a club profile that
                             does not say which division it is in, and cannot take you there. -->
                        <div class="club-profile-detail-row"><span>League</span><strong>${
                            profile.leagueId
                                ? `<a class="fm-CTeam-link" href="#" data-open-league="${htmlEscape(String(profile.leagueId))}"
                                      data-league-name="${htmlEscape(profile.leagueName || '')}">${htmlEscape(profile.leagueName || 'League')}</a>`
                                : htmlEscape('No league')
                        }</strong></div>
                        <!-- Who runs the club, and a way to his profile. The owner asked for exactly
                             this path: click a club, see who manages it, click him, read his profile.
                             Reads the FK on TeamController.getProfile — never a name join, which is
                             what P0-18 and P0-20 are about.

                             A club with no manager says "AI-run" rather than showing nothing: this page
                             is the viewer's OWN club, so the absence is meaningful and should look
                             deliberate. -->
                        <div class="club-profile-detail-row"><span>Manager</span><strong>${
                            profile.managerUserId
                                ? `<button type="button" class="fm-link-btn" data-open-manager="${htmlEscape(String(profile.managerUserId))}">${htmlEscape(profile.managerName || 'Manager')}</button>`
                                : htmlEscape('AI-run')
                        }</strong></div>
                    </div>
                </section>
            </div>
            <section class="fm-panel fm-milestone-board-panel">
                <div class="fm-panel-head">
                    <div>
                        <h3>Club milestones</h3>
                        <p class="fm-subtle">Current season snapshot for your club, kept alongside the league-wide milestone boards.</p>
                    </div>
                    <span class="fm-panel-action">Team board</span>
                </div>
                ${buildMilestoneBoardHtml(milestones)}
            </section>
            ${friendlyPanel.buildHtml(friendlyWeek)}
            ${friendlyBoard.buildHtml(board, mine, teamId)}
        </div>`;

        // League link. One delegated listener because the profile is re-rendered on every visit and
        // a listener bound to a node that gets replaced stops working silently.
        mainContent.querySelectorAll('[data-open-league]').forEach(link => {
            link.addEventListener('click', (e) => {
                e.preventDefault();
                openLeagueById(link.dataset.openLeague, link.dataset.leagueName || '');
            });
        });

        // The manager's profile. openUserProfile is on window because pages.js owns the router and
        // this is a factory-scoped view; the same reason openLeagueById is passed in below.
        mainContent.querySelectorAll('[data-open-manager]').forEach(button => {
            button.addEventListener('click', () => {
                const userId = button.dataset.openManager;
                if (!userId) return;
                if (typeof window.openUserProfile === 'function') {
                    window.openUserProfile(userId);
                } else {
                    window.alert('The profile page is still loading. Try again in a moment.');
                }
            });
        });

        const stadiumButton = mainContent.querySelector('.club-profile-stadium-btn');
        if (stadiumButton) {
            // Goes to the stadium page, which is the real one. This used to open a hard-coded
            // picture of somebody else's ground in a new tab, so a manager could not see their own
            // capacity, prices, colours or pitch.
            stadiumButton.addEventListener('click', () => {
                loadPage('stadium');
            });
        }

        wireFriendlyPanel(mainContent, teamId);
        wireFriendlyBoard(mainContent, teamId);
    }

    /**
     * The friendly panel's controls.
     *
     * <p>Bound per render, not delegated once, because the panel is re-rendered with the page on every
     * visit and a listener attached to a node that gets replaced stops working silently.
     *
     * <p>Every action re-reads the week and re-renders. The reason is that these writes change what the
     * manager may do next — accepting a request fills a slot, which closes the invite button — and a
     * panel left showing the pre-write state invites a second click that answers 409.
     */
    function wireFriendlyPanel(root, teamId) {
        const panel = root.querySelector('[data-friendly-panel]');
        if (!panel) return;

        const season = panel.dataset.season;
        const week = panel.dataset.week;
        let chosenSlot = null;

        const say = (text, kind) => {
            const box = panel.querySelector('[data-friendly-message]');
            if (!box) return;
            box.hidden = false;
            box.className = `fm-friendly-message ${kind === 'error' ? 'is-error' : 'is-ok'}`;
            box.textContent = text;
        };

        const post = async (path, body) => {
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
        };

        const reload = async () => {
            const fresh = await friendlyPanel.loadFriendlyWeek(teamId, week, season);
            const holder = document.createElement('div');
            holder.innerHTML = friendlyPanel.buildHtml(fresh);
            const replacement = holder.firstElementChild;
            if (replacement) {
                panel.replaceWith(replacement);
                wireFriendlyPanel(document.getElementById('main-content'), teamId);
            }
        };

        panel.querySelectorAll('[data-friendly-invite]').forEach(button => {
            button.addEventListener('click', () => {
                chosenSlot = button.dataset.friendlyInvite;
                const form = panel.querySelector('[data-friendly-form]');
                const search = panel.querySelector('[data-friendly-search]');
                if (form) form.hidden = false;
                if (search) search.focus();
                say(`Pick a club for the ${chosenSlot === '1' ? 'first' : 'second'} slot.`, 'ok');
            });
        });

        const closeForm = panel.querySelector('[data-friendly-cancel-form]');
        if (closeForm) {
            closeForm.addEventListener('click', () => {
                const form = panel.querySelector('[data-friendly-form]');
                if (form) form.hidden = true;
                chosenSlot = null;
            });
        }

        const search = panel.querySelector('[data-friendly-search]');
        if (search) {
            let timer = null;
            search.addEventListener('input', () => {
                // Debounced: this is a per-keystroke query and the answer is a list, not a warning.
                clearTimeout(timer);
                timer = setTimeout(async () => {
                    const box = panel.querySelector('[data-friendly-results]');
                    if (!box) return;
                    const opponents = await friendlyPanel.loadOpponents(teamId, search.value.trim());
                    if (!opponents.length) {
                        box.innerHTML = '<div class="fm-empty">No club in your country matches that.</div>';
                        return;
                    }
                    box.innerHTML = opponents.map(o => `
                        <button type="button" class="fm-friendly-opponent"
                                data-friendly-opponent="${o.id}" data-friendly-opponent-name="${htmlEscape(o.name)}">
                            ${htmlEscape(o.name)}
                        </button>`).join('');
                    box.querySelectorAll('[data-friendly-opponent]').forEach(button => {
                        button.addEventListener('click', async () => {
                            if (chosenSlot == null) {
                                say('Choose an open slot first.', 'error');
                                return;
                            }
                            const result = await post(
                                `/api/season/friendlies/${teamId}/request?opponentId=${button.dataset.friendlyOpponent}`
                                + `&week=${week}&slot=${chosenSlot}`);
                            if (!result.ok) {
                                say(result.text, 'error');
                                return;
                            }
                            await reload();
                        });
                    });
                }, 250);
            });
        }

        const respond = async (requestId, accept, reason) => {
            const query = reason ? `?accept=${accept}&reason=${encodeURIComponent(reason)}`
                : `?accept=${accept}`;
            const result = await post(
                `/api/season/friendlies/${teamId}/requests/${requestId}${query}`);
            if (!result.ok) {
                say(result.text, 'error');
                return;
            }
            await reload();
        };

        panel.querySelectorAll('[data-friendly-accept]').forEach(button => {
            button.addEventListener('click', () => respond(button.dataset.friendlyAccept, true));
        });
        panel.querySelectorAll('[data-friendly-decline]').forEach(button => {
            button.addEventListener('click', () => respond(button.dataset.friendlyDecline, false, 'Declined'));
        });
        panel.querySelectorAll('[data-friendly-cancel]').forEach(button => {
            button.addEventListener('click', async () => {
                const result = await post(
                    `/api/season/friendlies/${teamId}/requests/${button.dataset.friendlyCancel}/cancel`);
                if (!result.ok) {
                    say(result.text, 'error');
                    return;
                }
                await reload();
            });
        });
    }

    /**
     * The free-slot board's controls.
     *
     * <p>Same two actions the owner specified, nothing more: post a slot, and take one that is up. The
     * page says which week a slot is for because a week has more than one friendly slot, and an ad for a
     * week that has already passed should say so rather than let a manager take it.
     */
    function wireFriendlyBoard(root, teamId) {
        const board = root.querySelector('[data-friendly-board]');
        if (!board) return;

        const season = board.dataset.season;
        const week = board.dataset.week;

        const say = (text, kind) => {
            const box = board.querySelector('[data-offer-message]');
            if (!box) return;
            box.hidden = false;
            box.className = `fm-friendly-message ${kind === 'error' ? 'is-error' : 'is-ok'}`;
            box.textContent = text;
        };

        const post = async (path) => {
            const response = await authFetch(path, { method: 'POST' });
            if (!response.ok) {
                const payload = await response.json().catch(() => ({}));
                return { ok: false, text: payload.detail || payload.error
                    || `That could not be done (status ${response.status}).` };
            }
            return { ok: true };
        };

        const reload = async () => {
            const freshBoard = await friendlyBoard.loadBoard(season, week);
            const freshMine = await friendlyBoard.loadMine(teamId, season, week);
            const holder = document.createElement('div');
            holder.innerHTML = friendlyBoard.buildHtml(freshBoard, freshMine, teamId);
            const replacement = holder.firstElementChild;
            if (replacement) {
                board.replaceWith(replacement);
                wireFriendlyBoard(document.getElementById('main-content'), teamId);
            }
        };

        const showForm = board.querySelector('[data-offer-post]');
        const closeForm = board.querySelector('[data-offer-cancel]');
        const form = board.querySelector('[data-offer-form]');
        if (showForm) {
            showForm.addEventListener('click', () => {
                if (form) form.hidden = !form.hidden;
            });
        }
        if (closeForm) {
            closeForm.addEventListener('click', () => {
                if (form) form.hidden = true;
            });
        }

        const confirm = board.querySelector('[data-offer-post-confirm]');
        if (confirm) {
            confirm.addEventListener('click', async () => {
                const select = board.querySelector('[data-offer-slot]');
                const slot = select ? select.value : '1';
                const result = await post(`/api/season/friendly-offers?teamId=${teamId}&week=${week}&slot=${slot}`);
                if (!result.ok) {
                    say(result.text, 'error');
                    return;
                }
                await reload();
            });
        }

        board.querySelectorAll('[data-offer-claim]').forEach(button => {
            button.addEventListener('click', async () => {
                const result = await post(
                    `/api/season/friendly-offers/${button.dataset.offerClaim}/claim?teamId=${teamId}`);
                if (!result.ok) {
                    say(result.text, 'error');
                    return;
                }
                await reload();
            });
        });
    }

    function openStadiumImage(imageUrl) {
        window.open(imageUrl, '_blank');
    }

    function showStadiumModal(imageUrl, stadiumName) {
        const modal = document.createElement('div');
        modal.style.position = 'fixed';
        modal.style.inset = '0';
        modal.style.background = 'rgba(0,0,0,0.85)';
        modal.style.display = 'flex';
        modal.style.alignItems = 'center';
        modal.style.justifyContent = 'center';
        modal.style.zIndex = '9999';
        modal.innerHTML = `
            <div style="position: relative; max-width: 90vw; max-height: 90vh;">
                <button onclick="this.parentElement.parentElement.remove()"
                        style="position: absolute; top: -40px; right: 0; background: #f44336; color: white; border: none; border-radius: 50%; width: 36px; height: 36px; font-size: 1.4em; cursor: pointer;">
                    &times;
                </button>
                <img src="${imageUrl}" alt="${stadiumName}" style="max-width: 100%; max-height: 85vh; border-radius: 12px; box-shadow: 0 10px 40px rgba(0,0,0,0.7);">
                <p style="color: white; text-align: center; margin-top: 12px; font-size: 1.2em;">
                    ${stadiumName}
                </p>
            </div>
        `;
        modal.onclick = (e) => {
            if (e.target === modal) modal.remove();
        };
        document.body.appendChild(modal);
    }

    return { loadClubProfile, showStadiumModal, openStadiumImage };
}
