package org.example.footballmanager.newLogic.controller;

import lombok.RequiredArgsConstructor;
import org.example.footballmanager.newLogic.model.Stadium;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.example.footballmanager.newLogic.service.AdmissionService;
import org.example.footballmanager.newLogic.service.AdmissionService.TicketType;
import org.example.footballmanager.newLogic.service.PitchMaintenanceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Stadium controls: ticket prices, the maintenance programme, and a projection of what the current
 * settings would bring on a home fixture.
 *
 * <p>All lookups are by team id. Team names are not unique and two clubs may share one, so a name is
 * a label and never an identifier.
 */
@RestController
@RequestMapping("/api/teams/{teamId}/stadium")
@RequiredArgsConstructor
public class StadiumSettingsController {

    private final TeamRepository teamRepository;
    private final AdmissionService admission;
    private final PitchMaintenanceService pitch;

    @GetMapping
    public ResponseEntity<Map<String, Object>> get(@PathVariable Long teamId) {
        Team team = teamRepository.findById(teamId).orElse(null);
        if (team == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(view(team));
    }

    /**
     * Sets the face value of a tier. Tier prices are relative to the standard tier, so raising the
     * standard price moves the whole scale with it.
     */
    @PostMapping("/tickets")
    public ResponseEntity<Map<String, Object>> setTicketPrice(
            @PathVariable Long teamId,
            @RequestBody Map<String, Object> body) {

        Team team = teamRepository.findById(teamId).orElse(null);
        if (team == null || team.getStadium() == null) return ResponseEntity.notFound().build();

        String tierName = String.valueOf(body.getOrDefault("tier", "STANDARD"));
        Object price = body.get("price");
        if (price == null) return ResponseEntity.badRequest().build();

        TicketType tier;
        try {
            tier = TicketType.valueOf(tierName);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }

        admission.setTicketPrice(team, tier, ((Number) price).doubleValue());
        return ResponseEntity.ok(view(teamRepository.findById(teamId).orElseThrow()));
    }

    /** Sets the weekly maintenance budget, in euros. */
    @PostMapping("/maintenance")
    public ResponseEntity<Map<String, Object>> setMaintenance(
            @PathVariable Long teamId,
            @RequestBody Map<String, Object> body) {

        Object budget = body.get("weeklyBudget");
        if (budget == null) return ResponseEntity.badRequest().build();

        Stadium stadium = pitch.setMaintenanceProgramme(teamId, ((Number) budget).doubleValue());
        if (stadium == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(view(teamRepository.findById(teamId).orElseThrow()));
    }

    /**
     * What the current settings would produce on a home fixture — so a manager can see the effect
     * of a price change before committing to it, instead of discovering it a week later.
     */
    @GetMapping("/projection")
    public ResponseEntity<Map<String, Object>> project(@PathVariable Long teamId) {
        Team team = teamRepository.findById(teamId).orElse(null);
        if (team == null) return ResponseEntity.notFound().build();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("teamId", teamId);
        out.put("projection", admission.projectHomeFixture(team));
        return ResponseEntity.ok(out);
    }

    private Map<String, Object> view(Team team) {
        Stadium s = team.getStadium();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("teamId", team.getId());
        out.put("teamName", team.getName());
        if (s == null) {
            out.put("stadium", null);
            return out;
        }
        Map<String, Object> stadium = new LinkedHashMap<>();
        stadium.put("id", s.getId());
        stadium.put("name", s.getName());
        stadium.put("capacity", s.getCapacity());
        stadium.put("location", s.getLocation());
        stadium.put("standardTicketPrice", admission.priceOf(s, TicketType.STANDARD));
        stadium.put("prices", admission.allPrices(s));
        stadium.put("pitchCondition", pitch.conditionOf(s));
        stadium.put("pitchQuality", s.getPitchQuality());
        stadium.put("maintenanceRemaining", pitch.remainingOf(s));
        stadium.put("pitchStatus", pitch.describe(s));
        stadium.put("conditionPenalty", pitch.conditionPenalty(s));
        stadium.put("awaySectorShare", AdmissionService.AWAY_SECTOR_SHARE);
        stadium.put("awaySectorCapacity", admission.awaySectorCapacity(s));
        stadium.put("projectedGateRevenue", admission.projectHomeFixture(team).gateRevenue);
        out.put("stadium", stadium);
        return out;
    }
}
