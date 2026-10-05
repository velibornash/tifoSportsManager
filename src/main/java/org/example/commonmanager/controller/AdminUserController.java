package org.example.commonmanager.controller;

import lombok.RequiredArgsConstructor;
import org.example.commonmanager.model.User;
import org.example.commonmanager.model.UserRole;
import org.example.commonmanager.repository.UserRepository;
import org.example.footballmanager.newLogic.service.ClubOwnershipLinker;
import org.example.footballmanager.newLogic.service.ModerationService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Managing accounts: roles and forum write bans (owner, 2026-10-05).
 *
 * <h2>Why this controller had to exist before the forum could</h2>
 *
 * <p>{@code MOD} was an enum constant with <b>no writer anywhere in the codebase</b>. Neither had
 * {@code ADMIN}, {@code DEV} or {@code STAFF}: the only roles ever assigned by code were {@code OWNER}
 * (two seeders) and {@code REGULAR} (the same seeders, plus {@code RegistrationService} on approval). So
 * a moderator could not be appointed through the product — a forum with delete and ban rules and no way
 * to hold the office that grants them is a forum nobody can moderate.
 *
 * <p>{@link AdminController} was the other candidate and stays where it is: this is user administration,
 * not world administration, and the two have different roles and different blast radii.
 *
 * <p>It sits under {@code /admin}, so {@code SecurityConfig}'s
 * {@code hasAnyRole("ADMIN", "OWNER", "DEV")} matcher is the outer gate. <b>A {@code MOD} is therefore
 * refused here</b> — deliberately. {@code UserRoles.mayModerate} includes {@code MOD} so a moderator can
 * delete a post and apply a forum ban, but changing an account's role is world administration, and a
 * moderator who could promote themselves to {@code ADMIN} would be promoting themselves to the ability
 * to reset the database.
 *
 * <p>{@code AdminAuthorizationTest} exists because {@code /admin} relies entirely on matcher ordering.
 * This controller adds no {@code @PreAuthorize} of its own for that reason.
 */
@RestController
@RequestMapping("/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final ModerationService moderation;
    private final UserRepository users;
    private final ClubOwnershipLinker ownership;

    /**
     * Every account, for the admin user list.
     *
     * <p>Includes the ban columns and the resolved club, because the list's purpose is to be the place a
     * moderator works from — a list that showed names but not who was banned would make them re-visit
     * every profile to find out.
     */
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> listUsers() {
        return ResponseEntity.ok(users.findAll().stream().map(this::row).toList());
    }

    /** Bans an account from writing in the forum. Reading and playing are untouched. */
    @PostMapping("/{userId}/forum-ban")
    public ResponseEntity<Map<String, Object>> banFromForum(
            @PathVariable Long userId,
            @org.springframework.security.core.annotation.AuthenticationPrincipal User moderator,
            @RequestBody(required = false) Map<String, Object> body) {
        int days = readDays(body);
        String reason = body == null ? null : String.valueOf(body.get("reason"));
        return ResponseEntity.ok(row(moderation.banFromForum(moderator, userId, days, reason)));
    }

    @PostMapping("/{userId}/forum-ban/lift")
    public ResponseEntity<Map<String, Object>> liftForumBan(
            @PathVariable Long userId,
            @org.springframework.security.core.annotation.AuthenticationPrincipal User moderator) {
        return ResponseEntity.ok(row(moderation.liftForumBan(moderator, userId)));
    }

    /** Changes an account's role. This is the only writer of {@code MOD}, {@code ADMIN} and {@code DEV}. */
    @PostMapping("/{userId}/role")
    public ResponseEntity<Map<String, Object>> changeRole(
            @PathVariable Long userId,
            @org.springframework.security.core.annotation.AuthenticationPrincipal User moderator,
            @RequestBody(required = false) Map<String, String> body) {
        String wanted = body == null ? null : body.get("role");
        UserRole role;
        try {
            role = wanted == null ? null : UserRole.valueOf(wanted.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("'" + wanted + "' is not a role.");
        }
        return ResponseEntity.ok(row(moderation.changeRole(moderator, userId, role)));
    }

    /**
     * Repairs {@code User.footballTeam} for accounts written before that column existed.
     *
     * <p>A repair, on demand, and idempotent — see {@code ClubOwnershipLinker.backfillAll}. Reported as a
     * count because "did it work" has to be answerable from the response, not inferred from a log line.
     */
    @PostMapping("/repair-club-links")
    public ResponseEntity<Map<String, Object>> repairClubLinks() {
        return ResponseEntity.ok(Map.of("repaired", ownership.backfillAll()));
    }

    private int readDays(Map<String, Object> body) {
        if (body == null || body.get("days") == null) {
            throw new IllegalArgumentException("For how many days?");
        }
        try {
            return Integer.parseInt(String.valueOf(body.get("days")).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("That is not a number of days.");
        }
    }

    private Map<String, Object> row(User user) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", user.getId());
        row.put("displayName", user.getDisplayName());
        row.put("username", user.getUsername());
        row.put("role", user.getRole() == null ? null : user.getRole().name());
        row.put("countryCode", user.getCountryCode());
        row.put("clubId", user.getFootballTeam() == null ? null : user.getFootballTeam().getId());
        row.put("clubName", user.getFootballTeam() == null ? null : user.getFootballTeam().getName());
        row.put("forumBanned", user.isForumBanned());
        row.put("forumBanUntil", user.getForumBanUntil());
        row.put("forumBanReason", user.getForumBanReason());
        row.put("forumBanBy", user.getForumBanBy());
        row.put("forumBanDaysLeft", moderation.remainingBanDays(user));
        return row;
    }
}