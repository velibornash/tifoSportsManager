package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.model.ClubHonour;
import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.CompetitionEntry;
import org.example.footballmanager.newLogic.model.CompetitionScope;
import org.example.footballmanager.newLogic.model.CompetitionTeamType;
import org.example.footballmanager.newLogic.model.CompetitionType;
import org.example.footballmanager.newLogic.model.Match;
import org.example.footballmanager.newLogic.model.MatchFixture;
import org.example.footballmanager.newLogic.model.NationalStage;
import org.example.footballmanager.newLogic.model.NationalTournamentSchedule;
import org.example.footballmanager.newLogic.model.SeasonCompetition;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.ClubHonourRepository;
import org.example.footballmanager.newLogic.repository.CompetitionEntryRepository;
import org.example.footballmanager.newLogic.repository.CompetitionRepository;
import org.example.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.example.footballmanager.newLogic.repository.MatchRepository;
import org.example.footballmanager.newLogic.repository.SeasonCompetitionRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HonourServiceTest extends BaseTest {

    @Autowired HonourService honours;
    @Autowired ClubHonourRepository honourRows;
    @Autowired CompetitionRepository competitions;
    @Autowired SeasonCompetitionRepository seasons;
    @Autowired CompetitionEntryRepository entries;
    @Autowired MatchFixtureRepository fixtures;
    @Autowired MatchRepository matches;
    @Autowired TeamRepository teams;

    @Test
    @Transactional
    @DisplayName("a league gives gold, silver and bronze from the final table")
    void leagueTableDecidesTheMedals() {
        Competition league = aLeague();
        SeasonCompetition sc = aSeasonCompetition(league, 1);
        Team first = aTeam(), second = aTeam(), third = aTeam(), fourth = aTeam();
        join(sc, first, 1); join(sc, second, 2); join(sc, third, 3); join(sc, fourth, 4);

        int written = honours.derive(1);

        assertEquals(3, written, "three medals for three medal positions");
        assertMedal(first, league.getName(), 1, ClubHonour.Medal.GOLD);
        assertMedal(second, league.getName(), 1, ClubHonour.Medal.SILVER);
        assertMedal(third, league.getName(), 1, ClubHonour.Medal.BRONZE);
    }

    @Test
    @Transactional
    @DisplayName("a cup with a final and a third-place match awards all three medals")
    void aCupWithThirdPlaceAwardsThree() {
        Competition wc = aTournament();
        aSeasonCompetition(wc, 1);
        Team winner = aTeam(), loser = aTeam(), thirdPlace = aTeam(), out = aTeam();

        aFixture(wc, winner, out, NationalTournamentSchedule.ROUND_SEMI_FINAL, 1, 0);
        aFixture(wc, thirdPlace, out, NationalTournamentSchedule.ROUND_SEMI_FINAL, 2, 0);
        aFixture(wc, winner, loser, NationalTournamentSchedule.ROUND_FINAL, 3, 1);
        aFixture(wc, thirdPlace, out, NationalTournamentSchedule.ROUND_THIRD_PLACE, 2, 1);

        int written = honours.derive(1);

        assertEquals(3, written);
        assertMedal(winner, wc.getName(), 1, ClubHonour.Medal.GOLD);
        assertMedal(loser, wc.getName(), 1, ClubHonour.Medal.SILVER);
        assertMedal(thirdPlace, wc.getName(), 1, ClubHonour.Medal.BRONZE);
    }

    @Test
    @Transactional
    @DisplayName("a cup without a third-place match awards no bronze")
    void noThirdPlaceNoBronze() {
        Competition cup = new Competition();
        cup.setName("Cup " + UUID.randomUUID());
        cup.setType(CompetitionType.CUP);
        cup.setScope(CompetitionScope.NATIONAL);
        cup.setTeamType(CompetitionTeamType.CLUB);
        cup = competitions.save(cup);
        aSeasonCompetition(cup, 1);

        Team winner = aTeam(), loser = aTeam();
        aFixture(cup, winner, loser, 2, 2, 1);

        int written = honours.derive(1);

        assertEquals(2, written, "gold and silver only, because there is no third-place match to win");
        assertMedal(winner, cup.getName(), 1, ClubHonour.Medal.GOLD);
        assertMedal(loser, cup.getName(), 1, ClubHonour.Medal.SILVER);
    }

    @Test
    @Transactional
    @DisplayName("re-deriving one season never doubles or deletes another's medals")
    void rederivingOneSeasonLeavesTheOtherAlone() {
        Competition league = aLeague();
        SeasonCompetition s1 = aSeasonCompetition(league, 1);
        Team first = aTeam();
        join(s1, first, 1);
        SeasonCompetition s2 = aSeasonCompetition(league, 2);
        join(s2, first, 1);

        honours.derive(1);
        honours.derive(2);
        honours.derive(2);

        assertEquals(1, honourRows.findByTeamIdOrderBySeasonYearDescMedal(first.getId()).stream()
                .filter(h -> h.getSeasonYear() == 1).count(), "one gold for season 1");
        assertEquals(1, honourRows.findByTeamIdOrderBySeasonYearDescMedal(first.getId()).stream()
                .filter(h -> h.getSeasonYear() == 2).count(), "and one for season 2, not two");
    }

    private Competition aLeague() {
        Competition c = new Competition();
        c.setName("League " + UUID.randomUUID());
        c.setType(CompetitionType.LEAGUE);
        c.setScope(CompetitionScope.NATIONAL);
        c.setTeamType(CompetitionTeamType.CLUB);
        c.setTier(1);
        return competitions.save(c);
    }

    private Competition aTournament() {
        Competition c = new Competition();
        c.setName("WC " + UUID.randomUUID());
        c.setType(CompetitionType.TOURNAMENT);
        c.setScope(CompetitionScope.INTERNATIONAL);
        c.setTeamType(CompetitionTeamType.NATIONAL_TEAM);
        c.setNationalStage(NationalStage.WORLD_CUP);
        return competitions.save(c);
    }

    private SeasonCompetition aSeasonCompetition(Competition c, int year) {
        SeasonCompetition sc = new SeasonCompetition();
        sc.setCompetition(c);
        sc.setSeasonYear(year);
        sc.setFinished(true);
        return seasons.save(sc);
    }

    private Team aTeam() {
        Team t = new Team();
        t.setName("T " + UUID.randomUUID().toString().substring(0, 6));
        t.setFormation("4-3-3");
        return teams.save(t);
    }

    private void join(SeasonCompetition sc, Team team, int position) {
        CompetitionEntry e = new CompetitionEntry();
        e.setSeasonCompetition(sc);
        e.setTeam(team);
        e.setPosition(position);
        entries.save(e);
    }

    private void aFixture(Competition c, Team home, Team away, int round, int hg, int ag) {
        Match m = new Match();
        m.setHomeTeam(home);
        m.setAwayTeam(away);
        m.setHomeGoals(hg);
        m.setAwayGoals(ag);
        m.setCompetition(c);
        m.setSeasonYear(1);
        m.setPlayed(true);
        m.setMatchDate(java.time.LocalDateTime.of(2026, 1, 1, 18, 0).plusDays(round * 7L));
        m = matches.save(m);

        MatchFixture f = new MatchFixture();
        f.setCompetition(c);
        f.setHomeTeam(home);
        f.setAwayTeam(away);
        f.setRoundNumber(round);
        f.setSeasonYear(1);
        f.setPlayed(true);
        f.setPlayedMatch(m);
        f.setMatchDate(m.getMatchDate());
        fixtures.save(f);
    }

    private void assertMedal(Team team, String competitionName, int season, ClubHonour.Medal medal) {
        boolean found = honourRows.findByTeamIdOrderBySeasonYearDescMedal(team.getId()).stream()
                .anyMatch(h -> h.getCompetitionName().equals(competitionName)
                        && h.getSeasonYear() == season && h.getMedal() == medal);
        assertTrue(found, team.getName() + " should have " + medal + " in " + competitionName + " season " + season);
    }
}
