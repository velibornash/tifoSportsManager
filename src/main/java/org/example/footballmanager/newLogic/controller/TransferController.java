package org.example.footballmanager.newLogic.controller;

import org.example.footballmanager.newLogic.dto.transfer.PlayerTransferStatusDTO;
import org.example.footballmanager.newLogic.dto.transfer.TeamTransferOverviewDTO;
import org.example.footballmanager.newLogic.dto.transfer.TransferActionRequest;
import org.example.footballmanager.newLogic.dto.transfer.TransferDTO;
import org.example.footballmanager.newLogic.service.PlusFeatureService;
import org.example.footballmanager.newLogic.service.TransferService;
import org.example.footballmanager.newLogic.service.TransferWindowService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * The transfer market: a manager's own club, and nothing else.
 *
 * <p><b>Every write here used to take the acting club as a caller-supplied parameter and ask nothing
 * further.</b> The service could only compare that parameter against the seller — it cannot know who is
 * holding the token — so "is this your club?" was never asked anywhere on the surface. The codebase says so
 * itself: {@code AdminController.forceUnlist} carries a javadoc explaining it lives under {@code /admin}
 * <i>because</i> {@code /transfers} is not role-guarded. The hole was documented in prose and left open.
 *
 * <p>What that allowed, all of it now refused:
 *
 * <ul>
 *   <li>{@code POST /list/{playerId}} — list <b>any</b> player in the world at <b>any</b> price</li>
 *   <li>{@code DELETE /remove/{playerId}} — delist <b>any</b> player</li>
 *   <li>{@code POST /buy/{playerId}} — spend <b>any</b> club's budget</li>
 *   <li>{@code POST /interest/{playerId}} — register interest as <b>any</b> club</li>
 *   <li>{@code /accept-offer}, {@code /reject-offers}, {@code /interest/{id}/clear} — act on any listing</li>
 * </ul>
 *
 * <p>The rule is uniform and it is the one the rest of the game applies: <b>the club named in the request
 * must be the club the caller runs</b>. Not the same thing as "the club named is the seller" — naming a
 * rival satisfies the second and not the first, which is exactly how every one of the above was reachable.
 *
 * <p>Reads are untouched and stay that way. The market page is for every manager, the country filter already
 * defaults to the viewer's own, and the owner is explicit that signing a foreigner is allowed — so nothing
 * here restricts <i>what</i> a manager may look at or buy. Only <i>whose</i> club he may act for.
 */
@RestController
@RequestMapping("/transfers")
public class TransferController {

    private final TransferService transferService;
    private final TransferWindowService transferWindows;
    private final PlusFeatureService plusFeatures;

    public TransferController(TransferService transferService,
                              TransferWindowService transferWindows,
                              PlusFeatureService plusFeatures) {
        this.transferService = transferService;
        this.transferWindows = transferWindows;
        this.plusFeatures = plusFeatures;
    }

    /**
     * May this caller act for this club?
     *
     * <p>Two different refusals, and conflating them would make the API lie about what went wrong:
     *
     * <ul>
     *   <li><b>No club named</b> → <b>400</b>. The request is incomplete. That is not the same as being
     *       forbidden, and answering 403 tells a caller who simply forgot a parameter that he is not allowed
     *       to do a thing he may well be allowed to do.</li>
     *   <li><b>A club the caller does not run</b> → <b>403</b>.</li>
     * </ul>
     *
     * <p>Fails closed on both. The owner is let through, because a fix that locks the owner out of his own
     * game is worse than the hole it closes.
     *
     * <p>Checked <b>before</b> the service, not instead of it. The service's own check answers "is the club
     * you named the seller", which is a different question — naming a rival satisfies it and not this one,
     * which is precisely how every route below was reachable.
     */
    private boolean mayActFor(org.example.commonmanager.model.User principal, Long clubId) {
        if (clubId == null) {
            throw new org.example.footballmanager.newLogic.exception.ApiException(
                    HttpStatus.BAD_REQUEST, "TEAM_REQUIRED",
                    "Which club is acting? A club id is required to act on the transfer market.");
        }
        if (principal == null) {
            return false;
        }
        if (principal.getRole() != null && principal.getRole().name().equals("OWNER")) {
            return true;
        }
        return plusFeatures.isOwnTeam(principal, clubId);
    }

    /**
     * Thrown rather than returned, and the reason is the signatures.
     *
     * <p>These handlers answer a {@code TransferDTO}, so returning {@code ResponseEntity.status(403)} would
     * mean changing all ten return types — a wider diff than the defect deserves, and it would change what a
     * caller sees on success. {@code AccessDeniedException} is rethrown by the global handler on purpose so
     * that Spring Security renders it, which makes it a 403 rather than a 500.
     */
    private void refuseForeignClub() {
        throw new org.springframework.security.access.AccessDeniedException(
                "You can only act for your own club.");
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
    public TransferDTO listPlayer(@PathVariable Long playerId,
                                  @RequestBody TransferActionRequest request,
                                  @AuthenticationPrincipal org.example.commonmanager.model.User principal) {
        if (!mayActFor(principal, request.getTeamId())) {
            refuseForeignClub();
        }
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
                                       @RequestParam(required = false) Long teamId,
                                       @AuthenticationPrincipal org.example.commonmanager.model.User principal) {
        if (!mayActFor(principal, teamId)) {
            refuseForeignClub();
        }
        return transferService.addInterest(playerId, teamId, club);
    }

    @PostMapping("/interest/{playerId}/withdraw")
    public TransferDTO withdrawInterest(@PathVariable Long playerId,
                                        @RequestParam Long teamId,
                                        @AuthenticationPrincipal org.example.commonmanager.model.User principal) {
        if (!mayActFor(principal, teamId)) {
            refuseForeignClub();
        }
        return transferService.withdrawInterest(playerId, teamId);
    }

    @PostMapping("/interest/{playerId}/clear")
    public TransferDTO clearInterest(@PathVariable Long playerId,
                                     @RequestBody TransferActionRequest request,
                                     @AuthenticationPrincipal org.example.commonmanager.model.User principal) {
        if (!mayActFor(principal, request.getTeamId())) {
            refuseForeignClub();
        }
        return transferService.clearAllInterest(playerId, request.getTeamId());
    }

    @PostMapping("/buy/{playerId}")
    public TransferDTO buyListedPlayer(@PathVariable Long playerId,
                                       @RequestBody TransferActionRequest request,
                                       @AuthenticationPrincipal org.example.commonmanager.model.User principal) {
        if (!mayActFor(principal, request.getTeamId())) {
            refuseForeignClub();
        }
        return transferService.buyListedPlayer(playerId, request.getTeamId(), request.getPrice());
    }

    @PostMapping("/direct-buy/{playerId}")
    public TransferDTO directBuyPlayer(@PathVariable Long playerId,
                                       @RequestBody TransferActionRequest request,
                                       @AuthenticationPrincipal org.example.commonmanager.model.User principal) {
        if (!mayActFor(principal, request.getTeamId())) {
            refuseForeignClub();
        }
        return transferService.directBuyPlayer(playerId, request.getTeamId(), request.getPrice());
    }

    /**
     * Accept the highest live offer. Kept for the one-button path; see
     * {@link #acceptNamedOffer} for the seller choosing.
     */
    @PostMapping("/accept-offer/{playerId}")
    public TransferDTO acceptBestOffer(@PathVariable Long playerId,
                                       @RequestBody TransferActionRequest request,
                                       @AuthenticationPrincipal org.example.commonmanager.model.User principal) {
        if (!mayActFor(principal, request.getTeamId())) {
            refuseForeignClub();
        }
        return transferService.acceptBestOffer(playerId, request.getTeamId());
    }

    /**
     * The seller accepts one <em>named</em> bid.
     *
     * <p>An auction where the seller cannot choose is not an auction. This takes the
     * {@code offerId} the UI shows, so a manager whose second-choice buyer was simply the better fit
     * for his squad can say so — and it stops the backend picking the richest offer on his behalf.
     */
    @PostMapping("/accept-offer/{playerId}/{offerId}")
    public TransferDTO acceptNamedOffer(@PathVariable Long playerId,
                                        @PathVariable Long offerId,
                                        @RequestBody TransferActionRequest request,
                                        @AuthenticationPrincipal org.example.commonmanager.model.User principal) {
        if (!mayActFor(principal, request.getTeamId())) {
            refuseForeignClub();
        }
        return transferService.acceptOffer(playerId, offerId, request.getTeamId());
    }

    @PostMapping("/reject-offers/{playerId}")
    public TransferDTO rejectOffers(@PathVariable Long playerId,
                                    @RequestBody TransferActionRequest request,
                                    @AuthenticationPrincipal org.example.commonmanager.model.User principal) {
        if (!mayActFor(principal, request.getTeamId())) {
            refuseForeignClub();
        }
        return transferService.rejectOffers(playerId, request.getTeamId());
    }

    /**
 * Settles a player's objection to being on the transfer list (P2-3).
 *
 * <p>The manager's decision, and it is a real one: uphold the player and keep him, or pay
 * compensation and carry on selling him. Doing nothing leaves the objection standing, because a
 * player does not change his mind because a club relisted him.
 */
@PostMapping("/objection/{playerId}/resolve")
    public TransferDTO resolveObjection(@PathVariable Long playerId,
                                        @RequestParam String resolution,
                                        @RequestBody TransferActionRequest request) {
        TransferService.ObjectionResolution mode;
        try {
            mode = TransferService.ObjectionResolution.valueOf(resolution.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new org.example.footballmanager.newLogic.exception.ApiException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "INVALID_RESOLUTION",
                    "Resolution must be UPHELD (keep the player) or PAID (pay to overrule him).");
        }
        return transferService.resolveListingObjection(playerId, mode, request.getTeamId());
    }

    @DeleteMapping("/remove/{playerId}")
    public void removeFromList(@PathVariable Long playerId,
                               @RequestParam Long teamId,
                               @AuthenticationPrincipal org.example.commonmanager.model.User principal) {
        if (!mayActFor(principal, teamId)) {
            refuseForeignClub();
        }
        transferService.removeFromTransferList(playerId, teamId);
    }
}
