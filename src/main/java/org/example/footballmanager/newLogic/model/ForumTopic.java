package org.example.footballmanager.newLogic.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.example.commonmanager.model.User;

import java.time.LocalDateTime;
import java.util.List;

/**
 * A thread in the forum: a title, a section, an opener, and a list of replies (owner, 2026-10-05).
 *
 * <p>An old-school threaded forum in the shape the owner asked for — hattrick and sokker.org — which means
 * **flat**: a topic is a title and a stack of messages, oldest first, no sub-threads. Nesting is the thing
 * modern forums added and the thing this one deliberately does not have, because a manager asking a
 * question in a transfer thread wants an answer in that thread, not three levels down.
 *
 * <h2>Denormalised counters</h2>
 *
 * <p>{@code postCount} and {@code lastActivityAt} are stored rather than computed. A topic list needs both
 * for every row, and computing them means a count per row on a page with up to fifty topics — the N+1
 * pattern P1-4 removed three times in this codebase. They are maintained by {@code ForumService} in the
 * same transaction as the post, and one test asserts they cannot drift.
 */
@Entity(name = "NlForumTopic")
@Table(name = "nl_forum_topic", indexes = {
        // The section listing, newest activity first. This is the forum's only real query and it had no
        // supporting index at all before this annotation.
        @Index(name = "idx_nl_forum_topic_section_activity",
                columnList = "section, last_activity_at"),
        // A manager's own topics. Small, but it is the "did anyone answer me" query and it is per user.
        @Index(name = "idx_nl_forum_topic_author", columnList = "author_user_id")
})
@Getter
@Setter
public class ForumTopic {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The topic title.
     *
     * <p>120 characters, enforced in the service rather than trusted to the column: a title truncated by
     * the database ends mid-word, and a forum topic list is the one place where a half-sentence is read
     * by everybody.
     */
    @Column(nullable = false, length = 120)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "section", nullable = false, length = 16)
    private ForumSection section;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_user_id", nullable = false)
    private User author;

    /**
     * How many posts this topic has, including the opening one.
     *
     * <p>Counts <b>live</b> posts. A deleted post stays a row — see {@link ForumPost} — so this is also
     * the number of positions in the thread, which is what a reader needs.
     */
    @Column(name = "post_count", nullable = false)
    private int postCount;

    /**
     * When the most recent post was written.
     *
     * <p>Not {@code updatedAt} on the topic, which would also move when the title is edited. A topic whose
     * title somebody corrected should not jump to the top of the section as though it had new activity.
     */
    @Column(name = "last_activity_at", nullable = false)
    private LocalDateTime lastActivityAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /**
     * The replies, oldest first.
     *
     * <p><b>Never read by the application, and never initialised.</b> {@code ForumService} asks
     * {@code ForumPostRepository} for a topic's posts, so this association exists to satisfy the entity
     * model rather than to carry data.
     *
     * <h2>Why there is deliberately no cascade and no orphan removal</h2>
     *
     * <p>This was first written as {@code cascade = ALL, orphanRemoval = true} over an eagerly
     * initialised {@code new ArrayList<>()}, on the reasonable-sounding grounds that "a topic with
     * orphaned posts is unreadable". Both were removed, and the reason is worth recording honestly:
     *
     * <p><b>I could not demonstrate that it caused harm, and the comment claiming it did was wrong.</b>
     * Restoring both left all 33 forum tests green, and a probe that deletes one post and then forces a
     * flush and re-counts found three rows either way. An eagerly initialised collection <i>is</i> a
     * loaded collection and <i>would</i> make Hibernate reconcile an empty in-memory list against the
     * rows it believes it owns — that is the documented hazard, and it is why the shape is wrong. But
     * "this is a known Hibernate footgun" is not "this deletes replies in this application", and the
     * second claim is the one a reader would have believed.
     *
     * <p>So the decision stands on what is provable: nothing deletes a topic anywhere in the application,
     * a cascade therefore buys nothing, and the association is unused, so the safest mapping is also the
     * simplest. If a topic ever needs deleting, it gets an explicit repository delete in a test.
     */
    @OneToMany(mappedBy = "topic")
    private List<ForumPost> posts;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (lastActivityAt == null) {
            lastActivityAt = createdAt;
        }
        if (postCount == 0) {
            postCount = 1;
        }
    }

    @PreUpdate
    void onUpdate() {
        if (lastActivityAt == null) {
            lastActivityAt = LocalDateTime.now();
        }
    }
}