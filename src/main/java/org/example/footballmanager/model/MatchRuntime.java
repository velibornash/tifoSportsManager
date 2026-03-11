package org.example.footballmanager.model;

import org.example.footballmanager.dto.BallPositionDTO;
import org.example.footballmanager.dto.PlayerPositionDTO;
import org.example.footballmanager.dto.TacticsSlotDTO;
import org.example.footballmanager.model.event.GoalEvent;
import org.example.footballmanager.model.event.MatchEvent;
import org.example.footballmanager.model.tactics.Tactics;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MatchRuntime {

    // --- Single match state ---
    public PlayerPositionDTO currentCarrier;
    public BallPositionDTO ball;
    public int possessionTicks = 0;
    public int spacePassCooldown = 0;
    public boolean isShooting = false;
    public boolean isRebounding = false;
    public boolean isPassing = false;
    public double targetBallX;
    public double targetBallY;
    public int shotTicks = 0;
    public int reboundTicks = 0;
    public int passTicks = 0;
    public int maxPassTicks = 3;
    public Integer pendingReceiverId = null;
    public Integer pendingPasserId = null;
    public String pendingPassTeam = null;
    public boolean ballInTransit = false;
    public boolean ballTransitCanBeIntercepted = false;
    public double ballTransitTargetX = 50.0;
    public double ballTransitTargetY = 50.0;
    public double ballTransitStartX = 50.0;
    public double ballTransitStartY = 50.0;
    public int ballTransitTicks = 0;
    public int ballTransitMaxTicks = 0;
    public String ballTransitMode = "CONTROLLED";
    public Integer lastControllerId = null;
    public int lastControlTick = -100;
    public String lastControlSource = null;
    public double lastControlX = 50.0;
    public String lastControlTeam = null;

    public Integer lastPassReceiverId = null;
    public int lastPassReceiveTick = -100;
    public double lastPassReceiveX = 50.0;
    public String lastPassReceiveTeam = null;
    public Integer lastPassFromId = null;
    public Integer lastPassToId = null;
    public int lastPassPairTick = -100;
    public Integer previousPassFromId = null;
    public Integer previousPassToId = null;
    public int previousPassPairTick = -100;
    public Integer homeLastReceiverId = null;
    public Integer awayLastReceiverId = null;

    public Integer lastDuelWinnerId = null;
    public int lastDuelWinTick = -100;
    public double lastDuelWinX = 50.0;
    public String lastDuelWinTeam = null;

    public Integer lastRecoveryPlayerId = null;
    public int lastRecoveryTick = -100;
    public double lastRecoveryX = 50.0;
    public String lastRecoveryTeam = null;
    public double passQuality = 0.0;
    public int maxReboundTicks = 3;
    public int maxShotTicks = 4;
    public boolean attacksRightDuringShot;
    public Map<Integer, Integer> offsideStreak = new HashMap<>();
    public List<PlayerPositionDTO> players = new ArrayList<>();
    public List<MatchEvent> runtimeEvents = new ArrayList<>();
    public List<GoalEvent> runtimeGoals = new ArrayList<>();
    public int homeGoals;
    public int awayGoals;
    public int homePoints;
    public int awayPoints;
    public List<Player> home = new ArrayList<>();
    public List<Player> away = new ArrayList<>();
    public List<Player> homePlayers = new ArrayList<>();
    public List<Player> awayPlayers = new ArrayList<>();
    public Tactics homeTactics;
    public Tactics awayTactics;
    public Crowd crowd = new Crowd();
    public Referee referee = new Referee();
    public Team homeTeam = new Team();
    public Team awayTeam = new Team();
    public int tick = 0;
    public int ticksPerMinute = 27;
    public int nextDecisionTick = 0;
    public int reactionTicksRemaining = 0;

    // JPA Player references for event creation during simulation
    public List<Player> homeSquad = new ArrayList<>();
    public List<Player> awaySquad = new ArrayList<>();
    public Match matchRef;
    public Map<Long, Integer> playerMinutes = new HashMap<>();
    public Map<Long, String> playerTeamSide = new HashMap<>();
    public Map<Integer, String> playerSlotKeys = new HashMap<>();
    public Map<String, String> homeTacticalTargets = new HashMap<>();
    public Map<String, String> awayTacticalTargets = new HashMap<>();
    public List<TacticsSlotDTO> homeSlots = new ArrayList<>();
    public List<TacticsSlotDTO> awaySlots = new ArrayList<>();
    public int homeSubstitutionsUsed = 0;
    public int awaySubstitutionsUsed = 0;

    // Track which team touched the ball last ("HOME" or "AWAY")
    public String lastTouchTeam = "HOME";
    
    // Flag to indicate if a pass was completed in this simulation phase
    public boolean passCompletedThisPhase = false;

    // Active stoppage state (corner, free kick, throw-in, var review)
    public StoppageType activeStoppage = null;
    public int stoppageTicks = 0;

    // Tick-level recording for replay
    public List<TickState> tickStates = new ArrayList<>();
    public String restartTeam;
    public boolean kickoffFromCenter = false;

    public enum StoppageType {
        CORNER, FREE_KICK, THROW_IN, GOAL_KICK, PENALTY, VAR_REVIEW, GOAL_CELEBRATION
    }

    /**
     * Full snapshot of a single tick for replay.
     */
    public static class TickState {
        public final int tick;
        public final List<PlayerPositionDTO> players;
        public final BallPositionDTO ball;
        public final int carrierId;
        public final String activeEventType; // null if no event active
        public final boolean ballInTransit;  // NEW: tracks if ball is being passed
        public final int pendingReceiverId;  // NEW: tracks who will receive the ball if in transit

        public TickState(int tick, List<PlayerPositionDTO> players, BallPositionDTO ball, int carrierId, String activeEventType) {
            this(tick, players, ball, carrierId, activeEventType, false, -1);
        }

        public TickState(int tick, List<PlayerPositionDTO> players, BallPositionDTO ball, int carrierId, String activeEventType, boolean ballInTransit) {
            this(tick, players, ball, carrierId, activeEventType, ballInTransit, -1);
        }

        public TickState(int tick, List<PlayerPositionDTO> players, BallPositionDTO ball, int carrierId, String activeEventType, boolean ballInTransit, int pendingReceiverId) {
            this.tick = tick;
            this.players = players.stream()
                    .map(p -> new PlayerPositionDTO(p.getId(), p.getTeam(), p.getX(), p.getY(), 0, 0))
                    .toList();
            this.ball = new BallPositionDTO(ball.getX(), ball.getY());
            this.carrierId = carrierId;
            this.activeEventType = activeEventType;
            this.ballInTransit = ballInTransit;
            this.pendingReceiverId = pendingReceiverId;
        }
    }

    /** Record current state as a tick snapshot */
    public void recordTick() {
        int cId = currentCarrier != null ? currentCarrier.getId() : -1;
        String eventType = activeStoppage != null ? activeStoppage.name() : null;
        int pReceiverId = pendingReceiverId != null ? pendingReceiverId : -1;
        tickStates.add(new TickState(tick, players, ball, cId, eventType, ballInTransit, pReceiverId));
    }
}
