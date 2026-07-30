package org.example.footballmanager.newLogic.controller;

import lombok.RequiredArgsConstructor;
import org.example.footballmanager.newLogic.dto.CommunityChatMessageDTO;
import org.example.footballmanager.newLogic.dto.CommunityPostRequestDTO;
import org.example.footballmanager.newLogic.dto.CommunityRecipientDTO;
import org.example.footballmanager.newLogic.dto.MessageResponseDTO;
import org.example.footballmanager.newLogic.model.CommunityMessage;
import org.example.footballmanager.newLogic.model.RegistrationRequest;
import org.example.footballmanager.newLogic.model.RegistrationRequestStatus;
import org.example.footballmanager.newLogic.model.Team;
import org.example.commonmanager.model.User;
import org.example.commonmanager.model.UserRole;
import org.example.commonmanager.repository.UserRepository;
import org.example.footballmanager.newLogic.service.CommunityMessageService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@RestController
@RequestMapping("/community")
@RequiredArgsConstructor
public class CommunityController {

    private final CommunityMessageService communityMessageService;
    private final UserRepository userRepository;

    @GetMapping("/chat")
    public List<CommunityChatMessageDTO> getChatMessages(@AuthenticationPrincipal User principal) {
        User viewer = resolveViewer(principal);
        boolean admin = isAdminRole(viewer.getRole());

        List<CommunityChatMessageDTO> payload = communityMessageService.getRecentMessages().stream()
                .filter(message -> canViewMessage(message, viewer, admin))
                .map(message -> toDto(message, viewer, admin))
                .toList();
        communityMessageService.markChatViewed(viewer);
        return payload;
    }

    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> getCommunitySummary(@AuthenticationPrincipal User principal) {
        User viewer = resolveViewer(principal);
        boolean admin = isAdminRole(viewer.getRole());
        LocalDateTime lastViewedAt = viewer.getCommunityLastViewedAt();

        List<CommunityMessage> newIncomingMessages = communityMessageService.getRecentMessages().stream()
                .filter(message -> canViewMessage(message, viewer, admin))
                .filter(message -> message.getCreatedAt() != null)
                .filter(message -> lastViewedAt == null || message.getCreatedAt().isAfter(lastViewedAt))
                .filter(message -> message.getAuthorUser() == null || !Objects.equals(message.getAuthorUser().getId(), viewer.getId()))
                .toList();

        long newPrivateCount = newIncomingMessages.stream()
                .filter(message -> message.getRecipientUser() != null)
                .count();
        long newSharedCount = newIncomingMessages.stream()
                .filter(message -> message.getRecipientUser() == null)
                .count();

        CommunityMessage latest = newIncomingMessages.stream()
                .max(Comparator
                        .comparing(CommunityMessage::getCreatedAt, Comparator.nullsLast(LocalDateTime::compareTo))
                        .thenComparing(CommunityMessage::getId, Comparator.nullsLast(Long::compareTo)))
                .orElse(null);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("hasNewMessages", !newIncomingMessages.isEmpty());
        payload.put("newMessageCount", newIncomingMessages.size());
        payload.put("newPrivateCount", newPrivateCount);
        payload.put("newSharedCount", newSharedCount);
        payload.put("latestAuthor", latest != null ? latest.getAuthorLabel() : null);
        payload.put("latestMessage", latest != null ? abbreviate(latest.getMessage(), 120) : null);
        return ResponseEntity.ok(payload);
    }

    @GetMapping("/recipients")
    public List<CommunityRecipientDTO> getMessagingRecipients(@AuthenticationPrincipal User principal) {
        User viewer = resolveViewer(principal);
        return userRepository.findAllByIdNotOrderByUsernameAsc(viewer.getId()).stream()
                .filter(user -> user.getId() != null)
                .filter(user -> user.getUsername() != null && !user.getUsername().isBlank())
                .map(user -> new CommunityRecipientDTO(
                        user.getId(),
                        user.getUsername(),
                        user.getTifoCTeam() != null ? user.getTifoCTeam().getId() : null,
                        user.getTifoCTeam() != null ? user.getTifoCTeam().getName() : null,
                        user.getRole() != null ? user.getRole().name() : "USER"
                ))
                .toList();
    }

    @PostMapping("/chat")
    public ResponseEntity<MessageResponseDTO> postChatMessage(@AuthenticationPrincipal User principal,
                                                              @RequestBody CommunityPostRequestDTO request) {
        User author = resolveViewer(principal);
        try {
            communityMessageService.postUserMessage(author, request.getMessage(), request.getRecipientUserId());
            return ResponseEntity.ok(new MessageResponseDTO("SENT", "Message sent."));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new MessageResponseDTO("INVALID_MESSAGE", ex.getMessage()));
        }
    }

    private User resolveViewer(User principal) {
        if (principal == null || principal.getId() == null) {
            throw new IllegalArgumentException("Authenticated user not found.");
        }
        return userRepository.findById(principal.getId()).orElse(principal);
    }

    private boolean shouldHideFromNonAdmin(CommunityMessage message) {
        RegistrationRequest request = message.getRegistrationRequest();
        return request != null && request.getStatus() == RegistrationRequestStatus.PENDING;
    }

    private boolean canViewMessage(CommunityMessage message, User viewer, boolean adminViewer) {
        if (message.getRecipientUser() != null) {
            Long viewerId = viewer != null ? viewer.getId() : null;
            Long authorId = message.getAuthorUser() != null ? message.getAuthorUser().getId() : null;
            Long recipientId = message.getRecipientUser().getId();
            if (!Objects.equals(authorId, viewerId) && !Objects.equals(recipientId, viewerId)) {
                return false;
            }
        }
        return adminViewer || !shouldHideFromNonAdmin(message);
    }

    private CommunityChatMessageDTO toDto(CommunityMessage message, User viewer, boolean adminViewer) {
        RegistrationRequest request = message.getRegistrationRequest();
        var authorTeam = message.getAuthorUser() != null ? message.getAuthorUser().getTifoCTeam() : null;
        User recipient = message.getRecipientUser();
        Team requestedTeam = request != null ? request.getTeam() : null;
        boolean privateMessage = recipient != null;
        boolean sentByViewer = privateMessage
                && message.getAuthorUser() != null
                && Objects.equals(message.getAuthorUser().getId(), viewer.getId());
        String privatePeerUsername = null;
        if (privateMessage) {
            privatePeerUsername = sentByViewer
                    ? recipient.getUsername()
                    : (message.getAuthorUser() != null ? message.getAuthorUser().getUsername() : message.getAuthorLabel());
        }
        boolean actionable = adminViewer && request != null && request.getStatus() == RegistrationRequestStatus.PENDING;

        return new CommunityChatMessageDTO(
                message.getId(),
                message.getAuthorLabel(),
                message.getMessage(),
                message.getType().name(),
                message.getCreatedAt(),
                authorTeam != null ? authorTeam.getId() : null,
                authorTeam != null ? authorTeam.getName() : null,
                recipient != null ? recipient.getId() : null,
                recipient != null ? recipient.getUsername() : null,
                privateMessage,
                sentByViewer,
                privatePeerUsername,
                request != null ? request.getId() : null,
                request != null ? request.getStatus().name() : null,
                request != null ? request.getUsername() : null,
                adminViewer && request != null ? request.getEmail() : null,
                requestedTeam != null ? requestedTeam.getId() : null,
                requestedTeam != null ? requestedTeam.getName() : null,
                request != null ? request.getReviewerUsername() : null,
                request != null ? request.getReviewNote() : null,
                actionable,
                actionable
        );
    }

    private boolean isAdminRole(UserRole role) {
        return role == UserRole.OWNER || role == UserRole.ADMIN || role == UserRole.DEV;
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null || value.isBlank() || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, Math.max(0, maxLength - 1)).trim() + "…";
    }
}