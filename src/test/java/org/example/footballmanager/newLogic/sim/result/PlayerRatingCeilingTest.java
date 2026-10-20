package org.example.footballmanager.newLogic.sim.result;

import org.example.footballmanager.newLogic.sim.model.Player;
import org.example.footballmanager.newLogic.sim.model.PlayerSkills;
import org.example.footballmanager.newLogic.sim.model.Position;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A 10.0 has to mean an exceptional match (owner, 2026-10-11).
 *
 * <p>The owner: *"pojedinacni rejting na utakmicama i dalje veoma lako igrac dobije 10.0 sto treba
 * biti EKSTREMNO DOBRA partija, nekoliko golova i asistencija a ne asistencija i par uklizavanja"* —
 * a player gets 10.0 far too easily, and it should be an extremely good performance, several goals and
 * assists, not an assist and a couple of sliding tackles.
 *
 * <p><b>Three defects, all measured on the formula itself.</b>
 *
 * <ol>
 *   <li>{@code onDuelWon} credited the <em>loser</em> of every duel with a tackle. A striker
 *       dispossessed eight times banked eight "tackles" and +0.64 of rating for being dribbled past,
 *       and the manager was shown those eight in a tackles column.</li>
 *   <li>Those three defensive terms were <b>unbounded</b>, and with ~268 duels a match — about twelve
 *       per player — they outweighed goals. A striker winning twelve and losing twelve banked +2.4,
 *       which is more than two goals.</li>
 *   <li>A substitute was reported as playing 90 minutes, so the minute factor was dead weight for
 *       everyone except a red card.</li>
 * </ol>
 *
 * <p>And no test anywhere covered this formula at all.
 */
class PlayerRatingCeilingTest {

    private static final int TICKS_PER_MINUTE = ProposalMatchOutcomeBuilder.TICKS_PER_MINUTE;
    private static final int FULL_MATCH = 90 * TICKS_PER_MINUTE;

    // --- the complaint, first ---

    @Test
    @DisplayName("one assist and a couple of dispossessions is not a 10")
    void anAssistAndTwoSlidingTacklesIsNotATen() throws Exception {
        ProposalStatsCollector stats = aMatch("HOME");
        // Exactly the owner's sentence: one assist, and the ordinary business of being dribbled past.
        stats.onPassCompleted("HOME", "h2", "h1");
        stats.onDuelWon("d1", "h1");
        stats.onDuelWon("d2", "h1");
        stats.onGoal("HOME", "h1", null);

        double rating = ratingOf(stats, "h1");

        assertTrue(rating < 9.0,
                "an assist and two dispossessions came out at " + rating + ". Ten is for a hat-trick or "
                        + "four assists, not a good half hour.");
    }

    @Test
    @DisplayName("an ordinary striker's duel volume does not carry him to the ceiling")
    void duelVolumeDoesNotCarryAMan() throws Exception {
        ProposalStatsCollector stats = aMatch("HOME");
        // Twelve duels each way: about what a match actually produces, about twelve per player.
        for (int i = 0; i < 12; i++) {
            stats.onDuelWon("h1", "a1");
            stats.onDuelWon("a1", "h1");
        }

        double rating = ratingOf(stats, "h1");

        assertTrue(rating < 8.0,
                "twelve duels won and twelve lost came out at " + rating + ". The volume is not the "
                        + "performance, and the unbounded terms made it one.");
    }

    @Test
    @DisplayName("10.0 needs three goals or the equivalent")
    void tenNeedsAnExceptionalMatch() throws Exception {
        ProposalStatsCollector hatTrick = aMatch("HOME");
        hatTrick.onGoal("HOME", "h1", null);
        hatTrick.onGoal("HOME", "h1", null);
        hatTrick.onGoal("HOME", "h1", null);
        hatTrick.onGoal("AWAY", "a1", null);
        hatTrick.onGoal("AWAY", "a1", null);
        hatTrick.onGoal("AWAY", "a1", null);
        hatTrick.onPassAttempt("HOME", "h1");
        hatTrick.onPassCompleted("HOME", "h2", "h1");
        hatTrick.onPassAttempt("HOME", "h1");
        hatTrick.onPassCompleted("HOME", "h2", "h1");
        hatTrick.onPassAttempt("HOME", "h1");
        hatTrick.onPassCompleted("HOME", "h2", "h1");

        double rating = ratingOf(hatTrick, "h1");
        assertTrue(rating >= 9.0,
                "a hat-trick in a 3-3 with three assists came out at " + rating + ". That is a "
                        + "performance and it should read like one.");
    }

    @Test
    @DisplayName("10.0 is still the ceiling, and reaching it needs more than a hat-trick")
    void tenIsStillHardToReach() throws Exception {
        ProposalStatsCollector four = aMatch("HOME");
        for (int i = 0; i < 4; i++) four.onGoal("HOME", "h1", null);
        for (int i = 0; i < 4; i++) {
            four.onPassAttempt("HOME", "h1");
            four.onPassCompleted("HOME", "h2", "h1");
        }
        four.onGoal("AWAY", "a1", null);
        four.onGoal("AWAY", "a1", null);
        four.onGoal("AWAY", "a1", null);
        four.onGoal("AWAY", "a1", null);

        assertEquals(10.0, ratingOf(four, "h1"),
                "four goals and four assists in a 4-0 is the match the scale is for, and it should "
                        + "still reach the ceiling rather than stopping just short of it");
    }

    // --- the team result, which had no dimension at all ---

    @Test
    @DisplayName("a defender of a 4-0 outrates a defender of a 0-4")
    void theTeamResultCounts() throws Exception {
        ProposalStatsCollector won = aMatch("HOME");
        won.onGoal("HOME", "h1", null);
        won.onGoal("HOME", "h1", null);
        won.onGoal("HOME", "h1", null);
        won.onGoal("HOME", "h1", null);

        ProposalStatsCollector lost = aMatch("HOME");
        for (int i = 0; i < 4; i++) lost.onGoal("AWAY", "a1", null);

        assertTrue(ratingOf(won, "d1") > ratingOf(lost, "d1"),
                "a defender rated " + ratingOf(lost, "d1") + " in a 0-4 and "
                        + ratingOf(won, "d1") + " in a 4-0. Nothing in the formula could tell them "
                        + "apart, because it had no team-result term at all.");
    }

    @Test
    @DisplayName("a blank is not a good game")
    void aBlankIsNotAGoodGame() throws Exception {
        ProposalStatsCollector nil = aMatch("HOME");

        assertTrue(ratingOf(nil, "d1") < 6.5,
                "a defender in a 0-0 came out at " + ratingOf(nil, "d1") + " with no reason on the "
                        + "screen. A blank is not a good game.");
    }

    // --- minutes, which were always 90 ---

    @Test
    @DisplayName("a substitute is not credited with ninety minutes")
    void aSubstitutePlaysTheMinutesHePlayed() throws Exception {
        ProposalStatsCollector stats = aMatch("HOME");
        // Comes on at the hour mark and stays on.
        stats.onSubstitution("HOME", "h1", "h9", false, 60 * TICKS_PER_MINUTE);

        int minutes = minutesOf(stats, "h9");

        assertEquals(30, minutes,
                "a substitute who came on at 60' was reported as playing " + minutes + " minutes. It "
                        + "was always 90, which made the minute factor dead weight for everyone except "
                        + "a red card.");
    }

    @Test
    @DisplayName("a substitute's rating is scaled by his minutes, not a starter's")
    void aSubstituteIsRatedForWhatHeDid() throws Exception {
        ProposalStatsCollector full = aMatch("HOME");
        full.onGoal("HOME", "d1", null);

        ProposalStatsCollector part = aMatch("HOME");
        part.onGoal("HOME", "d1", null);
        part.onSubstitution("HOME", "h1", "d1", false, 60 * TICKS_PER_MINUTE);

        assertTrue(ratingOf(part, "d1") < ratingOf(full, "d1"),
                "a starter who scored came out at " + ratingOf(full, "d1") + " and a substitute who "
                        + "scored the same goal at the same moment came out at " + ratingOf(part, "d1")
                        + ". The goal is the same and the time on the pitch is not.");
    }

    @Test
    @DisplayName("a red card still stops the minutes")
    void aRedCardStopsTheMinutes() throws Exception {
        assertTrue(minutesOfRedCardedPlayer() < 90,
                "a player sent off in the 70th was reported as playing a full match");
    }

    // --- helpers ---

    private int minutesOfRedCardedPlayer() throws Exception {
        ProposalStatsCollector stats = aMatch("HOME");
        sentOff(stats, "d1", 70 * TICKS_PER_MINUTE);
        return minutesOf(stats, "d1");
    }

    private int minutesOf(ProposalStatsCollector stats, String playerId) {
        for (String team : List.of("HOME", "AWAY")) {
            for (PlayerStats entry : stats.buildPlayerStats(team)) {
                if (playerId.equals(entry.playerId())) {
                    return entry.minutesPlayed();
                }
            }
        }
        throw new AssertionError("no player stats for " + playerId);
    }

    /**
     * The rating a named player came out at.
     *
     * <p>Read through the public outcome rather than by calling the private formula, because the
     * outcome is what the manager sees and a test on the private method would pass while the screen
     * showed something else — which is how the played-match page shipped invented numbers.
     */
    private double ratingOf(ProposalStatsCollector stats, String playerId) {
        for (String team : List.of("HOME", "AWAY")) {
            for (PlayerStats entry : stats.buildPlayerStats(team)) {
                if (playerId.equals(entry.playerId())) {
                    return entry.rating();
                }
            }
        }
        throw new AssertionError("no player stats for " + playerId);
    }

    /**
     * A sending-off at a known tick.
     *
     * <p>The collector records the card; the <em>player</em> carries the tick it happened on. A red card
     * was the one case the old minute calculation got right, so it is pinned rather than assumed -
     * and the red card is a half-hour to a full match of rating, which is worth knowing is still there.
     */
    private void sentOff(ProposalStatsCollector stats, String playerId, int atTick) {
        String team = playerId.startsWith("a") ? "AWAY" : "HOME";
        stats.onRedCard(team, playerId);
        for (Player player : simPlayers) {
            if (playerId.equals(player.getId())) {
                player.setSentOff(true);
                player.setSentOffTick(atTick);
            }
        }
    }

    private final List<Player> simPlayers = new ArrayList<>();

    /**
     * A collector with a full eleven either side, because that is what the engine hands it and what a
     * real match's numbers are drawn from.
     *
     * <p>Every id is deterministic - "h1", "a1" - so a failed assertion can name the player it is
     * about. {@code d1} is the defender whose rating the team-result tests compare.
     */
    private ProposalStatsCollector aMatch(String team) {
        ProposalStatsCollector stats = new ProposalStatsCollector("Home", "Away");
        List<Player> squad = new ArrayList<>();
        for (int i = 1; i <= 11; i++) {
            squad.add(aPlayer("h" + i, "HOME", i == 11 ? "GK" : "DEF"));
        }
        for (int i = 1; i <= 11; i++) {
            squad.add(aPlayer("a" + i, "AWAY", i == 11 ? "GK" : "ATT"));
        }
        squad.add(aPlayer("d1", "HOME", "DEF"));
        squad.add(aPlayer("h9", "HOME", "ATT"));
        simPlayers.addAll(squad);
        stats.registerPlayers(squad);
        return stats;
    }

    private Player aPlayer(String id, String team, String role) {
        return new Player(id, id, team, role, new Position(4, 3), new Position(2, 3),
                new PlayerSkills(12, 12, 12, 12, 12, 12, 12, 12));
    }
}
