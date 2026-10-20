package org.example.footballmanager.newLogic.repository;

import org.example.footballmanager.newLogic.model.MessageThread;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Reads on {@code nl_message_thread}.
 *
 * <p>Separate from {@code ForumTopicRepository} for the same reason as the forum pair: a Spring Data
 * repository derives its queries against its own domain type.
 */
@Repository
public interface MessageThreadRepository extends JpaRepository<MessageThread, Long> {

    /**
     * Every conversation this account is in, newest activity first.
     *
     * <p><b>Both sides, from two indexed queries, not one.</b> The single obvious version —
     * {@code findBySenderUserIdOrRecipientUserId} — is a {@code OR} across two columns and Postgres will
     * not use either index for it, so "open my messages" scans the whole conversations table. Two
     * queries, each an index range scan, merged in Java.
     */
    List<MessageThread> findByRecipientUserIdOrderByLastActivityAtDescIdDesc(Long userId, Pageable pageable);

    List<MessageThread> findBySenderUserIdOrderByLastActivityAtDescIdDesc(Long userId, Pageable pageable);

    /**
     * Whether a conversation between these two accounts already exists.
     *
     * <p>Used when the <b>first</b> message of a conversation is sent. A reply finds its thread by id and
     * never comes through here, so this is only about the first message — at which point there is
     * exactly one conversation between a pair, unless they have somehow opened two.
     */
    List<MessageThread> findBySenderUserIdAndRecipientUserId(Long senderUserId, Long recipientUserId);
}