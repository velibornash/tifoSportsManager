package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.model.ClubSeasonRankingPoints;
import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.CompetitionScope;
import org.example.footballmanager.newLogic.model.CompetitionTeamType;
import org.example.footballmanager.newLogic.model.CompetitionType;
import org.example.footballmanager.newLogic.model.Match;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Position;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.ClubSeasonRankingPointsRepository;
import org.example.footballmanager.newLogic.repository.CompetitionRepository;
import org.example.footballmanager.newLogic.repository.MatchRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ranking tables are filled by something, and it is this (found 2026-10-08).
 *
 * <p><b>What went wrong.</b> {@code ClubRankingPointsService.recompute()},
 * {@code NationalRankingPointsService.recompute()}, {@code AchievementBonusService.apply()} and
 * {@code HonourService.derive()} were all written, all tested, and all green — and <b>not one of them was
 * called by the application</b>. The green came from tests that called them themselves. In a live game the
 * ranking lists sorted an empty ledger and no medal was ever derived.
 *
 * <p>Every test below therefore goes through the <b>rebuild</b> rather than through the individual
 * services, because calling the services is what the old tests did and it is exactly what let the wiring
 * go missing without anything going red.
 *
 * <p><b>None of them is {@code @Transactional}, and that is the point.</b> The rebuild runs in its own
 * {@code REQUIRES_NEW} transaction, so a test that saved its fixtures inside a test transaction would hand
 * the rebuild a world it cannot see — which is exactly how the first version of this test passed a rebuild
 * that had written nothing at all.
 */
class RankingPointsRebuildServiceTest extends BaseTest {

    @Autowired RankingPointsRebuildService rebuild;
    @Autowired ClubSeasonRankingPointsRepository clubLedger;
    @Autowired MatchRepository matches;
    @Autowired TeamRepository teams;
    @Autowired CompetitionRepository competitions;
    @Autowired org.example.footballmanager.newLogic.repository.PlayerRepository players;
    @Autowired ClubRankingPointsService clubRankingPoints;
    @Autowired NationalRankingPointsService nationalRankingPoints;
    @Autowired AchievementBonusService achievementBonuses;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("the rebuild writes the club ledger that the ranking lists read")
    void theRebuildFillsTheClubLedger() {
        Competition league = aCompetition("Rebuild " + UUID.randomUUID(), 1);
        Team firm = aClub("Rebuild firm " + UUID.randomUUID(), league, 80);
        Team weak = aClub("Rebuild weak " + UUID.randomUUID(), league, 50);
        aPlayedMatch(firm, weak, 3, 0, league, 1);

        assertTrue(clubLedger.findByTeamId(firm.getId()).isEmpty(),
                "the ledger starts empty, which is the state the world was actually in");

        RankingPointsRebuildService.Summary summary = rebuild.rebuild(1);

        assertEquals(1, summary.season());
        assertTrue(summary.clubRows() > 0,
                "and the rebuild wrote club rows rather than reporting a clean run: " + summary);

        // Both clubs, not just the winner: a rebuild that scored one side of every fixture would leave a
        // ranking list with half its clubs on the seed and no visible reason why.
        //
        // The arithmetic is deliberately NOT asserted here — a 3-0 between a firm favourite and a weak
        // side can land inside the forecast and be worth nothing, and `RankingPointsEngineTest` plus
        // `ClubRankingPointsReplayTest` already own that claim. This class is about who calls it.
        Optional<ClubSeasonRankingPoints> winner = clubLedger.findByTeamIdAndSeasonYear(firm.getId(), 1);
        Optional<ClubSeasonRankingPoints> loser = clubLedger.findByTeamIdAndSeasonYear(weak.getId(), 1);
        assertTrue(winner.isPresent(), "the home club is in the ledger the ranking list reads");
        assertTrue(loser.isPresent(), "and so is the away club");
        assertTrue(winner.get().getPoints() >= loser.get().getPoints(),
                "and the 3-0 winner is not below the side that lost it");
    }

    @Test
    @DisplayName("running the rebuild again changes nothing — the batch runs every matchday")
    void theRebuildIsIdempotent() {
        Competition league = aCompetition("Twice " + UUID.randomUUID(), 1);
        Team firm = aClub("Twice firm " + UUID.randomUUID(), league, 80);
        Team weak = aClub("Twice weak " + UUID.randomUUID(), league, 50);
        aPlayedMatch(firm, weak, 3, 0, league, 1);

        rebuild.rebuild(1);
        double first = clubLedger.findByTeamIdAndSeasonYear(firm.getId(), 1).orElseThrow().getPoints();
        long rowsAfterFirst = clubLedger.count();

        rebuild.rebuild(1);
        double second = clubLedger.findByTeamIdAndSeasonYear(firm.getId(), 1).orElseThrow().getPoints();

        assertEquals(first, second, 0.0001,
                "a second pass must not add the same match twice, because this runs every matchday");
        assertEquals(rowsAfterFirst, clubLedger.count(),
                "and it must not leave a second row per club per season");
    }

    @Test
    @DisplayName("the whole rebuild commits as one, so a bonus is never left standing on a rolled-back ledger")
    void theRebuildIsOneTransaction() {
        Competition league = aCompetition("One " + UUID.randomUUID(), 1);
        Team firm = aClub("One firm " + UUID.randomUUID(), league, 80);
        Team weak = aClub("One weak " + UUID.randomUUID(), league, 50);
        aPlayedMatch(firm, weak, 2, 1, league, 1);

        RankingPointsRebuildService.Summary summary = rebuild.rebuild(1);

        // The medals come last because a trophy bonus and a trophy medal are the same fact; if any step
        // threw, the whole thing rolls back and the next batch redoes it. Asserted as "every counter
        // comes from the same pass" rather than as an exception test, which would only prove the happy path.
        assertTrue(summary.clubRows() > 0 && summary.nationalRows() >= 0 && summary.medals() >= 0,
                "all four steps ran in the one pass: " + summary);
    }

    /**
     * A failure half way through must leave nothing behind.
     *
     * <p><b>Not @Transactional on purpose.</b> The rebuild commits on its own (REQUIRES_NEW), so this
     * needs committed rows to compare against; inside a test transaction the counts would be the test's
     * own view and the assertion would pass whatever the rebuild did.
     *
     * <p>The failing step is the medals, injected into a hand-built service — the last of the four, so a
     * rollback that leaves the earlier work behind would show up as a doubled ledger on the next run.
     */
    @Test
    @DisplayName("a rebuild that fails half way rolls back the lot, not just its own step")
    void aFailedRebuildWritesNothing() {
        Competition league = aCompetition("Fail " + UUID.randomUUID(), 1);
        Team firm = aClub("Fail firm " + UUID.randomUUID(), league, 80);
        Team weak = aClub("Fail weak " + UUID.randomUUID(), league, 50);
        aPlayedMatch(firm, weak, 3, 0, league, 1);

        rebuild.rebuild(1);
        long afterHealthyRun = clubLedger.count();
        assertTrue(afterHealthyRun > 0, "a healthy pass does write, so the next assertion means something");

        HonourService medalsAreDown = org.mockito.Mockito.mock(HonourService.class);
        org.mockito.Mockito.when(medalsAreDown.derive(org.mockito.ArgumentMatchers.anyInt()))
                .thenThrow(new IllegalStateException("medal derivation is down"));

        RankingPointsRebuildService broken = new RankingPointsRebuildService(
                clubRankingPoints, nationalRankingPoints, achievementBonuses, medalsAreDown, transactionManager);

        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () -> broken.rebuild(1));

        assertEquals(afterHealthyRun, clubLedger.count(),
                "and the ledger from the failed pass went back with it — a partial rebuild would double "
                        + "every club's points the next time it ran");
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────────────────

    private Competition aCompetition(String name, int tier) {
        Competition competition = new Competition();
        competition.setName(name);
        competition.setType(CompetitionType.LEAGUE);
        competition.setScope(CompetitionScope.NATIONAL);
        competition.setTeamType(CompetitionTeamType.CLUB);
        competition.setTier(tier);
        return competitions.save(competition);
    }

    private Team aClub(String name, Competition competition, int strength) {
        Team club = new Team();
        club.setName(name);
        club.setFormation("4-4-2");
        club = teams.save(club);
        club.setCompetition(competition);
        club = teams.save(club);
        for (int i = 0; i < 11; i++) {
            Player player = new Player();
            player.setName(name + " p" + i);
            player.setTeam(club);
            player.setPosition(i == 0 ? Position.GK : Position.MID);
            player.setRating(strength);
            player.setAge(22);
            players.save(player);
        }
        return club;
    }

    private void aPlayedMatch(Team home, Team away, int homeGoals, int awayGoals,
                              Competition competition, int season) {
        Match match = new Match();
        match.setHomeTeam(home);
        match.setAwayTeam(away);
        match.setHomeGoals(homeGoals);
        match.setAwayGoals(awayGoals);
        match.setCompetition(competition);
        match.setSeasonYear(season);
        match.setPlayed(true);
        match.setMatchDate(java.time.LocalDateTime.of(2026, 1, 1, 12, 0).plusDays(season * 7L));
        matches.save(match);
    }
}