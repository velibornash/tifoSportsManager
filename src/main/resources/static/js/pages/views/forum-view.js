// forum-view.js
//
// The forum (owner, 2026-10-05). Old-school and flat: a section, a list of topics, a thread.
//
// THREE SCREENS IN ONE FILE, DELIBERATELY
//
// The section list, the topic list and the thread are three renderings of the same data at three
// granularities, and splitting them across three modules would mean three files each holding a piece of
// the paging contract. Pages 1 and 2 are `loadForum()` and `loadForumSection()`; page 3 is
// `loadForumTopic()`.
//
// WHY THE FORUM REPLACED A ROUTE THAT ALREADY EXISTED
//
// `pages.js` routed three names — `forum`, `chat`, `events` — and `community.js:314-320` made all three
// `return loadChat()`. There was no forum. This module is what the `forum` route now actually renders, so
// the route the menu already pointed at stops being a lie.

import { authFetch } from '../../auth.js';
import { escapeHtml } from '../../ui/escape.js';
import { buildEmptyState } from './utils.js';
import { backButtonHtml } from '../../ui/components.js';

const TOPICS_PAGE = 30;
const POSTS_PAGE = 30;
const MAX_BODY = 4000;

/** The two sections, from the server's enum. Hard-coded here only as a label map for the tabs. */
const SECTION_LABELS = {
    TIFO: 'TIFO',
    GENERAL: 'Non-TIFO',
};

export function createForumView(deps) {
    const { getUsername, buildCommunityActionsHtml } = deps;

    // ── The section list, with a topic count on each ────────────────────────────────────────────────

    async function loadForum() {
        const main = document.getElementById('main-content');
        if (!main) return;

        let payload;
        try {
            const response = await authFetch(`/forum/topics?page=0&size=${TOPICS_PAGE}`);
            if (!response.ok) throw new Error(`status ${response.status}`);
            payload = await response.json();
        } catch (err) {
            main.innerHTML = buildEmptyState('The forum could not be loaded. ' + (err?.message || ''));
            return;
        }

        const topics = payload.topics || [];
        const counts = countBySection(topics);

        main.innerHTML = `
            <div class="fm-page fm-page--club">
                <section class="fm-panel fm-club-hero">
                    ${backButtonHtml('Back', 'dashboard')}
                    <div class="fm-club-hero-main">
                        <div>
                            <div class="fm-eyebrow">Community</div>
                            <h2>Forum</h2>
                        </div>
                    </div>
                    <div class="fm-medical-stat-grid team-summary-grid">
                        <div><strong>2</strong><span>Sections</span></div>
                        <div><strong>${topics.length}</strong><span>Recent topics</span></div>
                        <div><strong>${counts.TIFO}</strong><span>In TIFO</span></div>
                        <div><strong>${counts.GENERAL}</strong><span>In non-TIFO</span></div>
                    </div>
                </section>

                ${buildCommunityActionsHtml('forum')}

                <section class="fm-panel">
                    <div class="fm-panel-head">Sections</div>
                    <div class="fm-club-actions">
                        <button type="button" class="fm-action-btn js-section" data-section="TIFO">
                            TIFO
                        </button>
                        <button type="button" class="fm-action-btn secondary js-section" data-section="GENERAL">
                            Non-TIFO
                        </button>
                        <button type="button" class="fm-action-btn js-new-topic" data-section="TIFO">
                            New topic
                        </button>
                    </div>
                    <p class="fm-subtle" style="margin-top:12px;">
                        TIFO is for chants, visuals and matchday atmosphere. Non-TIFO is for everything else.
                    </p>
                </section>

                <section class="fm-panel">
                    <div class="fm-panel-head">
                        <div>
                            <h3>Latest activity</h3>
                            <p class="fm-subtle">Most recently active first, across both sections.</p>
                        </div>
                    </div>
                    ${topics.length ? topicListHtml(topics) : emptyHtml('No topics yet. Open the first one.')}
                </section>
            </div>`;

        bindForum(main);
    }

    // ── One section's topics ────────────────────────────────────────────────────────────────────────

    async function loadForumSection(section, page = 0) {
        const main = document.getElementById('main-content');
        if (!main) return;

        let payload;
        try {
            const response = await authFetch(
                `/forum/topics?section=${encodeURIComponent(section)}&page=${page}&size=${TOPICS_PAGE}`);
            if (!response.ok) throw new Error(`status ${response.status}`);
            payload = await response.json();
        } catch (err) {
            main.innerHTML = buildEmptyState('That section could not be loaded. ' + (err?.message || ''));
            return;
        }

        const topics = payload.topics || [];
        const label = SECTION_LABELS[section] || section;

        main.innerHTML = `
            <div class="fm-page fm-page--club">
                <section class="fm-panel fm-club-hero">
                    ${backButtonHtml('Back', 'dashboard')}
                    <div class="fm-club-hero-main">
                        <div>
                            <div class="fm-eyebrow">Forum</div>
                            <h2>${escapeHtml(label)}</h2>
                        </div>
                    </div>
                    <div class="fm-club-actions">
                        <button type="button" class="fm-action-btn js-new-topic" data-section="${escapeHtml(section)}">
                            New topic in ${escapeHtml(label)}
                        </button>
                    </div>
                </section>

                ${buildCommunityActionsHtml('forumSection')}

                <section class="fm-panel">
                    <div class="fm-panel-head">Topics</div>
                    ${topics.length ? topicListHtml(topics) : emptyHtml('No topics in this section yet.')}
                    ${pagerHtml(page, payload.hasMore)}
                </section>
            </div>`;

        bindForum(main);
    }

    // ── One topic, with its posts ───────────────────────────────────────────────────────────────────

    async function loadForumTopic(topicId, page = 0) {
        const main = document.getElementById('main-content');
        if (!main) return;

        let payload;
        try {
            const response = await authFetch(`/forum/topics/${encodeURIComponent(topicId)}?page=${page}&size=${POSTS_PAGE}`);
            if (!response.ok) {
                throw new Error(response.status === 404
                    ? 'That topic does not exist.'
                    : `status ${response.status}`);
            }
            payload = await response.json();
        } catch (err) {
            main.innerHTML = buildEmptyState('This topic could not be loaded. ' + (err?.message || ''));
            return;
        }

        const topic = payload.topic || {};
        const rows = payload.posts || [];

        main.innerHTML = `
            <div class="fm-page fm-page--club">
                <section class="fm-panel fm-club-hero">
                    ${backButtonHtml('Back', 'dashboard')}
                    <div class="fm-club-hero-main">
                        <div>
                            <div class="fm-eyebrow">${escapeHtml(SECTION_LABELS[topic.section] || 'Forum')}</div>
                            <h2>${escapeHtml(topic.title || 'Topic')}</h2>
                        </div>
                    </div>
                    <div class="fm-medical-stat-grid team-summary-grid">
                        <div><strong>${topic.postCount ?? 0}</strong><span>Posts</span></div>
                        <div><strong>${escapeHtml(topic.authorName || 'Manager')}</strong><span>Started by</span></div>
                        <div><strong>${escapeHtml(formatWhen(topic.lastActivityAt))}</strong><span>Last activity</span></div>
                        <div><strong>${escapeHtml(topic.sectionLabel || '—')}</strong><span>Section</span></div>
                    </div>
                </section>

                ${buildCommunityActionsHtml('forumTopic')}

                <section class="fm-panel">
                    <div class="fm-panel-head">Replies</div>
                    ${rows.length ? rows.map(postHtml).join('') : emptyHtml('No posts yet.')}
                    ${pagerHtml(page, payload.hasMore)}
                </section>

                ${replyFormHtml(topic.id)}
            </div>`;

        bindTopic(main, topic.id);
    }

    // ── Rendering ───────────────────────────────────────────────────────────────────────────────────

    function topicListHtml(topics) {
        return `<div class="forum-topic-list">${topics.map(topicRowHtml).join('')}</div>`;
    }

    function topicRowHtml(topic) {
        // The title is one button, not a link inside a clickable row: the row also opens the topic, and
        // nested handlers mean one of the two wins by accident.
        return `
            <div class="forum-topic-row">
                <div class="forum-topic-main">
                    <button type="button" class="forum-topic-title js-open-topic"
                            data-topic-id="${escapeHtml(topic.id)}">${escapeHtml(topic.title)}</button>
                    <div class="forum-topic-meta">
                        by ${authorLineHtml(topic)}
                        · ${topic.postCount ?? 0} ${(topic.postCount ?? 0) === 1 ? 'post' : 'posts'}
                        · ${escapeHtml(formatWhen(topic.lastActivityAt))}
                    </div>
                </div>
                <span class="forum-topic-section">${escapeHtml(topic.sectionLabel || '')}</span>
            </div>`;
    }

    /**
     * A post.
     *
     * <p>Every field here is typed by another manager — the name, and the body when it is not deleted —
     * so every one goes through {@link escapeHtml}. The body is a text node, not markup: a forum post is
     * plain text in this build and there is no renderer for anything else.
     */
    function postHtml(post) {
        const deleted = post.deleted === true;
        const editedTag = post.editedByModerator
            ? '<span class="forum-edited-tag">edited by a moderator</span>'
            : (post.edited ? '<span class="forum-edited-tag">edited</span>' : '');

        return `
            <article class="forum-post${deleted ? ' is-deleted' : ''}" data-post-id="${escapeHtml(post.id)}">
                <header class="forum-post-head">
                    <button type="button" class="forum-post-author js-open-manager"
                            data-manager-id="${escapeHtml(post.authorUserId)}">${escapeHtml(post.authorName)}</button>
                    ${authorFlagHtml(post)}
                    <span class="fm-subtle">${escapeHtml(formatWhen(post.createdAt))}</span>
                    ${editedTag}
                    <span class="forum-post-actions">
                        ${post.canEdit ? `<button type="button" class="fm-link-btn js-edit-post" data-post-id="${escapeHtml(post.id)}">Edit</button>` : ''}
                        ${post.canDelete ? `<button type="button" class="fm-link-btn js-delete-post" data-post-id="${escapeHtml(post.id)}">Delete</button>` : ''}
                    </span>
                </header>
                ${deleted
                    ? '<p class="forum-post-deleted">This message was deleted.</p>'
                    : `<div class="forum-post-body">${escapeHtml(post.body)}</div>`}
            </article>`;
    }

    function replyFormHtml(topicId) {
        return `
            <section class="fm-panel">
                <div class="fm-panel-head">Reply</div>
                <form class="community-compose-form js-reply-form" data-topic-id="${escapeHtml(topicId)}">
                    <div class="community-compose-textarea">
                        <textarea name="body" maxlength="${MAX_BODY}" rows="5"
                                  placeholder="Your message" required></textarea>
                    </div>
                    <div class="community-compose-actions">
                        <button type="submit" class="fm-action-btn">Post reply</button>
                    </div>
                    <div class="community-compose-toolbar fm-subtle" id="forum-reply-status"></div>
                </form>
            </section>`;
    }

    function newTopicFormHtml(section) {
        return `
            <section class="fm-panel">
                <div class="fm-panel-head">New topic</div>
                <form class="community-compose-form js-new-topic-form" data-section="${escapeHtml(section)}">
                    <div class="community-compose-toolbar">
                        <label class="fm-field-label" for="forum-topic-section">Section</label>
                        <select id="forum-topic-section" name="section">
                            ${Object.entries(SECTION_LABELS).map(([value, label]) => `
                                <option value="${escapeHtml(value)}"${value === section ? ' selected' : ''}>
                                    ${escapeHtml(label)}
                                </option>`).join('')}
                        </select>
                    </div>
                    <div class="community-compose-textarea">
                        <input type="text" name="title" maxlength="120" placeholder="Topic title" required />
                    </div>
                    <div class="community-compose-textarea">
                        <textarea name="body" maxlength="${MAX_BODY}" rows="5"
                                  placeholder="Say something" required></textarea>
                    </div>
                    <div class="community-compose-actions">
                        <button type="submit" class="fm-action-btn">Open topic</button>
                    </div>
                    <div class="community-compose-toolbar fm-subtle" id="forum-new-status"></div>
                </form>
            </section>`;
    }

    function pagerHtml(page, hasMore) {
        if (!page && !hasMore) return '';
        return `
            <div class="forum-pager">
                ${page > 0 ? `<button type="button" class="fm-action-btn secondary js-page" data-page="${page - 1}">Newer</button>` : ''}
                ${hasMore ? `<button type="button" class="fm-action-btn secondary js-page" data-page="${page + 1}">Older</button>` : ''}
            </div>`;
    }

    function emptyHtml(message) {
        return `<div class="fm-empty">${escapeHtml(message)}</div>`;
    }

    function countBySection(topics) {
        const counts = { TIFO: 0, GENERAL: 0 };
        for (const topic of topics) {
            if (counts[topic.section] !== undefined) {
                counts[topic.section] += 1;
            }
        }
        return counts;
    }

    /**
     * "by Velja" with a note when Velja never set a name.
     *
     * <p>A manager who has not chosen a name is shown his login address, because that is his username —
     * but the thread says so, rather than presenting an email as a name.
     */
    function authorLineHtml(row) {
        const name = escapeHtml(row.authorName || 'Manager');
        return row.authorHasChosenName === false
            ? `${name} <span class="fm-subtle">(no name set)</span>`
            : name;
    }

    function authorFlagHtml(post) {
        return post.authorHasChosenName === false
            ? '<span class="fm-subtle">(no name set)</span>'
            : '';
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

    // ── Interaction ─────────────────────────────────────────────────────────────────────────────────

    function bindForum(main) {
        main.querySelectorAll('.js-open-topic').forEach(button => {
            button.addEventListener('click', () => {
                const id = button.dataset.topicId;
                // Through loadPage, not straight to the view: the router is what pushes the navigation
                // history, and calling the view directly navigated without recording where from.
                // Back then popped whatever was open before the forum, which is how a section's Back
                // ended up on the messages screen.
                if (id && typeof window.loadPage === 'function') {
                    window.loadPage('forumTopic', { topicId: id });
                } else if (id) {
                    loadForumTopic(id);
                }
            });
        });
        main.querySelectorAll('.js-open-manager').forEach(button => {
            button.addEventListener('click', () => {
                const id = button.dataset.managerId;
                if (id && typeof window.openUserProfile === 'function') window.openUserProfile(id);
            });
        });
        main.querySelectorAll('.js-section').forEach(button => {
            button.addEventListener('click', () => {
                const section = button.dataset.section;
                if (typeof window.loadPage === 'function') {
                    window.loadPage('forumSection', { section });
                } else {
                    loadForumSection(section);
                }
            });
        });
        main.querySelectorAll('.js-new-topic').forEach(button => {
            button.addEventListener('click', () => showNewTopicForm(button.dataset.section));
        });
        main.querySelectorAll('.js-page').forEach(button => {
            button.addEventListener('click', () => {
                const page = Number(button.dataset.page);
                // The pager re-reads the section out of the DOM rather than being told, so there is one
                // place that decides which screen a page number belongs to.
                const section = main.querySelector('[data-section]')?.dataset.section;
                if (section) {
                    loadForumSection(section, page);
                } else {
                    loadForum(page);
                }
            });
        });
    }

    async function showNewTopicForm(section) {
        const main = document.getElementById('main-content');
        if (!main) return;
        const host = main.querySelector('.js-new-topic-slot') || appendNewTopicSlot(main);
        if (!host) return;
        host.innerHTML = newTopicFormHtml(section || 'TIFO');
        bindNewTopicForm(host);
        host.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
    }

    function appendNewTopicSlot(main) {
        let slot = main.querySelector('.js-new-topic-slot');
        if (!slot) {
            slot = document.createElement('section');
            slot.className = 'fm-panel js-new-topic-slot';
            main.querySelector('.fm-page').appendChild(slot);
        }
        return slot;
    }

    function bindNewTopicForm(host) {
        const form = host.querySelector('.js-new-topic-form');
        if (!form) return;
        form.addEventListener('submit', async (event) => {
            event.preventDefault();
            const status = form.querySelector('#forum-new-status');
            const title = form.querySelector('[name="title"]').value.trim();
            const body = form.querySelector('[name="body"]').value.trim();
            const chosen = form.querySelector('[name="section"]').value;

            if (!title || !body) {
                if (status) status.textContent = 'A topic needs a title and a message.';
                return;
            }

            const button = form.querySelector('button[type="submit"]');
            button.disabled = true;
            try {
                const response = await authFetch('/forum/topics', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ section: chosen, title, body })
                });
                const payload = await response.json().catch(() => ({}));
                if (!response.ok) {
                    // The server's message is the useful one here: a forum write ban explains itself.
                    if (status) status.textContent = payload.message || payload.error
                        || `Not posted (status ${response.status}).`;
                    return;
                }
                await loadForumTopic(payload.id);
            } catch (err) {
                if (status) status.textContent = `Could not post: ${err?.message || ''}`;
            } finally {
                button.disabled = false;
            }
        });
    }

    function bindTopic(main, topicId) {
        bindForum(main);

        const form = main.querySelector('.js-reply-form');
        if (form) {
            form.addEventListener('submit', async (event) => {
                event.preventDefault();
                const status = form.querySelector('#forum-reply-status');
                const textarea = form.querySelector('[name="body"]');
                const body = textarea.value.trim();
                if (!body) {
                    if (status) status.textContent = 'A reply cannot be empty.';
                    return;
                }

                const button = form.querySelector('button[type="submit"]');
                button.disabled = true;
                try {
                    const response = await authFetch(
                        `/forum/topics/${encodeURIComponent(topicId)}/posts`, {
                            method: 'POST',
                            headers: { 'Content-Type': 'application/json' },
                            body: JSON.stringify({ body })
                        });
                    const payload = await response.json().catch(() => ({}));
                    if (!response.ok) {
                        if (status) status.textContent = payload.message || payload.error
                            || `Not posted (status ${response.status}).`;
                        return;
                    }
                    // Reload rather than append: the page also carries the bumped post count and the new
                    // activity time, and appending only the post would leave both stale.
                    await loadForumTopic(topicId);
                } catch (err) {
                    if (status) status.textContent = `Could not post: ${err?.message || ''}`;
                } finally {
                    button.disabled = false;
                }
            });
        }

        main.querySelectorAll('.js-delete-post').forEach(button => {
            button.addEventListener('click', () => deletePost(button, topicId));
        });
        main.querySelectorAll('.js-edit-post').forEach(button => {
            button.addEventListener('click', () => startEdit(button, topicId));
        });
    }

    /**
     * Deletes a post, after saying what deleting means.
     *
     * <p>The prompt says the thread keeps its place rather than saying "are you sure", because a manager
     * who expects a forum delete to remove his message entirely has to be told here — it stays as
     * "deleted", and everybody still sees that it was.
     */
    async function deletePost(button, topicId) {
        const postId = button.dataset.postId;
        if (!postId) return;
        if (!window.confirm(
            'Delete this message?\n\n'
            + 'It stays in the thread as "deleted" so the replies under it still make sense. '
            + 'Other moderators can still see the original text.')) {
            return;
        }
        try {
            const response = await authFetch(`/forum/posts/${encodeURIComponent(postId)}`, { method: 'DELETE' });
            if (!response.ok) {
                const payload = await response.json().catch(() => ({}));
                window.alert(`Could not delete: ${payload.message || payload.error || response.status}`);
                return;
            }
            await loadForumTopic(topicId);
        } catch (err) {
            window.alert(`Error: ${err?.message || err}`);
        }
    }

    /** Turns a post into a textarea in place, so the reply you are reading does not scroll away. */
    async function startEdit(button, topicId) {
        const postId = button.dataset.postId;
        const article = button.closest('.forum-post');
        if (!postId || !article || article.querySelector('.js-edit-form')) return;

        const original = article.querySelector('.forum-post-body')?.textContent ?? '';
        const editor = document.createElement('div');
        editor.className = 'community-compose-form js-edit-form';
        editor.innerHTML = `
            <div class="community-compose-textarea">
                <textarea maxlength="${MAX_BODY}" rows="5">${escapeHtml(original)}</textarea>
            </div>
            <div class="community-compose-actions">
                <button type="button" class="fm-action-btn js-edit-save">Save</button>
                <button type="button" class="fm-action-btn secondary js-edit-cancel">Cancel</button>
            </div>
            <div class="community-compose-toolbar fm-subtle js-edit-status"></div>`;

        const body = article.querySelector('.forum-post-body');
        if (body) {
            body.replaceWith(editor);
        } else {
            article.appendChild(editor);
        }

        editor.querySelector('.js-edit-cancel').addEventListener('click', () => loadForumTopic(topicId));
        editor.querySelector('.js-edit-save').addEventListener('click', async () => {
            const wanted = editor.querySelector('textarea').value.trim();
            const status = editor.querySelector('.js-edit-status');
            if (!wanted) {
                if (status) status.textContent = 'A post cannot be emptied by editing it.';
                return;
            }
            try {
                const response = await authFetch(`/forum/posts/${encodeURIComponent(postId)}`, {
                    method: 'PUT',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ body: wanted })
                });
                if (!response.ok) {
                    const payload = await response.json().catch(() => ({}));
                    if (status) status.textContent = payload.message || payload.error
                        || `Not saved (status ${response.status}).`;
                    return;
                }
                await loadForumTopic(topicId);
            } catch (err) {
                if (status) status.textContent = `Could not save: ${err?.message || ''}`;
            }
        });
    }

    return { loadForum, loadForumSection, loadForumTopic };
}