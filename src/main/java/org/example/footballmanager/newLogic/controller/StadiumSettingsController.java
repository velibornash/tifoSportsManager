package org.example.footballmanager.newLogic.controller;

import lombok.RequiredArgsConstructor;
import org.example.footballmanager.newLogic.model.FinanceCategory;
import org.example.footballmanager.newLogic.service.TrainingFacilityService;
import org.example.footballmanager.newLogic.model.Stadium;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.example.footballmanager.newLogic.service.AdmissionService;
import org.example.footballmanager.newLogic.service.AdmissionService.TicketType;
import org.example.footballmanager.newLogic.service.PitchMaintenanceService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalDouble;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.example.commonmanager.model.User;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
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
    private final TrainingFacilityService facilities;
    private final org.example.footballmanager.newLogic.service.StadiumBuildService build;
    private final PitchMaintenanceService pitch;
    private final org.example.footballmanager.newLogic.service.StadiumImageService stadiumImages;
    private final org.example.footballmanager.newLogic.service.PlusFeatureService plusFeatures;

    /**
     * Uploads the club's stadium picture (owner, 2026-09-28).
     *
     * <p>Ownership is checked through {@code PlusFeatureService} rather than a local club lookup, so
     * "is this your club" cannot disagree with the answer the rest of the game gives. Without it any
     * authenticated manager could overwrite a rival's ground.
     */
    @PostMapping("/image")
    public ResponseEntity<Map<String, Object>> uploadImage(
            @PathVariable Long teamId,
            @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal User principal) {
        if (!plusFeatures.isOwnTeam(principal, teamId)) {
            return ResponseEntity.status(403).body(Map.of(
                    "error", "You can only change the picture of your own club's stadium."));
        }
        try {
            String url = stadiumImages.storeForTeam(teamId, file);
            return ResponseEntity.ok(Map.of("image", url));
        } catch (IllegalArgumentException e) {
            // A 400, not a 500: the request was well formed and the file was simply not acceptable.
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

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
    /**
     * Builds the ground out: more seats, better seats, or a roof.
     *
     * <p>One endpoint with an {@code action} rather than three, because they are the same decision
     * from the manager's side — "spend on the ground" — and they all cost money and all have a
     * ceiling. Each refuses with a reason the page can show, rather than a 500.
     */
    @PostMapping("/build")
    public ResponseEntity<?> buildGround(@PathVariable Long teamId,
                                         @RequestBody(required = false) Map<String, Object> body) {
        Team team = teamId == null ? null : teamRepository.findById(teamId).orElse(null);
        if (team == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "No such club"));
        }
        Map<String, Object> in = body == null ? Map.of() : body;
        String action = String.valueOf(in.getOrDefault("action", "")).toLowerCase(Locale.ROOT);

        Object result = switch (action) {
            case "expand" -> build.expand(team, intOf(in.get("seats"), 1000));
            case "seats" -> build.improveSeats(team);
            case "roof" -> build.buildRoof(team);
            default -> Map.of("refused", true,
                    "error", "Unknown action '" + action + "'. Use expand, seats or roof.");
        };
        if (result instanceof Map<?, ?> map && Boolean.TRUE.equals(map.get("refused"))) {
            // A refusal is a normal answer to a question the manager asked, not a server fault.
            return ResponseEntity.ok(Map.of("stadium", view(team).get("stadium"), "result", result));
        }
        return ResponseEntity.ok(Map.of("stadium", view(team).get("stadium"), "result", result));
    }

    /** Repaints the ground. Free, and validated. */
    @PostMapping("/paint")
    public ResponseEntity<?> paint(@PathVariable Long teamId,
                                   @RequestBody(required = false) Map<String, String> colours) {
        Team team = teamId == null ? null : teamRepository.findById(teamId).orElse(null);
        if (team == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "No such club"));
        }
        var result = build.paint(team, colours);
        return ResponseEntity.ok(Map.of("stadium", view(team).get("stadium"), "result", result));
    }

    private static int intOf(Object value, int fallback) {
        if (value instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return fallback;
        }
    }

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
        // The key stadium-view.js has always read. It was never sent, so the page has never shown a
        // real ground. imageFor resolves null to the Dunjareal fallback rather than a grey box.
        stadium.put("image", stadiumImages.imageFor(s));
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
        // Training facilities ride along with the stadium they belong to rather than getting their own
        // screen: a manager does not think of "my gym" as separate from "my ground", and splitting
        // them across two pages is how a feature ends up built and never looked at.
        stadium.put("northColour", s.getNorthColour());
        stadium.put("southColour", s.getSouthColour());
        stadium.put("eastColour", s.getEastColour());
        stadium.put("westColour", s.getWestColour());
        stadium.put("northEastCornerColour", s.getNorthEastCornerColour());
        stadium.put("northWestCornerColour", s.getNorthWestCornerColour());
        stadium.put("southEastCornerColour", s.getSouthEastCornerColour());
        stadium.put("southWestCornerColour", s.getSouthWestCornerColour());
        stadium.put("seatQuality", s.getSeatQuality() == null ? 10 : s.getSeatQuality());
        stadium.put("roof", s.isRoof());
        stadium.put("expandableTo", s.getExpandableTo());
        // What the next step costs, so the page can show a price instead of only failing on click.
        stadium.put("expansionQuote", build.expansionQuote(team, 1000));
        stadium.put("seatQuote", build.seatQuote(team));
        stadium.put("roofCost", s.isRoof() ? 0 : build.roofCost(s));
        stadium.put("budget", team.getBudget());
        out.put("trainingFacilities", facilities.levels(team));
        out.put("weeklyTrainingUpkeep", facilities.weeklyUpkeep(team));
        out.put("upkeepCategory", FinanceCategory.FACILITY_UPKEEP.label());
        return out;
    }

    /**
     * Takes one training facility up a level (Sprint 4.4).
     *
     * <p>Refuses rather than allowing a negative budget, and says why: a manager who cannot pay for
     * a gym should be told the price, not shown a red balance and a gym anyway.
     */
    @org.springframework.web.bind.annotation.PostMapping("/training-facilities/{facility}/upgrade")
    public ResponseEntity<?> upgradeTrainingFacility(
            @PathVariable Long teamId,
            @PathVariable String facility,
            @org.springframework.web.bind.annotation.RequestParam(required = false) Integer targetLevel) {
        TrainingFacilityService.Facility parsed;
        try {
            parsed = TrainingFacilityService.Facility.valueOf(facility.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            // Built from the enum rather than written out, so a new facility cannot be added without
            // appearing here. The previous message said "GROUND, GYM or TACTICAL" and would have told
            // a manager that YOUTH does not exist.
            String known = Arrays.stream(TrainingFacilityService.Facility.values())
                    .map(Enum::name)
                    .collect(java.util.stream.Collectors.joining(", "));
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Unknown facility '" + facility + "'. Use one of: " + known + "."));
        }
        Team team = teamId == null ? null : teamRepository.findById(teamId).orElse(null);
        if (team == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "No such club"));
        }
        Integer current = facilities.levels(team).get(parsed);
        double cost = facilities.upgradeCostTo(team.getStadium(), parsed,
                targetLevel != null ? targetLevel : current + 1);
        if (cost <= 0) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "That facility is already as good as it gets, or the target is below its level."));
        }
        OptionalDouble spent = facilities.upgrade(teamId, parsed);
        if (spent.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Not enough budget",
                    "cost", cost,
                    "budget", team.getBudget() == null ? 0.0 : team.getBudget()));
        }
        return ResponseEntity.ok(Map.of(
                "facility", parsed.name(),
                "level", facilities.levels(team).get(parsed),
                "spent", spent.getAsDouble()));
    }
}
