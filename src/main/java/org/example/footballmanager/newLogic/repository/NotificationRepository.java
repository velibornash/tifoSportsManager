package org.example.footballmanager.newLogic.repository;

import org.example.footballmanager.newLogic.model.Notification;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Reads on {@code nl_notification}.
 *
 * <p><b>Every method here takes a recipient id.</b> That is not tidiness — it is the whole authorization
 * story for this table. A method that could list notifications without naming who they are for would be a
 * method whose caller has to remember a filter, and the forum and messages features both call it.
 */
@Repository
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    /** The newest notifications for one account. Used by the dropdown and the page. */
    List<Notification> findByRecipientIdOrderByCreatedAtDescIdDesc(Long recipientId, Pageable pageable);

    /**
     * How many this account has not read.
     *
     * <p>An explicit count query rather than loading the rows and counting in Java, because the
     * 30-second poll runs this on every open session and the answer is all it needs.
     */
    long countByRecipientIdAndReadAtIsNull(Long recipientId);

    /**
     * The ids this account has not read, capped.
     *
     * <p>Exists so "mark all read" touches a bounded number of rows. An unbounded version would set
     * {@code read_at} on every notification this account has ever received, which on a busy forum is a
     * write per row on a page the manager opened once.
     */
    List<Notification> findTop200ByRecipientIdAndReadAtIsNullOrderByIdAsc(Long recipientId);

    /** Everything one account has read, for the count of "when did you last look". */
    List<Notification> findByRecipientIdAndReadAtIsNotNull(Long recipientId);
}