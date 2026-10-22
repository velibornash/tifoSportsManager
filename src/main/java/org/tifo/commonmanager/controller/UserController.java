package org.tifo.commonmanager.controller;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.tifo.commonmanager.dto.JwtResponseDTO;
import org.tifo.commonmanager.dto.LoginRequestDTO;
import org.tifo.commonmanager.model.User;
import org.tifo.commonmanager.repository.UserRepository;
import org.tifo.commonmanager.repository.LoginHistoryRepository;
import org.tifo.commonmanager.util.JwtUtil;
import org.tifo.footballmanager.newLogic.model.Team;
import org.tifo.footballmanager.newLogic.repository.TeamRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/auth")
public class UserController {

    private final UserRepository userRepo;
    private final AuthenticationManager authManager;
    private final JwtUtil jwtUtil;
    private final TeamRepository teamRepository;
    private final org.tifo.footballmanager.newLogic.service.RegistrationService registrationService;
    private final org.tifo.footballmanager.newLogic.service.ClubOwnershipLinker clubOwnershipLinker;
    private final LoginHistoryRepository loginHistoryRepository;

    public UserController(UserRepository userRepo, AuthenticationManager authManager, JwtUtil jwtUtil, TeamRepository teamRepository,
                          org.tifo.footballmanager.newLogic.service.RegistrationService registrationService,
                          org.tifo.footballmanager.newLogic.service.ClubOwnershipLinker clubOwnershipLinker,
                          LoginHistoryRepository loginHistoryRepository) {
        this.userRepo = userRepo;
        this.authManager = authManager;
        this.jwtUtil = jwtUtil;
        this.teamRepository = teamRepository;
        this.registrationService = registrationService;
        this.clubOwnershipLinker = clubOwnershipLinker;
        this.loginHistoryRepository = loginHistoryRepository;
    }

    /**
     * Registers a manager's interest in a club.
     *
     * <p><b>This endpoint did not exist</b> (owner, 2026-09-28). `register.html` had been posting to
     * `/auth/register` since it was written, `RegistrationService` was fully built, and nothing called
     * it — so registration silently did nothing and the only accounts in the game were the two
     * hand-seeded ones. It was found while adding the country picker, which had nothing to post to.
     *
     * <p>Public, like {@link #login}: a request is a request, not a session. It creates a <i>pending</i>
     * request that an admin approves; no account exists until then.
     */
    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> register(
            @RequestBody org.tifo.footballmanager.newLogic.dto.RegisterRequestDTO dto) {
        try {
            var request = registrationService.createPendingRequest(dto);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "PENDING");
            body.put("message", "Your registration request has been sent for approval.");
            body.put("countryCode", request.getCountryCode());
            body.put("countryName", org.tifo.footballmanager.newLogic.model.CountryCatalog
                    .byCode(request.getCountryCode())
                    .map(org.tifo.footballmanager.newLogic.model.CountryCatalog::displayName)
                    .orElse(request.getCountryCode()));
            body.put("reservedTeamName", request.getTeam() != null ? request.getTeam().getName() : null);
            return ResponseEntity.ok(body);
        } catch (IllegalArgumentException e) {
            // A 400, not a 500: the request was well formed and one of its values was not acceptable.
            // The manager needs to read what was wrong, so the message goes back verbatim.
            return ResponseEntity.badRequest()
                    .body(Map.of("status", "REJECTED", "message", e.getMessage()));
        } catch (IllegalStateException e) {
            // No free club in that country. A 409 rather than a 400: nothing is wrong with the request,
            // the club supply is exhausted, and that is worth distinguishing.
            return ResponseEntity.status(409)
                    .body(Map.of("status", "NO_CLUB", "message", e.getMessage()));
        }
    }

    @PostMapping("/login")
    public ResponseEntity<JwtResponseDTO> login(@RequestBody LoginRequestDTO dto,
                                                jakarta.servlet.http.HttpServletRequest request) {
        authManager.authenticate(new UsernamePasswordAuthenticationToken(dto.getUsername(), dto.getPassword()));

        User user = userRepo.findByUsernameOrEmail(dto.getUsername()).orElseThrow();

        // **Login history (owner, 2026-10-17).** Every successful login is recorded with the IP and
        // the user agent, so an administrator can see where an account has been accessed from. The
        // history is grouped by IP on the client; the server just stores the facts.
        try {
            org.tifo.commonmanager.model.LoginHistory entry = new org.tifo.commonmanager.model.LoginHistory();
            entry.setUser(user);
            entry.setIpAddress(resolveClientIp(request));
            entry.setUserAgent(truncate(request.getHeader("User-Agent"), 512));
            loginHistoryRepository.save(entry);
        } catch (Exception ignored) {
            // A login history row that fails to write must not block the login itself.
        }

        String token = jwtUtil.generateToken(user);
        return ResponseEntity.ok(new JwtResponseDTO(token));
    }

    private static String resolveClientIp(jakarta.servlet.http.HttpServletRequest request) {
        if (request == null) return "unknown";
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        String real = request.getHeader("X-Real-IP");
        if (real != null && !real.isBlank()) return real.trim();
        return request.getRemoteAddr();
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }

    @GetMapping("/me")
    public ResponseEntity<UserDTO> getCurrentUser(@AuthenticationPrincipal User user) {
        if (user == null) {
            return ResponseEntity.status(401).build();
        }

        User resolvedUser = userRepo.findById(user.getId()).orElse(user);

        UserDTO dto = new UserDTO();
        dto.setId(resolvedUser.getId());
        dto.setUsername(resolvedUser.getUsername());
        dto.setEmail(resolvedUser.getEmail());
        dto.setDisplayName(org.tifo.commonmanager.util.DisplayNames.of(resolvedUser));
        dto.setPlusSubscription(resolvedUser.isPlusSubscriber());
        dto.setRole(resolvedUser.getRole().name());

        if (resolvedUser.getCTeam() != null) {
            dto.setTeamId(resolvedUser.getCTeam().getId());
            dto.setTeamName(resolvedUser.getCTeam().getName());
            if (resolvedUser.getCTeam().getCsCountry() != null) {
                dto.setCountryName(resolvedUser.getCTeam().getCsCountry().getName());
                dto.setCountryIsoCode(resolvedUser.getCTeam().getCsCountry().getIsoCode());
            }
        }

        // The country the manager CHOSE, which outranks whatever club they happen to hold
        // (owner, 2026-09-28). Without this the whole country-agnostic system hangs off a derived
        // value, and a user would see the leagues of whatever country their club is in regardless of
        // the country they registered with. The block above is the legacy path for an account created
        // before the field existed, and only survives here when the user has no country of their own.
        String chosenCountry = resolvedUser.getCountryCode();
        if (chosenCountry != null && !chosenCountry.isBlank()) {
            org.tifo.footballmanager.newLogic.model.CountryCatalog.byCode(chosenCountry)
                    .ifPresent(chosen -> {
                        dto.setCountryIsoCode(chosen.code());
                        dto.setCountryName(chosen.displayName());
                    });
        }

        // **The club he manages, from the field that says so** (owner, 2026-10-18).
        //
        // This used to resolve the football club by looking up the *legacy* CTeam's name - the
        // footballtextmanager model - and it never read `User.footballTeam` at all. The authoritative
        // column was ignored and a name-join decided instead, so a manager who changed clubs was still
        // reported at his old one: a job offer accepted, the row in `app_user` correctly repointed, and
        // the entire application still loading the club he had left.
        //
        // `ClubOwnershipLinker.clubOf` is the resolver this codebase built to end exactly that - its own
        // javadoc says so. It prefers the foreign key, falls back to the name-join for a legacy row, and
        // persists what it finds.
        org.tifo.footballmanager.newLogic.model.Team managedClub = clubOwnershipLinker.clubOf(resolvedUser);
        if (managedClub != null) {
            dto.setFootballTeamId(managedClub.getId());
            dto.setFootballTeamName(managedClub.getName());
            dto.setFootballTeamLogoUrl(managedClub.getLogoUrl());
            // Which league he is in, and what it is called. The SPA reads these to decide which league
            // to open, so a club without them sends the league view to league 1.
            if (managedClub.getCompetition() != null) {
                dto.setCompetitionId(managedClub.getCompetition().getId());
                dto.setCompetitionName(managedClub.getCompetition().getName());
                dto.setCompetitionTier(managedClub.getCompetition().getTier());
            }
        }
        if (resolvedUser.getTifoCTeam() != null) {
            dto.setTifoTeamId(resolvedUser.getTifoCTeam().getId());
            dto.setTifoTeamName(resolvedUser.getTifoCTeam().getName());
        }
        if (resolvedUser.getBasketballTeam() != null) {
            dto.setBasketballTeamId(resolvedUser.getBasketballTeam().getId());
            dto.setBasketballTeamName(resolvedUser.getBasketballTeam().getName());
        }
        if (resolvedUser.getAmericanFootballTeam() != null) {
            dto.setAmericanFootballTeamId(resolvedUser.getAmericanFootballTeam().getId());
            dto.setAmericanFootballTeamName(resolvedUser.getAmericanFootballTeam().getName());
        }

        return ResponseEntity.ok(dto);
    }

    /**
     * Login history for one account (admin only).
     *
     * <p>Grouped by IP on the client; the server returns the raw facts (ip, userAgent, loginAt) so
     * the grouping and the expand/collapse stay a presentation concern.
     */
    @GetMapping("/admin/users/{userId}/login-history")
    public ResponseEntity<List<org.tifo.commonmanager.model.LoginHistory>> loginHistory(
            @AuthenticationPrincipal User caller,
            @PathVariable Long userId) {
        if (caller == null || !isAdmin(caller)) {
            return ResponseEntity.status(403).build();
        }
        User target = userRepo.findById(userId).orElse(null);
        if (target == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(loginHistoryRepository.findByUserOrderByLoginAtDesc(target));
    }

    private static boolean isAdmin(User user) {
        String role = user == null ? null : user.getRole() == null ? null : user.getRole().name();
        return "ADMIN".equals(role) || "OWNER".equals(role) || "DEV".equals(role);
    }

    @Data
    public static class UserDTO {
        private Long id;
        private String username;
        private String email;

        /**
         * What the manager is called. Falls back to the username when unset, so a profile never shows
         * a blank where a name belongs.
         */
        private String displayName;

        private String role;

        /**
         * Whether this account has paid for PLUS — reported separately from {@link #role} on purpose.
         * A role is a permission and a subscription is a purchase, and showing an owner as a paying
         * customer because his role bypasses the check would be wrong on the one screen whose whole
         * job is to tell the truth about the account.
         */
        private Boolean plusSubscription;
        private Long teamId;
        private String teamName;
        private Long footballTeamId;
        private String footballTeamName;

        /** Club badge path, or null when the club has none. The SPA applies its own default. */
        private String footballTeamLogoUrl;
        private Long tifoTeamId;
        private String tifoTeamName;
        private Long basketballTeamId;
        private String basketballTeamName;
        private Long americanFootballTeamId;
        private String americanFootballTeamName;
        private String countryName;
        private String countryIsoCode;

        /** The league the manager's club is in, so the SPA never has to guess. */
        private Long competitionId;
        private String competitionName;
        private Integer competitionTier;
    }
}