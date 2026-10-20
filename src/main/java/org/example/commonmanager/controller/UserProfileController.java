package org.example.commonmanager.controller;

import lombok.RequiredArgsConstructor;
import org.example.commonmanager.model.User;
import org.example.commonmanager.model.UserRoles;
import org.example.commonmanager.repository.UserRepository;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.service.ClubOwnershipLinker;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Somebody else's profile — the page you reach by clicking a club and then its manager.
 *
 * <p><b>Separate from {@link UserController} on purpose.</b> That controller is mapped {@code /auth},
 * which is {@code permitAll} with a null-check per method: its own javadoc records that "a new endpoint
 * here is public by default, silently", and {@code UserControllerAuthorizationTest} guards it. A public
 * profile does not belong on that prefix even with a guard on every method — a second prefix that means
 * "authenticated" in the matcher list is one thing to forget, rather than one thing per method.
 *
 * <p><b>What is not here is the point.</b> No email, no username, no last-seen, no ban columns unless
 * the caller moderates. The old chat had to gate the applicant's email behind {@code adminViewer}
 * ({@code CommunityController:173}) and even then let the <i>username</i> through — which is what P0-17
 * was about. A profile is the surface where that mistake would repeat, so the answer is structural: this
 * DTO has no email field to gate.
 */
@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserProfileController {

    /** Long enough to be a name, short enough that it cannot be a paragraph on someone's profile. */
    private static final int MAX_DISPLAY_NAME = 40;

    private final UserRepository users;
    private final ClubOwnershipLinker ownership;

    /**
     * The public profile of one account.
     *
     * <p>Readable by any authenticated manager, like a club profile — a community where you cannot see
     * who runs the team you are about to play is not a community.
     */
    @GetMapping("/{userId}/profile")
    public ResponseEntity<Map<String, Object>> profile(@PathVariable Long userId) {
        User subject = users.findById(userId).orElse(null);
        if (subject == null) {
            return ResponseEntity.notFound().build();
        }

        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("id", subject.getId());

        // displayName, or the username when unset — the same five-place fallback that already exists
        // in the codebase. It has to keep existing: every self-registered account has a null
        // displayName until Phase 2b lets them set one.
        String name = subject.getDisplayName();
        profile.put("displayName", name != null && !name.isBlank() ? name : subject.getUsername());
        // So the page can invite him to pick a name rather than showing an email address in its place.
        profile.put("hasChosenName", name != null && !name.isBlank());

        profile.put("role", UserRoles.nameOf(subject.getRole()));
        profile.put("countryCode", subject.getCountryCode());
        profile.put("plusSubscriber", subject.isPlusSubscriber());

        Team club = ownership.clubOf(subject);
        if (club != null) {
            profile.put("clubId", club.getId());
            profile.put("clubName", club.getName());
            profile.put("clubLogoUrl", club.getLogoUrl());
            profile.put("leagueId", club.getCompetition() == null ? null : club.getCompetition().getId());
            profile.put("leagueName", club.getCompetition() == null ? null : club.getCompetition().getName());
        }

        // No last-seen timestamp, and that is a decision rather than an omission. Presence is already
        // answered on the World page, which states its five-minute window rather than implying one;
        // repeating it per-stranger on a public page would disclose a timeline nobody asked for and
        // add a field every future reader has to decide about.

        return ResponseEntity.ok(profile);
    }

    /**
     * Sets the caller's own display name.
     *
     * <p><b>This field was never user-writable.</b> Four call sites in the entire repository wrote it, all
     * of them seeders, and there was no {@code PUT} or {@code PATCH} on any user anywhere — so every
     * account that came through the registration flow has a null name and shows an email address
     * wherever a person belongs. A profile page cannot be finished while its subject cannot say who
     * they are.
     *
     * <p>{@code PATCH} on {@code /users/me}, not on {@code /users/{id}} — an id in the path invites a
     * future copy-paste into someone else's profile, and there is no reason for the caller's own name to
     * travel as an id at all.
     */
    @PatchMapping("/me/display-name")
    public ResponseEntity<Map<String, Object>> setDisplayName(
            @AuthenticationPrincipal User caller,
            @RequestBody(required = false) Map<String, String> body) {
        if (caller == null) {
            return ResponseEntity.status(401).build();
        }
        String wanted = body == null ? null : body.get("displayName");

        String cleaned = normalize(wanted);
        if (cleaned == null) {
            throw new IllegalArgumentException(
                    "Give a name between 1 and " + MAX_DISPLAY_NAME + " characters, or leave it as your login.");
        }

        User subject = users.findById(caller.getId()).orElse(caller);
        subject.setDisplayName(cleaned);
        users.save(subject);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("displayName", cleaned);
        return ResponseEntity.ok(payload);
    }

    /**
     * Rejects a blank or over-long name, returning null so the caller can phrase the error.
     *
     * <p>Blank is refused rather than stored: an empty {@code displayName} is not "no name", because every
     * reader falls back to the username on blank, so storing one would silently do nothing while
     * appearing to have worked.
     */
    private String normalize(String wanted) {
        if (wanted == null) {
            return null;
        }
        String cleaned = wanted.trim();
        if (cleaned.isEmpty() || cleaned.length() > MAX_DISPLAY_NAME) {
            return null;
        }
        return cleaned;
    }
}