package org.example.footballmanager.util;

import org.example.footballmanager.model.Team;
import org.example.footballmanager.repository.TeamRepository;
import org.springframework.stereotype.Component;

@Component
public class TeamFactory {

    private final TeamRepository teamRepository;

    public TeamFactory(TeamRepository teamRepository) {
        this.teamRepository = teamRepository;
    }

    public Team findOrCreate(String name) {

        return teamRepository.findByName(name)
                .orElseGet(() -> {
                    Team team = new Team();
                    team.setName(name);

                    Team saved = teamRepository.save(team);
                    System.out.println("→ Kreiran novi tim: " + name);
                    return saved;
                });
    }
}
