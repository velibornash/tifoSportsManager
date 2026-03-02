package org.example.footballmanager.controller;

import lombok.Data;
import org.example.footballmanager.dto.JwtResponseDTO;
import org.example.footballmanager.dto.LoginRequestDTO;
import org.example.footballmanager.dto.RegisterRequestDTO;
import org.example.footballmanager.model.Team;
import org.example.footballmanager.model.User;
import org.example.footballmanager.model.UserRole;
import org.example.footballmanager.repository.UserRepository;
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

    public UserController(UserRepository userRepo, PasswordEncoder encoder, TeamFactory teamFactory,
                          AuthenticationManager authManager, JwtUtil jwtUtil) {
        this.userRepo = userRepo;
        this.encoder = encoder;
        this.teamFactory = teamFactory;
        this.authManager = authManager;
        this.jwtUtil = jwtUtil;
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
        System.out.println(" /me pozvan, user = " + (user != null ? user.getUsername() : "NULL"));
        if (user == null) {
            return ResponseEntity.status(401).build();
        }

        UserDTO dto = new UserDTO();
        dto.setId(user.getId());
        dto.setUsername(user.getUsername());
        dto.setEmail(user.getEmail());
        dto.setRole(user.getRole().name());
        if (user.getTeam() != null) {
            dto.setTeamId(user.getTeam().getId());
            dto.setTeamName(user.getTeam().getName());
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
    }
}

