package org.example.commonmanager.controller;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import java.util.List;
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

    public UserController(UserRepository userRepo, AuthenticationManager authManager, JwtUtil jwtUtil, TeamRepository teamRepository) {
        this.userRepo = userRepo;
        this.authManager = authManager;
        this.jwtUtil = jwtUtil;
        this.teamRepository = teamRepository;
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
        dto.setRole(resolvedUser.getRole().name());

        if (resolvedUser.getCTeam() != null) {
            dto.setTeamId(resolvedUser.getCTeam().getId());
            dto.setTeamName(resolvedUser.getCTeam().getName());
            if (resolvedUser.getCTeam().getCsCountry() != null) {
                dto.setCountryName(resolvedUser.getCTeam().getCsCountry().getName());
                dto.setCountryIsoCode(resolvedUser.getCTeam().getCsCountry().getIsoCode());
            }
            
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
        private String role;
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
    }
}