package org.example.footballmanager.newLogic.sim.result;

import org.example.footballmanager.newLogic.sim.MatchEngineRules;

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

    /**
     * Call from DUEL result.
     *
     * <p><b>The loser of a duel is not necessarily the tackler, and this used to pretend he was.</b>
     * Every duel credited the winner's {@code duelsWon} and the loser's {@code tackles}. A striker
     * dispossessed eight times banked eight "tackles" and +0.64 of rating for being dribbled past,
     * and the manager was shown those eight in a tackles column.
     *
     * <p>The two are now what they say. A defender who wins the ball has won a duel
     * ({@code duelsWon}); whoever <em>loses</em> it has lost one ({@code duelsLost}), which is a
     * penalty and not a credit. Nothing here decides who was the tackler — the engine does not record
     * that — so nothing invents it.
     */
    public void onDuelWon(String winnerId, String loserId) {
        PlayerAcc w = players.get(winnerId);
        if (w != null) w.duelsWon++;
        PlayerAcc l = players.get(loserId);
        if (l != null) l.duelsLost++;
    }

    /** Call from InjuryService. */
    public void onInjury(String team, String playerId, String type, int days) {
        TeamAcc ta = teams.get(team);
        if (ta != null) ta.injuries++;
        PlayerAcc pa = players.get(playerId);
        if (pa != null) { pa.injuries++; pa.injuryDays = days; }
    }

    /** Call from SubstitutionService. */
    public void onSubstitution(String team, String playerOffId, String playerOnId, boolean forced,
                               int atTick) {
        TeamAcc ta = teams.get(team);
        if (ta != null) ta.substitutions++;
        PlayerAcc off = players.get(playerOffId);
        if (off != null) {
            off.subbedOff = true;
            off.offTick = atTick;
        }
        PlayerAcc on = players.get(playerOnId);
        if (on != null) {
            on.subbedOn = true;
            // Minutes are read from these, and every substitute was being reported as playing 90.
            on.enteredTick = atTick;
        }
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
                        p.blocks, p.saves, p.duelsLost, p.duelsWon,
                        p.throughBalls, p.crosses, p.centers,
                        p.foulsCommitted, p.yellowCards, p.redCards,
                        minutesPlayed(p),
                        calculateRating(p)
                ))
                .sorted(Comparator.comparingDouble(PlayerStats::rating).reversed())
                .toList();
    }

    /** Full match — unless the player was sent off, then up to his red-card tick. */
    /** Ninety minutes, in ticks. Read from the builder so the two cannot drift. */
    private static final int FULL_MATCH_TICKS = 90 * ProposalMatchOutcomeBuilder.TICKS_PER_MINUTE;

    /** Minutes actually played, from when the player came on to when he went off. */
    private static int minutesPlayed(PlayerAcc p) {
        int start = p.subbedOn ? Math.max(0, p.enteredTick) : 0;
        int end = FULL_MATCH_TICKS;
        if (p.player.isSentOff() && p.player.getSentOffTick() >= 0) {
            end = Math.min(end, p.player.getSentOffTick());
        } else if (p.subbedOff && p.offTick >= 0) {
            end = Math.min(end, p.offTick);
        }
        if (end <= start) {
            return 0;
        }
        return (end - start) / ProposalMatchOutcomeBuilder.TICKS_PER_MINUTE;
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
        m.put("duelsLost", ps.duelsLost());
        m.put("throughBalls", ps.throughBalls());
        m.put("crosses", ps.crosses());
        m.put("centers", ps.centers());
        m.put("duelsWon", ps.duelsWon());
        m.put("minutesPlayed", ps.minutesPlayed());
        m.put("rating", Math.round(ps.rating() * 10.0) / 10.0);
        return m;
    }

    /**
     * A player's match rating, 2.0 to 10.0.
     *
     * <p><b>Rescaled (owner, 2026-10-20).</b> The owner's own words:
     *
     * <blockquote>"a GK who receive 4 goals got 10, player with 1 goal in 1-4 lost got 8.8 - too much,
     * when player had average game it is 6.5 lets say, one goal -7-ish, 3 goals is 9ish"</blockquote>
     *
     * <p><b>What was wrong.</b>
     * <ul>
     *   <li><b>The goalkeeper's saves were unbounded</b> — +0.35 each, with no limit on how many a
     *       keeper can make — and <b>there was no term at all for the goals he let in</b>. A keeper
     *       whose team was thrashing him collected more rating for the disaster than a striker collects
     *       for a hat-trick, and his rating had no idea how many had gone past him. Measured on the
     *       formula before this change: a keeper with four goals against him, ten saves, a clean
     *       passing evening and nine duels won came out at <b>10.0</b>.</li>
     *   <li><b>A goal was worth 1.6</b> — more than a quarter of the whole scale.</li>
     *   <li><b>The base was 6.0</b>, and the owner's ordinary match is a 6.5.</li>
     * </ul>
     *
     * <p><b>The scale now.</b> An ordinary game is <b>6.5</b>. One goal is <b>7</b>-ish. Three goals is
     * <b>9</b>-ish. A goalkeeper who concedes four goals is well below the ordinary game, whatever he
     * did in the rest of it. Ten is still reserved for an exceptional match.
     *
     * <p>The earlier rewriting (owner, 2026-10-10) stays: the 6.0 base, the bounded passing term
     * around a 70% completion rate, the shots that reward effort and punish waste, the bounded
     * defensive terms, the team result as a small shared term, and the minutes scaling. What changed
     * here is the base's value, the worth of a goal and an assist, and the goalkeeper's terms.
     *
     * <p>Deliberately <b>not</b> rewritten as a weighted average of team result and player actions: that
     * would rate a defender of a 4-0 highly for a game he did not touch, which is the same mistake in
     * the other direction.
     */
    private double calculateRating(PlayerAcc p) {
        // Base rating for playing the whole match. The owner's ordinary game is a 6.5 (2026-10-20).
        double r = 6.5;

        // Goals. The first goal was worth 1.6 - more than a quarter of the scale - and one goal in a
        // 1-4 loss read as 8.8, which the owner called "too much". Now a goal is 0.7 and a hat-trick
        // carries 0.4 more: one goal is a 7, three goals are a 9.
        if (p.goals > 0) {
            r += p.goals * 0.7;
            if (p.goals >= 3) {
                r += 0.4;
            }
        }

        // Assists reward creation but less than goals. They were worth more than half a goal each
        // (1.0 for the first, 0.45 for the rest), which made a creative midfielder's evening outrank
        // a striker's goal. An assist is now 0.5 and each after it 0.25.
        if (p.assists > 0) {
            r += 0.5 + (p.assists - 1) * 0.25;
        }

        // Passing: a rate, not a volume. A hub who completes 90% of 100 passes has earned something,
        // and a met whose team keeps it has earned something negative.
        if (p.passesAttempted > 0) {
            double completion = (double) p.passesCompleted / p.passesAttempted;
            r += Math.max(-0.8, Math.min(0.6, (completion - 0.70) * 2.0));
        }

        // Shots: effort is rewarded, finishing is rewarded more, and missing everything is not neutral.
        if (p.shots > 0) {
            r += Math.min(0.5, p.shotsOnTarget * 0.25) - Math.min(0.5, (p.shots - p.shotsOnTarget) * 0.12);
        }

        // Defensive work, bounded.
        //
        // These three were unbounded and, between them, the largest term in the formula. A match has
        // around 268 duels, which is about twelve per player, and they all counted the same: a player
        // who won twelve and lost twelve — a perfectly ordinary striker — banked
        // 12 x 0.12 + 12 x 0.08 = +2.4, more than two goals.
        //
        // Bounded because the work is real and the *volume* is not a measure of it. A defender who
        // wins nine duels has done his job; the tenth is not another nine-tenths of it.
        r += Math.min(0.7, p.interceptions * 0.16);
        r += Math.min(1.0, p.duelsWon * 0.12);
        r += Math.max(-0.6, Math.min(0.3, p.duelsLost * -0.05));

        // The team result. A defender of a 4-0 who touched nothing is not a 6.0, and a midfielder
        // who ran the match in a 0-0 loss is not a 7.5. It is a small term because it is a shared
        // one — twenty players share the same result — but a rating with no dimension for it cannot
        // describe a match at all.
        r += teamResultTerm(p);

        // Goalkeeper specific.
        //
        // The saves are worth something - a keeper who made ten saves had a game - but they are
        // bounded, because a keeper is busy when his team is losing and an unbounded bonus turns the
        // disaster into an achievement. And the goals he let in now cost him rating: they are his
        // to answer for, and without this term his rating could not tell a clean sheet from a
        // four-goal mauling (measured: 6.8 and 6.8 before this change).
        if ("GK".equals(p.player.getRole())) {
            r += Math.min(0.9, p.saves * 0.18);
            r -= goalsConcededAgainst(p) * 0.6;
        }

        // Discipline penalties
        r -= p.foulsCommitted * 0.25;
        r -= p.yellowCards * 0.4;
        r -= p.redCards * 3.0;

        // Minutes scaling: a 45-minute sub shouldn't rate the same as a 90-minute starter.
        // This was inert for everyone but a red card, because a substitute was reported as playing 90.
        int minutes = minutesPlayed(p);
        double minuteFactor = Math.min(1.0, minutes / 90.0);
        // Anchored on the base, which is 6.5. Anchoring on the old 6.0 would have quietly moved the
        // floor of every sub and every early substitute.
        r = 6.5 + (r - 6.5) * minuteFactor;

        return Math.max(2.0, Math.min(10.0, Math.round(r * 10.0) / 10.0));
    }

    /**
     * The team's result, as a share of the score and the margin, worth at most half a rating.
     *
     * <p>Bounded on purpose. Twenty players share one result, so a term large enough to move a rating
     * decisively would make every player on a winning side a good player regardless of what he did —
     * which is the mistake the comment above this formula already warns about in the other direction.
     *
     * <p>Read from the accumulator rather than from the engine's final score, because the formula runs
     * per player and a player's own goal count is the only per-player fact about the result available
     * here.
     */
    /**
     * The goals this player's team let in.
     *
     * <p>Read from the accumulator, for the same reason the team result is: the formula runs per
     * player, and the score is the only fact about the match the keeper shares with everyone else.
     */
    private int goalsConcededAgainst(PlayerAcc p) {
        TeamAcc againstAcc = teams.get(opponentOf(p.player.getTeam()));
        return againstAcc == null ? 0 : againstAcc.goals;
    }

    private double teamResultTerm(PlayerAcc p) {
        TeamAcc forAcc = teams.get(p.player.getTeam());
        TeamAcc againstAcc = teams.get(opponentOf(p.player.getTeam()));
        if (forAcc == null || againstAcc == null) return 0.0;
        int forTeam = forAcc.goals;
        int against = againstAcc.goals;
        if (forTeam == 0 && against == 0) {
            return -0.15;   // nothing happened; a blank is not a good game
        }
        if (forTeam > against) {
            double margin = Math.min(3, forTeam - against);
            return 0.15 + margin * 0.12;
        }
        if (forTeam == against) {
            return 0.0;
        }
        double margin = Math.min(3, against - forTeam);
        return -(0.2 + margin * 0.12);
    }

    private static String opponentOf(String team) {
        return MatchEngineRules.isHome(team) ? "AWAY" : "HOME";
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
        int duelsWon, duelsLost;
        int throughBalls, crosses, centers;
        int foulsCommitted, yellowCards, redCards;
        boolean subbedOff, subbedOn;   // Sprint 1.8
        int enteredTick = -1;           // when he came on; -1 is "started"
        int offTick = -1;               // when he went off; -1 is "played the full match"
        int injuries, injuryDays;      // Sprint 1.6
        PlayerAcc(Player p) { this.player = p; }
    }
}