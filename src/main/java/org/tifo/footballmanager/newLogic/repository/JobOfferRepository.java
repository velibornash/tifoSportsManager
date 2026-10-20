package org.tifo.footballmanager.newLogic.repository;

import org.tifo.footballmanager.newLogic.model.JobOffer;
import org.tifo.footballmanager.newLogic.model.OfferStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface JobOfferRepository extends JpaRepository<JobOffer, Long> {

    /** The manager's open offers. There is at most one by rule, and the list is how that is read. */
    List<JobOffer> findByUserIdAndStatusOrderByOfferedAtDesc(Long userId, OfferStatus status);

    /** The guard that keeps a manager from collecting clubs: is there one already on the table? */
    boolean existsByUserIdAndStatus(Long userId, OfferStatus status);

    /** Everything past its deadline, for the sweep. */
    List<JobOffer> findByStatusAndExpiresAtBefore(OfferStatus status, Instant moment);

    List<JobOffer> findByUserIdOrderByOfferedAtDesc(Long userId);
}
