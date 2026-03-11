package org.example.footballmanager.controller;

import lombok.Data;
import org.example.footballmanager.dto.JwtResponseDTO;
import org.example.footballmanager.dto.LoginRequestDTO;
import org.example.footballmanager.dto.RegisterRequestDTO;
import org.example.footballmanager.model.Competition;
import org.example.footballmanager.model.CompetitionEntry;
import org.example.footballmanager.model.Country;
import org.example.footballmanager.model.Team;
import org.example.footballmanager.model.User;
import org.example.footballmanager.model.UserRole;
import org.example.footballmanager.repository.CompetitionEntryRepository;
import org.example.footballmanager.repository.UserRepository;
import org.example.footballmanager.service.SeasonService;
import org.example.footballmanager.util.JwtUtil;
import org.example.footballmanager.util.teams.TeamFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth")
public class UserController {

    private final UserRepository userRepo;
    private final PasswordEncoder encoder;
    private final TeamFactory teamFactory;
    private final AuthenticationManager authManager;
    private final JwtUtil jwtUtil;
    private final SeasonService seasonService;
    private final CompetitionEntryRepository competitionEntryRepository;

    public UserController(UserRepository userRepo, PasswordEncoder encoder, TeamFactory teamFactory,
                          AuthenticationManager authManager, JwtUtil jwtUtil, SeasonService seasonService,
                          CompetitionEntryRepository competitionEntryRepository) {
        this.userRepo = userRepo;
        this.encoder = encoder;
        this.teamFactory = teamFactory;
        this.authManager = authManager;
        this.jwtUtil = jwtUtil;
        this.seasonService = seasonService;
        this.competitionEntryRepository = competitionEntryRepository;
    }

    @PostMapping("/register")
    public ResponseEntity<JwtResponseDTO> register(@RequestBody RegisterRequestDTO dto) {
        if (userRepo.findByEmail(dto.getEmail()).isPresent()) {
            return ResponseEntity.badRequest().body(null);
        }

        User user = new User();
        user.setUsername(dto.getUsername());
        user.setEmail(dto.getEmail());
        user.setPassword(encoder.encode(dto.getPassword()));
        user.setRole(UserRole.REGULAR); // Default; Owner-a ručno

        // Kreiraj tim
        Team team = teamFactory.findOrCreate(dto.getUsername() + "'s Team");
        user.setTeam(team);

        userRepo.save(user);

        // Automatski login posle register-a
        String token = jwtUtil.generateToken(user);
        return ResponseEntity.ok(new JwtResponseDTO(token));
    }

    @PostMapping("/login")
    public ResponseEntity<JwtResponseDTO> login(@RequestBody LoginRequestDTO dto) {
        authManager.authenticate(new UsernamePasswordAuthenticationToken(dto.getUsername(), dto.getPassword()));

        User user = userRepo.findByUsername(dto.getUsername()).orElseThrow();
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
        private Long competitionId;
        private String competitionName;
        private Integer competitionTier;
        private String countryName;
        private String countryIsoCode;
        private Integer seasonYear;
    }
}

