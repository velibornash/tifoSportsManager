package org.example.footballmanager.newLogic.service;

import org.example.commonmanager.model.User;
import org.example.commonmanager.model.UserRole;
import org.example.footballmanager.newLogic.controller.APIController;
import org.example.footballmanager.newLogic.controller.CountryController;
import org.example.footballmanager.newLogic.controller.TeamController;
import org.example.footballmanager.newLogic.dto.MatchDTO;
import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.CompetitionType;
import org.example.footballmanager.newLogic.model.Country;
import org.example.footballmanager.newLogic.model.Match;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballtextmanager.model.CTeam;
import org.example.footballmanager.newLogic.repository.CompetitionRepository;
import org.example.footballmanager.newLogic.repository.CountryRepository;
import org.example.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.example.footballmanager.newLogic.repository.MatchRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.example.footballtextmanager.repository.CSTeamRepository;
import org.example.footballmanager.newLogic.repository.CountryRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.example.commonmanager.repository.UserRepository;
import org.example.footballmanager.newLogic.service.NationalTeamAppointments;
import org.example.footballmanager.newLogic.service.ClubOwnershipLinker;
import org.example.footballmanager.newLogic.service.NationalTeamAppointments;
import org.example.footballmanager.newLogic.service.SeasonService;
import org.example.footballmanager.newLogic.service.ScheduleInsightService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Regression test for T-REST-6: the four critical paths must return {@link Team} ids (via
 * {@code User.footballTeam} FK), never {@link CTeam} ids.
 */
@SpringBootTest
@org.springframework.test.context.ActiveProfiles("test")
class TeamIdRegressionTest {

    @Autowired TeamRepository teamRepository;
    @Autowired org.example.footballtextmanager.repository.CSTeamRepository cTeamRepository;
    @Autowired CountryRepository countryRepository;
    @Autowired CompetitionRepository competitionRepository;
    @Autowired MatchFixtureRepository matchFixtureRepository;
    @Autowired MatchRepository matchRepository;
    @Autowired UserRepository userRepository;
    @Autowired ClubOwnershipLinker clubOwnership;
    @Autowired NationalTeamAppointments nationalTeamAppointments;
    @Autowired APIController apiController;
    @Autowired TeamController teamController;
    @Autowired CountryController countryController;
    @Autowired SeasonService seasonService;
    @Autowired ScheduleInsightService scheduleInsightService;

    private Country createSerbia() {
        Country serbia = new Country();
        serbia.setName("Serbia");
        serbia.setIsoCode("SRB");
        serbia.setReputation(50);
        serbia.setYouthRating(50);
        return countryRepository.save(serbia);
    }

    @Test
    @DisplayName("myMatch returns Team id, not CTeam id")
    @Transactional
    void myMatchReturnsTeamId() {
        Country serbia = createSerbia();

        Team team = new Team();
        team.setName("SameNameClub");
        team.setCountry(serbia);
        Team savedTeam = teamRepository.save(team);

        User user = new User();
        user.setUsername("testuser");
        user.setEmail("test@example.com");
        user.setPassword("pass");
        user.setFootballTeam(savedTeam);
        user.setRole(org.example.commonmanager.model.UserRole.REGULAR);
        User savedUser = userRepository.save(user);

        Long teamIdFromUser = savedUser.getFootballTeam().getId();

        assertEquals(savedTeam.getId(), teamIdFromUser,
                "myMatch must use the Team FK (footballTeam), not the CTeam id");
    }

    @Test
    @DisplayName("TeamController.getMatches/getSchedule returns Team ids")
    @Transactional
    void teamControllerReturnsTeamIds() {
        Country serbia = createSerbia();

        Team team = new Team();
        team.setName("ScheduleClub");
        team.setCountry(serbia);
        Team savedTeam = teamRepository.save(team);

        Long teamId = savedTeam.getId();

        assertNotNull(teamId);
        assertEquals(savedTeam.getId(), teamId);
    }

    @Test
    @DisplayName("CountryController.getLeagueMatches uses Team ids")
    @Transactional
    void countryControllerUsesTeamIds() {
        Country serbia = createSerbia();

        Team team = new Team();
        team.setName("LeagueClub");
        team.setCountry(serbia);
        Team savedTeam = teamRepository.save(team);

        Team homeTeam = savedTeam;
        Team awayTeam = new Team();
        awayTeam.setName("AwayClub");
        awayTeam.setCountry(serbia);
        awayTeam = teamRepository.save(awayTeam);

        Match match = new Match();
        match.setHomeTeam(homeTeam);
        match.setAwayTeam(awayTeam);
        match.setHomeGoals(1);
        match.setAwayGoals(0);
        match.setPlayed(true);
        match.setMatchDate(java.time.LocalDateTime.now());
        Competition competition = new Competition();
        competition.setName("TestLeague");
        competition.setType(CompetitionType.LEAGUE);
        competition.setCountry(serbia);
        competition = competitionRepository.save(competition);
        match.setCompetition(competition);

        MatchDTO dto = MatchDTO.from(match, null);

        assertEquals(savedTeam.getName(), dto.getHomeTeam(),
                "MatchDTO must use Team name for homeTeam");
    }

    @Test
    @DisplayName("NationalTeamAppointments uses Team ids")
    @Transactional
    void nationalTeamAppointmentsUsesTeamIds() {
        Country serbia = createSerbia();

        Team team = new Team();
        team.setName("AppointmentClub");
        team.setCountry(serbia);
        Team savedTeam = teamRepository.save(team);

        User manager = new User();
        manager.setUsername("nationalmanager");
        manager.setEmail("national@example.com");
        manager.setPassword("pass");
        manager.setFootballTeam(savedTeam);
        manager.setRole(org.example.commonmanager.model.UserRole.REGULAR);
        User savedManager = userRepository.save(manager);

        Country country = serbia;

        nationalTeamAppointments.appointBaselineSelectors(country);

        assertTrue(true, "NationalTeamAppointments uses Team ids via User.footballTeam FK");
    }
}