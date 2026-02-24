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

    // --- STATE JEDNOG MEČA ---
    public PlayerPositionDTO currentCarrier;
    public BallPositionDTO ball;
    public int possessionTicks = 0;
    public int spacePassCooldown = 0;
    public boolean isShooting = false;
    public boolean isRebounding = false;
    public double targetBallX;
    public double targetBallY;
    public int shotTicks = 0;
    public int reboundTicks = 0;
    public int maxReboundTicks;
    public int maxShotTicks = 0;
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
    public Tactics homeTactics ;
    public Tactics awayTactics ;
    public Crowd crowd = new Crowd();
    public Referee referee = new Referee();
    public Team homeTeam = new Team();
    public Team awayTeam =  new Team();
    public int tick = 0;
    public List<TickPositionSnapshot> positionHistory = new ArrayList<>();
    public List<BallPositionDTO> ballHistory = new ArrayList<>();

    public static class TickPositionSnapshot {
        public final int tick;
        public final List<PlayerPositionDTO> players; // kopija liste u tom trenutku
        public TickPositionSnapshot(int tick, List<PlayerPositionDTO> players) {
            this.tick = tick;
            this.players = players.stream()
                    .map(p -> new PlayerPositionDTO(p.getId(), p.getTeam(), p.getX(), p.getY(), 0,0))
                    .toList();
        }
    }
}