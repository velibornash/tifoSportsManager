package org.example.footballmanager.newLogic.sim;

import org.example.footballmanager.newLogic.model.Lineup;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.PlayerRole;
import org.example.footballmanager.newLogic.model.Position;
import org.example.footballmanager.newLogic.sim.engine.MatchOrchestrator;
import org.example.footballmanager.newLogic.sim.result.ProposalMatchOutcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A side that never gets the ball is not a football match (owner, 2026-10-10).
 *
 * <p><b>What was seen.</b> OFK Omladinac 2-0 OFK Proleter Apatin, shown on the Stats tab as **91%
 * possession, 10 shots to 1, 0 fouls by the away side** — and the user did not believe it. It was right
 * to be suspicious. Reading that match's event log settled it: **not one of its 25 events belonged to the
 * away side.** Every shot, every restart, all home. The number was not a display bug; the *simulation* had
 * given one team the ball for ninety minutes.
 *
 * <p><b>How rare it is.</b> Across the 707 matches simulated on the owner's database, average possession
 * is 48.1% home and only **6 matches (0.8%)** fall outside 20-80%. Match 80 is one of them, and one of
 * only **2** where the away side committed no foul at all. So this is not a systematic inversion — it is
 * a tail case, and a tail case that produces a nonsense scoreline.
 *
 * <p><b>What is asserted here.</b> Not that possession is near 50 — a legitimate 2-0 can be lopsided.
 * That is **every side gets the ball at all**, over many simulated matches. A match where one side has
 * effectively no possession is a broken match regardless of what the scoreline says, and it is exactly
 * what a reader cannot tell apart from a real one-sided game.
 */
class BothSidesGetTheBallTest {

    private static final int MATCHES = 40;

    @Test
    @DisplayName("neither side is starved of the ball across a batch of matches")
    void everySideGetsTheBall() {
        Random random = new Random(20261010L);
        List<String> starved = new ArrayList<>();
        double worst = 100.0;

        for (int i = 0; i < MATCHES; i++) {
            MatchOrchestrator orchestrator = SimMatchRunner.run(
                    "Home " + i, "Away " + i, 3600,
                    RealSquadFactory.buildSquad(lineupWith(eleven(random, 70 + random.nextInt(10))), "HOME"),
                    RealSquadFactory.buildSquad(lineupWith(eleven(random, 70 + random.nextInt(10))), "AWAY"));

            ProposalMatchOutcome outcome = orchestrator.buildOutcome();
            double home = outcome.possessionHome();
            double away = outcome.possessionAway();

            worst = Math.min(worst, Math.min(home, away));
            // 5% is the line. A real lopsided game bottoms out well above it; the match the owner was
            // shown had the away side on 9.4% and not one event in ninety minutes.
            if (home < 5.0 || away < 5.0) {
                starved.add("match " + i + ": " + home + "% / " + away + "% ("
                        + outcome.homeGoals() + "-" + outcome.awayGoals() + ")");
            }
        }

        assertTrue(starved.isEmpty(),
                starved.size() + " of " + MATCHES + " simulated matches gave one side under 5% of the "
                        + "possession. That is the shape of the 2-0 the owner was shown: a side with no "
                        + "possession produces no shots, no fouls and no events, so the scoreline looks "
                        + "like a result rather than a failure. Worst seen: " + worst + "%.");
    }

    @Test
    @DisplayName("no side is ever shut out of the match entirely")
    void noSideIsShutOut() {
        // The owner's ruling (2026-10-10): a 0-event match is never acceptable. Not rare, not a tail —
        // never. The match that prompted it was 2-0 at 90.6% possession where the away side produced no
        // shot, no foul and no event of its own in ninety minutes.
        //
        // The fix was to make the press ordered rather than reactive: TYPE A used to require a defender
        // to already be within 1.5 cells of the carrier, so a side keeping the ball out of press range
        // never attracted one. The threshold now decides who is *sent*, not whether anyone is.
        Random random = new Random(4242L);
        List<String> shutOut = new ArrayList<>();
        int total = 60;

        for (int i = 0; i < total; i++) {
            ProposalMatchOutcome outcome = SimMatchRunner.run(
                    "Home " + i, "Away " + i, 3600,
                    RealSquadFactory.buildSquad(lineupWith(eleven(random, 70 + random.nextInt(8))), "HOME"),
                    RealSquadFactory.buildSquad(lineupWith(eleven(random, 70 + random.nextInt(8))), "AWAY"))
                    .buildOutcome();

            for (ProposalMatchOutcome.TeamOutcome side : List.of(outcome.homeStats(), outcome.awayStats())) {
                if (side == null) {
                    continue;
                }
                // A side with no shot, no foul, no corner, no card and no substitution produced nothing
                // at all. That is the shape of the match the owner was shown.
                int actions = side.shots() + side.fouls() + side.corners()
                        + side.yellowCards() + side.redCards() + side.offsides()
                        + side.clearances() + side.interceptions() + side.throwIns()
                        + side.passesAttempted() + side.saves() + side.penalties();
                if (actions == 0) {
                    shutOut.add(side.teamName() + " in match " + i + " produced no action of any kind");
                }
            }
        }

        assertTrue(shutOut.isEmpty(),
                shutOut.size() + " of " + total + " matches had a side that did nothing at all: "
                        + shutOut.stream().limit(4).toList()
                        + ". A side with no shot, foul, corner, card or offside had the ball for ninety "
                        + "minutes without anyone being able to take it off them.");
    }

    @Test
    @DisplayName("possession always adds up to a whole match")
    void possessionSumsToOneHundred() {
        Random random = new Random(7L);
        for (int i = 0; i < 10; i++) {
            ProposalMatchOutcome outcome = SimMatchRunner.run(
                    "H" + i, "A" + i, 3600,
                    RealSquadFactory.buildSquad(lineupWith(eleven(random, 72)), "HOME"),
                    RealSquadFactory.buildSquad(lineupWith(eleven(random, 72)), "AWAY")).buildOutcome();

            double total = outcome.possessionHome() + outcome.possessionAway();
            assertTrue(Math.abs(total - 100.0) < 0.5,
                    "possession was " + outcome.possessionHome() + " + " + outcome.possessionAway()
                            + " = " + total + ". A match where the two sides do not account for all of it "
                            + "is one where some ticks were counted for neither team.");
        }
    }

    // ── fixtures ────────────────────────────────────────────────────────────────────────────────────

    private static List<Player> eleven(Random random, int rating) {
        PlayerRole[] roles = {
                PlayerRole.GOALKEEPER,
                PlayerRole.LEFT_BACK, PlayerRole.CENTRE_BACK, PlayerRole.RIGHT_CENTRE_BACK, PlayerRole.RIGHT_BACK,
                PlayerRole.DEFENSIVE_MIDFIELDER, PlayerRole.DEFENSIVE_MIDFIELDER,
                PlayerRole.CENTRE_MIDFIELDER, PlayerRole.CENTRE_MIDFIELDER,
                PlayerRole.STRIKER, PlayerRole.STRIKER,
        };
        List<Player> out = new ArrayList<>();
        for (PlayerRole role : roles) {
            Player player = new Player();
            player.setName(role.name());
            player.setRole(role);
            player.setPosition(positionFor(role));
            player.setRating(rating + random.nextInt(5) - 2);
            out.add(player);
        }
        return out;
    }

    private static Position positionFor(PlayerRole role) {
        return switch (role) {
            case GOALKEEPER -> Position.GK;
            case LEFT_BACK, RIGHT_BACK, LEFT_WING_BACK, RIGHT_WING_BACK, CENTRE_BACK, RIGHT_CENTRE_BACK -> Position.DEF;
            case WINGER, WIDE_MIDFIELDER -> Position.WNG;
            case STRIKER, SECOND_STRIKER -> Position.ATT;
            default -> Position.MID;
        };
    }

    private static Lineup lineupWith(List<Player> starters) {
        Lineup lineup = new Lineup();
        lineup.setFormation("4-4-2");
        lineup.setStyle("Balanced");
        lineup.getStartingPlayers().addAll(starters);
        return lineup;
    }
}