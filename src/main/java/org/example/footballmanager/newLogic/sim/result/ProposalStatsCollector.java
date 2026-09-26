package org.example.footballmanager.newLogic.sim.result;

import org.example.footballmanager.newLogic.sim.model.MatchState;
import org.example.footballmanager.newLogic.sim.model.Player;

import java.util.*;

/**
 * Collects per-team and per-player match statistics during simulation.
 * Fed explicitly from orchestrator call sites (mirrors demo/service MatchStatsCollector).
 * PROPOSAL_CURRENT_STATE.md
 */
public class ProposalStatsCollector {

    private final Map<String, TeamAcc> teams = new LinkedHashMap<>();
    private final Map<String, PlayerAcc> players = new LinkedHashMap<>();
    private String homeName = "Home FC";
    private String awayName = "Away United";

    // Assist tracking is supplied explicitly by MatchState at goal time.
    // The collector must not keep an independent, stale passer context.
    public int getPenalties(String team) {
        TeamAcc ta = teams.get(team);
        return ta != null ? ta.penalties : 0;
    }

    // Possession ticks
    private int homePossTicks;
    private int awayPossTicks;

    // Possession chains (per team: count, accumulated ticks, longest tick count)
    private String currentPossTeam;
    private int currentChainTicks;
    private final int[] posChainCount = new int[2];  // [HOME, AWAY]
    private final int[] posChainTicks = new int[2];
    private final int[] posLongest = new int[2];
    private final List<PossessionChain> possessionChains = new ArrayList<>();
    private long nextChainId = 1L;
    private long currentChainId;
    private int currentChainPassCount;

    public record PossessionChain(long chainId, String team, int ticks, int passCount) {
    }

    public ProposalStatsCollector(String homeName, String awayName) {
        this.homeName = homeName;
        this.awayName = awayName;
        teams.put("HOME", new TeamAcc(homeName));
        teams.put("AWAY", new TeamAcc(awayName));
    }

    public void registerPlayers(List<Player> players) {
        for (Player p : players) {
            this.players.put(p.getId(), new PlayerAcc(p));
        }
    }

    public void setDisplayNames(String homeName, String awayName) {
        this.homeName = homeName;
        this.awayName = awayName;
        teams.get("HOME").teamName = homeName;
        teams.get("AWAY").teamName = awayName;
    }

    public String getHomeName() { return homeName; }
    public String getAwayName() { return awayName; }

    // ==================== FEED METHODS ====================

    /** Call from decision block when PASS is executed. */
    public void onPassAttempt(String team, String passerId) {
        TeamAcc ta = teams.get(team);
        if (ta != null) ta.passesAttempted++;
        PlayerAcc pa = players.get(passerId);
        if (pa != null) pa.passesAttempted++;
    }

    /** Call from RECEIVE result (pass completed). */
    public void onPassCompleted(String team, String receiverId, String passerId) {
        TeamAcc ta = teams.get(team);
        if (ta != null) ta.passesCompleted++;
        if (passerId != null) {
            PlayerAcc passer = players.get(passerId);
            if (passer != null) passer.passesCompleted++;
        }
        if (currentPossTeam != null && currentPossTeam.equals(team)) {
            currentChainPassCount++;
        }
    }

    /** Call from decision block when SHOT is executed. */
    public void onShot(String team, String shooterId, boolean onTarget) {
        TeamAcc ta = teams.get(team);
        if (ta != null) {
            ta.shots++;
            if (onTarget) ta.shotsOnTarget++;
        }
        PlayerAcc pa = players.get(shooterId);
        if (pa != null) {
            pa.shots++;
            if (onTarget) pa.shotsOnTarget++;
        }
    }

    /** Call from decision block when DRIBBLE is executed (changed = true). */
    /** A THROUGH BALL played into the space behind the defence. */
    public void onThroughBall(String team, String playerId) {
        TeamAcc ta = teams.get(team);
        if (ta != null) ta.throughBalls++;
        PlayerAcc pa = players.get(playerId);
        if (pa != null) pa.throughBalls++;
    }

    /** A CROSS from the flank into the box. */
    public void onCross(String team, String playerId) {
        TeamAcc ta = teams.get(team);
        if (ta != null) ta.crosses++;
        PlayerAcc pa = players.get(playerId);
        if (pa != null) pa.crosses++;
    }

    /** A CENTER — lofted delivery into the middle of the box. */
    public void onCenter(String team, String playerId) {
        TeamAcc ta = teams.get(team);
        if (ta != null) ta.centers++;
        PlayerAcc pa = players.get(playerId);
        if (pa != null) pa.centers++;
    }

    public void onDribble(String team, String playerId) {
        TeamAcc ta = teams.get(team);
        if (ta != null) ta.dribbles++;
        PlayerAcc pa = players.get(playerId);
        if (pa != null) pa.dribbles++;
    }

    /** Call from decision block when CLEAR is executed. */
    public void onClearance(String team, String playerId) {
        TeamAcc ta = teams.get(team);
        if (ta != null) ta.clearances++;
        PlayerAcc pa = players.get(playerId);
        if (pa != null) pa.clearances++;
    }

    /** Offside given away by this team (the attacking side that was caught). */
    public void onOffside(String team) {
        TeamAcc ta = teams.get(team);
        if (ta != null) ta.offsides++;
    }

    /** Call from INTERCEPT result. */
    public void onInterception(String team, String interceptorId) {
        TeamAcc ta = teams.get(team);
        if (ta != null) ta.interceptions++;
        PlayerAcc pa = players.get(interceptorId);
        if (pa != null) pa.interceptions++;
    }

    /** Call from DEFLECT result. */
    public void onDeflect(String team) {
        TeamAcc ta = teams.get(team);
        if (ta != null) ta.deflections++;
    }

    /** Call from SAVE result. */
    public void onSave(String gkTeam) {
        TeamAcc ta = teams.get(gkTeam);
        if (ta != null) ta.saves++;
        PlayerAcc gk = players.values().stream()
                .filter(p -> p.player.getTeam().equals(gkTeam) && "GK".equals(p.player.getRole()))
                .findFirst().orElse(null);
        if (gk != null) gk.saves++;
    }

    /** Call from BLOCK result (outfield defender blocks shot). */
    public void onBlock(String team) {
        TeamAcc ta = teams.get(team);
        if (ta != null) ta.blocks++;
    }

    /** Call from GOAL result. */
    public void onGoal(String scorerTeam, String scorerId, String assistId) {
        TeamAcc ta = teams.get(scorerTeam);
        if (ta != null) ta.goals++;
        PlayerAcc scorer = players.get(scorerId);
        if (scorer != null) scorer.goals++;
        if (assistId != null && !assistId.equals(scorerId)) {
            PlayerAcc assist = players.get(assistId);
            if (assist != null && assist.player.getTeam().equals(scorerTeam)
                    && !"GK".equals(assist.player.getRole())) {
                assist.assists++;
            }
        }
    }

    /** Call from OOB_RESTART result (restart type string). */
    public void onRestart(String restartType) {
        String team = restartType.endsWith("_HOME") ? "HOME"
                : restartType.endsWith("_AWAY") ? "AWAY" : null;
        if (team == null) return;
        TeamAcc ta = teams.get(team);
        if (ta == null) return;
        if (restartType.startsWith("CORNER")) ta.corners++;
        else if (restartType.startsWith("GOAL_KICK")) ta.goalKicks++;
        else if (restartType.startsWith("THROW_IN")) ta.throwIns++;
    }

    /** Call from DUEL result. */
    public void onDuelWon(String winnerId, String loserId) {
        PlayerAcc w = players.get(winnerId);
        if (w != null) w.duelsWon++;
        PlayerAcc l = players.get(loserId);
        if (l != null) l.tackles++; // defensive action by loser = tackle attempt
    }

    /** Call from InjuryService. */
    public void onInjury(String team, String playerId, String type, int days) {
        TeamAcc ta = teams.get(team);
        if (ta != null) ta.injuries++;
        PlayerAcc pa = players.get(playerId);
        if (pa != null) { pa.injuries++; pa.injuryDays = days; }
    }

    /** Call from SubstitutionService. */
    public void onSubstitution(String team, String playerOffId, String playerOnId, boolean forced) {
        TeamAcc ta = teams.get(team);
        if (ta != null) ta.substitutions++;
        PlayerAcc off = players.get(playerOffId);
        if (off != null) off.subbedOff = true;
        PlayerAcc on = players.get(playerOnId);
        if (on != null) on.subbedOn = true;
    }

    /** Call from DisciplineService after a confirmed foul. */
    public void onFoul(String team, String playerId) {
        TeamAcc ta = teams.get(team);
        if (ta != null) ta.fouls++;
        PlayerAcc pa = players.get(playerId);
        if (pa != null) pa.foulsCommitted++;
    }

    /** Call after a confirmed yellow card. */
    public void onYellowCard(String team, String playerId) {
        TeamAcc ta = teams.get(team);
        if (ta != null) ta.yellowCards++;
        PlayerAcc pa = players.get(playerId);
        if (pa != null) pa.yellowCards++;
    }

    /** Call after a confirmed red card. */
    public void onRedCard(String team, String playerId) {
        TeamAcc ta = teams.get(team);
        if (ta != null) ta.redCards++;
        PlayerAcc pa = players.get(playerId);
        if (pa != null) pa.redCards++;
    }

    /** Call from Penalty result (penalty kick awarded). */
    public void onPenalty(String team) {
        TeamAcc ta = teams.get(team);
        if (ta != null) ta.penalties++;
    }

    /** Call from possession tick tracking (carrier != null). */
    public void onPossessionTick(String carrierTeam) {
        int idx;
        if ("HOME".equals(carrierTeam)) {
            homePossTicks++;
            idx = 0;
        } else if ("AWAY".equals(carrierTeam)) {
            awayPossTicks++;
            idx = 1;
        } else {
            closeCurrentChain();
            return;
        }
        if (carrierTeam.equals(currentPossTeam)) {
            currentChainTicks++;
        } else {
            closeCurrentChain();
            currentPossTeam = carrierTeam;
            currentChainId = nextChainId++;
            currentChainPassCount = 0;
            currentChainTicks = 1;
        }
    }

    /** Close the open possession chain, if any (also called at match end). */
    public void closePossessionChains() {
        closeCurrentChain();
    }

    private void closeCurrentChain() {
        if (currentPossTeam == null) return;
        int idx = "HOME".equals(currentPossTeam) ? 0 : 1;
        posChainCount[idx]++;
        posChainTicks[idx] += currentChainTicks;
        if (currentChainTicks > posLongest[idx]) posLongest[idx] = currentChainTicks;
        possessionChains.add(new PossessionChain(
                currentChainId, currentPossTeam, currentChainTicks, currentChainPassCount));
        currentPossTeam = null;
        currentChainId = 0L;
        currentChainPassCount = 0;
        currentChainTicks = 0;
    }

    public List<PossessionChain> getPossessionChains() {
        closeCurrentChain();
        return List.copyOf(possessionChains);
    }

    // ==================== BUILD METHODS ====================

    /** Build final stats snapshot for a team. */
    public TeamStats buildTeamStats(String team) {
        TeamAcc ta = teams.get(team);
        if (ta == null) return null;
        closePossessionChains();
        int idx = "HOME".equals(team) ? 0 : 1;
        double avgDur = 0;
        if (posChainCount[idx] > 0) {
            avgDur = Math.round((100.0 * posChainTicks[idx] / posChainCount[idx]) / 10.0) / 10.0;
        }
        double poss = 0;
        if (homePossTicks + awayPossTicks > 0) {
            poss = "HOME".equals(team)
                    ? 100.0 * homePossTicks / (homePossTicks + awayPossTicks)
                    : 100.0 * awayPossTicks / (homePossTicks + awayPossTicks);
        }
        return new TeamStats(
                ta.teamName, ta.goals, ta.shots, ta.shotsOnTarget,
                ta.passesAttempted, ta.passesCompleted, ta.dribbles,
                ta.clearances, ta.interceptions, ta.deflections, ta.blocks, ta.saves,
                ta.corners, ta.goalKicks, ta.throwIns, ta.offsides,
                ta.throughBalls, ta.crosses, ta.centers,
                ta.fouls, ta.yellowCards, ta.redCards,
                Math.round(poss * 10.0) / 10.0,
                avgDur, posLongest[idx]
        );
    }

    /** Build final stats for all players on a team, sorted by rating desc. */
    public List<PlayerStats> buildPlayerStats(String team) {
        return players.values().stream()
                .filter(p -> p.player.getTeam().equals(team))
                .map(p -> new PlayerStats(
                        p.player.getId(), p.player.getLabel(), "HOME".equals(team) ? homeName : awayName,
                        p.player.getRole(),
                        p.goals, p.assists, p.shots, p.shotsOnTarget,
                        p.passesAttempted, p.passesCompleted,
                        p.dribbles, p.clearances, p.interceptions, p.deflections,
                        p.blocks, p.saves, p.tackles, p.duelsWon,
                        p.throughBalls, p.crosses, p.centers,
                        p.foulsCommitted, p.yellowCards, p.redCards,
                        minutesPlayed(p.player),
                        calculateRating(p)
                ))
                .sorted(Comparator.comparingDouble(PlayerStats::rating).reversed())
                .toList();
    }

    /** Full match — unless the player was sent off, then up to his red-card tick. */
    private static int minutesPlayed(Player player) {
        if (player.isSentOff() && player.getSentOffTick() >= 0) {
            return Math.max(1, player.getSentOffTick() / ProposalMatchOutcomeBuilder.TICKS_PER_MINUTE);
        }
        return 90;
    }

    /** Get HOME possession percentage. */
    public double getHomePossessionPct() {
        if (homePossTicks + awayPossTicks == 0) return 50.0;
        return 100.0 * homePossTicks / (homePossTicks + awayPossTicks);
    }

    /** Map snapshot for JSON export: { HOME: {...}, AWAY: {...} }. */
    public Map<String, Object> toTeamJson() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("HOME", buildTeamStats("HOME"));
        out.put("AWAY", buildTeamStats("AWAY"));
        return out;
    }

    /** Player list snapshot for JSON export. */
    public List<Map<String, Object>> toPlayersJson() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (PlayerStats ps : buildPlayerStats("HOME")) out.add(toMap(ps));
        for (PlayerStats ps : buildPlayerStats("AWAY")) out.add(toMap(ps));
        return out;
    }

    // ==================== INTERNALS ====================

    private static Map<String, Object> toMap(PlayerStats ps) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("playerId", ps.playerId());
        m.put("playerName", ps.playerName());
        m.put("teamName", ps.teamName());
        m.put("role", ps.role());
        m.put("goals", ps.goals());
        m.put("assists", ps.assists());
        m.put("shots", ps.shots());
        m.put("shotsOnTarget", ps.shotsOnTarget());
        m.put("passesAttempted", ps.passesAttempted());
        m.put("passesCompleted", ps.passesCompleted());
        m.put("passAccuracy", ps.passAccuracy());
        m.put("dribbles", ps.dribbles());
        m.put("clearances", ps.clearances());
        m.put("interceptions", ps.interceptions());
        m.put("deflections", ps.deflections());
        m.put("blocks", ps.blocks());
        m.put("saves", ps.saves());
        m.put("tackles", ps.tackles());
        m.put("throughBalls", ps.throughBalls());
        m.put("crosses", ps.crosses());
        m.put("centers", ps.centers());
        m.put("duelsWon", ps.duelsWon());
        m.put("minutesPlayed", ps.minutesPlayed());
        m.put("rating", Math.round(ps.rating() * 10.0) / 10.0);
        return m;
    }

    private double calculateRating(PlayerAcc p) {
        double r = 6.0;
        r += p.goals * 1.5;
        r += p.assists * 1.0;
        r += p.passesCompleted * 0.02;
        r += p.interceptions * 0.3;
        r += p.duelsWon * 0.2;
        r += p.saves * 0.5;
        if ("GK".equals(p.player.getRole())) r += p.saves * 0.3;
        r -= p.foulsCommitted * 0.3;
        r -= p.yellowCards * 0.5;
        r -= p.redCards * 2.0;
        return Math.max(1.0, Math.min(10.0, r));
    }

    private static class TeamAcc {
        String teamName;
        int goals, shots, shotsOnTarget;
        int passesAttempted, passesCompleted, dribbles, clearances;
        int interceptions, deflections, blocks, saves;
        int corners, goalKicks, throwIns, offsides;
        int throughBalls, crosses, centers;
        int fouls, yellowCards, redCards;
        int penalties;
        int substitutions;   // Sprint 1.8
        int injuries;        // Sprint 1.6
        TeamAcc(String name) { this.teamName = name; }
    }

    private static class PlayerAcc {
        final Player player;
        int goals, assists, shots, shotsOnTarget;
        int passesAttempted, passesCompleted, dribbles, clearances;
        int interceptions, deflections, blocks, saves;
        int tackles, duelsWon;
        int throughBalls, crosses, centers;
        int foulsCommitted, yellowCards, redCards;
        boolean subbedOff, subbedOn;   // Sprint 1.8
        int injuries, injuryDays;      // Sprint 1.6
        PlayerAcc(Player p) { this.player = p; }
    }
}