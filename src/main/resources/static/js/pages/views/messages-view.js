// messages-view.js
//
// Private messages (owner, 2026-10-05): an inbox, a conversation, and a way to start one.
//
// WHY THIS REPLACES THE OLD CHAT ROUTE
//
// The Community page was one screen reached by a menu button, with a recipient dropdown and no thread:
// every message was a row in a shared feed and there was no way to follow a correspondence. `pages.js`
// routed `chat` at it. This module is what "Messages" now renders.
//
// THE THREAD IS THE POINT
//
// The owner's specification: "if you reply to a particular message, a thread is created so the
// correspondence history can be followed." So a conversation is a subject plus an ordered list, and a
// reply carries no subject of its own. `MessageService.send` decides whether a body starts a conversation
// or continues one, and the client only has to say which.

import { authFetch } from '../../auth.js';
import { escapeHtml } from '../../ui/escape.js';
import { buildEmptyState } from './utils.js';
import { backButtonHtml } from '../../ui/components.js';

const THREADS_PAGE = 30;
const MESSAGES_PAGE = 30;
const MAX_BODY = 4000;
const MAX_SUBJECT = 150;

export function createMessagesView(deps) {
    const { buildCommunityActionsHtml } = deps;

    // ── The inbox ───────────────────────────────────────────────────────────────────────────────────

    async function loadMessages() {
        const main = document.getElementById('main-content');
        if (!main) return;

        let payload;
        try {
            const response = await authFetch(`/messages?size=${THREADS_PAGE}`);
            if (!response.ok) throw new Error(`status ${response.status}`);
            payload = await response.json();
        } catch (err) {
            main.innerHTML = buildEmptyState('Your messages could not be loaded. ' + (err?.message || ''));
            return;
        }

        const threads = payload.threads || [];
        const unread = Number(payload.unreadThreads || 0);

        main.innerHTML = `
            <div class="fm-page fm-page--club">
                <section class="fm-panel fm-club-hero">
                    ${backButtonHtml('Back', 'dashboard')}
                    <div class="fm-club-hero-main">
                        <div>
                            <div class="fm-eyebrow">Community</div>
                            <h2>Messages</h2>
                        </div>
                    </div>
                    <div class="fm-medical-stat-grid team-summary-grid">
                        <div><strong>${threads.length}</strong><span>Conversations</span></div>
                        <div><strong>${unread}</strong><span>Unread</span></div>
                    </div>
                </section>

                ${buildCommunityActionsHtml('messages')}

                <section class="fm-panel">
                    <div class="fm-panel-head">
                        <div>
                            <h3>New message</h3>
                            <p class="fm-subtle">To any manager. He does not have to be online.</p>
                        </div>
                    </div>
                    ${composeFormHtml()}
                </section>

                <section class="fm-panel">
                    <div class="fm-panel-head">Conversations</div>
                    ${threads.length ? threadListHtml(threads) : emptyHtml('No conversations yet.')}
                </section>
            </div>`;

        main.querySelectorAll('.js-open-thread').forEach(button => {
            button.addEventListener('click', () => {
                // Through the router, so it pushes the navigation history. Calling the view directly
                // navigated without recording where from, and Back then popped whatever was open before
                // the inbox rather than the inbox.
                const threadId = button.dataset.threadId;
                if (typeof window.loadPage === 'function') {
                    window.loadPage('messageThread', { threadId });
                } else {
                    loadMessageThread(threadId);
                }
            });
        });
        main.querySelectorAll('.js-open-manager').forEach(button => {
            button.addEventListener('click', () => {
                const id = button.dataset.managerId;
                if (id && typeof window.openUserProfile === 'function') window.openUserProfile(id);
            });
        });
        bindCompose(main);
    }

    // ── One conversation ───────────────────────────────────────────────────────────────────────────

    async function loadMessageThread(threadId) {
        const main = document.getElementById('main-content');
        if (!main) return;

        let payload;
        try {
            const response = await authFetch(`/messages/threads/${encodeURIComponent(threadId)}?size=${MESSAGES_PAGE}`);
            if (!response.ok) {
                throw new Error(response.status === 403
                    ? 'That is not your conversation.'
                    : 'That conversation does not exist.');
            }
            payload = await response.json();
        } catch (err) {
            main.innerHTML = buildEmptyState('This conversation could not be loaded. ' + (err?.message || ''));
            return;
        }

        const thread = payload.thread || {};
        const rows = payload.messages || [];

        main.innerHTML = `
            <div class="fm-page fm-page--club">
                <section class="fm-panel fm-club-hero">
                    ${backButtonHtml('Back', 'messages')}
                    <div class="fm-club-hero-main">
                        <div>
                            <div class="fm-eyebrow">Conversation with
                                <button type="button" class="fm-link-btn js-open-manager"
                                        data-manager-id="${escapeHtml(thread.otherUserId)}">${escapeHtml(thread.otherName || 'a manager')}</button>
                            </div>
                            <h2>${escapeHtml(thread.subject || '(no subject)')}</h2>
                        </div>
                    </div>
                    <div class="fm-medical-stat-grid team-summary-grid">
                        <div><strong>${thread.messageCount ?? 0}</strong><span>Messages</span></div>
                        <div><strong>${escapeHtml(formatWhen(thread.lastActivityAt))}</strong><span>Last activity</span></div>
                    </div>
                </section>

                ${buildCommunityActionsHtml('messageThread')}

                <section class="fm-panel">
                    <div class="fm-panel-head">History</div>
                    ${rows.length
                        ? rows.map(row => messageHtml(row, payload.viewerUserId)).join('')
                        : emptyHtml('No messages yet.')}
                </section>

                <section class="fm-panel">
                    <div class="fm-panel-head">Reply</div>
                    <form class="community-compose-form js-thread-reply" data-thread-id="${escapeHtml(thread.id)}">
                        <div class="community-compose-textarea">
                            <textarea name="body" maxlength="${MAX_BODY}" rows="4"
                                      placeholder="Your reply" required></textarea>
                        </div>
                        <div class="community-compose-actions">
                            <button type="submit" class="fm-action-btn">Send reply</button>
                        </div>
                        <div class="community-compose-toolbar fm-subtle" id="message-reply-status"></div>
                    </form>
                </section>
            </div>`;

        main.querySelectorAll('.js-open-manager').forEach(button => {
            button.addEventListener('click', () => {
                const id = button.dataset.managerId;
                if (id && typeof window.openUserProfile === 'function') window.openUserProfile(id);
            });
        });

        const form = main.querySelector('.js-thread-reply');
        form?.addEventListener('submit', async (event) => {
            event.preventDefault();
            const status = form.querySelector('#message-reply-status');
            const textarea = form.querySelector('[name="body"]');
            const body = textarea.value.trim();
            if (!body) {
                if (status) status.textContent = 'A message cannot be empty.';
                return;
            }

            const button = form.querySelector('button[type="submit"]');
            button.disabled = true;
            try {
                // threadId, not recipientId: a reply continues the conversation and carries no subject.
                const response = await authFetch('/messages', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ threadId: form.dataset.threadId, body })
                });
                const result = await response.json().catch(() => ({}));
                if (!response.ok) {
                    if (status) status.textContent = result.message || result.error
                        || `Not sent (status ${response.status}).`;
                    return;
                }
                await loadMessageThread(form.dataset.threadId);
            } catch (err) {
                if (status) status.textContent = `Could not send: ${err?.message || ''}`;
            } finally {
                button.disabled = false;
            }
        });
    }

    // ── Rendering ───────────────────────────────────────────────────────────────────────────────────

    function threadListHtml(threads) {
        return `<div class="forum-topic-list">${threads.map(threadRowHtml).join('')}</div>`;
    }

    function threadRowHtml(thread) {
        return `
            <div class="forum-topic-row">
                <div class="forum-topic-main">
                    <button type="button" class="forum-topic-title js-open-thread"
                            data-thread-id="${escapeHtml(thread.id)}">${escapeHtml(thread.subject || '(no subject)')}</button>
                    <div class="forum-topic-meta">
                        with <button type="button" class="fm-link-btn js-open-manager"
                                      data-manager-id="${escapeHtml(thread.otherUserId)}">${escapeHtml(thread.otherName || 'a manager')}</button>
                        · ${thread.messageCount ?? 0} ${(thread.messageCount ?? 0) === 1 ? 'message' : 'messages'}
                        · ${escapeHtml(formatWhen(thread.lastActivityAt))}
                    </div>
                </div>
                ${thread.unread ? '<span class="forum-topic-section">Unread</span>' : ''}
            </div>`;
    }

    /**
     * A message in a conversation.
     *
     * <p><b>Pass the viewer's id explicitly.</b> Written as `rows.map(messageHtml)` it was correct-looking
     * and wrong: {@code map} hands the array index as the second argument, so "is this mine" was true for
     * the first message of every conversation and false for the rest. The id comes from the response,
     * which the server knows and the client would otherwise have to fetch separately.
     */
    function messageHtml(message, viewerId) {
        const mine = viewerId != null && String(message.senderUserId) === String(viewerId);
        return `
            <article class="forum-post${mine ? ' is-mine' : ''}"
                     data-message-id="${escapeHtml(message.id)}">
                <header class="forum-post-head">
                    <button type="button" class="forum-post-author js-open-manager"
                            data-manager-id="${escapeHtml(message.senderUserId)}">${escapeHtml(message.senderName)}</button>
                    <span class="fm-subtle">${escapeHtml(formatWhen(message.createdAt))}</span>
                    ${message.edited ? '<span class="forum-edited-tag">edited</span>' : ''}
                </header>
                ${message.deleted
                    ? '<p class="forum-post-deleted">This message was deleted.</p>'
                    : `<div class="forum-post-body">${escapeHtml(message.body)}</div>`}
            </article>`;
    }

    /**
     * The compose form, with the recipient list fetched from the server.
     *
     * <p>The list is a server response rather than a hard-coded array for one reason: it is
     * <b>every account</b>, and a list maintained by hand in a template is a list that stops being true
     * the moment a manager registers.
     */
    function composeFormHtml() {
        return `
            <form class="community-compose-form js-compose" id="message-compose">
                <div class="compose-recipient">
                    <label class="fm-field-label" for="message-recipient-search">To</label>
                    <!-- A text box over a hidden select, not a native <select>.
                         The owner asked to be able to type a name to find a manager, and a native select
                         cannot: it scrolls, it matches from the start only, and it is unusable on a
                         phone once there are more than a handful of managers. The <select> is kept
                         behind it because it is what the form submits and what the tests read - the
                         chosen id has to survive a validation error and a page re-render. -->
                    <input type="text" id="message-recipient-search" class="compose-recipient-input"
                           placeholder="Type a name to find a manager"
                           autocomplete="off" role="combobox" aria-expanded="false"
                           aria-controls="message-recipient-list" aria-autocomplete="list" />
                    <ul class="compose-recipient-list" id="message-recipient-list" role="listbox" hidden></ul>
                    <select id="message-recipient" name="recipientUserId" required hidden>
                        <option value="">Loading managers...</option>
                    </select>
                    <div class="fm-subtle" id="message-recipient-status"></div>
                </div>
                <div class="community-compose-textarea">
                    <input type="text" name="subject" maxlength="${MAX_SUBJECT}"
                           placeholder="Subject" required />
                </div>
                <div class="community-compose-textarea">
                    <textarea name="body" maxlength="${MAX_BODY}" rows="5"
                              placeholder="Your message" required></textarea>
                </div>
                <div class="community-compose-actions">
                    <button type="submit" class="fm-action-btn">Send</button>
                </div>
                <div class="community-compose-toolbar fm-subtle" id="message-compose-status"></div>
            </form>`;
    }

    /**
     * Filters the manager list as you type.
     *
     * <p>Matches on the name anywhere in the string, case-insensitively, so "kec" finds Kecko and
     * "cko" does too. Also matches the login, because a manager who never set a name is shown his login
     * and that is what you would be typing.
     *
     * <p>Filtering is done here rather than server-side: the recipient list is every account, so it is
     * already in the page, and a request per keystroke would be slower and would fail on a flaky
     * connection at exactly the moment somebody is trying to find somebody.
     */
    function filterRecipients(list, query) {
        const wanted = String(query || '').trim().toLowerCase();
        if (!wanted) return list;
        return list.filter(r => {
            const name = String(r.displayName || '').toLowerCase();
            const login = String(r.login || '').toLowerCase();
            return name.includes(wanted) || login.includes(wanted);
        });
    }

    function emptyHtml(message) {
        return `<div class="fm-empty">${escapeHtml(message)}</div>`;
    }

    function formatWhen(iso) {
        if (!iso) return '—';
        const then = new Date(iso);
        if (Number.isNaN(then.getTime())) return String(iso);
        const seconds = Math.max(0, Math.round((Date.now() - then.getTime()) / 1000));
        if (seconds < 60) return 'just now';
        const minutes = Math.round(seconds / 60);
        if (minutes < 60) return `${minutes} min ago`;
        const hours = Math.round(minutes / 60);
        if (hours < 24) return `${hours} h ago`;
        const days = Math.round(hours / 24);
        if (days < 7) return `${days} d ago`;
        return then.toLocaleDateString();
    }

    /** Fills the recipient picker and binds the compose form. */
    function bindCompose(main) {
        const form = main.querySelector('.js-compose');
        if (!form) return;
        const status = form.querySelector('#message-compose-status');

        const search = form.querySelector('#message-recipient-search');
        const list = form.querySelector('#message-recipient-list');
        const pickerStatus = form.querySelector('#message-recipient-status');
        const select = form.querySelector('[name="recipientUserId"]');
        let everyone = [];
        let shown = [];

        function closeList() {
            if (!list) return;
            list.hidden = true;
            list.innerHTML = '';
            if (search) search.setAttribute('aria-expanded', 'false');
        }

        function renderList(matches, query) {
            if (!list) return;
            shown = matches;
            if (!matches.length) {
                list.hidden = false;
                list.innerHTML = `<li class="compose-recipient-none">${
                    everyone.length
                        ? `No manager matches "${escapeHtml(query)}".`
                        : 'There are no other managers yet.'}</li>`;
                if (search) search.setAttribute('aria-expanded', 'true');
                return;
            }
            list.hidden = false;
            if (search) search.setAttribute('aria-expanded', 'true');
            list.innerHTML = matches.map(r => {
                const name = r.displayName || 'Manager';
                const suffix = r.hasChosenName ? '' : ' \u00b7 no name set';
                return `<li class="compose-recipient-option" role="option" data-user-id="${escapeHtml(r.userId)}">`
                    + `<span class="compose-recipient-name">${escapeHtml(name)}</span>`
                    + `<span class="fm-subtle">${escapeHtml(suffix)}</span></li>`;
            }).join('');
            list.querySelectorAll('.compose-recipient-option').forEach(option => {
                option.addEventListener('mousedown', (event) => {
                    // mousedown, not click: the input's blur closes the list before a click lands.
                    event.preventDefault();
                    choose(option.dataset.userId);
                });
            });
        }

        function choose(userId) {
            const chosen = everyone.find(r => String(r.userId) === String(userId));
            if (!chosen) return;
            if (select) select.value = String(chosen.userId);
            if (search) {
                search.value = chosen.displayName || chosen.login || '';
                search.setAttribute('aria-expanded', 'false');
            }
            closeList();
            if (pickerStatus) pickerStatus.textContent = `To: ${chosen.displayName || chosen.login}`;
        }

        void loadRecipients().then((recipients) => {
            everyone = recipients;
            if (!select) return;
            if (!everyone.length) {
                select.innerHTML = '<option value="">No other managers yet</option>';
                if (pickerStatus) pickerStatus.textContent = 'There is nobody to write to yet.';
                if (search) search.disabled = true;
                return;
            }
            // Populated even though the select is hidden: it is what the form submits and what the
            // browser validation reads, so the chosen id survives a failed send.
            select.innerHTML = '<option value="">Choose a manager</option>'
                + everyone.map(r => {
                    const name = r.displayName || 'Manager';
                    const suffix = r.hasChosenName ? '' : ' (login, no name set)';
                    return `<option value="${escapeHtml(r.userId)}">${escapeHtml(name)}${escapeHtml(suffix)}</option>`;
                }).join('');
            if (search) search.disabled = false;
        });

        search?.addEventListener('input', () => renderList(filterRecipients(everyone, search.value), search.value));
        search?.addEventListener('focus', () => renderList(filterRecipients(everyone, search.value), search.value));
        search?.addEventListener('blur', () => {
            // The list is under the input, so a plain click on an option blurs the input first. Deferred
            // by a tick so the option's mousedown has run.
            window.setTimeout(() => { if (!list?.matches(':hover')) closeList(); }, 120);
        });
        search?.addEventListener('keydown', (event) => {
            if (event.key === 'Escape') {
                closeList();
                search.blur();
                return;
            }
            if (event.key !== 'ArrowDown' && event.key !== 'Enter') return;
            const first = shown[0];
            if (!first) return;
            event.preventDefault();
            choose(first.userId);
        });
        document.addEventListener('click', (event) => {
            if (!list || list.hidden) return;
            if (list.contains(event.target) || search?.contains(event.target)) return;
            closeList();
        });

        form.addEventListener('submit', async (event) => {
            event.preventDefault();
            const select = form.querySelector('[name="recipientUserId"]');
            const subject = form.querySelector('[name="subject"]').value.trim();
            const body = form.querySelector('[name="body"]').value.trim();
            const recipientId = select.value;

            if (!recipientId) {
                if (status) status.textContent = 'Choose someone to send this to.';
                return;
            }
            if (!subject || !body) {
                if (status) status.textContent = 'A message needs a subject and a body.';
                return;
            }

            const button = form.querySelector('button[type="submit"]');
            button.disabled = true;
            try {
                const response = await authFetch('/messages', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ recipientUserId: Number(recipientId), subject, body })
                });
                const result = await response.json().catch(() => ({}));
                if (!response.ok) {
                    if (status) status.textContent = result.message || result.error
                        || `Not sent (status ${response.status}).`;
                    return;
                }
                await loadMessageThread(result.threadId);
            } catch (err) {
                if (status) status.textContent = `Could not send: ${err?.message || ''}`;
            } finally {
                button.disabled = false;
            }
        });
    }

    async function loadRecipients() {
        try {
            const response = await authFetch('/messages/recipients');
            if (!response.ok) return [];
            const rows = await response.json();
            return Array.isArray(rows) ? rows : [];
        } catch {
            return [];
        }
    }

    return { loadMessages, loadMessageThread };
}