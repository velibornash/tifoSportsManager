package org.example.footballmanager.newLogic.sim;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.CompetitionEntry;
import org.example.footballmanager.newLogic.model.CompetitionType;
import org.example.footballmanager.newLogic.model.GameClock;
import org.example.footballmanager.newLogic.model.Lineup;
import org.example.footballmanager.newLogic.model.Match;
import org.example.footballmanager.newLogic.model.MatchFixture;
import org.example.footballmanager.newLogic.model.MatchPlayerStats;
import org.example.footballmanager.newLogic.model.MatchType;
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
import org.example.footballmanager.newLogic.service.NationalRatingService;
import org.example.footballmanager.newLogic.sim.engine.InjuryService;
import org.example.footballmanager.newLogic.sim.engine.PenaltyShootout;
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
import org.example.footballmanager.newLogic.service.ZoneLoadRecorder;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.example.footballmanager.newLogic.util.LazySquadGenerator;

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
    /** The manager's conditional substitution plan, keyed by fixture. Null-safe in wiring-free tests. */
    private final org.example.footballmanager.newLogic.repository.SubstitutionPlanRepository substitutionPlans;
    private final CompetitionEntryRepository competitionEntryRepository;
    private final SeasonService seasonService;
    private final org.example.footballmanager.newLogic.service.MoraleService moraleService;
    private final SimReplayStore replayStore;
    private final LineupRepository lineupRepository;
    private final org.example.footballmanager.newLogic.util.LazySquadGenerator lazySquadGenerator;
    private final MatchPlayerStatsRepository matchPlayerStatsRepository;
    private final PlayerRepository playerRepository;
    private final org.example.footballmanager.newLogic.service.SquadRegistrationService squadRegistration;
    private final StaffMemberRepository staffMemberRepository;
    private final AttendanceService attendanceService;
    private final ObjectMapper objectMapper;
    private final ZoneLoadRecorder zoneLoadRecorder;
    private final NationalRatingService nationalRatingService;
    private final org.example.footballmanager.newLogic.service.TacticsRulesProvider tacticsRules;
    private final org.example.footballmanager.newLogic.repository.TeamTacticsProfileRepository
            teamTacticsProfileRepository;

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

        // A simulated club has no players until a match is the thing that makes it need some. Without
        // this, a tie against a human club resolved on reputation and had no lineup to show at all.
        //
        // Only here, only on the match path, and only when one side is human: two bot clubs is still
        // just a result, and generating for those would undo the static world entirely.
        if (homeTeam != null && awayTeam != null && LazySquadGenerator.isHumanInvolved(homeTeam, awayTeam)) {
            int generated = lazySquadGenerator.ensureSquadsForMatch(homeTeam, awayTeam);
            if (generated > 0) {
                log.info("{} vs {}: generated {} player(s) for a club that had no squad.",
                        homeName, awayName, generated);
            }
        }

        List<Player> homeBench = new ArrayList<>();
        List<Player> awayBench = new ArrayList<>();
        List<Player> homeSquad = loadRealSquad(homeTeam, "HOME", homeBench);
        List<Player> awaySquad = loadRealSquad(awayTeam, "AWAY", awayBench);

        // **The home club's own tactical editor shape, for the first time.**
        //
        // This used to construct nothing here at all: MatchOrchestrator built its own TacticsRules, which
        // opened a raw JDBC connection and read `WHERE team_id = 1 AND formation = '4-4-2'`. Every club
        // in the world therefore ran team 1's tactics, and because a missing profile and a wrong password
        // and an unparseable profile were all swallowed into the same silent null, the fallback export
        // looked like it working.
        //
        // Both sides now carry their own authored shape. The perspective decision that used to be
        // outstanding turned out to be already made, and made consistently: the editor has a single frame,
        // every club's grid is stored in it, and the mirror is applied at lookup by the side asking.
        // Injury risk follows the kind of match. Fatigue does not: a practice match costs exactly what
        // a league match costs, because the player still played ninety minutes.
        InjuryService.setRiskMultiplier(fixture == null
                ? 1.0 : fixture.resolvedMatchType().injuryRisk());
        // **Each club's own tactics, for the first time.**
        //
        // This loaded only the home club's grid and handed it to the whole match, so the away side was
        // resolved against the home side's vocabulary: a 4-3-3 visitor asked a 4-4-2's rules about
        // CM / WL / WR / ST, and every one of those players silently fell back to his own formation's
        // anchor. Two clubs, one shape.
        //
        // No mirror is applied here and none is needed: the editor has a single frame, every club's grid is
        // stored in it, and TacticalPerspectiveTransformer applies the mirror once at lookup, keyed on the
        // side asking. Selecting the right object per side is the whole of the fix.
        // **The manager's conditional substitutions, attached before the first tick.**
        //
        // Built rather than run, so the plan can be handed to the engine before it starts ticking.
        // `MatchOrchestrator` evaluated `conditionalSubs.onTick()` on every tick of every match against
        // a list that was always empty, because nothing could put anything in it: the rules holder was
        // private and the orchestrator was constructed and simulated inside one static call. Ten unit
        // tests were green throughout — they built the object themselves and called add() directly.
        var orchestrator = SimMatchRunner.build(homeName, awayName,
                homeSquad, awaySquad, homeBench, awayBench,
                new org.example.footballmanager.newLogic.sim.tactics.SideTactics(
                        tacticsRules.forTeam(homeTeam.getId()),
                        tacticsRules.forTeam(awayTeam.getId())));

        applySubstitutionPlan(fixture, orchestrator);

        orchestrator.simulate(SimMatchRunner.FULL_MATCH_TICKS);
        ProposalMatchOutcome outcome = orchestrator.buildOutcome();
        persistMatchCondition(orchestrator.getState());
        // Whatever happens next, the next match is a competitive one unless it says otherwise.
        InjuryService.resetRiskForNextMatch();

        long replayId = -1L;
        if (storeReplay) {
            replayId = replayStore.store(SimReplayView.build(orchestrator, homeName, awayName));
        }
        return new SimMatchOutcome(outcome, replayId, fixture.getHomeTeam(), fixture.getAwayTeam(),
                orchestrator.getRecorder().getSnapshots(),
                // How each conditional rule actually went, read once here while the object is still
                // in memory. (T1-16) Without this the VoidReason the engine assigned is discarded and
                // a rule that never fired is indistinguishable from a rule that was never set.
                ruleOutcomeJson(orchestrator));
    }

    /**
     * Reads the manager's substitution plan for this fixture and hands it to the engine.
     *
     * <p><b>Never throws.</b> A plan is an instruction, and a malformed one must not cost the manager
     * his match. Anything unreadable is logged and the match is simulated with no rules, which is exactly
     * what happens when no plan was ever set — so a broken plan degrades to the default rather than
     * failing the fixture.
     *
     * <p>Keyed by fixture, so this is the one simulation that reads it. {@code MatchFixture.playedMatch}
     * is a unique indexed column and {@code SimMatchService.persist} sets it in the same block that sets
     * {@code played = true}, so there is exactly one match per fixture and no way to read the wrong one.
     */
    private void applySubstitutionPlan(MatchFixture fixture,
                                       org.example.footballmanager.newLogic.sim.engine.MatchOrchestrator orchestrator) {
        if (fixture == null || fixture.getId() == null || substitutionPlans == null) return;
        try {
            var plan = substitutionPlans.findByFixtureId(fixture.getId()).orElse(null);
            if (plan == null || plan.getRulesJson() == null || plan.getRulesJson().isBlank()) return;

            org.example.footballmanager.newLogic.sim.engine.ConditionalSubstitutionRules.Rule[] rules =
                    new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                            plan.getRulesJson(),
                            org.example.footballmanager.newLogic.sim.engine.ConditionalSubstitutionRules.Rule[].class);

            for (var rule : rules) {
                if (rule == null) continue;
                // A rule with no trigger minute can never fire, and one naming nobody is the engine's
                // choice rather than the manager's. Both are legal; neither is worth a log line.
                orchestrator.conditionalSubstitutions().add(rule);
            }
            log.info("Substitution plan for fixture {}: {} rule(s) attached.", fixture.getId(), rules.length);
        } catch (Exception e) {
            log.warn("Could not read the substitution plan for fixture {}; simulating with none. {}",
                    fixture.getId(), e.getMessage());
        }
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
                        coachFactorFor(team) * cohesionFactorFor(team), formationOf(lineup, team));
            }
        }
        // No usable lineup template → build the XI from the team's real DB
        // players (position-sorted fallback) so real names/ids reach the sim,
        // the detail view and MatchPlayerStats even without a saved lineup.
        // Owned plus loaned in. Without the union a club whose manager saved no lineup fields a
        // fallback XI that does not contain the player he went to the trouble of borrowing.
        List<org.example.footballmanager.newLogic.model.Player> squad =
                squadRegistration.availablePlayers(team.getId());
        if (squad == null || squad.size() < 11) return null;
        return RealSquadFactory.buildSquadFromPlayers(squad, side,
                coachFactorFor(team) * cohesionFactorFor(team), formationOf(null, team));
    }

    /**
     * The shape this club actually plays in.
     *
     * <p>The lineup template's own formation first — that is what the manager picked on the screen — and
     * then the tactical editor profile, because a club can have an editor shape and no saved lineup. The
     * formation decides the eleven role keys the engine wears and where each role's anchor cell is, so
     * this is what turns "the editor holds a 4-3-3" into "the striker stands where a 4-3-3 striker
     * stands".
     *
     * <p>Falls back to the engine's 4-4-2 rather than inventing one: a club that has expressed no
     * preference gets the shape the engine has always given it, which is also what every existing caller
     * gets when this returns null.
     */
    private String formationOf(Lineup lineup, Team team) {
        if (lineup != null && lineup.getFormation() != null && !lineup.getFormation().isBlank()) {
            return lineup.getFormation();
        }
        if (team != null && team.getId() != null) {
            String fromEditor = teamTacticsProfileRepository.findByTeamId(team.getId())
                    .map(org.example.footballmanager.newLogic.model.tactics.TeamTacticsProfile::getFormation)
                    .filter(f -> f != null && !f.isBlank())
                    .orElse(null);
            if (fromEditor != null) {
                return fromEditor;
            }
        }
        return null;
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

    /** The three-argument form, for a caller replaying a stored outcome and with no ticks to read. */
    public Long persist(MatchFixture fixture, ProposalMatchOutcome outcome, long replayId) {
        return persist(fixture, outcome, replayId, null);
    }

    /**
     * @param simSnapshots the match's ticks, or null when there are none. Only the zone load reads
     *        them, so every existing three-argument caller keeps working unchanged.
     */
    @Transactional
    public Long persist(MatchFixture fixture, ProposalMatchOutcome outcome, long replayId,
                        List<org.example.footballmanager.newLogic.sim.recording.MatchSnapshot> simSnapshots) {
        try {
            Match match = new Match();
            match.setHomeTeam(fixture.getHomeTeam());
            match.setAwayTeam(fixture.getAwayTeam());
            match.setCompetition(fixture.getCompetition());
            // Written on every match, from the fixture's own statement or its competition (P2-8).
            // Persisted rather than derived on read so results can be filtered by it in SQL.
            match.setMatchType(fixture.resolvedMatchType());
            match.setSeasonYear(fixture.getSeasonYear());
            match.setRoundNumber(fixture.getRoundNumber());
            match.setWeekNumber(fixture.getWeekNumber());
            match.setDayNumber(fixture.getDayNumber());
            // Which cup group this was, if any (P0-CUPS-1). The fixture is the only thing that knows,
            // and a played match has to remember it: the table rebuild reads played matches, not
            // fixtures, and the penalty rule needs it too.
            match.setGroupCode(fixture.getGroupCode());
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

            // A knockout tie that finished level is settled from the spot. It used to be left level,
            // and the cup's winner lookup returned null for it, logged "no shootout recorded", and
            // dropped the club — so every level tie cost a knockout round a team and the competition
            // could not get past its first rounds.
            //
            // A CUP tie outside a group (P0-CUPS-2). A tie inside a cup group is scored as the draw it
            // is; settling it from the spot would decide the group on penalties, and a Champions Cup
            // decided by shootouts is not the competition the owner specified.
            if (isKnockoutTie(match) && match.getHomeGoals() == match.getAwayGoals()) {
                try {
                    settleFromTheSpot(match);
                } catch (RuntimeException e) {
                    // The tie stays level and the cup drops a club, which is the old behaviour and is
                    // better than losing the match record because a shootout threw.
                    log.warn("Could not settle the level cup tie {} from the spot: {}",
                            match.getId(), e.getMessage());
                }
            }

            // A result the manager has not asked to see yet stays hidden, and a manager's own match is
            // the only one that can be: nobody is waiting to discover how a bot's game went.
            //
            // This used to set both to true, which meant the whole reveal model - the columns, the
            // DTO's resultHidden, the reveal endpoint, the Watch button - was present and inert. The
            // matchday job fires at 19:00 whether or not anyone is watching, so the result exists the
            // moment the job finishes; hiding it is the only thing that makes "Watch your match" a
            // decision rather than a formality.
            boolean involvesManager = isHumanClub(fixture.getHomeTeam()) || isHumanClub(fixture.getAwayTeam());
            match.setHomeResultRevealed(!involvesManager);
            match.setAwayResultRevealed(!involvesManager);

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
                // The per-match rows are written for every type: a manager who played an exhibition
                // should be able to read back what happened in it.
                persistPlayerStats(match, outcome);
                if (match.resolvedMatchType().countsForCareer()) {
                    bumpCareerStats(outcome);
                } else {
                    // No career goals, no career assists, no morale and no form. This is the whole of
                    // "changes nothing", and it is one branch because both halves lived in the same
                    // method: splitting them would leave a player whose match was recorded but whose
                    // morale had moved, which is the "green but did nothing" shape.
                    log.debug("Match {} is {}: career stats and morale untouched",
                            match.getId(), match.resolvedMatchType());
                }
            }
            // Where he worked, not just what he did. The zone table, the recovery rules and the daily
            // recovery job all existed with nothing writing them, so recovery reported zero for every
            // player in the world. Best-effort: a missing zone row must never cost a match result.
            try {
                recordZoneLoad(match, simSnapshots);
            } catch (RuntimeException e) {
                log.warn("Could not record zone load for match {}: {}", match.getId(), e.getMessage());
            }

            fixture.setPlayed(true);
            fixture.setPlayedMatch(match);
            matchFixtureRepository.save(fixture);

            // An international changes two countries' ratings, and the World page reads them. Doing
            // it here means the column is right the moment the matchday finishes rather than at the
            // next restart. A replay, not an increment, so running it for every match of a matchday is
            // harmless - and if it throws, the match is still saved and the next boot catches up.
            //
            // Club Elo is deliberately NOT here. Its replay is world-wide - every club and every match
            // it has played - and this runs once per fixture, so a 155-match matchday would replay the
            // whole world 155 times. It is called once per batch instead, at the end of
            // AsyncSimulationRunner and after a single match in SimulationController.
            if (match.getCompetition() != null
                    && match.getCompetition().getType() == CompetitionType.INTERNATIONAL) {
                try {
                    nationalRatingService.recompute();
                } catch (RuntimeException e) {
                    log.warn("Could not recompute national Elo after match {}: {}", match.getId(), e.getMessage());
                }
            }

            if (match.resolvedMatchType().countsForTable(match)) {
                updateLeagueTable(match, outcome != null ? outcome.homeGoals() : 0,
                        outcome != null ? outcome.awayGoals() : 0);
            }

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

    /**
     * Reads the zone load off a played match and writes it.
     *
     * <p>The engine's player ids are either a real database id or a synthetic "HOME-1" label, depending
     * on whether the club had a saved lineup. The mapping is resolved here from the same
     * {@code parsePlayerId} the player stats use, and anything unresolvable is skipped rather than
     * written against a player that does not exist.
     */
    /**
     * Whether a team is the one a manager actually plays as.
     *
     * <p>Deliberately a property of the match rather than of who is looking at it. An AI-vs-AI result
     * has no audience waiting for it, and a result the scheduler produced at 19:00 has to be the same
     * hidden row for the manager whether it is read from the dashboard, the club schedule or the league
     * table - so the decision belongs with the match and {@code MatchDTO} decides only who may see it.
     */
    /**
     * Is this a tie that must be decided, rather than one that may finish level?
     *
     * <p><b>The answer is the same field the group table reads (P0-CUPS-2).</b> It used to be
     * {@code type == CUP}, which said every cup tie had to be won — and its own comment named the
     * consequence: *"the day {@code ensureGroupStage} is wired, every level group match is settled by a
     * shootout."* A Champions Cup group stage decided on penalties is not a group stage, it is sixteen
     * shootouts and a table nobody chose.
     *
     * <p>So a cup match is a knockout tie exactly when it is <b>not</b> in a group. A cup group match
     * may finish level and is scored as a draw; a domestic cup tie, which has no groups at all, is a
     * knockout tie and always has been.
     *
     * <h2>About {@code MatchFormat}, which used to be the recommended fix and no longer exists</h2>
     *
     * <p>Three comments in this repository, including the one above this method, said the fix was to
     * wire {@code MatchFormat} and that a format column had to land <b>before</b> the group stage. That
     * ordering constraint was right about the deadline and wrong about the shape.
     *
     * <p>{@code MatchFormat} was a <i>competition</i>'s format, answering {@code goesToPenalties()} for
     * the whole competition. But a Champions Cup <b>has two formats in one competition</b>: five group
     * matchdays that may be drawn, and five knockout rounds that may not. A column on
     * {@code Competition} cannot hold "knockout for rounds 6–10, group for rounds 1–5".
     * {@code MatchFormat}'s own {@code Tournament} subclass admitted the flaw in its javadoc — *"knockout
     * rounds go to penalties and the group phase does not"* — a per-match distinction wearing a
     * per-competition type.
     *
     * <p>So the discriminator has to be per match, and P0-CUPS-1 had already put it there: the match
     * records the cup group it was played in. {@code P0-CUPS-1} put that field on {@code Match} for the
     * table rule and this method reads the same one with the opposite sense. <b>One field, two rules,
     * no migration and no new type.</b> {@code MatchFormat} was deleted on the owner's decision,
     * 2026-10-06, on the grounds that it had zero callers and was the wrong shape for the question.
     */
    private static boolean isKnockoutTie(Match match) {
        if (match.getCompetition() == null) {
            return false;
        }
        Competition competition = match.getCompetition();
        if (competition.getType() != CompetitionType.CUP
                && competition.getType() != CompetitionType.TOURNAMENT) {
            return false;
        }
        // A tie outside a group has to be won. A tie inside one may finish level, and settling it
        // from the spot would decide the group on penalties instead of on points.
        //
        // TOURNAMENT is here for the national-team competitions: a World Cup knockout tie is decided
        // on the night, and it is a TOURNAMENT row that no CUP predicate would ever have reached.
        // The group test still protects the qualifying groups, which are TOURNAMENT rows too.
        return !MatchType.isGroupMatch(match);
    }

    /**
     * Runs the shootout and writes it onto the match.
     *
     * <p>Only the match, not the replay. The shootout happens after the final whistle, so the ticks are
     * already recorded and there is nothing to animate; the kicks go into the match's own columns, which
     * is where the cup's winner lookup and the match page read them from.
     */
    private void settleFromTheSpot(Match match) {
        // The squad that played it, built the same way the match was built — through the real lineup
        // template, or the real squad behind it. A shootout taken by a synthetic stand-in squad would
        // be decided by men who were never on the pitch, which is the sort of thing that looks right in
        // the data and is nonsense in the fiction.
        List<Player> home = loadRealSquad(match.getHomeTeam(), "HOME");
        List<Player> away = loadRealSquad(match.getAwayTeam(), "AWAY");
        if (home == null || away == null) {
            return;
        }

        PenaltyShootout.Result shootout =
                PenaltyShootout.run(home, away, match.getHomeGoals(), match.getAwayGoals());
        if (shootout.winningTeam() == null) {
            return;
        }
        match.setHomePenaltyGoals(shootout.homeScored());
        match.setAwayPenaltyGoals(shootout.awayScored());
        log.info("Cup tie {} finished level and was settled {}-{} on penalties ({}).",
                match.getId(), shootout.homeScored(), shootout.awayScored(), shootout.winningTeam());
    }

    private static boolean isHumanClub(org.example.footballmanager.newLogic.model.Team team) {
        return team != null && team.isHumanControlled();
    }

    private void recordZoneLoad(Match match,
                                List<org.example.footballmanager.newLogic.sim.recording.MatchSnapshot> snapshots) {
        if (snapshots == null || snapshots.isEmpty()) {
            return;
        }
        Map<String, org.example.footballmanager.newLogic.model.Player> resolved = new HashMap<>();
        for (org.example.footballmanager.newLogic.sim.recording.PlayerSnapshot snapshotPlayer
                : snapshots.get(snapshots.size() - 1).getPlayers()) {
            if (snapshotPlayer == null) {
                continue;
            }
            Long dbId = parsePlayerId(snapshotPlayer.getId());
            if (dbId == null) {
                continue;
            }
            playerRepository.findById(dbId)
                    .ifPresent(player -> resolved.put(snapshotPlayer.getId(), player));
        }
        zoneLoadRecorder.record(match, snapshots, resolved);
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

    /**
     * @param snapshots every tick of the match, carried so the zone load can be read off the match
     *        that was played. They are already in memory - the recorder holds them until the run
     *        returns - so this is a reference, not a copy.
     */
    public record SimMatchOutcome(ProposalMatchOutcome outcome, long replayId,
                                  Team homeTeam, Team awayTeam,
                                  List<org.example.footballmanager.newLogic.sim.recording.MatchSnapshot> snapshots,
                                  /** Each conditional rule's fate, as JSON, or null when there were none. */
                                  String ruleOutcomeJson) {
        public int homeGoals() { return outcome != null ? outcome.homeGoals() : 0; }
        public int awayGoals() { return outcome != null ? outcome.awayGoals() : 0; }

        /** The four-argument form every existing caller uses. */
        public SimMatchOutcome(ProposalMatchOutcome outcome, long replayId, Team homeTeam, Team awayTeam,
                               List<org.example.footballmanager.newLogic.sim.recording.MatchSnapshot> snapshots) {
            this(outcome, replayId, homeTeam, awayTeam, snapshots, null);
        }
    }

    /**
     * Each conditional rule's fate at the final whistle, as JSON.
     *
     * <p>Reads {@code firedAtMinute} and {@code voidReason} — the two fields the engine writes during
     * the match and nothing ever read. Returns null when no plan was attached, which is the common
     * case and is not worth a row.
     */
    private String ruleOutcomeJson(
            org.example.footballmanager.newLogic.sim.engine.MatchOrchestrator orchestrator) {
        var rules = orchestrator.conditionalSubstitutions().all();
        if (rules == null || rules.isEmpty()) return null;
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var rows = new java.util.ArrayList<java.util.Map<String, Object>>();
        for (var rule : rules) {
            if (rule == null) continue;
            rows.add(new java.util.LinkedHashMap<>(java.util.Map.of(
                    "team", rule.team == null ? "" : rule.team,
                    "triggerMinute", rule.triggerMinute,
                    "condition", rule.condition == null ? "" : rule.condition,
                    "playerOnId", rule.playerOnId == null ? "" : rule.playerOnId,
                    "playerOffId", rule.playerOffId == null ? "" : rule.playerOffId,
                    "status", rule.status == null ? "" : rule.status.name(),
                    "voidReason", rule.voidReason == null ? "" : rule.voidReason.name(),
                    "firedAtMinute", rule.firedAtMinute)));
        }
        if (rows.isEmpty()) return null;
        try {
            return mapper.writeValueAsString(rows);
        } catch (Exception e) {
            // The match is already played; failing to record how its rules went must not cost it.
            log.warn("Could not record the substitution rule outcome for fixture {}: {}",
                    "fixture", e.getMessage());
            return null;
        }
    }
}