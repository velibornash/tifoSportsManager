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
import org.example.footballmanager.newLogic.service.SeasonService;
import org.example.footballmanager.newLogic.service.StadiumSectionService;
import org.example.footballmanager.newLogic.model.SeatingType;
import org.example.footballmanager.newLogic.model.StandPosition;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import java.util.Arrays;
import java.util.List;
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

/**
 * Stadium controls: the eight sections a ground is built from, ticket prices, the maintenance programme,
 * and a projection of what the current settings would bring on a home fixture.
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
    private final StadiumSectionService sectionService;
    private final PitchMaintenanceService pitch;
    private final org.example.footballmanager.newLogic.service.StadiumImageService stadiumImages;
    private final org.example.footballmanager.newLogic.service.PlusFeatureService plusFeatures;
    private final SeasonService seasonService;

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
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal User principal) {

        if (!mayManage(principal, teamId)) {
            return notYourClub();
        }

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
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal User principal) {

        if (!mayManage(principal, teamId)) {
            return notYourClub();
        }

        Object budget = body.get("weeklyBudget");
        if (budget == null) return ResponseEntity.badRequest().build();

        Stadium stadium = pitch.setMaintenanceProgramme(teamId, ((Number) budget).doubleValue());
        if (stadium == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(view(teamRepository.findById(teamId).orElseThrow()));
    }

    /**
     * What work on one section would cost, and how long that section would be unusable. <b>Nothing is
     * spent here.</b>
     *
     * <p>The owner's two questions, asked before the decision: the price, and the number of weeks the
     * stand holds nobody. Answering them on one endpoint is the whole point — the old page took the
     * money on the click and told you afterwards.
     */
    @PostMapping("/sections/quote")
    public ResponseEntity<?> quoteSection(@PathVariable Long teamId,
                                          @RequestBody(required = false) Map<String, Object> body,
                                          @AuthenticationPrincipal User principal) {
        if (!mayManage(principal, teamId)) {
            return notYourClub();
        }
        Team team = teamId == null ? null : teamRepository.findById(teamId).orElse(null);
        if (team == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "No such club"));
        }
        Map<String, Object> in = body == null ? Map.of() : body;
        StandPosition position = parsePosition(in.get("position"));
        SeatingType type = parseSeatingType(in.get("seatingType"));
        return ResponseEntity.ok(sectionService.quote(team.getStadium(), position, type,
                intOf(in.get("capacityToAdd"), 0), boolOf(in.get("roof"), false), weekOf()));
    }

    /** Confirms the quote: spends the money and closes that one section for the weeks it needs. */
    @PostMapping("/sections/build")
    public ResponseEntity<?> buildSection(@PathVariable Long teamId,
                                          @RequestBody(required = false) Map<String, Object> body,
                                          @AuthenticationPrincipal User principal) {
        if (!mayManage(principal, teamId)) {
            return notYourClub();
        }
        Team team = teamId == null ? null : teamRepository.findById(teamId).orElse(null);
        if (team == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "No such club"));
        }
        Map<String, Object> in = body == null ? Map.of() : body;
        Map<String, Object> result = sectionService.build(team,
                parsePosition(in.get("position")),
                parseSeatingType(in.get("seatingType")),
                intOf(in.get("capacityToAdd"), 0),
                boolOf(in.get("roof"), false),
                seasonOf(), weekOf());
        return ResponseEntity.ok(Map.of(
                "result", result,
                "sections", sectionService.sectionViews(team.getStadium()),
                "stadium", view(team).get("stadium")));
    }

    /** One section's own ticket price, which is the owner's per-stand pricing decision. */
    @PostMapping("/sections/price")
    public ResponseEntity<?> priceSection(@PathVariable Long teamId,
                                          @RequestBody(required = false) Map<String, Object> body,
                                          @AuthenticationPrincipal User principal) {
        if (!mayManage(principal, teamId)) {
            return notYourClub();
        }
        Team team = teamId == null ? null : teamRepository.findById(teamId).orElse(null);
        if (team == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "No such club"));
        }
        Map<String, Object> in = body == null ? Map.of() : body;
        Object price = in.get("price");
        Double value = price instanceof Number n ? n.doubleValue() : null;
        return ResponseEntity.ok(sectionService.setPrice(team, parsePosition(in.get("position")), value));
    }

    /** Repaints the ground. Free, and validated. */
    @PostMapping("/paint")
    public ResponseEntity<?> paint(@PathVariable Long teamId,
                                   @RequestBody(required = false) Map<String, String> colours,
                                   @AuthenticationPrincipal User principal) {
        if (!mayManage(principal, teamId)) {
            return notYourClub();
        }
        Team team = teamId == null ? null : teamRepository.findById(teamId).orElse(null);
        if (team == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "No such club"));
        }
        var result = build.paint(team, colours);
        return ResponseEntity.ok(Map.of("stadium", view(team).get("stadium"), "result", result));
    }

    /**
     * May this caller change <b>this</b> club's ground?
     *
     * <p>The same rule {@code TeamController} and the stadium's own {@code /image} route already apply. It
     * was written here once, for one route, and never extended to the five beside it — so {@code /build},
     * {@code /maintenance}, {@code /tickets}, {@code /paint} and the facility upgrade all accepted any
     * logged-in manager, and three of them took the club's money with him.
     *
     * <p>Fails closed. The owner is let through, because a fix that locks the owner out of his own game is
     * worse than the hole it closes.
     */
    private boolean mayManage(User principal, Long teamId) {
        if (principal == null || teamId == null) {
            return false;
        }
        if (principal.getRole() != null && principal.getRole().name().equals("OWNER")) {
            return true;
        }
        return plusFeatures.isOwnTeam(principal, teamId);
    }

    private ResponseEntity<Map<String, Object>> notYourClub() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                "error", "You can only change your own club's ground."));
    }

    private static int intOf(Object value, int fallback) {
        if (value instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return fallback;
        }
    }

    private static boolean boolOf(Object value, boolean fallback) {
        if (value instanceof Boolean b) return b;
        if (value == null) return fallback;
        return Boolean.parseBoolean(String.valueOf(value));
    }

    /**
     * A stand name, or null so the service refuses with "pick one of the four sides or four corners".
     *
     * <p>Built from the enum rather than checked against a written list, so a stand cannot be added
     * without being accepted here.
     */
    private static StandPosition parsePosition(Object value) {
        if (value == null) return null;
        try {
            return StandPosition.valueOf(String.valueOf(value).trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static SeatingType parseSeatingType(Object value) {
        if (value == null) return null;
        try {
            return SeatingType.valueOf(String.valueOf(value).trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private int seasonOf() {
        return seasonService.getOrCreateClock().getCurrentSeason() == null
                ? 1 : seasonService.getOrCreateClock().getCurrentSeason();
    }

    private int weekOf() {
        Integer week = seasonService.getOrCreateClock().getCurrentWeek();
        return week == null ? 1 : week;
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
        // The eight sections first: reading them is what lays out a legacy ground and recomputes its
        // capacity, seat quality and roof flag. Everything below then reports the recomputed values
        // rather than whatever the columns happened to hold before this request.
        List<Map<String, Object>> sections = sectionService.sectionViews(s);
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
        // The eight sections, each with its own capacity, seating type, roof, price and recommendation —
        // this is the whole build-out model now, so the page has one place to read it from.
        stadium.put("sections", sections);
        stadium.put("seatingTypes", Arrays.stream(SeatingType.values())
                .map(t -> Map.of("name", t.name(), "label", t.label()))
                .toList());
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
            @org.springframework.web.bind.annotation.RequestParam(required = false) Integer targetLevel,
            @AuthenticationPrincipal User principal) {
        if (!mayManage(principal, teamId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "You can only change your own club's ground."));
        }
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
