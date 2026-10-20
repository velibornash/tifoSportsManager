package org.example.footballmanager.newLogic.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.example.commonmanager.model.User;

import java.time.LocalDateTime;

/**
 * One message inside a topic (owner, 2026-10-05).
 *
 * <h2>Delete is soft, and that is the decision worth stating</h2>
 *
 * <p>A deleted post's body is replaced with a placeholder and {@code deletedAt} is set. The row stays.
 *
 * <p>The owner asked for "anyone can delete their own message". Taken literally — a hard delete — that
 * breaks every reply underneath it: the thread has a gap where a message used to be, and a reader who
 * quoted it is quoting something that no longer exists. On a forum, "he deleted that" is information, and
 * a forum that erases it becomes a place where nobody can be held to what they wrote.
 *
 * <p>A moderator's delete is the same operation with a different authority, which is why both set
 * {@code deletedAt} rather than having two mechanisms.
 *
 * <h2>Edit keeps the original</h2>
 *
 * <p>{@code editedAt} is set and the UI shows an "edited" tag. The owner asked for that tag
 * specifically, so this column exists to produce it. The prior text is <b>not</b> kept: a version history
 * is a feature, and a half-built one that only stored the previous body and exposed no way to read it
 * would be worse than none.
 */
@Entity(name = "NlForumPost")
@Table(name = "nl_forum_post", indexes = {
        // Reading a topic is the forum's only hot path: one range scan per topic view.
        @Index(name = "idx_nl_forum_post_topic", columnList = "topic_id, id"),
        // A manager's own posts, for the profile.
        @Index(name = "idx_nl_forum_post_author", columnList = "author_user_id")
})
@Getter
@Setter
public class ForumPost {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "topic_id", nullable = false)
    private ForumTopic topic;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_user_id", nullable = false)
    private User author;

    /**
     * The message.
     *
     * <p>4000 characters. Long enough for a real argument about a formation, short enough that nobody
     * posts an essay into a thread — and enforced in the service, not here, so an over-long message is a
     * 400 with an explanation rather than a row truncated mid-sentence.
     */
    @Column(name = "body", nullable = false, length = 4000)
    private String body;

    /**
     * Set when the author edits. Null for a post written once and never touched, which is the common case
     * and should not render an "edited" tag on every post in the forum.
     */
    @Column(name = "edited_at")
    private LocalDateTime editedAt;

    /**
     * Who made the last edit, when it was not the author.
     *
     * <p>Exists because "edited by a moderator" has to be a fact about the post, not a fact about who is
     * looking. The first version computed it from the <i>viewer</i> — {@code isEdited && !own &&
     * mayModerate(viewer)} — which meant the author reading his own thread was told it was his own edit,
     * and a moderator reading it was told it was a moderator's. The tag has to be the same for everyone,
     * so the editor is stored.
     */
    @Column(name = "edited_by_user_id")
    private Long editedByUserId;

    /** Set on delete. Null while the post is live. */
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    /** What this post should display: the body, or the fact that it is gone. */
    public String displayBody() {
        return deletedAt == null ? body : null;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public boolean isEdited() {
        return editedAt != null;
    }
}