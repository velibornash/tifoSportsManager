package org.tifo.footballmanager.newLogic.sim;

import org.tifo.footballmanager.BaseTest;
import org.tifo.footballmanager.newLogic.model.Competition;
import org.tifo.footballmanager.newLogic.model.CompetitionTeamType;
import org.tifo.footballmanager.newLogic.model.CompetitionType;
import org.tifo.footballmanager.newLogic.model.MatchFixture;
import org.tifo.footballmanager.newLogic.model.Player;
import org.tifo.footballmanager.newLogic.model.PlayerDiscipline;
import org.tifo.footballmanager.newLogic.model.Position;
import org.tifo.footballmanager.newLogic.model.SkillName;
import org.tifo.footballmanager.newLogic.model.Skills;
import org.tifo.footballmanager.newLogic.model.Team;
import org.tifo.footballmanager.newLogic.repository.CompetitionRepository;
import org.tifo.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.tifo.footballmanager.newLogic.repository.PlayerDisciplineRepository;
import org.tifo.footballmanager.newLogic.repository.PlayerRepository;
import org.tifo.footballmanager.newLogic.repository.TeamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * A replay reproduces the football and charges nothing (owner, 2026-10-17).
 *
 * <p>{@code POST /api/sim/replay/{matchId}} re-simulates a match that has <b>already been played</b>, so
 * that a recording can be produced for a matchday match — those are simulated with
 * {@code storeReplay = false} for storage reasons and have no replay. The endpoint is a read.
 *
 * <p>It was not one. {@code SimMatchService.simulate} spends the ban of every suspended player in the
 * fixture and carries the match's fatigue onto every player who took part, which is right for a match
 * being played and wrong for one already in the books. Before {@code applySideEffects} existed, every
 * replay therefore charged the whole match's fatigue a second time and spent a second ban: a one-match
 * ban served twice, and {@code bansServed} inflated until {@code DisciplineService.serve} reset that
 * player's yellow-card count early.
 *
 * <p><b>This test asserts both directions on purpose.</b> The first half is the fix. The second half
 * runs the same call with side effects on and asserts the damage <i>does</i> happen — so a green run
 * proves the mechanism is real rather than that the assertion measured nothing, which is how three
 * tests in this repository were green while measuring nothing.
 */
class ReplayChargesNoSideEffectsTest extends BaseTest {

    private static final int SEASON = 1;

    @Autowired private SimMatchService simMatchService;
    @Autowired private CompetitionRepository competitions;
    @Autowired private TeamRepository teams;
    @Autowired private PlayerRepository players;
    @Autowired private MatchFixtureRepository fixtures;
    @Autowired private PlayerDisciplineRepository disciplineRecords;

    @Test
    @Transactional
    @DisplayName("a replay adds no fatigue and spends no ban; playing the match does both")
    void aReplayChargesNothing() {
        Competition league = new Competition();
        league.setName("ZZ Replay League " + UUID.randomUUID());
        league.setType(CompetitionType.LEAGUE);
        league.setTier(1);
        league.setTeamType(CompetitionTeamType.CLUB);
        league = competitions.save(league);

        Team home = club("ZZ Replay Home " + UUID.randomUUID(), league);
        Team away = club("ZZ Replay Away " + UUID.randomUUID(), league);

        MatchFixture fixture = new MatchFixture();
        fixture.setCompetition(league);
        fixture.setHomeTeam(home);
        fixture.setAwayTeam(away);
        fixture.setSeasonYear(SEASON);
        fixture.setWeekNumber(1);
        fixture.setDayNumber(1);
        fixture.setPlayed(false);
        fixture = fixtures.save(fixture);

        // A red-card ban: club-wide, so the row has no competition and every official fixture serves it.
        Player banned = players.findByTeamId(home.getId()).get(0);
        PlayerDiscipline record = new PlayerDiscipline();
        record.setPlayer(banned);
        record.setSeason(SEASON);
        record.setCompetition(null);
        record.setYellowCards(0);
        record.setBansOwed(1);
        record.setBansServed(0);
        Long recordId = disciplineRecords.save(record).getId();

        Map<Long, Integer> before = fatigue(home, away);

        // ---- the replay: side effects off ----
        simMatchService.simulate(fixture, true, false);

        assertEquals(before, fatigue(home, away),
                "a replay must not charge the match's fatigue a second time");
        assertEquals(1, bansOwed(recordId),
                "a replay must not spend a ban the original match already served");

        // ---- the same call with side effects on, so this test can fail ----
        simMatchService.simulate(fixture, true, true);

        assertNotEquals(before, fatigue(home, away),
                "playing the match DOES charge fatigue — otherwise the assertion above measured nothing");
        assertEquals(0, bansOwed(recordId),
                "playing the match DOES spend the ban — otherwise the assertion above measured nothing");
    }

    private int bansOwed(Long recordId) {
        return disciplineRecords.findById(recordId).orElseThrow().getBansOwed();
    }

    /** Every player's fatigue, read back through the repository rather than from the entities we saved. */
    private Map<Long, Integer> fatigue(Team... clubs) {
        Map<Long, Integer> out = new LinkedHashMap<>();
        for (Team club : clubs) {
            for (Player p : players.findByTeamId(club.getId())) {
                out.put(p.getId(), p.getSkills() == null ? 0 : p.getSkills().getFatigue());
            }
        }
        return out;
    }

    private Team club(String name, Competition league) {
        Team team = new Team();
        team.setName(name);
        team.setCompetition(league);
        team.setReputation(60.0);
        team.setHumanControlled(false);
        Team saved = teams.save(team);
        for (int i = 0; i < 25; i++) {
            players.save(player(name + " P" + i, saved, positionFor(i)));
        }
        return saved;
    }

    private static Position positionFor(int index) {
        if (index < 2) return Position.GK;
        if (index < 10) return Position.DEF;
        if (index < 18) return Position.MID;
        return Position.ATT;
    }

    private static Player player(String name, Team team, Position position) {
        Skills skills = new Skills();
        skills.setSkill(SkillName.PACE, 12);
        skills.setSkill(SkillName.STAMINA, 12);
        skills.setSkill(SkillName.GOALKEEPER, 12);
        skills.setSkill(SkillName.DEFENDER, 12);
        skills.setSkill(SkillName.TECHNIQUE, 12);
        skills.setSkill(SkillName.PLAYMAKER, 12);
        skills.setSkill(SkillName.PASSING, 12);
        skills.setSkill(SkillName.STRIKER, 12);
        skills.initializeExactFromVisibleIfNeeded();
        Player p = new Player();
        p.setName(name);
        p.setPosition(position);
        p.setSkills(skills);
        p.setAge(21);
        p.setTeam(team);
        p.setRating(p.careerRating());
        return p;
    }

    /** Guards against a silently empty squad: without players there is nothing to charge and no test. */
    @Test
    @Transactional
    @DisplayName("the fixture used above really does field players")
    void theFixtureFieldsPlayers() {
        Competition league = new Competition();
        league.setName("ZZ Replay Squad " + UUID.randomUUID());
        league.setType(CompetitionType.LEAGUE);
        league.setTier(1);
        league.setTeamType(CompetitionTeamType.CLUB);
        league = competitions.save(league);
        Team home = club("ZZ Replay Squad " + UUID.randomUUID(), league);
        List<Player> squad = players.findByTeamId(home.getId());
        assertEquals(25, squad.size(), "the squad has to exist for any of the assertions above to mean anything");
    }
}
