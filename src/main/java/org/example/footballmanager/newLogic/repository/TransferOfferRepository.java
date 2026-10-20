package org.example.footballmanager.newLogic.repository;

import org.example.footballmanager.newLogic.model.OfferStatus;
import org.example.footballmanager.newLogic.model.TransferOffer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TransferOfferRepository extends JpaRepository<TransferOffer, Long> {

    List<TransferOffer> findByTransferIdOrderByRoundAsc(Long transferId);

    List<TransferOffer> findByTransferIdAndStatusInOrderByFeeDesc(Long transferId, List<OfferStatus> statuses);

    /** A club's live offer on a transfer, if it has one. Identity is by id, never by name. */
    Optional<TransferOffer> findByTransferIdAndBuyerTeamIdAndStatusIn(
            Long transferId, Long buyerTeamId, List<OfferStatus> statuses);

    void deleteByTransferId(Long transferId);
}
