package org.example.footballmanager.controller;

import org.example.footballmanager.model.Competition;
import org.example.footballmanager.model.Team;
import org.example.footballmanager.model.User;
import org.example.footballmanager.model.UserRole;
import org.example.footballmanager.repository.UserRepository;
import org.example.footballmanager.service.SeasonService;
import org.example.footballmanager.util.JwtUtil;
import org.example.footballmanager.util.teams.TeamFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserControllerTest {

    @Mock private UserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private TeamFactory teamFactory;
    @Mock private AuthenticationManager authenticationManager;
    @Mock private JwtUtil jwtUtil;
    @Mock private SeasonService seasonService;

    @InjectMocks private UserController userController;

    @Test
    void getCurrentUserReturnsTeamLeagueContext() {
        Competition competition = new Competition();
        competition.setId(2L);
        competition.setName("Prva Liga Srbije");
        competition.setTier(2);

        Team team = new Team();
        team.setId(10L);
        team.setName("OFK Omladinac");
        team.setCompetition(competition);

        User user = new User();
        user.setId(5L);
        user.setUsername("velman");
        user.setEmail("velman@test.rs");
        user.setRole(UserRole.REGULAR);
        user.setTeam(team);

        when(seasonService.getActiveSeasonYear()).thenReturn(2026);

        ResponseEntity<UserController.UserDTO> response = userController.getCurrentUser(user);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(10L, response.getBody().getTeamId());
        assertEquals("OFK Omladinac", response.getBody().getTeamName());
        assertEquals(2L, response.getBody().getCompetitionId());
        assertEquals("Prva Liga Srbije", response.getBody().getCompetitionName());
        assertEquals(2, response.getBody().getCompetitionTier());
        assertEquals(2026, response.getBody().getSeasonYear());
    }

    @Test
    void getCurrentUserReturnsUnauthorizedWhenMissingPrincipal() {
        ResponseEntity<UserController.UserDTO> response = userController.getCurrentUser(null);

        assertEquals(401, response.getStatusCode().value());
        assertNull(response.getBody());
    }
}