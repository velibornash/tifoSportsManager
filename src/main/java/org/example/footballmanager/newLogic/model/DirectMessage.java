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
 * One message inside a conversation (owner, 2026-10-05).
 *
 * <p>No subject. The subject belongs to the {@link MessageThread}, because a reply cannot arrive with a
 * different one — that is what makes a conversation followable rather than a list of unrelated messages
 * that happen to share participants.
 *
 * <h2>Delete is soft, for the same reason as the forum</h2>
 *
 * <p>A removed message stays a row and its body is hidden, so the correspondence still reads as a
 * correspondence. A manager retracting something is different from a conversation that has a hole in it.
 */
@Entity(name = "NlDirectMessage")
@Table(name = "nl_direct_message", indexes = {
        // Reading a thread, oldest first. The forum's equivalent index is the same shape for the same
        // reason: one range scan per thread view rather than a sort over the whole table.
        @Index(name = "idx_nl_direct_message_thread", columnList = "thread_id, id")
})
@Getter
@Setter
public class DirectMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "thread_id", nullable = false)
    private MessageThread thread;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sender_user_id", nullable = false)
    private User sender;

    @Column(nullable = false, length = 4000)
    private String body;

    @Column(name = "edited_at")
    private LocalDateTime editedAt;

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

    public String displayBody() {
        return deletedAt == null ? body : null;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }
}