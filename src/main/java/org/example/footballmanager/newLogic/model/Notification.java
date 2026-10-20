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
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.example.commonmanager.model.User;

/**
 * One thing that happened that the manager should know about.
 *
 * <p><b>Nothing of this shape existed.</b> The dashboard ticker was a projection recomputed from eight
 * endpoints on every render, and the only persisted read-tracking was {@code User.communityLastViewedAt}
 * — one timestamp per account, so "which of my four unread messages did I see" was not a question the
 * database could answer.
 *
 * <h2>Why a row per notification rather than a count on the user</h2>
 *
 * <p>A count answers "how many" and nothing else. A manager cannot clear one, cannot see what it was,
 * and cannot tell a forum reply from a moderator's decision. The row is slightly more to write and the
 * only thing that can carry the text the manager needs to read.
 *
 * <p>Deliberately <b>not</b> deleted on read. Marking {@code readAt} keeps "what happened to me" after
 * the badge clears, and a notification nobody can see the history of is a half-built feature. There is no
 * purge yet — see the exit criteria.
 */
@Entity(name = "NlNotification")
@Table(name = "nl_notification", indexes = {
        // Every read is "this account's unread count" or "this account's newest page". Leading with
        // recipient_id is what makes both of them a range scan; without it they are a full table scan
        // that grows with every manager who ever posts a forum reply.
        @Index(name = "idx_nl_notification_recipient_created", columnList = "recipient_id, created_at"),
        // The unread query filters on this, so it is the other half of the same index. Without it
        // Postgres uses the one above and filters read_at afterwards, which reads every row this
        // account has ever been sent.
        @Index(name = "idx_nl_notification_recipient_unread", columnList = "recipient_id, read_at")
})
@Getter
@Setter
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Who is told. Not nullable: a notification nobody receives is a log line, not a notification. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recipient_id", nullable = false)
    private User recipient;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private NotificationKind kind;

    /**
     * The one-line summary the badge and ticker show.
     *
     * <p>Written at creation rather than assembled at read time, so a notification says what happened
     * <i>then</i>. A title assembled from current state changes under you: a topic renamed after you were
     * replied to would show the new name for an event about the old one.
     */
    @Column(name = "summary", nullable = false, length = 300)
    private String summary;

    /** Where a click goes — a topic id, a thread id. Null for kinds with no destination. */
    @Column(name = "target_page", length = 40)
    private String targetPage;

    @Column(name = "target_id")
    private Long targetId;

    /**
     * When the manager read it, or null while unread.
     *
     * <p>A null {@code read_at} <i>is</i> the unread state. There is no separate boolean, because the two
     * would be able to disagree and a badge that says 3 while every row says read is the failure nobody
     * notices until it ships.
     */
    @Column(name = "read_at")
    private java.time.LocalDateTime readAt;

    @Column(name = "created_at", nullable = false)
    private java.time.LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = java.time.LocalDateTime.now();
        }
    }

    public boolean isUnread() {
        return readAt == null;
    }
}