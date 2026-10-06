package org.example.footballmanager.newLogic.repository;

import org.example.footballmanager.newLogic.model.FriendlyOffer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FriendlyOfferRepository extends JpaRepository<FriendlyOffer, Long> {

    /**
     * Every posting still advertised in one week, oldest first.
     *
     * <p>"Still advertised" is {@code status = OPEN} rather than a date comparison, because the expiry is
     * decided against the game clock and written back by {@code expirePassed}. Filtering on the status
     * means a listing can never show something the expiry pass has already retired, and the two cannot
     * disagree about what is live.
     */
    List<FriendlyOffer> findBySeasonYearAndWeekNumberAndStatusOrderByIdAsc(
            Integer seasonYear, Integer weekNumber, FriendlyOffer.OfferStatus status);

    /** Every posting of one club in a week, whatever its state. */
    List<FriendlyOffer> findByOfferingTeamIdAndSeasonYearAndWeekNumber(
            Long offeringTeamId, Integer seasonYear, Integer weekNumber);

    Optional<FriendlyOffer> findByOfferingTeamIdAndSeasonYearAndWeekNumberAndDayNumber(
            Long offeringTeamId, Integer seasonYear, Integer weekNumber, Integer dayNumber);
}