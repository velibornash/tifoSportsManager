package org.example.commonmanager.controller;

import lombok.Data;
import org.example.commonmanager.dto.JwtResponseDTO;
import org.example.commonmanager.dto.LoginRequestDTO;
import org.example.commonmanager.model.User;
import org.example.commonmanager.repository.UserRepository;
import org.example.commonmanager.util.JwtUtil;
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

    public UserController(UserRepository userRepo, AuthenticationManager authManager, JwtUtil jwtUtil) {
        this.userRepo = userRepo;
        this.authManager = authManager;
        this.jwtUtil = jwtUtil;
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
        private Long tifoTeamId;
        private String tifoTeamName;
        private Long basketballTeamId;
        private String basketballTeamName;
        private Long americanFootballTeamId;
        private String americanFootballTeamName;
    }
}