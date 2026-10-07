package org.example.footballmanager.newLogic.repository;

import org.example.footballmanager.newLogic.model.DirectMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
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

    /**
     * The newest message in each of these threads, one query for the whole list (owner, 2026-10-07).
     *
     * <p>The owner's requirement for the message list: *"u listi poruka se samo vidi subject i poslednja
     * poruka"*. The preview was missing, and asking for it per thread would be a query per row on a
     * screen that shows thirty of them.
     *
     * <p>A {@code DISTINCT ON} over the thread id, which is the one-query form of "newest per group".
     * PostgreSQL-only, so {@link MessageService} falls back to reading without a preview when the
     * database is not PostgreSQL — a missing preview is a missing preview, not a failed screen.
     */
    @Query(value = """
            SELECT DISTINCT ON (m.thread_id) m.*
            FROM nl_direct_message m
            WHERE m.thread_id IN (:threadIds) AND m.deleted_at IS NULL
            ORDER BY m.thread_id, m.created_at DESC, m.id DESC
            """, nativeQuery = true)
    List<DirectMessage> findNewestPerThread(@Param("threadIds") List<Long> threadIds);
}