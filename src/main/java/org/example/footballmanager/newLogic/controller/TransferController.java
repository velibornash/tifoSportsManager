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
    private final org.example.footballmanager.newLogic.service.PlusFeatureService plusFeatures;

    public TransferController(TransferService transferService,
                              TransferWindowService transferWindows,
                              org.example.footballmanager.newLogic.service.PlusFeatureService plusFeatures) {
        this.transferService = transferService;
        this.transferWindows = transferWindows;
        this.plusFeatures = plusFeatures;
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

    /**
     * The transfer market, filtered by country (owner, 2026-09-28).
     *
     * <p><b>This is a filter, not a restriction.</b> Players belong to a <i>league's</i> country, not
     * necessarily to a manager's nationality, and signing one is explicitly allowed — the owner was
     * clear that "there is no obstacle to bringing a foreigner into the club". So the country only
     * decides <b>which market is on screen</b>, never what a manager is allowed to buy. Cross-border
     * business works exactly as before; the only thing scoped is the view.
     *
     * <p>Defaults to the viewer's own country, because a domestic market is what a manager wants
     * first and it is what a domestic league's transfer rules are written around. Passing
     * {@code ?country=XXX} browses another one, which is the point of the filter.
     *
     * <p>The default is read from the authenticated manager rather than trusted as a parameter: a
     * country a caller silently falls back to should not be one they picked. An explicit choice is
     * honoured, but only if it names a country we actually have — otherwise the filter would accept
     * any string and return nothing with no explanation.
     */
    @GetMapping
    public List<TransferDTO> getAllTransfers(
            @RequestParam(required = false) Long teamId,
            @RequestParam(required = false) String country,
            @org.springframework.security.core.annotation.AuthenticationPrincipal
            org.example.commonmanager.model.User principal) {
        String selected = plusFeatures.viewerCountryCode(principal);
        if (country != null && !country.isBlank()) {
            String requested = country.trim().toUpperCase(java.util.Locale.ROOT);
            selected = org.example.footballmanager.newLogic.model.CountryCatalog.isKnown(requested)
                    ? requested
                    : selected;
        }
        return transferService.getAllTransfers(teamId, selected);
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
