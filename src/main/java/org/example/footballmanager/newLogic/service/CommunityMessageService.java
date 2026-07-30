package org.example.footballmanager.newLogic.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.newLogic.model.CommunityMessage;
import org.example.footballmanager.newLogic.model.CommunityMessageType;
import org.example.footballmanager.newLogic.model.RegistrationRequest;
import org.example.commonmanager.model.User;
import org.example.commonmanager.model.UserRole;
import org.example.footballmanager.newLogic.repository.CommunityMessageRepository;
import org.example.commonmanager.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class CommunityMessageService {

    private final CommunityMessageRepository communityMessageRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public List<CommunityMessage> getRecentMessages() {
        return communityMessageRepository.findTop150ByOrderByCreatedAtDescIdDesc();
    }

    @Transactional
    public CommunityMessage postUserMessage(User author, String rawMessage, Long recipientUserId) {
        String message = normalizeMessage(rawMessage);
        CommunityMessageType type = isAdminRole(author.getRole()) ? CommunityMessageType.ADMIN : CommunityMessageType.USER;
        User recipient = resolveRecipient(author, recipientUserId);
        return save(author, author.getUsername(), message, type, recipient, null);
    }

    @Transactional
    public CommunityMessage postRegistrationSubmitted(RegistrationRequest request) {
        return save(
                null,
                "Registration Desk",
                "New ownership request: " + request.getUsername() + " wants to take over " + request.getTeam().getName() + ".",
                CommunityMessageType.SERVICE,
                null,
                request
        );
    }

    @Transactional
    public CommunityMessage postRegistrationApproved(RegistrationRequest request, User reviewer) {
        return save(
                reviewer,
                reviewer.getUsername(),
                "Ownership approved for " + request.getUsername() + ". Club assigned: " + request.getTeam().getName() + ".",
                CommunityMessageType.SERVICE,
                null,
                request
        );
    }

    @Transactional
    public CommunityMessage postRegistrationRejected(RegistrationRequest request, User reviewer, String note) {
        String suffix = note == null || note.isBlank() ? "" : " Note: " + note.trim();
        return save(
                reviewer,
                reviewer.getUsername(),
                "Ownership request rejected for " + request.getUsername() + " (" + request.getTeam().getName() + ")." + suffix,
                CommunityMessageType.SERVICE,
                null,
                request
        );
    }

    @Transactional
    public CommunityMessage postFakeEmailNotification(RegistrationRequest request, boolean approved) {
        String message = approved
                ? "Fake email sent to " + request.getEmail() + ": your ownership request for " + request.getTeam().getName() + " was approved."
                : "Fake email sent to " + request.getEmail() + ": your ownership request for " + request.getTeam().getName() + " was rejected.";
        log.info("{}", message);
        return save(null, "Mail Service", message, CommunityMessageType.SERVICE, null, request);
    }

    @Transactional
    public void markChatViewed(User viewer) {
        if (viewer == null || viewer.getId() == null) {
            return;
        }
        viewer.setCommunityLastViewedAt(LocalDateTime.now());
        userRepository.save(viewer);
    }

    private CommunityMessage save(User author, String authorLabel, String message, CommunityMessageType type, User recipient, RegistrationRequest request) {
        CommunityMessage entity = new CommunityMessage();
        entity.setAuthorUser(author);
        entity.setAuthorLabel(authorLabel);
        entity.setMessage(message);
        entity.setType(type);
        entity.setRecipientUser(recipient);
        entity.setRegistrationRequest(request);
        return communityMessageRepository.save(entity);
    }

    private User resolveRecipient(User author, Long recipientUserId) {
        if (recipientUserId == null) {
            return null;
        }
        if (author == null || author.getId() == null) {
            throw new IllegalArgumentException("Authenticated author not found.");
        }
        if (author.getId().equals(recipientUserId)) {
            throw new IllegalArgumentException("Choose another user for a private message.");
        }
        return userRepository.findById(recipientUserId)
                .orElseThrow(() -> new IllegalArgumentException("Selected recipient was not found."));
    }

    private String normalizeMessage(String rawMessage) {
        String message = rawMessage == null ? "" : rawMessage.trim();
        if (message.isBlank()) {
            throw new IllegalArgumentException("Message cannot be empty.");
        }
        return message.length() > 1200 ? message.substring(0, 1200) : message;
    }

    private boolean isAdminRole(UserRole role) {
        return role == UserRole.OWNER || role == UserRole.ADMIN || role == UserRole.DEV;
    }
}