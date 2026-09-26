package org.example.footballmanager.newLogic.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.transaction.Transactional;
import org.example.footballmanager.newLogic.dto.transfer.PlayerTransferStatusDTO;
import org.example.footballmanager.newLogic.dto.transfer.TeamTransferOverviewDTO;
import org.example.footballmanager.newLogic.dto.transfer.TransferDTO;
import org.example.footballmanager.newLogic.exception.ApiException;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Position;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.model.Transfer;
import org.example.footballmanager.newLogic.model.TransferStatus;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.example.footballmanager.newLogic.repository.TransferRepository;
import org.example.commonmanager.repository.UserRepository;
import org.example.footballmanager.newLogic.util.players.SquadNumberAssigner;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class TransferService {
    private static final Logger log = LoggerFactory.getLogger(TransferService.class);
    private final TransferWindowService transferWindows;
    private final ClubNeedService clubNeeds;


    private final TransferRepository transferRepository;
    private final PlayerRepository playerRepository;
    private final TeamRepository teamRepository;
    private final UserRepository userRepository;
    private final SquadNumberAssigner squadNumberAssigner;
    private final Random random = new Random();

    public TransferService(TransferRepository transferRepository,
                           PlayerRepository playerRepository,
                           TeamRepository teamRepository,
                           UserRepository userRepository,
                           SquadNumberAssigner squadNumberAssigner,
                           TransferWindowService transferWindows,
                           ClubNeedService clubNeeds) {
        this.clubNeeds = clubNeeds;
        this.transferRepository = transferRepository;
        this.playerRepository = playerRepository;
        this.teamRepository = teamRepository;
        this.userRepository = userRepository;
        this.squadNumberAssigner = squadNumberAssigner;
        this.transferWindows = transferWindows;
    }

    @Transactional
    public Transfer listPlayerForTransfer(Long playerId, double askingPrice) {
        Player player = playerRepository.findById(playerId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PLAYER_NOT_FOUND", "Player not found."));

        Team sellerTeam = requirePlayerTeam(player);
        return listPlayerForTransferEntity(player, sellerTeam.getId(), askingPrice);
    }

    @Transactional
    public TransferDTO listPlayerForTransfer(Long playerId, Long actingTeamId, double askingPrice) {
        Player player = playerRepository.findById(playerId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PLAYER_NOT_FOUND", "Player not found."));
        return toTransferDto(listPlayerForTransferEntity(player, actingTeamId, askingPrice), actingTeamId);
    }

    /**
     * What the manager can see of the market: the listed players, plus anyone they have scouted.
     *
     * <p>Previously this returned only {@code status == LISTED}, which made a rival's unlisted squad
     * invisible. That is not realism, it is a missing feature: scouting is how a manager finds the
     * player nobody has put up for sale, and the owner allows approaching a player who has no asking
     * price, but there was no way to see one. The transfer centre now reports both, and says which
     * is which, so an unlisted player is reachable rather than merely existent.
     */
    @Transactional
    public List<TransferDTO> getAllTransfers(Long viewerTeamId) {
        List<TransferDTO> out = new ArrayList<>();
        for (Transfer transfer : transferRepository
                .findByStatusAndBuyerTeamIsNullOrderByListedAtDesc(TransferStatus.LISTED)) {
            out.add(toTransferDto(transfer, viewerTeamId));
        }

        // Unlisted players are shown as scout reports: no asking price, and no pretending they are
        // for sale. The manager may still approach one.
        for (Player player : scoutedUnlisted(viewerTeamId)) {
            Transfer stub = new Transfer();
            stub.setPlayer(player);
            stub.setSellerTeam(player.getTeam());
            stub.setStatus(TransferStatus.LISTED);
            stub.setAskingPrice(0.0);
            stub.setListedAt(null);
            TransferDTO dto = toTransferDto(stub, viewerTeamId);
            out.add(dto);
        }

        out.sort(Comparator.comparing(TransferDTO::getAskingPrice,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(TransferDTO::getPlayerName, Comparator.nullsLast(Comparator.naturalOrder())));
        return out;
    }

    /**
     * Rival players a manager may see and approach but who are not on the market.
     *
     * <p>Everyone at another club except a keeper, since a club does not scout for a goalkeeper it
     * already has, and excluding the viewer's own squad which is on its own page.
     */
    @Transactional
    public List<Player> scoutedUnlisted(Long viewerTeamId) {
        if (viewerTeamId == null) return List.of();
        return teamRepository.findClubTeamsForOperations().stream()
                .filter(Objects::nonNull)
                .filter(team -> team.getId() != null)
                .filter(team -> !Objects.equals(team.getId(), viewerTeamId))
                .flatMap(team -> playerRepository.findByTeamId(team.getId()).stream())
                .filter(Objects::nonNull)
                .filter(p -> p.getId() != null && p.getTeam() != null)
                .filter(p -> p.getPosition() == null || p.getPosition() != Position.GK)
                .filter(p -> transferRepository.findByPlayerId(p.getId())
                        .map(t -> !isActiveListing(t))
                        .orElse(true))
                .limit(60)
                .toList();
    }

    @Transactional
    public TeamTransferOverviewDTO getTeamTransferOverview(Long teamId, Long viewerTeamId) {
        Team team = loadTeam(teamId);
        TeamTransferOverviewDTO dto = new TeamTransferOverviewDTO();
        dto.setTeamId(team.getId());
        dto.setTeamName(team.getName());
        dto.setBudget(team.getBudget());
        List<Transfer> teamTransfers = transferRepository
                .findBySellerTeamIdAndStatusInAndBuyerTeamIsNullOrderByListedAtDesc(teamId, EnumSet.of(TransferStatus.LISTED, TransferStatus.OFFER_RECEIVED));
        List<TransferDTO> listed = teamTransfers.stream()
                .filter(this::isActiveListing)
                .map(transfer -> toTransferDto(transfer, viewerTeamId))
                .toList();
        List<TransferDTO> incomingOffers = teamTransfers.stream()
                .filter(this::hasOpenOffer)
                .filter(transfer -> !isActiveListing(transfer))
                .map(transfer -> toTransferDto(transfer, viewerTeamId))
                .toList();
        dto.setListedPlayers(listed);
        dto.setListedCount(listed.size());
        dto.setIncomingOffers(incomingOffers);
        dto.setIncomingOfferCount(incomingOffers.size());
        return dto;
    }

    @Transactional
    public PlayerTransferStatusDTO getPlayerTransferStatus(Long playerId, Long viewerTeamId) {
        Player player = playerRepository.findById(playerId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PLAYER_NOT_FOUND", "Player not found."));
        Transfer transfer = transferRepository.findByPlayerId(playerId).orElse(null);

        Long currentTeamId = player.getTeam() != null ? player.getTeam().getId() : null;
        boolean ownedByViewer = viewerTeamId != null && Objects.equals(currentTeamId, viewerTeamId);
        boolean listed = isActiveListing(transfer);
        boolean openOffer = hasOpenOffer(transfer);

        PlayerTransferStatusDTO dto = new PlayerTransferStatusDTO();
        dto.setPlayerId(player.getId());
        dto.setCurrentTeamId(currentTeamId);
        dto.setCurrentTeamName(player.getTeam() != null ? player.getTeam().getName() : null);
        dto.setListed(listed);
        dto.setStatus(transfer != null && transfer.getStatus() != null ? transfer.getStatus().name() : (listed ? TransferStatus.LISTED.name() : null));
        dto.setAskingPrice(listed && transfer != null ? transfer.getAskingPrice() : null);
        dto.setAgreedPrice(transfer != null && transfer.getStatus() == TransferStatus.COMPLETED ? transfer.getAgreedPrice() : null);
        dto.setListedAt((listed || openOffer || transfer != null && transfer.getStatus() == TransferStatus.COMPLETED) && transfer != null ? transfer.getListedAt() : null);
        dto.setCompletedAt(transfer != null ? transfer.getCompletedAt() : null);
        dto.setSellerTeamId(transfer != null && transfer.getSellerTeam() != null ? transfer.getSellerTeam().getId() : currentTeamId);
        dto.setSellerTeamName(transfer != null && transfer.getSellerTeam() != null ? transfer.getSellerTeam().getName() : dto.getCurrentTeamName());
        dto.setBuyerTeamId(transfer != null && transfer.getBuyerTeam() != null ? transfer.getBuyerTeam().getId() : null);
        dto.setBuyerTeamName(transfer != null && transfer.getBuyerTeam() != null ? transfer.getBuyerTeam().getName() : null);
        dto.setInterestedTeams(sortedInterests(transfer));
        dto.setOwnedByViewer(ownedByViewer);
        dto.setCanList(ownedByViewer && !listed);
        dto.setCanRemove(ownedByViewer && listed && !hasPricedOffer(transfer));
        dto.setCanBuyListed(listed && viewerTeamId != null && !ownedByViewer);
        dto.setCanDirectBuy(viewerTeamId != null && !ownedByViewer && !listed);
        dto.setCanAcceptOffer(ownedByViewer && openOffer);
        dto.setCanRejectOffer(ownedByViewer && openOffer);
        // Bare interest entries must not be able to trap a seller on the list (Sprint 0.2).
        dto.setCanClearInterest(ownedByViewer && listed && !sortedInterests(transfer).isEmpty());
        dto.setHasPricedOffer(hasPricedOffer(transfer));
        dto.setSummary(buildPlayerSummary(dto));
        return dto;
    }

    @Transactional
    public TransferDTO addInterest(Long playerId, Long viewerTeamId, String clubName) {
        Transfer transfer = getActiveTransfer(playerId);
        Long sellerTeamId = transfer.getSellerTeam() != null ? transfer.getSellerTeam().getId() : null;
        if (viewerTeamId != null && Objects.equals(sellerTeamId, viewerTeamId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TRANSFER",
                    "Your club cannot register interest in its own player.");
        }

        String resolvedClubName = clubName;
        if ((resolvedClubName == null || resolvedClubName.isBlank()) && viewerTeamId != null) {
            resolvedClubName = loadTeam(viewerTeamId).getName();
        }

        if (resolvedClubName == null || resolvedClubName.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CLUB_REQUIRED",
                    "Club name is required to register interest.");
        }

        transfer.getInterestedTeams().add(resolvedClubName.trim());
        return toTransferDto(transferRepository.save(transfer), viewerTeamId);
    }

    @Transactional
    public void removeFromTransferList(Long playerId, Long actingTeamId) {
        Transfer transfer = getActiveTransfer(playerId);
        Team sellerTeam = transfer.getSellerTeam() != null ? transfer.getSellerTeam() : requirePlayerTeam(transfer.getPlayer());
        if (actingTeamId != null && !Objects.equals(sellerTeam.getId(), actingTeamId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN",
                    "Only the owning club can remove this player from the transfer list.");
        }
        // Only a PRICED offer blocks delisting. A bare "register interest" entry is not an offer,
        // so treating it as one used to soft-lock the player on the list forever: canRemove went
        // false while canRejectOffer stayed false too, leaving no escape route.
        if (hasPricedOffer(transfer)) {
            throw new ApiException(HttpStatus.CONFLICT, "ACTIVE_OFFER",
                    "Cannot remove this player from the transfer list while a club has a live offer on him. "
                            + "Reject the offers first.");
        }
        transfer.setStatus(TransferStatus.CANCELLED);
        transfer.setCompletedAt(LocalDateTime.now());
        transfer.getInterestedTeams().clear();
        transferRepository.save(transfer);
    }

    /**
     * Withdraw this club's own interest in a listed player.
     *
     * <p>Gives an interested club a clean exit so it cannot hold a seller's player hostage, and lets
     * AI clubs back out without a seller having to clear the whole list.
     */
    @Transactional
    public TransferDTO withdrawInterest(Long playerId, Long withdrawingTeamId, String clubName) {
        Transfer transfer = getActiveTransfer(playerId);
        String resolvedClub = clubName;
        if ((resolvedClub == null || resolvedClub.isBlank()) && withdrawingTeamId != null) {
            resolvedClub = loadTeam(withdrawingTeamId).getName();
        }
        if (resolvedClub == null || resolvedClub.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CLUB_REQUIRED",
                    "Club name or team id is required to withdraw interest.");
        }

        String normalized = resolvedClub.trim();
        boolean removed = transfer.getInterestedTeams().removeIf(existing -> {
            String value = existing == null ? "" : existing.trim();
            return value.equalsIgnoreCase(normalized) || parseOfferDetails(value) != null
                    && parseOfferDetails(value).clubName().equalsIgnoreCase(normalized);
        });

        if (!removed) {
            throw new ApiException(HttpStatus.CONFLICT, "NO_INTEREST_FOUND",
                    resolvedClub + " has no registered interest in this player.");
        }
        return toTransferDto(transferRepository.save(transfer), withdrawingTeamId);
    }

    /**
     * Clear every interest and offer on a listing without accepting any of them.
     *
     * <p>{@link #rejectOffers} could only be reached when a priced offer existed, so a listing held
     * hostage by bare interest entries had no seller-side exit at all. This is that exit.
     */
    @Transactional
    public TransferDTO clearAllInterest(Long playerId, Long actingTeamId) {
        Transfer transfer = getActiveTransfer(playerId);
        Team sellerTeam = transfer.getSellerTeam() != null ? transfer.getSellerTeam() : requirePlayerTeam(transfer.getPlayer());
        if (actingTeamId != null && !Objects.equals(sellerTeam.getId(), actingTeamId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN",
                    "Only the owning club can clear interest on this player.");
        }

        int cleared = transfer.getInterestedTeams().size();
        transfer.getInterestedTeams().clear();
        transfer.setBuyerTeam(null);
        transfer.setStatus(TransferStatus.LISTED);
        transfer.setCompletedAt(null);
        TransferDTO dto = toTransferDto(transferRepository.save(transfer), actingTeamId);
        dto.setOfferAccepted(false);
        dto.setActionMessage("Cleared " + cleared + " interest/offer entr" + (cleared == 1 ? "y" : "ies")
                + ". The player remains on the transfer list.");
        return dto;
    }

    /**
     * Admin override: force a player off the transfer list regardless of any interest or offers.
     * Exists so a stuck listing can always be resolved by an operator.
     */
    @Transactional
    public TransferDTO forceUnlist(Long playerId) {
        Transfer transfer = transferRepository.findByPlayerId(playerId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TRANSFER_NOT_FOUND",
                        "Transfer listing not found."));
        int cleared = transfer.getInterestedTeams().size();
        transfer.getInterestedTeams().clear();
        transfer.setBuyerTeam(null);
        transfer.setStatus(TransferStatus.CANCELLED);
        transfer.setCompletedAt(LocalDateTime.now());
        TransferDTO dto = toTransferDto(transferRepository.save(transfer), null);
        dto.setActionMessage("Force-unlisted by admin. Cleared " + cleared + " entr"
                + (cleared == 1 ? "y" : "ies") + ".");
        return dto;
    }

    @Transactional
    public TransferDTO buyListedPlayer(Long playerId, Long buyerTeamId, Double offeredPrice) {
        Transfer transfer = getActiveTransfer(playerId);
        Team buyerTeam = loadTeam(buyerTeamId);
        // A listed player is sold at (or above) the asking price only. resolveAgreedPrice is the
        // single choke point that enforces this - it also protects direct-buy.
        double price = resolveAgreedPrice(offeredPrice, transfer.getAskingPrice());
        return toTransferDto(completeTransfer(transfer.getPlayer(), buyerTeam, price, transfer), buyerTeamId);
    }

    @Transactional
    public TransferDTO directBuyPlayer(Long playerId, Long buyerTeamId, Double offeredPrice) {
        Player player = playerRepository.findById(playerId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PLAYER_NOT_FOUND", "Player not found."));
        Team buyerTeam = loadTeam(buyerTeamId);
        Team sellerTeam = requirePlayerTeam(player);
        Transfer transfer = transferRepository.findByPlayerId(playerId).orElseGet(Transfer::new);
        if (Objects.equals(sellerTeam.getId(), buyerTeam.getId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TRANSFER", "You cannot buy your own player.");
        }

        transfer.setPlayer(player);
        double fallbackPrice = transfer.getAskingPrice() > 0 ? transfer.getAskingPrice() : Math.max(1.0, player.getPlayerValue());
        double price = resolveAgreedPrice(offeredPrice, fallbackPrice);

        double buyerBudget = buyerTeam.getBudget() == null ? 0.0 : buyerTeam.getBudget();
        if (buyerBudget + 0.0001 < price) {
            throw new ApiException(HttpStatus.CONFLICT, "INSUFFICIENT_BUDGET",
                    "Your club does not have enough budget for this offer.");
        }

        if (isActiveListing(transfer)) {
            // Listed player: reaching the asking price closes the deal (see buyListedPlayer).
            TransferDTO dto = toTransferDto(completeTransfer(player, buyerTeam, price, transfer), buyerTeamId);
            dto.setOfferAccepted(true);
            dto.setActionMessage("Offer accepted by " + sellerTeam.getName() + ". Transfer completed for €" + Math.round(price) + ".");
            return dto;
        }

        boolean accepted = isOfferAccepted(player, sellerTeam, buyerTeam, price);
        if (!accepted) {
            TransferDTO dto = buildOfferResponseDto(player, sellerTeam, buyerTeam, price, transfer, buyerTeamId, false,
                    sellerTeam.getName() + " rejected the offer of €" + Math.round(price) + ".");
            return dto;
        }

        TransferDTO dto = toTransferDto(completeTransfer(player, buyerTeam, price, transfer), buyerTeamId);
        dto.setOfferAccepted(true);
        dto.setActionMessage("Offer accepted by " + sellerTeam.getName() + ". Transfer completed for €" + Math.round(price) + ".");
        return dto;
    }

    @Transactional
    public TransferDTO acceptBestOffer(Long playerId, Long actingTeamId) {
        Transfer transfer = getOpenOfferTransfer(playerId);
        Team sellerTeam = transfer.getSellerTeam() != null ? transfer.getSellerTeam() : requirePlayerTeam(transfer.getPlayer());
        if (actingTeamId != null && !Objects.equals(sellerTeam.getId(), actingTeamId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Only the owning club can accept incoming offers.");
        }

        OfferResolution bestOffer = resolveBestAcceptableOffer(transfer);
        Team buyerTeam = bestOffer.buyerTeam();

        TransferDTO dto = toTransferDto(completeTransfer(transfer.getPlayer(), buyerTeam, bestOffer.offer().price(), transfer), actingTeamId);
        dto.setOfferAccepted(true);
        dto.setActionMessage("Offer accepted. " + transfer.getPlayer().getName()
                + " joins " + buyerTeam.getName() + " for EUR " + Math.round(bestOffer.offer().price()) + ".");
        return dto;
    }

    @Transactional
    public TransferDTO rejectOffers(Long playerId, Long actingTeamId) {
        Transfer transfer = getOpenOfferTransfer(playerId);
        Team sellerTeam = transfer.getSellerTeam() != null ? transfer.getSellerTeam() : requirePlayerTeam(transfer.getPlayer());
        if (actingTeamId != null && !Objects.equals(sellerTeam.getId(), actingTeamId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Only the owning club can reject incoming offers.");
        }

        transfer.getInterestedTeams().clear();
        transfer.setBuyerTeam(null);
        if (isActiveListing(transfer)) {
            transfer.setStatus(TransferStatus.LISTED);
            transfer.setCompletedAt(null);
        } else {
            transfer.setStatus(TransferStatus.CANCELLED);
            transfer.setCompletedAt(LocalDateTime.now());
        }
        Transfer saved = transferRepository.save(transfer);
        TransferDTO dto = toTransferDto(saved, actingTeamId);
        dto.setOfferAccepted(false);
        dto.setActionMessage("All incoming offers were rejected.");
        return dto;
    }

    @Transactional
    public void simulateWeeklyMarketActivity() {
        // The market only runs during a registration window. This is called every week from the
        // season advance, so without the guard it would attempt transfers for most of the season and
        // be refused six weeks out of eleven. A club does not make offers in April, and neither does
        // the AI.
        if (transferWindows != null && !transferWindows.currentWindow().permitsBusiness()) {
            log.debug("Transfer market idle: window is shut.");
            return;
        }

        List<Team> allTeams = teamRepository.findClubTeamsForOperations().stream()
                .filter(Objects::nonNull)
                .filter(team -> team.getId() != null)
                .toList();
        if (allTeams.isEmpty()) {
            return;
        }

        Set<Long> humanManagedTeamIds = new HashSet<>(userRepository.findDistinctManagedTeamIds());

        Map<Long, Transfer> transferByPlayerId = transferRepository
                .findByStatusInAndBuyerTeamIsNull(EnumSet.of(TransferStatus.LISTED, TransferStatus.OFFER_RECEIVED)).stream()
                .filter(Objects::nonNull)
                .filter(transfer -> transfer.getPlayer() != null && transfer.getPlayer().getId() != null)
                .collect(Collectors.toMap(transfer -> transfer.getPlayer().getId(), Function.identity(), (left, right) -> right));

        List<Team> aiTeams = allTeams.stream()
                .filter(team -> !humanManagedTeamIds.contains(team.getId()))
                .toList();

        maybeCreateAiListing(aiTeams, transferByPlayerId);
        maybeCreateIncomingOffer(humanManagedTeamIds, aiTeams, transferByPlayerId);
    }

    private Transfer listPlayerForTransferEntity(Player player, Long actingTeamId, double askingPrice) {
        Team sellerTeam = requirePlayerTeam(player);
        if (actingTeamId != null && !Objects.equals(sellerTeam.getId(), actingTeamId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN",
                    "Only the owning club can list this player.");
        }

        Transfer transfer = transferRepository.findByPlayerId(player.getId()).orElse(new Transfer());
        boolean alreadyListed = isActiveListing(transfer);
        transfer.setPlayer(player);
        transfer.setSellerTeam(sellerTeam);
        transfer.setBuyerTeam(null);
        transfer.setStatus(TransferStatus.LISTED);
        transfer.setAskingPrice(Math.max(1.0, askingPrice));
        transfer.setAgreedPrice(null);
        transfer.setListedAt(LocalDateTime.now());
        transfer.setCompletedAt(null);
        if (!alreadyListed) {
            transfer.getInterestedTeams().clear();
        }
        return transferRepository.save(transfer);
    }

    private Transfer completeTransfer(Player player, Team buyerTeam, double price, Transfer transfer) {
        Team sellerTeam = requirePlayerTeam(player);
        if (Objects.equals(sellerTeam.getId(), buyerTeam.getId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TRANSFER", "You cannot buy your own player.");
        }

        // Defence in depth: no code path may move a player for less than the agreed floor.
        // resolveAgreedPrice is the primary guard; this catches any future caller that bypasses it.
        double floor = transfer == null ? 0.0 : transfer.getAskingPrice();
        if (floor > 0 && price + 0.0001 < floor) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "PRICE_BELOW_ASKING",
                    "Agreed price must be at least the asking price of €" + Math.round(floor) + ".");
        }

        double buyerBudget = buyerTeam.getBudget() == null ? 0.0 : buyerTeam.getBudget();
        if (buyerBudget + 0.0001 < price) {
            throw new ApiException(HttpStatus.CONFLICT, "INSUFFICIENT_BUDGET",
                    "Your club does not have enough budget for this transfer.");
        }

        buyerTeam.setBudget(round2(buyerBudget - price));
        double sellerBudget = sellerTeam.getBudget() == null ? 0.0 : sellerTeam.getBudget();
        sellerTeam.setBudget(round2(sellerBudget + price));
        teamRepository.saveAll(List.of(sellerTeam, buyerTeam));

        player.setTeam(buyerTeam);
        player.setSquadNumber(squadNumberAssigner.nextNumberForTeam(buyerTeam, player.getPosition()));
        playerRepository.save(player);
        squadNumberAssigner.assignMissingNumbers(sellerTeam);
        squadNumberAssigner.assignMissingNumbers(buyerTeam);

        transfer.setPlayer(player);
        transfer.setSellerTeam(sellerTeam);
        transfer.setBuyerTeam(buyerTeam);
        transfer.setStatus(TransferStatus.COMPLETED);
        transfer.setAgreedPrice(price);
        transfer.setCompletedAt(LocalDateTime.now());
        if (transfer.getListedAt() == null) {
            transfer.setListedAt(LocalDateTime.now());
        }
        if (transfer.getAskingPrice() <= 0) {
            transfer.setAskingPrice(price);
        }
        return transferRepository.save(transfer);
    }

    private Transfer getActiveTransfer(Long playerId) {
        Transfer transfer = transferRepository.findByPlayerId(playerId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TRANSFER_NOT_FOUND", "Transfer listing not found."));
        if (!isActiveListing(transfer)) {
            throw new ApiException(HttpStatus.CONFLICT, "NOT_LISTED", "Player is not currently transfer listed.");
        }
        return transfer;
    }

    private Transfer getOpenOfferTransfer(Long playerId) {
        Transfer transfer = transferRepository.findByPlayerId(playerId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TRANSFER_NOT_FOUND", "Incoming offer was not found."));
        if (!hasOpenOffer(transfer)) {
            throw new ApiException(HttpStatus.CONFLICT, "NO_OPEN_OFFERS",
                    "There are no incoming offers to process for this player.");
        }
        return transfer;
    }

    private boolean isActiveListing(Transfer transfer) {
        if (transfer == null || transfer.getPlayer() == null) {
            return false;
        }
        if (transfer.getStatus() != TransferStatus.LISTED) {
            return false;
        }
        return transfer.getBuyerTeam() == null;
    }

    private boolean hasOpenOffer(Transfer transfer) {
        if (transfer == null || transfer.getPlayer() == null) {
            return false;
        }
        return transfer.getBuyerTeam() == null && hasPricedOffer(transfer);
    }

    /**
     * A <em>priced</em> offer exists (an "X offered EUR Y" entry), as opposed to a bare
     * "register interest" entry which carries no price and no commitment.
     *
     * <p>This distinction is the whole point: a bare interest must never be able to block a seller
     * from delisting, otherwise the listing becomes permanently stuck.
     */
    private boolean hasPricedOffer(Transfer transfer) {
        if (transfer == null || transfer.getPlayer() == null) {
            return false;
        }
        return sortedInterests(transfer).stream().anyMatch(this::isOfferEntry);
    }

    private Team loadTeam(Long teamId) {
        if (teamId == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TEAM_REQUIRED", "Team id is required.");
        }
        return teamRepository.findById(teamId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TEAM_NOT_FOUND", "Team not found."));
    }

    private Team requirePlayerTeam(Player player) {
        if (player.getTeam() == null || player.getTeam().getId() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "PLAYER_UNASSIGNED", "Player is not assigned to a club.");
        }
        return player.getTeam();
    }

    private List<String> sortedInterests(Transfer transfer) {
        if (transfer == null || transfer.getInterestedTeams() == null) {
            return List.of();
        }
        return transfer.getInterestedTeams().stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    /**
     * Single choke point for every agreed price in the transfer flow.
     *
     * <p>Historically this was {@code Math.max(1.0, requested)} with no lower bound against the
     * asking price, which let any listed player be bought for EUR 1. The floor is now mandatory.
     *
     * @param requestedPrice client-supplied price; {@code null} means "accept the floor"
     * @param floorPrice     the agreed minimum (asking price when listed, market value otherwise)
     * @throws ApiException {@code PRICE_BELOW_ASKING} if the request is under the floor
     * @throws ApiException {@code INVALID_PRICE} if the request is not a positive finite amount
     */
    private double resolveAgreedPrice(Double requestedPrice, double floorPrice) {
        double floor = floorPrice > 0 ? floorPrice : 0.0;

        if (requestedPrice == null) {
            return floor > 0 ? floor : 1.0;
        }
        if (requestedPrice.isNaN() || requestedPrice.isInfinite()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_PRICE",
                    "Offered price must be a valid number.");
        }
        if (requestedPrice <= 0) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_PRICE",
                    "Offered price must be greater than zero.");
        }
        if (floor > 0 && requestedPrice + 0.0001 < floor) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "PRICE_BELOW_ASKING",
                    "Offered price €" + Math.round(requestedPrice) + " is below the asking price of €"
                            + Math.round(floor) + ".");
        }
        return requestedPrice;
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private void maybeCreateAiListing(List<Team> aiTeams, Map<Long, Transfer> transferByPlayerId) {
        if (aiTeams.isEmpty() || nextRandomDouble() > 0.42) {
            return;
        }

        List<Team> eligibleTeams = aiTeams.stream()
                .filter(team -> countOpenTransfersForSeller(team.getId(), transferByPlayerId) < 2)
                .filter(team -> playerRepository.countByTeam(team) >= 14)
                .toList();
        if (eligibleTeams.isEmpty()) {
            return;
        }

        Team sellerTeam = randomItem(eligibleTeams);
        List<Player> candidates = playerRepository.findByTeam(sellerTeam).stream()
                .filter(player -> player.getId() != null)
                .filter(player -> player.getAge() >= 18)
                .filter(player -> !hasOpenTransferRecord(transferByPlayerId.get(player.getId())))
                .toList();
        if (candidates.isEmpty()) {
            return;
        }

        Player player = randomItem(candidates);
        double baseValue = Math.max(1.0, player.getPlayerValue());
        double askingPrice = round2(baseValue * (0.88 + nextRandomDouble() * 0.24));
        Transfer created = listPlayerForTransferEntity(player, sellerTeam.getId(), askingPrice);
        transferByPlayerId.put(player.getId(), created);
    }

    /**
     * One week of bidding, by every club that has a reason.
     *
     * <p>This used to return immediately unless the player was running a club, and then pick a buyer
     * uniformly at random and a price from a dice roll. So AI clubs never traded with each other at
     * all — the market was the player's alone — and the one bid that did happen ignored whether the
     * buyer needed the player or could pay for him.
     *
     * <p>Now every listed player is up for auction, every club is a potential buyer, and a club bids
     * in proportion to how badly it wants that specific player. A club with no gap at that position
     * does not bid, which is the whole difference between a market and noise.
     */
    private void maybeCreateIncomingOffer(Set<Long> humanManagedTeamIds, List<Team> aiTeams,
                                          Map<Long, Transfer> transferByPlayerId) {
        if (aiTeams.isEmpty() || nextRandomDouble() > 0.68) {
            return;
        }

        // Every club in the game can bid, the manager's included — the manager's own club making an
        // offer is the AI playing their side, and the manager can accept or refuse it.
        List<Team> allTeams = new ArrayList<>(aiTeams);

        // Everyone who is on the market this week, whoever they play for.
        List<Player> listedPlayers = transferByPlayerId.values().stream()
                .filter(Objects::nonNull)
                .filter(this::isActiveListing)
                .map(Transfer::getPlayer)
                .filter(p -> p != null && p.getId() != null && p.getTeam() != null)
                .distinct()
                .toList();
        if (listedPlayers.isEmpty()) {
            return;
        }

        for (Player targetPlayer : listedPlayers) {
            Team sellerTeam = requirePlayerTeam(targetPlayer);

            // The seller does not bid for his own player, and neither does anyone who cannot pay.
            List<Team> buyers = allTeams.stream()
                    .filter(team -> !Objects.equals(team.getId(), sellerTeam.getId()))
                    .filter(team -> needsInterest(team, targetPlayer))
                    .toList();
            if (buyers.isEmpty()) {
                continue;
            }

            // Weighted, not uniform: the club that wants him most is the likeliest to win him, and a
            // rich club with a real gap outbids a poor one with the same gap.
            Team buyerTeam = weightedBuyer(buyers, targetPlayer);
            if (buyerTeam == null) {
                continue;
            }

            double offerPrice = clubNeeds.valuation(buyerTeam, targetPlayer);
            if (offerPrice <= 0) {
                continue;
            }
            // Never bid more than the club has, and never below what the seller is asking.
            double asking = transferByPlayerId.get(targetPlayer.getId()) == null
                    ? targetPlayer.getPlayerValue()
                    : transferByPlayerId.get(targetPlayer.getId()).getAskingPrice();
            double buyerBudget = buyerTeam.getBudget() == null ? 0.0 : buyerTeam.getBudget();
            offerPrice = round2(Math.min(offerPrice, buyerBudget));
            if (asking > 0 && offerPrice < asking) {
                // Short of the asking price, so this is a bid rather than a deal. The seller can
                // still take it to the board; nothing is completed here.
                offerPrice = round2(asking * 0.92);
            }
            if (buyerBudget + 0.0001 < offerPrice) {
                continue;
            }
            if (buyerTeam.getId() != null
                    && humanManagedTeamIds.contains(buyerTeam.getId())
                    && needsInterest(buyerTeam, targetPlayer)) {
                recordIncomingInterest(buyerTeam, targetPlayer, offerPrice, asking, transferByPlayerId);
            } else {
                recordIncomingInterest(buyerTeam, targetPlayer, offerPrice, asking, transferByPlayerId);
            }
        }
    }

    /** Whether a club would bother with this player at all. */
    private boolean needsInterest(Team club, Player target) {
        return clubNeeds.interest(club, target) > 0;
    }

    /**
     * Picks a buyer in proportion to appetite, so a club with a genuine gap usually wins the player
     * it actually needs rather than losing him to a richer club with a thinner one.
     */
    private Team weightedBuyer(List<Team> buyers, Player target) {
        double total = 0;
        for (Team buyer : buyers) {
            total += clubNeeds.interest(buyer, target);
        }
        if (total <= 0) {
            return null;
        }
        double roll = nextRandomDouble() * total;
        double running = 0;
        for (Team buyer : buyers) {
            running += clubNeeds.interest(buyer, target);
            if (roll <= running) {
                return buyer;
            }
        }
        return buyers.get(buyers.size() - 1);
    }

    /** Records a bid against the listing, keeping the existing interest bookkeeping. */
    private void recordIncomingInterest(Team buyerTeam, Player targetPlayer, double offerPrice,
                                        double asking, Map<Long, Transfer> transferByPlayerId) {
        Team sellerTeam = requirePlayerTeam(targetPlayer);
        Transfer transfer = transferByPlayerId.get(targetPlayer.getId());
        if (transfer == null && targetPlayer.getId() != null) {
            transfer = transferRepository.findByPlayerId(targetPlayer.getId()).orElse(null);
        }
        Transfer updated;
        if (isActiveListing(transfer)) {
            updated = transfer;
        } else {
            updated = transfer == null ? new Transfer() : transfer;
            updated.setPlayer(targetPlayer);
            updated.setSellerTeam(sellerTeam);
            updated.setBuyerTeam(null);
            updated.setStatus(TransferStatus.OFFER_RECEIVED);
            updated.setAgreedPrice(null);
            updated.setCompletedAt(null);
            updated.setAskingPrice(Math.max(1.0, asking));
            updated.setListedAt(LocalDateTime.now());
            if (updated.getInterestedTeams() == null) {
                updated.setInterestedTeams(new HashSet<>());
            } else {
                updated.getInterestedTeams().clear();
            }
        }

        replaceInterestFromClub(updated, buyerTeam.getName(), offerPrice);
        Transfer saved = transferRepository.save(updated);
        transferByPlayerId.put(targetPlayer.getId(), saved);
    }

    private int countOpenTransfersForSeller(Long sellerTeamId, Map<Long, Transfer> transferByPlayerId) {
        if (sellerTeamId == null) {
            return 0;
        }
        return (int) transferByPlayerId.values().stream()
                .filter(Objects::nonNull)
                .filter(transfer -> transfer.getSellerTeam() != null && Objects.equals(transfer.getSellerTeam().getId(), sellerTeamId))
                .filter(this::hasOpenTransferRecord)
                .count();
    }

    private boolean hasOpenTransferRecord(Transfer transfer) {
        if (transfer == null || transfer.getPlayer() == null) {
            return false;
        }
        if (transfer.getBuyerTeam() != null) {
            return false;
        }
        return transfer.getStatus() == TransferStatus.LISTED || transfer.getStatus() == TransferStatus.OFFER_RECEIVED;
    }

    private void replaceInterestFromClub(Transfer transfer, String clubName, double price) {
        if (transfer.getInterestedTeams() == null) {
            transfer.setInterestedTeams(new HashSet<>());
        }
        String normalizedClub = String.valueOf(clubName == null ? "" : clubName).trim();
        transfer.getInterestedTeams().removeIf(existing -> {
            String value = existing == null ? "" : existing.trim();
            return !normalizedClub.isBlank() && value.regionMatches(true, 0, normalizedClub, 0, normalizedClub.length());
        });
        transfer.getInterestedTeams().add(normalizedClub + " offered €" + Math.round(price));
    }

    private OfferDetails extractBestOffer(Transfer transfer) {
        return sortedInterests(transfer).stream()
                .filter(this::isOfferEntry)
                .map(this::parseOfferDetails)
                .filter(Objects::nonNull)
                .max(Comparator.comparingDouble(OfferDetails::price))
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "NO_VALID_OFFERS",
                        "There are no valid incoming offers to accept."));
    }

    private OfferResolution resolveBestAcceptableOffer(Transfer transfer) {
        List<OfferResolution> validOffers = sortedInterests(transfer).stream()
                .filter(this::isOfferEntry)
                .map(this::parseOfferDetails)
                .filter(Objects::nonNull)
                .map(offer -> resolveOffer(transfer, offer))
                .filter(Objects::nonNull)
                .filter(offer -> canBuyerAfford(offer.buyerTeam(), offer.offer().price()))
                .sorted(Comparator.comparingDouble((OfferResolution value) -> value.offer().price()).reversed())
                .toList();

        if (!validOffers.isEmpty()) {
            return validOffers.getFirst();
        }

        purgeInvalidOffers(transfer);
        throw new ApiException(HttpStatus.CONFLICT, "BUYER_BUDGET_CHANGED",
                "None of the current offers can be completed because the buying club no longer has enough budget.");
    }

    private OfferResolution resolveOffer(Transfer transfer, OfferDetails offer) {
        // LEGACY OFFER PATH. The offers being read here are prose strings from
        // Transfer.interestedTeams, which carry a club NAME and nothing else - so resolving the
        // buyer by name is all this path can do. findByName returns Optional<Team> and would THROW
        // on a duplicate name, and two clubs sharing a name is explicitly allowed, so the safe
        // list-based lookup is used and the ambiguity is logged rather than fatal.
        //
        // The real fix is NegotiationService, which holds the buying club as a foreign key and
        // never resolves identity from a label. New code should use it; migrating these legacy
        // strings is tracked in sprintBacklog.md.
        Team buyerTeam = resolveClubByName(offer.clubName());
        Team sellerTeam = transfer.getSellerTeam() != null ? transfer.getSellerTeam() : requirePlayerTeam(transfer.getPlayer());
        if (buyerTeam == null || Objects.equals(sellerTeam.getId(), buyerTeam.getId())) {
            return null;
        }
        return new OfferResolution(offer, buyerTeam);
    }

    /**
     * Resolves a club by name without crashing on a duplicate.
     *
     * <p>Returns the lowest id on ambiguity, which is deterministic, and says so in the log rather
     * than pretending the lookup was unambiguous.
     */
    private Team resolveClubByName(String clubName) {
        if (clubName == null || clubName.isBlank()) return null;
        java.util.List<Team> matches = teamRepository.findAllByNameIgnoreCase(clubName.trim());
        if (matches.isEmpty()) return null;
        if (matches.size() > 1) {
            matches.stream().min(Comparator.comparing(t -> t.getId() == null ? Long.MAX_VALUE : t.getId()))
                    .ifPresent(t -> log.warn("Club name '{}' matches {} clubs; using id {}. "
                            + "Identities must be carried as ids, not names.",
                            clubName, matches.size(), t.getId()));
            return matches.stream()
                    .min(Comparator.comparing(t -> t.getId() == null ? Long.MAX_VALUE : t.getId()))
                    .orElse(null);
        }
        return matches.getFirst();
    }

    private boolean canBuyerAfford(Team buyerTeam, double price) {
        double buyerBudget = buyerTeam.getBudget() == null ? 0.0 : buyerTeam.getBudget();
        return buyerBudget + 0.0001 >= price;
    }

    private void purgeInvalidOffers(Transfer transfer) {
        if (transfer.getInterestedTeams() == null || transfer.getInterestedTeams().isEmpty()) {
            return;
        }

        Set<String> validEntries = transfer.getInterestedTeams().stream()
                .filter(Objects::nonNull)
                .filter(raw -> {
                    if (!isOfferEntry(raw)) {
                        return true;
                    }
                    OfferDetails offer = parseOfferDetails(raw);
                    if (offer == null) {
                        return false;
                    }
                    OfferResolution resolution = resolveOffer(transfer, offer);
                    return resolution != null && canBuyerAfford(resolution.buyerTeam(), resolution.offer().price());
                })
                .collect(Collectors.toCollection(HashSet::new));

        transfer.setInterestedTeams(validEntries);
        if (!hasOpenOffer(transfer) && transfer.getStatus() == TransferStatus.OFFER_RECEIVED) {
            transfer.setStatus(TransferStatus.CANCELLED);
            transfer.setCompletedAt(LocalDateTime.now());
        }
        transferRepository.save(transfer);
    }

    private OfferDetails parseOfferDetails(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return null;
        }
        String marker = " offered €";
        int splitIndex = rawValue.toLowerCase().indexOf(marker);
        if (splitIndex < 0) {
            return null;
        }
        String clubName = rawValue.substring(0, splitIndex).trim();
        String priceText = rawValue.substring(splitIndex + marker.length()).replaceAll("[^0-9.]", "").trim();
        if (clubName.isBlank() || priceText.isBlank()) {
            return null;
        }
        try {
            return new OfferDetails(clubName, Math.max(1.0, Double.parseDouble(priceText)));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private boolean isOfferEntry(String rawValue) {
        return rawValue != null && rawValue.toLowerCase().contains(" offered ");
    }

    private boolean isOfferAccepted(Player player, Team sellerTeam, Team buyerTeam, double price) {
        double baseValue = Math.max(1.0, player.getPlayerValue());
        double ratio = price / baseValue;
        double acceptanceChance = 0.18;
        if (ratio >= 0.85) acceptanceChance += 0.16;
        if (ratio >= 1.0) acceptanceChance += 0.22;
        if (ratio >= 1.1) acceptanceChance += 0.14;
        if (ratio >= 1.2) acceptanceChance += 0.08;
        if (player.getAge() <= 21) acceptanceChance -= 0.05;

        double sellerRep = sellerTeam.getReputation() == null ? 50.0 : sellerTeam.getReputation();
        double buyerRep = buyerTeam.getReputation() == null ? 50.0 : buyerTeam.getReputation();
        if (buyerRep + 6.0 < sellerRep) {
            acceptanceChance -= 0.06;
        }

        acceptanceChance = Math.max(0.12, Math.min(0.82, acceptanceChance));
        return nextRandomDouble() < acceptanceChance;
    }

    private TransferDTO buildOfferResponseDto(Player player,
                                              Team sellerTeam,
                                              Team buyerTeam,
                                              double price,
                                              Transfer transfer,
                                              Long viewerTeamId,
                                              boolean accepted,
                                              String actionMessage) {
        TransferDTO dto;
        if (transfer != null && transfer.getPlayer() != null) {
            dto = toTransferDto(transfer, viewerTeamId);
        } else {
            dto = new TransferDTO();
            dto.setPlayerId(player.getId());
            dto.setPlayerName(player.getName());
            dto.setPosition(player.getPosition() != null ? player.getPosition().name() : null);
            dto.setAge(player.getAge());
            dto.setRating(player.getRating());
            dto.setPlayerValue(player.getPlayerValue());
            dto.setSellerTeamId(sellerTeam.getId());
            dto.setSellerTeamName(sellerTeam.getName());
            dto.setBuyerTeamId(buyerTeam.getId());
            dto.setBuyerTeamName(buyerTeam.getName());
        }
        dto.setAgreedPrice(price);
        dto.setOfferAccepted(accepted);
        dto.setActionMessage(actionMessage);
        return dto;
    }

    private <T> T randomItem(List<T> items) {
        return items.get(nextRandomInt(items.size()));
    }

    protected double nextRandomDouble() {
        return random.nextDouble();
    }

    protected int nextRandomInt(int bound) {
        return random.nextInt(bound);
    }

    private String buildPlayerSummary(PlayerTransferStatusDTO dto) {
        if (dto.isListed()) {
            String base = dto.getAskingPrice() == null
                    ? "Player is on the transfer list."
                    : "Player is on the transfer list for €" + Math.round(dto.getAskingPrice()) + ".";
            if (!dto.getInterestedTeams().isEmpty()) {
                return base + " Active interest: " + dto.getInterestedTeams().size() + " offer(s).";
            }
            return base;
        }
        if (TransferStatus.OFFER_RECEIVED.name().equals(dto.getStatus()) && !dto.getInterestedTeams().isEmpty()) {
            if (dto.getInterestedTeams().size() == 1) {
                return "Incoming offer received: " + dto.getInterestedTeams().get(0) + ".";
            }
            return dto.getInterestedTeams().size() + " incoming offers received.";
        }
        if (TransferStatus.COMPLETED.name().equals(dto.getStatus()) && dto.getBuyerTeamName() != null && dto.getAgreedPrice() != null) {
            return "Last move: sold to " + dto.getBuyerTeamName() + " for €" + Math.round(dto.getAgreedPrice()) + ".";
        }
        return "Player is not currently transfer listed.";
    }

    private TransferDTO toTransferDto(Transfer transfer, Long viewerTeamId) {
        Player player = transfer.getPlayer();
        Long sellerTeamId = transfer.getSellerTeam() != null ? transfer.getSellerTeam().getId() : null;
        boolean ownedByViewer = viewerTeamId != null && Objects.equals(sellerTeamId, viewerTeamId);
        boolean hasOpenOffer = hasOpenOffer(transfer);

        TransferDTO dto = new TransferDTO();
        dto.setId(transfer.getId());
        dto.setPlayerId(player != null ? player.getId() : null);
        dto.setPlayerName(player != null ? player.getName() : null);
        dto.setPosition(player != null && player.getPosition() != null ? player.getPosition().name() : null);
        dto.setAge(player != null ? player.getAge() : null);
        dto.setRating(player != null ? player.getRating() : null);
        dto.setPlayerValue(player != null ? player.getPlayerValue() : null);
        dto.setSellerTeamId(sellerTeamId);
        dto.setSellerTeamName(transfer.getSellerTeam() != null ? transfer.getSellerTeam().getName() : null);
        dto.setBuyerTeamId(transfer.getBuyerTeam() != null ? transfer.getBuyerTeam().getId() : null);
        dto.setBuyerTeamName(transfer.getBuyerTeam() != null ? transfer.getBuyerTeam().getName() : null);
        dto.setAskingPrice(transfer.getAskingPrice());
        dto.setAgreedPrice(transfer.getAgreedPrice());
        dto.setStatus(transfer.getStatus() != null ? transfer.getStatus().name() : TransferStatus.LISTED.name());
        dto.setListedAt(transfer.getListedAt());
        dto.setCompletedAt(transfer.getCompletedAt());
        dto.setInterestedTeams(sortedInterests(transfer));
        dto.setOwnedByViewer(ownedByViewer);
        dto.setBuyableByViewer(viewerTeamId != null && !ownedByViewer && isActiveListing(transfer));
        dto.setRemovalAllowed(ownedByViewer && isActiveListing(transfer) && sortedInterests(transfer).isEmpty());
        dto.setCanAcceptOffer(ownedByViewer && hasOpenOffer);
        dto.setCanRejectOffer(ownedByViewer && hasOpenOffer);
        return dto;
    }

    private record OfferDetails(String clubName, double price) {
    }

    private record OfferResolution(OfferDetails offer, Team buyerTeam) {
    }
}
