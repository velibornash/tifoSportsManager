package org.tifo.footballtextmanager.engine;

import org.tifo.footballtextmanager.model.CSPlayStyle;
import org.tifo.footballtextmanager.model.CSPlayer;
import org.tifo.footballtextmanager.model.CSTactics;
import org.tifo.footballtextmanager.model.CSTeam;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A league where all sixteen opponents play the same way is not a league.
 *
 * <p>Every club except the manager's own used to receive a hardcoded 4-4-2 BALANCED, so scouting
 * an opponent told you nothing. The property that matters is not that any particular shape is
 * correct — it is that clubs differ from each other, and differ <i>stably</i>, so a manager can
 * learn them.
 */
class CSOpponentProfileTest {

    @Test
    @DisplayName("Style varies across a league of ids")
    void styleIsNotTheSameForEveryClub() {
        Set<CSPlayStyle> styles = new java.util.HashSet<>();
        for (long id = 1; id <= 16; id++) {
            styles.add(CSOpponentProfile.styleFor(SquadFixture.team(id, "Club " + id)));
        }

        assertTrue(styles.size() >= 3,
                "Sixteen clubs produced only " + styles.size() + " distinct styles (" + styles
                        + "). A league of identical managers is not a league.");
    }

    @Test
    @DisplayName("The same club always plays the same way")
    void profileIsStableAcrossCalls() {
        CSTeam club = SquadFixture.team(7L, "FK Proleter");
        List<CSPlayer> roster = roster("GK", "DEF", "DEF", "DEF", "DEF", "MID", "MID", "MID", "MID", "ATT", "ATT");

        CSTactics first = CSOpponentProfile.tacticsFor(club, roster);
        for (int i = 0; i < 25; i++) {
            CSTactics again = CSOpponentProfile.tacticsFor(club, roster);
            assertEquals(first.getFormation(), again.getFormation(),
                    "The club changed formation between calls — the league table stops being reproducible");
            assertEquals(first.getStyle(), again.getStyle(),
                    "The club changed style between calls");
        }
    }

    @Test
    @DisplayName("Formation fits the squad it was derived from")
    void formationMatchesTheSquadsActualShape() {
        // A back-three squad must not be handed four defenders' worth of slots.
        CSTeam club = SquadFixture.team(3L, "Back Three FC");
        List<CSPlayer> backThree = roster("GK", "DEF", "DEF", "DEF", "MID", "MID", "MID", "MID", "MID", "ATT", "ATT");

        assertEquals("3-5-2", CSOpponentProfile.formationFor(club, backThree),
                "A 3-5-2 squad was given a shape it cannot execute. That is a random handicap, not tactics.");
    }

    @Test
    @DisplayName("An attacking-heavy squad is not given a defensive shape")
    void formationFollowsAttackers() {
        CSTeam club = SquadFixture.team(4L, "Goal Mouth FC");
        List<CSPlayer> frontHeavy = roster("GK", "DEF", "DEF", "DEF", "DEF", "MID", "WNG", "WNG", "ATT", "ATT", "ATT");

        String formation = CSOpponentProfile.formationFor(club, frontHeavy);

        assertTrue(formation.endsWith("3") || formation.endsWith("1"),
                "A squad with six forwards was given " + formation
                        + ", which sends out more defenders than it has strikers to support");
    }

    @Test
    @DisplayName("An empty roster degrades instead of throwing")
    void emptyRosterStillProducesTactics() {
        CSTactics tactics = CSOpponentProfile.tacticsFor(SquadFixture.team(9L, "Empty FC"), List.of());

        assertEquals("4-4-2", tactics.getFormation());
        assertEquals(CSOpponentProfile.styleFor(SquadFixture.team(9L, "Empty FC")), tactics.getStyle());
    }

    @Test
    @DisplayName("Describe renders something a manager can read")
    void describeIsHumanReadable() {
        CSTeam club = SquadFixture.team(2L, "FK Župa");
        List<CSPlayer> roster = roster("GK", "DEF", "DEF", "DEF", "DEF", "MID", "MID", "MID", "MID", "ATT", "ATT");

        String text = CSOpponentProfile.describe(club, roster);

        assertTrue(text.contains("·"), "Scouting line is missing its separator: " + text);
        assertTrue(Set.of("BALANCED", "ATTACKING", "DEFENSIVE", "COUNTER")
                        .contains(text.split("· ")[1].trim()),
                "Scouting line does not name a real style: " + text);
    }

    private static List<CSPlayer> roster(String... positions) {
        List<CSPlayer> squad = new ArrayList<>();
        for (int i = 0; i < positions.length; i++) {
            squad.add(SquadFixture.player(100 + i, "P" + i, positions[i], 12));
        }
        return squad.stream().collect(Collectors.toList());
    }
}