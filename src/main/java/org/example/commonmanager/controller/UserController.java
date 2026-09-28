package org.example.commonmanager.controller;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.example.commonmanager.dto.JwtResponseDTO;
import org.example.commonmanager.dto.LoginRequestDTO;
import org.example.commonmanager.model.User;
import org.example.commonmanager.repository.UserRepository;
import org.example.commonmanager.util.JwtUtil;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.TeamRepository;
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
    private final org.example.footballmanager.newLogic.service.RegistrationService registrationService;

    public UserController(UserRepository userRepo, AuthenticationManager authManager, JwtUtil jwtUtil, TeamRepository teamRepository,
                          org.example.footballmanager.newLogic.service.RegistrationService registrationService) {
        this.userRepo = userRepo;
        this.authManager = authManager;
        this.jwtUtil = jwtUtil;
        this.teamRepository = teamRepository;
        this.registrationService = registrationService;
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
            @RequestBody org.example.footballmanager.newLogic.dto.RegisterRequestDTO dto) {
        try {
            var request = registrationService.createPendingRequest(dto);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "PENDING");
            body.put("message", "Your registration request has been sent for approval.");
            body.put("countryCode", request.getCountryCode());
            body.put("countryName", org.example.footballmanager.newLogic.model.CountryCatalog
                    .byCode(request.getCountryCode())
                    .map(org.example.footballmanager.newLogic.model.CountryCatalog::displayName)
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
    public ResponseEntity<JwtResponseDTO> login(@RequestBody LoginRequestDTO dto) {
        authManager.authenticate(new UsernamePasswordAuthenticationToken(dto.getUsername(), dto.getPassword()));

        User user = userRepo.findByUsernameOrEmail(dto.getUsername()).orElseThrow();
        String token = jwtUtil.generateToken(user);
        return ResponseEntity.ok(new JwtResponseDTO(token));
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
        dto.setDisplayName(resolvedUser.getDisplayName() != null && !resolvedUser.getDisplayName().isBlank()
                ? resolvedUser.getDisplayName() : resolvedUser.getUsername());
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
            org.example.footballmanager.newLogic.model.CountryCatalog.byCode(chosenCountry)
                    .ifPresent(chosen -> {
                        dto.setCountryIsoCode(chosen.code());
                        dto.setCountryName(chosen.displayName());
                    });
        }

        if (resolvedUser.getCTeam() != null) {
            
            // Look up the newLogic football team by name.
            //
            // findByName throws when two clubs share a name, and two clubs sharing a name is
            // explicitly allowed - TeamRepository's own javadoc says so. Calling it here meant a
            // duplicate club name anywhere in the database could make /auth/me fail, which is a
            // login failure for that user and not something a duplicate should be able to cause.
            // findAllByNameIgnoreCase is the survivable call: it is explicit about the ambiguity
            // instead of throwing on it.
            String teamName = resolvedUser.getCTeam().getName();
            List<Team> matches = teamRepository.findAllByNameIgnoreCase(teamName);
            if (matches.size() > 1) {
                // A human's club wins the tie. If two clubs match, the one somebody actually
                // manages is the one they meant.
                matches.stream()
                        .filter(Team::isHumanControlled)
                        .findFirst()
                        .ifPresentOrElse(
                                match -> log.warn("Naziv kluba '{}' pripada {} klubovima; "
                                                + "koristim ljudski kontrolisani {}", teamName, matches.size(), match.getName()),
                                () -> log.warn("Naziv kluba '{}' pripada {} klubovima i nijedan nije "
                                        + "ljudski kontrolisan; koristim prvi", teamName, matches.size()));
            }
            matches.stream()
                    .filter(Team::isHumanControlled)
                    .findFirst()
                    .or(() -> matches.stream().findFirst())
                    .ifPresent(team -> {
                        dto.setFootballTeamId(team.getId());
                        dto.setFootballTeamName(team.getName());
                        dto.setFootballTeamLogoUrl(team.getLogoUrl());
                        // Which league he is in, and what it is called.
                        //
                        // The SPA reads these to decide which league to open. They were missing
                        // here, so the league view always fell back to league 1 and titled itself
                        // "League" — which is how a second manager ends up looking at the wrong
                        // club's table, and how a Šid manager ends up staring at the Superliga.
                        if (team.getCompetition() != null) {
                            dto.setCompetitionId(team.getCompetition().getId());
                            dto.setCompetitionName(team.getCompetition().getName());
                            dto.setCompetitionTier(team.getCompetition().getTier());
                        }
                    });
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