package org.tifo.footballmanager.newLogic.sim;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.tifo.footballmanager.newLogic.model.Competition;
import org.tifo.footballmanager.newLogic.model.CompetitionEntry;
import org.tifo.footballmanager.newLogic.model.CompetitionType;
import org.tifo.footballmanager.newLogic.model.GameClock;
import org.tifo.footballmanager.newLogic.model.Lineup;
import org.tifo.footballmanager.newLogic.model.Match;
import org.tifo.footballmanager.newLogic.model.MatchFixture;
import org.tifo.footballmanager.newLogic.model.MatchPlayerStats;
import org.tifo.footballmanager.newLogic.model.MatchType;
import org.tifo.footballmanager.newLogic.model.Position;
import org.tifo.footballmanager.newLogic.model.SeasonCompetition;
import org.tifo.footballmanager.newLogic.model.Team;
import org.tifo.footballmanager.newLogic.repository.CompetitionEntryRepository;
import org.tifo.footballmanager.newLogic.repository.LineupRepository;
import org.tifo.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.tifo.footballmanager.newLogic.repository.MatchPlayerStatsRepository;
import org.tifo.footballmanager.newLogic.repository.MatchRepository;
import org.tifo.footballmanager.newLogic.model.StaffMember;
import org.tifo.footballmanager.newLogic.model.StaffRole;
import org.tifo.footballmanager.newLogic.repository.PlayerRepository;
import org.tifo.footballmanager.newLogic.repository.StaffMemberRepository;
import org.tifo.footballmanager.newLogic.service.NationalRatingService;
import org.tifo.footballmanager.newLogic.sim.engine.InjuryService;
import org.tifo.footballmanager.newLogic.sim.engine.PenaltyShootout;
import org.tifo.footballmanager.newLogic.service.SquadEnvironment;
import org.tifo.footballmanager.newLogic.sim.model.Player;
import org.tifo.footballmanager.newLogic.sim.recording.SimReplayView;
import org.tifo.footballmanager.newLogic.sim.result.ProposalMatchOutcome;
import org.tifo.footballmanager.newLogic.sim.result.SimReportMapper;
import org.tifo.footballmanager.newLogic.sim.util.SimulationRandom;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tifo.footballmanager.newLogic.service.AttendanceService;
import org.tifo.footballmanager.newLogic.service.SeasonService;
import org.tifo.footballmanager.newLogic.service.ZoneLoadRecorder;

import java.time.LocalDateTime;
import com.fasterxml.jackson.core.type.TypeReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.tifo.footballmanager.newLogic.sim.engine.PenaltyShootout.Kick;
import java.util.Map;
import java.util.Set;
import org.tifo.footballmanager.newLogic.util.LazySquadGenerator;

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
    private final org.tifo.footballmanager.newLogic.repository.SubstitutionPlanRepository substitutionPlans;
    private final CompetitionEntryRepository competitionEntryRepository;
    private final SeasonService seasonService;
    private final org.tifo.footballmanager.newLogic.service.MoraleService moraleService;
    private final SimReplayStore replayStore;
    private final LineupRepository lineupRepository;
    private final org.tifo.footballmanager.newLogic.util.LazySquadGenerator lazySquadGenerator;
    private final MatchPlayerStatsRepository matchPlayerStatsRepository;
    private final PlayerRepository playerRepository;
    private final org.tifo.footballmanager.newLogic.service.SquadRegistrationService squadRegistration;
    private final StaffMemberRepository staffMemberRepository;
    private final AttendanceService attendanceService;
    private final ObjectMapper objectMapper;
    private final ZoneLoadRecorder zoneLoadRecorder;
    private final NationalRatingService nationalRatingService;
    private final org.tifo.footballmanager.newLogic.service.TacticsRulesProvider tacticsRules;
    private final org.tifo.footballmanager.newLogic.service.MatchTacticsPreparationService matchTacticsPreparation;
    private final org.tifo.footballmanager.newLogic.service.MatchLineupService matchLineups;
    private final org.tifo.footballmanager.newLogic.service.DisciplineService discipline;
    private final org.tifo.footballmanager.newLogic.repository.TeamTacticsProfileRepository
            teamTacticsProfileRepository;

    /** Simulate a full match between two DB teams using their real saved squads
     *  (mapped into the engine's 4-4-2 slot structure). Falls back to synthetic
     *  squads when a team has no lineup template. */
    public SimMatchOutcome simulate(MatchFixture fixture, boolean storeReplay) {
        return simulate(fixture, storeReplay, true);
    }

    /**
     * Simulates a match, optionally without charging its side effects.
     *
     * <p><b>Why the third parameter exists (owner, 2026-10-17).</b> This method is not a read. It spends
     * the ban of every suspended player in the fixture, and it carries the match's fatigue onto every
     * player who took part. Both are right for a match that is being played — and both are wrong for one
     * that has already been played.
     *
     * <p>{@code POST /api/sim/replay/{matchId}} exists to produce a recording for a match that was
     * simulated without one, so it re-runs this method on a match already in the books. Before this
     * parameter existed, every replay therefore charged the whole match's fatigue a second time and spent
     * a second ban from every suspended player: a one-match ban served twice, and {@code bansServed}
     * inflated until {@code DisciplineService.serve} reset that player's yellow-card count early. The
     * endpoint is a read; this is what makes it one.
     *
     * <p><b>The eleven is unaffected.</b> A ban is spent by the original match, so at replay time a
     * player's remaining bans already decide whether he is picked, exactly as they did the first time.
     *
     * @param applySideEffects {@code false} reproduces the football and writes nothing.
     */
    public SimMatchOutcome simulate(MatchFixture fixture, boolean storeReplay, boolean applySideEffects) {
        return simulate(fixture, storeReplay, applySideEffects, SimMatchRunner.FULL_MATCH_TICKS); }

    public SimMatchOutcome simulate(MatchFixture fixture, boolean storeReplay, boolean applySideEffects, int ticks) {
        return simulate(fixture, storeReplay, applySideEffects, ticks, null, null, null, null);
    }

    public SimMatchOutcome simulate(MatchFixture fixture, boolean storeReplay, boolean applySideEffects,
                                    int ticks, List<Player> awaySquadOverride,
                                    List<Player> awayBenchOverride,
                                    org.tifo.footballmanager.newLogic.sim.tactics.TacticsRules homeTacticsOverride) {
        return simulate(fixture, storeReplay, applySideEffects, ticks, awaySquadOverride, awayBenchOverride,
                homeTacticsOverride, null);
    }

    /**
     * As above, with the away side's eleven chosen by the caller.
     *
     * <p><b>Why the seam exists (owner, 2026-10-27).</b> The training match plays against an opponent
     * that is not in the database at all: the manager picks a strength and every one of the opponent's
     * men arrives with every football skill at that number. {@link #loadRealSquad} can only read a
     * squad that a club in the {@code team} table has, so without this the synthetic side would be
     * handed straight to {@code SimTeamFactory.addTeam} — twenty-two unnamed stand-ins of no standard
     * he chose, and a tactic test that proves nothing.
     *
     * <p>Both parameters are optional and {@code null} means "read the squad the normal way", which is
     * what every existing caller passes. Nothing else about the run changes: the same tactics, the same
     * bench, the same recorder, the same outcome, the same absence of side effects when
     * {@code applySideEffects} is false.
     *
     * @param awaySquadOverride the away side's starting eleven, or {@code null} to read it from the club
     * @param awayBenchOverride the away side's reserves, or {@code null} to read them from the club
     * @param homeTacticsOverride the home side's tactics, or {@code null} to read the club's own
     * @param awayTacticsOverride the away side's tactics, or {@code null} to read the club's own
     *        (falls back to the bundled rules for an in-memory opponent)
     */
    public SimMatchOutcome simulate(MatchFixture fixture, boolean storeReplay, boolean applySideEffects,
                                    int ticks, List<Player> awaySquadOverride,
                                    List<Player> awayBenchOverride,
                                    org.tifo.footballmanager.newLogic.sim.tactics.TacticsRules homeTacticsOverride,
                                    org.tifo.footballmanager.newLogic.sim.tactics.TacticsRules awayTacticsOverride) {
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
        // Bans are spent before the squads are chosen, so the players this fixture frees are the ones
        // the auto-pick sees (T-DISC).
        if (applySideEffects) {
            serveSuspensions(fixture);
        }

        // The fixture is threaded through so a club's pick for THIS match wins over its template.
        List<Player> homeSquad = loadRealSquad(homeTeam, "HOME", homeBench, fixture);
        List<Player> awaySquad;
        if (awaySquadOverride != null) {
            // A side the caller built rather than one the world holds — the training match's opponent.
            // The bench is supplied with it, because a side that cannot substitute is not the side a
            // manager is testing his own tactics against.
            awaySquad = awaySquadOverride;
            if (awayBenchOverride != null) {
                awayBench.addAll(awayBenchOverride);
            }
        } else {
            awaySquad = loadRealSquad(awayTeam, "AWAY", awayBench, fixture);
        }

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
        // The club's conditional instructions for this fixture, read once here and consulted every tick
        // inside the orchestrator (T0-BE-2). Null when nobody planned the fixture, which is the ordinary
        // case — the match then keeps the shape it was built with and costs nothing per tick.
        var liveTactics = matchTacticsPreparation == null ? null
                : matchTacticsPreparation.forFixture(fixture, homeTeam.getId(), awayTeam.getId());

        var orchestrator = SimMatchRunner.build(homeName, awayName,
                homeSquad, awaySquad, homeBench, awayBench,
                new org.tifo.footballmanager.newLogic.sim.tactics.SideTactics(
                        homeTacticsOverride != null ? homeTacticsOverride : tacticsRules.forTeam(homeTeam.getId()),
                        awayTacticsOverride != null ? awayTacticsOverride : tacticsRules.forTeam(awayTeam.getId())),
                liveTactics);

        // Wire the tactic style into the match state so the decision engine can apply it.
        orchestrator.getState().setTacticStyle("HOME", tacticsRules.styleForTeam(homeTeam.getId()));
        orchestrator.getState().setTacticStyle("AWAY", tacticsRules.styleForTeam(awayTeam.getId()));

        applySubstitutionPlan(fixture, orchestrator);

        // Tick snapshots are only ever read by the replay viewer and the zone load, and the zone load
        // is only written for matches involving a human club. Recording 3600 of them for every bot
        // match is the bulk of the per-match memory and the thing that OOMs a full matchday.
        boolean involvesManager = isHumanClub(homeTeam) || isHumanClub(awayTeam);
        orchestrator.getRecorder().setSnapshotsEnabled(storeReplay || involvesManager);

        orchestrator.simulate(ticks);
        ProposalMatchOutcome outcome = orchestrator.buildOutcome();
        // Fatigue and injuries are a consequence of playing. A replay is not playing, so it does not
        // charge them a second time (owner, 2026-10-17).
        if (applySideEffects) {
            persistMatchCondition(orchestrator.getState());
        }
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
                                       org.tifo.footballmanager.newLogic.sim.engine.MatchOrchestrator orchestrator) {
        if (fixture == null || fixture.getId() == null || substitutionPlans == null) return;
        try {
            var plan = substitutionPlans.findByFixtureId(fixture.getId()).orElse(null);
            if (plan == null || plan.getRulesJson() == null || plan.getRulesJson().isBlank()) return;

            org.tifo.footballmanager.newLogic.sim.engine.ConditionalSubstitutionRules.Rule[] rules =
                    new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                            plan.getRulesJson(),
                            org.tifo.footballmanager.newLogic.sim.engine.ConditionalSubstitutionRules.Rule[].class);

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
        return loadRealSquad(team, side, new ArrayList<>(), null);
    }

    private List<Player> loadRealSquad(Team team, String side, MatchFixture fixture) {
        return loadRealSquad(team, side, new ArrayList<>(), fixture);
    }

    private List<Player> loadRealSquad(Team team, String side, List<Player> benchOut) {
        return loadRealSquad(team, side, benchOut, null);
    }

    private List<Player> loadRealSquad(Team team, String side, List<Player> benchOut,
                                       MatchFixture fixture) {
        if (team == null || team.getId() == null) return null;
        Lineup lineup = loadLineup(team, fixture);
        if (lineup != null) {
            List<org.tifo.footballmanager.newLogic.model.Player> ordered =
                    squadRegistration.theEleven(lineup);
            if (ordered != null && ordered.size() >= 11) {
                return RealSquadFactory.buildSquad(lineup, squadRegistration.onTheBooks(team.getId()),
                        side, benchOut, coachFactorFor(team) * cohesionFactorFor(team),
                        formationOf(lineup, team));
            }
            if (ordered != null && !ordered.isEmpty()) {
                // The saved XI is SHORT - one of the manager's men left, retired, or was loaned away.
                //
                // **Top it up from the club's squad rather than throwing the whole XI away.** A manager
                // who picked eleven and lost one still has ten of his own men, and replacing all
                // eleven with an auto-pick is a silent way of telling him his choice did not matter.
                List<org.tifo.footballmanager.newLogic.sim.model.Player> toppedUp =
                        topUpTheXi(ordered, team, side, benchOut, fixture);
                if (toppedUp != null) {
                    return toppedUp;
                }
            }
        }
        // A banned player cannot play (T-DISC), so the auto-pick pool excludes him. He is dropped here
        // rather than from the saved lineup above because that one is the manager's own pick and the
        // screen already tells him who is unavailable; this pool is the club picking for itself.
        //
        // **One query for the whole squad, not one per player.** Asking the database about a ban
        // player by player, twice, for every player of both squads, was about 220 questions a fixture and
        // half a million for a 2,400-fixture matchday — and the derived query it went through rebuilt its
        // whole syntax tree every time. That is what exhausted the heap on 2026-10-19.
        List<org.tifo.footballmanager.newLogic.model.Player> available =
                squadRegistration.availablePlayers(team.getId());
        if (available == null) return null;
        Set<Long> barred = barredPlayerIds(available, fixture);
        List<org.tifo.footballmanager.newLogic.model.Player> pool = available.stream()
                .filter(player -> player.getId() == null || !barred.contains(player.getId()))
                .toList();
        if (pool.size() < 11) return null;
        return RealSquadFactory.buildSquadFromPlayers(pool, side,
                coachFactorFor(team) * cohesionFactorFor(team), formationOf(null, team));
    }

    /**
     * A short XI, topped up from the club's squad.
     *
     * <p><b>The manager's men keep their slots.</b> The nine he still has stand where he put them, and
     * the replacements take the slots that are left. Throwing the whole XI away and auto-picking
     * instead is a silent way of telling him his choice did not matter — and it is what happened
     * whenever one player of his eleven dropped out.
     *
     * @return the eleven, or null when the club cannot fill the gap
     */
    private List<org.tifo.footballmanager.newLogic.sim.model.Player> topUpTheXi(
            List<org.tifo.footballmanager.newLogic.model.Player> ordered, Team team, String side,
            List<Player> benchOut, MatchFixture fixture) {
        List<org.tifo.footballmanager.newLogic.model.Player> available =
                squadRegistration.availablePlayers(team.getId());
        if (available == null) return null;
        Set<Long> barred = barredPlayerIds(available, fixture);
        Set<Long> chosen = new java.util.HashSet<>();
        List<org.tifo.footballmanager.newLogic.model.Player> eleven = new ArrayList<>();
        for (org.tifo.footballmanager.newLogic.model.Player p : ordered) {
            if (p == null || p.getId() == null) continue;
            if (barred.contains(p.getId())) continue;
            if (chosen.add(p.getId())) eleven.add(p);
        }
        if (eleven.size() >= 11) return null;
        List<org.tifo.footballmanager.newLogic.model.Player> replacements = available.stream()
                .filter(p -> p != null && p.getId() != null && !chosen.contains(p.getId()))
                .filter(p -> !barred.contains(p.getId()))
                .sorted(java.util.Comparator.comparingInt(
                        (org.tifo.footballmanager.newLogic.model.Player p) -> p.getRating()).reversed())
                .limit(11 - eleven.size())
                .toList();
        eleven.addAll(replacements);
        if (eleven.size() < 11) return null;
        return RealSquadFactory.buildSquadFromOrdered(eleven, side, benchOut,
                coachFactorFor(team) * cohesionFactorFor(team), formationOf(null, team));
    }

    /**
     * The players of a squad who are barred from this fixture, read in one query.
     *
     * <p>{@link DisciplineService#suspensionsFor} does the work; this only keeps the call site
     * readable and keeps the null-discipline case out of the way.
     */
    private Set<Long> barredPlayerIds(List<org.tifo.footballmanager.newLogic.model.Player> squad,
                                      MatchFixture fixture) {
        if (discipline == null || squad == null || squad.isEmpty() || fixture == null) {
            return Set.of();
        }
        List<Long> ids = squad.stream()
                .map(org.tifo.footballmanager.newLogic.model.Player::getId)
                .filter(java.util.Objects::nonNull)
                .toList();
        if (ids.isEmpty()) {
            return Set.of();
        }
        return discipline.suspensionsFor(ids, fixture.getSeasonYear(), fixture).keySet();
    }

    /**
     * Serves every ban this fixture is serving, for both clubs.
     *
     * <p><b>Served by the fixture, not by the selection.</b> The owner's rule is that a red card bars the
     * first next official match <em>of the club</em> — a match he is banned from is a match the ban is
     * spent on whether or not he would otherwise have been picked. Tying it to selection would let a
     * manager keep a player out of the XI and quietly reset his suspension, which is not a thing the
     * rules allow.
     *
     * <p>Called before the squads are built, so the players it frees are the ones the auto-pick sees.
     */
    private void serveSuspensions(MatchFixture fixture) {
        if (fixture == null || discipline == null) return;
        Integer season = fixture.getSeasonYear();
        for (Team club : java.util.List.of(fixture.getHomeTeam(), fixture.getAwayTeam())) {
            if (club == null || club.getId() == null) continue;
            List<org.tifo.footballmanager.newLogic.model.Player> squad =
                    squadRegistration.availablePlayers(club.getId());
            if (squad == null || squad.isEmpty()) continue;
            // **One query per squad, not one per player.** The check was the single biggest source of
            // database traffic in a matchday, and the reason the day-1 matchday exhausted the heap on
            // 2026-10-19. See DisciplineService.suspensionsFor.
            Set<Long> barred = barredPlayerIds(squad, fixture);
            for (org.tifo.footballmanager.newLogic.model.Player player : squad) {
                if (player.getId() == null) continue;
                if (barred.contains(player.getId())) {
                    discipline.serve(player.getId(), season, fixture);
                }
            }
        }
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
                    .map(org.tifo.footballmanager.newLogic.model.tactics.TeamTacticsProfile::getFormation)
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
        return persist(fixture, outcome, replayId, null, false);
    }

    /**
     * Computes and stores a match without publishing it (owner, 2026-10-11).
     *
     * <p>An hour before kickoff the whole matchday is simulated so that a manager's tactics, eleven
     * and orders are settled before anything is shown - changing them afterwards could not change what
     * was already computed, and pretending otherwise would be the dishonest version of a deadline.
     *
     * <p>The difference from an ordinary persist is two fields and nothing else. {@code played} stays
     * false, so the fixture is still "not played" to every read path in the game, and the match row's
     * id is written to {@code preparedMatchId} instead of {@code playedMatch}. Everything else -
     * player stats, ratings, the league table, zone load - happens exactly as it always does, because
     * a computed match is a real match and its side effects are real.
     *
     * @return the id of the stored match, for the kickoff job to publish
     */
    @Transactional
    public Long prepareMatch(MatchFixture fixture, ProposalMatchOutcome outcome, long replayId,
                             List<org.tifo.footballmanager.newLogic.sim.recording.MatchSnapshot> simSnapshots) {
        return persist(fixture, outcome, replayId, simSnapshots, true);
    }

    /**
     * @param simSnapshots the match's ticks, or null when there are none. Only the zone load reads
     *        them, so every existing three-argument caller keeps working unchanged.
     */
    @Transactional
    public Long persist(MatchFixture fixture, ProposalMatchOutcome outcome, long replayId,
                        List<org.tifo.footballmanager.newLogic.sim.recording.MatchSnapshot> simSnapshots) {
        return persist(fixture, outcome, replayId, simSnapshots, false);
    }

    private Long persist(MatchFixture fixture, ProposalMatchOutcome outcome, long replayId,
                         List<org.tifo.footballmanager.newLogic.sim.recording.MatchSnapshot> simSnapshots,
                         boolean preparedOnly) {
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
            // Which rules produced this. Written here, in the one place a Match row is built, so
            // every stored result is attributable to a ruleset even after the constants move.
            match.setEngineVersion(MatchEngineVersion.stamp());
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

            match.setHomeLineup(loadLineup(fixture.getHomeTeam(), fixture));
            match.setAwayLineup(loadLineup(fixture.getAwayTeam(), fixture));

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
                recordDiscipline(fixture, outcome);
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
            //
            // The zone load is only ever read by the replay viewer, which is only ever opened for a
            // club a manager controls - so writing it for a bot's match is eighty-five rows per match
            // that nobody will read. On a league day that is one match in every division of the world:
            // the zone load alone was the biggest single writer in the prepare job. It is written now
            // only when a manager is involved.
            if (involvesManager) {
                try {
                    recordZoneLoad(match, simSnapshots);
                } catch (RuntimeException e) {
                    log.warn("Could not record zone load for match {}: {}", match.getId(), e.getMessage());
                }
            }

            if (preparedOnly) {
                // Computed, stored, and deliberately still unplayed. See prepareMatch.
                fixture.setPreparedMatchId(match.getId());
                fixture.setPreparedAt(match.getMatchDate() == null
                        ? java.time.LocalDateTime.now() : match.getMatchDate());
            } else {
                fixture.setPlayed(true);
                fixture.setPlayedMatch(match);
            }
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

            log.info("Persisted sim match to DB: id={}, {} {} - {} {} (replayId={}, {})",
                    match.getId(),
                    fixture.getHomeTeam().getName(), outcome != null ? outcome.homeGoals() : 0,
                    outcome != null ? outcome.awayGoals() : 0, fixture.getAwayTeam().getName(),
                    replayId, match.getEngineVersion());
            return match.getId();
        } catch (Exception e) {
            log.error("Failed to persist sim match for fixture={}", fixture.getId(), e);
            return null;
        }
    }

    /**
     * The XI for this match: the club's pick for this fixture first, their template second, nothing third.
     *
     * <p><b>The order is the feature.</b> {@code Lineup.match} has been a nullable column all along and
     * every reader asked for {@code match IS NULL} — the template — because that was the only row that
     * could exist. A manager could not pick a team for a game; the order was whatever the last template
     * said. Reading the fixture's own lineup first is what makes a per-match XI mean anything, and it is
     * deliberately ahead of the template rather than beside it.
     *
     * <p>Resolved through {@code MatchFixture.playedMatch}, so a lineup set before kickoff reaches the
     * match that fixture becomes. When there is no per-match lineup — the overwhelming majority — this
     * behaves exactly as it always did, one extra lookup on a match the club has not planned for.
     */
    private Lineup loadLineup(Team team, MatchFixture fixture) {
        if (team == null || team.getId() == null) return null;

        // The decision itself lives in MatchLineupService so it can be tested directly. It used to be
        // written here, and a test that copied these two lookups proved nothing: mutating this method left
        // the whole suite green.
        return matchLineups.resolve(team.getId(), fixture);
    }

    /** Writes one MatchPlayerStats row per real DB player who took part (synthetic
     *  fallback ids like "HOME-1" cannot resolve and are skipped). */
    /**
     * Writes the cards the engine awarded into the disciplinary record (T-DISC).
     *
     * <p><b>The engine already awards them.</b> {@code DuelService} calls {@code stats.onYellowCard} and
     * {@code stats.onRedCard}, and each {@code PlayerOutcome} carries the totals — so this is a write, not
     * an invention. It sat unwired while the rules existed and the tests were green, which is the shape of
     * {@code ConditionalSubstitutionRules} and the reason the board records such things rather than
     * declaring them done.
     *
     * <p><b>Best-effort.</b> A failure here must not cost the match that has already been played; the same
     * reasoning as the substitution plan's reader. It is logged and the match stands.
     */
    private void recordDiscipline(MatchFixture fixture, ProposalMatchOutcome outcome) {
        if (discipline == null || fixture == null || outcome == null || outcome.players() == null) return;
        Competition competition = fixture.getCompetition();
        Integer season = fixture.getSeasonYear();
        try {
            for (ProposalMatchOutcome.PlayerOutcome po : outcome.players()) {
                if (po.yellowCards() <= 0 && po.redCards() <= 0) continue;
                Long playerId = parsePlayerId(po.playerId());
                if (playerId == null) continue;   // a synthetic engine player, not a squad member
                discipline.record(playerId, season, competition, po.yellowCards(), po.redCards());
            }
        } catch (RuntimeException e) {
            log.warn("Could not record cards for fixture {}: {}", fixture.getId(), e.getMessage());
        }
    }

    /**
     * The database players behind a set of simulation ids, in <b>one</b> query.
     *
     * <p>All four loops that read a match back from the engine used to call
     * {@code playerRepository.findById} once per player: about twenty-two rows per match, replicated
     * across the stats, the career totals, the condition and the zone load, so a single fixture cost
     * something close to a hundred round trips to fetch the same twenty-two players four times. On a
     * matchday that plays every fixture in the world this is the persistence half of the cost.
     *
     * <p>The engine's ids are either a real database id or a synthetic {@code "HOME-1"} label,
     * depending on whether the club had a saved lineup, so they go through {@link #parsePlayerId}
     * first. An id that cannot resolve is simply absent from the map and the caller skips it, which is
     * exactly what the per-player lookup did by returning empty.
     */
    private Map<Long, org.tifo.footballmanager.newLogic.model.Player> loadPlayers(
            java.util.Collection<String> simIds) {
        Set<Long> ids = new LinkedHashSet<>();
        for (String simId : simIds) {
            Long id = parsePlayerId(simId);
            if (id != null) {
                ids.add(id);
            }
        }
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, org.tifo.footballmanager.newLogic.model.Player> byId = new HashMap<>();
        for (org.tifo.footballmanager.newLogic.model.Player player : playerRepository.findAllById(ids)) {
            byId.put(player.getId(), player);
        }
        return byId;
    }

    private void persistPlayerStats(Match match, ProposalMatchOutcome outcome) {
        Team homeTeam = match.getHomeTeam();
        Team awayTeam = match.getAwayTeam();
        int homeConceded = outcome.awayGoals();
        int awayConceded = outcome.homeGoals();
        Map<Long, org.tifo.footballmanager.newLogic.model.Player> dbPlayers = loadPlayers(
                outcome.players().stream().map(ProposalMatchOutcome.PlayerOutcome::playerId).toList());

        List<MatchPlayerStats> pendingStats = new ArrayList<>();
        for (ProposalMatchOutcome.PlayerOutcome po : outcome.players()) {
            Long dbId = parsePlayerId(po.playerId());
            if (dbId == null) continue;
            org.tifo.footballmanager.newLogic.model.Player dbPlayer = dbPlayers.get(dbId);
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
            // The slot the player actually occupied (T-MATCH-8). Carried by the outcome all along and
            // dropped on the floor here, which is the whole reason training could not tell a minute
            // played at full-back from a minute played at centre midfield.
            st.setPositionPlayed(parseRole(po.role()));
            st.setRating(rating100(po.rating()));
            st.setInterceptions(po.interceptions());
            st.setSaves(po.saves());
            st.setCleanSheet(cleanSheet);
            st.setShots(po.shots());
            st.setPassesAttempted(po.passesAttempted());
            st.setPassesCompleted(po.passesCompleted());
            pendingStats.add(st);
        }
        if (!pendingStats.isEmpty()) {
            matchPlayerStatsRepository.saveAll(pendingStats);
        }
    }

    /** Bumps career totalGoals/totalAssists and sets match rating (10-100 scale)
     *  on every real DB player who took part. */
    private void bumpCareerStats(ProposalMatchOutcome outcome) {
        Set<org.tifo.footballmanager.newLogic.model.Player> updated = new LinkedHashSet<>();
        Map<Long, org.tifo.footballmanager.newLogic.model.Player> dbPlayers = loadPlayers(
                outcome.players().stream().map(ProposalMatchOutcome.PlayerOutcome::playerId).toList());
        for (ProposalMatchOutcome.PlayerOutcome po : outcome.players()) {
            Long dbId = parsePlayerId(po.playerId());
            if (dbId == null) continue;
            org.tifo.footballmanager.newLogic.model.Player dbPlayer = dbPlayers.get(dbId);
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
                // collected; saved in batch below
            }
        }
        if (!updated.isEmpty()) {
            playerRepository.saveAll(new ArrayList<>(updated));
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
    private void persistMatchCondition(org.tifo.footballmanager.newLogic.sim.model.MatchState state) {
        if (state == null) return;
        GameClock clock = seasonService.getOrCreateClock();
        int season = clock.getCurrentSeason() == null ? 1 : clock.getCurrentSeason();
        int week = clock.getCurrentWeek() == null ? 1 : clock.getCurrentWeek();

        Map<Long, org.tifo.footballmanager.newLogic.model.Player> dbPlayers = loadPlayers(
                state.getPlayers().stream().map(Player::getId).toList());
        List<org.tifo.footballmanager.newLogic.model.Player> toSave = new ArrayList<>();
        for (Player p : state.getPlayers()) {
            Long dbId = parsePlayerId(p.getId());
            if (dbId == null) continue;
            org.tifo.footballmanager.newLogic.model.Player dbPlayer = dbPlayers.get(dbId);
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
                // **And why it happened**, so the medical page can answer the question instead of
                // leaving the manager to guess.
                dbPlayer.setInjuryCause(p.getInjuryCause());
            }
            toSave.add(dbPlayer);
        }
        if (!toSave.isEmpty()) {
            playerRepository.saveAll(toSave);
        }
    }

    /**
     * The simulation's slot name as a {@link org.tifo.footballmanager.newLogic.model.Position}, or null
     * when it is not one we know.
     *
     * <p>Null is the answer for an unrecognised role and not an error: training counts every minute for a
     * player whose slot was not recorded, which is the old behaviour and the safe one. Mapping an unknown
     * role onto a default would quietly move those minutes to whichever slot happened to be first.
     */
    private static org.tifo.footballmanager.newLogic.model.Position parseRole(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim().toUpperCase();
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            return org.tifo.footballmanager.newLogic.model.Position.valueOf(trimmed);
        } catch (IllegalArgumentException unknownRole) {
            log.debug("Unknown simulation role '{}', recorded as no position.", raw);
            return null;
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
        appendShootoutEvents(match, shootout);
        log.info("Cup tie {} finished level and was settled {}-{} on penalties ({}).",
                match.getId(), shootout.homeScored(), shootout.awayScored(), shootout.winningTeam());
    }

    /**
     * Writes every kick of the shootout into the match's own event list.
     *
     * <p><b>The engine already knew this and it was thrown away.</b> {@code PenaltyShootout.Result} carries
     * a {@code Kick} per penalty — the side, the taker, whether it went in — and only the two totals were
     * kept, in the two columns. So the match was settled, the right club went through, and the report said
     * the tie "drew 2-2" and showed no penalties anywhere. The owner found it on a tie his club had won:
     * *"vidim da sam prosao posle 2-2, po pravilima treba da budu penali a nema ih nigde u izvestaju."*
     *
     * <p>Events rather than a new column, for two reasons. The report's timeline already renders one row
     * per event and this is the same kind of fact; and a shootout kick has no minute, so inventing a
     * timestamp for it would be a lie on a page whose whole job is the order things happened.
     *
     * <p>The running score is carried on each kick, because a shootout is only legible as a sequence —
     * "missed" means nothing without which round and what it left the tie at.
     */
    /** Package-private so a test can prove the kicks are written, not just summed. */
    void appendShootoutEvents(Match match, PenaltyShootout.Result shootout) {
        List<Kick> kicks = shootout.kicks();
        if (kicks == null || kicks.isEmpty()) {
            return;
        }
        List<Object> events = eventList(match);
        if (events == null) {
            return;
        }
        int homeScored = 0;
        int awayScored = 0;
        int kickNumber = 0;
        for (Kick kick : kicks) {
            kickNumber++;
            boolean home = "HOME".equals(kick.team());
            if (kick.scored()) {
                if (home) {
                    homeScored++;
                } else {
                    awayScored++;
                }
            }
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("type", kick.scored() ? "PENALTY_SCORED" : "PENALTY_MISSED");
            event.put("teamSide", home ? "HOME" : "AWAY");
            event.put("playerName", kick.takerName());
            event.put("kickNumber", kickNumber);
            event.put("homeScoreAfter", homeScored);
            event.put("awayScoreAfter", awayScored);
            event.put("suddenDeath", kickNumber > PenaltyShootout.KICKS_EACH * 2);
            event.put("description", (home ? "HOME " : "AWAY ") + kick.takerName() + " "
                    + (kick.scored() ? "scores" : "misses") + " — penalties " + homeScored + "-" + awayScored);
            events.add(event);
        }
        writeEventList(match, events);
    }

    /** The match's own event list, or an empty one when it holds nothing readable. */
    private List<Object> eventList(Match match) {
        String raw = match.getEventJson();
        if (raw == null || raw.isBlank()) {
            return new ArrayList<>();
        }
        try {
            List<Object> parsed = objectMapper.readValue(raw, new TypeReference<List<Object>>() {
            });
            return parsed == null ? new ArrayList<>() : new ArrayList<>(parsed);
        } catch (Exception unreadable) {
            // A match whose timeline cannot be read must not lose its shootout as well, and must not take
            // the whole cup tie down because of it. The totals are already in their own columns.
            log.warn("Could not read the event list of match {} while adding its penalty shootout: {}",
                    match.getId(), unreadable.getMessage());
            return null;
        }
    }

    private void writeEventList(Match match, List<Object> events) {
        try {
            match.setEventJson(objectMapper.writeValueAsString(events));
        } catch (Exception unwritable) {
            log.warn("Could not write the event list of match {}: {}", match.getId(), unwritable.getMessage());
        }
    }

    private static boolean isHumanClub(org.tifo.footballmanager.newLogic.model.Team team) {
        return team != null && team.isHumanControlled();
    }

    private void recordZoneLoad(Match match,
                                List<org.tifo.footballmanager.newLogic.sim.recording.MatchSnapshot> snapshots) {
        if (snapshots == null || snapshots.isEmpty()) {
            return;
        }
        Map<String, org.tifo.footballmanager.newLogic.model.Player> resolved = new HashMap<>();
        Map<Long, org.tifo.footballmanager.newLogic.model.Player> dbPlayers = loadPlayers(
                snapshots.get(snapshots.size() - 1).getPlayers().stream()
                        .filter(java.util.Objects::nonNull)
                        .map(org.tifo.footballmanager.newLogic.sim.recording.PlayerSnapshot::getId)
                        .toList());
        for (org.tifo.footballmanager.newLogic.sim.recording.PlayerSnapshot snapshotPlayer
                : snapshots.get(snapshots.size() - 1).getPlayers()) {
            if (snapshotPlayer == null) {
                continue;
            }
            Long dbId = parsePlayerId(snapshotPlayer.getId());
            if (dbId == null) {
                continue;
            }
            org.tifo.footballmanager.newLogic.model.Player player = dbPlayers.get(dbId);
            if (player != null) {
                resolved.put(snapshotPlayer.getId(), player);
            }
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
                                  List<org.tifo.footballmanager.newLogic.sim.recording.MatchSnapshot> snapshots,
                                  /** Each conditional rule's fate, as JSON, or null when there were none. */
                                  String ruleOutcomeJson) {
        public int homeGoals() { return outcome != null ? outcome.homeGoals() : 0; }
        public int awayGoals() { return outcome != null ? outcome.awayGoals() : 0; }

        /** The four-argument form every existing caller uses. */
        public SimMatchOutcome(ProposalMatchOutcome outcome, long replayId, Team homeTeam, Team awayTeam,
                               List<org.tifo.footballmanager.newLogic.sim.recording.MatchSnapshot> snapshots) {
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
            org.tifo.footballmanager.newLogic.sim.engine.MatchOrchestrator orchestrator) {
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