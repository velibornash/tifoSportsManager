package org.example.footballmanager.service;

import org.example.footballmanager.dto.BallPositionDTO;
import org.example.footballmanager.dto.PlayerPositionDTO;
import org.example.footballmanager.model.Crowd;
import org.example.footballmanager.model.Player;
import org.example.footballmanager.model.Referee;
import org.example.footballmanager.model.Team;
import org.example.footballmanager.model.event.GoalEvent;
import org.example.footballmanager.model.event.MatchEvent;
import org.example.footballmanager.model.tactics.Tactics;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class DemoMatchRuntime {

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

    // tick counter
    public int tick = 0;
}
