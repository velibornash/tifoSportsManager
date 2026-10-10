package org.example.footballmanager.newLogic.service;

import lombok.RequiredArgsConstructor;
import org.example.footballmanager.newLogic.model.Lineup;
import org.example.footballmanager.newLogic.model.MatchFixture;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.model.tactics.Tactic;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How well a side's chosen eleven fits the shape it has chosen (T0-UI-8).
 *
 * <p><b>Six preview fields were hardcoded {@code null}</b> with the comment *"not knowable before a
 * match"*. That was true when a fixture had no lineup and no tactic, and it stopped being true the moment
 * a manager could pick both — which is now. Fitness, mismatches and bench quality are arithmetic over the
 * eleven and the shape, and both are known the moment the manager saves them.
 *
 * <p><b>What is still not knowable is left null.</b> Availability depends on injuries that may happen
 * during the match, so that one stays null rather than being guessed at.
 *
 * <p><b>Fitness is measured against the shape the manager actually chose</b> — the per-match tactic if
 * there is one, then the lineup's own formation, and only then the club's standing column. Reading the
 * team's column first would show a manager the shape he used last week while he is picking a different one
 * in front of the screen.
 */
@Service
@RequiredArgsConstructor
public class ShapePreviewService {

    private final MatchLineupService lineups;
    private final TacticLibraryService tactics;

    /**
     * The shape and its fit for one side of a fixture.
     *
     * @return the shape in force, the eleven behind it, and how well the two agree
     */
    @Transactional(readOnly = true)
    public Map<String, Object> forSide(Team club, MatchFixture fixture, String side) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (club == null || club.getId() == null || fixture == null) {
            return out;
        }

        Lineup lineup = lineups.resolve(club.getId(), fixture);
        String formation = formationInForce(club, lineup);
        List<Player> eleven = lineup == null ? List.of() : lineup.getOrderedStartingPlayers();
        List<Player> bench = lineup == null ? List.of() : lineup.getOrderedSubstitutePlayers();

        out.put("formation", formation);
        out.put("fromTactic", tacticFormation(club) != null && lineup == null);
        out.put("hasLineup", lineup != null && lineup.getMatch() != null);

        if (eleven.size() < 11) {
            // No XI chosen, so there is nothing to measure a shape against. Absent rather than zero: zero
            // would read as "this eleven fits its formation perfectly".
            out.put("formationFitness", null);
            out.put("positionMismatches", null);
            out.put("benchQuality", null);
            out.put("starterCount", eleven.size());
            return out;
        }

        // **Capacity, not membership.** Asking only "does this shape have a D slot" scores a 4-4-2 that
        // fields seven centre backs at 100%: it has one D, and it has four, so the answer has to be
        // whether the defenders *fit*, not whether defenders are allowed. Slot counts are exactly what
        // T1-6 turned out to be about, so reading the shape as a set here would contradict it.
        Map<String, Integer> capacity = slotCapacity(formation);

        int mismatches = 0;
        for (Player player : eleven) {
            String slot = slotForRole(player.effectiveRole() == null ? "" : player.effectiveRole().name());
            if (slot == null) {
                // A role the shape does not name a slot for. Never counted: every shape here is described
                // by lines of back, midfield and attack, and a midfielder is not out of position in any of
                // them. Penalising that would make 4-3-3 look worse than 4-4-2 for having fewer wings.
                continue;
            }
            int left = capacity.getOrDefault(slot, 0);
            if (left > 0) {
                capacity.put(slot, left - 1);
            } else {
                mismatches++;
            }
        }

        out.put("starterCount", eleven.size());
        out.put("formationFitness", Math.round((11 - mismatches) * 100.0 / 11.0) / 100.0);
        out.put("positionMismatches", mismatches);
        out.put("benchQuality", benchQuality(bench));
        out.put("benchSize", bench.size());
        return out;
    }

    /**
     * The shape this side plays here: a per-match tactic, then the chosen lineup, then the club's column.
     */
    private String formationInForce(Team club, Lineup lineup) {
        String fromTactic = tacticFormation(club);
        if (fromTactic != null) {
            return fromTactic;
        }
        if (lineup != null && lineup.getFormation() != null && !lineup.getFormation().isBlank()) {
            return lineup.getFormation();
        }
        return club.getFormation() == null || club.getFormation().isBlank() ? "4-4-2" : club.getFormation();
    }

    /** The club's default tactic's formation, when it has one. */
    private String tacticFormation(Team club) {
        try {
            return tactics.defaultFor(club.getId()).map(Tactic::getFormation).orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * How many slots of each kind the shape actually has: 4-4-2 is four D, four M and two ST.
     *
     * <p>The engine's slot keys are positional — {@code DCL}, {@code CML}, {@code AMR} — so the kind is
     * the leading letters, not a prefix of fixed length. Truncating to two characters groups {@code DCL}
     * with {@code DR} under {@code DC} and never matches a role's {@code D} at all, which quietly scored
     * every centre back as a mismatch and made a correct 4-4-2 come out at 64%.
     */
    private static Map<String, Integer> slotCapacity(String formation) {
        Map<String, Integer> capacity = new HashMap<>();
        for (String slot : org.example.footballmanager.newLogic.sim.RealSquadFactory.slotOrderFor(formation)) {
            capacity.merge(slotKind(slot), 1, Integer::sum);
        }
        return capacity;
    }

    /** The line a slot key belongs to, from the key's leading letters. */
    private static String slotKind(String slot) {
        if (slot.startsWith("GK")) {
            return "GK";
        }
        if (slot.startsWith("D")) {
            return "D";
        }
        if (slot.startsWith("ST")) {
            return "ST";
        }
        if (slot.startsWith("AM")) {
            return "AM";
        }
        return "M";
    }

    /** The bench's mean rating, or null when there is no bench to judge. */
    private Double benchQuality(List<Player> bench) {
        if (bench == null || bench.isEmpty()) {
            return null;
        }
        double total = 0.0;
        int counted = 0;
        for (Player player : bench) {
            if (player.getRating() > 0) {
                total += player.getRating();
                counted++;
            }
        }
        return counted == 0 ? null : Math.round((total / counted) * 10.0) / 10.0;
    }

    /** The engine slot a role belongs in, so a mismatch is measured in the engine's own vocabulary. */
    private static String slotForRole(String role) {
        return switch (role) {
            case "GOALKEEPER" -> "GK";
            case "LEFT_BACK", "RIGHT_BACK", "LEFT_WING_BACK", "RIGHT_WING_BACK",
                 "CENTRE_BACK", "RIGHT_CENTRE_BACK" -> "D";
            case "WINGER", "WIDE_MIDFIELDER" -> "M";
            case "STRIKER", "SECOND_STRIKER" -> "ST";
            default -> null;
        };
    }
}