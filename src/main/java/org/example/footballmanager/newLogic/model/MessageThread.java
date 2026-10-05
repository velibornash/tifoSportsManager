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
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.example.commonmanager.model.User;

import java.time.LocalDateTime;

/**
 * One correspondence between two managers (owner, 2026-10-05).
 *
 * <p><b>A thread, not a message.</b> The owner's specification: "if you reply to a particular message, a
 * thread is created so the correspondence history can be followed." The first message creates the thread
 * with its subject; every reply appends to it and <b>has no subject of its own</b>. One thread per
 * conversation between the same pair, so there is no "which subject?" problem six messages later.
 *
 * <h2>Why the thread knows both participants</h2>
 *
 * <p>A thread could find its messages by joining on sender/recipient, which is the obvious design and
 * the wrong one: "show me my conversations" becomes a group-by over the whole message table, and a manager
 * with 400 messages has 400 rows scanned to find their threads. Storing the participant pair turns the
 * inbox query into a range scan on one index.
 */
@Entity(name = "NlMessageThread")
@Table(name = "nl_message_thread", indexes = {
        // The inbox: "threads where I am the recipient, newest first". Leading with the participant
        // column is what makes it a range scan instead of a scan of every conversation in the game.
        @Index(name = "idx_nl_message_thread_recipient", columnList = "recipient_user_id, last_activity_at"),
        // The same for sent. Both sides are indexed because both are asked for on every open of the
        // page, and "sent" is not derivable from a query over "received".
        @Index(name = "idx_nl_message_thread_sender", columnList = "sender_user_id, last_activity_at")
})
@Getter
@Setter
public class MessageThread {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The subject, set by whoever opened the conversation.
     *
     * <p>Carried by the thread rather than by each message so a reply cannot arrive with a different
     * subject and silently fork the conversation into two threads that look the same in a list.
     */
    @Column(nullable = false, length = 150)
    private String subject;

    /** Who started it. Equals {@link #recipientUser} on the opening message. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sender_user_id", nullable = false)
    private User senderUser;

    /**
     * The other party.
     *
     * <p>Named for the <b>thread</b>, not for the message: on a thread, the person who opened it is the
     * sender and the other one is the recipient, forever. A reply is still a reply, not a new thread.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recipient_user_id", nullable = false)
    private User recipientUser;

    @Column(name = "message_count", nullable = false)
    private int messageCount;

    @Column(name = "last_activity_at", nullable = false)
    private LocalDateTime lastActivityAt;

    /**
     * How far each side has read.
     *
     * <p>Two columns rather than one, because "I have read his reply" and "he has read mine" are
     * different facts. A single cursor would mark a message read for both the moment either party looked,
     * which is the bug that makes a read receipt useless.
     *
     * <p>Null means "has read everything so far" — a conversation with no unread messages has no cursor
     * to store, and treating null as "never read" would put a permanent unread badge on a thread nobody
     * has replied to yet.
     */
    @Column(name = "read_by_sender_at")
    private LocalDateTime readBySenderAt;

    @Column(name = "read_by_recipient_at")
    private LocalDateTime readByRecipientAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (lastActivityAt == null) {
            lastActivityAt = createdAt;
        }
        if (messageCount == 0) {
            messageCount = 1;
        }
    }

    @PreUpdate
    void onUpdate() {
        if (lastActivityAt == null) {
            lastActivityAt = LocalDateTime.now();
        }
    }

    /**
     * Whether this account has unread messages in this thread.
     *
     * <p>Compared against the thread's own activity time rather than per message: a thread carries one
     * "last activity" and one cursor per side, so a message counts as unread for somebody when it was
     * written after they last looked.
     */
    public boolean isUnreadFor(User viewer) {
        if (viewer == null || viewer.getId() == null) {
            return false;
        }
        boolean viewerIsSender = senderUser != null && senderUser.getId().equals(viewer.getId());
        LocalDateTime cursor = viewerIsSender ? readBySenderAt : readByRecipientAt;
        if (cursor == null) {
            // A null cursor means this side has never opened the thread, and there is a message in it, so
            // it is unread. The sender of a new conversation is not an exception: `send` writes
            // `readBySenderAt` as it sends, because a person has read what they just typed.
            //
            // The first version returned `messageCount > 1` here, on the reasoning that a brand new
            // thread should not badge the sender. That reasoning was right and the mechanism was
            // redundant — it suppressed the badge for the *recipient*, who genuinely had an unread
            // message, and `theInboxCountsUnreadThreads` caught it.
            return messageCount > 0;
        }
        return lastActivityAt != null && lastActivityAt.isAfter(cursor);
    }
}