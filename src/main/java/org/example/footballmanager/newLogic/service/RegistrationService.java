package org.example.footballmanager.newLogic.service;

import lombok.RequiredArgsConstructor;
import org.example.footballmanager.newLogic.dto.RegisterRequestDTO;
import org.example.footballmanager.newLogic.model.CompetitionTeamType;
import org.example.footballmanager.newLogic.model.Country;
import org.example.footballmanager.newLogic.model.CountryCatalog;
import org.example.footballtextmanager.model.CTeam;
import org.example.footballtextmanager.model.CSCountry;
import org.example.footballtextmanager.model.CSCompetitionTeamType;
import org.example.footballmanager.newLogic.model.RegistrationRequest;
import org.example.footballmanager.newLogic.model.NotificationKind;
import org.example.footballmanager.newLogic.model.RegistrationRequestStatus;
import org.example.footballmanager.newLogic.model.Team;
import org.example.commonmanager.model.User;
import org.example.commonmanager.model.UserRole;
import org.example.commonmanager.model.UserRoles;
import org.example.footballmanager.newLogic.repository.RegistrationRequestRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.example.footballmanager.newLogic.repository.CountryRepository;
import org.example.commonmanager.repository.UserRepository;
import org.springframework.data.domain.Sort;
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
    private final CountryRepository countryRepository;
        private final org.example.footballtextmanager.repository.CSTeamRepository cTeamRepository;
    private final org.example.footballtextmanager.repository.CSCountryRepository csCountryRepository;
    private final PasswordEncoder passwordEncoder;
    private final NotificationService notifications;
    private final UserRepository users;

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

        // The country is chosen by the applicant, and the club is reserved from that country's
        // leagues (owner, 2026-09-28). It used to take the first free AI club in id order, which is
        // always a club in Serbia — so a manager in Qatar was handed a Serbian club and given a
        // country by accident. That was the single hardestcoded country assumption in the system.
        String countryCode = requireKnownCountry(dto.getCountryCode());
        Country country = requireCountryWithClubs(countryCode);

        Team reservedTeam = teamRepository.findAll(Sort.by(Sort.Direction.ASC, "id"))
                .stream()
                .filter(this::isRegistrableClub)
                .filter(team -> !team.isHumanControlled())
                .filter(team -> team.getCountry() != null
                        && countryCode.equalsIgnoreCase(nullToEmpty(team.getCountry().getIsoCode())))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No free clubs are available in " + country.getName()
                                + " yet. Seed that country's leagues first, or pick another country."));

        RegistrationRequest request = new RegistrationRequest();
        request.setUsername(username);
        request.setEmail(email);
        request.setPasswordHash(passwordEncoder.encode(password));
        request.setTeam(reservedTeam);
        request.setCountryCode(countryCode);
        request.setStatus(RegistrationRequestStatus.PENDING);

        RegistrationRequest saved = registrationRequestRepository.save(request);
        // The moderators are told an application arrived. Was a chat message, which meant a pending
        // applicant's username and email travelled through a feed every logged-in manager could read —
        // the exposure P0-17 recorded, and the reason the filter existed at all.
        //
        // The queue itself is on the Admin tab now, where only staff can reach it. A notification is
        // worse for discovery and much better for privacy: a moderator learns that there is something
        // waiting, and goes somewhere only he can see.
        notifyModerators("A new manager applied: " + saved.getUsername(), saved);
        return saved;
    }

    /** The country must be one we actually know — a submitted code is untrusted input. */
    private String requireKnownCountry(String submitted) {
        String code = normalizeText(submitted, "Choose the country you want to play in.");
        return CountryCatalog.byCode(code)
                .orElseThrow(() -> new IllegalArgumentException(
                        "'" + code + "' is not a country this game knows about."))
                .code();
    }

    /**
     * The chosen country, or a refusal that says what is actually wrong.
     *
     * <p>Distinct from "you picked a country with no clubs" — a real and currently common state,
     * because only Serbia has its leagues seeded. Saying so plainly beats a generic failure, and it
     * points the manager at the real problem instead of at the form.
     */
    private Country requireCountryWithClubs(String countryCode) {
        return countryRepository.findByIsoCode(countryCode.toUpperCase(Locale.ROOT))
                .orElseThrow(() -> new IllegalArgumentException(
                        "That country is not set up in this game yet."));
    }

    /**
     * The text-manager's view of a country, created if the seeder has not made it yet.
     *
     * <p>Not optional, and never left null: a club with no country has no country page, no flag in
     * the menu and no competitions to look at — the club exists and the world around it does not.
     */
    private CSCountry csCountryFor(String isoCode) {
        return csCountryRepository.findByIsoCodeIgnoreCase(isoCode)
                .orElseGet(() -> {
                    CSCountry created = new CSCountry();
                    created.setIsoCode(isoCode.toUpperCase(Locale.ROOT));
                    CountryCatalog.byCode(isoCode).ifPresent(c -> created.setName(c.displayName()));
                    created.setName(created.getName() == null ? isoCode : created.getName());
                    return csCountryRepository.save(created);
                });
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    @Transactional
    public RegistrationRequest approveRequest(Long requestId, User reviewer, String reviewNote) {
        RegistrationRequest request = getPendingRequest(requestId);
        User resolvedReviewer = requireReviewer(reviewer);

        if (userRepository.existsByUsernameIgnoreCase(request.getUsername()) || userRepository.existsByEmailIgnoreCase(request.getEmail())) {
            throw new IllegalStateException("A user with this username or email already exists.");
        }

        Team team = request.getTeam();
        team.setHumanControlled(true);
        teamRepository.save(team);

        // Link the account to the club, or the approval produces an account that logs in and manages
        // nothing (owner, 2026-09-28). `User` holds a CTeam and /auth/me resolves the football club by
        // matching that name, so the CTeam and the Team must carry IDENTICAL names. This is the
        // easiest thing in the flow to get silently wrong, and it was missing entirely: before this,
        // the only accounts that existed were the two hand-seeded ones in DatabaseInitializer.
        CTeam club = cTeamRepository.findByName(team.getName())
                .orElseGet(() -> {
                    CTeam created = new CTeam();
                    created.setName(team.getName());
                    created.setType(CSCompetitionTeamType.CLUB);
                    created.setHumanControlled(true);
                    return created;
                });
        club.setHumanControlled(true);
        if (club.getCsCountry() == null && team.getCountry() != null) {
            club.setCsCountry(csCountryFor(team.getCountry().getIsoCode()));
        }
        cTeamRepository.save(club);

        User user = new User();
        user.setUsername(request.getUsername());
        user.setEmail(request.getEmail());
        user.setPassword(request.getPasswordHash());
        user.setRole(UserRole.REGULAR);
        // The country the applicant chose, carried onto the account. Falls back to the reserved
        // club's country for a request raised before the field existed.
        user.setCountryCode(request.getCountryCode() != null && !request.getCountryCode().isBlank()
                ? request.getCountryCode()
                : team.getCountry() != null ? team.getCountry().getIsoCode() : null);
        user.setCTeam(club);
        // The newLogic club as a real id, not a name to be joined later. Approval is the last moment
        // where both are in hand, so this is the one place that cannot get it wrong; every account
        // created from here on is born with the foreign key already filled in.
        user.setFootballTeam(team);
        userRepository.save(user);

        request.setStatus(RegistrationRequestStatus.APPROVED);
        request.setReviewedAt(LocalDateTime.now());
        request.setReviewerUsername(resolvedReviewer.getUsername());
        request.setReviewNote(normalizeOptionalNote(reviewNote));

        RegistrationRequest saved = registrationRequestRepository.save(request);
        notifyModerators(resolvedReviewer.getDisplayName() + " approved " + saved.getUsername(), saved);
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
        notifyModerators(resolvedReviewer.getDisplayName() + " rejected " + saved.getUsername(), saved);
        return saved;
    }

    /**
     * Tells every moderator that something happened to a registration.
     *
     * <p>The applicant's <b>username only</b>, never the email. The old chat put both into a shared feed,
     * and the email was gated behind an admin check while the username was not — which is exactly the
     * half-gating P0-17 was raised for. A notification is a much smaller surface: one recipient at a
     * time, no shared feed, and no field a moderator does not need to see in order to act.
     *
     * <p>The fake email is gone rather than rewritten. It logged a line and wrote a chat row claiming to
     * be a mail; there is no SMTP in this application and no {@code JavaMailSender}, so it was a
     * notification wearing a disguise. What actually exists now is the notification store.
     */
    private void notifyModerators(String summary, RegistrationRequest request) {
        for (User staff : users.findAll()) {
            if (UserRoles.isStaff(staff)) {
                notifications.notify(staff, NotificationKind.REGISTRATION_DECIDED, summary,
                        "admin", request == null ? null : request.getId());
            }
        }
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

    private boolean isRegistrableClub(Team team) {
        return team != null && (team.getType() == null || team.getType() == CompetitionTeamType.CLUB);
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