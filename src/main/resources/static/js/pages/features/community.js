export function createCommunityFeature(deps) {
    const {
        authFetch,
        getTeamId,
        getTeamName,
        getUsername,
        getUserRole,
        escapeHtml,
        formatDateTimeLabel,
        buildCommunityActionsHtml,
        loadLeagueTeam,
    } = deps;

    function buildCommunityPageShell({ currentPage, eyebrow, title, subtitle, stats = [], bodyHtml = '' }) {
        const statHtml = stats.map(stat => `
            <div><strong>${escapeHtml(String(stat.value ?? '-'))}</strong><span>${escapeHtml(stat.label || '')}</span></div>
        `).join('');
        return `
            <div class="fm-page fm-page--club">
                <section class="fm-panel fm-club-hero">
                    <button class="back-to-dashboard" data-nav-back="dashboard">Back</button>
                    <div class="fm-club-hero-main">
                        <div>
                            <div class="fm-eyebrow">${escapeHtml(eyebrow || 'Community')}</div>
                            <h2>${escapeHtml(title || 'Community')}</h2>
                            <p class="fm-subtle">${escapeHtml(subtitle || '')}</p>
                        </div>
                        ${buildCommunityActionsHtml(currentPage, { showAdminTools: isAdminViewer() })}
                    </div>
                    <div class="fm-medical-stat-grid team-summary-grid">${statHtml}</div>
                </section>
                ${bodyHtml}
            </div>`;
    }

    function isAdminViewer() {
        const role = String(getUserRole?.() || '').toUpperCase();
        return role === 'OWNER' || role === 'ADMIN' || role === 'DEV';
    }

    function formatCommunityDate(value, fallback = '—') {
        return value ? formatDateTimeLabel(value) : fallback;
    }

    function typeBadgeClass(type) {
        const value = String(type || '').toUpperCase();
        if (value === 'ADMIN') return 'community-type-admin';
        if (value === 'SERVICE') return 'community-type-service';
        return 'community-type-user';
    }

    function buildRegistrationGrid(message) {
        const rows = [];
        if (message?.requestedUsername) rows.push({ label: 'Applicant', value: message.requestedUsername });
        if (message?.requestedEmail) rows.push({ label: 'Email', value: message.requestedEmail });
        if (message?.requestedTeamName) rows.push({ label: 'Reserved club', value: message.requestedTeamName });
        if (message?.registrationStatus) rows.push({ label: 'Status', value: message.registrationStatus });
        if (message?.reviewerUsername) rows.push({ label: 'Reviewed by', value: message.reviewerUsername });
        if (message?.reviewNote) rows.push({ label: 'Review note', value: message.reviewNote });
        if (!rows.length) return '';
        return `
            <div class="community-registration-grid">
                ${rows.map(row => `
                    <div>
                        <span>${escapeHtml(row.label)}</span>
                        <strong>${escapeHtml(String(row.value ?? '—'))}</strong>
                    </div>`).join('')}
            </div>`;
    }

    function buildClubButtons(message) {
        const buttons = [];
        if (message?.teamId && message?.teamName) {
            buttons.push(`<button type="button" class="fm-action-btn secondary" data-community-team-id="${message.teamId}" data-community-team-name="${escapeHtml(message.teamName)}">Open club</button>`);
        }
        if (message?.requestedTeamId && message?.requestedTeamName && message.requestedTeamId !== message.teamId) {
            buttons.push(`<button type="button" class="fm-action-btn secondary" data-community-team-id="${message.requestedTeamId}" data-community-team-name="${escapeHtml(message.requestedTeamName)}">Open reserved club</button>`);
        }
        return buttons.join('');
    }

    function buildAdminButtons(message) {
        if (!message?.registrationRequestId) return '';
        const buttons = [];
        if (message.canApprove) {
            buttons.push(`<button type="button" class="fm-action-btn" data-registration-action="approve" data-registration-id="${message.registrationRequestId}">Approve club</button>`);
        }
        if (message.canReject) {
            buttons.push(`<button type="button" class="fm-action-btn secondary" data-registration-action="reject" data-registration-id="${message.registrationRequestId}">Reject request</button>`);
        }
        return buttons.join('');
    }

    function buildMessageCard(message) {
        const metaBits = [];
        if (message?.privateMessage) {
            const peer = message?.privatePeerUsername || message?.recipientUsername || 'manager';
            metaBits.push(message?.sentByViewer ? `Private to ${peer}` : `Private with ${peer}`);
        }
        if (message?.teamName) metaBits.push(`Current club: ${message.teamName}`);
        if (message?.registrationStatus) metaBits.push(`Registration ${message.registrationStatus}`);
        const footerBits = [];
        if (message?.requestedUsername && !message?.registrationStatus) footerBits.push(`Applicant ${message.requestedUsername}`);

        return `
            <article class="community-card">
                <div class="community-card-head">
                    <div>
                        <div class="community-card-meta">
                            <span class="fm-badge community-type-badge ${typeBadgeClass(message?.type)}">${escapeHtml(String(message?.type || 'USER'))}</span>
                            ${message?.privateMessage ? '<span class="fm-badge community-visibility-badge is-private">Private</span>' : ''}
                            <span>${escapeHtml(formatCommunityDate(message?.date, 'Just now'))}</span>
                        </div>
                        <div class="community-card-title">${escapeHtml(message?.author || 'Community')}</div>
                        ${metaBits.length ? `<div class="community-card-meta">${metaBits.map(bit => `<span>${escapeHtml(bit)}</span>`).join('')}</div>` : ''}
                    </div>
                </div>
                <div class="community-card-body">${escapeHtml(message?.message || '')}</div>
                ${buildRegistrationGrid(message)}
                <div class="community-card-footer">
                    <div class="community-card-meta">${footerBits.map(bit => `<span>${escapeHtml(bit)}</span>`).join('')}</div>
                    <div class="community-admin-actions">
                        ${buildClubButtons(message)}
                        ${buildAdminButtons(message)}
                    </div>
                </div>
            </article>`;
    }

    function buildRecipientOptions(recipients) {
        const safeRecipients = Array.isArray(recipients) ? recipients : [];
        return `
            <option value="">Everyone (shared chat)</option>
            ${safeRecipients.map(recipient => {
                const teamBit = recipient?.teamName ? ` · ${recipient.teamName}` : '';
                return `<option value="${Number(recipient.userId)}">${escapeHtml(`${recipient.username || 'Manager'}${teamBit}`)}</option>`;
            }).join('')}`;
    }

    async function handleAdminTool(button) {
        const action = button?.dataset?.communityAdminAction;
        if (action === 'export-tactics') {
            await exportDefaultTactics(button);
            return;
        }
        const handler = action === 'reset' ? window.resetDatabase : window.initializeDatabase;
        if (typeof handler !== 'function') {
            alert('This admin action is not available right now.');
            return;
        }

        button.disabled = true;
        try {
            await handler();
        } finally {
            button.disabled = false;
        }
    }

    async function exportDefaultTactics(button) {
        const teamId = getTeamId();
        if (!teamId) { alert('No team assigned.'); return; }
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
            if (saveRes.ok) {
                alert('Default tactics saved successfully! They will persist after DB reset.');
            } else {
                alert('Failed to save default tactics.');
            }
        } catch (e) {
            alert('Error exporting tactics: ' + e.message);
        } finally {
            button.disabled = false;
        }
    }

    async function handleSeasonFlowTool(button) {
        const action = button?.dataset?.communitySeasonAction;
        const handler = {
            'play-match': window.startRealisticDemoTest,
            'simulate-round': window.simulateCurrentRoundTest,
            'advance-week': window.advanceWeekTest,
        }[action];
        if (typeof handler !== 'function') {
            alert('This season flow action is not available right now.');
            return;
        }

        await handler();
    }

    async function handleSendMessage(form) {
        const textarea = form.querySelector('textarea');
        const recipientSelect = form.querySelector('select[name="recipientUserId"]');
        const submitButton = form.querySelector('button[type="submit"]');
        const message = String(textarea?.value || '').trim();
        const recipientUserId = recipientSelect?.value ? Number(recipientSelect.value) : null;
        if (!message) return;

        submitButton.disabled = true;
        submitButton.textContent = 'Sending...';
        try {
            await authFetch('/community/chat', {
                method: 'POST',
                body: JSON.stringify({ message, recipientUserId })
            });
            textarea.value = '';
            if (recipientSelect) recipientSelect.value = '';
            await loadChat();
        } catch (error) {
            alert(error.message || 'Failed to send message.');
        } finally {
            submitButton.disabled = false;
            submitButton.textContent = 'Send message';
        }
    }

    async function handleRegistrationAction(button) {
        const requestId = Number(button.dataset.registrationId);
        const action = button.dataset.registrationAction;
        if (!requestId || !action) return;

        const note = window.prompt(
            action === 'approve'
                ? 'Optional approval note:'
                : 'Optional rejection note:',
            ''
        );
        if (note === null) return;

        button.disabled = true;
        try {
            const response = await authFetch(`/admin/registration-requests/${requestId}/${action}`, {
                method: 'POST',
                body: JSON.stringify({ note: note.trim() })
            });
            const payload = await response.json().catch(() => ({}));
            if (payload?.message) alert(payload.message);
            await loadChat();
        } catch (error) {
            alert(error.message || 'Failed to review registration request.');
            button.disabled = false;
        }
    }

    function bindCommunityInteractions(mainContent) {
        const form = mainContent.querySelector('#community-compose-form');
        if (form) {
            form.addEventListener('submit', async event => {
                event.preventDefault();
                await handleSendMessage(form);
            });
        }

        mainContent.querySelectorAll('[data-community-team-id]').forEach(button => {
            button.addEventListener('click', () => {
                const teamId = Number(button.dataset.communityTeamId);
                if (!teamId || typeof loadLeagueTeam !== 'function') return;
                loadLeagueTeam(teamId, button.dataset.communityTeamName || 'Club');
            });
        });

        mainContent.querySelectorAll('[data-registration-action]').forEach(button => {
            button.addEventListener('click', async () => handleRegistrationAction(button));
        });

        mainContent.querySelectorAll('[data-community-admin-action]').forEach(button => {
            button.addEventListener('click', async () => handleAdminTool(button));
        });

        mainContent.querySelectorAll('[data-community-season-action]').forEach(button => {
            button.addEventListener('click', async () => handleSeasonFlowTool(button));
        });
    }

    async function loadChat() {
        const mainContent = document.getElementById('main-content');
        try {
            const [response, recipientsResponse] = await Promise.all([
                authFetch('/community/chat'),
                authFetch('/community/recipients').catch(() => null)
            ]);
            const messages = response.ok ? await response.json() : [];
            const recipients = recipientsResponse?.ok ? await recipientsResponse.json() : [];
            const orderedMessages = [...messages].sort((a, b) => new Date(b.date || 0) - new Date(a.date || 0));
            const pendingApprovals = orderedMessages.filter(item => item?.registrationStatus === 'PENDING').length;
            const latestMessage = orderedMessages[0] || null;
            const adminViewer = isAdminViewer();
            const viewerRole = getUserRole?.() || 'USER';
            const viewerTeam = getTeamName?.() || 'Unassigned';

            mainContent.innerHTML = buildCommunityPageShell({
                currentPage: 'chat',
                eyebrow: adminViewer ? 'Community control room' : 'Community chat',
                title: 'Community Chat',
                subtitle: adminViewer
                    ? 'Managers can chat normally, while admin, owner, and dev accounts can approve or reject pending club ownership requests directly from service messages.'
                    : 'Managers can send messages here and follow service updates about approved or rejected club ownership requests.',
                stats: [
                    { value: orderedMessages.length, label: 'Messages' },
                    { value: viewerTeam, label: 'Your club' },
                    { value: viewerRole, label: 'Role' },
                    { value: adminViewer ? pendingApprovals : formatCommunityDate(latestMessage?.date), label: adminViewer ? 'Pending approvals' : 'Latest update' }
                ],
                bodyHtml: `
                    <section class="fm-panel">
                        <div class="fm-panel-head">
                            <div>
                                <h3>Send message</h3>
                                <p class="fm-subtle">Signed in as ${escapeHtml(getUsername?.() || 'Manager')}. Choose everyone for the shared feed or pick a manager for a private message only the two of you can see.</p>
                            </div>
                            <span class="fm-panel-action">Live feed</span>
                        </div>
                        <form id="community-compose-form" class="community-compose-form">
                            <textarea class="community-compose-textarea" name="message" maxlength="1200" placeholder="Write a message to the community..." required></textarea>
                            <div class="community-compose-toolbar">
                                <label class="fm-season-select-wrap community-recipient-wrap">
                                    <span>Send to</span>
                                    <select class="fm-season-select community-recipient-select" name="recipientUserId">
                                        ${buildRecipientOptions(recipients)}
                                    </select>
                                </label>
                            </div>
                            <div class="community-compose-actions">
                                <span class="fm-subtle">${adminViewer ? 'Service messages below can still contain registration approvals, while private messages stay visible only to sender and recipient.' : 'Admin service decisions appear here as system messages, while private messages stay visible only to sender and recipient.'}</span>
                                <button type="submit" class="fm-action-btn">Send message</button>
                            </div>
                        </form>
                    </section>
                    <section class="fm-panel">
                        <div class="fm-panel-head">
                            <div>
                                <h3>Recent feed</h3>
                                <p class="fm-subtle">Newest messages first. Pending registration requests stay admin-only, while private messages are shown only to the sender and recipient.</p>
                            </div>
                            <span class="fm-panel-action">${orderedMessages.length} items</span>
                        </div>
                        ${orderedMessages.length
                            ? `<div class="community-feed">${orderedMessages.map(buildMessageCard).join('')}</div>`
                            : `<div class="fm-empty">No community messages yet. Start the conversation.</div>`}
                    </section>`
            });

            bindCommunityInteractions(mainContent);
        } catch (error) {
            mainContent.innerHTML = buildCommunityPageShell({
                currentPage: 'chat',
                eyebrow: 'Community chat',
                title: 'Community Chat',
                subtitle: 'The chat feed could not be loaded right now.',
                stats: [
                    { value: getTeamName?.() || 'Unassigned', label: 'Your club' },
                    { value: getUserRole?.() || 'USER', label: 'Role' },
                    { value: 'Error', label: 'Status' },
                    { value: getUsername?.() || 'Manager', label: 'Signed in as' }
                ],
                bodyHtml: `<section class="fm-panel"><div class="fm-empty">${escapeHtml(error.message || 'Failed to load the community feed.')}</div></section>`
            });
        }
    }

    async function loadForum() {
        const mainContent = document.getElementById('main-content');
        if (!isAdminViewer()) {
            return loadChat();
        }

        mainContent.innerHTML = buildCommunityPageShell({
            currentPage: 'forum',
            eyebrow: 'Community control room',
            title: 'Admin DB Tools',
            subtitle: 'Reset and initialization actions are available here again until further notice, while the chat remains the shared community/forum feed.',
            stats: [
                { value: getUserRole?.() || 'ADMIN', label: 'Role' },
                { value: getUsername?.() || 'Manager', label: 'Signed in as' },
                { value: '5', label: 'Admin actions' },
                { value: getTeamName?.() || 'Unassigned', label: 'Current club' }
            ],
            bodyHtml: `
                <section class="fm-panel">
                    <div class="fm-panel-head">
                        <div>
                            <h3>Database controls</h3>
                            <p class="fm-subtle">Use these only when you really want to clear or rebuild the local data. Both actions reuse the existing admin endpoints.</p>
                        </div>
                        <span class="fm-panel-action">Admin only</span>
                    </div>
                    <div class="community-tool-grid">
                        <article class="community-tool-card">
                            <h4>Reset DB</h4>
                            <p class="fm-subtle">Clears local data and rebuilds the usable football baseline so login and dashboard boot work again.</p>
                            <button type="button" class="fm-action-btn secondary" data-community-admin-action="reset">Reset DB</button>
                        </article>
                        <article class="community-tool-card">
                            <h4>Initialize DB</h4>
                            <p class="fm-subtle">Runs the full initializer again and rebuilds the football structure.</p>
                            <button type="button" class="fm-action-btn" data-community-admin-action="initialize">Initialize DB</button>
                        </article>
                        <article class="community-tool-card">
                            <h4>Export Default Tactics</h4>
                            <p class="fm-subtle">Saves the current tactical editor setup as the default for your team. Loaded automatically after DB reset.</p>
                            <button type="button" class="fm-action-btn" data-community-admin-action="export-tactics">Save Default Tactics</button>
                        </article>
                    </div>
                </section>
                <section class="fm-panel">
                    <div class="fm-panel-head">
                        <div>
                            <h3>Season flow controls</h3>
                            <p class="fm-subtle">Same manual round controls as the dashboard, now available here next to the DB tools for admin testing.</p>
                        </div>
                        <span class="fm-panel-action">Manual flow</span>
                    </div>
                    <div id="dashboard-season-flow-status" class="fm-season-flow-status">Choose the next manual season action for the current week.</div>
                    <div class="community-tool-grid fm-season-flow-buttons">
                        <article class="community-tool-card">
                            <h4>Play your match</h4>
                            <p class="fm-subtle">Starts your club's current scheduled live/replay-ready match when one exists for this week.</p>
                            <button type="button" id="start-realistic-demo-btn" class="fm-action-btn fm-dashboard-cta" data-label="⚽ Play Your Match" data-community-season-action="play-match">⚽ Play Your Match</button>
                        </article>
                        <article class="community-tool-card">
                            <h4>Simulate other results</h4>
                            <p class="fm-subtle">Runs the remaining fixtures for the current round across the Serbian league pyramid and shows a summary.</p>
                            <button type="button" id="simulate-current-round-btn" class="fm-action-btn" data-label="🧮 Simulate Other Results" data-community-season-action="simulate-round">🧮 Simulate Other Results</button>
                        </article>
                        <article class="community-tool-card">
                            <h4>Advance week</h4>
                            <p class="fm-subtle">Moves the calendar forward once the current round is fully resolved and training/season logic can continue.</p>
                            <button type="button" id="advance-week-btn" class="fm-action-btn secondary" data-label="📅 Advance Week" data-community-season-action="advance-week">📅 Advance Week</button>
                        </article>
                    </div>
                </section>`
        });

        bindCommunityInteractions(mainContent);
    }

    async function loadEvents() {
        return loadChat();
    }

    return { loadForum, loadChat, loadEvents };
}