package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.model.Country;
import org.example.footballmanager.newLogic.model.Match;
import org.example.footballmanager.newLogic.model.MatchFixture;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.PlayerZoneLoad;
import org.example.footballmanager.newLogic.model.Position;
import org.example.footballmanager.newLogic.model.SkillName;
import org.example.footballmanager.newLogic.model.Skills;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.model.Zone;
import org.example.footballmanager.newLogic.repository.CompetitionEntryRepository;
import org.example.footballmanager.newLogic.repository.CompetitionRepository;
import org.example.footballmanager.newLogic.repository.CountryRepository;
import org.example.footballmanager.newLogic.repository.LineupRepository;
import org.example.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.example.footballmanager.newLogic.repository.MatchRepository;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.PlayerZoneLoadRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.example.footballmanager.newLogic.sim.SimMatchService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A match writes its zone load, and recovery can then find it (owner, 2026-09-29).
 *
 * <p>The owner asked for "morale / form should be a zone compute, recovery is happening each day".
 * {@code Zone}, {@code PlayerZoneLoad}, {@code ZoneLoadService} and the daily {@code RecoveryJob} all
 * existed, and <b>nothing ever wrote the table</b> — so recovery correctly reported zero for every
 * player in the world and nothing looked broken.
 *
 * <p>It stayed that way because every test of this area asserted the arithmetic rather than the
 * arrival of the data: given these rows, recovery is correct. These tests assert the other half — play
 * a match, and rows exist.
 */
class ZoneLoadWiringTest extends BaseTest {

    @Test
    @Transactional
    @DisplayName("a played match writes a zone load, and recovery then finds it")
    void aPlayedMatchFeedsRecovery() {
        Country country = countryRepository.save(country());
        var competition = competitionRepository.save(competition());
        Team home = teamRepository.save(team("Zone Home", country, competition));
        Team away = teamRepository.save(team("Zone Away", country, competition));
        List<Player> homePlayers = playerRepository.saveAll(squad("Zone Home", home));
        List<Player> awayPlayers = playerRepository.saveAll(squad("Zone Away", away));
        lineupRepository.save(lineupWith(home, homePlayers));
        lineupRepository.save(lineupWith(away, awayPlayers));
        MatchFixture fixture = fixtureRepository.save(fixture(home, away, competition));

        // Before: recovery has nothing to read.
        assertEquals(0.0, zoneLoadService.recoveryFor(homePlayers.get(0).getId()), 0.0001,
                "nothing has been played yet, so there is nothing to recover");

        SimMatchService.SimMatchOutcome sim = simMatchService.simulate(fixture, false);
        Long matchId = simMatchService.persist(fixture, sim.outcome(), -1L, sim.snapshots());
        assertNotNull(matchId);

        // The data arrived. This is the assertion the whole area was missing.
        List<PlayerZoneLoad> rows = zoneLoadRepository.findByPlayerIdOrderByIdDesc(homePlayers.get(0).getId());
        assertTrue(!rows.isEmpty(),
                "a played match must leave a zone load behind - without this the recovery rules, the "
                        + "zone breakdown and the daily recovery job all read an empty table");
        assertTrue(rows.stream().anyMatch(r -> r.getMatch() != null && r.getMatch().getId().equals(matchId)));
        assertTrue(rows.stream().allMatch(r -> r.getMinutes() > 0), "a player who played has minutes somewhere");
        assertTrue(rows.stream().allMatch(r -> r.getIntensity() >= 0.0 && r.getIntensity() <= 1.0),
                "intensity is a 0-1 scale, not a speed in cells per tick");

        // And recovery can now find it, which was the point of the whole thing.
        assertTrue(zoneLoadService.recoveryFor(homePlayers.get(0).getId()) > 0.0,
                "recovery must be positive for a player who has just played ninety minutes");

        // His zones have to add up to the time he was on the pitch. If they do not, a ninety-minute
        // match has quietly become a hundred and twenty, and every recovery figure downstream is
        // wrong by that much without anything looking wrong.
        double totalMinutes = rows.stream().mapToDouble(PlayerZoneLoad::getMinutes).sum();
        assertTrue(totalMinutes > 80.0 && totalMinutes <= 95.0,
                () -> "a starter's zones summed to " + totalMinutes + " minutes, which is not a match");

        // lastPlayedAt was the other half of the task, and had zero writers before this.
        assertNotNull(playerRepository.findById(homePlayers.get(0).getId()).orElseThrow().getLastPlayedAt(),
                "a player who has played must have a lastPlayedAt");
    }

    @Test
    @DisplayName("zones are from the player's own perspective, so the two teams mirror")
    void zonesAreFromThePlayersOwnPerspective() {
        // Row 1 is a fixed goal line, so it is one team's own third and the other's attacking third.
        // Reading it as absolute would make the two sides' loads incomparable, which is the one thing
        // the model's own class comment rules out.
        assertEquals(Zone.DEFENSIVE_CENTRE, ZoneLoadRecorder.zoneFor(1.5, 3.5, "HOME"),
                "row 1 is the home team's own goal");
        assertEquals(Zone.ATTACKING_CENTRE, ZoneLoadRecorder.zoneFor(1.5, 3.5, "AWAY"),
                "and it is the away team's attacking third, because row 1 is the HOME goal");
        assertEquals(Zone.DEFENSIVE_CENTRE, ZoneLoadRecorder.zoneFor(7.5, 3.5, "AWAY"),
                "row 7 is the away team's own goal end, so it is his defensive third");

        // The lane mirrors with the third. Two players on the same physical column, at the same row,
        // are on opposite sides of their own pitch - which is the whole point of "from the player's own
        // perspective": the loads have to be comparable between the two teams to be worth recording.
        assertEquals(Zone.MIDFIELD_LEFT, ZoneLoadRecorder.zoneFor(4.5, 1.5, "HOME"));
        assertEquals(Zone.MIDFIELD_RIGHT, ZoneLoadRecorder.zoneFor(4.5, 1.5, "AWAY"),
                "his left is the pitch's right");
    }

    // ---------- helpers ----------

    private org.example.footballmanager.newLogic.model.Competition competition() {
        org.example.footballmanager.newLogic.model.Competition c =
                new org.example.footballmanager.newLogic.model.Competition();
        c.setName("Zone League");
        c.setType(org.example.footballmanager.newLogic.model.CompetitionType.LEAGUE);
        c.setScope(org.example.footballmanager.newLogic.model.CompetitionScope.NATIONAL);
        c.setTeamType(org.example.footballmanager.newLogic.model.CompetitionTeamType.CLUB);
        c.setTier(1);
        c.setTeamsPerCompetition(16);
        c.setHasPlayoff(false);
        c.setHasPlayout(false);
        return c;
    }

    private Country country() {
        Country c = new Country();
        c.setName("Zone Republic");
        c.setIsoCode("ZNR");
        c.setReputation(60);
        return c;
    }

    private Team team(String name, Country country,
                      org.example.footballmanager.newLogic.model.Competition competition) {
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
        java.util.List<Player> out = new java.util.ArrayList<>();
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

    private org.example.footballmanager.newLogic.model.Lineup lineupWith(Team team, List<Player> starters) {
        org.example.footballmanager.newLogic.model.Lineup lineup =
                new org.example.footballmanager.newLogic.model.Lineup();
        lineup.setTeam(team);
        lineup.setStartingPlayers(new java.util.ArrayList<>(starters));
        lineup.setStarterOrder(String.join(",", starters.stream()
                .map(Player::getId).map(String::valueOf).toList()));
        lineup.setFormation("4-4-2");
        return lineup;
    }

    private MatchFixture fixture(Team home, Team away,
                                 org.example.footballmanager.newLogic.model.Competition competition) {
        MatchFixture f = new MatchFixture();
        f.setHomeTeam(home);
        f.setAwayTeam(away);
        f.setCompetition(competition);
        f.setSeasonYear(1);
        f.setRoundNumber(1);
        f.setWeekNumber(1);
        f.setMatchDate(LocalDateTime.now());
        return f;
    }

    @Autowired private SimMatchService simMatchService;
    @Autowired private ZoneLoadService zoneLoadService;
    @Autowired private PlayerZoneLoadRepository zoneLoadRepository;
    @Autowired private PlayerRepository playerRepository;
    @Autowired private TeamRepository teamRepository;
    @Autowired private CountryRepository countryRepository;
    @Autowired private CompetitionRepository competitionRepository;
    @Autowired private CompetitionEntryRepository competitionEntryRepository;
    @Autowired private LineupRepository lineupRepository;
    @Autowired private MatchFixtureRepository fixtureRepository;
    @Autowired private MatchRepository matchRepository;
}
