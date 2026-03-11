package org.example.footballmanager.controller;

import lombok.RequiredArgsConstructor;
import org.example.footballmanager.dto.CommunityChatMessageDTO;
import org.example.footballmanager.dto.CommunityPostRequestDTO;
import org.example.footballmanager.dto.MessageResponseDTO;
import org.example.footballmanager.model.CommunityMessage;
import org.example.footballmanager.model.RegistrationRequest;
import org.example.footballmanager.model.RegistrationRequestStatus;
import org.example.footballmanager.model.Team;
import org.example.footballmanager.model.User;
import org.example.footballmanager.model.UserRole;
import org.example.footballmanager.repository.UserRepository;
import org.example.footballmanager.service.CommunityMessageService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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

        return communityMessageService.getRecentMessages().stream()
                .filter(message -> admin || !shouldHideFromNonAdmin(message))
                .map(message -> toDto(message, admin))
                .toList();
    }

    @PostMapping("/chat")
    public ResponseEntity<MessageResponseDTO> postChatMessage(@AuthenticationPrincipal User principal,
                                                              @RequestBody CommunityPostRequestDTO request) {
        User author = resolveViewer(principal);
        try {
            communityMessageService.postUserMessage(author, request.getMessage());
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

    private CommunityChatMessageDTO toDto(CommunityMessage message, boolean adminViewer) {
        RegistrationRequest request = message.getRegistrationRequest();
        Team authorTeam = message.getAuthorUser() != null ? message.getAuthorUser().getTeam() : null;
        Team requestedTeam = request != null ? request.getTeam() : null;
        boolean actionable = adminViewer && request != null && request.getStatus() == RegistrationRequestStatus.PENDING;

        return new CommunityChatMessageDTO(
                message.getId(),
                message.getAuthorLabel(),
                message.getMessage(),
                message.getType().name(),
                message.getCreatedAt(),
                authorTeam != null ? authorTeam.getId() : null,
                authorTeam != null ? authorTeam.getName() : null,
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
}