package org.example.footballmanager.util;

import jakarta.transaction.Transactional;
import org.example.footballmanager.model.Team;
import org.example.footballmanager.repository.TeamRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class TeamFactory {

    private final TeamRepository teamRepository;

    public TeamFactory(TeamRepository teamRepository) {
        this.teamRepository = teamRepository;
    }

    @Transactional
    public Team findOrCreate(String name) {
        // 1. Pokušaj da dohvatimo po imenu
        Optional<Team> existing = teamRepository.findByName(name);
        if (existing.isPresent()) {
            return existing.get();
        }

        // 2. Ako ne postoji, kreiraj novi
        Team newTeam = new Team();
        newTeam.setName(name);
        newTeam.setBudget(0);
        newTeam.setReputation(50);
        newTeam.setStadium("Unknown");
        newTeam.setCountry("Serbia");

        // ID NE POSTAVLJAJ - neka PostgreSQL auto-generiše
        return teamRepository.save(newTeam);
    }

}
