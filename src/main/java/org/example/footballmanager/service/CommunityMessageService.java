package org.example.footballmanager.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.model.CommunityMessage;
import org.example.footballmanager.model.CommunityMessageType;
import org.example.footballmanager.model.RegistrationRequest;
import org.example.footballmanager.model.User;
import org.example.footballmanager.model.UserRole;
import org.example.footballmanager.repository.CommunityMessageRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class CommunityMessageService {

    private final CommunityMessageRepository communityMessageRepository;

    @Transactional(readOnly = true)
    public List<CommunityMessage> getRecentMessages() {
        return communityMessageRepository.findTop150ByOrderByCreatedAtDescIdDesc();
    }

    @Transactional
    public CommunityMessage postUserMessage(User author, String rawMessage) {
        String message = normalizeMessage(rawMessage);
        CommunityMessageType type = isAdminRole(author.getRole()) ? CommunityMessageType.ADMIN : CommunityMessageType.USER;
        return save(author, author.getUsername(), message, type, null);
    }

    @Transactional
    public CommunityMessage postRegistrationSubmitted(RegistrationRequest request) {
        return save(
                null,
                "Registration Desk",
                "New ownership request: " + request.getUsername() + " wants to take over " + request.getTeam().getName() + ".",
                CommunityMessageType.SERVICE,
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
                request
        );
    }

    @Transactional
    public CommunityMessage postFakeEmailNotification(RegistrationRequest request, boolean approved) {
        String message = approved
                ? "Fake email sent to " + request.getEmail() + ": your ownership request for " + request.getTeam().getName() + " was approved."
                : "Fake email sent to " + request.getEmail() + ": your ownership request for " + request.getTeam().getName() + " was rejected.";
        log.info("{}", message);
        return save(null, "Mail Service", message, CommunityMessageType.SERVICE, request);
    }

    private CommunityMessage save(User author, String authorLabel, String message, CommunityMessageType type, RegistrationRequest request) {
        CommunityMessage entity = new CommunityMessage();
        entity.setAuthorUser(author);
        entity.setAuthorLabel(authorLabel);
        entity.setMessage(message);
        entity.setType(type);
        entity.setRegistrationRequest(request);
        return communityMessageRepository.save(entity);
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