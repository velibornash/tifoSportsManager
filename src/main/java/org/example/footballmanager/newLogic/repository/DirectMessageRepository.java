package org.example.footballmanager.newLogic.repository;

import org.example.footballmanager.newLogic.model.DirectMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Reads on {@code nl_direct_message}.
 */
@Repository
public interface DirectMessageRepository extends JpaRepository<DirectMessage, Long> {

    /**
     * A thread's messages, oldest first.
     *
     * <p>Ordered by id rather than {@code created_at} for the forum's reason: two messages written in the
     * same millisecond must not come back in an arbitrary order, because a conversation that reorders
     * between two page loads is the feature visibly broken.
     */
    List<DirectMessage> findByThread_IdOrderByIdAsc(Long threadId, Pageable pageable);
}