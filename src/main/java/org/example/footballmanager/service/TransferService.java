package org.example.footballmanager.service;

import org.example.footballmanager.model.Player;
import org.example.footballmanager.model.Transfer;
import org.example.footballmanager.repository.PlayerRepository;
import org.example.footballmanager.repository.TransferRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class TransferService {

    private final TransferRepository transferRepository;
    private final PlayerRepository playerRepository;

    public TransferService(TransferRepository transferRepository, PlayerRepository playerRepository) {
        this.transferRepository = transferRepository;
        this.playerRepository = playerRepository;
    }

    public Transfer listPlayerForTransfer(Long playerId, double askingPrice) {
        Player player = playerRepository.findById(playerId)
                .orElseThrow(() -> new RuntimeException("Player not found"));

        Transfer transfer = transferRepository.findByPlayerId(playerId).orElse(new Transfer());
        transfer.setPlayer(player);
        transfer.setAskingPrice(askingPrice);
        transfer.setListedAt(LocalDateTime.now());

        return transferRepository.save(transfer);
    }

    public List<Transfer> getAllTransfers() {
        return transferRepository.findAll();
    }

    public Transfer addInterest(Long playerId, String clubName) {
        Transfer transfer = transferRepository.findByPlayerId(playerId)
                .orElseThrow(() -> new RuntimeException("Transfer not found"));

        transfer.getInterestedTeams().add(clubName);
        return transferRepository.save(transfer);
    }

    public void removeFromTransferList(Long playerId) {
        transferRepository.findByPlayerId(playerId).ifPresent(transferRepository::delete);
    }
}