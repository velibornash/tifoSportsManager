package org.example.footballmanager.newLogic.model;

import org.example.footballmanager.newLogic.engine.MatchMetrics;
import org.example.footballmanager.newLogic.model.event.MatchEvent;

import java.util.*;

public class MatchState {
    public static final double MIN_X = 4.0, MAX_X = 96.0;
    public static final double MIN_Y = 6.0, MAX_Y = 94.0;
    public static final double OVERLAP_DUEL_DISTANCE = 2.0;

    public final Match match;
    public final List<PlayerSnapshot> playerSnapshots = new ArrayList<>();
    public BallState ball = BallState.at(50, 50);
    public Long carrierId;
    public String carrierTeamSide;
    public String possessionTeam;
    public int homePossessionTicks, awayPossessionTicks;
    public int tick;
    public int minute;
    public int homeGoals;
    public int awayGoals;

    public boolean ballInTransit;
    public double transitStartX, transitStartY;
    public double transitTargetX, transitTargetY;
    public int transitTicks, transitMaxTicks;
    public String transitMode;
    public Long pendingReceiverId;
    public Long pendingPasserId;
    public String pendingPassTeam;
    public boolean transitInterceptable;

    public String lastTouchTeam;
    public StoppageType stoppage;
    public int stoppageTicks;

    public final List<MatchEvent> events = new ArrayList<>();
    public final List<TickSnapshot> tickHistory = new ArrayList<>();
    public int homeSubsUsed, awaySubsUsed;

    public final Map<Long, Integer> playerFatigue = new HashMap<>();
    public final Map<Long, String> playerTeamSide = new HashMap<>();
    public final Map<Long, Integer> playerMinutes = new HashMap<>();
    public final Set<Long> injuredPlayers = new HashSet<>();
    public final Set<Long> sentOffPlayers = new HashSet<>();
    public final Map<Long, Integer> playerYellowCards = new HashMap<>();
    public final Map<Long, Integer> playerOffsideStreak = new HashMap<>();
    public final Map<Long, String> playerSlotKeys = new HashMap<>();

    public int lastPassTick = -100;
    public int lastDecisionTick = -100;
    public Long lastPassFromId, lastPassToId;
    public Long lastPasserId; // For assist tracking
    public Long pendingPenaltyTakerId;
    public Long kickoffTakerId;
    public Long restartTakerId;
    public String restartTeamSide;
    public String restartMode;
    public double restartBallX;
    public double restartBallY;

    public final Map<Long, BlendTarget> blendTargets = new HashMap<>();

    public PossessionPhase possessionPhase = PossessionPhase.BUILD_UP;
    public int possessionAgeTicks;
    public int possessionPassCount;
    public long possessionChainId;
    public String possessionTeamLabel;
    public int lastDuelTick = -100;
    public int lastShotTick = -100;

    // Offside state
    public boolean offsideActive;
    public String offsideTeam;
    public int offsideTick;

    // Consecutive pass tracking - prevents same 2 players passing back and forth
    public final List<Long> lastPasserIds = new ArrayList<>();
    public int consecutivePassCount;

    // Backward pass tracking - limits backward passes per possession
    public int backwardPassCount;
    public String lastBallAction = null; // e.g., "CARRY", "SHORT_PASS"
    public int lastBallActionStreak = 0;

    public MatchMetrics simulatorMetrics; // Reference to simulator metrics for tracking

    public MatchState(Match match) {
        this.match = match;
        for (var p : match.homeTeam().startingXI()) {
            playerTeamSide.put(p.id(), "HOME");
            playerMinutes.put(p.id(), 0);
        }
        for (var p : match.awayTeam().startingXI()) {
            playerTeamSide.put(p.id(), "AWAY");
            playerMinutes.put(p.id(), 0);
        }
    }

    public Player playerById(long id) {
        for (var p : match.homeTeam().startingXI()) if (p.id() == id) return p;
        for (var p : match.awayTeam().startingXI()) if (p.id() == id) return p;
        for (var p : match.homeTeam().substitutes()) if (p.id() == id) return p;
        for (var p : match.awayTeam().substitutes()) if (p.id() == id) return p;
        return null;
    }

    public PlayerSnapshot snapshotById(long id) {
        return playerSnapshots.stream().filter(s -> s.playerId() == id).findFirst().orElse(null);
    }

    public List<Player> homePlayers() { return match.homeTeam().startingXI(); }
    public List<Player> awayPlayers() { return match.awayTeam().startingXI(); }

    public List<PlayerSnapshot> homeSnapshots() {
        return playerSnapshots.stream().filter(s -> "HOME".equals(s.teamSide())).toList();
    }

    public List<PlayerSnapshot> awaySnapshots() {
        return playerSnapshots.stream().filter(s -> "AWAY".equals(s.teamSide())).toList();
    }

    public PlayerSnapshot ballCarrierSnapshot() {
        if (carrierId == null) return null;
        return snapshotById(carrierId);
    }

    public boolean isHomeTeam(long playerId) {
        return "HOME".equals(playerTeamSide.get(playerId));
    }

    public boolean isAwayTeam(long playerId) {
        return "AWAY".equals(playerTeamSide.get(playerId));
    }

    public String teamSideOf(long playerId) {
        return playerTeamSide.get(playerId);
    }

    public String oppositeTeam(String side) {
        return "HOME".equals(side) ? "AWAY" : "HOME";
    }

    public void recordTick() {
        tickHistory.add(TickSnapshot.capture(
            tick, minute, playerSnapshots, ball,
            carrierId, pendingReceiverId, ballInTransit,
            stoppage != null ? stoppage.name() : null
        ));
    }

    public void addEvent(MatchEvent event) {
        events.add(event);
    }

    public void clearPendingPass() {
        pendingReceiverId = null;
        pendingPasserId = null;
        pendingPassTeam = null;
    }

    public record BlendTarget(double targetX, double targetY, int ticksRemaining, int totalTicks) {
        public BlendTarget dec() { return new BlendTarget(targetX, targetY, ticksRemaining - 1, totalTicks); }
        public double progress() { return 1.0 - (double) ticksRemaining / totalTicks; }
    }

    public enum StoppageType {
        CORNER, FREE_KICK, THROW_IN, GOAL_KICK, PENALTY, VAR_REVIEW, GOAL_CELEBRATION, KICK_OFF
    }

    public enum PossessionPhase {
        BUILD_UP, PROGRESSION, FINAL_THIRD, BOX_CHAOS, TRANSITION
    }
}