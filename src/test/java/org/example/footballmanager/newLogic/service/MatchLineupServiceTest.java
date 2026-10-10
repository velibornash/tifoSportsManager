package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.Lineup;
import org.example.footballmanager.newLogic.model.Match;
import org.example.footballmanager.newLogic.model.MatchFixture;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.PlayerRole;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.CompetitionRepository;
import org.example.footballmanager.newLogic.repository.LineupRepository;
import org.example.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.example.footballmanager.newLogic.repository.MatchRepository;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A club picks a team for one fixture (T0-BE-3).
 *
 * <p><b>What was missing.</b> {@code Lineup.match} has been a nullable {@code @ManyToOne} all along and
 * every reader asked for {@code match IS NULL} — the template — because that was the only row that could
 * exist. The schema was right and the writer was missing, so a manager could not pick a team for a game.
 *
 * <p><b>The assertion that matters.</b> A lineup saved for one fixture must beat the template for that
 * fixture and <em>only</em> that fixture. A test that only checks "the saved lineup comes back" would
 * pass against a reader that ignored the fixture entirely.
 */
@SpringBootTest
@org.springframework.test.context.ActiveProfiles("test")
class MatchLineupServiceTest {

    @Autowired MatchLineupService service;
    @Autowired LineupRepository lineups;
    @Autowired PlayerRepository players;
    @Autowired MatchRepository matches;
    @Autowired MatchFixtureRepository fixtures;
    @Autowired TeamRepository teams;
    @Autowired CompetitionRepository competitions;

    private Team club;
    private Player[] squad;
    private MatchFixture fixture;

    @BeforeEach
    void setUp() {
        club = new Team();
        club.setName("Lineup Club " + System.nanoTime());
        club = teams.save(club);

        Competition competition = new Competition();
        competition.setName("Lineup League " + System.nanoTime());
        competition.setType(org.example.footballmanager.newLogic.model.CompetitionType.LEAGUE);
        competition = competitions.save(competition);

        Team opponent = new Team();
        opponent.setName("Lineup Opponent " + System.nanoTime());
        opponent = teams.save(opponent);

        fixture = new MatchFixture();
        fixture.setHomeTeam(club);
        fixture.setAwayTeam(opponent);
        fixture.setCompetition(competition);
        fixture.setSeasonYear(1);
        fixture.setWeekNumber(1);
        fixture.setDayNumber(3);
        fixture.setMatchDate(LocalDateTime.now().plusDays(2));
        fixture = fixtures.save(fixture);

        squad = new Player[18];
        for (int i = 0; i < squad.length; i++) {
            Player player = new Player();
            player.setName("Player " + i);
            player.setTeam(club);
            player.setRole(i == 0 ? PlayerRole.GOALKEEPER : PlayerRole.CENTRE_MIDFIELDER);
            squad[i] = players.save(player);
        }
    }

    private List<Long> ids(Player... group) {
        List<Long> out = new ArrayList<>();
        for (Player player : group) {
            out.add(player.getId());
        }
        return out;
    }

    private Player[] eleven(int from) {
        Player[] team = new Player[11];
        System.arraycopy(squad, from, team, 0, 11);
        return team;
    }

    /** Links the fixture to the match it becomes, which is how a per-match lineup is found. */
    private Match playTheFixture() {
        Match match = new Match();
        match.setHomeTeam(club);
        match.setAwayTeam(fixture.getAwayTeam());
        match.setCompetition(fixture.getCompetition());
        match.setSeasonYear(1);
        match.setWeekNumber(1);
        match.setDayNumber(3);
        match.setMatchDate(fixture.getMatchDate());
        match.setHomeGoals(1);
        match.setAwayGoals(0);
        match.setPlayed(true);
        match = matches.save(match);
        fixture.setPlayedMatch(match);
        fixture.setPlayed(true);
        fixtures.save(fixture);
        return match;
    }

    /** The club's standing template — the row every reader used to find and the only one that could exist. */
    private Lineup saveTemplate() {
        Lineup template = new Lineup();
        template.setTeam(club);
        template.setStartingPlayers(List.of(eleven(0)));
        template.setStarterOrderFromIds(ids(eleven(0)));
        template.setFormation("4-4-2");
        return lineups.save(template);
    }

    /** The production decision, not a copy of it — this is what the simulation calls. */
    private Lineup resolveForThisMatch() {
        return service.resolve(club.getId(), fixture);
    }

    @Test
    @DisplayName("a lineup saved for the fixture beats the template for that fixture")
    void thePerMatchLineupWins() {
        Lineup template = saveTemplate();
        playTheFixture();

        Player[] matchEleven = eleven(2);
        service.save(club.getId(), fixture.getId(), ids(matchEleven), ids(squad[13], squad[14]),
                "4-3-3", "Attacking");

        Lineup chosen = resolveForThisMatch();

        assertNotEquals(template.getId(), chosen.getId(),
                "the template came back, so the per-match lineup never reached the match");
        assertEquals("4-3-3", chosen.getFormation(), "the fixture's own formation is the one played");
        assertEquals(ids(matchEleven), chosen.getOrderedStarterIds(),
                "and the order the manager picked is the order they keep");
    }

    @Test
    @DisplayName("the order the manager chose is kept, not re-sorted")
    void theChosenOrderIsKept() {
        playTheFixture();
        Player[] ordered = eleven(0);
        List<Long> reversed = new ArrayList<>(ids(ordered));
        java.util.Collections.reverse(reversed);

        Lineup saved = service.save(club.getId(), fixture.getId(), reversed, List.of(),
                "4-4-2", "Balanced");

        assertEquals(reversed, saved.getOrderedStarterIds(),
                "a formation is not an ordering; the manager's eleven is a sequence and the engine walks "
                        + "it in the order they wrote it");
        assertEquals("Player 10", saved.getOrderedStartingPlayers().get(0).getName(),
                "the last name picked must be the first name on the pitch");
    }

    @Test
    @DisplayName("a fixture with no lineup still gets the template")
    void theTemplateStillWinsWhenThereIsNoPerMatchOne() {
        Lineup template = saveTemplate();
        playTheFixture();

        Lineup chosen = resolveForThisMatch();

        assertEquals(template.getId(), chosen.getId(),
                "with nothing picked for this fixture the standing template must stand — otherwise every "
                        + "match silently auto-picks a team and the squad order a manager set means nothing");
    }

    @Test
    @DisplayName("a lineup saved for one fixture does not leak into the next")
    void itIsScopedToTheOneFixture() {
        Team other = new Team();
        other.setName("Lineup Other " + System.nanoTime());
        other = teams.save(other);

        MatchFixture next = new MatchFixture();
        next.setHomeTeam(club);
        next.setAwayTeam(other);
        next.setCompetition(fixture.getCompetition());
        next.setSeasonYear(1);
        next.setWeekNumber(2);
        next.setDayNumber(3);
        next.setMatchDate(LocalDateTime.now().plusDays(9));
        next = fixtures.save(next);

        playTheFixture();
        Match nextMatch = new Match();
        nextMatch.setHomeTeam(club);
        nextMatch.setAwayTeam(other);
        nextMatch.setSeasonYear(1);
        nextMatch.setWeekNumber(2);
        nextMatch.setDayNumber(3);
        nextMatch.setMatchDate(next.getMatchDate());
        nextMatch.setPlayed(true);
        nextMatch = matches.save(nextMatch);
        next.setPlayedMatch(nextMatch);
        next.setPlayed(true);
        fixtures.save(next);

        Lineup template = saveTemplate();
        service.save(club.getId(), fixture.getId(), ids(eleven(2)), List.of(), "4-3-3", "Attacking");

        Lineup nextFixtureLineup = lineups
                .findByTeamIdAndMatchId(club.getId(), nextMatch.getId()).orElse(null);

        assertEquals(null, nextFixtureLineup,
                "the previous fixture's XI leaked into the next match, which would be a manager picking a "
                        + "team for one game and the engine playing it in another");
        assertEquals(template.getId(),
                lineups.findFirstByTeamIdAndMatchIsNullOrderByIdDesc(club.getId()).orElseThrow().getId(),
                "and the template is untouched by a per-match save");
    }

    @Test
    @DisplayName("a squad that cannot field eleven is refused, with the number")
    void aShortSquadIsRefused() {
        playTheFixture();
        Player[] ten = new Player[10];
        System.arraycopy(squad, 0, ten, 0, 10);

        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                () -> service.save(club.getId(), fixture.getId(), ids(ten), List.of(), "4-4-2", ""));

        assertTrue(refusal.getMessage().contains("11"),
                "the manager is told how many he picked and how many he needs: " + refusal.getMessage());
    }

    @Test
    @DisplayName("another club's player cannot be picked, even by id")
    void anotherClubsPlayerIsRefused() {
        Team other = new Team();
        other.setName("Foreign Club " + System.nanoTime());
        other = teams.save(other);
        Player foreign = new Player();
        foreign.setName("Foreign Player");
        foreign.setTeam(other);
        foreign = players.save(foreign);

        playTheFixture();
        List<Long> withForeign = ids(eleven(0));
        withForeign.set(10, foreign.getId());

        Lineup saved = service.save(club.getId(), fixture.getId(), withForeign, List.of(), "4-4-2", "");

        assertEquals(10, saved.getStartingPlayers().size(),
                "a player of another club was stored on this club's lineup. The P2-7 defect was join-table "
                        + "rows returning regardless of club membership, and this is the second reader of "
                        + "that table — so it is checked here too rather than assumed");
    }

    @Test
    @DisplayName("a player cannot start and be on the bench")
    void startersAndBenchMustDisagree() {
        playTheFixture();
        Player[] eleven = eleven(0);
        List<Long> bench = List.of(eleven[3].getId());

        assertThrows(IllegalArgumentException.class,
                () -> service.save(club.getId(), fixture.getId(), ids(eleven), bench, "4-4-2", ""));
    }

    @Test
    @DisplayName("the goalkeeper rule is stated, not discovered at minute 60")
    void theGoalkeeperRuleIsSaidUpFront() {
        playTheFixture();
        Player[] eleven = eleven(0);   // squad[0] is the keeper

        // A real second keeper on the bench. Every other squad player is a midfielder, so passing an
        // outfielder here would be testing "there is no keeper on the bench" and calling it the other case.
        Player reserveKeeper = squad[13];
        reserveKeeper.setRole(PlayerRole.GOALKEEPER);
        players.save(reserveKeeper);

        List<String> withKeeperOnBench =
                service.warningsFor(club.getId(), fixture.getId(), ids(eleven), List.of(reserveKeeper.getId(), squad[1].getId()));
        assertTrue(withKeeperOnBench.stream().anyMatch(w -> w.contains("only be replaced by another goalkeeper")),
                "the owner specified that a keeper may be subbed only by another keeper, and "
                        + "SubstitutionService enforces it by matching GK status. The manager has to be told "
                        + "while he is looking at the screen: " + withKeeperOnBench);

        List<String> withNoKeeperOnBench =
                service.warningsFor(club.getId(), fixture.getId(), ids(eleven), List.of(squad[1].getId(), squad[2].getId()));
        assertTrue(withNoKeeperOnBench.stream().anyMatch(w -> w.contains("cannot be substituted")),
                "with no keeper on the bench his keeper can never be replaced, and that is worth saying "
                        + "before the match rather than after it");
    }

    @Test
    @DisplayName("an injured player is warned about, and the save still goes through")
    void injuriesWarnButDoNotBlock() {
        Player[] eleven = eleven(0);
        eleven[4].setInjured(true);
        eleven[4].setInjuryDaysRemaining(10);
        players.save(eleven[4]);
        playTheFixture();

        assertTrue(service.warningsFor(club.getId(), fixture.getId(), ids(eleven), List.of()).stream()
                        .anyMatch(w -> w.contains("injured")),
                "an injured player in the XI is a mistake the manager can see");

        Lineup saved = service.save(club.getId(), fixture.getId(), ids(eleven), List.of(), "4-4-2", "");
        assertEquals(11, saved.getStartingPlayers().size(),
                "a warning must not refuse the save: the rest of the team selection would be lost with it, "
                        + "and the engine has its own rules and will substitute him");
    }

    @Test
    @DisplayName("a club not playing in the fixture cannot pick a team for it")
    void onlyClubsInTheFixtureMayPick() {
        Team bystander = new Team();
        bystander.setName("Bystander " + System.nanoTime());
        final Team clubNotPlaying = teams.save(bystander);
        final List<Long> theirEleven = ids(eleven(0));

        assertThrows(IllegalArgumentException.class, () -> service.save(
                clubNotPlaying.getId(), fixture.getId(), theirEleven, List.of(), "4-4-2", ""));
    }
}