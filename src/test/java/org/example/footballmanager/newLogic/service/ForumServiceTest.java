package org.example.footballmanager.newLogic.service;

import org.example.commonmanager.model.User;
import org.example.commonmanager.model.UserRole;
import org.example.commonmanager.repository.UserRepository;
import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.model.ForumPost;
import org.example.footballmanager.newLogic.model.ForumSection;
import org.example.footballmanager.newLogic.model.ForumTopic;
import org.example.footballmanager.newLogic.model.Notification;
import org.example.footballmanager.newLogic.model.NotificationKind;
import org.example.footballmanager.newLogic.repository.ForumPostRepository;
import org.example.footballmanager.newLogic.repository.ForumTopicRepository;
import org.example.footballmanager.newLogic.repository.NotificationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The forum: writing, editing, deleting, and the ban.
 *
 * <p>The owner's specification, and each rule here is one clause of it:
 *
 * <ul>
 *   <li>two fixed sections, TIFO and non-TIFO</li>
 *   <li>any manager opens a topic and replies in one</li>
 *   <li>you may edit and delete <b>your own</b> message, and an edit shows a tag</li>
 *   <li>MOD/ADMIN/OWNER may edit and delete <b>anybody's</b></li>
 *   <li>a ban stops writing, and reading and the rest of the game keep working</li>
 * </ul>
 *
 * <p>Assertions read the database rather than a returned object, because the failure this guards against
 * is a post that was counted and never written.
 */
class ForumServiceTest extends BaseTest {

    @Autowired
    ForumService forum;

    @Autowired
    ForumTopicRepository topics;

    @Autowired
    ForumPostRepository posts;

    @Autowired
    NotificationRepository notifications;

    @Autowired
    UserRepository users;

    @Autowired
    ModerationService moderation;

    @Autowired
    jakarta.persistence.EntityManager entityManager;

    // ── Opening and replying ────────────────────────────────────────────────────────────────────────

    @Test
    @Transactional
    @DisplayName("a topic opens with its first post, and never with none")
    void aTopicOpensWithItsFirstPost() {
        User me = aUser(UserRole.REGULAR);

        ForumTopic topic = forum.createTopic(me, ForumSection.TIFO, "Chants for the derby", "We sing the Omladinac chant at minute 70.");

        assertEquals(1, posts.countByTopicId(topic.getId()),
                "a topic exists with no posts, which is a title in a list that goes nowhere");
        assertEquals(1, topic.getPostCount());
        assertEquals(ForumSection.TIFO, topics.findById(topic.getId()).orElseThrow().getSection());
    }

    @Test
    @Transactional
    @DisplayName("a missing section defaults to non-TIFO rather than refusing")
    void aMissingSectionDefaultsToGeneral() {
        User me = aUser(UserRole.REGULAR);

        ForumTopic topic = forum.createTopic(me, null, "Transfer window thoughts", "Who is buying?");

        assertEquals(ForumSection.GENERAL, topics.findById(topic.getId()).orElseThrow().getSection());
    }

    @Test
    @Transactional
    @DisplayName("a blank title or body is refused rather than creating an empty topic")
    void aBlankTopicIsRefused() {
        User me = aUser(UserRole.REGULAR);

        assertThrows(IllegalArgumentException.class, () -> forum.createTopic(me, ForumSection.TIFO, "   ", "Body"));
        assertThrows(IllegalArgumentException.class, () -> forum.createTopic(me, ForumSection.TIFO, "Title", "  "));
        assertEquals(0, topics.count(), "a refused topic was written anyway");
    }

    @Test
    @Transactional
    @DisplayName("an over-long title is refused, not truncated mid-word by the column")
    void anOverlongTitleIsRefused() {
        User me = aUser(UserRole.REGULAR);

        assertThrows(IllegalArgumentException.class,
                () -> forum.createTopic(me, ForumSection.GENERAL, "x".repeat(121), "Body"));
        assertEquals(0, topics.count());
    }

    @Test
    @Transactional
    @DisplayName("a reply bumps the topic's activity and its count")
    void aReplyUpdatesTheTopic() {
        User me = aUser(UserRole.REGULAR);
        ForumTopic topic = forum.createTopic(me, ForumSection.GENERAL, "Lineup question", "Who plays left back?");

        forum.createPost(aUser(UserRole.REGULAR), topic.getId(), "The young one from the academy.");

        ForumTopic reread = topics.findById(topic.getId()).orElseThrow();
        assertEquals(2, reread.getPostCount());
        assertEquals(2, posts.countByTopicId(topic.getId()));
    }

    @Test
    @Transactional
    @DisplayName("replying to a topic that does not exist is a 404, not an orphan post")
    void replyingNowhereIsANotFound() {
        User me = aUser(UserRole.REGULAR);

        assertThrows(ResponseStatusException.class, () -> forum.createPost(me, 999_999_999L, "Hello?"));
    }

    @Test
    @Transactional
    @DisplayName("a reply brings the topic back to the top of its section")
    void aReplyMovesTheTopicUp() {
        User me = aUser(UserRole.REGULAR);
        ForumSection section = ForumSection.TIFO;

        ForumTopic quiet = forum.createTopic(me, section, "An old question", "Any answer?");
        ForumTopic loud = forum.createTopic(me, section, "A newer question", "Also unanswered.");
        // Make "quiet" older than "loud" so ordering cannot pass by accident.
        quiet.setLastActivityAt(java.time.LocalDateTime.now().minusDays(3));
        topics.save(quiet);

        Map<String, Object> before = forum.listTopics(section, 0, 10);
        assertEquals(loud.getId(), firstTopicId(before), "the newer topic should lead");

        forum.createPost(aUser(UserRole.REGULAR), quiet.getId(), "Answering my own question.");

        Map<String, Object> after = forum.listTopics(section, 0, 10);
        assertEquals(quiet.getId(), firstTopicId(after),
                "a topic with new activity did not come forward");
    }

    // ── Editing ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    @Transactional
    @DisplayName("editing your own post sets the edited tag")
    void editingYourOwnPost() {
        User me = aUser(UserRole.REGULAR);
        ForumPost post = firstPostOf(forum.createTopic(me, ForumSection.GENERAL, "Title", "First version."));

        forum.editPost(me, post.getId(), "Second version.");

        ForumPost reread = posts.findById(post.getId()).orElseThrow();
        assertEquals("Second version.", reread.getBody());
        assertNotNull(reread.getEditedAt(), "the edit left no trace, and the owner asked for a visible tag");
    }

    @Test
    @Transactional
    @DisplayName("a post written once carries no edited tag")
    void anUneditedPostHasNoTag() {
        User me = aUser(UserRole.REGULAR);
        ForumPost post = firstPostOf(forum.createTopic(me, ForumSection.GENERAL, "Title", "Only version."));

        assertFalse(posts.findById(post.getId()).orElseThrow().isEdited(),
                "every post in the forum would show an 'edited' tag");
    }

    @Test
    @Transactional
    @DisplayName("you cannot edit somebody else's post")
    void youCannotEditAnotherPost() {
        User mine = aUser(UserRole.REGULAR);
        ForumPost post = firstPostOf(forum.createTopic(mine, ForumSection.GENERAL, "Title", "Not yours."));

        ResponseStatusException thrown = assertThrows(ResponseStatusException.class,
                () -> forum.editPost(aUser(UserRole.REGULAR), post.getId(), "Hijacked"));

        assertEquals(org.springframework.http.HttpStatus.FORBIDDEN, thrown.getStatusCode());
        assertEquals("Not yours.", posts.findById(post.getId()).orElseThrow().getBody());
    }

    @Test
    @Transactional
    @DisplayName("a moderator can edit anybody's post, and the UI is told it was a moderator's edit")
    void aModeratorCanEditAnybody() {
        User mine = aUser(UserRole.REGULAR);
        User mod = aUser(UserRole.MOD);
        ForumPost post = firstPostOf(forum.createTopic(mine, ForumSection.GENERAL, "Title", "Original."));

        forum.editPost(mod, post.getId(), "Corrected by a moderator.");

        assertTrue(posts.findById(post.getId()).orElseThrow().isEdited());

        // The author reading the thread must be able to tell it was not his own change — and must
        // still be able to edit it, because a moderator correcting a typo does not transfer ownership
        // of the post. The first version of this test asserted canEdit was false, on the reasoning that
        // a moderated post should be frozen; that is a different moderation model and not this one.
        Map<String, Object> asAuthor = firstPostViewedBy(post, mine);
        assertEquals(Boolean.TRUE, asAuthor.get("editedByModerator"),
                "a manager whose words were changed has no way of knowing it was a moderator");
        assertEquals(Boolean.TRUE, asAuthor.get("canEdit"),
                "a moderated post became uneditable by its author");
        assertEquals(mod.getId(), asAuthor.get("editedByUserId"),
                "the post does not record who edited it");
    }

    @Test
    @Transactional
    @DisplayName("editing a post to nothing is refused rather than emptying the thread")
    void editingToNothingIsRefused() {
        User me = aUser(UserRole.REGULAR);
        ForumPost post = firstPostOf(forum.createTopic(me, ForumSection.GENERAL, "Title", "Something."));

        assertThrows(IllegalArgumentException.class, () -> forum.editPost(me, post.getId(), "   "));
        assertEquals("Something.", posts.findById(post.getId()).orElseThrow().getBody());
    }

    // ── Deleting ────────────────────────────────────────────────────────────────────────────────────

    @Test
    @Transactional
    @DisplayName("deleting your own post hides the body and keeps the row")
    void deletingYourOwnPostIsSoft() {
        User me = aUser(UserRole.REGULAR);
        ForumPost post = firstPostOf(forum.createTopic(me, ForumSection.GENERAL, "Title", "Regrettable."));

        forum.deletePost(me, post.getId());

        ForumPost reread = posts.findById(post.getId()).orElseThrow();
        assertTrue(reread.isDeleted());
        assertNull(reread.displayBody(), "the deleted post still reports its body");
        // Counted against the TOPIC. This said post.getId() for a while and answered 0, which reads
        // exactly like "the row was hard-deleted" — the assertion was right about the risk it guards
        // and wrong about the query it used, and a test that cries wolf gets ignored rather than fixed.
        assertEquals(1, posts.countByTopicId(post.getTopic().getId()),
                "the row was hard-deleted, so every reply underneath now points at a gap");
        assertEquals(1, posts.count(), "the post row is gone entirely");
    }

    /**
     * A soft delete must not take the thread's other posts with it.
     *
     * <p><b>The explicit flush was added because counting in the same transaction cannot see commit-time
     * damage.</b> The original version counted the posts before the flush, so anything Hibernate does
     * when it reconciles collections was invisible to it. That is a real weakness in the test and it was
     * fixed regardless of what it found — which was nothing: restoring {@code orphanRemoval = true} on
     * the topic's post list leaves this test green too.
     *
     * <p>The flush is therefore explicit, and the count is re-read through the repository afterwards
     * rather than from anything still in memory.
     */
    @Test
    @Transactional
    @DisplayName("the replies under a deleted post survive the flush, which is where the damage happened")
    void repliesSurviveADeletedPost() {
        User me = aUser(UserRole.REGULAR);
        ForumTopic topic = forum.createTopic(me, ForumSection.GENERAL, "Title", "Opening.");
        ForumPost reply = forum.createPost(me, topic.getId(), "Answering.");
        ForumPost second = forum.createPost(me, topic.getId(), "And another.");

        forum.deletePost(me, firstPostOf(topic).getId());

        // Without this the delete and the count happen before Hibernate reconciles collections, so
        // commit-time damage is invisible to the assertion.
        entityManager.flush();
        entityManager.clear();

        assertEquals(3, posts.countByTopicId(topic.getId()),
                "deleting one post removed the others — the topic's post collection is deleting them");
        assertTrue(posts.findById(reply.getId()).isPresent(), "a reply was deleted with its neighbour");
        assertTrue(posts.findById(second.getId()).isPresent(), "a reply was deleted with its neighbour");
    }

    @Test
    @Transactional
    @DisplayName("a moderator can delete anybody's post")
    void aModeratorCanDeleteAnybody() {
        User mine = aUser(UserRole.REGULAR);
        ForumPost post = firstPostOf(forum.createTopic(mine, ForumSection.GENERAL, "Title", "Rude."));

        forum.deletePost(aUser(UserRole.MOD), post.getId());

        assertTrue(posts.findById(post.getId()).orElseThrow().isDeleted());
    }

    /**
     * A moderator may delete the OWNER's post, and must.
     *
     * <p>The owner's instruction is that MOD, ADMIN and OWNER may delete and edit anybody's messages, with
     * no carve-out. A first version added a "a moderator may not touch another moderator's post" rule,
     * reasoned from {@link ModerationService} where a MOD cannot ban a colleague — but a ban is a
     * punishment and a post delete is a reversible, visibly-marked correction. Running it against the real
     * application is what caught it: a MOD was refused when editing the owner's own post.
     *
     * <p>So this asserts the rule the owner actually gave, against the hardest case: the most senior
     * account in the system.
     */
    @Test
    @Transactional
    @DisplayName("a moderator may delete the owner's post")
    void aModeratorMayDeleteTheOwnersPost() {
        User owner = aUser(UserRole.OWNER);
        ForumPost post = firstPostOf(forum.createTopic(owner, ForumSection.GENERAL, "Title", "By the owner."));

        forum.deletePost(aUser(UserRole.MOD), post.getId());

        assertTrue(posts.findById(post.getId()).orElseThrow().isDeleted(),
                "a MOD was refused when deleting the owner's post, which is the owner's own instruction");
    }

    @Test
    @Transactional
    @DisplayName("deleting twice does not move the timestamp")
    void deletingTwiceIsIdempotent() {
        User me = aUser(UserRole.REGULAR);
        ForumPost post = firstPostOf(forum.createTopic(me, ForumSection.GENERAL, "Title", "Body."));

        forum.deletePost(me, post.getId());
        var first = posts.findById(post.getId()).orElseThrow().getDeletedAt();
        forum.deletePost(me, post.getId());

        assertEquals(first, posts.findById(post.getId()).orElseThrow().getDeletedAt());
    }

    @Test
    @Transactional
    @DisplayName("a deleted post cannot be edited back to life")
    void aDeletedPostCannotBeUndeletedByEditing() {
        User me = aUser(UserRole.REGULAR);
        ForumPost post = firstPostOf(forum.createTopic(me, ForumSection.GENERAL, "Title", "Body."));
        forum.deletePost(me, post.getId());

        Map<String, Object> asAuthor = firstPostViewedBy(post, me);
        assertEquals(Boolean.FALSE, asAuthor.get("canEdit"),
                "the UI offers to edit a deleted post");
    }

    // ── The ban ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    @Transactional
    @DisplayName("a banned manager cannot open a topic")
    void aBannedManagerCannotOpenATopic() {
        User mod = aUser(UserRole.MOD);
        User banned = aUser(UserRole.REGULAR);
        moderation.banFromForum(mod, banned.getId(), 7, "Spam");

        ResponseStatusException thrown = assertThrows(ResponseStatusException.class,
                () -> forum.createTopic(banned, ForumSection.GENERAL, "Hello", "Is this allowed?"));

        assertEquals(org.springframework.http.HttpStatus.FORBIDDEN, thrown.getStatusCode());
        assertTrue(thrown.getMessage().contains("Spam"),
                "the refusal does not say why, so the manager cannot argue with it: " + thrown.getMessage());
    }

    @Test
    @Transactional
    @DisplayName("a banned manager cannot reply")
    void aBannedManagerCannotReply() {
        User owner = aUser(UserRole.REGULAR);
        ForumTopic topic = forum.createTopic(owner, ForumSection.GENERAL, "Title", "Open question?");
        User mod = aUser(UserRole.MOD);
        User banned = aUser(UserRole.REGULAR);
        moderation.banFromForum(mod, banned.getId(), 3, "Spam");

        assertThrows(ResponseStatusException.class,
                () -> forum.createPost(banned, topic.getId(), "One more thing"));
        assertEquals(1, posts.countByTopicId(topic.getId()), "a banned manager's reply was written");
    }

    @Test
    @Transactional
    @DisplayName("a banned manager can still READ the forum")
    void aBannedManagerCanStillRead() {
        // The owner's instruction was about writing. Silencing somebody from the discussion is not the
        // same as silencing them from knowing what was said, and a ban that removed reading would be a
        // different and much larger decision.
        User owner = aUser(UserRole.REGULAR);
        ForumTopic topic = forum.createTopic(owner, ForumSection.TIFO, "Chants", "The Omladinac chant.");
        User mod = aUser(UserRole.MOD);
        User banned = aUser(UserRole.REGULAR);
        moderation.banFromForum(mod, banned.getId(), 7, "Spam");

        Map<String, Object> view = forum.viewTopic(banned, topic.getId(), 0, 30);

        assertNotNull(view.get("topic"));
        assertEquals(1, ((List<?>) view.get("posts")).size(),
                "a forum write ban also removed the manager's ability to read");
    }

    @Test
    @Transactional
    @DisplayName("an expired ban stops blocking writes")
    void anExpiredBanStopsBlocking() {
        User mod = aUser(UserRole.MOD);
        User banned = aUser(UserRole.REGULAR);
        moderation.banFromForum(mod, banned.getId(), 7, "Spam");
        User row = users.findById(banned.getId()).orElseThrow();
        row.setForumBanUntil(java.time.LocalDateTime.now().minusMinutes(1));
        users.save(row);

        // Must not throw: this is the assertion that a ban is a bounded punishment rather than a
        // permanent one nobody can undo.
        ForumTopic topic = forum.createTopic(users.findById(banned.getId()).orElseThrow(),
                ForumSection.GENERAL, "Back again", "Sorry about that.");
        assertNotNull(topic.getId());
    }

    // ── Notifications ───────────────────────────────────────────────────────────────────────────────

    @Test
    @Transactional
    @DisplayName("a reply notifies the topic's author")
    void aReplyNotifiesTheAuthor() {
        User owner = aUser(UserRole.REGULAR);
        ForumTopic topic = forum.createTopic(owner, ForumSection.GENERAL, "Question", "Anyone?");

        forum.createPost(aUser(UserRole.REGULAR), topic.getId(), "Yes.");

        assertEquals(1, unreadNotificationsFor(owner),
                "the person who asked the question was not told somebody answered it");
    }

    @Test
    @Transactional
    @DisplayName("replying to your own topic notifies nobody")
    void replyingToYourselfNotifiesNobody() {
        // How a forum is used: you answer your own question. Notifying yourself is how a badge becomes
        // noise, and a badge nobody reads is a badge that hides the one notification that mattered.
        User me = aUser(UserRole.REGULAR);
        ForumTopic topic = forum.createTopic(me, ForumSection.GENERAL, "Question", "Answering myself.");

        forum.createPost(me, topic.getId(), "Found it.");

        assertEquals(0, unreadNotificationsFor(me));
    }

    @Test
    @Transactional
    @DisplayName("a reply notifies another participant, not only the topic's opener")
    void aReplyNotifiesOtherParticipants() {
        // In a long thread the person who needs to know is the one who replied three messages ago.
        User opener = aUser(UserRole.REGULAR);
        User helper = aUser(UserRole.REGULAR);
        ForumTopic topic = forum.createTopic(opener, ForumSection.GENERAL, "Question", "Anyone?");
        forum.createPost(helper, topic.getId(), "I had this last week.");

        forum.createPost(aUser(UserRole.REGULAR), topic.getId(), "Same problem.");

        assertEquals(1, unreadNotificationsFor(helper),
                "only the topic's opener was told, which is why long threads go quiet");
    }

    @Test
    @Transactional
    @DisplayName("a notification points at the topic it is about")
    void aNotificationPointsAtItsTopic() {
        User owner = aUser(UserRole.REGULAR);
        ForumTopic topic = forum.createTopic(owner, ForumSection.GENERAL, "Question", "Anyone?");

        forum.createPost(aUser(UserRole.REGULAR), topic.getId(), "Yes.");

        Notification notification = notifications
                .findByRecipientIdOrderByCreatedAtDescIdDesc(owner.getId(),
                        org.springframework.data.domain.PageRequest.of(0, 1)).get(0);
        assertEquals(NotificationKind.FORUM_REPLY, notification.getKind());
        assertEquals("forumTopic", notification.getTargetPage());
        assertEquals(topic.getId(), notification.getTargetId());
    }

    // ── The counters cannot drift ───────────────────────────────────────────────────────────────────

    @Test
    @Transactional
    @DisplayName("the stored post count matches the table, which is the whole point of storing it")
    void theStoredCountMatchesTheTable() {
        User me = aUser(UserRole.REGULAR);
        ForumTopic topic = forum.createTopic(me, ForumSection.GENERAL, "Title", "Opening.");
        forum.createPost(me, topic.getId(), "Second.");
        forum.createPost(me, topic.getId(), "Third.");
        forum.deletePost(me, firstPostOf(topic).getId());

        ForumTopic reread = topics.findById(topic.getId()).orElseThrow();
        assertEquals((long) reread.getPostCount(), posts.countByTopicId(topic.getId()),
                "the stored count and the table disagree — the topic list would show the wrong number");

        Map<String, Object> summary = (Map<String, Object>) ((List<?>) forum
                .listTopics(ForumSection.GENERAL, 0, 10).get("topics")).get(0);
        assertEquals(summary.get("postCount"), summary.get("actualPostCount"));
    }

    @Test
    @Transactional
    @DisplayName("a deleted post is not counted as live, and the topic's count still does not drop")
    void aDeletedPostIsCountedAsAPositionNotAsLiveContent() {
        User me = aUser(UserRole.REGULAR);
        ForumTopic topic = forum.createTopic(me, ForumSection.GENERAL, "Title", "Opening.");
        forum.createPost(me, topic.getId(), "Second.");
        forum.deletePost(me, firstPostOf(topic).getId());

        Map<String, Object> summary = (Map<String, Object>) ((List<?>) forum
                .listTopics(ForumSection.GENERAL, 0, 10).get("topics")).get(0);

        assertEquals(2L, summary.get("postCount"),
                "the topic's count dropped, so it no longer matches the positions a reader sees");
        assertEquals(1L, summary.get("livePostCount"));
    }

    // ── Reading ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    @Transactional
    @DisplayName("a topic's posts come back oldest first, and the order is stable")
    void postsComeBackOldestFirst() {
        User me = aUser(UserRole.REGULAR);
        ForumTopic topic = forum.createTopic(me, ForumSection.GENERAL, "Title", "One");
        forum.createPost(me, topic.getId(), "Two");
        forum.createPost(me, topic.getId(), "Three");

        List<?> rows = (List<?>) forum.viewTopic(me, topic.getId(), 0, 30).get("posts");

        assertEquals("One", ((Map<?, ?>) rows.get(0)).get("body"));
        assertEquals("Two", ((Map<?, ?>) rows.get(1)).get("body"));
        assertEquals("Three", ((Map<?, ?>) rows.get(2)).get("body"));
    }

    @Test
    @Transactional
    @DisplayName("paging a topic walks it without repeating or skipping")
    void topicPagingIsStable() {
        User me = aUser(UserRole.REGULAR);
        ForumTopic topic = forum.createTopic(me, ForumSection.GENERAL, "Title", "One");
        for (int i = 2; i <= 5; i++) {
            forum.createPost(me, topic.getId(), "Post " + i);
        }

        Map<String, Object> first = forum.viewTopic(me, topic.getId(), 0, 2);
        Map<String, Object> second = forum.viewTopic(me, topic.getId(), 1, 2);

        assertEquals(2, ((List<?>) first.get("posts")).size());
        assertEquals(Boolean.TRUE, first.get("hasMore"));
        assertEquals(2, ((List<?>) second.get("posts")).size());
        // The third page holds the fifth post and nothing more, so the second page DOES have more after
        // it. "hasMore" is about rows that exist, not about whether this page was full.
        assertEquals(Boolean.TRUE, second.get("hasMore"));
        Map<String, Object> third = forum.viewTopic(me, topic.getId(), 2, 2);
        assertEquals(1, ((List<?>) third.get("posts")).size());
        assertEquals(Boolean.FALSE, third.get("hasMore"));

        long firstIds = ((List<?>) first.get("posts")).stream()
                .map(row -> ((Map<?, ?>) row).get("id")).distinct().count();
        long secondIds = ((List<?>) second.get("posts")).stream()
                .map(row -> ((Map<?, ?>) row).get("id")).distinct().count();
        assertEquals(2, firstIds);
        assertEquals(2, secondIds);
    }

    @Test
    @Transactional
    @DisplayName("a hostile page size is clamped")
    void aHostilePageSizeIsClamped() {
        User me = aUser(UserRole.REGULAR);
        forum.createTopic(me, ForumSection.GENERAL, "Title", "Body");

        assertEquals(100, forum.listTopics(ForumSection.GENERAL, 0, 100_000).get("size"));
        assertEquals(0, forum.listTopics(ForumSection.GENERAL, -5, 30).get("page"));
    }

    @Test
    @Transactional
    @DisplayName("a deleted post's body never goes over the wire")
    void aDeletedBodyIsNeverSent() {
        User me = aUser(UserRole.REGULAR);
        ForumPost post = firstPostOf(forum.createTopic(me, ForumSection.GENERAL, "Title", "Something regrettable."));
        forum.deletePost(me, post.getId());

        Map<String, Object> asReader = firstPostViewedBy(post, aUser(UserRole.REGULAR));

        assertNull(asReader.get("body"), "the deleted body's text reached a reader");
        assertEquals(Boolean.TRUE, asReader.get("deleted"));
    }

    @Test
    @Transactional
    @DisplayName("the two sections are exactly TIFO and non-TIFO, and both are reachable")
    void bothSectionsAreReachable() {
        User me = aUser(UserRole.REGULAR);
        forum.createTopic(me, ForumSection.TIFO, "A chant", "Body");
        forum.createTopic(me, ForumSection.GENERAL, "A transfer", "Body");

        assertEquals(1, ((List<?>) forum.listTopics(ForumSection.TIFO, 0, 10).get("topics")).size());
        assertEquals(1, ((List<?>) forum.listTopics(ForumSection.GENERAL, 0, 10).get("topics")).size());
        assertEquals(2, ((List<?>) forum.listTopics(null, 0, 10).get("topics")).size(),
                "'all sections' does not return both");
        assertEquals("Non-TIFO", ForumSection.GENERAL.label(),
                "the second section is not labelled the way the owner asked for");
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────────────────────────

    private Long firstTopicId(Map<String, Object> page) {
        return (Long) ((Map<?, ?>) ((List<?>) page.get("topics")).get(0)).get("id");
    }

    private ForumPost firstPostOf(ForumTopic topic) {
        return posts.findByTopicIdOrderByIdAsc(topic.getId(),
                org.springframework.data.domain.PageRequest.of(0, 1)).get(0);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> firstPostViewedBy(ForumPost post, User viewer) {
        Map<String, Object> page = forum.viewTopic(viewer, post.getTopic().getId(), 0, 30);
        return (Map<String, Object>) ((List<?>) page.get("posts")).stream()
                .filter(row -> post.getId().equals(((Map<?, ?>) row).get("id")))
                .findFirst()
                .orElseThrow();
    }

    private long unreadNotificationsFor(User user) {
        return notifications.countByRecipientIdAndReadAtIsNull(user.getId());
    }

    private User aUser(UserRole role) {
        User user = new User();
        user.setUsername("forum-" + UUID.randomUUID() + "@test.local");
        user.setEmail(user.getUsername());
        user.setPassword("not-a-real-hash");
        user.setDisplayName("Forum tester");
        user.setRole(role);
        user.setPlusSubscription(false);
        return users.save(user);
    }
}