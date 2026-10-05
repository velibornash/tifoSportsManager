package org.example.footballmanager.newLogic.repository;

import org.example.footballmanager.newLogic.model.ForumPost;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Reads on {@code nl_forum_post}.
 *
 * <p>Separate from {@link ForumTopicRepository} because a Spring Data repository derives its queries
 * against its own domain type — a {@code findByTopicId} returning {@code ForumPost} cannot live on a
 * {@code ForumTopic} repository.
 */
@Repository
public interface ForumPostRepository extends JpaRepository<ForumPost, Long> {

    /**
     * The posts in a topic, oldest first.
     *
     * <p>Ordered by id rather than {@code created_at}: two posts written in the same millisecond would
     * otherwise come back in an arbitrary order, and a thread that reorders between two page loads is the
     * forum's core feature visibly broken.
     */
    List<ForumPost> findByTopicIdOrderByIdAsc(Long topicId, Pageable pageable);

    /** Posts after one, for "load more" — the same page without the rows already on screen. */
    List<ForumPost> findByTopicIdAndIdGreaterThanOrderByIdAsc(Long topicId, Long afterId, Pageable pageable);

    /** How many posts a topic has, to check the denormalised counter against the truth. */
    long countByTopicId(Long topicId);

    long countByTopicIdAndDeletedAtIsNull(Long topicId);

    /** How many live posts a manager has written, for his profile. */
    long countByAuthor_IdAndDeletedAtIsNull(Long authorUserId);

    /** A manager's recent posts, for his profile. */
    List<ForumPost> findTop5ByAuthor_IdAndDeletedAtIsNullOrderByIdDesc(Long authorUserId);
}