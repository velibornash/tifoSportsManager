package org.tifo.americanfootballmanager.service;

import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.tifo.americanfootballmanager.model.AfPlayer;
import org.tifo.americanfootballmanager.model.AfTeam;
import org.tifo.americanfootballmanager.repository.AfPlayerRepository;
import org.tifo.americanfootballmanager.repository.AfTeamRepository;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
@Slf4j
public class AfTrainingService {

    private final AfTeamRepository teamRepository;
    private final AfPlayerRepository playerRepository;
    private final Random random = new Random();

    public AfTrainingService(AfTeamRepository teamRepository, AfPlayerRepository playerRepository) {
        this.teamRepository = teamRepository;
        this.playerRepository = playerRepository;
    }

    public String getFocus(Long teamId) {
        return teamRepository.findById(teamId).map(t -> t.getTrainingFocus() == null ? "BALANCED" : t.getTrainingFocus()).orElse("BALANCED");
    }

    public void setFocus(Long teamId, String focus) {
        if (!Set.of("BALANCED", "OFFENSE", "DEFENSE", "SPECIAL").contains(focus)) focus = "BALANCED";
        String finalFocus = focus;
        teamRepository.findById(teamId).ifPresent(t -> { t.setTrainingFocus(finalFocus); teamRepository.save(t); });
    }

    @Transactional
    public Map<String, Object> train(Long teamId) {
        AfTeam team = teamRepository.findById(teamId).orElseThrow(() -> new IllegalArgumentException("Team not found"));
        String focus = team.getTrainingFocus() == null ? "BALANCED" : team.getTrainingFocus();
        int improved = 0;
        for (AfPlayer p : team.getPlayers()) {
            String skill = pickSkill(focus);
            if (tryImprove(p, skill)) improved++;
            playerRepository.save(p);
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ok", true);
        resp.put("focus", focus);
        resp.put("playersTrained", team.getPlayers().size());
        resp.put("playersImproved", improved);
        return resp;
    }

    private String pickSkill(String focus) {
        List<String> offense = List.of("skillPassing", "skillRunning", "skillPace", "skillPlaymaking");
        List<String> defense = List.of("skillTackling", "skillStrength", "skillPace", "skillStamina");
        List<String> special = List.of("skillShooting", "skillStamina", "skillPace");
        List<String> pool;
        switch (focus) {
            case "OFFENSE" -> pool = offense;
            case "DEFENSE" -> pool = defense;
            case "SPECIAL" -> pool = special;
            default -> pool = List.of("skillStamina", "skillStrength", "skillPace", "skillPlaymaking", "skillPassing", "skillRunning", "skillTackling", "skillShooting");
        }
        return pool.get(random.nextInt(pool.size()));
    }

    private boolean tryImprove(AfPlayer p, String skill) {
        int current = getSkill(p, skill);
        if (current >= 20) return false;
        double chance = (20 - current) / 20.0;
        if (random.nextDouble() > chance) return false;
        setSkill(p, skill, current + 1);
        return true;
    }

    private int getSkill(AfPlayer p, String skill) {
        return switch (skill) {
            case "skillStamina" -> p.getSkillStamina();
            case "skillStrength" -> p.getSkillStrength();
            case "skillPace" -> p.getSkillPace();
            case "skillPlaymaking" -> p.getSkillPlaymaking();
            case "skillPassing" -> p.getSkillPassing();
            case "skillRunning" -> p.getSkillRunning();
            case "skillTackling" -> p.getSkillTackling();
            case "skillShooting" -> p.getSkillShooting();
            default -> 5;
        };
    }

    private void setSkill(AfPlayer p, String skill, int val) {
        switch (skill) {
            case "skillStamina" -> p.setSkillStamina(val);
            case "skillStrength" -> p.setSkillStrength(val);
            case "skillPace" -> p.setSkillPace(val);
            case "skillPlaymaking" -> p.setSkillPlaymaking(val);
            case "skillPassing" -> p.setSkillPassing(val);
            case "skillRunning" -> p.setSkillRunning(val);
            case "skillTackling" -> p.setSkillTackling(val);
            case "skillShooting" -> p.setSkillShooting(val);
        }
    }
}
