package org.example.footballmanager.controller;

import org.example.footballmanager.model.Competition;
import org.example.footballmanager.model.CompetitionEntry;
import org.example.footballmanager.model.Country;
import org.example.footballmanager.model.RegistrationRequest;
import org.example.footballmanager.model.RegistrationRequestStatus;
import org.example.footballmanager.model.SeasonCompetition;
import org.example.footballmanager.model.Team;
import org.example.footballmanager.model.User;
import org.example.footballmanager.model.UserRole;
import org.example.footballmanager.repository.CompetitionEntryRepository;
import org.example.footballmanager.repository.UserRepository;
import org.example.footballmanager.service.RegistrationService;
import org.example.footballmanager.service.SeasonService;
import org.example.footballmanager.util.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserControllerTest {

    @Mock private UserRepository userRepository;
    @Mock private AuthenticationManager authenticationManager;
    @Mock private JwtUtil jwtUtil;
    @Mock private SeasonService seasonService;
    @Mock private CompetitionEntryRepository competitionEntryRepository;
    @Mock private RegistrationService registrationService;

    @InjectMocks private UserController userController;

    @BeforeEach
    void setUp() {
        userController = new UserController(
                userRepository,
                authenticationManager,
                jwtUtil,
                seasonService,
                competitionEntryRepository,
                registrationService
        );
    }

    @Test
    void registerCreatesPendingRequestInsteadOfJwt() {
        Team team = new Team();
        team.setId(10L);
        team.setName("FK Sloboda");

        RegistrationRequest request = new RegistrationRequest();
        request.setId(4L);
        request.setTeam(team);
        request.setStatus(RegistrationRequestStatus.PENDING);

        org.example.footballmanager.dto.RegisterRequestDTO dto = new org.example.footballmanager.dto.RegisterRequestDTO();
        dto.setUsername("noviuser");
        dto.setEmail("novi@example.com");
        dto.setPassword("A12345!");

        when(registrationService.createPendingRequest(any())).thenReturn(request);

        ResponseEntity<org.example.footballmanager.dto.RegisterResponseDTO> response = userController.register(dto);

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertEquals("PENDING_APPROVAL", response.getBody().getStatus());
        assertEquals("FK Sloboda", response.getBody().getReservedTeamName());
    }

    @Test
    void loginUsesUsernameOrEmailLookup() {
        User user = new User();
        user.setUsername("manager");
        user.setEmail("manager@example.com");
        user.setRole(UserRole.REGULAR);

        org.example.footballmanager.dto.LoginRequestDTO dto = new org.example.footballmanager.dto.LoginRequestDTO();
        dto.setUsername("manager@example.com");
        dto.setPassword("A12345!");

        when(userRepository.findByUsernameOrEmail("manager@example.com")).thenReturn(Optional.of(user));
        when(jwtUtil.generateToken(user)).thenReturn("jwt-token");

        ResponseEntity<org.example.footballmanager.dto.JwtResponseDTO> response = userController.login(dto);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("jwt-token", response.getBody().getToken());
        verify(authenticationManager).authenticate(any());
    }

    @Test
    void getCurrentUserReturnsTeamLeagueContext() {
        Country country = new Country();
        country.setName("Serbia");
        country.setIsoCode("SRB");

        Competition competition = new Competition();
        competition.setId(2L);
        competition.setName("Prva Liga Srbije");
        competition.setTier(2);
        competition.setCountry(country);

        Team team = new Team();
        team.setId(10L);
        team.setName("OFK Omladinac");
        team.setCompetition(competition);
        team.setCountry(country);
        team.setHumanControlled(true);

        User user = new User();
        user.setId(5L);
        user.setUsername("velman");
        user.setEmail("velman@test.rs");
        user.setRole(UserRole.REGULAR);
        user.setTeam(team);

	        when(userRepository.findById(5L)).thenReturn(Optional.of(user));
        when(seasonService.getActiveSeasonYear()).thenReturn(2026);

        ResponseEntity<UserController.UserDTO> response = userController.getCurrentUser(user);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(10L, response.getBody().getTeamId());
        assertEquals("OFK Omladinac", response.getBody().getTeamName());
        assertEquals(2L, response.getBody().getCompetitionId());
        assertEquals("Prva Liga Srbije", response.getBody().getCompetitionName());
        assertEquals(2, response.getBody().getCompetitionTier());
        assertEquals("Serbia", response.getBody().getCountryName());
        assertEquals("SRB", response.getBody().getCountryIsoCode());
        assertEquals(2026, response.getBody().getSeasonYear());
        assertTrue(response.getBody().getTeamHumanControlled());
    }

    @Test
    void getCurrentUserFallsBackToActiveSeasonCompetitionEntry() {
        Country country = new Country();
        country.setName("Serbia");
        country.setIsoCode("SRB");

        Competition competition = new Competition();
        competition.setId(3L);
        competition.setName("Srpska Liga Beograd");
        competition.setTier(3);
        competition.setCountry(country);

        SeasonCompetition seasonCompetition = new SeasonCompetition();
        seasonCompetition.setSeasonYear(2026);
        seasonCompetition.setCompetition(competition);

        CompetitionEntry entry = new CompetitionEntry();
        entry.setSeasonCompetition(seasonCompetition);

        Team team = new Team();
        team.setId(10L);
        team.setName("OFK Omladinac");
        team.setCompetition(null);
        team.setCountry(null);
        team.setHumanControlled(true);

        User user = new User();
        user.setId(5L);
        user.setUsername("velman");
        user.setEmail("velman@test.rs");
        user.setRole(UserRole.REGULAR);
        user.setTeam(team);

        when(userRepository.findById(5L)).thenReturn(Optional.of(user));
        when(seasonService.getActiveSeasonYear()).thenReturn(2026);
        when(competitionEntryRepository.findFirstByTeamAndSeasonCompetitionSeasonYearOrderByIdDesc(team, 2026))
                .thenReturn(Optional.of(entry));

        ResponseEntity<UserController.UserDTO> response = userController.getCurrentUser(user);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(3L, response.getBody().getCompetitionId());
        assertEquals("Srpska Liga Beograd", response.getBody().getCompetitionName());
        assertEquals(3, response.getBody().getCompetitionTier());
        assertEquals("Serbia", response.getBody().getCountryName());
        assertEquals("SRB", response.getBody().getCountryIsoCode());
    }

    @Test
    void getCurrentUserReturnsUnauthorizedWhenMissingPrincipal() {
        ResponseEntity<UserController.UserDTO> response = userController.getCurrentUser(null);

        assertEquals(401, response.getStatusCode().value());
        assertNull(response.getBody());
    }
}