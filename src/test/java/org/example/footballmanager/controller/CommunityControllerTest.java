package org.example.footballmanager.controller;

import org.example.footballmanager.dto.CommunityChatMessageDTO;
import org.example.footballmanager.dto.CommunityPostRequestDTO;
import org.example.footballmanager.model.CommunityMessage;
import org.example.footballmanager.model.CommunityMessageType;
import org.example.footballmanager.model.RegistrationRequest;
import org.example.footballmanager.model.RegistrationRequestStatus;
import org.example.footballmanager.model.Team;
import org.example.footballmanager.model.User;
import org.example.footballmanager.model.UserRole;
import org.example.footballmanager.repository.UserRepository;
import org.example.footballmanager.service.CommunityMessageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CommunityControllerTest {

    @Mock private CommunityMessageService communityMessageService;
    @Mock private UserRepository userRepository;

    @InjectMocks private CommunityController communityController;

    @Test
    void getChatMessagesShowsSharedAndRelevantPrivateMessagesOnly() {
        User viewer = user(1L, "viewer", UserRole.REGULAR, null);
        User author = user(2L, "sender", UserRole.REGULAR, team(20L, "FK Sender"));
        User other = user(3L, "other", UserRole.REGULAR, null);

        RegistrationRequest pendingRequest = new RegistrationRequest();
        pendingRequest.setStatus(RegistrationRequestStatus.PENDING);

        CommunityMessage shared = message(10L, author, "sender", "Shared", CommunityMessageType.USER);
        CommunityMessage privateForViewer = message(11L, author, "sender", "Private hello", CommunityMessageType.USER);
        privateForViewer.setRecipientUser(viewer);

        CommunityMessage privateForOther = message(12L, author, "sender", "Hidden", CommunityMessageType.USER);
        privateForOther.setRecipientUser(other);

        CommunityMessage pendingAdmin = message(13L, null, "Registration Desk", "Pending", CommunityMessageType.SERVICE);
        pendingAdmin.setRegistrationRequest(pendingRequest);

        when(userRepository.findById(1L)).thenReturn(Optional.of(viewer));
        when(communityMessageService.getRecentMessages()).thenReturn(List.of(shared, privateForViewer, privateForOther, pendingAdmin));

        List<CommunityChatMessageDTO> response = communityController.getChatMessages(viewer);

        assertEquals(List.of(10L, 11L), response.stream().map(CommunityChatMessageDTO::id).toList());
        CommunityChatMessageDTO privateDto = response.get(1);
        assertTrue(privateDto.privateMessage());
        assertFalse(privateDto.sentByViewer());
        assertEquals("sender", privateDto.privatePeerUsername());
        verify(communityMessageService).markChatViewed(viewer);
    }

    @Test
    void getCommunitySummaryCountsOnlyNewIncomingMessages() {
        User viewer = user(1L, "viewer", UserRole.REGULAR, null);
        viewer.setCommunityLastViewedAt(LocalDateTime.of(2026, 3, 11, 12, 0));
        User author = user(2L, "sender", UserRole.REGULAR, team(20L, "FK Sender"));

        CommunityMessage shared = message(10L, author, "sender", "Shared update", CommunityMessageType.USER);
        shared.setCreatedAt(LocalDateTime.of(2026, 3, 11, 12, 30));

        CommunityMessage privateForViewer = message(11L, author, "sender", "Private hello", CommunityMessageType.USER);
        privateForViewer.setRecipientUser(viewer);
        privateForViewer.setCreatedAt(LocalDateTime.of(2026, 3, 11, 13, 0));

        CommunityMessage oldShared = message(12L, author, "sender", "Old news", CommunityMessageType.USER);
        oldShared.setCreatedAt(LocalDateTime.of(2026, 3, 11, 11, 0));

        when(userRepository.findById(1L)).thenReturn(Optional.of(viewer));
        when(communityMessageService.getRecentMessages()).thenReturn(List.of(shared, privateForViewer, oldShared));

        ResponseEntity<Map<String, Object>> response = communityController.getCommunitySummary(viewer);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(true, response.getBody().get("hasNewMessages"));
        assertEquals(2, response.getBody().get("newMessageCount"));
        assertEquals(1L, response.getBody().get("newPrivateCount"));
        assertEquals(1L, response.getBody().get("newSharedCount"));
        assertEquals("sender", response.getBody().get("latestAuthor"));
    }

    @Test
    void postChatMessageForwardsRecipientIdToService() {
        User viewer = user(1L, "viewer", UserRole.REGULAR, null);
        CommunityPostRequestDTO request = new CommunityPostRequestDTO();
        request.setMessage("Hello there");
        request.setRecipientUserId(7L);

        when(userRepository.findById(1L)).thenReturn(Optional.of(viewer));

        ResponseEntity<?> response = communityController.postChatMessage(viewer, request);

        assertEquals(200, response.getStatusCode().value());
        verify(communityMessageService).postUserMessage(viewer, "Hello there", 7L);
    }

    @Test
    void getMessagingRecipientsExcludesViewerAndMapsTeamData() {
        User viewer = user(1L, "viewer", UserRole.REGULAR, null);
        User recipient = user(2L, "manager2", UserRole.ADMIN, team(99L, "OFK Omladinac"));

        when(userRepository.findById(1L)).thenReturn(Optional.of(viewer));
        when(userRepository.findAllByIdNotOrderByUsernameAsc(1L)).thenReturn(List.of(recipient));

        var response = communityController.getMessagingRecipients(viewer);

        assertEquals(1, response.size());
        assertEquals(2L, response.getFirst().userId());
        assertEquals("manager2", response.getFirst().username());
        assertEquals("OFK Omladinac", response.getFirst().teamName());
        assertEquals("ADMIN", response.getFirst().role());
    }

    private CommunityMessage message(Long id, User author, String authorLabel, String text, CommunityMessageType type) {
        CommunityMessage message = new CommunityMessage();
        message.setId(id);
        message.setAuthorUser(author);
        message.setAuthorLabel(authorLabel);
        message.setMessage(text);
        message.setType(type);
        message.setCreatedAt(LocalDateTime.now());
        return message;
    }

    private User user(Long id, String username, UserRole role, Team team) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setRole(role);
        user.setTeam(team);
        return user;
    }

    private Team team(Long id, String name) {
        Team team = new Team();
        team.setId(id);
        team.setName(name);
        return team;
    }
}