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

        mainContent.innerHTML = buildCommunityPageShell({
            currentPage: 'forum',
            eyebrow: 'Community forum',
            title: 'Forum',
            subtitle: 'Community now opens directly to the forum page, with the old sidebar actions moved into the page action row.',
            stats: [
                { value: orderedPosts.length, label: 'Posts' },
                { value: uniqueAuthors.length, label: 'Authors' },
                { value: latestPost ? formatDateTimeLabel(latestPost.date) : '—', label: 'Latest post' },
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
                                    ${orderedPosts.map(post => `
                                        <tr class="fm-squad-row">
                                            <td>${escapeHtml(post.author || 'Unknown')}</td>
                                            <td class="sq-name">${escapeHtml(post.title || 'Untitled topic')}</td>
                                            <td>${formatDateTimeLabel(post.date)}</td>
                                        </tr>`).join('')}
                                </tbody>
                            </table>
                        </div>`}
                </section>`
        });
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

        mainContent.innerHTML = buildCommunityPageShell({
            currentPage: 'chat',
            eyebrow: 'Team chat',
            title: 'Team Chat',
            subtitle: 'Quick locker-room communication now lives inside the same Community shell instead of the old desktop sidebar flow.',
            stats: [
                { value: orderedMessages.length, label: 'Messages' },
                { value: uniqueUsers.length, label: 'Active users' },
                { value: latestMessage ? formatDateTimeLabel(latestMessage.date) : '—', label: 'Latest message' },
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
                        <div class="club-profile-detail-list">
                            ${orderedMessages.map(message => `
                                <div class="club-profile-detail-row">
                                    <span>${escapeHtml(message.user || 'Unknown')}</span>
                                    <strong>${escapeHtml(message.message || '')}</strong>
                                    <span>${formatDateTimeLabel(message.date)}</span>
                                </div>`).join('')}
                        </div>`}
                </section>`
        });
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

        mainContent.innerHTML = buildCommunityPageShell({
            currentPage: 'events',
            eyebrow: 'Club events',
            title: 'Events',
            subtitle: 'Meetings, club happenings, and other community moments now use the same open-football-inspired shell as the rest of the section.',
            stats: [
                { value: orderedEvents.length, label: 'Events' },
                { value: latestEvent ? (latestEvent.title || '—') : '—', label: 'Latest item' },
                { value: latestEvent ? formatDateTimeLabel(latestEvent.date) : '—', label: 'Latest date' },
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
                                    ${orderedEvents.map(event => `
                                        <tr class="fm-squad-row">
                                            <td class="sq-name">${escapeHtml(event.title || 'Untitled event')}</td>
                                            <td>${formatDateTimeLabel(event.date)}</td>
                                        </tr>`).join('')}
                                </tbody>
                            </table>
                        </div>`}
                </section>`
        });
    }

    return { loadForum, loadChat, loadEvents };
}