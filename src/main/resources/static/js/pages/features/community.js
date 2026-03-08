export function createCommunityFeature(deps) {
    const { authFetch, getTeamId, escapeHtml, formatDateTimeLabel, buildCommunityActionsHtml } = deps;

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

    function formatCommunityDate(value, fallback = '—') {
        return value ? formatDateTimeLabel(value) : fallback;
    }

    function buildCommunityDetailBody({ backPage, backLabel, title, subtitle, summaryRows = [], primaryCopy = '', secondaryTitle = '', secondaryRows = [] }) {
        return `
            <section class="fm-panel">
                <div class="fm-panel-head">
                    <div>
                        <h3>${escapeHtml(title)}</h3>
                        <p class="fm-subtle">${escapeHtml(subtitle || '')}</p>
                    </div>
                    <button type="button" class="fm-action-btn secondary" data-community-back="${escapeHtml(backPage)}">${escapeHtml(backLabel)}</button>
                </div>
                <div class="community-message-body">${escapeHtml(primaryCopy)}</div>
                <div class="club-profile-detail-list" style="margin-top:14px;">
                    ${summaryRows.map(row => `<div class="club-profile-detail-row"><span>${escapeHtml(row.label)}</span><strong>${escapeHtml(String(row.value ?? '-'))}</strong></div>`).join('')}
                </div>
            </section>
            ${secondaryRows.length ? `
                <section class="fm-panel">
                    <div class="fm-panel-head"><h3>${escapeHtml(secondaryTitle || 'Details')}</h3></div>
                    <div class="club-profile-detail-list">
                        ${secondaryRows.map(row => `<div class="club-profile-detail-row"><span>${escapeHtml(row.label)}</span><strong>${escapeHtml(String(row.value ?? '-'))}</strong></div>`).join('')}
                    </div>
                </section>` : ''}`;
    }

    function bindCommunityBack(mainContent, callback) {
        const backButton = mainContent.querySelector('[data-community-back]');
        if (backButton) backButton.addEventListener('click', callback);
    }

    async function loadForum() {
        const teamId = getTeamId();
        console.log(`Loading forum for ${teamId}`);
        const response = await authFetch(`/demo/forum/teams/${teamId}`);
        console.log(`Response status: ${response.status}`);
        const posts = response.ok ? await response.json() : [];

        const mainContent = document.getElementById('main-content');
        const orderedPosts = [...posts].sort((a, b) => new Date(b.date || 0) - new Date(a.date || 0));
        const uniqueAuthors = [...new Set(orderedPosts.map(post => post.author).filter(Boolean))];
        const latestPost = orderedPosts[0] || null;

        function openForumTopic(index) {
            const post = orderedPosts[index];
            if (!post) return loadForum();

            mainContent.innerHTML = buildCommunityPageShell({
                currentPage: 'forum',
                eyebrow: 'Forum topic',
                title: post.title || 'Untitled topic',
                subtitle: 'Lightweight topic drill-down that keeps Community inside the same FM shell.',
                stats: [
                    { value: post.author || 'Unknown', label: 'Author' },
                    { value: formatCommunityDate(post.date), label: 'Posted' },
                    { value: 'General board', label: 'Board' },
                    { value: 'Open', label: 'Status' }
                ],
                bodyHtml: buildCommunityDetailBody({
                    backPage: 'forum',
                    backLabel: 'Back to Forum',
                    title: 'Thread overview',
                    subtitle: 'This view leaves room for fuller thread/reply data later.',
                    primaryCopy: `${post.author || 'A community member'} opened this topic to discuss "${post.title || 'Untitled topic'}". For now, this detail page gives the forum items the same click-through feel as the rest of the app while keeping the implementation safe and frontend-only.`,
                    summaryRows: [
                        { label: 'Topic', value: post.title || 'Untitled topic' },
                        { label: 'Author', value: post.author || 'Unknown' },
                        { label: 'Opened', value: formatCommunityDate(post.date) },
                        { label: 'Activity', value: 'Awaiting full thread backend' }
                    ],
                    secondaryTitle: 'Thread notes',
                    secondaryRows: [
                        { label: 'Discussion focus', value: 'Matchday / club talk' },
                        { label: 'Tone', value: 'Community board' },
                        { label: 'Next step', value: 'Reply flow can be added later' }
                    ]
                })
            });

            bindCommunityBack(mainContent, () => loadForum());
        }

        mainContent.innerHTML = buildCommunityPageShell({
            currentPage: 'forum',
            eyebrow: 'Community forum',
            title: 'Forum',
            subtitle: 'Community now opens directly to the forum page, with the old sidebar actions moved into the page action row.',
            stats: [
                { value: orderedPosts.length, label: 'Posts' },
                { value: uniqueAuthors.length, label: 'Authors' },
                { value: latestPost ? formatCommunityDate(latestPost.date) : '—', label: 'Latest post' },
                { value: 'Open', label: 'Board status' }
            ],
            bodyHtml: `
                <section class="fm-panel">
                    <div class="fm-panel-head">
                        <div>
                            <h3>Latest discussions</h3>
                            <p class="fm-subtle">Forum threads stay simple for now, but they share the same shell and spacing as the rest of Community.</p>
                        </div>
                        <span class="fm-panel-action">${orderedPosts.length} topics</span>
                    </div>
                    ${orderedPosts.length === 0 ? `<div class="fm-empty">No forum posts available right now.</div>` : `
                        <div class="fm-squad-wrap">
                            <table class="fm-squad">
                                <thead><tr><th>Author</th><th class="sq-name">Topic</th><th>Posted</th></tr></thead>
                                <tbody>
                                    ${orderedPosts.map((post, index) => `
                                        <tr class="fm-squad-row community-click-row" data-open-forum="${index}">
                                            <td>${escapeHtml(post.author || 'Unknown')}</td>
                                            <td class="sq-name">${escapeHtml(post.title || 'Untitled topic')}</td>
                                            <td>${formatCommunityDate(post.date)}</td>
                                        </tr>`).join('')}
                                </tbody>
                            </table>
                        </div>`}
                </section>`
        });

        mainContent.querySelectorAll('[data-open-forum]').forEach(row => row.addEventListener('click', () => openForumTopic(Number(row.dataset.openForum))));
    }

    async function loadChat() {
        const teamId = getTeamId();
        console.log(`Loading chat for ${teamId}`);
        const response = await authFetch(`/demo/chat/teams/${teamId}`);
        console.log(`Response status: ${response.status}`);
        const messages = response.ok ? await response.json() : [];

        const mainContent = document.getElementById('main-content');
        const orderedMessages = [...messages].sort((a, b) => new Date(b.date || 0) - new Date(a.date || 0));
        const uniqueUsers = [...new Set(orderedMessages.map(message => message.user).filter(Boolean))];
        const latestMessage = orderedMessages[0] || null;

        function openChatMessage(index) {
            const message = orderedMessages[index];
            if (!message) return loadChat();

            mainContent.innerHTML = buildCommunityPageShell({
                currentPage: 'chat',
                eyebrow: 'Chat thread',
                title: message.user || 'Team message',
                subtitle: 'Locker-room messages now open their own FM-style detail panel.',
                stats: [
                    { value: message.user || 'Unknown', label: 'Sender' },
                    { value: formatCommunityDate(message.date, 'Live room'), label: 'Sent' },
                    { value: 'Locker room', label: 'Channel' },
                    { value: String(message.message || '').length, label: 'Chars' }
                ],
                bodyHtml: buildCommunityDetailBody({
                    backPage: 'chat',
                    backLabel: 'Back to Chat',
                    title: 'Message detail',
                    subtitle: 'Simple detail view for chat lines, ready for a richer message thread later.',
                    primaryCopy: message.message || 'No message text available.',
                    summaryRows: [
                        { label: 'Sender', value: message.user || 'Unknown' },
                        { label: 'Sent', value: formatCommunityDate(message.date, 'Live room') },
                        { label: 'Context', value: 'Squad communication' },
                        { label: 'Visibility', value: 'Team only' }
                    ],
                    secondaryTitle: 'Message notes',
                    secondaryRows: [
                        { label: 'Status', value: 'Delivered' },
                        { label: 'Room', value: 'Locker room' },
                        { label: 'Future expansion', value: 'Reply chain / reactions' }
                    ]
                })
            });

            bindCommunityBack(mainContent, () => loadChat());
        }

        mainContent.innerHTML = buildCommunityPageShell({
            currentPage: 'chat',
            eyebrow: 'Team chat',
            title: 'Team Chat',
            subtitle: 'Quick locker-room communication now lives inside the same Community shell instead of the old desktop sidebar flow.',
            stats: [
                { value: orderedMessages.length, label: 'Messages' },
                { value: uniqueUsers.length, label: 'Active users' },
                { value: latestMessage ? formatCommunityDate(latestMessage.date, 'Live room') : '—', label: 'Latest message' },
                { value: 'Live', label: 'Room status' }
            ],
            bodyHtml: `
                <section class="fm-panel">
                    <div class="fm-panel-head">
                        <div>
                            <h3>Locker-room feed</h3>
                            <p class="fm-subtle">Compact chat history with the same dark panel language used across the rest of the app.</p>
                        </div>
                        <span class="fm-panel-action">${orderedMessages.length} lines</span>
                    </div>
                    ${orderedMessages.length === 0 ? `<div class="fm-empty">No chat messages available right now.</div>` : `
                        <div class="fm-squad-wrap">
                            <table class="fm-squad">
                                <thead><tr><th>User</th><th class="sq-name">Message</th><th>Sent</th></tr></thead>
                                <tbody>
                                    ${orderedMessages.map((message, index) => `
                                        <tr class="fm-squad-row community-click-row" data-open-chat="${index}">
                                            <td>${escapeHtml(message.user || 'Unknown')}</td>
                                            <td class="sq-name">${escapeHtml(message.message || '')}</td>
                                            <td>${formatCommunityDate(message.date, 'Live room')}</td>
                                        </tr>`).join('')}
                                </tbody>
                            </table>
                        </div>`}
                </section>`
        });

        mainContent.querySelectorAll('[data-open-chat]').forEach(row => row.addEventListener('click', () => openChatMessage(Number(row.dataset.openChat))));
    }

    async function loadEvents() {
        const teamId = getTeamId();
        console.log(`Loading events for ${teamId}`);
        const response = await authFetch(`/demo/events/teams/${teamId}`);
        console.log(`Response status: ${response.status}`);
        const events = response.ok ? await response.json() : [];

        const mainContent = document.getElementById('main-content');
        const orderedEvents = [...events].sort((a, b) => new Date(b.date || 0) - new Date(a.date || 0));
        const latestEvent = orderedEvents[0] || null;

        function openEventDetail(index) {
            const event = orderedEvents[index];
            if (!event) return loadEvents();

            mainContent.innerHTML = buildCommunityPageShell({
                currentPage: 'events',
                eyebrow: 'Community event',
                title: event.title || 'Untitled event',
                subtitle: 'Event cards now open a detail page that matches the current community styling.',
                stats: [
                    { value: event.title || 'Untitled event', label: 'Event' },
                    { value: formatCommunityDate(event.date), label: 'Date' },
                    { value: 'Club', label: 'Scope' },
                    { value: 'Planned', label: 'Status' }
                ],
                bodyHtml: buildCommunityDetailBody({
                    backPage: 'events',
                    backLabel: 'Back to Events',
                    title: 'Event overview',
                    subtitle: 'A light detail page now exists even before we add full event descriptions or locations from the backend.',
                    primaryCopy: `${event.title || 'This event'} is scheduled for ${formatCommunityDate(event.date)}. The page keeps the same Community shell and gives each board item a proper destination instead of leaving the list flat.`,
                    summaryRows: [
                        { label: 'Event', value: event.title || 'Untitled event' },
                        { label: 'Date', value: formatCommunityDate(event.date) },
                        { label: 'Venue', value: 'Club community space' },
                        { label: 'Status', value: 'Planned' }
                    ],
                    secondaryTitle: 'Operational notes',
                    secondaryRows: [
                        { label: 'Preparation', value: 'Open' },
                        { label: 'Visibility', value: 'Club community' },
                        { label: 'Future expansion', value: 'Description / RSVP / location' }
                    ]
                })
            });

            bindCommunityBack(mainContent, () => loadEvents());
        }

        mainContent.innerHTML = buildCommunityPageShell({
            currentPage: 'events',
            eyebrow: 'Club events',
            title: 'Events',
            subtitle: 'Meetings, club happenings, and other community moments now use the same open-football-inspired shell as the rest of the section.',
            stats: [
                { value: orderedEvents.length, label: 'Events' },
                { value: latestEvent ? (latestEvent.title || '—') : '—', label: 'Latest item' },
                { value: latestEvent ? formatCommunityDate(latestEvent.date) : '—', label: 'Latest date' },
                { value: 'Club', label: 'Scope' }
            ],
            bodyHtml: `
                <section class="fm-panel">
                    <div class="fm-panel-head">
                        <div>
                            <h3>Calendar board</h3>
                            <p class="fm-subtle">Same data as before, just reorganized into the new Community page layout.</p>
                        </div>
                        <span class="fm-panel-action">Board</span>
                    </div>
                    ${orderedEvents.length === 0 ? `<div class="fm-empty">No events available right now.</div>` : `
                        <div class="fm-squad-wrap">
                            <table class="fm-squad">
                                <thead><tr><th class="sq-name">Event</th><th>Date</th></tr></thead>
                                <tbody>
                                    ${orderedEvents.map((event, index) => `
                                        <tr class="fm-squad-row community-click-row" data-open-event="${index}">
                                            <td class="sq-name">${escapeHtml(event.title || 'Untitled event')}</td>
                                            <td>${formatCommunityDate(event.date)}</td>
                                        </tr>`).join('')}
                                </tbody>
                            </table>
                        </div>`}
                </section>`
        });

        mainContent.querySelectorAll('[data-open-event]').forEach(row => row.addEventListener('click', () => openEventDetail(Number(row.dataset.openEvent))));
    }

    return { loadForum, loadChat, loadEvents };
}