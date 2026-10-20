package org.example.footballmanager.newLogic.repository;

import org.example.footballmanager.newLogic.model.ForumTopic;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Reads on {@code nl_forum_topic}.
 *
 * <p><b>Two repositories, not one.</b> A single interface extending {@code JpaRepository<ForumTopic>} that
 * also declared {@code findByTopicId...} would not compile the way it reads: Spring Data derives queries
 * against the repository's own domain type, so a {@code ForumPost} return type on a
 * {@code ForumTopic} repository either fails at startup or is quietly wrong. {@code ForumPostRepository}
 * is separate for that reason, not for tidiness.
 */
@Repository
public interface ForumTopicRepository extends JpaRepository<ForumTopic, Long> {

    /**
     * One page of topics in a section, most recently active first.
     *
     * <p>Ordered by activity rather than creation on purpose: a topic nobody replied to drops back, and
     * one somebody answered three times comes forward. Ordering by date buries the conversations that are
     * actually happening.
     */
    List<ForumTopic> findBySectionOrderByLastActivityAtDescIdDesc(
            org.example.footballmanager.newLogic.model.ForumSection section, Pageable pageable);

    /** The newest topics across both sections, for a "latest activity" list. */
    List<ForumTopic> findAllByOrderByLastActivityAtDescIdDesc(Pageable pageable);

    /**
     * A manager's own topics, for his profile.
     *
     * <p>{@code Author_Id} and not {@code AuthorUserId}: the field is a {@code User} called
     * {@code author}, so Spring Data parses {@code AuthorUserId} as "the author's property called
     * userId", which does not exist. That mistake is a context-load failure for every test in the
     * application, because a broken derived query fails at bean creation rather than at first call.
     */
    List<ForumTopic> findTop5ByAuthor_IdOrderByCreatedAtDesc(Long authorUserId);

    long countByAuthor_Id(Long authorUserId);
}