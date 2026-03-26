package org.example.footballmanager.repository;

import org.example.footballmanager.model.TransferStatus;
import org.example.footballmanager.model.Transfer;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TransferRepository extends JpaRepository<Transfer, Long> {
    @EntityGraph(attributePaths = {"player", "player.team", "sellerTeam", "buyerTeam"})
    Optional<Transfer> findByPlayerId(Long playerId);

    @EntityGraph(attributePaths = {"player", "player.team", "sellerTeam", "buyerTeam"})
    List<Transfer> findByStatusAndBuyerTeamIsNullOrderByListedAtDesc(TransferStatus status);

    @EntityGraph(attributePaths = {"player", "player.team", "sellerTeam", "buyerTeam"})
    List<Transfer> findBySellerTeamIdAndStatusAndBuyerTeamIsNullOrderByListedAtDesc(Long teamId, TransferStatus status);

    @EntityGraph(attributePaths = {"player", "player.team", "sellerTeam", "buyerTeam"})
    List<Transfer> findBySellerTeamIdAndStatusInAndBuyerTeamIsNullOrderByListedAtDesc(Long teamId, Collection<TransferStatus> statuses);

    @EntityGraph(attributePaths = {"player", "player.team", "sellerTeam", "buyerTeam"})
    List<Transfer> findByStatusInAndBuyerTeamIsNull(Collection<TransferStatus> statuses);
}
