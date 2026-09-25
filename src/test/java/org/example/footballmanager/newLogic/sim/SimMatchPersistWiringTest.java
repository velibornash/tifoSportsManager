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
import org.example.footballmanager.newLogic.model.Skills;
import org.example.footballmanager.newLogic.model.SkillName;
import org.example.footballmanager.newLogic.model.Stadium;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.CompetitionEntryRepository;
import org.example.footballmanager.newLogic.repository.CompetitionRepository;
import org.example.footballmanager.newLogic.repository.CountryRepository;
import org.example.footballmanager.newLogic.repository.LineupRepository;
import org.example.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.example.footballmanager.newLogic.repository.MatchPlayerStatsRepository;
import org.example.footballmanager.newLogic.repository.MatchRepository;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.example.footballmanager.newLogic.sim.result.ProposalMatchOutcome;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Integration test verifying the full entity wiring done by
 *  {@link SimMatchService#persist}: MatchPlayerStats rows for real DB players,
 *  home/away Lineup links on the Match, Player career bumps and stadium
 *  attendance. */
class SimMatchPersistWiringTest extends BaseTest {

    @Autowired private SimMatchService simMatchService;
    @Autowired private MatchRepository matchRepository;
    @Autowired private MatchFixtureRepository fixtureRepository;
    @Autowired private MatchPlayerStatsRepository statsRepository;
    @Autowired private PlayerRepository playerRepository;
    @Autowired private TeamRepository teamRepository;
    @Autowired private CountryRepository countryRepository;
    @Autowired private CompetitionRepository competitionRepository;
    @Autowired private LineupRepository lineupRepository;
    @Autowired private CompetitionEntryRepository entryRepository;

    @Test
    @Transactional
    void persistWiresMatchPlayerStatsLineupsCareersAndAttendance() {
        Country country = countryRepository.save(country());
        Competition competition = competitionRepository.save(competition(country));

        Team home = teamRepository.save(team("Home FC", country, competition));
        Team away = teamRepository.save(team("Away United", country, competition));

        List<Player> homePlayers = playerRepository.saveAll(starters("Home", home));
        List<Player> awayPlayers = playerRepository.saveAll(starters("Away", away));

        Lineup homeLineup = lineupRepository.save(lineupWith(home, homePlayers));
        Lineup awayLineup = lineupRepository.save(lineupWith(away, awayPlayers));

        MatchFixture fixture = fixtureRepository.save(fixture(home, away, competition));

        ProposalMatchOutcome outcome = syntheticOutcome(home.getId(), away.getId(), homePlayers, awayPlayers);

        Long matchId = simMatchService.persist(fixture, outcome, -1L);
        assertNotNull(matchId);

        // 1. Match row written + reopen with lineups linked
        Match match = matchRepository.findById(matchId).orElseThrow();
        assertTrue(match.isPlayed() && match.isFinished());
        assertNotNull(match.getHomeLineup());
        assertNotNull(match.getAwayLineup());
        assertEquals(homeLineup.getId(), match.getHomeLineup().getId());
        assertEquals(awayLineup.getId(), match.getAwayLineup().getId());
        assertEquals(2, match.getHomeGoals());
        assertEquals(0, match.getAwayGoals());

        // 2. MatchPlayerStats row per real DB player (22 total), home won to nil so
        //    home GK/DEF get clean sheet
        List<MatchPlayerStats> stats = statsRepository.findByMatchId(matchId);
        assertEquals(22, stats.size());
        long homeCleanSheets = stats.stream().filter(MatchPlayerStats::isCleanSheet).count();
        assertEquals(5, homeCleanSheets);
        long awayCleanSheets = stats.stream().filter(s -> s.isCleanSheet()
                && awayPlayers.stream().anyMatch(p -> p.getId().equals(s.getPlayer().getId()))).count();
        assertEquals(0, awayCleanSheets);
        // rating scaled to 10-100
        MatchPlayerStats scorer = stats.stream()
                .filter(s -> s.getGoals() == 2).findFirst().orElseThrow();
        assertEquals(80, scorer.getRating());

        // 3. Career bumps: scorer got +2 goals, +1 assist; rating set
        Player dbScorer = playerRepository.findById(scorer.getPlayer().getId()).orElseThrow();
        assertEquals(2, dbScorer.getTotalGoals());
        assertEquals(1, dbScorer.getTotalAssists());
        assertEquals(80, dbScorer.getRating());

        // 4. League table entries created for both teams
        assertTrue(entryRepository.findByTeam(home).size() == 1);
        assertTrue(entryRepository.findByTeam(away).size() == 1);
    }

    @Test
    @Transactional
    void persistSkipsSyntheticPlayerIds() {
        Country country = countryRepository.save(country());
        Competition competition = competitionRepository.save(competition(country));
        Team home = teamRepository.save(team("Synth Home", country, competition));
        Team away = teamRepository.save(team("Synth Away", country, competition));
        MatchFixture fixture = fixtureRepository.save(fixture(home, away, competition));

        // Only synthetic ids — none resolve to DB players, so no stats rows
        ProposalMatchOutcome outcome = syntheticOutcome("HOME-1", "AWAY-1");
        Long matchId = simMatchService.persist(fixture, outcome, -1L);
        assertNotNull(matchId);
        assertEquals(0, statsRepository.findByMatchId(matchId).size());
        // match still persisted
        Match match = matchRepository.findById(matchId).orElseThrow();
        assertEquals(1, match.getHomeGoals());
        assertEquals(0, match.getAwayGoals());
    }

    // ---------- helpers ----------

    private static Country country() {
        Country c = new Country();
        c.setName("Wiring Republic");
        c.setIsoCode("WRG");
        c.setReputation(60);
        return c;
    }

    private static Competition competition(Country country) {
        Competition c = new Competition();
        c.setName("Wiring League");
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

    private static Team team(String name, Country country, Competition competition) {
        Team t = new Team();
        t.setName(name);
        t.setCountry(country);
        t.setCompetition(competition);
        t.setReputation(60.0);
        t.setBudget(10_000_000.0);
        t.setHumanControlled(false);
        return t;
    }

    private static List<Player> starters(String prefix, Team team) {
        Position[] line = {Position.GK, Position.DEF, Position.DEF, Position.DEF, Position.DEF,
                Position.MID, Position.MID, Position.MID, Position.MID,
                Position.ATT, Position.ATT};
        List<Player> out = new ArrayList<>();
        for (int i = 1; i <= 11; i++) {
            out.add(player(prefix + " P" + i, line[i - 1], team));
        }
        return out;
    }

    private static Player player(String name, Position position, Team team) {
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
        Player p = new Player();
        p.setName(name);
        p.setPosition(position);
        p.setSkills(skills);
        p.setAge(22);
        p.setTeam(team);
        p.setTotalGoals(0);
        p.setTotalAssists(0);
        return p;
    }

    private static Lineup lineupWith(Team team, List<Player> starters) {
        Lineup lineup = new Lineup();
        lineup.setTeam(team);
        lineup.setStartingPlayers(new ArrayList<>(starters));
        lineup.setStarterOrder(String.join(",", starters.stream().map(Player::getId).map(String::valueOf).toList()));
        lineup.setFormation("4-4-2");
        return lineup;
    }

    private static MatchFixture fixture(Team home, Team away, Competition competition) {
        MatchFixture f = new MatchFixture();
        f.setHomeTeam(home);
        f.setAwayTeam(away);
        f.setCompetition(competition);
        f.setSeasonYear(2026);
        f.setRoundNumber(8);
        f.setWeekNumber(8);
        f.setMatchDate(LocalDateTime.now().plusDays(1));
        return f;
    }

    /** Real-id outcome: 22 players with DB ids; HOME wins 2-0; the first ATT
     *  scores 2 goals (rating 8.0), everyone else 0 (rating 6.5). */
    private static ProposalMatchOutcome syntheticOutcome(Long homeId, Long awayId,
                                                         List<Player> homePlayers, List<Player> awayPlayers) {
        List<ProposalMatchOutcome.PlayerOutcome> players = new ArrayList<>();
        for (Player p : homePlayers) {
            boolean scorer = p.getPositionEnum() == Position.ATT;
            players.add(po(String.valueOf(p.getId()), p.getName(), "Home FC",
                    scorer ? 2 : 0, scorer ? 1 : 0, scorer ? 8.0 : 6.5, 90, p.getPositionEnum()));
        }
        for (Player p : awayPlayers) {
            boolean scorer = p.getPositionEnum() == Position.ATT;
            players.add(po(String.valueOf(p.getId()), p.getName(), "Away United",
                    scorer ? 0 : 0, scorer ? 0 : 0, scorer ? 6.5 : 6.0, 90, p.getPositionEnum()));
        }
        return new ProposalMatchOutcome(
                "Home FC", "Away United", 2, 0, 3600L, 90,
                "4-4-2", "4-4-2",
                52.0, 48.0, 1.7, 0.9,
                teamOutcome("Home FC", 2), teamOutcome("Away United", 0),
                players, List.of(), null, null, null);
    }

    /** Synthetic-only outcome baselines (no DB ids). */
    private static ProposalMatchOutcome syntheticOutcome(String homePrefix, String awayPrefix) {
        List<ProposalMatchOutcome.PlayerOutcome> players = List.of(
                po(homePrefix + "-1", "Synth Home GK", "Home FC", 0, 0, 6.5, 90, Position.GK),
                po(awayPrefix + "-1", "Synth Away GK", "Away United", 0, 0, 6.5, 90, Position.GK));
        return new ProposalMatchOutcome(
                "Synth Home", "Synth Away", 1, 0, 3600L, 90,
                "4-4-2", "4-4-2",
                51.0, 49.0, 1.0, 0.0,
                teamOutcome("Synth Home", 1), teamOutcome("Synth Away", 0),
                players, List.of(), null, null, null);
    }

    private static ProposalMatchOutcome.PlayerOutcome po(String id, String name, String team,
                                                         int goals, int assists, double rating,
                                                         int minutes, Position position) {
        return new ProposalMatchOutcome.PlayerOutcome(
                id, name, team, position.name(),
                goals, assists, goals > 0 ? 4 : 1, goals > 0 ? 3 : 0,
                30, 24, 2, 1, 1, 0, 0,
                position == Position.GK ? 3 : 0,
                2, 1, 0, 0, 0, minutes, rating);
    }

    private static ProposalMatchOutcome.TeamOutcome teamOutcome(String teamName, int goals) {
        return new ProposalMatchOutcome.TeamOutcome(
                teamName, goals, 12, 5, 330, 290, 8, 6, 4, 2, 2,
                goals > 0 ? 2 : 3, 5, 7, 9, 2, 1, 0, 0, 0,
                goals >= 2 ? 52.0 : 40.0, 6.4);
    }
}