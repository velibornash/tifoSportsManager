package org.example.footballmanager.newLogic.repository;

import org.example.footballmanager.newLogic.model.Transfer;
import org.example.footballmanager.newLogic.model.TransferStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TransferRepository extends JpaRepository<Transfer, Long> {
    List<Transfer> findBySellerTeamId(Long sellerTeamId);
    List<Transfer> findByBuyerTeamId(Long buyerTeamId);
    List<Transfer> findByPlayerId(Long playerId);
    List<Transfer> findByStatus(TransferStatus status);
}