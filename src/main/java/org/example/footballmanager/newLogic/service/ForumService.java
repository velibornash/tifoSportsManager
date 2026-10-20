package org.example.footballmanager.newLogic.service;

import org.example.commonmanager.model.User;
import org.example.commonmanager.model.UserRole;
import org.example.commonmanager.model.UserRoles;
import org.example.footballmanager.newLogic.model.ForumPost;
import org.example.footballmanager.newLogic.model.ForumSection;
import org.example.footballmanager.newLogic.model.ForumTopic;
import org.example.footballmanager.newLogic.model.NotificationKind;
import org.example.footballmanager.newLogic.repository.ForumPostRepository;
import org.example.footballmanager.newLogic.repository.ForumTopicRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The forum (owner, 2026-10-05). Old-school and flat: a title, a section, and a stack of messages.
 *
 * <h2>Where the ban is enforced, and why it is not in the controller</h2>
 *
 * <p>{@link #assertMayPost} runs inside {@code createTopic} and {@code createPost}. A ban checked by the
 * controller would hold for exactly the endpoints the controller has, and the forum has three writers
 * (open a topic, reply, and — after Phase 5 — nothing else, but that is a coincidence of today). Enforced
 * in the service it holds for every caller, including a future one.
 *
 * <p>The check is {@link User#isForumBanned()} and nothing else. Reading is untouched: a banned manager
 * can still read the whole forum, because silencing somebody <i>from the discussion</i> is not the same as
 * silencing them <i>from knowing what was said</i>, and the owner's instruction was about writing.
 *
 * <h2>Who may do what</h2>
 *
 * <table>
 *   <tr><th></th><th>own post</th><th>anybody's post</th></tr>
 *   <tr><td>read</td><td>everyone</td><td>everyone</td></tr>
 *   <tr><td>edit</td><td>the author</td><td>MOD / ADMIN / OWNER / DEV</td></tr>
 *   <tr><td>delete</td><td>the author</td><td>MOD / ADMIN / OWNER / DEV</td></tr>
 * </table>
 *
 * <p>A moderator editing somebody else's post is the owner's instruction, and it is a strange power: it
 * lets a moderator put words in a manager's mouth. It is allowed because the owner asked for it, it is
 * <b>recorded in the post's {@code editedAt}</b> so the thread shows it happened, and there is no version
 * history — the next honest step, flagged on the board rather than built here.
 */
@Service
public class ForumService {

    private static final int MAX_TITLE = 120;
    private static final int MAX_BODY = 4000;
    private static final int DEFAULT_PAGE = 30;

    private final ForumTopicRepository topics;
    private final ForumPostRepository posts;
    private final NotificationService notifications;

    public ForumService(ForumTopicRepository topics, ForumPostRepository posts, NotificationService notifications) {
        this.topics = topics;
        this.posts = posts;
        this.notifications = notifications;
    }

    // ── Writing ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * Opens a topic with its first post, as one action.
     *
     * <p>One transaction and one call, so a topic can never exist with no posts — a topic with nothing in
     * it is a title in a list that goes nowhere.
     *
     * @throws org.springframework.web.server.ResponseStatusException 403 when the author is forum-banned
     */
    @Transactional
    public ForumTopic createTopic(User author, ForumSection section, String title, String body) {
        assertMayPost(author);

        String cleanTitle = requireText(title, MAX_TITLE, "A topic needs a title.");
        String cleanBody = requireText(body, MAX_BODY, "A topic needs a message.");
        ForumSection target = section == null ? ForumSection.GENERAL : section;

        ForumTopic topic = new ForumTopic();
        topic.setSection(target);
        topic.setTitle(cleanTitle);
        topic.setAuthor(author);
        topic.setPostCount(1);
        topics.save(topic);

        ForumPost first = new ForumPost();
        first.setTopic(topic);
        first.setAuthor(author);
        first.setBody(cleanBody);
        posts.save(first);

        return topic;
    }

    /**
     * Adds a reply, and tells the people it concerns.
     *
     * <p>The topic's {@code lastActivityAt} moves so the section listing reorders, and its
     * {@code postCount} grows so the list does not have to count.
     *
     * <p>Notifications go to the topic's author <b>unless the replier is them</b>: replying to your own
     * topic is how a forum is used, and notifying yourself about it is how a badge becomes noise.
     */
    @Transactional
    public ForumPost createPost(User author, Long topicId, String body) {
        assertMayPost(author);

        String cleanBody = requireText(body, MAX_BODY, "A reply cannot be empty.");

        ForumTopic topic = topics.findById(topicId)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "That topic does not exist."));

        ForumPost post = new ForumPost();
        post.setTopic(topic);
        post.setAuthor(author);
        post.setBody(cleanBody);
        posts.save(post);

        topic.setPostCount(topic.getPostCount() + 1);
        topic.setLastActivityAt(LocalDateTime.now());
        topics.save(topic);

        notifyInvolvedParties(topic, author);
        return post;
    }

    /**
     * Edits a post's text.
     *
     * <p>Sets {@code editedAt}, which is the tag the owner asked to be visible. A moderator editing another
     * manager's post goes through exactly this method — the authority check is
     * {@link #mayModify}, and there is deliberately no second path.
     */
    @Transactional
    public ForumPost editPost(User actor, Long postId, String newBody) {
        ForumPost post = requirePost(postId);
        requireMayModify(actor, post);

        String cleanBody = requireText(newBody, MAX_BODY, "A post cannot be emptied by editing it.");
        post.setBody(cleanBody);
        post.setEditedAt(LocalDateTime.now());
        // Recorded whenever the editor is not the author, which is the moderator case. Kept for the
        // author's own edits too, so the column always answers "who last touched this".
        post.setEditedByUserId(actor == null ? null : actor.getId());
        return posts.save(post);
    }

    /**
     * Deletes a post: soft, so the replies underneath survive.
     *
     * <p>The topic's {@code postCount} deliberately <b>does not drop</b>. A deleted post stays a row and
     * stays a position in the thread, so lowering the counter would make the count disagree with the
     * number of positions a reader sees.
     */
    @Transactional
    public ForumPost deletePost(User actor, Long postId) {
        ForumPost post = requirePost(postId);
        requireMayModify(actor, post);

        if (!post.isDeleted()) {
            post.setDeletedAt(LocalDateTime.now());
            posts.save(post);
        }
        return post;
    }

    /**
     * The ban check, in one place.
     *
     * <p>403 with a message the manager can act on, because "you are banned" without saying for how long
     * or why is the version that produces a support ticket.
     */
    private void assertMayPost(User actor) {
        if (actor == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.UNAUTHORIZED, "Sign in first.");
        }
        if (!actor.isForumBanned()) {
            return;
        }
        throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.FORBIDDEN,
                "You cannot post in the forum" + reasonSuffix(actor) + ". You can still read it and message other managers.");
    }

    private static String reasonSuffix(User user) {
        StringBuilder out = new StringBuilder();
        if (user.getForumBanUntil() != null) {
            long days = java.time.temporal.ChronoUnit.DAYS.between(
                    LocalDateTime.now(), user.getForumBanUntil());
            out.append(" for ").append(Math.max(1, days)).append(" more day(s)");
        }
        if (user.getForumBanReason() != null && !user.getForumBanReason().isBlank()) {
            out.append(". Reason: ").append(user.getForumBanReason().trim());
        }
        return out.toString();
    }

    /**
     * Whether this actor may edit or delete this post.
     *
     * <p>Author, or a moderator — with no exception for moderators' posts, because the owner's
     * instruction is explicit: MOD, ADMIN and OWNER may edit and delete <b>anybody's</b> messages.
     *
     * <p>The first version added a "a moderator may not touch another moderator's post" rule, reasoning
     * from {@link ModerationService} — where a MOD cannot ban a colleague, because a ban is a
     * <i>punishment</i>. It was wrong here, and running it caught it: a MOD was refused when editing the
     * owner's own post, which is exactly the case the owner asked for.
     *
     * <p>The distinction that matters is the action, not the rank. Editing or deleting a post is
     * reversible and visible — it leaves an "edited" tag or a "deleted" marker in a thread everybody can
     * read. A ban is not reversible by the banned manager and says nothing anywhere else. So the
     * moderator-on-moderator protection lives in {@code ModerationService} and not here.
     */
    private void requireMayModify(User actor, ForumPost post) {
        if (actor == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.UNAUTHORIZED, "Sign in first.");
        }
        boolean isAuthor = post.getAuthor() != null
                && java.util.Objects.equals(post.getAuthor().getId(), actor.getId());
        if (isAuthor) {
            return;
        }
        if (!UserRoles.mayModerate(actor)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "That is not your message.");
        }
    }

    // ── Reading ─────────────────────────────────────────────────────────────────────────────────────

    /** One page of a section's topics, with a live post count beside the stored one. */
    @Transactional(readOnly = true)
    public Map<String, Object> listTopics(ForumSection section, int page, int size) {
        int safeSize = clamp(size);
        int safePage = Math.max(0, page);

        List<ForumTopic> rows = section == null
                ? topics.findAllByOrderByLastActivityAtDescIdDesc(PageRequest.of(safePage, safeSize))
                : topics.findBySectionOrderByLastActivityAtDescIdDesc(section, PageRequest.of(safePage, safeSize));

        List<Map<String, Object>> rendered = rows.stream()
                .map(topic -> topicSummary(topic, null))
                .toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("section", section == null ? null : section.name());
        result.put("sectionLabel", section == null ? "All sections" : section.label());
        result.put("page", safePage);
        result.put("size", safeSize);
        result.put("hasMore", rows.size() == safeSize);
        result.put("topics", rendered);
        return result;
    }

    /** A whole topic with its posts, oldest first. */
    @Transactional(readOnly = true)
    public Map<String, Object> viewTopic(User viewer, Long topicId, int page, int size) {
        ForumTopic topic = topics.findById(topicId)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "That topic does not exist."));

        int safeSize = clamp(size);
        int safePage = Math.max(0, page);
        List<ForumPost> rows = posts.findByTopicIdOrderByIdAsc(topicId, PageRequest.of(safePage, safeSize));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("topic", topicSummary(topic, viewer));
        result.put("page", safePage);
        result.put("size", safeSize);
        result.put("hasMore", rows.size() == safeSize);
        result.put("posts", rows.stream().map(post -> postSummary(post, viewer)).toList());
        return result;
    }

    /**
     * The counters, for the manager's profile.
     *
     * <p>Reads the live count rather than the stored one: on a profile page, the truth matters more than
     * the speed, and this is not a hot path.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> forumStatsFor(User user) {
        Map<String, Object> stats = new LinkedHashMap<>();
        if (user == null || user.getId() == null) {
            stats.put("postCount", 0L);
            stats.put("topicCount", 0L);
            return stats;
        }
        stats.put("postCount", posts.countByAuthor_IdAndDeletedAtIsNull(user.getId()));
        stats.put("topicCount", topics.countByAuthor_Id(user.getId()));
        return stats;
    }

    // ── Rendering ───────────────────────────────────────────────────────────────────────────────────

    /**
     * A topic as the list shows it.
     *
     * <p>{@code livePostCount} is the stored counter and {@code actualPostCount} is counted from the table.
     * They are both sent on purpose: when they disagree, that is a bug in the denormalisation, and a
     * response that carries both is one a test can assert on instead of something found by reading the UI.
     */
    private Map<String, Object> topicSummary(ForumTopic topic, User viewer) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", topic.getId());
        row.put("title", topic.getTitle());
        row.put("section", topic.getSection() == null ? null : topic.getSection().name());
        row.put("sectionLabel", topic.getSection() == null ? null : topic.getSection().label());

        User author = topic.getAuthor();
        row.put("authorUserId", author == null ? null : author.getId());
        row.put("authorName", displayNameOf(author));
        row.put("authorHasChosenName", hasChosenName(author));

        row.put("postCount", (long) topic.getPostCount());
        row.put("actualPostCount", posts.countByTopicId(topic.getId()));
        row.put("livePostCount", posts.countByTopicIdAndDeletedAtIsNull(topic.getId()));
        row.put("createdAt", topic.getCreatedAt());
        row.put("lastActivityAt", topic.getLastActivityAt());
        return row;
    }

    private Map<String, Object> postSummary(ForumPost post, User viewer) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", post.getId());
        row.put("topicId", post.getTopic() == null ? null : post.getTopic().getId());

        User author = post.getAuthor();
        row.put("authorUserId", author == null ? null : author.getId());
        row.put("authorName", displayNameOf(author));
        row.put("authorHasChosenName", hasChosenName(author));

        // A deleted post sends null, not its old body. The body stays in the table for the moderator who
        // may need to look at it; it does not go over the wire to a reader.
        row.put("body", post.displayBody());
        row.put("deleted", post.isDeleted());
        row.put("edited", post.isEdited());
        row.put("editedAt", post.getEditedAt());
        row.put("editedByUserId", post.getEditedByUserId());
        row.put("createdAt", post.getCreatedAt());

        boolean own = viewer != null && author != null
                && java.util.Objects.equals(author.getId(), viewer.getId());
        boolean moderator = UserRoles.mayModerate(viewer);
        row.put("canEdit", (own || moderator) && !post.isDeleted());
        row.put("canDelete", (own || moderator) && !post.isDeleted());
        // A fact about the post, not about the reader. The first version derived it from the viewer,
        // which told the author he had edited his own words.
        row.put("editedByModerator", post.isEdited()
                && post.getEditedByUserId() != null
                && (author == null || !post.getEditedByUserId().equals(author.getId())));
        return row;
    }

    // ── Notifications ───────────────────────────────────────────────────────────────────────────────

    /**
     * Tells the topic's author, and everybody who has posted in it.
     *
     * <p>Every participant, not only the opener: in a long thread the person who needs to know is the one
     * who replied three messages ago, and notifying only the opener is why a thread goes quiet.
     *
     * <p>De-duplicated by author id, so a manager who replied and then is replied to again is told once
     * per reply — not once per earlier message of his own.
     */
    private void notifyInvolvedParties(ForumTopic topic, User replier) {
        // Nothing is pre-seeded here. The first version added the topic's author to this set before the
        // loop, intending to avoid a duplicate — and so skipped him entirely, because the loop's own
        // `add` then reported him as already-seen. The person who opened the thread is precisely the one
        // who must hear that somebody answered it.
        Set<Long> alreadyTold = new LinkedHashSet<>();

        for (ForumPost earlier : posts.findByTopicIdOrderByIdAsc(topic.getId(), PageRequest.of(0, 200))) {
            User participant = earlier.getAuthor();
            if (participant == null || participant.getId() == null) {
                continue;
            }
            if (participant.getId().equals(replier.getId()) || !alreadyTold.add(participant.getId())) {
                continue;
            }
            notifications.notify(participant, NotificationKind.FORUM_REPLY,
                    displayNameOf(replier) + " replied in \"" + topic.getTitle() + "\".",
                    "forumTopic", topic.getId());
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────────────────────────

    private ForumPost requirePost(Long postId) {
        if (postId == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "Which message?");
        }
        return posts.findById(postId)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "That message does not exist."));
    }

    private static String requireText(String value, int max, String message) {
        String clean = value == null ? "" : value.trim();
        if (clean.isEmpty()) {
            throw new IllegalArgumentException(message);
        }
        if (clean.length() > max) {
            throw new IllegalArgumentException(
                    "That is longer than " + max + " characters.");
        }
        return clean;
    }

    private static int clamp(int size) {
        if (size <= 0) {
            return DEFAULT_PAGE;
        }
        return Math.min(size, 100);
    }

    /**
     * What to call a manager.
     *
     * <p>Now the seventh copy of this fallback in the codebase. {@code User.displayName} documents two of
     * the others, and every one was written separately — which is why every self-registered manager shows
     * an email address beside their posts. It is not being consolidated here: a change that touched six
     * call sites across five features is a change that needs its own commit and its own tests.
     */
    private static String displayNameOf(User user) {
        if (user == null) {
            return "Someone";
        }
        if (user.getDisplayName() != null && !user.getDisplayName().isBlank()) {
            return user.getDisplayName().trim();
        }
        return user.getUsername();
    }

    private static boolean hasChosenName(User user) {
        return user != null
                && user.getDisplayName() != null
                && !user.getDisplayName().isBlank();
    }
}