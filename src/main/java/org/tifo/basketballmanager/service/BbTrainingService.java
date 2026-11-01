package org.tifo.basketballmanager.service;

import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.tifo.basketballmanager.model.BbPlayer;
import org.tifo.basketballmanager.model.BbTeam;
import org.tifo.basketballmanager.repository.BbPlayerRepository;
import org.tifo.basketballmanager.repository.BbTeamRepository;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
@Slf4j
public class BbTrainingService {

    private final BbTeamRepository teamRepository;
    private final BbPlayerRepository playerRepository;
    private final BbInboxService inboxService;
    private final Random random = new Random();

    public BbTrainingService(BbTeamRepository teamRepository, BbPlayerRepository playerRepository, BbInboxService inboxService) {
        this.teamRepository = teamRepository;
        this.playerRepository = playerRepository;
        this.inboxService = inboxService;
    }

    public String getFocus(Long teamId) {
        return teamRepository.findById(teamId).map(t -> t.getTrainingFocus() == null ? "BALANCED" : t.getTrainingFocus()).orElse("BALANCED");
    }

    public void setFocus(Long teamId, String focus) {
        String finalFocus = Set.of("BALANCED", "ATTACK", "DEFENCE", "CREATION").contains(focus) ? focus : "BALANCED";
        teamRepository.findById(teamId).ifPresent(t -> { t.setTrainingFocus(finalFocus); teamRepository.save(t); });
    }

    @Transactional
    public Map<String, Object> train(Long teamId) {
        BbTeam team = teamRepository.findById(teamId).orElseThrow(() -> new IllegalArgumentException("Team not found"));
        String focus = team.getTrainingFocus() == null ? "BALANCED" : team.getTrainingFocus();
        int improved = 0;
        for (BbPlayer p : team.getPlayers()) {
            // Weighted by position and focus
            String skillToTrain = pickSkill(p.getPosition().name(), focus);
            boolean gained = tryImprove(p, skillToTrain);
            if (gained) improved++;
            playerRepository.save(p);
        }
        try {
            inboxService.addMessage(teamId, "CLUB", "Training", focus + " session: " + improved + " of " + team.getPlayers().size() + " players improved.", null);
        } catch (Exception ignored) { }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ok", true);
        resp.put("focus", focus);
        resp.put("playersTrained", team.getPlayers().size());
        resp.put("playersImproved", improved);
        return resp;
    }

    private String pickSkill(String position, String focus) {
        List<String> attackSkills = List.of("skillTwoPtShot", "skillThreePtShot", "skillFreeThrows");
        List<String> defenceSkills = List.of("skillSteals", "skillBlocks", "skillRebounding");
        List<String> creationSkills = List.of("skillPlaymaking", "skillPace");
        List<String> pool;
        switch (focus) {
            case "ATTACK" -> pool = attackSkills;
            case "DEFENCE" -> pool = defenceSkills;
            case "CREATION" -> pool = creationSkills;
            default -> pool = List.of("skillPace", "skillSteals", "skillBlocks", "skillFreeThrows", "skillTwoPtShot", "skillThreePtShot", "skillRebounding", "skillPlaymaking");
        }
        // PG/SG bias: creation/attack; PF/C bias: defence/rebounding
        return pool.get(random.nextInt(pool.size()));
    }

    private boolean tryImprove(BbPlayer p, String skill) {
        // Reflective set via switch
        int current = getSkill(p, skill);
        if (current >= 20) return false;
        // diminishing: higher skill less likely
        double chance = (20 - current) / 20.0;
        if (random.nextDouble() > chance) return false;
        setSkill(p, skill, current + 1);
        return true;
    }

    private int getSkill(BbPlayer p, String skill) {
        return switch (skill) {
            case "skillPace" -> p.getSkillPace();
            case "skillSteals" -> p.getSkillSteals();
            case "skillBlocks" -> p.getSkillBlocks();
            case "skillFreeThrows" -> p.getSkillFreeThrows();
            case "skillTwoPtShot" -> p.getSkillTwoPtShot();
            case "skillThreePtShot" -> p.getSkillThreePtShot();
            case "skillRebounding" -> p.getSkillRebounding();
            case "skillPlaymaking" -> p.getSkillPlaymaking();
            default -> 5;
        };
    }

    private void setSkill(BbPlayer p, String skill, int val) {
        switch (skill) {
            case "skillPace" -> p.setSkillPace(val);
            case "skillSteals" -> p.setSkillSteals(val);
            case "skillBlocks" -> p.setSkillBlocks(val);
            case "skillFreeThrows" -> p.setSkillFreeThrows(val);
            case "skillTwoPtShot" -> p.setSkillTwoPtShot(val);
            case "skillThreePtShot" -> p.setSkillThreePtShot(val);
            case "skillRebounding" -> p.setSkillRebounding(val);
            case "skillPlaymaking" -> p.setSkillPlaymaking(val);
        }
    }
}