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
import org.example.footballmanager.newLogic.model.TransferOffer;
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
    private final NegotiationService negotiation;


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
                           ClubNeedService clubNeeds,
                           NegotiationService negotiation) {
        this.clubNeeds = clubNeeds;
        this.negotiation = negotiation;
        this.transferRepository = transferRepository;
        this.playerRepository = playerRepository;
        this.teamRepository = teamRepository;
        this.userRepository = userRepository;
        this.squadNumberAssigner = squadNumberAssigner;
        this.transferWindows = transferWindows;
    }

    /**
     * The current transfer record for a player, whatever state it is in.
     *
     * <p>A read rather than a private helper, because "where has this player got to" is a question
     * the transfer screen, the settlement and a test all need to ask, and answering it three
     * different ways is how they drift apart.
     */
    @Transactional
    public Transfer findTransferForPlayer(Long playerId) {
        if (playerId == null) {
            return null;
        }
        return transferRepository.findByPlayerId(playerId).orElse(null);
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
    /**
     * The transfer market, scoped to the viewer's own country (owner, 2026-09-28).
     *
     * <p>It used to return <b>every listed player in the game</b>, which is a country system with no
     * countries in it: a manager in Brazil browsed a worldwide market, and a domestic league's transfer
     * market is a real thing with real rules and a real window.
     *
     * <p><b>The country filters the view; it does not restrict the game.</b> Nothing here stops a
     * manager signing a player from another country — the owner was explicit that bringing a foreigner
     * in is allowed. This is the market <i>on screen</i>, not the market they may shop in.
     *
     * <p>A null country shows an <b>empty</b> market rather than the whole world. Defaulting to
     * everything is how a scope that was supposed to exist quietly disappears, and a manager whose own
     * country cannot be read should see that something is wrong rather than be handed every player in
     * the game as though it were normal.
     */
    public List<TransferDTO> getAllTransfers(Long viewerTeamId, String viewerCountryCode) {
        if (viewerCountryCode == null || viewerCountryCode.isBlank()) {
            return List.of();
        }
        List<TransferDTO> out = new ArrayList<>();
        for (Transfer transfer : transferRepository
                .findByStatusAndBuyerTeamIsNullOrderByListedAtDesc(TransferStatus.LISTED)) {
            if (!inCountry(transfer.getSellerTeam(), viewerCountryCode)) {
                continue;
            }
            out.add(toTransferDto(transfer, viewerTeamId));
        }

        // Unlisted players are shown as scout reports: no asking price, and no pretending they are
        // for sale. The manager may still approach one.
        for (Player player : scoutedUnlisted(viewerTeamId, viewerCountryCode)) {
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
    public List<Player> scoutedUnlisted(Long viewerTeamId, String viewerCountryCode) {
        if (viewerTeamId == null) return List.of();
        if (viewerCountryCode == null || viewerCountryCode.isBlank()) return List.of();
        return teamRepository.findClubTeamsForOperations().stream()
                .filter(Objects::nonNull)
                .filter(team -> team.getId() != null)
                .filter(team -> !Objects.equals(team.getId(), viewerTeamId))
                .filter(team -> inCountry(team, viewerCountryCode))
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

    /**
     * Whether a club belongs to the viewer's country.
     *
     * <p>Case-insensitive, and a club with no country is <b>not</b> in any country — so it is never
     * shown. Treating "unknown" as "matches" would put every unassigned club in every market.
     */
    private boolean inCountry(Team team, String countryCode) {
        if (team == null || team.getCountry() == null) {
            return false;
        }
        String iso = team.getCountry().getIsoCode();
        return iso != null && iso.equalsIgnoreCase(countryCode);
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
        dto.setInterestedTeams(offerSummaries(transfer));
        dto.setOwnedByViewer(ownedByViewer);
        dto.setCanList(ownedByViewer && !listed);
        dto.setCanRemove(ownedByViewer && listed && !hasPricedOffer(transfer));
        dto.setCanBuyListed(listed && viewerTeamId != null && !ownedByViewer);
        dto.setCanDirectBuy(viewerTeamId != null && !ownedByViewer && !listed);
        dto.setCanAcceptOffer(ownedByViewer && openOffer);
        dto.setCanRejectOffer(ownedByViewer && openOffer);
        // Bare interest entries must not be able to trap a seller on the list (Sprint 0.2).
        dto.setCanClearInterest(ownedByViewer && listed && !offerSummaries(transfer).isEmpty());
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

        if (viewerTeamId == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CLUB_REQUIRED",
                    "The interested club's id is required.");
        }
        Team buyer = loadTeam(viewerTeamId);

        // Interest is a real offer now, with a buyer, a fee, a wage and a length. It used to be a
        // club NAME pushed into a Set<String>, which meant the buyer's identity had to be recovered
        // by parsing prose and two clubs sharing a name were indistinguishable.
        Player target = transfer.getPlayer();
        TransferOffer offer = negotiation.openOffer(transfer, buyer,
                transfer.getAskingPrice() > 0 ? transfer.getAskingPrice() : target.getPlayerValue(),
                target.getEarnings(), 3);
        return toTransferDto(transfer, viewerTeamId);
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
        transferRepository.save(transfer);
    }

    /**
     * Withdraws this club's own offer on a listed player.
     *
     * <p>Identified by club <b>id</b>, not name. The previous version matched a name against prose
     * strings, which could not tell two clubs apart if they shared one — and duplicate names are
     * explicitly allowed in this game.
     *
     * <p>A clean exit matters: without it an interested club can sit on a seller's list and block
     * the delisting, and an AI club has no way to back out of a bid it no longer wants.
     */
    @Transactional
    public TransferDTO withdrawInterest(Long playerId, Long withdrawingTeamId) {
        Transfer transfer = getActiveTransfer(playerId);
        if (withdrawingTeamId == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CLUB_REQUIRED",
                    "The withdrawing club's id is required.");
        }
        Team withdrawing = loadTeam(withdrawingTeamId);

        List<TransferOffer> mine = liveOffers(transfer).stream()
                .filter(o -> o.getBuyerTeam() != null)
                .filter(o -> Objects.equals(o.getBuyerTeam().getId(), withdrawing.getId()))
                .toList();
        if (mine.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "NO_INTEREST_FOUND",
                    withdrawing.getName() + " has no live offer on this player.");
        }
        for (TransferOffer offer : mine) {
            negotiation.withdraw(offer);
        }
        return toTransferDto(transfer, withdrawingTeamId);
    }

    @Transactional
    public TransferDTO clearAllInterest(Long playerId, Long actingTeamId) {
        Transfer transfer = getActiveTransfer(playerId);
        Team sellerTeam = transfer.getSellerTeam() != null ? transfer.getSellerTeam() : requirePlayerTeam(transfer.getPlayer());
        if (actingTeamId != null && !Objects.equals(sellerTeam.getId(), actingTeamId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN",
                    "Only the owning club can clear interest on this player.");
        }

        // Reject the live offers rather than clear a set of strings: an offer is a record with a
        // buyer, a fee and a wage, and pretending it never happened would leave the two disagreeing.
        List<TransferOffer> live = liveOffers(transfer);
        for (TransferOffer offer : live) {
            negotiation.reject(offer);
        }
        int cleared = live.size();
        transfer.setBuyerTeam(null);
        transfer.setStatus(TransferStatus.LISTED);
        transfer.setCompletedAt(null);
        TransferDTO dto = toTransferDto(transferRepository.save(transfer), actingTeamId);
        dto.setOfferAccepted(false);
        dto.setActionMessage("Rejected " + cleared + (cleared == 1 ? " offer." : " offers.")
                + " The player remains on the transfer list.");
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
        // Reject the live offers rather than clear a set of strings: an offer is a record with a
        // buyer, a fee and a wage, and pretending it never happened would leave the two disagreeing.
        List<TransferOffer> live = liveOffers(transfer);
        for (TransferOffer offer : live) {
            negotiation.reject(offer);
        }
        int cleared = live.size();
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

        // The highest live offer the buyer can still honour. Real offer records, so the buyer is a
        // foreign key rather than a name parsed back out of a sentence.
        TransferOffer best = liveOffers(transfer).stream()
                .filter(o -> o.getBuyerTeam() != null)
                .filter(o -> o.getFee() != null && o.getFee() > 0)
                .filter(o -> canBuyerAfford(o.getBuyerTeam(), o.getFee()))
                .max(Comparator.comparingDouble(TransferOffer::getFee))
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "NO_VALID_OFFERS",
                        "There are no valid incoming offers to accept."));

        negotiation.acceptOffer(transfer.getId(), best.getId());
        Team buyerTeam = best.getBuyerTeam();

        TransferDTO dto = toTransferDto(
                completeTransfer(transfer.getPlayer(), buyerTeam, best.getFee(), transfer), actingTeamId);
        dto.setOfferAccepted(true);
        dto.setActionMessage("Offer accepted. " + transfer.getPlayer().getName()
                + " joins " + buyerTeam.getName() + " for EUR " + Math.round(best.getFee()) + ".");
        return dto;
    }

    @Transactional
    public TransferDTO rejectOffers(Long playerId, Long actingTeamId) {
        Transfer transfer = getOpenOfferTransfer(playerId);
        Team sellerTeam = transfer.getSellerTeam() != null ? transfer.getSellerTeam() : requirePlayerTeam(transfer.getPlayer());
        if (actingTeamId != null && !Objects.equals(sellerTeam.getId(), actingTeamId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Only the owning club can reject incoming offers.");
        }

        // Rejecting means rejecting: the offers become REJECTED records rather than a cleared set,
        // so the thread still shows what was on the table.
        for (TransferOffer offer : liveOffers(transfer)) {
            negotiation.reject(offer);
        }
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
        return transferRepository.save(transfer);
    }

    /**
     * Settles a purchase.
     *
     * <p>This used to contain its own copy of the money and the player move, reachable from four
     * public entry points while {@code NegotiationService} had a second implementation. Two
     * settlement paths on one {@link Transfer} entity is a double-completion waiting to happen.
     *
     * <p>What is left here is the validation this entry point owns — the asking price is a floor,
     * and the club must have the cash — and then the settlement itself, which
     * {@link NegotiationService#settle} performs once for every path.
     */
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
                    "Agreed price must be at least the asking price of EUR " + Math.round(floor) + ".");
        }

        double buyerBudget = buyerTeam.getBudget() == null ? 0.0 : buyerTeam.getBudget();
        if (buyerBudget + 0.0001 < price) {
            throw new ApiException(HttpStatus.CONFLICT, "INSUFFICIENT_BUDGET",
                    "Your club does not have enough budget for this transfer.");
        }

        if (transfer == null || transfer.getId() == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "TRANSFER_NOT_FOUND", "This transfer no longer exists.");
        }

        if (!negotiation.settle(transfer.getId(), buyerTeam, price, player.getEarnings(), null)) {
            throw new ApiException(HttpStatus.CONFLICT, "TRANSFER_NOT_COMPLETED",
                    "The transfer could not be completed. The seller may have withdrawn it, "
                            + "or your club may no longer be able to afford it.");
        }

        Transfer completed = transferRepository.findById(transfer.getId()).orElseThrow();
        player.setSquadNumber(squadNumberAssigner.nextNumberForTeam(buyerTeam, player.getPosition()));
        playerRepository.save(player);
        squadNumberAssigner.assignMissingNumbers(sellerTeam);
        squadNumberAssigner.assignMissingNumbers(buyerTeam);
        return completed;
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
        return liveOffers(transfer).stream().anyMatch(o -> o.getFee() != null && o.getFee() > 0);
    }

    /**
     * The live offers on a transfer, from the real offer records.
     *
     * <p>This replaces a layer that stored interest as prose strings in
     * {@code Transfer.interestedTeams} — {@code "Rival FC offered EUR 900000"} — and then parsed
     * them back to work out who the buyer was. A club's identity cannot be recovered from a label,
     * two clubs may share a name, and the buyer had to be resolved by string comparison on every
     * read. Interest is now a {@code TransferOffer} row with a foreign key, and this returns it.
     */
    private List<TransferOffer> liveOffers(Transfer transfer) {
        if (transfer == null || transfer.getId() == null) {
            return List.of();
        }
        return negotiation.liveOffers(transfer.getId());
    }

    /** Human-readable one-liners for the offers on a transfer, for the transfer screen. */
    private List<String> offerSummaries(Transfer transfer) {
        return liveOffers(transfer).stream()
                .map(offer -> {
                    String club = offer.getBuyerTeam() == null ? "A club" : offer.getBuyerTeam().getName();
                    long fee = offer.getFee() == null ? 0 : Math.round(offer.getFee());
                    return club + " offered EUR " + fee;
                })
                .toList();
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
        }

        // A real offer, not a sentence about one. This is what the seller accepts, so the buyer is
        // a foreign key from the moment the bid is made.
        if (updated.getId() != null) {
            // The offer carries a wage as well as a fee, because a deal is three numbers and the
            // seller is deciding on the player's terms too, not just the transfer fee.
            negotiation.openOffer(updated, buyerTeam, offerPrice, targetPlayer.getEarnings() * 1.1, 3);
        }
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

    private boolean canBuyerAfford(Team buyerTeam, double price) {
        double buyerBudget = buyerTeam.getBudget() == null ? 0.0 : buyerTeam.getBudget();
        return buyerBudget + 0.0001 >= price;
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
        dto.setInterestedTeams(offerSummaries(transfer));
        dto.setOwnedByViewer(ownedByViewer);
        dto.setBuyableByViewer(viewerTeamId != null && !ownedByViewer && isActiveListing(transfer));
        dto.setRemovalAllowed(ownedByViewer && isActiveListing(transfer) && liveOffers(transfer).isEmpty());
        dto.setCanAcceptOffer(ownedByViewer && hasOpenOffer);
        dto.setCanRejectOffer(ownedByViewer && hasOpenOffer);
        return dto;
    }
}
