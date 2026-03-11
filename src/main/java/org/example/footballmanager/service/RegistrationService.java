package org.example.footballmanager.service;

import lombok.RequiredArgsConstructor;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class RegistrationService {

    private final RegistrationRequestRepository registrationRequestRepository;
    private final UserRepository userRepository;
    private final TeamRepository teamRepository;
    private final PasswordEncoder passwordEncoder;
    private final CommunityMessageService communityMessageService;

    @Transactional
    public RegistrationRequest createPendingRequest(RegisterRequestDTO dto) {
        String username = normalizeText(dto.getUsername(), "Username is required.");
        String email = normalizeEmail(dto.getEmail());
        String password = normalizeText(dto.getPassword(), "Password is required.");

        if (userRepository.existsByUsernameIgnoreCase(username)
                || registrationRequestRepository.findByUsernameIgnoreCaseAndStatus(username, RegistrationRequestStatus.PENDING).isPresent()) {
            throw new IllegalArgumentException("Username is already taken or waiting for approval.");
        }

        if (userRepository.existsByEmailIgnoreCase(email)
                || registrationRequestRepository.findByEmailIgnoreCaseAndStatus(email, RegistrationRequestStatus.PENDING).isPresent()) {
            throw new IllegalArgumentException("Email is already taken or waiting for approval.");
        }

        Team reservedTeam = teamRepository.findAllByTypeOrderByIdAsc(CompetitionTeamType.CLUB)
                .stream()
                .filter(team -> !team.isHumanControlled())
                .filter(team -> !userRepository.existsByTeam(team))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Trenutno nema slobodnih AI timova za registraciju."));

        RegistrationRequest request = new RegistrationRequest();
        request.setUsername(username);
        request.setEmail(email);
        request.setPasswordHash(passwordEncoder.encode(password));
        request.setTeam(reservedTeam);
        request.setStatus(RegistrationRequestStatus.PENDING);

        RegistrationRequest saved = registrationRequestRepository.save(request);
        communityMessageService.postRegistrationSubmitted(saved);
        return saved;
    }

    @Transactional
    public RegistrationRequest approveRequest(Long requestId, User reviewer, String reviewNote) {
        RegistrationRequest request = getPendingRequest(requestId);
        User resolvedReviewer = requireReviewer(reviewer);

        if (userRepository.existsByUsernameIgnoreCase(request.getUsername()) || userRepository.existsByEmailIgnoreCase(request.getEmail())) {
            throw new IllegalStateException("A user with this username or email already exists.");
        }
        if (userRepository.existsByTeam(request.getTeam())) {
            throw new IllegalStateException("Reserved team is no longer free.");
        }

        Team team = request.getTeam();
        team.setHumanControlled(true);

        User user = new User();
        user.setUsername(request.getUsername());
        user.setEmail(request.getEmail());
        user.setPassword(request.getPasswordHash());
        user.setRole(UserRole.REGULAR);
        user.setTeam(team);
        userRepository.save(user);

        request.setStatus(RegistrationRequestStatus.APPROVED);
        request.setReviewedAt(LocalDateTime.now());
        request.setReviewerUsername(resolvedReviewer.getUsername());
        request.setReviewNote(normalizeOptionalNote(reviewNote));

        RegistrationRequest saved = registrationRequestRepository.save(request);
        communityMessageService.postRegistrationApproved(saved, resolvedReviewer);
        communityMessageService.postFakeEmailNotification(saved, true);
        return saved;
    }

    @Transactional
    public RegistrationRequest rejectRequest(Long requestId, User reviewer, String reviewNote) {
        RegistrationRequest request = getPendingRequest(requestId);
        User resolvedReviewer = requireReviewer(reviewer);

        request.setStatus(RegistrationRequestStatus.REJECTED);
        request.setReviewedAt(LocalDateTime.now());
        request.setReviewerUsername(resolvedReviewer.getUsername());
        request.setReviewNote(normalizeOptionalNote(reviewNote));

        RegistrationRequest saved = registrationRequestRepository.save(request);
        communityMessageService.postRegistrationRejected(saved, resolvedReviewer, saved.getReviewNote());
        communityMessageService.postFakeEmailNotification(saved, false);
        return saved;
    }

    private RegistrationRequest getPendingRequest(Long requestId) {
        RegistrationRequest request = registrationRequestRepository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Registration request not found."));
        if (request.getStatus() != RegistrationRequestStatus.PENDING) {
            throw new IllegalStateException("Registration request is already " + request.getStatus().name().toLowerCase(Locale.ROOT) + ".");
        }
        return request;
    }

    private User requireReviewer(User reviewer) {
        if (reviewer == null || reviewer.getId() == null) {
            throw new IllegalArgumentException("Reviewer not found.");
        }
        return userRepository.findById(reviewer.getId()).orElse(reviewer);
    }

    private String normalizeText(String value, String errorMessage) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(errorMessage);
        }
        return normalized;
    }

    private String normalizeEmail(String value) {
        return normalizeText(value, "Email is required.").toLowerCase(Locale.ROOT);
    }

    private String normalizeOptionalNote(String value) {
        String normalized = value == null ? "" : value.trim();
        return normalized.isBlank() ? null : normalized;
    }
}