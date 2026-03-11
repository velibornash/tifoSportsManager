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
                        ${buildCommunityActionsHtml(currentPage)}
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

    async function handleSendMessage(form) {
        const textarea = form.querySelector('textarea');
        const submitButton = form.querySelector('button[type="submit"]');
        const message = String(textarea?.value || '').trim();
        if (!message) return;

        submitButton.disabled = true;
        submitButton.textContent = 'Sending...';
        try {
            await authFetch('/community/chat', {
                method: 'POST',
                body: JSON.stringify({ message })
            });
            textarea.value = '';
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
    }

    async function loadChat() {
        const mainContent = document.getElementById('main-content');
        try {
            const response = await authFetch('/community/chat');
            const messages = response.ok ? await response.json() : [];
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
                                <p class="fm-subtle">Signed in as ${escapeHtml(getUsername?.() || 'Manager')}. Messages are stored in the shared community feed.</p>
                            </div>
                            <span class="fm-panel-action">Live feed</span>
                        </div>
                        <form id="community-compose-form" class="community-compose-form">
                            <textarea class="community-compose-textarea" name="message" maxlength="1200" placeholder="Write a message to the community..." required></textarea>
                            <div class="community-compose-actions">
                                <span class="fm-subtle">${adminViewer ? 'Service messages below can also contain registration approvals.' : 'Admin service decisions will appear here as system messages.'}</span>
                                <button type="submit" class="fm-action-btn">Send message</button>
                            </div>
                        </form>
                    </section>
                    <section class="fm-panel">
                        <div class="fm-panel-head">
                            <div>
                                <h3>Recent feed</h3>
                                <p class="fm-subtle">Newest messages first. Pending registration requests are visible only to admin-side roles.</p>
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
        return loadChat();
    }

    async function loadEvents() {
        return loadChat();
    }

    return { loadForum, loadChat, loadEvents };
}