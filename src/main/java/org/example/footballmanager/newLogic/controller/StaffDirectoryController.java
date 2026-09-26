package org.example.footballmanager.newLogic.controller;

import lombok.RequiredArgsConstructor;
import org.example.footballmanager.newLogic.model.Sponsor;
import org.example.footballmanager.newLogic.model.StaffMember;
import org.example.footballmanager.newLogic.model.StaffRole;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.SponsorRepository;
import org.example.footballmanager.newLogic.repository.StaffMemberRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.example.footballmanager.newLogic.service.StaffSponsorService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Year;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Real staff and sponsors, replacing the hardcoded browser arrays.
 *
 * <p>`staff-directory.js` built four coaches and five support staff out of literal strings with
 * invented ratings, and the endpoint it called (`/demo/teams/1/coaches`) returned a constant. Every
 * club therefore had the same staff, the same ages and the same contracts, and the wages shown were
 * the wages of nobody.
 *
 * <p>Looked up by team id: two clubs may share a name.
 */
@RestController
@RequestMapping("/api/teams/{teamId}")
@RequiredArgsConstructor
public class StaffDirectoryController {

    private final TeamRepository teams;
    private final StaffMemberRepository staff;
    private final SponsorRepository sponsors;
    private final StaffSponsorService staffSponsors;

    @GetMapping("/staff")
    public ResponseEntity<Map<String, Object>> staff(@PathVariable Long teamId) {
        Team club = teams.findById(teamId).orElse(null);
        if (club == null) return ResponseEntity.notFound().build();

        List<StaffMember> members = staff.findByTeamId(teamId);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (StaffMember m : members) {
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
            row.put("overall", m.overall());
            rows.add(row);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("teamId", teamId);
        out.put("teamName", club.getName());
        out.put("staff", rows);
        out.put("headCount", members.size());
        out.put("weeklyWageTotal", staffSponsors.weeklyStaffWage(teamId));
        out.put("vacancies", vacancies(members));
        return ResponseEntity.ok(out);
    }

    @GetMapping("/sponsors")
    public ResponseEntity<Map<String, Object>> sponsors(@PathVariable Long teamId) {
        Team club = teams.findById(teamId).orElse(null);
        if (club == null) return ResponseEntity.notFound().build();

        int season = Year.now().getValue();
        List<Map<String, Object>> rows = new ArrayList<>();
        double weekly = 0;
        for (Sponsor s : sponsors.findByTeamId(teamId)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", s.getId());
            row.put("name", s.getName());
            row.put("tier", s.getTier() == null ? null : s.getTier().name());
            row.put("annualValue", s.getAnnualValue());
            row.put("startSeason", s.getStartSeason());
            row.put("endSeason", s.getEndSeason());
            row.put("performanceBonusClause", s.getPerformanceBonusClause());
            row.put("active", s.isActive(season));
            row.put("weeklyIncome", Math.round(s.weeklyIncome(season) * 100.0) / 100.0);
            rows.add(row);
            weekly += s.weeklyIncome(season);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("teamId", teamId);
        out.put("teamName", club.getName());
        out.put("sponsors", rows);
        out.put("weeklyIncome", Math.round(weekly * 100.0) / 100.0);
        return ResponseEntity.ok(out);
    }

    /**
     * Roles a club is expected to have but does not. A vacancy is a decision, and the club that
     * has no head coach should be visibly broken rather than quietly employ three of them.
     */
    private List<String> vacancies(List<StaffMember> members) {
        List<String> out = new ArrayList<>();
        for (StaffRole required : new StaffRole[] { StaffRole.HEAD_COACH }) {
            boolean present = members.stream().anyMatch(m -> m.getRole() == required);
            if (!present) out.add(required.name());
        }
        return out;
    }
}
