package org.example.footballmanager.newLogic.sim;

import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.CompetitionScope;
import org.example.footballmanager.newLogic.model.CompetitionTeamType;
import org.example.footballmanager.newLogic.model.CompetitionType;
import org.example.footballmanager.newLogic.model.Country;
import org.example.footballmanager.newLogic.model.Lineup;
import org.example.footballmanager.newLogic.model.Match;
import org.example.footballmanager.newLogic.model.MatchFixture;
import org.example.footballmanager.newLogic.model.MatchPlayerStats;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Position;
import org.example.footballmanager.newLogic.model.SkillName;
import org.example.footballmanager.newLogic.model.Skills;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.CompetitionRepository;
import org.example.footballmanager.newLogic.repository.CountryRepository;
import org.example.footballmanager.newLogic.repository.LineupRepository;
import org.example.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.example.footballmanager.newLogic.repository.MatchPlayerStatsRepository;
import org.example.footballmanager.newLogic.repository.MatchRepository;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S7.1: every fixture the world plays is played by the proposal engine.
 *
 * <p>The audit that produced this task claimed AI-vs-AI league matches were produced by a Poisson
 * dice roll and that the league table was therefore statistically incoherent with the match engine.
 * <b>That was wrong, and the correction is the reason this test exists.</b> The real paths already
 * ran the proposal engine; {@code simulateQuickScore} was only reachable from two services that had
 * zero callers, and one of them was not injected anywhere. Sprint 7 was cut from five days to a
 * regression test — this one.
 *
 * <p>So this is not a test that the engine works. It is a test that <b>nothing has grown beside
 * it</b>. A dice-roll path is the easiest possible thing to reintroduce: it is fast, it is
 * deterministic if you seed it, and it makes the league table look tidy. The structural half names
 * the two places a football {@code Match} may be born and the two places the engine may be entered,
 * so a third is a test failure rather than a quiet divergence. The behavioural half proves the
 * engine's outcome is what reaches the database, which is the half the retracted audit never
 * established.
 *
 * <p>American football and basketball have their own engines and are deliberately outside the
 * structural scan: they are separate games, they simulate their own fixtures, and a scan that
 * included them would report their code as a violation.
 */
class ProposalEngineIsTheOnlyFixtureProducerTest extends BaseTest {

    private static final Path JAVA = Path.of("src/main/java/org/example/footballmanager");

    /**
     * The entry points, by file name.
     *
     * <p><b>Added deliberately, not loosened.</b> {@code ExhibitionMatchService} is the third path
     * into the engine (P2-8): a manager's own practice match, played inline so it is never picked up
     * by the matchday job and never reaches a table. It goes through the same
     * {@code SimMatchService.persist} as a competitive match, so every rule about what an exhibition
     * changes is enforced in one place rather than in a second engine path.
     *
     * <p>This is still an allow-list, not a lower bound. The next caller fails here.
     */
    private static final Set<String> SIMULATE_ENTRY_POINTS =
            Set.of("SimulationController.java", "AsyncSimulationRunner.java",
                    "ExhibitionMatchService.java");

    // ---------- structural: nothing grows beside the engine ----------

    @Test
    @DisplayName("exactly one production site constructs a football Match - SimMatchService")
    void onlyOneSiteConstructsAMatch() throws IOException {
        Set<String> constructors = productionFilesMatching(Pattern.compile("\\bnew\\s+Match\\s*\\("));

        assertEquals(Set.of("SimMatchService.java"), constructors,
                () -> "A football Match is now constructed in " + constructors + ". Every other "
                        + "matchRepository.save in this game updates a row that SimMatchService "
                        + "already wrote. A second constructor is a second way for a fixture to be "
                        + "played, and a fixture played outside the proposal engine is exactly what "
                        + "this task exists to prevent - it is what the retracted audit believed was "
                        + "already happening.");

        // The saves themselves are fine, and it is worth saying why: a fixture is played when its
        // Match row is born, and the rest of these are the replay store clearing a dangling id.
        for (String file : productionFilesMatching(Pattern.compile("matchRepository\\.save\\s*\\("))) {
            assertTrue(Set.of("SimMatchService.java", "MatchReplayService.java", "MatchController.java",
                            "MatchPersistenceService.java", "SimReplayController.java").contains(file),
                    () -> file + " writes a Match row and is not on the known list. If it is writing a "
                            + "new row rather than updating one, that is a new producer.");
        }
    }

    @Test
    @DisplayName("the engine is entered from the audited call sites and no others")
    void onlyTheTwoKnownEntryPointsSimulate() throws IOException {
        Set<String> callers = productionFilesMatching(
                Pattern.compile("\\bsimMatchService\\.simulate\\s*\\("));

        assertEquals(SIMULATE_ENTRY_POINTS, callers,
                () -> "simMatchService.simulate is now called from " + callers + ". A new caller is "
                        + "not automatically wrong, but it has to be added here deliberately: a third "
                        + "path into the engine is a third path that could later be a path around it.");
    }

    @Test
    @DisplayName("the retracted dice-roll entry point stays gone")
    void noDiceRollEntryPointExists() throws IOException {
        Set<String> offenders = productionFilesMatching(
                Pattern.compile("simulateQuickScore|quickScore|poissonScore|randomScoreline"));

        assertTrue(offenders.isEmpty(),
                () -> "A dice-roll scorer is back in " + offenders + ". That is the specific defect "
                        + "this task was written to rule out: it makes the league table statistically "
                        + "incoherent with the match engine, and nothing about it fails loudly.");
    }

    // ---------- behavioural: the engine's outcome is what lands ----------

    @Test
    @Transactional
    @DisplayName("a real engine run lands in the database with evidence a dice roll cannot produce")
    void aRealEngineRunReachesTheDatabase() {
        Country country = countryRepository.save(country());
        Competition competition = competitionRepository.save(competition(country));
        Team home = teamRepository.save(team("Engine Home", country, competition));
        Team away = teamRepository.save(team("Engine Away", country, competition));

        List<Player> homePlayers = playerRepository.saveAll(squad("Home", home));
        List<Player> awayPlayers = playerRepository.saveAll(squad("Away", away));
        lineupRepository.save(lineupWith(home, homePlayers));
        lineupRepository.save(lineupWith(away, awayPlayers));

        MatchFixture fixture = fixtureRepository.save(fixture(home, away, competition));

        SimMatchService.SimMatchOutcome sim = simMatchService.simulate(fixture, true);
        Long matchId = simMatchService.persist(fixture, sim.outcome(), sim.replayId());
        assertNotNull(matchId, "the engine's outcome must persist");

        Match match = matchRepository.findById(matchId).orElseThrow();

        // 1. A full match, not a scoreline. The stoppage clock announces added time, so this is a
        //    floor rather than the old exact 3600 - that assertion was pinning the bug it later fixed.
        assertTrue(match.isPlayed() && match.isFinished());
        assertNotNull(sim.outcome());
        assertTrue(sim.outcome().totalTicks() >= 3600L,
                () -> "the engine ran only " + sim.outcome().totalTicks() + " ticks");
        assertTrue(sim.outcome().minute() >= 90,
                () -> "the engine reached minute " + sim.outcome().minute() + ", not full time");

        // 2. Per-player rows for both elevens. A dice roll has no players in it at all.
        List<MatchPlayerStats> stats = statsRepository.findByMatchId(matchId);
        assertEquals(22, stats.size(), "every starter on the pitch must get a row");

        // 3. Possession that adds up, rather than a constant.
        assertEquals(100.0, match.getPossessionHome() + match.getPossessionAway(), 0.5);

        // 4. A replay, and a fixture that is now played with a real match behind it.
        assertTrue(sim.replayId() > 0, "a stored match must leave a replay behind");
        assertNotNull(match.getReplayId());

        MatchFixture reloaded = fixtureRepository.findById(fixture.getId()).orElseThrow();
        assertTrue(reloaded.isPlayed());
        assertNotNull(reloaded.getPlayedMatch(), "a played fixture must point at the match that played it");

        // 5. Team statistics with real volume behind them. A scorer that picks a winner produces a
        //    score and nothing else, so possession, xG and pass accuracy that came out of a
        //    simulation are evidence a dice roll cannot leave behind. The key names are the
        //    mapper's, so this also pins the shape SimReportMapper publishes.
        String statsJson = match.getStatsJson();
        assertNotNull(statsJson, "the engine's team statistics must be persisted");
        for (String key : List.of("homePossession", "homeExpectedGoals", "homePassAccuracy",
                "awayPossession", "awayExpectedGoals", "awayPassAccuracy")) {
            assertTrue(statsJson.contains("\"" + key + "\""),
                    () -> "team statistics are missing " + key + ", which is the engine's own output");
        }
    }

    @Test
    @Transactional
    @DisplayName("re-simulating one fixture is deterministic, so a fixture cannot be re-rolled")
    void theSameFixtureProducesTheSameMatch() {
        Country country = countryRepository.save(country());
        Competition competition = competitionRepository.save(competition(country));
        Team home = teamRepository.save(team("Roll Home", country, competition));
        Team away = teamRepository.save(team("Roll Away", country, competition));
        lineupRepository.save(lineupWith(home, playerRepository.saveAll(squad("Roll Home", home))));
        lineupRepository.save(lineupWith(away, playerRepository.saveAll(squad("Roll Away", away))));
        MatchFixture fixture = fixtureRepository.save(fixture(home, away, competition));

        var first = simMatchService.simulate(fixture, false).outcome();
        var second = simMatchService.simulate(fixture, false).outcome();

        assertEquals(first.homeGoals(), second.homeGoals(), "a fixture is seeded by its own id");
        assertEquals(first.awayGoals(), second.awayGoals(), "a fixture is seeded by its own id");
        assertEquals(first.totalTicks(), second.totalTicks());
    }

    // ---------- helpers ----------

    /**
     * The production files under the football game whose contents match.
     *
     * <p>Comments are stripped first, so a Javadoc that names a method is not a reference to it. The
     * audit that this task came from was retracted precisely because a search counted a mention.
     */
    private Set<String> productionFilesMatching(Pattern needle) throws IOException {
        Set<String> hits = new TreeSet<>();
        try (Stream<Path> files = Files.walk(JAVA)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String code = stripComments(Files.readString(file, StandardCharsets.UTF_8));
                Matcher m = needle.matcher(code);
                if (m.find()) {
                    hits.add(file.getFileName().toString());
                }
            }
        }
        return hits;
    }

    private String stripComments(String src) {
        return src.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", " ");
    }

    private Country country() {
        Country c = new Country();
        c.setName("Engine Republic");
        c.setIsoCode("ENR");
        c.setReputation(60);
        return c;
    }

    private Competition competition(Country country) {
        Competition c = new Competition();
        c.setName("Engine League");
        c.setType(CompetitionType.LEAGUE);
        c.setScope(CompetitionScope.NATIONAL);
        c.setTeamType(CompetitionTeamType.CLUB);
        c.setCountry(country);
        c.setTier(1);
        c.setTeamsPerCompetition(16);
        c.setHasPlayoff(false);
        c.setHasPlayout(false);
        return c;
    }

    private Team team(String name, Country country, Competition competition) {
        Team t = new Team();
        t.setName(name);
        t.setCountry(country);
        t.setCompetition(competition);
        t.setReputation(60.0);
        t.setBudget(10_000_000.0);
        t.setHumanControlled(false);
        return t;
    }

    private List<Player> squad(String prefix, Team team) {
        Position[] shape = {Position.GK, Position.DEF, Position.DEF, Position.DEF, Position.DEF,
                Position.MID, Position.MID, Position.MID, Position.MID,
                Position.ATT, Position.ATT};
        List<Player> out = new ArrayList<>();
        for (int i = 0; i < shape.length; i++) {
            Player p = new Player();
            p.setName(prefix + " P" + (i + 1));
            p.setPosition(shape[i]);
            Skills skills = new Skills();
            skills.setSkill(SkillName.PACE, 14);
            skills.setSkill(SkillName.STAMINA, 15);
            skills.setSkill(SkillName.GOALKEEPER, 12);
            skills.setSkill(SkillName.DEFENDER, 13);
            skills.setSkill(SkillName.TECHNIQUE, 13);
            skills.setSkill(SkillName.PLAYMAKER, 13);
            skills.setSkill(SkillName.PASSING, 14);
            skills.setSkill(SkillName.STRIKER, 14);
            skills.initializeExactFromVisibleIfNeeded();
            p.setSkills(skills);
            p.setAge(22);
            p.setTeam(team);
            p.setTotalGoals(0);
            p.setTotalAssists(0);
            out.add(p);
        }
        return out;
    }

    private Lineup lineupWith(Team team, List<Player> starters) {
        Lineup lineup = new Lineup();
        lineup.setTeam(team);
        lineup.setStartingPlayers(new ArrayList<>(starters));
        lineup.setStarterOrder(String.join(",", starters.stream()
                .map(Player::getId).map(String::valueOf).toList()));
        lineup.setFormation("4-4-2");
        return lineup;
    }

    private MatchFixture fixture(Team home, Team away, Competition competition) {
        MatchFixture f = new MatchFixture();
        f.setHomeTeam(home);
        f.setAwayTeam(away);
        f.setCompetition(competition);
        // A season is a number counted from 1. The other test in this area still said 2026, which
        // is the calendar-year scheme this project no longer uses.
        f.setSeasonYear(1);
        f.setRoundNumber(1);
        f.setWeekNumber(1);
        f.setMatchDate(LocalDateTime.now().plusDays(1));
        return f;
    }

    @Autowired private SimMatchService simMatchService;
    @Autowired private MatchRepository matchRepository;
    @Autowired private MatchFixtureRepository fixtureRepository;
    @Autowired private MatchPlayerStatsRepository statsRepository;
    @Autowired private PlayerRepository playerRepository;
    @Autowired private TeamRepository teamRepository;
    @Autowired private CountryRepository countryRepository;
    @Autowired private CompetitionRepository competitionRepository;
    @Autowired private LineupRepository lineupRepository;
}
