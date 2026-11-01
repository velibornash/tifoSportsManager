package org.tifo.basketballmanager.service;

import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.tifo.basketballmanager.dto.BbPlayerDTO;
import org.tifo.basketballmanager.model.BbPlayer;
import org.tifo.basketballmanager.model.BbTeam;
import org.tifo.basketballmanager.repository.BbPlayerRepository;
import org.tifo.basketballmanager.repository.BbTeamRepository;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class BbTransferService {

    private static final long BUY_PRICE_PER_OVR = 10_000L;
    private static final long SELL_PRICE_PER_OVR = 8_000L;
    private static final long MIN_PRICE = 5_000L;

    private final BbTeamRepository teamRepository;
    private final BbPlayerRepository playerRepository;

    public BbTransferService(BbTeamRepository teamRepository, BbPlayerRepository playerRepository) {
        this.teamRepository = teamRepository;
        this.playerRepository = playerRepository;
    }

    public List<BbPlayerDTO> getMarketPlayers(Long userTeamId, int limit) {
        return playerRepository.findAll().stream()
                .filter(p -> p.getTeam() != null && !p.getTeam().getId().equals(userTeamId))
                .sorted((a, b) -> Integer.compare(b.getOverall(), a.getOverall()))
                .limit(limit)
                .map(this::toMarketDTO)
                .collect(Collectors.toList());
    }

    @Transactional
    public Map<String, Object> buyPlayer(Long userTeamId, Long playerId) {
        BbPlayer player = playerRepository.findById(playerId)
                .orElseThrow(() -> new IllegalArgumentException("Player not found: " + playerId));
        BbTeam seller = player.getTeam();
        if (seller == null) {
            throw new IllegalArgumentException("Player has no team");
        }
        if (seller.getId().equals(userTeamId)) {
            throw new IllegalArgumentException("Player is already on your team");
        }
        BbTeam buyer = teamRepository.findById(userTeamId)
                .orElseThrow(() -> new IllegalArgumentException("Your team not found: " + userTeamId));

        long price = Math.max(MIN_PRICE, (long) player.getOverall() * BUY_PRICE_PER_OVR);
        if (buyer.getBudget() == null || buyer.getBudget() < price) {
            throw new IllegalArgumentException("Insufficient budget (need " + price + ")");
        }

        // Do NOT touch the @OneToMany collections here: bb_teams.players has
        // orphanRemoval=true, so remove() would DELETE the player. Only the FK
        // (player.team_id) changes on transfer.
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
        BbPlayer player = playerRepository.findById(playerId)
                .orElseThrow(() -> new IllegalArgumentException("Player not found: " + playerId));
        BbTeam seller = player.getTeam();
        if (seller == null || !seller.getId().equals(userTeamId)) {
            throw new IllegalArgumentException("Player is not on your team");
        }
        BbTeam buyer = teamRepository.findAll().stream()
                .filter(t -> !t.getId().equals(userTeamId))
                .min(Comparator.comparingInt(t -> t.getPlayers().size()))
                .orElseThrow(() -> new IllegalArgumentException("No destination club available"));

        long price = Math.max(MIN_PRICE, (long) player.getOverall() * SELL_PRICE_PER_OVR);

        // See buyPlayer: do not remove/add on the orphanRemoval collection.
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

    private BbPlayerDTO toMarketDTO(BbPlayer p) {
        BbPlayerDTO dto = new BbPlayerDTO();
        dto.setId(p.getId());
        dto.setName(p.getName());
        dto.setPosition(p.getPosition().name());
        dto.setTeamId(p.getTeam().getId());
        dto.setTeamName(p.getTeam().getName());
        dto.setTeamColor(p.getTeam().getColor());
        dto.setOverall(p.getOverall());
        dto.setSkills(Map.of(
                "pace", p.getSkillPace(),
                "steals", p.getSkillSteals(),
                "blocks", p.getSkillBlocks(),
                "freeThrows", p.getSkillFreeThrows(),
                "twoPtShot", p.getSkillTwoPtShot(),
                "threePtShot", p.getSkillThreePtShot(),
                "rebounding", p.getSkillRebounding(),
                "playmaking", p.getSkillPlaymaking()
        ));
        return dto;
    }
}