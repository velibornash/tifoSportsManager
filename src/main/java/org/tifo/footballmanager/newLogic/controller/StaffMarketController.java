package org.tifo.footballmanager.newLogic.controller;

import org.tifo.footballmanager.newLogic.model.StaffMember;
import org.tifo.commonmanager.model.User;
import org.tifo.footballmanager.newLogic.service.ClubOwnershipLinker;
import org.tifo.footballmanager.newLogic.service.StaffRecruitingService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The staff transfer market: the list of candidates, and the two decisions a manager can make about
 * one — sign him, or turn him down.
 *
 * <p>Signing is resolved against the manager's own club, not against a path variable, for the same
 * reason job offers are: a manager can only sign for the club he runs, and trusting a path would
 * let a crafted request sign a man for somebody else's wage bill.
 */
@RestController
@RequestMapping("/api/staff-market")
public class StaffMarketController {

    private final StaffRecruitingService recruiting;
    private final ClubOwnershipLinker ownership;

    public StaffMarketController(StaffRecruitingService recruiting, ClubOwnershipLinker ownership) {
        this.recruiting = recruiting;
        this.ownership = ownership;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> market() {
        List<StaffMember> candidates = recruiting.market();
        List<Map<String, Object>> rows = candidates.stream().map(this::view).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("candidates", rows);
        out.put("count", rows.size());
        return ResponseEntity.ok(out);
    }

    @PostMapping("/{candidateId}/accept")
    public ResponseEntity<Map<String, Object>> accept(@AuthenticationPrincipal User user,
                                                      @PathVariable Long candidateId) {
        if (user == null) return ResponseEntity.status(401).build();
        try {
            StaffMember signed = recruiting.accept(user, candidateId);
            return ResponseEntity.ok(Map.of(
                    "status", "signed",
                    "member", view(signed)));
        } catch (StaffRecruitingService.StaffRecruitingException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.reason(), "message", e.getMessage()));
        }
    }

    @PostMapping("/{candidateId}/refuse")
    public ResponseEntity<Map<String, Object>> refuse(@PathVariable Long candidateId) {
        recruiting.refuse(candidateId);
        return ResponseEntity.ok(Map.of("status", "refused"));
    }

    private Map<String, Object> view(StaffMember m) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", m.getId());
        row.put("role", m.getRole() == null ? null : m.getRole().name());
        row.put("name", m.getName());
        row.put("age", m.getAge());
        row.put("contractEndSeason", m.getContractEndSeason());
        row.put("weeklyWage", m.getWeeklyWage());
        row.put("development", m.getDevelopment());
        row.put("tactical", m.getTactical());
        row.put("motivation", m.getMotivation());
        row.put("goalkeeping", m.getGoalkeeping());
        row.put("fitness", m.getFitness());
        row.put("scouting", m.getScouting());
        row.put("skillStamina", m.getSkillStamina());
        row.put("skillGoalkeeper", m.getSkillGoalkeeper());
        row.put("skillDefender", m.getSkillDefender());
        row.put("skillPace", m.getSkillPace());
        row.put("skillTechnique", m.getSkillTechnique());
        row.put("skillPlaymaker", m.getSkillPlaymaker());
        row.put("skillPassing", m.getSkillPassing());
        row.put("skillStriker", m.getSkillStriker());
        row.put("overallSkill", m.overallSkill());
        return row;
    }
}
