package org.example.footballmanager.engines;

import org.example.footballmanager.dto.BallPositionDTO;
import org.example.footballmanager.dto.PlayerPositionDTO;
import org.example.footballmanager.model.Match;
import org.example.footballmanager.model.MatchRuntime;
import org.example.footballmanager.model.Player;
import org.example.footballmanager.model.Position;
import org.example.footballmanager.model.Skills;
import org.example.footballmanager.model.Team;
import org.example.footballmanager.model.event.GoalEvent;
import org.example.footballmanager.model.event.VARReviewEvent;
import org.example.footballmanager.service.FormationSlotCatalog;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MatchEngineTest {

    @Test
    void selectStartingPlayersKeepsSingleGoalkeeperEvenIfTwoArePreferred() {
        List<Player> pool = new ArrayList<>();
        pool.add(player(1L, Position.GK));
        pool.add(player(2L, Position.GK));
        pool.add(player(3L, Position.DEF));
        pool.add(player(4L, Position.DEF));
        pool.add(player(5L, Position.DEF));
        pool.add(player(6L, Position.DEF));
        pool.add(player(7L, Position.MID));
        pool.add(player(8L, Position.MID));
        pool.add(player(9L, Position.MID));
        pool.add(player(10L, Position.WNG));
        pool.add(player(11L, Position.WNG));
        pool.add(player(12L, Position.ATT));
        pool.add(player(13L, Position.ATT));

        List<Player> starters = MatchEngine.selectStartingPlayers(pool, List.of(2L, 1L, 12L, 13L, 7L, 8L, 9L, 3L, 4L, 5L, 6L));

        assertEquals(11, starters.size());
        assertEquals(1, starters.stream().filter(player -> player.getPosition() == Position.GK).count());
        assertEquals(2L, starters.get(0).getId());
        assertFalse(starters.stream().anyMatch(player -> player.getId().equals(1L)));
    }

    @Test
    void selectStartingPlayersUsesFormationAwareFallbackWhenTemplateOrderIsMissing() {
        List<Player> pool = new ArrayList<>(List.of(
                player(11L, Position.ATT),
                player(12L, Position.ATT),
                player(13L, Position.MID),
                player(14L, Position.WNG),
                player(1L, Position.GK),
                player(2L, Position.DEF),
                player(3L, Position.DEF),
                player(4L, Position.DEF),
                player(5L, Position.DEF),
                player(6L, Position.MID),
                player(7L, Position.MID),
                player(8L, Position.WNG),
                player(9L, Position.MID)
        ));

        List<Player> starters = MatchEngine.selectStartingPlayers(pool, List.of(), "4-4-2");

        assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 9L, 13L, 11L, 12L),
                starters.stream().map(Player::getId).toList());
    }

    @Test
    void looseBallTargetPrefersPlayerAlreadyInsideBallZone() throws Exception {
        RealisticMatchEngine engine = engine();
        Player inZone = player(10L, Position.MID);
        Player closerButAdjacent = player(11L, Position.MID);

        MatchRuntime rt = new MatchRuntime();
        rt.homePlayers = List.of(inZone, closerButAdjacent);
        rt.awayPlayers = List.of();
        rt.players = new ArrayList<>(List.of(
                new PlayerPositionDTO(10, "HOME", 73.5, 54.0, 0, 0),
                new PlayerPositionDTO(11, "HOME", 58.9, 49.8, 0, 0)
        ));
        rt.ball = new BallPositionDTO(71.0, 50.0);

        Player target = invokePlayerMethod(engine, "findLooseBallTarget", rt);

        assertSame(inZone, target);
    }

    @Test
    void looseBallTargetExpandsToAdjacentZoneWhenCurrentZoneIsEmpty() throws Exception {
        RealisticMatchEngine engine = engine();
        Player adjacent = player(10L, Position.DEF);
        Player farther = player(11L, Position.MID);

        MatchRuntime rt = new MatchRuntime();
        rt.homePlayers = List.of(adjacent, farther);
        rt.awayPlayers = List.of();
        rt.players = new ArrayList<>(List.of(
                new PlayerPositionDTO(10, "HOME", 69.0, 50.0, 0, 0),
                new PlayerPositionDTO(11, "HOME", 31.0, 18.0, 0, 0)
        ));
        rt.ball = new BallPositionDTO(50.0, 50.0);

        Player target = invokePlayerMethod(engine, "findLooseBallTarget", rt);

        assertSame(adjacent, target);
    }

    @Test
    void pendingReceiverStaysAnchoredDuringBallTransit() throws Exception {
        RealisticMatchEngine engine = engine();
        Player receiver = player(10L, Position.ATT);

        MatchRuntime rt = new MatchRuntime();
        rt.homePlayers = List.of(receiver);
        rt.awayPlayers = List.of();
        rt.players = new ArrayList<>(List.of(new PlayerPositionDTO(10, "HOME", 70.0, 50.0, 0, 0)));
        rt.ball = new BallPositionDTO(76.0, 50.0);
        rt.ballInTransit = true;
        rt.pendingReceiverId = 10;
        rt.lastTouchTeam = "HOME";
        rt.playerSlotKeys.put(10, "ST");
        rt.homeTacticalTargets = new HashMap<>();
        rt.homeTacticalTargets.put("ST|CELL_3_2|WE_HAVE_BALL", "CELL_4_2");

        invokeVoidMethod(engine, "updateSupportingMovement", rt);
        PlayerPositionDTO receiverPos = rt.players.getFirst();

        assertTrue(receiverPos.getX() > 70.0);
        assertEquals(72.4, receiverPos.getX(), 0.001);
        assertEquals(50.0, receiverPos.getY(), 0.001);
    }

    @Test
    void defensiveThreatFavorsDefenderReactionOverWingerReaction() throws Exception {
        RealisticMatchEngine engine = engine();
        Player defender = player(10L, Position.DEF);
        Player winger = player(11L, Position.WNG);
        Player carrier = player(20L, Position.ATT);

        MatchRuntime rt = new MatchRuntime();
        rt.homePlayers = List.of(defender, winger);
        rt.awayPlayers = List.of(carrier);
        rt.players = new ArrayList<>(List.of(
                new PlayerPositionDTO(10, "HOME", 30.0, 50.0, 0, 0),
                new PlayerPositionDTO(11, "HOME", 30.0, 20.0, 0, 0),
                new PlayerPositionDTO(20, "AWAY", 34.0, 50.0, 0, 0)
        ));
        rt.currentCarrier = new PlayerPositionDTO(20, "AWAY", 34.0, 50.0, 0, 0);

        Player defenderThreat = invokePlayerMethod(
                engine,
                "findDefensivePressureThreat",
                rt,
                rt.players.get(0),
                defender,
                new double[]{30.0, 50.0}
        );
        Player wingerThreat = invokePlayerMethod(
                engine,
                "findDefensivePressureThreat",
                rt,
                rt.players.get(1),
                winger,
                new double[]{30.0, 20.0}
        );

        assertSame(carrier, defenderThreat);
        assertNull(wingerThreat);
    }

    @Test
    void defensiveThreatExpandsToSecondZoneForNearGoalCarrier() throws Exception {
        RealisticMatchEngine engine = engine();
        Player midfielder = player(10L, Position.MID);
        Player carrier = player(20L, Position.ATT);

        MatchRuntime rt = new MatchRuntime();
        rt.homePlayers = List.of(midfielder);
        rt.awayPlayers = List.of(carrier);
        rt.players = new ArrayList<>(List.of(
                new PlayerPositionDTO(10, "HOME", 64.0, 50.0, 0, 0),
                new PlayerPositionDTO(20, "AWAY", 28.0, 50.0, 0, 0)
        ));
        rt.currentCarrier = new PlayerPositionDTO(20, "AWAY", 28.0, 50.0, 0, 0);

        Player threat = invokePlayerMethod(
                engine,
                "findDefensivePressureThreat",
                rt,
                rt.players.getFirst(),
                midfielder,
                new double[]{64.0, 50.0}
        );

        assertSame(carrier, threat);
    }

    @Test
    void overlapDetectionFindsDefenderOnCarrierSpot() throws Exception {
        RealisticMatchEngine engine = engine();
        Player attacker = player(9L, Position.ATT);
        Player defender = player(20L, Position.DEF);
        Player winger = player(21L, Position.WNG);

        MatchRuntime rt = new MatchRuntime();
        rt.homePlayers = List.of(attacker);
        rt.awayPlayers = List.of(winger, defender);
        rt.players = new ArrayList<>(List.of(
                new PlayerPositionDTO(9, "HOME", 60.0, 50.0, 0, 0),
                new PlayerPositionDTO(20, "AWAY", 60.0, 50.0, 0, 0),
                new PlayerPositionDTO(21, "AWAY", 60.0, 50.0, 0, 0)
        ));

        Player overlap = invokePlayerMethod(engine, "findImmediateBallPressureDefender", rt, attacker);

        assertSame(defender, overlap);
    }

    @Test
    void overlapDetectionAllowsSlightlyWiderVisualGap() throws Exception {
        RealisticMatchEngine engine = engine();
        Player attacker = player(9L, Position.ATT);
        Player defender = player(20L, Position.DEF);

        MatchRuntime rt = new MatchRuntime();
        rt.homePlayers = List.of(attacker);
        rt.awayPlayers = List.of(defender);
        rt.players = new ArrayList<>(List.of(
                new PlayerPositionDTO(9, "HOME", 60.0, 50.0, 0, 0),
                new PlayerPositionDTO(20, "AWAY", 61.4, 50.0, 0, 0)
        ));

        Player overlap = invokePlayerMethod(engine, "findImmediateBallPressureDefender", rt, attacker);

        assertSame(defender, overlap);
    }

    @Test
    void offsideStreakTriggersForcedRetreatAfterTwoTicks() throws Exception {
        RealisticMatchEngine engine = engine();
        Player attacker = player(9L, Position.ATT);
        Player defenderA = player(20L, Position.DEF);
        Player defenderB = player(21L, Position.DEF);

        MatchRuntime rt = new MatchRuntime();
        PlayerPositionDTO attackerPos = new PlayerPositionDTO(9, "HOME", 88.0, 50.0, 0, 0);
        rt.homePlayers = List.of(attacker);
        rt.awayPlayers = List.of(defenderA, defenderB);
        rt.players = new ArrayList<>(List.of(
                attackerPos,
                new PlayerPositionDTO(20, "AWAY", 72.0, 42.0, 0, 0),
                new PlayerPositionDTO(21, "AWAY", 74.0, 58.0, 0, 0)
        ));
        rt.lastTouchTeam = "HOME";

        double firstTarget = invokeDoubleMethod(engine, "applyOffsideTolerance", rt, attackerPos, 90.0, true);
        double secondTarget = invokeDoubleMethod(engine, "applyOffsideTolerance", rt, attackerPos, 90.0, true);

        assertEquals(70.2, firstTarget, 0.01);
        assertTrue(secondTarget < firstTarget - 10.0, () -> "Second offside tick should force a real retreat, got " + secondTarget);
        assertEquals(2, rt.offsideStreak.get(9));
        assertTrue(attackerPos.getRetreatTicksRemaining() > 0);
    }

    @Test
    void awayDefenderUsesDisciplinedLineInsteadOfCollapsingIntoGoalmouth() throws Exception {
        RealisticMatchEngine engine = engine();
        Player attacker = player(9L, Position.ATT);
        Player defender = player(20L, Position.DEF);

        MatchRuntime rt = new MatchRuntime();
        rt.homePlayers = List.of(attacker);
        rt.awayPlayers = List.of(defender);
        rt.players = new ArrayList<>(List.of(
                new PlayerPositionDTO(9, "HOME", 64.0, 50.0, 0, 0),
                new PlayerPositionDTO(20, "AWAY", 88.0, 50.0, 0, 0)
        ));
        rt.ball = new BallPositionDTO(64.0, 50.0);
        rt.lastTouchTeam = "HOME";

        double disciplinedTarget = invokeDoubleMethod(
                engine,
                "applyDefensiveLineDiscipline",
                rt,
                rt.players.get(1),
                defender,
                86.0,
                false,
                false
        );

        assertEquals(74.0, disciplinedTarget, 0.01);
    }

    @Test
    void offsideCheckOnlyFlagsActualTargetReceiver() throws Exception {
        RealisticMatchEngine engine = engine();
        Player passer = player(7L, Position.MID);
        Player intendedReceiver = player(9L, Position.ATT);
        Player unrelatedOffsideRunner = player(10L, Position.ATT);
        Player defenderA = player(20L, Position.DEF);
        Player defenderB = player(21L, Position.DEF);

        MatchRuntime rt = new MatchRuntime();
        rt.homePlayers = List.of(passer, intendedReceiver, unrelatedOffsideRunner);
        rt.awayPlayers = List.of(defenderA, defenderB);
        rt.players = new ArrayList<>(List.of(
                new PlayerPositionDTO(7, "HOME", 68.0, 50.0, 0, 0),
                new PlayerPositionDTO(9, "HOME", 72.0, 44.0, 0, 0),
                new PlayerPositionDTO(10, "HOME", 87.0, 60.0, 0, 0),
                new PlayerPositionDTO(20, "AWAY", 74.0, 40.0, 0, 0),
                new PlayerPositionDTO(21, "AWAY", 78.0, 58.0, 0, 0)
        ));

        boolean intendedReceiverOffside = (Boolean) invokeObjectMethod(engine, "isOffsideReceiver", rt, passer, intendedReceiver, "HOME");
        boolean unrelatedRunnerOffside = (Boolean) invokeObjectMethod(engine, "isOffsideReceiver", rt, passer, unrelatedOffsideRunner, "HOME");

        assertFalse(intendedReceiverOffside);
        assertTrue(unrelatedRunnerOffside);
    }

    @Test
    void offsideCheckIgnoresRunnerOutsideLikelyPassLane() throws Exception {
        RealisticMatchEngine engine = engine();
        Player passer = player(7L, Position.MID);
        Player wideRunner = player(10L, Position.ATT);
        Player defenderA = player(20L, Position.DEF);
        Player defenderB = player(21L, Position.DEF);

        MatchRuntime rt = new MatchRuntime();
        rt.homePlayers = List.of(passer, wideRunner);
        rt.awayPlayers = List.of(defenderA, defenderB);
        rt.players = new ArrayList<>(List.of(
                new PlayerPositionDTO(7, "HOME", 68.0, 50.0, 0, 0),
                new PlayerPositionDTO(10, "HOME", 83.0, 88.0, 0, 0),
                new PlayerPositionDTO(20, "AWAY", 74.0, 42.0, 0, 0),
                new PlayerPositionDTO(21, "AWAY", 78.0, 58.0, 0, 0)
        ));

        boolean wideRunnerOffside = (Boolean) invokeObjectMethod(engine, "isOffsideReceiver", rt, passer, wideRunner, "HOME");

        assertFalse(wideRunnerOffside);
    }

    @Test
    void goalVarReviewIsCreatedWhenChanceRollSucceeds() throws Exception {
        RealisticMatchEngine engine = engine();
        setField(engine, "random", new FixedRandom(0.0, 0.0));

        Team home = new Team();
        home.setName("Home");
        Team away = new Team();
        away.setName("Away");

        Match match = new Match();
        match.setHomeTeam(home);
        match.setAwayTeam(away);

        Player scorer = player(9L, Position.ATT);
        scorer.setTeam(home);

        GoalEvent goal = new GoalEvent();
        goal.setTeam(home);
        goal.setScorer(scorer);
        goal.setTick(12);
        goal.setMinute(18);
        goal.setScored(true);

        MatchRuntime rt = new MatchRuntime();
        rt.runtimeGoals.add(goal);
        rt.homeGoals = 1;

        invokeMethodWithTypes(
                engine,
                "maybeCreateVarReview",
                new Class<?>[]{GoalEvent.class, org.example.footballmanager.model.event.PenaltyEvent.class, MatchRuntime.class, Match.class, int.class},
                goal,
                null,
                rt,
                match,
                18
        );

        assertEquals(MatchRuntime.StoppageType.VAR_REVIEW, rt.activeStoppage);
        assertEquals(4, rt.stoppageTicks);
        assertEquals(1, rt.runtimeEvents.size());
        assertTrue(rt.runtimeEvents.getFirst() instanceof VARReviewEvent);

        VARReviewEvent reviewEvent = (VARReviewEvent) rt.runtimeEvents.getFirst();
        assertSame(goal, reviewEvent.getReviewedGoalEvent());
        assertEquals("Confirmed", reviewEvent.getDecision());
    }

    @Test
    void goalKickRestartPushesOpponentsOutsidePenaltyArea() throws Exception {
        RealisticMatchEngine engine = engine();
        Player goalkeeper = player(1L, Position.GK);
        Player homeDef = player(2L, Position.DEF);
        Player awayAtt = player(9L, Position.ATT);

        MatchRuntime rt = new MatchRuntime();
        rt.homePlayers = List.of(goalkeeper, homeDef);
        rt.awayPlayers = List.of(awayAtt);
        rt.players = new ArrayList<>(List.of(
                new PlayerPositionDTO(1, "HOME", 8.0, 50.0, 0, 0),
                new PlayerPositionDTO(2, "HOME", 18.0, 36.0, 0, 0),
                new PlayerPositionDTO(9, "AWAY", 14.0, 52.0, 0, 0)
        ));

        @SuppressWarnings("unchecked")
        Map<Integer, double[]> targetMap = (Map<Integer, double[]>) invokeObjectMethod(
                engine,
                "buildRestartTargetMap",
                rt,
                "HOME",
                "GOAL_KICK"
        );

        double[] attackerTarget = targetMap.get(9);
        assertTrue(attackerTarget[0] > 18.0 || attackerTarget[1] < 22.0 || attackerTarget[1] > 78.0,
                () -> "Opponent should be forced outside penalty area, got x=" + attackerTarget[0] + ", y=" + attackerTarget[1]);
        assertEquals(8.5, targetMap.get(1)[0], 0.01);
    }

    @Test
    void penaltySetupMovesSupportPlayersOutsideBoxAndKeepsOtherGoalkeeperBack() throws Exception {
        RealisticMatchEngine engine = engine();
        Player ownGoalkeeper = player(99L, Position.GK);
        Player taker = player(9L, Position.ATT);
        Player teammate = player(10L, Position.MID);
        Player goalkeeper = player(1L, Position.GK);
        Player defender = player(2L, Position.DEF);

        MatchRuntime rt = new MatchRuntime();
        rt.homePlayers = List.of(ownGoalkeeper, taker, teammate);
        rt.awayPlayers = List.of(goalkeeper, defender);
        rt.players = new ArrayList<>(List.of(
                new PlayerPositionDTO(99, "HOME", 68.0, 46.0, 0, 0),
                new PlayerPositionDTO(9, "HOME", 87.0, 50.0, 0, 0),
                new PlayerPositionDTO(10, "HOME", 86.0, 44.0, 0, 0),
                new PlayerPositionDTO(1, "AWAY", 95.0, 50.0, 0, 0),
                new PlayerPositionDTO(2, "AWAY", 88.0, 58.0, 0, 0)
        ));

        invokeVoidMethod(engine, "preparePenaltySetup", rt, taker, goalkeeper, "HOME", true);

        PlayerPositionDTO ownGoalkeeperPos = rt.players.get(0);
        PlayerPositionDTO takerPos = rt.players.get(1);
        PlayerPositionDTO teammatePos = rt.players.get(2);
        PlayerPositionDTO goalkeeperPos = rt.players.get(3);
        PlayerPositionDTO defenderPos = rt.players.get(4);

        assertEquals(8.5, ownGoalkeeperPos.getX(), 0.01);
        assertEquals(50.0, ownGoalkeeperPos.getY(), 0.01);
        assertEquals(88.0, takerPos.getX(), 0.01);
        assertEquals(50.0, takerPos.getY(), 0.01);
        assertEquals(96.0, goalkeeperPos.getX(), 0.01);
        assertEquals(50.0, goalkeeperPos.getY(), 0.01);
        assertTrue(teammatePos.getX() < 82.0, () -> "Attacking teammate must be outside box, got x=" + teammatePos.getX());
        assertTrue(defenderPos.getX() < 82.0, () -> "Defender must be outside box, got x=" + defenderPos.getX());
    }

    @Test
    void penaltyFoulLocationRequiresCentralDeeperBoxContact() throws Exception {
        RealisticMatchEngine engine = engine();

        boolean edgeBox = (Boolean) invokeObjectMethod(
                engine,
                "isPenaltyFoulLocation",
                new PlayerPositionDTO(9, "HOME", 84.5, 24.0, 0, 0),
                true
        );
        boolean centralBox = (Boolean) invokeObjectMethod(
                engine,
                "isPenaltyFoulLocation",
                new PlayerPositionDTO(9, "HOME", 88.0, 50.0, 0, 0),
                true
        );

        assertFalse(edgeBox);
        assertTrue(centralBox);
    }

    @Test
    void canShootNowAllowsCloseRangeCarrierWithoutFreshPassTrigger() throws Exception {
        RealisticMatchEngine engine = engine();
        Player shooter = player(9L, Position.ATT);

        MatchRuntime rt = new MatchRuntime();
        rt.homePlayers = List.of(shooter);
        rt.awayPlayers = List.of();
        rt.players = new ArrayList<>(List.of(new PlayerPositionDTO(9, "HOME", 77.0, 50.0, 0, 0)));
        rt.ball = new BallPositionDTO(77.0, 50.0);
        rt.currentCarrier = rt.players.getFirst();
        rt.lastTouchTeam = "HOME";
        rt.tick = 30;

        boolean canShoot = (Boolean) invokeObjectMethod(engine, "canShootNow", rt, shooter, "HOME");

        assertTrue(canShoot);
    }

    @Test
    void goalKickRestartPrefersDefenderAsVisibleTaker() throws Exception {
        RealisticMatchEngine engine = engine();
        Player goalkeeper = player(1L, Position.GK);
        Player centerBack = player(2L, Position.DEF);
        Player fullBack = player(3L, Position.DEF);

        MatchRuntime rt = new MatchRuntime();
        rt.homePlayers = List.of(goalkeeper, centerBack, fullBack);
        rt.awayPlayers = List.of();
        rt.players = new ArrayList<>(List.of(
                new PlayerPositionDTO(1, "HOME", 8.5, 50.0, 0, 0),
                new PlayerPositionDTO(2, "HOME", 18.0, 38.5, 0, 0),
                new PlayerPositionDTO(3, "HOME", 19.0, 72.0, 0, 0)
        ));

        Player taker = invokePlayerMethod(engine, "selectGoalKickTaker", rt, "HOME", 38.0);
        invokeVoidMethod(engine, "positionGoalKickActors", rt, "HOME", taker, 8.8, 38.0);

        assertEquals(2L, taker.getId());
        assertEquals(8.8, rt.ball.getX(), 0.01);
        assertEquals(38.0, rt.ball.getY(), 0.01);
        assertEquals(8.8, rt.players.get(1).getX(), 0.01);
        assertEquals(38.0, rt.players.get(1).getY(), 0.01);
        assertEquals(8.5, rt.players.getFirst().getX(), 0.01);
        assertEquals(50.0, rt.players.getFirst().getY(), 0.01);
    }

    @Test
    void restartResetAppliesImmediatelyWithoutSyntheticReplayTicks() throws Exception {
        RealisticMatchEngine engine = engine();
        Player homeGoalkeeper = player(1L, Position.GK);
        Player homeDefender = player(2L, Position.DEF);
        Player awayGoalkeeper = player(11L, Position.GK);
        Player awayAttacker = player(12L, Position.ATT);

        MatchRuntime rt = new MatchRuntime();
        rt.homePlayers = List.of(homeGoalkeeper, homeDefender);
        rt.awayPlayers = List.of(awayGoalkeeper, awayAttacker);
        rt.players = new ArrayList<>(List.of(
                new PlayerPositionDTO(1, "HOME", 45.0, 20.0, 2, 3),
                new PlayerPositionDTO(2, "HOME", 61.0, 63.0, 0, 0),
                new PlayerPositionDTO(11, "AWAY", 57.0, 82.0, 4, 1),
                new PlayerPositionDTO(12, "AWAY", 39.0, 42.0, 0, 0)
        ));
        rt.tick = 18;

        invokeVoidMethod(engine, "resetPositionsForRestart", rt, "HOME", "STANDARD");

        assertEquals(18, rt.tick);
        assertEquals(0, rt.tickStates.size());
        assertEquals(8.5, rt.players.get(0).getX(), 0.01);
        assertEquals(50.0, rt.players.get(0).getY(), 0.01);
        assertEquals(0, rt.players.get(0).getOffsideTicksRemaining());
        assertEquals(0, rt.players.get(0).getRetreatTicksRemaining());
        assertEquals(75.0, rt.players.get(2).getX(), 0.01);
        assertEquals(68.0, rt.players.get(2).getY(), 0.01);
    }

    @Test
    void goalKickRestartStillSnapsPlayersForSpecialRestart() throws Exception {
        RealisticMatchEngine engine = engine();
        Player homeGoalkeeper = player(1L, Position.GK);
        Player homeDefender = player(2L, Position.DEF);
        Player awayGoalkeeper = player(11L, Position.GK);
        Player awayAttacker = player(12L, Position.ATT);

        MatchRuntime rt = new MatchRuntime();
        rt.homePlayers = List.of(homeGoalkeeper, homeDefender);
        rt.awayPlayers = List.of(awayGoalkeeper, awayAttacker);
        rt.players = new ArrayList<>(List.of(
                new PlayerPositionDTO(1, "HOME", 45.0, 20.0, 0, 0),
                new PlayerPositionDTO(2, "HOME", 61.0, 63.0, 0, 0),
                new PlayerPositionDTO(11, "AWAY", 57.0, 82.0, 0, 0),
                new PlayerPositionDTO(12, "AWAY", 39.0, 42.0, 0, 0)
        ));

        invokeVoidMethod(engine, "resetPositionsForRestart", rt, "HOME", "GOAL_KICK");

        assertEquals(91.5, rt.players.get(2).getX(), 0.01);
        assertEquals(50.0, rt.players.get(2).getY(), 0.01);
    }

    @Test
    void kickoffRestartPlacesTwoOutfieldPlayersAroundCenterSpot() throws Exception {
        RealisticMatchEngine engine = engine();
        Player goalkeeper = player(1L, Position.GK);
        Player midfielder = player(6L, Position.MID);
        Player attacker = player(9L, Position.ATT);
        Player defender = player(2L, Position.DEF);

        MatchRuntime rt = new MatchRuntime();
        rt.homePlayers = List.of(goalkeeper, midfielder, attacker, defender);
        rt.awayPlayers = List.of(player(21L, Position.GK), player(22L, Position.DEF));
        rt.players = new ArrayList<>(List.of(
                new PlayerPositionDTO(1, "HOME", 8.5, 50.0, 0, 0),
                new PlayerPositionDTO(6, "HOME", 32.0, 55.0, 0, 0),
                new PlayerPositionDTO(9, "HOME", 44.0, 47.0, 0, 0),
                new PlayerPositionDTO(2, "HOME", 22.0, 40.0, 0, 0),
                new PlayerPositionDTO(21, "AWAY", 91.5, 50.0, 0, 0),
                new PlayerPositionDTO(22, "AWAY", 76.0, 54.0, 0, 0)
        ));

        @SuppressWarnings("unchecked")
        Map<Integer, double[]> targetMap = (Map<Integer, double[]>) invokeObjectMethod(
                engine,
                "buildRestartTargetMap",
                rt,
                "HOME",
                "KICKOFF"
        );

        assertEquals(50.0, targetMap.get(6)[0], 0.01);
        assertEquals(50.0, targetMap.get(6)[1], 0.01);
        assertEquals(47.0, targetMap.get(9)[0], 0.01);
        assertEquals(50.0, targetMap.get(9)[1], 0.01);
        assertEquals(8.5, targetMap.get(1)[0], 0.01);
        assertEquals(50.0, targetMap.get(1)[1], 0.01);
    }

    @Test
    void goalKickRestartCreatesShortPassingShapeForRestartTeam() throws Exception {
        RealisticMatchEngine engine = engine();
        Player goalkeeper = player(1L, Position.GK);
        Player upperDefender = player(2L, Position.DEF);
        Player lowerDefender = player(3L, Position.DEF);
        Player midfielder = player(4L, Position.MID);

        MatchRuntime rt = new MatchRuntime();
        rt.homePlayers = List.of(goalkeeper, upperDefender, lowerDefender, midfielder);
        rt.awayPlayers = List.of(player(9L, Position.ATT));
        rt.players = new ArrayList<>(List.of(
                new PlayerPositionDTO(1, "HOME", 8.5, 50.0, 0, 0),
                new PlayerPositionDTO(2, "HOME", 18.0, 35.0, 0, 0),
                new PlayerPositionDTO(3, "HOME", 18.0, 66.0, 0, 0),
                new PlayerPositionDTO(4, "HOME", 25.0, 44.0, 0, 0),
                new PlayerPositionDTO(9, "AWAY", 13.0, 50.0, 0, 0)
        ));

        @SuppressWarnings("unchecked")
        Map<Integer, double[]> targetMap = (Map<Integer, double[]>) invokeObjectMethod(
                engine,
                "buildRestartTargetMap",
                rt,
                "HOME",
                "GOAL_KICK"
        );

        assertEquals(16.0, targetMap.get(2)[0], 0.01);
        assertEquals(36.0, targetMap.get(2)[1], 0.01);
        assertEquals(16.0, targetMap.get(3)[0], 0.01);
        assertEquals(64.0, targetMap.get(3)[1], 0.01);
        assertEquals(22.0, targetMap.get(4)[0], 0.01);
        assertEquals(42.0, targetMap.get(4)[1], 0.01);
    }

    @Test
    void cornerDeliveryTargetsPenaltySpotZoneAndLoadsBoxForDuel() throws Exception {
        RealisticMatchEngine engine = engine();
        Player taker = player(7L, Position.WNG);
        Player striker = player(9L, Position.ATT);
        Player midfielder = player(8L, Position.MID);
        Player centerBack = player(4L, Position.DEF);
        Player awayGoalkeeper = player(20L, Position.GK);
        Player awayDef1 = player(21L, Position.DEF);
        Player awayDef2 = player(22L, Position.DEF);
        Player awayMid = player(23L, Position.MID);

        MatchRuntime rt = new MatchRuntime();
        rt.homePlayers = List.of(taker, striker, midfielder, centerBack);
        rt.awayPlayers = List.of(awayGoalkeeper, awayDef1, awayDef2, awayMid);
        rt.players = new ArrayList<>(List.of(
                new PlayerPositionDTO(7, "HOME", 72.0, 18.0, 0, 0),
                new PlayerPositionDTO(9, "HOME", 66.0, 46.0, 0, 0),
                new PlayerPositionDTO(8, "HOME", 58.0, 54.0, 0, 0),
                new PlayerPositionDTO(4, "HOME", 42.0, 38.0, 0, 0),
                new PlayerPositionDTO(20, "AWAY", 91.5, 50.0, 0, 0),
                new PlayerPositionDTO(21, "AWAY", 82.0, 44.0, 0, 0),
                new PlayerPositionDTO(22, "AWAY", 84.0, 56.0, 0, 0),
                new PlayerPositionDTO(23, "AWAY", 76.0, 50.0, 0, 0)
        ));
        rt.ball = new BallPositionDTO(98.5, 6.0);

        invokeVoidMethod(engine, "prepareCornerActors", rt, "HOME", true, taker, 98.5, 6.0);
        invokeVoidMethod(engine, "deliverCorner", rt, taker, "HOME", true);

        assertEquals(98.5, rt.players.getFirst().getX(), 0.01);
        assertEquals(6.0, rt.players.getFirst().getY(), 0.01);
        long packedInBox = rt.players.stream()
                .filter(pos -> pos.getId() != 7)
                .filter(pos -> pos.getX() >= 82.0 && pos.getX() <= 92.0)
                .filter(pos -> pos.getY() >= 37.0 && pos.getY() <= 63.0)
                .count();
        assertTrue(packedInBox >= 4, () -> "Expected multiple players loaded into the duel zone, got " + packedInBox);
        assertEquals("CROSS", rt.ballTransitMode);
        assertTrue(rt.ballTransitTargetX >= 86.0 && rt.ballTransitTargetX <= 90.0,
                () -> "Corner target x should stay around the penalty spot, got " + rt.ballTransitTargetX);
        assertTrue(rt.ballTransitTargetY >= 43.0 && rt.ballTransitTargetY <= 53.0,
                () -> "Upper-side corner target y should stay in central duel lane, got " + rt.ballTransitTargetY);
    }

    @Test
    void cornerRestartStagesApproachPauseBeforeCross() throws Exception {
        RealisticMatchEngine engine = engine();
        setField(engine, "random", new FixedRandom(0.0, 0.0));

        Team home = new Team();
        home.setName("Home");
        Team away = new Team();
        away.setName("Away");

        Match match = new Match();
        match.setHomeTeam(home);
        match.setAwayTeam(away);

        Player taker = player(7L, Position.WNG);
        Player striker = player(9L, Position.ATT);
        Player midfielder = player(8L, Position.MID);
        Player centerBack = player(4L, Position.DEF);
        Player awayGoalkeeper = player(20L, Position.GK);
        Player awayDef1 = player(21L, Position.DEF);
        Player awayDef2 = player(22L, Position.DEF);
        Player awayMid = player(23L, Position.MID);

        MatchRuntime rt = new MatchRuntime();
        rt.homePlayers = List.of(taker, striker, midfielder, centerBack);
        rt.awayPlayers = List.of(awayGoalkeeper, awayDef1, awayDef2, awayMid);
        rt.players = new ArrayList<>(List.of(
                new PlayerPositionDTO(7, "HOME", 72.0, 18.0, 0, 0),
                new PlayerPositionDTO(9, "HOME", 66.0, 46.0, 0, 0),
                new PlayerPositionDTO(8, "HOME", 58.0, 54.0, 0, 0),
                new PlayerPositionDTO(4, "HOME", 42.0, 38.0, 0, 0),
                new PlayerPositionDTO(20, "AWAY", 91.5, 50.0, 0, 0),
                new PlayerPositionDTO(21, "AWAY", 82.0, 44.0, 0, 0),
                new PlayerPositionDTO(22, "AWAY", 84.0, 56.0, 0, 0),
                new PlayerPositionDTO(23, "AWAY", 76.0, 50.0, 0, 0)
        ));
        rt.ball = new BallPositionDTO(98.5, 6.0);

        Player restartPlayer = (Player) invokeMethodWithTypes(
                engine,
                "setupCornerRestart",
                new Class<?>[]{MatchRuntime.class, Match.class, int.class, String.class, boolean.class},
                rt,
                match,
                18,
                "HOME",
                true
        );

        assertNotNull(restartPlayer);
        assertEquals(7, rt.tick);
        assertEquals(7, rt.tickStates.size());
        assertTrue(rt.tickStates.stream().allMatch(state -> "CORNER".equals(state.activeEventType)));
        assertTrue(rt.tickStates.getFirst().ball.getX() < 98.5, () -> "First staged tick should still show the taker approaching the flag.");
        assertEquals(98.5, rt.tickStates.getLast().ball.getX(), 0.01);
        assertEquals(6.0, rt.tickStates.getLast().ball.getY(), 0.01);
        assertEquals("CROSS", rt.ballTransitMode);
    }

    @Test
    void penaltyPauseRecordsReplayTicksWithPenaltyMarker() throws Exception {
        RealisticMatchEngine engine = engine();

        MatchRuntime rt = new MatchRuntime();
        rt.players = new ArrayList<>(List.of(new PlayerPositionDTO(9, "HOME", 88.0, 50.0, 0, 0)));
        rt.ball = new BallPositionDTO(88.0, 50.0);

        invokeVoidMethod(engine, "recordStoppagePause", rt, MatchRuntime.StoppageType.PENALTY, 4);

        assertEquals(4, rt.tickStates.size());
        assertEquals(4, rt.tick);
        assertTrue(rt.tickStates.stream().allMatch(state -> "PENALTY".equals(state.activeEventType)));
        assertNull(rt.activeStoppage);
        assertEquals(0, rt.stoppageTicks);
    }

    @Test
    void goalKickRestartAddsPauseBeforeBallRelease() throws Exception {
        RealisticMatchEngine engine = engine();

        Team home = new Team();
        home.setName("Home");
        Team away = new Team();
        away.setName("Away");

        Match match = new Match();
        match.setHomeTeam(home);
        match.setAwayTeam(away);

        Player goalkeeper = player(1L, Position.GK);
        Player centerBack = player(2L, Position.DEF);
        Player fullBack = player(3L, Position.DEF);

        MatchRuntime rt = new MatchRuntime();
        rt.homePlayers = List.of(goalkeeper, centerBack, fullBack);
        rt.awayPlayers = List.of(player(9L, Position.ATT));
        rt.players = new ArrayList<>(List.of(
                new PlayerPositionDTO(1, "HOME", 8.5, 50.0, 0, 0),
                new PlayerPositionDTO(2, "HOME", 18.0, 38.5, 0, 0),
                new PlayerPositionDTO(3, "HOME", 19.0, 72.0, 0, 0),
                new PlayerPositionDTO(9, "AWAY", 76.0, 50.0, 0, 0)
        ));
        rt.ball = new BallPositionDTO(8.8, 38.0);

        Player taker = (Player) invokeMethodWithTypes(
                engine,
                "setupGoalKickRestart",
                new Class<?>[]{MatchRuntime.class, Match.class, int.class, String.class, boolean.class, boolean.class},
                rt,
                match,
                22,
                "HOME",
                true,
                true
        );

        assertEquals(2L, taker.getId());
        assertEquals(3, rt.tick);
        assertEquals(3, rt.tickStates.size());
        assertTrue(rt.tickStates.stream().allMatch(state -> "GOAL_KICK".equals(state.activeEventType)));
        assertTrue(rt.ballInTransit);
    }

    @Test
    void goalkeeperTacticalTargetStaysNearGoalCenterEvenForWideBallState() throws Exception {
        RealisticMatchEngine engine = engine();
        Player goalkeeper = player(1L, Position.GK);

        MatchRuntime rt = new MatchRuntime();
        rt.homePlayers = List.of(goalkeeper);
        rt.awayPlayers = List.of();
        rt.players = new ArrayList<>(List.of(new PlayerPositionDTO(1, "HOME", 8.0, 50.0, 0, 0)));
        rt.ball = new BallPositionDTO(96.0, 94.0);
        rt.playerSlotKeys.put(1, "GK");
        rt.homeTacticalTargets = new HashMap<>();
        rt.homeTacticalTargets.put("GK|ATTACK_RIGHT_CORNER|" + FormationSlotCatalog.WE_HAVE_BALL, "CELL_4_4");

        double[] target = (double[]) invokeObjectMethod(
                engine,
                "resolveTacticalTarget",
                rt,
                rt.players.getFirst(),
                goalkeeper,
                true
        );

        assertTrue(target[0] >= 7.5 && target[0] <= 13.5,
                () -> "Goalkeeper x target should stay near goal, got " + target[0]);
        assertTrue(target[1] >= 44.0 && target[1] <= 56.0,
                () -> "Goalkeeper y target should stay near goal center, got " + target[1]);
    }

    @Test
    void goalkeeperFarFromCenterDoesNotCountAsShotCoverage() throws Exception {
        RealisticMatchEngine engine = engine();
        Player goalkeeper = player(1L, Position.GK);

        MatchRuntime rt = new MatchRuntime();
        rt.homePlayers = List.of(goalkeeper);
        rt.awayPlayers = List.of();
        rt.players = new ArrayList<>(List.of(new PlayerPositionDTO(1, "HOME", 8.0, 63.0, 0, 0)));

        boolean coveringWhenWide = (Boolean) invokeObjectMethod(engine, "isGoalkeeperProtectingGoal", rt, goalkeeper, "HOME");
        assertFalse(coveringWhenWide);

        rt.players.getFirst().setX(10.0);
        rt.players.getFirst().setY(50.0);
        boolean coveringWhenCentral = (Boolean) invokeObjectMethod(engine, "isGoalkeeperProtectingGoal", rt, goalkeeper, "HOME");
        assertTrue(coveringWhenCentral);
    }

    @Test
    void supportingMovementFreezesDuringCrossTransit() throws Exception {
        RealisticMatchEngine engine = engine();

        MatchRuntime rt = new MatchRuntime();
        Player attacker = player(9L, Position.ATT);
        Player defender = player(21L, Position.DEF);
        rt.homePlayers = List.of(attacker);
        rt.awayPlayers = List.of(defender);
        rt.players = new ArrayList<>(List.of(
                new PlayerPositionDTO(9, "HOME", 86.0, 46.0, 0, 0),
                new PlayerPositionDTO(21, "AWAY", 87.0, 53.0, 0, 0)
        ));
        rt.ball = new BallPositionDTO(90.0, 50.0);
        rt.ballInTransit = true;
        rt.ballTransitMode = "CROSS";

        double attackerX = rt.players.get(0).getX();
        double attackerY = rt.players.get(0).getY();
        double defenderX = rt.players.get(1).getX();
        double defenderY = rt.players.get(1).getY();

        invokeVoidMethod(engine, "updateSupportingMovement", rt);

        assertEquals(attackerX, rt.players.get(0).getX(), 0.001);
        assertEquals(attackerY, rt.players.get(0).getY(), 0.001);
        assertEquals(defenderX, rt.players.get(1).getX(), 0.001);
        assertEquals(defenderY, rt.players.get(1).getY(), 0.001);
    }

    @Test
    void crossArrivalTriggersDirectShotAfterAttackerWinsAerialDuel() throws Exception {
        RealisticMatchEngine engine = new RealisticMatchEngine(
                null, null, null, null, null, null, null, null, null,
                new AIDecisionMaker(),
                new PositionalDefense(),
                new DuelResolver(new FixedRandom(0.0, 0.0, 0.0, 0.0, 0.0, 0.0)),
                new RealisticEventGenerator(null),
                null
        );

        Team home = new Team();
        home.setName("Home");
        Team away = new Team();
        away.setName("Away");
        Match match = new Match();
        match.setHomeTeam(home);
        match.setAwayTeam(away);

        Player taker = player(7L, Position.WNG);
        Player striker = player(9L, Position.ATT);
        striker.getSkills().setStriker(18);
        striker.getSkills().setTechnique(18);
        striker.getSkills().setPace(17);
        Player defender = player(21L, Position.DEF);
        defender.getSkills().setDefender(3);
        defender.getSkills().setPace(3);

        MatchRuntime rt = new MatchRuntime();
        rt.homePlayers = List.of(taker, striker);
        rt.awayPlayers = List.of(defender);
        rt.players = new ArrayList<>(List.of(
                new PlayerPositionDTO(7, "HOME", 98.5, 6.0, 0, 0),
                new PlayerPositionDTO(9, "HOME", 87.6, 49.8, 0, 0),
                new PlayerPositionDTO(21, "AWAY", 88.4, 52.1, 0, 0)
        ));
        rt.ball = new BallPositionDTO(98.5, 6.0);
        rt.ballInTransit = true;
        rt.ballTransitMode = "CROSS";
        rt.ballTransitStartX = 98.5;
        rt.ballTransitStartY = 6.0;
        rt.ballTransitTargetX = 88.0;
        rt.ballTransitTargetY = 50.0;
        rt.ballTransitTicks = 0;
        rt.ballTransitMaxTicks = 1;
        rt.pendingPassTeam = "HOME";
        rt.pendingPasserId = 7;
        rt.lastTouchTeam = "HOME";

        invokeVoidMethod(engine, "resolveBallTransit", rt, match, 18);

        assertTrue(rt.runtimeEvents.stream().anyMatch(event -> "DuelEvent".equals(event.getClass().getSimpleName())));
        assertTrue(rt.runtimeEvents.stream().anyMatch(event -> List.of("GoalEvent", "ShotOnTargetEvent", "ShotOffTargetEvent")
                .contains(event.getClass().getSimpleName())));
        assertFalse("CROSS".equals(rt.ballTransitMode));
        assertNull(rt.pendingPassTeam);
    }

    @Test
    void crossArrivalStartsClearanceWhenDefenderWinsAerialDuel() throws Exception {
        RealisticMatchEngine engine = new RealisticMatchEngine(
                null, null, null, null, null, null, null, null, null,
                new AIDecisionMaker(),
                new PositionalDefense(),
                new DuelResolver(new FixedRandom(0.0, 0.99, 0.0, 0.0)),
                new RealisticEventGenerator(null),
                null
        );

        Team home = new Team();
        home.setName("Home");
        Team away = new Team();
        away.setName("Away");
        Match match = new Match();
        match.setHomeTeam(home);
        match.setAwayTeam(away);

        Player taker = player(7L, Position.WNG);
        Player attacker = player(9L, Position.ATT);
        attacker.getSkills().setStriker(3);
        attacker.getSkills().setTechnique(3);
        attacker.getSkills().setPace(3);
        Player defender = player(21L, Position.DEF);
        defender.getSkills().setDefender(18);
        defender.getSkills().setPace(17);

        MatchRuntime rt = new MatchRuntime();
        rt.homePlayers = List.of(taker, attacker);
        rt.awayPlayers = List.of(defender);
        rt.players = new ArrayList<>(List.of(
                new PlayerPositionDTO(7, "HOME", 98.5, 6.0, 0, 0),
                new PlayerPositionDTO(9, "HOME", 87.8, 50.2, 0, 0),
                new PlayerPositionDTO(21, "AWAY", 88.1, 49.3, 0, 0)
        ));
        rt.ball = new BallPositionDTO(98.5, 6.0);
        rt.ballInTransit = true;
        rt.ballTransitMode = "CROSS";
        rt.ballTransitStartX = 98.5;
        rt.ballTransitStartY = 6.0;
        rt.ballTransitTargetX = 88.0;
        rt.ballTransitTargetY = 50.0;
        rt.ballTransitTicks = 0;
        rt.ballTransitMaxTicks = 1;
        rt.pendingPassTeam = "HOME";
        rt.pendingPasserId = 7;
        rt.lastTouchTeam = "HOME";

        invokeVoidMethod(engine, "resolveBallTransit", rt, match, 18);

        assertTrue(rt.runtimeEvents.stream().anyMatch(event -> "DuelEvent".equals(event.getClass().getSimpleName())));
        assertTrue(rt.ballInTransit);
        assertEquals("CLEARANCE", rt.ballTransitMode);
        assertTrue(rt.ballTransitTargetX < 88.0,
                () -> "Away defender clearance should travel away from the home attack, got target x=" + rt.ballTransitTargetX);
        assertNull(rt.pendingPassTeam);
    }

    @Test
    void longWideForwardPassIsClassifiedAsCross() throws Exception {
        RealisticMatchEngine engine = engine();
        PlayerPositionDTO passer = new PlayerPositionDTO(7, "HOME", 66.0, 14.0, 0, 0);
        PlayerPositionDTO receiver = new PlayerPositionDTO(9, "HOME", 84.0, 49.0, 0, 0);

        Object mode = invokeObjectMethod(engine, "classifyPassTransitMode", passer, receiver, "HOME");

        assertEquals("CROSS", mode);
    }

    @Test
    void groundPassTargetKeepsSmallOutOfBoundsBuffer() throws Exception {
        RealisticMatchEngine engine = engine();

        double bounded = invokeDoubleMethod(engine, "boundTransitTarget", 95.2, 6.0, 94.0, "GROUND_PASS");

        assertTrue(bounded > 94.0, () -> "Ground pass near touchline should keep slight out-of-bounds buffer, got " + bounded);
        assertEquals(95.2, bounded, 0.001);
    }

    private Player player(Long id, Position position) {
        Skills skills = new Skills();
        skills.setPassing(10);
        skills.setTechnique(10);
        skills.setStriker(10);
        skills.setPace(10);
        skills.setDefender(10);

        Player player = new Player();
        player.setId(id);
        player.setName("P" + id);
        player.setPosition(position);
        player.setSkills(skills);
        player.setInjured(false);
        return player;
    }

    private RealisticMatchEngine engine() {
        return new RealisticMatchEngine(
                null, null, null, null, null, null, null, null, null,
                new AIDecisionMaker(),
                new PositionalDefense(),
                new DuelResolver(new Random(7L)),
                new RealisticEventGenerator(null),
                null
        );
    }

    private Player invokePlayerMethod(RealisticMatchEngine engine, String methodName, Object... args) throws Exception {
        Method method = RealisticMatchEngine.class.getDeclaredMethod(methodName, resolveTypes(args));
        method.setAccessible(true);
        return (Player) method.invoke(engine, args);
    }

    private void invokeVoidMethod(RealisticMatchEngine engine, String methodName, Object... args) throws Exception {
        Method method = RealisticMatchEngine.class.getDeclaredMethod(methodName, resolveTypes(args));
        method.setAccessible(true);
        method.invoke(engine, args);
    }

    private double invokeDoubleMethod(RealisticMatchEngine engine, String methodName, Object... args) throws Exception {
        Method method = RealisticMatchEngine.class.getDeclaredMethod(methodName, resolveTypes(args));
        method.setAccessible(true);
        return ((Number) method.invoke(engine, args)).doubleValue();
    }

    private Object invokeObjectMethod(RealisticMatchEngine engine, String methodName, Object... args) throws Exception {
        Method method = RealisticMatchEngine.class.getDeclaredMethod(methodName, resolveTypes(args));
        method.setAccessible(true);
        return method.invoke(engine, args);
    }

    private Object invokeMethodWithTypes(RealisticMatchEngine engine, String methodName, Class<?>[] parameterTypes, Object... args) throws Exception {
        Method method = RealisticMatchEngine.class.getDeclaredMethod(methodName, parameterTypes);
        method.setAccessible(true);
        return method.invoke(engine, args);
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private Class<?>[] resolveTypes(Object[] args) {
        Class<?>[] parameterTypes = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof Boolean) {
                parameterTypes[i] = boolean.class;
            } else if (args[i] instanceof Integer) {
                parameterTypes[i] = int.class;
            } else if (args[i] instanceof Double) {
                parameterTypes[i] = double.class;
            } else {
                parameterTypes[i] = args[i].getClass();
            }
        }
        return parameterTypes;
    }

    private static final class FixedRandom extends Random {
        private final double[] values;
        private int index = 0;

        private FixedRandom(double... values) {
            this.values = values.length == 0 ? new double[]{0.0} : values;
        }

        @Override
        public double nextDouble() {
            int currentIndex = Math.min(index, values.length - 1);
            index++;
            return values[currentIndex];
        }
    }
}