package org.example.footballmanager.controller;

import org.example.footballmanager.dto.transfer.PlayerTransferStatusDTO;
import org.example.footballmanager.dto.transfer.TeamTransferOverviewDTO;
import org.example.footballmanager.dto.transfer.TransferActionRequest;
import org.example.footballmanager.dto.transfer.TransferDTO;
import org.example.footballmanager.service.TransferService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/transfers")
public class TransferController {

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    @PostMapping("/list/{playerId}")
    public TransferDTO listPlayer(@PathVariable Long playerId, @RequestBody TransferActionRequest request) {
        return transferService.listPlayerForTransfer(playerId, request.getTeamId(), request.getPrice() == null ? 0.0 : request.getPrice());
    }

    @GetMapping
    public List<TransferDTO> getAllTransfers(@RequestParam(required = false) Long teamId) {
        return transferService.getAllTransfers(teamId);
    }

    @GetMapping("/team/{teamId}")
    public TeamTransferOverviewDTO getTeamTransfers(@PathVariable Long teamId,
                                                    @RequestParam(required = false) Long viewerTeamId) {
        return transferService.getTeamTransferOverview(teamId, viewerTeamId);
    }

    @GetMapping("/player/{playerId}")
    public PlayerTransferStatusDTO getPlayerTransferStatus(@PathVariable Long playerId,
                                                           @RequestParam(required = false) Long viewerTeamId) {
        return transferService.getPlayerTransferStatus(playerId, viewerTeamId);
    }

    @PostMapping("/interest/{playerId}")
    public TransferDTO expressInterest(@PathVariable Long playerId,
                                       @RequestParam(required = false) String club,
                                       @RequestParam(required = false) Long teamId) {
        return transferService.addInterest(playerId, teamId, club);
    }

    @PostMapping("/buy/{playerId}")
    public TransferDTO buyListedPlayer(@PathVariable Long playerId, @RequestBody TransferActionRequest request) {
        return transferService.buyListedPlayer(playerId, request.getTeamId(), request.getPrice());
    }

    @PostMapping("/direct-buy/{playerId}")
    public TransferDTO directBuyPlayer(@PathVariable Long playerId, @RequestBody TransferActionRequest request) {
        return transferService.directBuyPlayer(playerId, request.getTeamId(), request.getPrice());
    }

    @DeleteMapping("/remove/{playerId}")
    public void removeFromList(@PathVariable Long playerId, @RequestParam Long teamId) {
        transferService.removeFromTransferList(playerId, teamId);
    }
}