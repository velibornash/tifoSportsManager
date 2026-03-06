package org.example.footballmanager.model;

import org.example.footballmanager.dto.BallPositionDTO;
import org.example.footballmanager.dto.PlayerPositionDTO;
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
    public int homeSubstitutionsUsed = 0;
    public int awaySubstitutionsUsed = 0;

    // Track which team touched the ball last ("HOME" or "AWAY")
    public String lastTouchTeam = "HOME";

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

        public TickState(int tick, List<PlayerPositionDTO> players, BallPositionDTO ball, int carrierId, String activeEventType) {
            this.tick = tick;
            this.players = players.stream()
                    .map(p -> new PlayerPositionDTO(p.getId(), p.getTeam(), p.getX(), p.getY(), 0, 0))
                    .toList();
            this.ball = new BallPositionDTO(ball.getX(), ball.getY());
            this.carrierId = carrierId;
            this.activeEventType = activeEventType;
        }
    }

    /** Record current state as a tick snapshot */
    public void recordTick() {
        int cId = currentCarrier != null ? currentCarrier.getId() : -1;
        String eventType = activeStoppage != null ? activeStoppage.name() : null;
        tickStates.add(new TickState(tick, players, ball, cId, eventType));
    }
}
