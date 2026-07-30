package org.example.footballmanager.newLogic.repository;

import org.example.footballmanager.newLogic.model.Transfer;
import org.example.footballmanager.newLogic.model.TransferStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
