package org.example.footballmanager.newLogic.sim;

import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.CompetitionEntry;
import org.example.footballmanager.newLogic.model.CompetitionScope;
import org.example.footballmanager.newLogic.model.CompetitionTeamType;
import org.example.footballmanager.newLogic.model.CompetitionType;
import org.example.footballmanager.newLogic.model.Country;
import org.example.footballmanager.newLogic.model.Lineup;
import org.example.footballmanager.newLogic.model.Match;
import org.example.footballmanager.newLogic.model.MatchFixture;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Position;
import org.example.footballmanager.newLogic.model.SeasonCompetition;
import org.example.footballmanager.newLogic.model.SkillName;
import org.example.footballmanager.newLogic.model.Skills;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.CompetitionEntryRepository;
import org.example.footballmanager.newLogic.repository.CompetitionRepository;
import org.example.footballmanager.newLogic.repository.CountryRepository;
import org.example.footballmanager.newLogic.repository.LineupRepository;
import org.example.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.example.footballmanager.newLogic.repository.MatchRepository;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.SeasonCompetitionRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.example.footballmanager.newLogic.sim.result.ProposalMatchOutcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * P0-CUPS-1 — a cup group table was never written, so every group was decided by seed order.
 *
 * <p><b>This is the defect the twelve green tests in {@code InternationalClubCupDrawTest} could not
 * see.</b> That class builds its played matches by hand — it saves a {@code Match} straight to the
 * repository — so it exercises the draw and never the write path. Meanwhile
 * {@code MatchType.countsForTable()} said no to every cup match, so {@code updateLeagueTable} was
 * never called for one, and all eight group tables of a Champions Cup sat at zero points.
 *
 * <p>{@code InternationalClubCupDraw.rankingWithin()} then ranked those eight rows by points, goal
 * difference and goals scored — all zero — and fell through to {@code LeagueTableOrder}'s last key,
 * <b>team id</b>. "The top two advance" therefore meant the two lowest database ids in the group. Not
 * a near miss: the group stage decided every tie before a ball was kicked, and the bracket built on
 * top of it was arithmetically perfect.
 *
 * <p>So these tests go through {@link SimMatchService#persist}, which is the only place the rule is
 * applied, and they assert the numbers a group table is read for.
 */
class CupGroupTableTest extends BaseTest {

    @Autowired private SimMatchService simMatchService;
    @Autowired private MatchRepository matchRepository;
    @Autowired private MatchFixtureRepository fixtureRepository;
    @Autowired private PlayerRepository playerRepository;
    @Autowired private TeamRepository teamRepository;
    @Autowired private CountryRepository countryRepository;
    @Autowired private CompetitionRepository competitionRepository;
    @Autowired private LineupRepository lineupRepository;
    @Autowired private CompetitionEntryRepository entryRepository;
    @Autowired private SeasonCompetitionRepository seasonCompetitionRepository;

    private static final int SEASON = 1;

    /** The one that matters: a group match writes points and goals, and both sides get a row. */
    @Test
    @Transactional
    @DisplayName("a cup group match writes its group's table")
    void aGroupMatchDecidesTheGroupTable() {
        Competition cup = continentalCup("Champions Cup");
        Team home = club("Group Home", cup);
        Team away = club("Group Away", cup);

        simMatchService.persist(groupFixture(home, away, cup, "A", 1), score(home, away, 3, 1), -1L);

        CompetitionEntry homeRow = rowOf(cup, home);
        CompetitionEntry awayRow = rowOf(cup, away);
        assertNotNull(homeRow, "the group table has no row for the home club");
        assertEquals(3, homeRow.getPoints(), "three points for a win");
        assertEquals(3, homeRow.getGoalsScored());
        assertEquals(1, homeRow.getGoalsConceded());
        assertEquals(1, homeRow.getWins());
        assertEquals(0, homeRow.getDraws());

        assertEquals(0, awayRow.getPoints());
        assertEquals(1, awayRow.getGoalsScored());
        assertEquals(3, awayRow.getGoalsConceded());
        assertEquals(1, awayRow.getLosses());
    }

    @Test
    @Transactional
    @DisplayName("a level cup group match is a point each, and not a win for anyone")
    void aDrawnGroupMatchIsAPointEach() {
        Competition cup = continentalCup("Champions Cup");
        Team home = club("Draw Home", cup);
        Team away = club("Draw Away", cup);

        simMatchService.persist(groupFixture(home, away, cup, "B", 2), score(home, away, 2, 2), -1L);

        assertEquals(1, rowOf(cup, home).getPoints());
        assertEquals(1, rowOf(cup, away).getPoints());
        assertEquals(1, rowOf(cup, home).getDraws());
        assertEquals(0, rowOf(cup, home).getWins());
        assertEquals(0, rowOf(cup, away).getWins());
    }

    /**
     * The half of the old rule that was right.
     *
     * <p>A domestic cup is pure knockout, so it must not grow a table. The old no-argument predicate
     * said no to every cup match and was wrong about the continental ones; this is the part of it that
     * was correct and has to stay correct.
     */
    @Test
    @Transactional
    @DisplayName("a domestic cup tie writes no table")
    void aDomesticCupTieDecidesNoTable() {
        Competition nationalCup = nationalCup("Kup Srbije");
        Team home = club("Cup Home", nationalCup);
        Team away = club("Cup Away", nationalCup);

        simMatchService.persist(knockoutFixture(home, away, nationalCup, 7, 10), score(home, away, 2, 0), -1L);

        assertNull(rowOf(nationalCup, home),
                "a domestic cup is pure knockout; it must not grow a table");
    }

    /**
     * Why the rule reads {@code groupCode} and not the round number.
     *
     * <p>Both fixtures here carry round 1, because a Champions Cup's first round <i>is</i> its first
     * group matchday and a domestic cup's first round is a knockout tie. Any rule built on the round
     * number gets one of those two wrong, silently, and the test fails only if that is the rule.
     */
    @Test
    @Transactional
    @DisplayName("round number cannot tell a group match from a knockout one")
    void theRoundNumberIsNotTheDiscriminator() {
        Competition cup = continentalCup("Masters Cup");
        Team inGroupHome = club("Round A Home", cup);
        Team inGroupAway = club("Round A Away", cup);
        Team outsideHome = club("Round B Home", cup);
        Team outsideAway = club("Round B Away", cup);

        MatchFixture inside = groupFixture(inGroupHome, inGroupAway, cup, "A", 1);
        MatchFixture outside = groupFixture(outsideHome, outsideAway, cup, "A", 1);
        outside.setGroupCode(null);

        assertEquals(inside.getRoundNumber(), outside.getRoundNumber(),
                "this test only means anything while both fixtures carry the same round");
        assertNotEquals(inside.getGroupCode(), outside.getGroupCode());

        simMatchService.persist(inside, score(inGroupHome, inGroupAway, 1, 0), -1L);
        simMatchService.persist(outside, score(outsideHome, outsideAway, 1, 0), -1L);

        assertEquals(3, rowOf(cup, inGroupHome).getPoints(), "the group match counted");
        assertNull(rowOf(cup, outsideHome), "the tie outside a group did not");
    }

/**
     * The two rows a bracket reads, through the comparator it actually uses.
     *
     * <p>Asserted through {@code LeagueTableOrder} because that is what
     * {@code InternationalClubCupDraw.rankingWithin()} delegates to. Without it this test would still
     * pass if the group ranked its own way, which is exactly what happened for as long as the table was
     * empty.
     *
     * <p><b>The scenario is built so that points cannot decide it.</b> All three clubs finish on three
     * points, so the whole order is the tie-break chain doing its work — and before this fix all three
     * sat on zero, which sent the order straight to team id.
     *
     * <pre>
     *   A 3-0 B, loses 1-2 to C -> 3 pts, 4 scored, 2 against, +2
     *   B loses 0-3 to A, 1-0 C  -> 3 pts, 1 scored, 3 against, -2
     *   C 2-1 A, loses 0-1 to B  -> 3 pts, 2 scored, 2 against,  0
     * </pre>
     */
    @Test
    @Transactional
    @DisplayName("a group's ranking falls through the tie-break, not through to team id")
    void aGroupRanksOnWhatItPlayed() {
        Competition cup = continentalCup("Challenge Cup");
        Team a = club("Rank A", cup);
        Team b = club("Rank B", cup);
        Team c = club("Rank C", cup);

        simMatchService.persist(groupFixture(a, b, cup, "A", 1), score(a, b, 3, 0), -1L);
        simMatchService.persist(groupFixture(b, c, cup, "A", 2), score(b, c, 1, 0), -1L);
        simMatchService.persist(groupFixture(c, a, cup, "A", 3), score(c, a, 2, 1), -1L);

        assertEquals(3, rowOf(cup, a).getPoints());
        assertEquals(3, rowOf(cup, b).getPoints());
        assertEquals(3, rowOf(cup, c).getPoints());
        assertEquals(2, rowOf(cup, a).getGoalsScored() - rowOf(cup, a).getGoalsConceded(), "A is +2");
        assertEquals(-2, rowOf(cup, b).getGoalsScored() - rowOf(cup, b).getGoalsConceded(), "B is -2");

        List<CompetitionEntry> ranked = org.example.footballmanager.newLogic.util.LeagueTableOrder.sort(
                entryRepository.findBySeasonCompetition(seasonCompetitionOf(cup)));

        assertEquals(List.of(a.getName(), c.getName(), b.getName()),
                ranked.stream().map(row -> row.getTeam().getName()).toList(),
                "level on points, so the goal difference decides, and it must not be team id");
    }

    // ---------- P0-CUPS-2: who has to be decided, and who may finish level ----------

    /**
     * A level cup group match stays level.
     *
     * <p>{@code isKnockoutTie()} used to be {@code type == CUP}, and its own comment named what would
     * happen the day the group stage went live: *"every level group match is settled by a shootout."* So
     * this asserts the two things that would be true then — {@code homePenaltyGoals} written, and a
     * homeGoals/awayGoals pair that no longer matches what was played.
     */
    @Test
    @Transactional
    @DisplayName("a level cup group match stays level, with no shootout")
    void aLevelGroupMatchIsNotSettledFromTheSpot() {
        Competition cup = continentalCup("Champions Cup");
        Team home = club("Shootout Home", cup);
        Team away = club("Shootout Away", cup);

        Long matchId = simMatchService.persist(
                groupFixture(home, away, cup, "C", 5), score(home, away, 0, 0), -1L);

        Match played = matchRepository.findById(matchId).orElseThrow();
        assertNull(played.getHomePenaltyGoals(),
                "a group match is scored as a draw; a shootout here decides the group on penalties");
        assertNull(played.getAwayPenaltyGoals());
        assertEquals(0, played.getHomeGoals());
        assertEquals(0, played.getAwayGoals());
    }

    /**
     * And the half that must not change: a level tie outside a group is still settled from the spot.
     *
     * <p>This is the behaviour the old rule got right, and it is load-bearing. The cup's winner lookup
     * returns null for a level tie with no shootout recorded, logs "no shootout recorded", and drops the
     * club — so without this a domestic cup cannot get past its first rounds.
     */
    @Test
    @Transactional
    @DisplayName("a level cup tie outside a group is still settled from the spot")
    void aLevelKnockoutTieIsStillSettledFromTheSpot() {
        Competition nationalCup = nationalCup("Kup Srbije");
        Team home = club("Spot Home", nationalCup);
        Team away = club("Spot Away", nationalCup);

        Long matchId = simMatchService.persist(
                knockoutFixture(home, away, nationalCup, 7, 10), score(home, away, 0, 0), -1L);

        Match played = matchRepository.findById(matchId).orElseThrow();
        assertNotNull(played.getHomePenaltyGoals(), "a knockout tie must have a winner");
        assertNotNull(played.getAwayPenaltyGoals());
        assertNotEquals(played.getHomePenaltyGoals(), played.getAwayPenaltyGoals(),
                "a shootout that finished level decided nothing");
        assertEquals(0, played.getHomeGoals(), "the shootout does not go into the scoreline");
        assertEquals(0, played.getAwayGoals());
    }

    /** A league draw is not a cup tie and has never been settled from the spot. */
    @Test
    @Transactional
    @DisplayName("a level league match is not settled from the spot")
    void aLevelLeagueMatchIsNotSettledFromTheSpot() {
        Competition league = league("Wiring League");
        Team home = club("League Home", league);
        Team away = club("League Away", league);

        Long matchId = simMatchService.persist(
                knockoutFixture(home, away, league, 4, 6), score(home, away, 1, 1), -1L);

        Match played = matchRepository.findById(matchId).orElseThrow();
        assertNull(played.getHomePenaltyGoals());
        assertNull(played.getAwayPenaltyGoals());
    }

    // ---------- helpers ----------

    private SeasonCompetition seasonCompetitionOf(Competition cup) {
        return seasonCompetitionRepository
                .findByCompetitionAndSeasonYear(cup, SEASON)
                .orElseThrow(() -> new AssertionError("no season row for " + cup.getName()));
    }

    /** The row, or null. Null is the expected answer for a tie that decides no table. */
    private CompetitionEntry rowOf(Competition cup, Team team) {
        return entryRepository
                .findBySeasonCompetitionAndTeam(
                        seasonCompetitionRepository.findByCompetitionAndSeasonYear(cup, SEASON).orElse(null), team)
                .orElse(null);
    }

    private Competition continentalCup(String name) {
        Competition c = new Competition();
        c.setName(name);
        c.setType(CompetitionType.CUP);
        c.setScope(CompetitionScope.INTERNATIONAL);
        c.setTeamType(CompetitionTeamType.CLUB);
        c.setTier(1);
        // No country: the entrants come from 48 of them, and that is the whole difference between a
        // domestic cup and a continental one.
        return competitionRepository.save(c);
    }

    private Competition nationalCup(String name) {
        Competition c = new Competition();
        c.setName(name);
        c.setType(CompetitionType.CUP);
        c.setScope(CompetitionScope.NATIONAL);
        c.setTeamType(CompetitionTeamType.CLUB);
        c.setTier(1);
        c.setCountry(countryRepository.save(country()));
        return competitionRepository.save(c);
    }

    private Competition league(String name) {
        Competition c = new Competition();
        c.setName(name);
        c.setType(CompetitionType.LEAGUE);
        c.setScope(CompetitionScope.NATIONAL);
        c.setTeamType(CompetitionTeamType.CLUB);
        c.setTier(1);
        c.setCountry(countryRepository.save(country()));
        return competitionRepository.save(c);
    }

    private static Country country() {
        Country c = new Country();
        c.setName("Cup Republic");
        c.setIsoCode("CUP");
        c.setReputation(60);
        return c;
    }

    private Team club(String name, Competition cup) {
        Team t = new Team();
        t.setName(name);
        t.setReputation(60.0);
        t.setBudget(10_000_000.0);
        t.setHumanControlled(false);
        t.setCompetition(cup);
        Team saved = teamRepository.save(t);
        List<Player> squad = playerRepository.saveAll(starters(name, saved));
        lineupRepository.save(lineupWith(saved, squad));
        return saved;
    }

    private MatchFixture groupFixture(Team home, Team away, Competition cup, String groupCode, int round) {
        MatchFixture f = knockoutFixture(home, away, cup, round, round);
        f.setDayNumber(1);
        f.setGroupCode(groupCode);
        return fixtureRepository.save(f);
    }

    private MatchFixture knockoutFixture(Team home, Team away, Competition cup, int round, int week) {
        MatchFixture f = new MatchFixture();
        f.setHomeTeam(home);
        f.setAwayTeam(away);
        f.setCompetition(cup);
        f.setSeasonYear(SEASON);
        f.setRoundNumber(round);
        f.setWeekNumber(week);
        f.setMatchDate(LocalDateTime.now().plusDays(1));
        return fixtureRepository.save(f);
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
        p.setRating(p.careerRating());
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

    /** A plain score with no per-player detail: this test is about the table, not the football. */
    private ProposalMatchOutcome score(Team home, Team away, int homeGoals, int awayGoals) {
        return new ProposalMatchOutcome(
                home.getName(), away.getName(), homeGoals, awayGoals, 3600L, 90,
                "4-4-2", "4-4-2",
                50.0, 50.0, 1.4, 1.1,
                teamOutcome(home.getName(), homeGoals), teamOutcome(away.getName(), awayGoals),
                new ArrayList<>(), List.of(), null, null, null);
    }

    private static ProposalMatchOutcome.TeamOutcome teamOutcome(String teamName, int goals) {
        return new ProposalMatchOutcome.TeamOutcome(
                teamName, goals, 12, 5, 330, 290, 8, 6, 4, 2, 2,
                goals > 0 ? 2 : 3, 5, 7, 9, 2, 1, 0, 0, 0,
                goals >= 2 ? 52.0 : 40.0, 6.4);
    }
}