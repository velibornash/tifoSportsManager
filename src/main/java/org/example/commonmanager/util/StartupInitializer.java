package org.example.commonmanager.util;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.commonmanager.model.User;
import org.example.commonmanager.model.UserRole;
import org.example.commonmanager.repository.UserRepository;
import org.example.footballtextmanager.model.CTeam;
import org.example.footballtextmanager.repository.CSTeamRepository;
import org.example.basketballmanager.model.BbTeam;
import org.example.basketballmanager.repository.BbTeamRepository;
import org.example.americanfootballmanager.model.AfTeam;
import org.example.americanfootballmanager.repository.AfTeamRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class StartupInitializer implements org.springframework.boot.CommandLineRunner {

    private final UserRepository userRepository;
    private final org.example.footballtextmanager.repository.CSTeamRepository csTeamRepository;
    private final org.example.basketballmanager.repository.BbTeamRepository bbTeamRepository;
    private final org.example.americanfootballmanager.repository.AfTeamRepository afTeamRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.owner.username:velibor@example.com}")
    private String ownerUsername;

    @Value("${app.owner.email:velibor@example.com}")
    private String ownerEmail;

    @Value("${app.owner.password:A12345!}")
    private String ownerPassword;

    @Value("${app.owner.football-team:OFK Omladinac}")
    private String footballTeamName;

    @Value("${app.owner.basketball-team:KK Omladinac}")
    private String basketballTeamName;

    @Value("${app.owner.american-football-team:Omladinac}")
    private String americanFootballTeamName;

    @Override
    @Transactional
    public void run(String... args) {
        log.info("Initializing owner user...");

        Optional<org.example.commonmanager.model.User> existingOwner = userRepository.findByUsernameOrEmail(ownerEmail);

        if (existingOwner.isEmpty()) {
            createOwnerUser();
        } else {
            updateExistingOwner(existingOwner.get());
        }
    }

    private void createOwnerUser() {
        log.info("Creating owner user: {}", ownerUsername);

        org.example.footballtextmanager.model.CTeam footballTeam = csTeamRepository.findByName(footballTeamName)
                .orElseGet(() -> {
                    log.warn("Football team '{}' not found, creating placeholder", footballTeamName);
                    org.example.footballtextmanager.model.CTeam team = new org.example.footballtextmanager.model.CTeam();
                    team.setName(footballTeamName);
                    return csTeamRepository.save(team);
                });

        org.example.basketballmanager.model.BbTeam basketballTeam = null;
        if (!basketballTeamName.isBlank()) {
            basketballTeam = bbTeamRepository.findByName(basketballTeamName)
                    .orElseGet(() -> {
                        log.warn("Basketball team '{}' not found", basketballTeamName);
                        return null;
                    });
        } else {
            basketballTeam = bbTeamRepository.findAll().stream().findFirst().orElse(null);
        }

        org.example.americanfootballmanager.model.AfTeam americanFootballTeam = null;
        if (!americanFootballTeamName.isBlank()) {
            americanFootballTeam = afTeamRepository.findByName(americanFootballTeamName)
                    .orElseGet(() -> {
                        log.warn("American Football team '{}' not found", americanFootballTeamName);
                        return null;
                    });
        } else {
            americanFootballTeam = afTeamRepository.findAll().stream().findFirst().orElse(null);
        }

        org.example.commonmanager.model.User owner = new org.example.commonmanager.model.User();
        owner.setUsername(ownerUsername);
        owner.setEmail(ownerEmail);
        owner.setPassword(passwordEncoder.encode(ownerPassword));
        owner.setRole(org.example.commonmanager.model.UserRole.OWNER);
        owner.setCTeam(footballTeam);
        owner.setTifoCTeam(footballTeam);
        owner.setBasketballTeam(basketballTeam);
        owner.setAmericanFootballTeam(americanFootballTeam);

        userRepository.save(owner);
        log.info("Created owner user '{}' with role OWNER", ownerUsername);
    }

    private void updateExistingOwner(org.example.commonmanager.model.User owner) {
        log.info("Owner user '{}' already exists, updating teams/role", ownerUsername);

        org.example.footballtextmanager.model.CTeam footballTeam = csTeamRepository.findByName(footballTeamName)
                .orElseGet(() -> {
                    org.example.footballtextmanager.model.CTeam team = new org.example.footballtextmanager.model.CTeam();
                    team.setName(footballTeamName);
                    return csTeamRepository.save(team);
                });

        org.example.basketballmanager.model.BbTeam basketballTeam = null;
        if (!basketballTeamName.isBlank()) {
            basketballTeam = bbTeamRepository.findByName(basketballTeamName)
                    .orElseGet(() -> bbTeamRepository.findAll().stream().findFirst().orElse(null));
        } else {
            basketballTeam = bbTeamRepository.findAll().stream().findFirst().orElse(null);
        }

        org.example.americanfootballmanager.model.AfTeam americanFootballTeam = null;
        if (!americanFootballTeamName.isBlank()) {
            americanFootballTeam = afTeamRepository.findByName(americanFootballTeamName)
                    .orElseGet(() -> afTeamRepository.findAll().stream().findFirst().orElse(null));
        } else {
            americanFootballTeam = afTeamRepository.findAll().stream().findFirst().orElse(null);
        }

        owner.setCTeam(footballTeam);
        owner.setTifoCTeam(footballTeam);
        owner.setBasketballTeam(basketballTeam);
        owner.setAmericanFootballTeam(americanFootballTeam);
        owner.setRole(org.example.commonmanager.model.UserRole.OWNER);
        owner.setPassword(passwordEncoder.encode(ownerPassword));

        userRepository.save(owner);
        log.info("Updated owner user '{}'", ownerUsername);
    }
}