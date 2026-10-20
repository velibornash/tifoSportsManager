package org.tifo.footballmanager.newLogic.sim.controller;

import org.tifo.footballmanager.newLogic.model.MatchFixture;
import org.tifo.footballmanager.newLogic.model.Team;
import org.tifo.footballmanager.newLogic.model.tactics.MatchTacticOrder;
import org.tifo.footballmanager.newLogic.model.tactics.Tactic;
import org.tifo.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.tifo.footballmanager.newLogic.repository.MatchTacticOrderRepository;
import org.tifo.footballmanager.newLogic.repository.TacticRepository;
import org.tifo.footballmanager.newLogic.repository.TeamRepository;
import org.tifo.footballmanager.newLogic.sim.tactics.TacticMatchCondition;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The mid-match tactic instructions (T-MATCH-6).
 *
 * <p>Separate from {@link MatchTacticPlanController} on purpose. The plan is three standing tactics and
 * the orders are ten switches between them, and putting them on one endpoint would make "how many tactics
 * does a match hold" answerable in two places — which is how {@code MAX_PER_SIDE} and the owner's ten come
 * to disagree.
 *
 * <p><b>Same lock as the rest of Match Setup.</b> An hour before kickoff the matchday job prepares the
 * fixture and this answers 423, exactly as the lineup, the plan and the substitution panel do. The lock is
 * asked about the fixture, not about the clock.
 */
@RestController
@RequestMapping("/api/sim/fixtures/{fixtureId}/tactic-orders")
public class MatchTacticOrderController {

    private final MatchFixtureRepository fixtures;
    private final TeamRepository teams;
    private final TacticRepository tactics;
    private final MatchTacticOrderRepository orders;

    public MatchTacticOrderController(MatchFixtureRepository fixtures, TeamRepository teams,
                                      TacticRepository tactics, MatchTacticOrderRepository orders) {
        this.fixtures = fixtures;
        this.teams = teams;
        this.tactics = tactics;
        this.orders = orders;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> get(@PathVariable Long fixtureId,
                                                   @AuthenticationPrincipal org.tifo.commonmanager.model.User user) {
        var fixture = fixtures.findById(fixtureId).orElse(null);
        if (fixture == null) {
            return notFound();
        }
        Team club = clubOf(user);
        String side = sideOf(fixture, club);
        if (side == null) {
            return notInFixture();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("fixtureId", fixtureId);
        out.put("side", side);
        out.put("cap", MatchTacticOrder.MAX_ORDERS_PER_SIDE);
        out.put("orders", rows(fixtureId, side));
        // Said here as well as in the markup: the page reads this, and the API is what a script reads.
        out.put("closed", closed(fixture));
        return ResponseEntity.ok(out);
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> add(@PathVariable Long fixtureId,
                                                   @RequestBody(required = false) Map<String, Object> body,
                                                   @AuthenticationPrincipal org.tifo.commonmanager.model.User user) {
        var fixture = fixtures.findById(fixtureId).orElse(null);
        if (fixture == null) {
            return notFound();
        }
        Team club = clubOf(user);
        String side = sideOf(fixture, club);
        if (side == null) {
            return notInFixture();
        }
        ResponseEntity<Map<String, Object>> lock = closed(fixture);
        if (lock != null) {
            return lock;
        }

        long existing = orders.countByFixtureIdAndTeam(fixtureId, side);
        // **Stated, not silently enforced.** The owner asked for ten; a manager who writes an eleventh is
        // told the limit and how many are already there, rather than having a row quietly dropped.
        if (existing >= MatchTacticOrder.MAX_ORDERS_PER_SIDE) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "TOO_MANY_ORDERS",
                    "message", "A match holds " + MatchTacticOrder.MAX_ORDERS_PER_SIDE
                            + " tactic orders. This one already has " + existing + "; remove one first."));
        }

        Long tacticId = asLong(body == null ? null : body.get("tacticId"));
        Tactic tactic = tacticId == null ? null : tactics.findById(tacticId).orElse(null);
        if (tactic == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "NO_SUCH_TACTIC",
                    "message", "That tactic is not one of this club's."));
        }
        // A tactic id from another club would name somebody else's shape. The plan controller checks the
        // pair; the same check belongs here rather than being assumed.
        if (tactic.getTeam() == null || !tactic.getTeam().getId().equals(club.getId())) {
            return ResponseEntity.badRequest().body(Map.of("error", "NOT_YOUR_TACTIC",
                    "message", "That tactic belongs to another club."));
        }

        String conditionName = body.get("condition") == null
                ? "ALWAYS" : String.valueOf(body.get("condition"));
        TacticMatchCondition condition;
        try {
            condition = TacticMatchCondition.valueOf(conditionName.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "UNKNOWN_CONDITION",
                    "message", "'" + conditionName + "' is not a condition the engine understands."));
        }

        Integer minuteFrom = asInteger(body.get("minuteFrom"));
        int minute = minuteFrom == null ? 0 : minuteFrom;
        if (minute < 0 || minute > 90) {
            return ResponseEntity.badRequest().body(Map.of("error", "BAD_MINUTE",
                    "message", "An order fires between minute 0 and minute 90. " + minute + " is outside it."));
        }

        MatchTacticOrder order = new MatchTacticOrder();
        order.setFixture(fixtures.findById(fixtureId).orElseThrow());
        order.setTactic(tactic);
        order.setTeam(side);
        // Position continues the list, which is what makes "the last one written wins" mean what the
        // manager sees rather than depending on an id they never look at.
        order.setPosition((int) existing + 1);
        order.setCondition(condition);
        order.setMinuteFrom(minute);
        orders.save(order);

        return get(fixtureId, user);
    }

    @DeleteMapping("/{orderId}")
    public ResponseEntity<Map<String, Object>> remove(@PathVariable Long fixtureId,
                                                      @PathVariable Long orderId,
                                                      @AuthenticationPrincipal org.tifo.commonmanager.model.User user) {
        var fixture = fixtures.findById(fixtureId).orElse(null);
        if (fixture == null) {
            return notFound();
        }
        Team club = clubOf(user);
        String side = sideOf(fixture, club);
        if (side == null) {
            return notInFixture();
        }
        ResponseEntity<Map<String, Object>> lock = closed(fixture);
        if (lock != null) {
            return lock;
        }
        MatchTacticOrder order = orders.findByIdAndFixtureId(orderId, fixtureId).orElse(null);
        // A stranger's order is not found rather than refused: the id space belongs to the fixture.
        if (order == null || !order.getTeam().equals(side)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "NO_SUCH_ORDER", "message", "No such order on this fixture."));
        }
        orders.delete(order);
        // Positions are rewritten rather than left with a hole, because "the last one wins" is by
        // position and a gap would make two orders tie on nothing.
        int position = 1;
        for (MatchTacticOrder remaining : orders.findByFixtureIdAndTeamOrderByPositionAsc(fixtureId, side)) {
            if (remaining.getPosition() != position) {
                remaining.setPosition(position);
                orders.save(remaining);
            }
            position++;
        }
        return get(fixtureId, user);
    }

    // --- helpers ----------------------------------------------------------

    private List<Map<String, Object>> rows(Long fixtureId, String side) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (MatchTacticOrder order : orders.findByFixtureIdAndTeamOrderByPositionAsc(fixtureId, side)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", order.getId());
            row.put("position", order.getPosition());
            row.put("tacticId", order.getTactic() == null ? null : order.getTactic().getId());
            row.put("tacticName", order.getTactic() == null ? null : order.getTactic().getName());
            row.put("formation", order.getTactic() == null ? null : order.getTactic().getFormation());
            row.put("condition", order.getCondition() == null ? null : order.getCondition().name());
            row.put("conditionLabel", order.getCondition() == null ? null : order.getCondition().label());
            row.put("minuteFrom", order.getMinuteFrom());
            out.add(row);
        }
        return out;
    }

    /** 423 once the match is decided, and null while it is still open. The same lock the plan asks. */
    private ResponseEntity<Map<String, Object>> closed(MatchFixture fixture) {
        if (fixture.isPlayed() || fixture.isPrepared()) {
            return ResponseEntity.status(HttpStatus.LOCKED).body(Map.of(
                    "error", "MATCH_CLOSED",
                    "message", "This match is decided. Its orders are part of the record now."));
        }
        return null;
    }

    private ResponseEntity<Map<String, Object>> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "NO_SUCH_FIXTURE", "message", "No such fixture."));
    }

    private ResponseEntity<Map<String, Object>> notInFixture() {
        return ResponseEntity.badRequest().body(Map.of("error", "NOT_IN_THIS_FIXTURE",
                "message", "Your club is not playing in this fixture."));
    }

    /**
     * The caller's club, the same two-step resolution the plan controller uses.
     *
     * <p>Copied rather than simplified. It reads the football team first and falls back to the CTeam by
     * name, and an account whose club is only reachable through the second step would have got
     * {@code NOT_IN_THIS_FIXTURE} from a simpler version — an order screen that silently belongs to nobody.
     */
    private Team clubOf(org.tifo.commonmanager.model.User user) {
        if (user == null) return null;
        if (user.getFootballTeam() != null && user.getFootballTeam().getId() != null) {
            return teams.findById(user.getFootballTeam().getId()).orElse(null);
        }
        if (user.getCTeam() != null && user.getCTeam().getName() != null) {
            return teams.findByName(user.getCTeam().getName()).orElse(null);
        }
        return null;
    }

    /** Which side of this fixture the club is on, decided by id and never by name. */
    private String sideOf(MatchFixture fixture, Team club) {
        if (club == null || club.getId() == null) {
            return null;
        }
        if (fixture.getHomeTeam() != null && club.getId().equals(fixture.getHomeTeam().getId())) {
            return "HOME";
        }
        if (fixture.getAwayTeam() != null && club.getId().equals(fixture.getAwayTeam().getId())) {
            return "AWAY";
        }
        return null;
    }

    private static Long asLong(Object value) {
        return value instanceof Number n ? n.longValue() : null;
    }

    private static Integer asInteger(Object value) {
        return value instanceof Number n ? n.intValue() : null;
    }
}