package org.tifo.americanfootballmanager.service;

import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.tifo.americanfootballmanager.dto.AfPlayerDTO;
import org.tifo.americanfootballmanager.model.AfPlayer;
import org.tifo.americanfootballmanager.model.AfTeam;
import org.tifo.americanfootballmanager.repository.AfPlayerRepository;
import org.tifo.americanfootballmanager.repository.AfTeamRepository;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class AfTransferService {

    private static final long BUY_PRICE_PER_OVR = 50_000L;
    private static final long SELL_PRICE_PER_OVR = 40_000L;
    private static final long MIN_PRICE = 10_000L;

    private final AfTeamRepository teamRepository;
    private final AfPlayerRepository playerRepository;

    public AfTransferService(AfTeamRepository teamRepository, AfPlayerRepository playerRepository) {
        this.teamRepository = teamRepository;
        this.playerRepository = playerRepository;
    }

    public List<AfPlayerDTO> getMarketPlayers(Long userTeamId, int limit) {
        return playerRepository.findAll().stream()
                .filter(p -> p.getTeam() != null && !p.getTeam().getId().equals(userTeamId))
                .sorted((a, b) -> Integer.compare(b.getOverall(), a.getOverall()))
                .limit(limit)
                .map(this::toMarketDTO)
                .collect(Collectors.toList());
    }

    @Transactional
    public Map<String, Object> buyPlayer(Long userTeamId, Long playerId) {
        AfPlayer player = playerRepository.findById(playerId)
                .orElseThrow(() -> new IllegalArgumentException("Player not found: " + playerId));
        AfTeam seller = player.getTeam();
        if (seller == null) throw new IllegalArgumentException("Player has no team");
        if (seller.getId().equals(userTeamId)) throw new IllegalArgumentException("Player is already on your team");
        AfTeam buyer = teamRepository.findById(userTeamId)
                .orElseThrow(() -> new IllegalArgumentException("Your team not found: " + userTeamId));

        long price = Math.max(MIN_PRICE, (long) player.getOverall() * BUY_PRICE_PER_OVR);
        if (buyer.getBudget() == null || buyer.getBudget() < price) {
            throw new IllegalArgumentException("Insufficient cap space (need " + price + ")");
        }

        player.setTeam(buyer);
        buyer.setBudget(buyer.getBudget() - price);
        seller.setBudget((seller.getBudget() == null ? 0L : seller.getBudget()) + price);

        playerRepository.save(player);
        teamRepository.save(buyer);
        teamRepository.save(seller);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ok", true);
        resp.put("playerId", player.getId());
        resp.put("playerName", player.getName());
        resp.put("price", price);
        resp.put("budget", buyer.getBudget());
        return resp;
    }

    @Transactional
    public Map<String, Object> sellPlayer(Long userTeamId, Long playerId) {
        AfPlayer player = playerRepository.findById(playerId)
                .orElseThrow(() -> new IllegalArgumentException("Player not found: " + playerId));
        AfTeam seller = player.getTeam();
        if (seller == null || !seller.getId().equals(userTeamId)) {
            throw new IllegalArgumentException("Player is not on your team");
        }
        AfTeam buyer = teamRepository.findAll().stream()
                .filter(t -> !t.getId().equals(userTeamId))
                .min(java.util.Comparator.comparingInt(t -> t.getPlayers().size()))
                .orElseThrow(() -> new IllegalArgumentException("No destination club available"));

        long price = Math.max(MIN_PRICE, (long) player.getOverall() * SELL_PRICE_PER_OVR);

        player.setTeam(buyer);
        seller.setBudget((seller.getBudget() == null ? 0L : seller.getBudget()) + price);

        playerRepository.save(player);
        teamRepository.save(seller);
        teamRepository.save(buyer);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ok", true);
        resp.put("playerId", player.getId());
        resp.put("playerName", player.getName());
        resp.put("destination", buyer.getName());
        resp.put("price", price);
        resp.put("budget", seller.getBudget());
        return resp;
    }

    private AfPlayerDTO toMarketDTO(AfPlayer p) {
        AfPlayerDTO dto = new AfPlayerDTO();
        dto.setId(p.getId());
        dto.setName(p.getName());
        dto.setPosition(p.getPosition().name());
        dto.setTeamId(p.getTeam().getId());
        dto.setTeamName(p.getTeam().getName());
        dto.setTeamColor(p.getTeam().getColor());
        dto.setOverall(p.getOverall());
        return dto;
    }
}
