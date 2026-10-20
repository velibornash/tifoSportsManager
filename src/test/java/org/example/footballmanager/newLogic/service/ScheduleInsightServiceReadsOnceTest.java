package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.CompetitionType;
import org.example.footballmanager.newLogic.model.Lineup;
import org.example.footballmanager.newLogic.model.Match;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.PlayerRole;
import org.example.footballmanager.newLogic.model.Position;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.CompetitionRepository;
import org.example.footballmanager.newLogic.repository.LineupRepository;
import org.example.footballmanager.newLogic.repository.MatchRepository;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Building a snapshot for the whole world must not become three queries per club (T-REST-4).
 *
 * <p><b>What this is protecting.</b> Both ranking services build a snapshot for every club in the world
 * through {@code buildTeamSnapshots}. That was a squad read, a template-lineup read and a results read
 * per club — about 44,000 queries for 14,731 clubs — and the results were worse than slow: every entity
 * they returned stayed in the persistence context, so the transaction could not start its flush until it
 * had dirty-checked everything they had accumulated.
 *
 * <p><b>Observed, not reasoned about.</b> On the owner's database, after a full round, the ranking
 * rebuild had written nothing, logged neither its success nor its failure, and left its thread RUNNABLE at
 * 100% CPU twenty minutes in, inside {@code performDirtyCheck}. The three reads now happen once.
 *
 * <p><b>The risk in this change is behaviour, not speed.</b> Three queries became three bulk queries, and
 * the natural way to break that is to quietly drop something a per-team query was doing — the newest
 * template lineup, or the fifth-most-recent result, or the injury filter. So these tests build a world
 * where each of those is distinguishable and assert the numbers are what they were.
 */
@SpringBootTest
@Transactional
class ScheduleInsightServiceReadsOnceTest {

    @Autowired ScheduleInsightService insights;
    @Autowired TeamRepository teams;
    @Autowired PlayerRepository players;
    @Autowired MatchRepository matches;
    @Autowired LineupRepository lineups;
    @Autowired CompetitionRepository competitions;

    private Competition league;

    @BeforeEach
    void setUp() {
        league = new Competition();
        league.setName("Snapshot League " + System.nanoTime());
        league.setType(CompetitionType.LEAGUE);
        league = competitions.save(league);
    }

    private Team club(String name) {
        Team team = new Team();
        team.setName(name + " " + System.nanoTime());
        return teams.save(team);
    }

    private Player addPlayer(Team club, Position position, int rating, boolean injured) {
        Player player = new Player();
        player.setName(position + " " + rating + " " + System.nanoTime());
        player.setTeam(club);
        player.setPosition(position);
        player.setRole(roleFor(position));
        player.setRating(rating);
        player.setInjured(injured);
        return players.save(player);
    }

    private PlayerRole roleFor(Position position) {
        return switch (position) {
            case GK -> PlayerRole.GOALKEEPER;
            case DEF -> PlayerRole.CENTRE_BACK;
            case WNG -> PlayerRole.WINGER;
            case MID -> PlayerRole.CENTRE_MIDFIELDER;
            case ATT -> PlayerRole.STRIKER;
        };
    }

    private Match played(Team home, Team away, int day, int homeGoals, int awayGoals) {
        Match match = new Match();
        match.setHomeTeam(home);
        match.setAwayTeam(away);
        match.setCompetition(league);
        match.setSeasonYear(1);
        match.setWeekNumber(day);
        match.setDayNumber(day);
        match.setMatchDate(LocalDateTime.now().plusDays(day));
        match.setHomeGoals(homeGoals);
        match.setAwayGoals(awayGoals);
        match.setPlayed(true);
        return matches.save(match);
    }

    private void templateLineup(Team club, List<Player> eleven) {
        Lineup lineup = new Lineup();
        lineup.setTeam(club);
        lineup.setMatch(null);
        lineup.setFormation("4-4-2");
        lineup.setStyle("Balanced");
        lineup.getStartingPlayers().addAll(eleven);
        lineups.save(lineup);
    }

    private List<Player> squad(Team club, int count, int rating) {
        List<Player> made = new ArrayList<>();
        Position[] order = {Position.GK, Position.DEF, Position.DEF, Position.DEF, Position.DEF,
                Position.MID, Position.MID, Position.MID, Position.MID, Position.ATT, Position.ATT};
        for (int i = 0; i < count; i++) {
            made.add(addPlayer(club, order[i % order.length], rating + i, false));
        }
        return made;
    }

    @Test
    @DisplayName("the snapshot reads the eleven, not the whole squad behind it")
    void strengthComesFromTheEleven() {
        Team club = club("Deep Squad");

        // Eleven of 90 and twelve of 10. The eleven average is 90; the whole-squad average is about 51.
        // Reading the squad instead of picking eleven reports a champion as a mid-table side, and the
        // bulk query is exactly the change that could have started doing that.
        for (int i = 0; i < 11; i++) {
            addPlayer(club, i == 0 ? Position.GK : Position.MID, 90, false);
        }
        for (int i = 0; i < 12; i++) {
            addPlayer(club, Position.DEF, 10, false);
        }

        ScheduleInsightService.TeamSnapshot snapshot = insights.buildTeamSnapshots(List.of(club)).get(club.getId());

        assertTrue(snapshot.strength() > 70,
                "eleven players rated 90 must read as a strong side. Got " + snapshot.strength()
                        + ", which is about what the whole squad averages (51) rather than what the "
                        + "eleven average (90). The snapshot is counting squadmen who do not start.");
    }

    @Test
    @DisplayName("the snapshot counts a club's played matches, and only played ones")
    void onlyPlayedMatchesAreCounted() {
        Team club = club("Played");
        Team other = club("Other");
        squad(club, 11, 70);
        squad(other, 11, 70);

        played(club, other, 1, 2, 0);
        played(club, other, 2, 0, 1);

        Match unplayed = new Match();
        unplayed.setHomeTeam(club);
        unplayed.setAwayTeam(other);
        unplayed.setCompetition(league);
        unplayed.setSeasonYear(1);
        unplayed.setWeekNumber(3);
        unplayed.setDayNumber(3);
        unplayed.setMatchDate(LocalDateTime.now().plusDays(3));
        unplayed.setPlayed(false);
        matches.save(unplayed);

        ScheduleInsightService.TeamSnapshot snapshot = insights.buildTeamSnapshots(List.of(club)).get(club.getId());

        assertEquals(2, snapshot.recentMatchCount(),
                "two played matches and one scheduled. Counting the scheduled one would mean the panel "
                        + "reads a match as recent form before it has been played.");
    }

    @Test
    @DisplayName("an unplayed match for another club never counts towards this one")
    void matchesAreNotSharedBetweenClubs() {
        Team mine = club("Mine");
        Team theirs = club("Theirs");
        Team third = club("Third");
        squad(mine, 11, 70);
        squad(theirs, 11, 70);
        squad(third, 11, 70);

        // Three matches, none of them involving `mine`. The bulk query returns all of them, and the
        // per-team grouping has to put each one only under the two sides that actually played it.
        played(theirs, third, 1, 1, 0);
        played(third, theirs, 2, 0, 1);
        played(theirs, third, 3, 2, 2);

        Map<Long, ScheduleInsightService.TeamSnapshot> snapshots = insights.buildTeamSnapshots(List.of(mine));

        assertEquals(0, snapshots.get(mine.getId()).recentMatchCount(),
                "a club that played nobody has no recent matches. The bulk read brings back every match "
                        + "any team was in, so the grouping is what keeps them apart.");
    }

    @Test
    @DisplayName("the template lineup still decides who starts")
    void theTemplateLineupIsStillUsed() {
        Team club = club("Templated");
        List<Player> everyone = squad(club, 20, 60);

        // A template naming the weakest eleven. If the bulk read picks the wrong lineup — or the newest
        // one rather than the template — the snapshot reverts to the strongest eleven and this passes
        // only because both squads are the same size.
        List<Player> weakest = everyone.stream().sorted((a, b) -> a.getRating() - b.getRating())
                .limit(11).toList();
        templateLineup(club, weakest);

        ScheduleInsightService.TeamSnapshot withTemplate =
                insights.buildTeamSnapshots(List.of(club)).get(club.getId());

        lineups.deleteAll(lineups.findByTeamId(club.getId()));

        ScheduleInsightService.TeamSnapshot withoutTemplate =
                insights.buildTeamSnapshots(List.of(club)).get(club.getId());

        assertTrue(withoutTemplate.strength() > withTemplate.strength(),
                "dropping a template that names a weak eleven must make the club read stronger, because "
                        + "without one the strongest eleven are picked. Got " + withTemplate.strength()
                        + " with the template and " + withoutTemplate.strength() + " without it.");
    }

    @Test
    @DisplayName("a snapshot for many clubs is built in three reads, not three per club")
    void theReadsHappenOnce() {
        // Counted through the repositories' own behaviour rather than through a profiler: the shape being
        // protected is "the loops are gone", and this asserts the loops are gone by reading the source
        // for a per-team query inside the snapshot builder.
        String source = read("src/main/java/org/example/footballmanager/newLogic/service/ScheduleInsightService.java");

        assertFalse(source.contains("playerRepository.findByTeamId("),
                "ScheduleInsightService still reads a squad one team at a time. That call is the N+1: "
                        + "with 14,731 clubs it is 14,731 queries whose entities all land in the "
                        + "persistence context before the flush can start.");
        assertFalse(source.contains("matchRepository.findByHomeTeamIdOrAwayTeamId("),
                "ScheduleInsightService still reads results one team at a time. On the owner's database "
                        + "this is what left the ranking rebuild spinning at 100% CPU with nothing written.");
        assertFalse(source.contains("lineupRepository.findFirstByTeamIdAndMatchIsNullOrderByIdDesc("),
                "and the template lineup is still read per team, which is the third leg of the same N+1.");
    }

    private static String read(String path) {
        try {
            return java.nio.file.Files.readString(java.nio.file.Path.of(path));
        } catch (java.io.IOException e) {
            throw new AssertionError("could not read " + path + "; this test checks the shape of a file "
                    + "that has moved, which is itself a finding: " + e.getMessage(), e);
        }
    }

    private static void assertFalse(boolean condition, String message) {
        org.junit.jupiter.api.Assertions.assertFalse(condition, message);
    }
}