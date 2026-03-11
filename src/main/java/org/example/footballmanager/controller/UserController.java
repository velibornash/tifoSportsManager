package org.example.footballmanager.controller;

import lombok.Data;
import org.example.footballmanager.dto.JwtResponseDTO;
import org.example.footballmanager.dto.LoginRequestDTO;
import org.example.footballmanager.dto.RegisterResponseDTO;
import org.example.footballmanager.dto.RegisterRequestDTO;
import org.example.footballmanager.model.Competition;
import org.example.footballmanager.model.CompetitionEntry;
import org.example.footballmanager.model.Country;
import org.example.footballmanager.model.RegistrationRequest;
import org.example.footballmanager.model.Team;
import org.example.footballmanager.model.User;
import org.example.footballmanager.repository.CompetitionEntryRepository;
import org.example.footballmanager.repository.UserRepository;
import org.example.footballmanager.service.RegistrationService;
import org.example.footballmanager.service.SeasonService;
import org.example.footballmanager.util.JwtUtil;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth")
public class UserController {

    private final UserRepository userRepo;
    private final AuthenticationManager authManager;
    private final JwtUtil jwtUtil;
    private final SeasonService seasonService;
    private final CompetitionEntryRepository competitionEntryRepository;
    private final RegistrationService registrationService;

    public UserController(UserRepository userRepo, AuthenticationManager authManager,
                          JwtUtil jwtUtil, SeasonService seasonService,
                          CompetitionEntryRepository competitionEntryRepository,
                          RegistrationService registrationService) {
        this.userRepo = userRepo;
        this.authManager = authManager;
        this.jwtUtil = jwtUtil;
        this.seasonService = seasonService;
        this.competitionEntryRepository = competitionEntryRepository;
        this.registrationService = registrationService;
    }

    @PostMapping("/register")
    public ResponseEntity<RegisterResponseDTO> register(@RequestBody RegisterRequestDTO dto) {
        try {
            RegistrationRequest request = registrationService.createPendingRequest(dto);
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(new RegisterResponseDTO(
                    "PENDING_APPROVAL",
                    "Registration request sent. Reserved club: " + request.getTeam().getName() + ". Owner approval is required before login.",
                    request.getTeam().getId(),
                    request.getTeam().getName()
            ));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new RegisterResponseDTO("INVALID_REQUEST", ex.getMessage(), null, null));
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new RegisterResponseDTO("NO_FREE_TEAMS", ex.getMessage(), null, null));
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
        //System.out.println(" /me pozvan, user = " + (user != null ? user.getUsername() : "NULL"));
        if (user == null) {
            return ResponseEntity.status(401).build();
        }

	        User resolvedUser = userRepo.findById(user.getId()).orElse(user);

        int activeSeasonYear = seasonService.getActiveSeasonYear();

        UserDTO dto = new UserDTO();
	        dto.setId(resolvedUser.getId());
	        dto.setUsername(resolvedUser.getUsername());
	        dto.setEmail(resolvedUser.getEmail());
	        dto.setRole(resolvedUser.getRole().name());
	        dto.setSeasonYear(activeSeasonYear);
	        if (resolvedUser.getTeam() != null) {
	            dto.setTeamId(resolvedUser.getTeam().getId());
	            dto.setTeamName(resolvedUser.getTeam().getName());
	            dto.setTeamHumanControlled(resolvedUser.getTeam().isHumanControlled());
	            Country country = resolvedUser.getTeam().getCountry();
	            Competition competition = resolvedUser.getTeam().getCompetition();
            if (competition == null) {
                CompetitionEntry activeEntry = competitionEntryRepository
                        .findFirstByTeamAndSeasonCompetitionSeasonYearOrderByIdDesc(resolvedUser.getTeam(), activeSeasonYear)
                        .orElse(null);
                if (activeEntry != null && activeEntry.getSeasonCompetition() != null) {
                    competition = activeEntry.getSeasonCompetition().getCompetition();
                }
            }
            if (competition != null) {
                dto.setCompetitionId(competition.getId());
                dto.setCompetitionName(competition.getName());
                dto.setCompetitionTier(competition.getTier());
                if (country == null) {
                    country = competition.getCountry();
                }
            }
            if (country != null) {
                dto.setCountryName(country.getName());
                dto.setCountryIsoCode(country.getIsoCode());
            }
        }

        return ResponseEntity.ok(dto);
    }

    @Data
    public class UserDTO {
        private Long id;
        private String username;
        private String email;
        private String role;
        private Long teamId;
        private String teamName;
        private Boolean teamHumanControlled;
        private Long competitionId;
        private String competitionName;
        private Integer competitionTier;
        private String countryName;
        private String countryIsoCode;
        private Integer seasonYear;
    }
}

