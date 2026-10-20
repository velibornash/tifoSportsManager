package org.tifo.footballmanager.newLogic.repository;

import org.tifo.footballmanager.newLogic.model.Transfer;
import org.tifo.footballmanager.newLogic.model.TransferStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TransferRepository extends JpaRepository<Transfer, Long> {
    @EntityGraph(attributePaths = {"player", "player.team", "sellerTeam", "buyerTeam"})
    Optional<Transfer> findByPlayerId(Long playerId);

    @EntityGraph(attributePaths = {"player", "player.team", "sellerTeam", "buyerTeam"})
    List<Transfer> findBySellerTeamId(Long sellerTeamId);

    @EntityGraph(attributePaths = {"player", "player.team", "sellerTeam", "buyerTeam"})
    List<Transfer> findByBuyerTeamId(Long buyerTeamId);

    List<Transfer> findByStatus(TransferStatus status);

    @EntityGraph(attributePaths = {"player", "player.team", "sellerTeam", "buyerTeam"})
    List<Transfer> findByStatusAndBuyerTeamIsNullOrderByListedAtDesc(TransferStatus status);

    @EntityGraph(attributePaths = {"player", "player.team", "sellerTeam", "buyerTeam"})
    List<Transfer> findBySellerTeamIdAndStatusInAndBuyerTeamIsNullOrderByListedAtDesc(Long teamId, Collection<TransferStatus> statuses);

    @EntityGraph(attributePaths = {"player", "player.team", "sellerTeam", "buyerTeam"})
    List<Transfer> findByStatusInAndBuyerTeamIsNull(Collection<TransferStatus> statuses);

    /**
     * Every live listing whose player is refusing to be sold — one query for the whole world.
     *
     * <p>Read weekly by {@code SupporterMoodService} to work out which clubs are selling players who
     * are objecting. Asking per club would be one query per club per week, which at 14,880 clubs is
     * 14,880 round-trips to count rows that are nearly always zero.
     *
     * <p>Narrowed to {@code LISTED} with no buyer, because an objection only means something while the
     * player is actually on the market. {@code sellerTeam} is fetched eagerly: the caller groups by it,
     * and a lazy proxy per row would turn one query into one per row.
     */
    @EntityGraph(attributePaths = {"sellerTeam"})
    @Query("SELECT t FROM Transfer t WHERE t.status = org.tifo.footballmanager.newLogic.model.TransferStatus.LISTED"
            + " AND t.buyerTeam IS NULL AND t.listingObjection IS NOT NULL"
            + " AND t.listingObjection <> org.tifo.footballmanager.newLogic.model.ListingObjection.NONE")
    List<Transfer> findActiveObjectedListings();

    /**
     * The player ids that are on the market right now — one query for the whole world.
     *
     * <p>The transfer market shows unlisted players as scout reports, but must leave out anyone who is
     * already for sale. That test used to be asked one player at a time
     * ({@code transferRepository.findByPlayerId(p.getId())}), which is one round-trip per player in
     * the country before the result is even trimmed to sixty. The same predicate as
     * {@code isActiveListing}: {@code LISTED}, no buyer, a real player.
     */
    @Query("SELECT DISTINCT t.player.id FROM Transfer t"
            + " WHERE t.status = org.tifo.footballmanager.newLogic.model.TransferStatus.LISTED"
            + " AND t.buyerTeam IS NULL AND t.player IS NOT NULL")
    List<Long> findActiveListedPlayerIds();
}
