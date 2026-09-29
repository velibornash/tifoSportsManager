package org.example.footballmanager.newLogic.sim;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.newLogic.model.CompetitionEntry;
import org.example.footballmanager.newLogic.model.GameClock;
import org.example.footballmanager.newLogic.model.Lineup;
import org.example.footballmanager.newLogic.model.Match;
import org.example.footballmanager.newLogic.model.MatchFixture;
import org.example.footballmanager.newLogic.model.MatchPlayerStats;
import org.example.footballmanager.newLogic.model.Position;
import org.example.footballmanager.newLogic.model.SeasonCompetition;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.CompetitionEntryRepository;
import org.example.footballmanager.newLogic.repository.LineupRepository;
import org.example.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.example.footballmanager.newLogic.repository.MatchPlayerStatsRepository;
import org.example.footballmanager.newLogic.repository.MatchRepository;
import org.example.footballmanager.newLogic.model.StaffMember;
import org.example.footballmanager.newLogic.model.StaffRole;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.StaffMemberRepository;
import org.example.footballmanager.newLogic.service.SquadEnvironment;
import org.example.footballmanager.newLogic.sim.model.Player;
import org.example.footballmanager.newLogic.sim.recording.SimReplayView;
import org.example.footballmanager.newLogic.sim.result.ProposalMatchOutcome;
import org.example.footballmanager.newLogic.sim.result.SimReportMapper;
import org.example.footballmanager.newLogic.sim.util.SimulationRandom;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.example.footballmanager.newLogic.service.AttendanceService;
import org.example.footballmanager.newLogic.service.SeasonService;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Official match path: runs the sim engine headless for a fixture and persists
 * the outcome into the newLogic Match row — score, formations, statsJson (the
 * canonical report), report eventJson (goals timeline), lineupJson and the
 * league table. Optionally keeps a replay for the proposal viewer.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SimMatchService {

    private final MatchRepository matchRepository;
    private final MatchFixtureRepository matchFixtureRepository;
    private final CompetitionEntryRepository competitionEntryRepository;
    private final SeasonService seasonService;
    private final org.example.footballmanager.newLogic.service.MoraleService moraleService;
    private final SimReplayStore replayStore;
    private final LineupRepository lineupRepository;
    private final MatchPlayerStatsRepository matchPlayerStatsRepository;
    private final PlayerRepository playerRepository;
    private final StaffMemberRepository staffMemberRepository;
    private final AttendanceService attendanceService;
    private final ObjectMapper objectMapper;

    /** Simulate a full match between two DB teams using their real saved squads
     *  (mapped into the engine's 4-4-2 slot structure). Falls back to synthetic
     *  squads when a team has no lineup template. */
    public SimMatchOutcome simulate(MatchFixture fixture, boolean storeReplay) {
        // Deterministic run: seed the engine RNG from the fixture id so
        // re-simulating the same fixture always reproduces the same match.
        SimulationRandom.seed(fixture != null && fixture.getId() != null
                ? fixture.getId() : System.nanoTime());

        Team homeTeam = fixture.getHomeTeam();
        Team awayTeam = fixture.getAwayTeam();
        String homeName = homeTeam != null ? homeTeam.getName() : "Home FC";
        String awayName = awayTeam != null ? awayTeam.getName() : "Away FC";

        List<Player> homeBench = new ArrayList<>();
        List<Player> awayBench = new ArrayList<>();
        List<Player> homeSquad = loadRealSquad(homeTeam, "HOME", homeBench);
        List<Player> awaySquad = loadRealSquad(awayTeam, "AWAY", awayBench);

        var orchestrator = SimMatchRunner.run(homeName, awayName, SimMatchRunner.FULL_MATCH_TICKS,
                homeSquad, awaySquad, homeBench, awayBench);
        ProposalMatchOutcome outcome = orchestrator.buildOutcome();
        persistMatchCondition(orchestrator.getState());

        long replayId = -1L;
        if (storeReplay) {
            replayId = replayStore.store(SimReplayView.build(orchestrator, homeName, awayName));
        }
        return new SimMatchOutcome(outcome, replayId, fixture.getHomeTeam(), fixture.getAwayTeam());
    }

    private List<Player> loadRealSquad(Team team, String side) {
        return loadRealSquad(team, side, new ArrayList<>());
    }

    private List<Player> loadRealSquad(Team team, String side, List<Player> benchOut) {
        if (team == null || team.getId() == null) return null;
        Lineup lineup = loadLineup(team);
        if (lineup != null) {
            List<org.example.footballmanager.newLogic.model.Player> ordered =
                    lineup.getOrderedStartingPlayers();
            if (ordered != null && ordered.size() >= 11) {
                return RealSquadFactory.buildSquad(lineup, side, benchOut,
                        coachFactorFor(team) * cohesionFactorFor(team));
            }
        }
        // No usable lineup template → build the XI from the team's real DB
        // players (position-sorted fallback) so real names/ids reach the sim,
        // the detail view and MatchPlayerStats even without a saved lineup.
        List<org.example.footballmanager.newLogic.model.Player> squad =
                playerRepository.findByTeamId(team.getId());
        if (squad == null || squad.size() < 11) return null;
        return RealSquadFactory.buildSquadFromPlayers(squad, side,
                coachFactorFor(team) * cohesionFactorFor(team));
    }

    /**
     * The head coach's effect on this club's players for this match (Sprint 4.3).
     *
     * <p>Only the head coach, and only from the attributes that describe how a manager actually
     * handles players — an assistant's rating is not the same man, and a scout's certainly is not a
     * coach. A club with no head coach gets 1.0, because nobody hired is not a coach who is bad at
     * his job.
     *
     * <p>Resolved per match rather than cached with the squad, for the same reason the training
     * service resolves its coach every week: a club can hire and fire between matches, and a manager
     * remembered for a week too long would keep producing results that belonged to his predecessor.
     */
    private double coachFactorFor(Team team) {
        if (team == null || team.getId() == null) {
            return 1.0;
        }
        for (StaffMember member : staffMemberRepository.findByTeamId(team.getId())) {
            if (member != null && member.getRole() == StaffRole.HEAD_COACH) {
                return member.matchFactor();
            }
        }
        return 1.0;
    }

    /**
     * What a settled dressing room is worth on the pitch (Sprint 4.6).
     *
     * <p>Multiplied with the head coach's factor rather than folded into it, so the two stay
     * separately reportable: a manager who thinks his team is misperforming deserves to know whether
     * the answer is the man on the touchline or the people around him.
     */
    private double cohesionFactorFor(Team team) {
        return SquadEnvironment.cohesionMatchFactor(team);
    }

    @Transactional
    public Long persist(MatchFixture fixture, ProposalMatchOutcome outcome, long replayId) {
        try {
            Match match = new Match();
            match.setHomeTeam(fixture.getHomeTeam());
            match.setAwayTeam(fixture.getAwayTeam());
            match.setCompetition(fixture.getCompetition());
            match.setSeasonYear(fixture.getSeasonYear());
            match.setRoundNumber(fixture.getRoundNumber());
            match.setWeekNumber(fixture.getWeekNumber());
            match.setMatchDate(fixture.getMatchDate() != null ? fixture.getMatchDate() : LocalDateTime.now());
            match.setHomeGoals(outcome != null ? outcome.homeGoals() : 0);
            match.setAwayGoals(outcome != null ? outcome.awayGoals() : 0);
            match.setPossessionHome(outcome != null ? outcome.possessionHome() : 50.0);
            match.setPossessionAway(outcome != null ? outcome.possessionAway() : 50.0);
            match.setHomeFormation(outcome != null ? outcome.homeFormation() : null);
            match.setAwayFormation(outcome != null ? outcome.awayFormation() : null);
            match.setPlayed(true);
            match.setStarted(true);
            match.setFinished(true);
            match.setReplayId(replayId);
            match.setHomeResultRevealed(true);
            match.setAwayResultRevealed(true);

            match.setHomeLineup(loadLineup(fixture.getHomeTeam()));
            match.setAwayLineup(loadLineup(fixture.getAwayTeam()));

            if (outcome != null) {
                match.setEventJson(SimReportMapper.eventJson(objectMapper, outcome));
                match.setStatsJson(objectMapper.writeValueAsString(SimReportMapper.statsMap(outcome)));
                match.setLineupJson(SimReportMapper.lineupJson(objectMapper, outcome));
            }

            attendanceService.ensureAttendance(match);
            match = matchRepository.save(match);

            if (outcome != null) {
                persistPlayerStats(match, outcome);
                bumpCareerStats(outcome);
            }

            fixture.setPlayed(true);
            fixture.setPlayedMatch(match);
            matchFixtureRepository.save(fixture);

            updateLeagueTable(match, outcome != null ? outcome.homeGoals() : 0, outcome != null ? outcome.awayGoals() : 0);

            log.info("Persisted sim match to DB: id={}, {} {} - {} {} (replayId={})",
                    match.getId(),
                    fixture.getHomeTeam().getName(), outcome != null ? outcome.homeGoals() : 0,
                    outcome != null ? outcome.awayGoals() : 0, fixture.getAwayTeam().getName(),
                    replayId);
            return match.getId();
        } catch (Exception e) {
            log.error("Failed to persist sim match for fixture={}", fixture.getId(), e);
            return null;
        }
    }

    private Lineup loadLineup(Team team) {
        if (team == null || team.getId() == null) return null;
        return lineupRepository
                .findFirstByTeamIdAndMatchIsNullOrderByIdDesc(team.getId())
                .orElse(null);
    }

    /** Writes one MatchPlayerStats row per real DB player who took part (synthetic
     *  fallback ids like "HOME-1" cannot resolve and are skipped). */
    private void persistPlayerStats(Match match, ProposalMatchOutcome outcome) {
        Team homeTeam = match.getHomeTeam();
        Team awayTeam = match.getAwayTeam();
        int homeConceded = outcome.awayGoals();
        int awayConceded = outcome.homeGoals();

        for (ProposalMatchOutcome.PlayerOutcome po : outcome.players()) {
            Long dbId = parsePlayerId(po.playerId());
            if (dbId == null) continue;
            org.example.footballmanager.newLogic.model.Player dbPlayer = playerRepository.findById(dbId).orElse(null);
            if (dbPlayer == null) continue;

            boolean isHome = homeTeam != null && homeTeam.getName().equals(po.teamName());
            int conceded = isHome ? homeConceded : awayConceded;
            boolean cleanSheet = conceded == 0
                    && po.minutesPlayed() >= 60
                    && (dbPlayer.getPositionEnum() == Position.GK || dbPlayer.getPositionEnum() == Position.DEF);

            MatchPlayerStats st = new MatchPlayerStats();
            st.setMatch(match);
            st.setPlayer(dbPlayer);
            st.setGoals(po.goals());
            st.setAssists(po.assists());
            st.setYellowCards(po.yellowCards());
            st.setRedCards(po.redCards());
            st.setMinutesPlayed(po.minutesPlayed());
            st.setRating(rating100(po.rating()));
            st.setInterceptions(po.interceptions());
            st.setSaves(po.saves());
            st.setCleanSheet(cleanSheet);
            st.setShots(po.shots());
            st.setPassesAttempted(po.passesAttempted());
            st.setPassesCompleted(po.passesCompleted());
            matchPlayerStatsRepository.save(st);
        }
    }

    /** Bumps career totalGoals/totalAssists and sets match rating (10-100 scale)
     *  on every real DB player who took part. */
    private void bumpCareerStats(ProposalMatchOutcome outcome) {
        Set<org.example.footballmanager.newLogic.model.Player> updated = new LinkedHashSet<>();
        for (ProposalMatchOutcome.PlayerOutcome po : outcome.players()) {
            Long dbId = parsePlayerId(po.playerId());
            if (dbId == null) continue;
            org.example.footballmanager.newLogic.model.Player dbPlayer = playerRepository.findById(dbId).orElse(null);
            if (dbPlayer == null) continue;
            dbPlayer.setTotalGoals(dbPlayer.getTotalGoals() + po.goals());
            dbPlayer.setTotalAssists(dbPlayer.getTotalAssists() + po.assists());
            // The match rating is NOT written back onto the player. This column is a career rating
            // derived from his skills (Player.careerRating); overwriting it with one match's rating
            // made his displayed OVR depend on how the last match went, and is why a bot that had
            // played once stopped reading 96. The per-match rating lives on MatchPlayerStats, which
            // is written above, and "how he is playing lately" is form, which MoraleService owns.

            // Morale and form finally move on what happened (Sprint 2.6). This is the one place
            // where every player's line for the match is already in hand, so it is the only place
            // that can be wired without re-reading the whole recording.
            // The result is derived from the scoreline rather than carried on the player's line:
            // a team result is a property of the match, and storing it per player would mean 22
            // copies of the same fact that can disagree with each other.
            String teamName = po.teamName();
            boolean isHome = teamName != null && teamName.equals(outcome.homeTeam());
            int mine = isHome ? outcome.homeGoals() : outcome.awayGoals();
            int theirs = isHome ? outcome.awayGoals() : outcome.homeGoals();
            moraleService.applyMatch(dbPlayer,
                    po.minutesPlayed(),
                    po.goals(),
                    po.assists(),
                    po.rating(),
                    mine > theirs,
                    mine == theirs);

            if (updated.add(dbPlayer)) {
                playerRepository.save(dbPlayer);
            }
        }
    }

    /**
     * Carries in-match condition back onto the persisted players (Sprint 1.6).
     *
     * <p>Until now the engine's fatigue lived only on {@code sim.model.Player} and never left the
     * match. The Medical Center, the injury model and the season's recovery all read
     * {@code Player.skills.fatigue} on the DB entity, so a player could run a whole season at zero
     * recorded fatigue however hard he played, and the weekly recovery had nothing to recover.
     *
     * <p>The engine's fatigue is 0..1; the DB column is 0..100. Injury days and type are copied so
     * the medical page and the injury countdown finally have a source.
     */
    private void persistMatchCondition(org.example.footballmanager.newLogic.sim.model.MatchState state) {
        if (state == null) return;
        GameClock clock = seasonService.getOrCreateClock();
        int season = clock.getCurrentSeason() == null ? 1 : clock.getCurrentSeason();
        int week = clock.getCurrentWeek() == null ? 1 : clock.getCurrentWeek();

        for (Player p : state.getPlayers()) {
            Long dbId = parsePlayerId(p.getId());
            if (dbId == null) continue;
            org.example.footballmanager.newLogic.model.Player dbPlayer =
                    playerRepository.findById(dbId).orElse(null);
            if (dbPlayer == null || dbPlayer.getSkills() == null) continue;

            if (p.getFatigue() > 0) {
                int carried = (int) Math.round(Math.min(1.0, p.getFatigue()) * 100.0);
                dbPlayer.getSkills().setFatigue(Math.min(100,
                        dbPlayer.getSkills().getFatigue() + carried));
            }
            if (p.isInjured() && p.getInjuryDaysRemaining() > 0) {
                dbPlayer.setInjured(true);
                dbPlayer.setInjuryDaysRemaining(p.getInjuryDaysRemaining());
                dbPlayer.setInjurySeasonNumber(season);
                dbPlayer.setInjuryWeekNumber(week);
            }
            playerRepository.save(dbPlayer);
        }
    }

    private static Long parsePlayerId(String playerId) {
        try {
            return playerId != null && !playerId.isEmpty() ? Long.valueOf(playerId) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int rating100(double rating10) {
        return (int) Math.round(Math.max(0.0, Math.min(10.0, rating10)) * 10.0);
    }

    private void updateLeagueTable(Match match, int homeGoals, int awayGoals) {
        if (match.getCompetition() == null || match.getSeasonYear() == null) return;

        SeasonCompetition sc = seasonService.ensureSeasonCompetition(match.getCompetition(), match.getSeasonYear());

        CompetitionEntry homeEntry = seasonService.findOrCreateEntry(sc, match.getHomeTeam());
        CompetitionEntry awayEntry = seasonService.findOrCreateEntry(sc, match.getAwayTeam());

        if (homeGoals > awayGoals) { homeEntry.setPoints(homeEntry.getPoints() + 3); homeEntry.setWins(homeEntry.getWins() + 1); }
        else if (homeGoals == awayGoals) { homeEntry.setPoints(homeEntry.getPoints() + 1); homeEntry.setDraws(homeEntry.getDraws() + 1); }
        else { homeEntry.setLosses(homeEntry.getLosses() + 1); }
        homeEntry.setGoalsScored(homeEntry.getGoalsScored() + homeGoals);
        homeEntry.setGoalsConceded(homeEntry.getGoalsConceded() + awayGoals);

        if (awayGoals > homeGoals) { awayEntry.setPoints(awayEntry.getPoints() + 3); awayEntry.setWins(awayEntry.getWins() + 1); }
        else if (homeGoals == awayGoals) { awayEntry.setPoints(awayEntry.getPoints() + 1); awayEntry.setDraws(awayEntry.getDraws() + 1); }
        else { awayEntry.setLosses(awayEntry.getLosses() + 1); }
        awayEntry.setGoalsScored(awayEntry.getGoalsScored() + awayGoals);
        awayEntry.setGoalsConceded(awayEntry.getGoalsConceded() + homeGoals);

        competitionEntryRepository.saveAll(List.of(homeEntry, awayEntry));
    }

    public record SimMatchOutcome(ProposalMatchOutcome outcome, long replayId,
                                  Team homeTeam, Team awayTeam) {
        public int homeGoals() { return outcome != null ? outcome.homeGoals() : 0; }
        public int awayGoals() { return outcome != null ? outcome.awayGoals() : 0; }
    }
}