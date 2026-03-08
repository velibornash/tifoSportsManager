package org.example.footballmanager.service;

import jakarta.transaction.Transactional;
import org.example.footballmanager.dto.transfer.PlayerTransferStatusDTO;
import org.example.footballmanager.dto.transfer.TeamTransferOverviewDTO;
import org.example.footballmanager.dto.transfer.TransferDTO;
import org.example.footballmanager.model.Player;
import org.example.footballmanager.model.Team;
import org.example.footballmanager.model.Transfer;
import org.example.footballmanager.model.TransferStatus;
import org.example.footballmanager.repository.PlayerRepository;
import org.example.footballmanager.repository.TeamRepository;
import org.example.footballmanager.repository.TransferRepository;
import org.example.footballmanager.util.players.SquadNumberAssigner;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

@Service
public class TransferService {

    private final TransferRepository transferRepository;
    private final PlayerRepository playerRepository;
    private final TeamRepository teamRepository;
    private final SquadNumberAssigner squadNumberAssigner;

    public TransferService(TransferRepository transferRepository,
                           PlayerRepository playerRepository,
                           TeamRepository teamRepository,
                           SquadNumberAssigner squadNumberAssigner) {
        this.transferRepository = transferRepository;
        this.playerRepository = playerRepository;
        this.teamRepository = teamRepository;
        this.squadNumberAssigner = squadNumberAssigner;
    }

    @Transactional
    public Transfer listPlayerForTransfer(Long playerId, double askingPrice) {
        Player player = playerRepository.findById(playerId)
                .orElseThrow(() -> new RuntimeException("Player not found"));

        Team sellerTeam = requirePlayerTeam(player);
        return listPlayerForTransferEntity(player, sellerTeam.getId(), askingPrice);
    }

    @Transactional
    public TransferDTO listPlayerForTransfer(Long playerId, Long actingTeamId, double askingPrice) {
        Player player = playerRepository.findById(playerId)
                .orElseThrow(() -> new RuntimeException("Player not found"));
        return toTransferDto(listPlayerForTransferEntity(player, actingTeamId, askingPrice), actingTeamId);
    }

    @Transactional
    public List<TransferDTO> getAllTransfers(Long viewerTeamId) {
        return transferRepository.findAll().stream()
                .filter(this::isActiveListing)
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
        List<TransferDTO> listed = transferRepository.findBySellerTeamId(teamId).stream()
                .filter(this::isActiveListing)
                .sorted(Comparator.comparing(Transfer::getListedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(transfer -> toTransferDto(transfer, viewerTeamId))
                .toList();
        dto.setListedPlayers(listed);
        dto.setListedCount(listed.size());
        return dto;
    }

    @Transactional
    public PlayerTransferStatusDTO getPlayerTransferStatus(Long playerId, Long viewerTeamId) {
        Player player = playerRepository.findById(playerId)
                .orElseThrow(() -> new RuntimeException("Player not found"));
        Transfer transfer = transferRepository.findByPlayerId(playerId).orElse(null);

        Long currentTeamId = player.getTeam() != null ? player.getTeam().getId() : null;
        boolean ownedByViewer = viewerTeamId != null && Objects.equals(currentTeamId, viewerTeamId);
        boolean listed = isActiveListing(transfer);

        PlayerTransferStatusDTO dto = new PlayerTransferStatusDTO();
        dto.setPlayerId(player.getId());
        dto.setCurrentTeamId(currentTeamId);
        dto.setCurrentTeamName(player.getTeam() != null ? player.getTeam().getName() : null);
        dto.setListed(listed);
        dto.setStatus(transfer != null && transfer.getStatus() != null ? transfer.getStatus().name() : (listed ? TransferStatus.LISTED.name() : null));
        dto.setAskingPrice(transfer != null ? transfer.getAskingPrice() : null);
        dto.setAgreedPrice(transfer != null ? transfer.getAgreedPrice() : null);
        dto.setListedAt(transfer != null ? transfer.getListedAt() : null);
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
        dto.setCanDirectBuy(viewerTeamId != null && !ownedByViewer);
        dto.setSummary(buildPlayerSummary(dto));
        return dto;
    }

    @Transactional
    public TransferDTO addInterest(Long playerId, Long viewerTeamId, String clubName) {
        Transfer transfer = getActiveTransfer(playerId);
        Long sellerTeamId = transfer.getSellerTeam() != null ? transfer.getSellerTeam().getId() : null;
        if (viewerTeamId != null && Objects.equals(sellerTeamId, viewerTeamId)) {
            throw new RuntimeException("Owning club cannot register interest in its own player");
        }

        String resolvedClubName = clubName;
        if ((resolvedClubName == null || resolvedClubName.isBlank()) && viewerTeamId != null) {
            resolvedClubName = loadTeam(viewerTeamId).getName();
        }

        if (resolvedClubName == null || resolvedClubName.isBlank()) {
            throw new RuntimeException("Club name is required to register interest");
        }

        transfer.getInterestedTeams().add(resolvedClubName.trim());
        return toTransferDto(transferRepository.save(transfer), viewerTeamId);
    }

    @Transactional
    public void removeFromTransferList(Long playerId, Long actingTeamId) {
        Transfer transfer = getActiveTransfer(playerId);
        Team sellerTeam = transfer.getSellerTeam() != null ? transfer.getSellerTeam() : requirePlayerTeam(transfer.getPlayer());
        if (actingTeamId != null && !Objects.equals(sellerTeam.getId(), actingTeamId)) {
            throw new RuntimeException("Only the owning club can remove this player from the transfer list");
        }
        if (!sortedInterests(transfer).isEmpty()) {
            throw new RuntimeException("Cannot remove player from transfer list while a bid/interest exists");
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
                .orElseThrow(() -> new RuntimeException("Player not found"));
        Team buyerTeam = loadTeam(buyerTeamId);
        Transfer transfer = transferRepository.findByPlayerId(playerId).orElseGet(Transfer::new);
        transfer.setPlayer(player);
        double fallbackPrice = transfer.getAskingPrice() > 0 ? transfer.getAskingPrice() : Math.max(1.0, player.getPlayerValue());
        double price = normalizePrice(offeredPrice, fallbackPrice);
        return toTransferDto(completeTransfer(player, buyerTeam, price, transfer), buyerTeamId);
    }

    private Transfer listPlayerForTransferEntity(Player player, Long actingTeamId, double askingPrice) {
        Team sellerTeam = requirePlayerTeam(player);
        if (actingTeamId != null && !Objects.equals(sellerTeam.getId(), actingTeamId)) {
            throw new RuntimeException("Only the owning club can list this player");
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
            throw new RuntimeException("Buyer club must be different from seller club");
        }

        double buyerBudget = buyerTeam.getBudget() == null ? 0.0 : buyerTeam.getBudget();
        if (buyerBudget + 0.0001 < price) {
            throw new RuntimeException("Buyer club does not have enough budget");
        }

        buyerTeam.setBudget(round2(buyerBudget - price));
        double sellerBudget = sellerTeam.getBudget() == null ? 0.0 : sellerTeam.getBudget();
        sellerTeam.setBudget(round2(sellerBudget + price));
        teamRepository.save(sellerTeam);
        teamRepository.save(buyerTeam);

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
                .orElseThrow(() -> new RuntimeException("Transfer not found"));
        if (!isActiveListing(transfer)) {
            throw new RuntimeException("Player is not currently transfer listed");
        }
        return transfer;
    }

    private boolean isActiveListing(Transfer transfer) {
        if (transfer == null || transfer.getPlayer() == null) {
            return false;
        }
        if (transfer.getStatus() == TransferStatus.CANCELLED || transfer.getStatus() == TransferStatus.COMPLETED) {
            return false;
        }
        return transfer.getBuyerTeam() == null;
    }

    private Team loadTeam(Long teamId) {
        if (teamId == null) {
            throw new RuntimeException("Team id is required");
        }
        return teamRepository.findById(teamId)
                .orElseThrow(() -> new RuntimeException("Team not found"));
    }

    private Team requirePlayerTeam(Player player) {
        if (player.getTeam() == null || player.getTeam().getId() == null) {
            throw new RuntimeException("Player is not assigned to a club");
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

    private String buildPlayerSummary(PlayerTransferStatusDTO dto) {
        if (dto.isListed()) {
            return dto.getAskingPrice() == null
                    ? "Player is on the transfer list."
                    : "Player is on the transfer list for €" + Math.round(dto.getAskingPrice()) + ".";
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
        return dto;
    }
}