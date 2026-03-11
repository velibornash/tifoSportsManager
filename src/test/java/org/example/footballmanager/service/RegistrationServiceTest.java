package org.example.footballmanager.service;

import org.example.footballmanager.dto.RegisterRequestDTO;
import org.example.footballmanager.model.CompetitionTeamType;
import org.example.footballmanager.model.RegistrationRequest;
import org.example.footballmanager.model.RegistrationRequestStatus;
import org.example.footballmanager.model.Team;
import org.example.footballmanager.model.User;
import org.example.footballmanager.model.UserRole;
import org.example.footballmanager.repository.RegistrationRequestRepository;
import org.example.footballmanager.repository.TeamRepository;
import org.example.footballmanager.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RegistrationServiceTest {

    @Mock private RegistrationRequestRepository registrationRequestRepository;
    @Mock private UserRepository userRepository;
    @Mock private TeamRepository teamRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private CommunityMessageService communityMessageService;

    @InjectMocks private RegistrationService registrationService;

    @Test
    void createPendingRequestAssignsFirstFreeAiTeam() {
        Team ownerTeam = new Team();
        ownerTeam.setId(1L);
        ownerTeam.setName("OFK Omladinac");
        ownerTeam.setType(CompetitionTeamType.CLUB);
        ownerTeam.setHumanControlled(true);

        Team freeTeam = new Team();
        freeTeam.setId(2L);
        freeTeam.setName("FK Sloboda");
        freeTeam.setType(CompetitionTeamType.CLUB);
        freeTeam.setHumanControlled(false);

        RegisterRequestDTO dto = new RegisterRequestDTO();
        dto.setUsername("noviuser");
        dto.setEmail("NOVI@example.com");
        dto.setPassword("A12345!");

        when(teamRepository.findAll(any(org.springframework.data.domain.Sort.class))).thenReturn(List.of(ownerTeam, freeTeam));
        when(userRepository.existsByUsernameIgnoreCase("noviuser")).thenReturn(false);
        when(userRepository.existsByEmailIgnoreCase("novi@example.com")).thenReturn(false);
        when(userRepository.existsByTeam(freeTeam)).thenReturn(false);
        when(passwordEncoder.encode("A12345!")).thenReturn("encoded-password");
        when(registrationRequestRepository.save(any(RegistrationRequest.class))).thenAnswer(invocation -> invocation.getArgument(0));

        RegistrationRequest request = registrationService.createPendingRequest(dto);

        assertEquals("noviuser", request.getUsername());
        assertEquals("novi@example.com", request.getEmail());
        assertEquals("encoded-password", request.getPasswordHash());
        assertEquals(freeTeam, request.getTeam());
        assertEquals(RegistrationRequestStatus.PENDING, request.getStatus());
        verify(communityMessageService).postRegistrationSubmitted(request);
    }

    @Test
    void createPendingRequestStillAssignsFreeAiTeamWhenOldPendingReservationExists() {
        Team ownerTeam = new Team();
        ownerTeam.setId(1L);
        ownerTeam.setName("OFK Omladinac");
        ownerTeam.setType(CompetitionTeamType.CLUB);
        ownerTeam.setHumanControlled(true);

        Team freeTeam = new Team();
        freeTeam.setId(2L);
        freeTeam.setName("FK Sloboda");
        freeTeam.setType(CompetitionTeamType.CLUB);
        freeTeam.setHumanControlled(false);

        RegisterRequestDTO dto = new RegisterRequestDTO();
        dto.setUsername("noviuser");
        dto.setEmail("NOVI@example.com");
        dto.setPassword("A12345!");

        when(teamRepository.findAll(any(org.springframework.data.domain.Sort.class))).thenReturn(List.of(ownerTeam, freeTeam));
        when(userRepository.existsByUsernameIgnoreCase("noviuser")).thenReturn(false);
        when(userRepository.existsByEmailIgnoreCase("novi@example.com")).thenReturn(false);
        when(userRepository.existsByTeam(freeTeam)).thenReturn(false);
        lenient().when(registrationRequestRepository.existsByTeamAndStatus(freeTeam, RegistrationRequestStatus.PENDING)).thenReturn(true);
        when(passwordEncoder.encode("A12345!")).thenReturn("encoded-password");
        when(registrationRequestRepository.save(any(RegistrationRequest.class))).thenAnswer(invocation -> invocation.getArgument(0));

        RegistrationRequest request = registrationService.createPendingRequest(dto);

        assertEquals(freeTeam, request.getTeam());
        assertEquals(RegistrationRequestStatus.PENDING, request.getStatus());
    }

    @Test
    void createPendingRequestTreatsNullTypeTeamsAsRegistrableClubs() {
        Team ownerTeam = new Team();
        ownerTeam.setId(1L);
        ownerTeam.setName("OFK Omladinac");
        ownerTeam.setType(CompetitionTeamType.CLUB);
        ownerTeam.setHumanControlled(true);

        Team legacyClub = new Team();
        legacyClub.setId(2L);
        legacyClub.setName("FK Legacy");
        legacyClub.setType(null);
        legacyClub.setHumanControlled(false);

        RegisterRequestDTO dto = new RegisterRequestDTO();
        dto.setUsername("noviuser");
        dto.setEmail("NOVI@example.com");
        dto.setPassword("A12345!");

        when(teamRepository.findAll(any(org.springframework.data.domain.Sort.class))).thenReturn(List.of(ownerTeam, legacyClub));
        when(userRepository.existsByUsernameIgnoreCase("noviuser")).thenReturn(false);
        when(userRepository.existsByEmailIgnoreCase("novi@example.com")).thenReturn(false);
        when(userRepository.existsByTeam(legacyClub)).thenReturn(false);
        when(passwordEncoder.encode("A12345!")).thenReturn("encoded-password");
        when(registrationRequestRepository.save(any(RegistrationRequest.class))).thenAnswer(invocation -> invocation.getArgument(0));

        RegistrationRequest request = registrationService.createPendingRequest(dto);

        assertEquals(legacyClub, request.getTeam());
        assertEquals(RegistrationRequestStatus.PENDING, request.getStatus());
    }

    @Test
    void createPendingRequestFailsWhenNoFreeAiTeamsExist() {
        Team takenTeam = new Team();
        takenTeam.setId(2L);
        takenTeam.setName("FK Sloboda");
        takenTeam.setType(CompetitionTeamType.CLUB);
        takenTeam.setHumanControlled(false);

        RegisterRequestDTO dto = new RegisterRequestDTO();
        dto.setUsername("noviuser");
        dto.setEmail("novi@example.com");
        dto.setPassword("A12345!");

        when(teamRepository.findAll(any(org.springframework.data.domain.Sort.class))).thenReturn(List.of(takenTeam));
        when(userRepository.existsByUsernameIgnoreCase("noviuser")).thenReturn(false);
        when(userRepository.existsByEmailIgnoreCase("novi@example.com")).thenReturn(false);
        when(userRepository.existsByTeam(takenTeam)).thenReturn(true);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> registrationService.createPendingRequest(dto));

        assertTrue(ex.getMessage().contains("nema slobodnih AI timova"));
    }

    @Test
    void approveRequestCreatesUserAndMarksTeamHumanControlled() {
        Team team = new Team();
        team.setId(7L);
        team.setName("FK Sloboda");
        team.setHumanControlled(false);

        RegistrationRequest request = new RegistrationRequest();
        request.setId(5L);
        request.setUsername("noviuser");
        request.setEmail("novi@example.com");
        request.setPasswordHash("encoded-password");
        request.setTeam(team);
        request.setStatus(RegistrationRequestStatus.PENDING);

        User reviewer = new User();
        reviewer.setId(1L);
        reviewer.setUsername("owner@example.com");
        reviewer.setRole(UserRole.OWNER);

        when(registrationRequestRepository.findById(5L)).thenReturn(Optional.of(request));
        when(userRepository.findById(1L)).thenReturn(Optional.of(reviewer));
        when(userRepository.existsByUsernameIgnoreCase("noviuser")).thenReturn(false);
        when(userRepository.existsByEmailIgnoreCase("novi@example.com")).thenReturn(false);
        when(userRepository.existsByTeam(team)).thenReturn(false);
        when(registrationRequestRepository.save(any(RegistrationRequest.class))).thenAnswer(invocation -> invocation.getArgument(0));

        registrationService.approveRequest(5L, reviewer, "Sve ok");

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        User savedUser = userCaptor.getValue();
        assertEquals("noviuser", savedUser.getUsername());
        assertEquals("novi@example.com", savedUser.getEmail());
        assertEquals(team, savedUser.getTeam());
        assertEquals(UserRole.REGULAR, savedUser.getRole());
        assertTrue(team.isHumanControlled());
        assertEquals(RegistrationRequestStatus.APPROVED, request.getStatus());
        assertEquals("owner@example.com", request.getReviewerUsername());
        assertEquals("Sve ok", request.getReviewNote());
        verify(communityMessageService).postRegistrationApproved(request, reviewer);
        verify(communityMessageService).postFakeEmailNotification(request, true);
    }

    @Test
    void rejectRequestKeepsTeamAsAiControlled() {
        Team team = new Team();
        team.setId(7L);
        team.setName("FK Sloboda");
        team.setHumanControlled(false);

        RegistrationRequest request = new RegistrationRequest();
        request.setId(5L);
        request.setUsername("noviuser");
        request.setEmail("novi@example.com");
        request.setTeam(team);
        request.setStatus(RegistrationRequestStatus.PENDING);

        User reviewer = new User();
        reviewer.setId(1L);
        reviewer.setUsername("owner@example.com");
        reviewer.setRole(UserRole.OWNER);

        when(registrationRequestRepository.findById(5L)).thenReturn(Optional.of(request));
        when(userRepository.findById(1L)).thenReturn(Optional.of(reviewer));
        when(registrationRequestRepository.save(any(RegistrationRequest.class))).thenAnswer(invocation -> invocation.getArgument(0));

        registrationService.rejectRequest(5L, reviewer, "Nema mesta");

        assertEquals(RegistrationRequestStatus.REJECTED, request.getStatus());
        assertFalse(team.isHumanControlled());
        verify(communityMessageService).postRegistrationRejected(request, reviewer, "Nema mesta");
        verify(communityMessageService).postFakeEmailNotification(request, false);
    }
}