package org.example.footballmanager.newLogic.controller;

import org.example.footballmanager.newLogic.dto.transfer.PlayerTransferStatusDTO;
import org.example.footballmanager.newLogic.dto.transfer.TeamTransferOverviewDTO;
import org.example.footballmanager.newLogic.dto.transfer.TransferActionRequest;
import org.example.footballmanager.newLogic.dto.transfer.TransferDTO;
import org.example.footballmanager.newLogic.service.TransferService;
import org.example.footballmanager.newLogic.service.TransferWindowService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/transfers")
public class TransferController {

    private final TransferService transferService;
    private final TransferWindowService transferWindows;

    public TransferController(TransferService transferService,
                              TransferWindowService transferWindows) {
        this.transferService = transferService;
        this.transferWindows = transferWindows;
    }

    /**
     * Is the window open, and when does it shut.
     *
     * <p>The dashboard ticker reads this. It used to have no way to: a manager had to open the
     * transfer centre to find out whether he could do business, and the news that the window was
     * closing reached him at the moment it had already shut.
     */
    @GetMapping("/window")
    public java.util.Map<String, Object> windowStatus() {
        return transferWindows.status();
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

    @PostMapping("/interest/{playerId}/withdraw")
    public TransferDTO withdrawInterest(@PathVariable Long playerId,
                                        @RequestParam Long teamId) {
        return transferService.withdrawInterest(playerId, teamId);
    }

    @PostMapping("/interest/{playerId}/clear")
    public TransferDTO clearInterest(@PathVariable Long playerId, @RequestBody TransferActionRequest request) {
        return transferService.clearAllInterest(playerId, request.getTeamId());
    }

    @PostMapping("/buy/{playerId}")
    public TransferDTO buyListedPlayer(@PathVariable Long playerId, @RequestBody TransferActionRequest request) {
        return transferService.buyListedPlayer(playerId, request.getTeamId(), request.getPrice());
    }

    @PostMapping("/direct-buy/{playerId}")
    public TransferDTO directBuyPlayer(@PathVariable Long playerId, @RequestBody TransferActionRequest request) {
        return transferService.directBuyPlayer(playerId, request.getTeamId(), request.getPrice());
    }

    @PostMapping("/accept-offer/{playerId}")
    public TransferDTO acceptBestOffer(@PathVariable Long playerId, @RequestBody TransferActionRequest request) {
        return transferService.acceptBestOffer(playerId, request.getTeamId());
    }

    @PostMapping("/reject-offers/{playerId}")
    public TransferDTO rejectOffers(@PathVariable Long playerId, @RequestBody TransferActionRequest request) {
        return transferService.rejectOffers(playerId, request.getTeamId());
    }

    @DeleteMapping("/remove/{playerId}")
    public void removeFromList(@PathVariable Long playerId, @RequestParam Long teamId) {
        transferService.removeFromTransferList(playerId, teamId);
    }
}
