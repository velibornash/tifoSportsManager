package org.example.footballmanager.service;

import jakarta.transaction.Transactional;
import org.example.footballmanager.dto.transfer.PlayerTransferStatusDTO;
import org.example.footballmanager.dto.transfer.TeamTransferOverviewDTO;
import org.example.footballmanager.dto.transfer.TransferDTO;
import org.example.footballmanager.exception.ApiException;
import org.example.footballmanager.model.Player;
import org.example.footballmanager.model.Team;
import org.example.footballmanager.model.Transfer;
import org.example.footballmanager.model.TransferStatus;
import org.example.footballmanager.repository.PlayerRepository;
import org.example.footballmanager.repository.TeamRepository;
import org.example.footballmanager.repository.TransferRepository;
import org.example.footballmanager.repository.UserRepository;
import org.example.footballmanager.util.players.SquadNumberAssigner;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
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
                           SquadNumberAssigner squadNumberAssigner) {
        this.transferRepository = transferRepository;
        this.playerRepository = playerRepository;
        this.teamRepository = teamRepository;
        this.userRepository = userRepository;
        this.squadNumberAssigner = squadNumberAssigner;
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

    @Transactional
    public List<TransferDTO> getAllTransfers(Long viewerTeamId) {
        return transferRepository.findByStatusAndBuyerTeamIsNullOrderByListedAtDesc(TransferStatus.LISTED).stream()
                .sorted(Comparator.comparing(Transfer::getListedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(transfer -> toTransferDto(transfer, viewerTeamId))
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
        dto.setCanRemove(ownedByViewer && listed && sortedInterests(transfer).isEmpty());
        dto.setCanBuyListed(listed && viewerTeamId != null && !ownedByViewer);
        dto.setCanDirectBuy(viewerTeamId != null && !ownedByViewer && !listed);
        dto.setCanAcceptOffer(ownedByViewer && openOffer);
        dto.setCanRejectOffer(ownedByViewer && openOffer);
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
        if (!sortedInterests(transfer).isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "ACTIVE_INTEREST",
                    "Cannot remove this player from the transfer list while another club has active interest.");
        }
        transfer.setStatus(TransferStatus.CANCELLED);
        transfer.setCompletedAt(LocalDateTime.now());
        transferRepository.save(transfer);
    }

    @Transactional
    public TransferDTO buyListedPlayer(Long playerId, Long buyerTeamId, Double offeredPrice) {
        Transfer transfer = getActiveTransfer(playerId);
        Team buyerTeam = loadTeam(buyerTeamId);
        double price = normalizePrice(offeredPrice, transfer.getAskingPrice());
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
        double price = normalizePrice(offeredPrice, fallbackPrice);

        double buyerBudget = buyerTeam.getBudget() == null ? 0.0 : buyerTeam.getBudget();
        if (buyerBudget + 0.0001 < price) {
            throw new ApiException(HttpStatus.CONFLICT, "INSUFFICIENT_BUDGET",
                    "Your club does not have enough budget for this offer.");
        }

        if (isActiveListing(transfer)) {
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

        OfferDetails bestOffer = extractBestOffer(transfer);
        Team buyerTeam = teamRepository.findByName(bestOffer.clubName())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "BUYER_NOT_FOUND",
                        "The club behind this offer could not be found."));

        TransferDTO dto = toTransferDto(completeTransfer(transfer.getPlayer(), buyerTeam, bestOffer.price(), transfer), actingTeamId);
        dto.setOfferAccepted(true);
        dto.setActionMessage("Offer accepted. " + transfer.getPlayer().getName()
                + " joins " + buyerTeam.getName() + " for EUR " + Math.round(bestOffer.price()) + ".");
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
        return transfer.getBuyerTeam() == null && sortedInterests(transfer).stream().anyMatch(this::isOfferEntry);
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

    private double normalizePrice(Double requestedPrice, double fallbackPrice) {
        double resolved = requestedPrice == null ? fallbackPrice : requestedPrice;
        return Math.max(1.0, resolved);
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

    private void maybeCreateIncomingOffer(Set<Long> humanManagedTeamIds, List<Team> aiTeams, Map<Long, Transfer> transferByPlayerId) {
        if (humanManagedTeamIds.isEmpty() || aiTeams.isEmpty() || nextRandomDouble() > 0.68) {
            return;
        }

        List<Player> humanPlayers = playerRepository.findByTeamIdIn(humanManagedTeamIds.stream().toList()).stream()
                .filter(Objects::nonNull)
                .filter(player -> player.getId() != null)
                .filter(player -> player.getTeam() != null && player.getTeam().getId() != null)
                .toList();
        if (humanPlayers.isEmpty()) {
            return;
        }

        List<Player> listedPlayers = humanPlayers.stream()
                .filter(player -> isActiveListing(transferByPlayerId.get(player.getId())))
                .toList();
        List<Player> nonListedPlayers = humanPlayers.stream()
                .filter(player -> !isActiveListing(transferByPlayerId.get(player.getId())))
                .toList();

        Player targetPlayer;
        if (!listedPlayers.isEmpty() && (nonListedPlayers.isEmpty() || nextRandomDouble() < 0.78)) {
            targetPlayer = randomItem(listedPlayers);
        } else if (!nonListedPlayers.isEmpty()) {
            targetPlayer = randomItem(nonListedPlayers);
        } else {
            return;
        }

        Team sellerTeam = requirePlayerTeam(targetPlayer);
        List<Team> candidateBuyers = aiTeams.stream()
                .filter(team -> !Objects.equals(team.getId(), sellerTeam.getId()))
                .toList();
        if (candidateBuyers.isEmpty()) {
            return;
        }

        Team buyerTeam = randomItem(candidateBuyers);
        double offerPrice = round2(Math.max(1.0, targetPlayer.getPlayerValue()) * (0.80 + nextRandomDouble() * 0.40));
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
            updated.setAskingPrice(Math.max(1.0, targetPlayer.getPlayerValue()));
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
}
