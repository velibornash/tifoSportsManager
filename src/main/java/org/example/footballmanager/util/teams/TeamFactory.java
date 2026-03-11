package org.example.footballmanager.util.teams;

import jakarta.transaction.Transactional;
import org.example.footballmanager.model.CompetitionTeamType;
import org.example.footballmanager.model.Country;
import org.example.footballmanager.model.Stadium;
import org.example.footballmanager.model.Team;
import org.example.footballmanager.repository.CountryRepository;
import org.example.footballmanager.repository.TeamRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class TeamFactory {

    private final TeamRepository teamRepository;
    private final CountryRepository countryRepository;

    public TeamFactory(TeamRepository teamRepository, CountryRepository countryRepository) {
        this.teamRepository = teamRepository;
        this.countryRepository = countryRepository;
    }

    @Transactional
    public Team findOrCreate(String name) {

        // 🔹 Dohvati državu Srbija po ISO kodu
        Country country = countryRepository.findByIsoCode("SRB")
                .orElseGet(() -> {
                    // Ako ne postoji, kreiraj novu
                    Country newCountry = new Country();
                    newCountry.setName("Serbia");
                    newCountry.setIsoCode("SRB");
                    newCountry.setCurrencyCode("RSD");
                    newCountry.setReputation(50);
                    newCountry.setYouthRating(50);
                    return countryRepository.save(newCountry);
                });

        // 🔹 Proveri da li tim već postoji
        Optional<Team> existing = teamRepository.findByName(name);
        if (existing.isPresent()) {
            Team existingTeam = existing.get();
            if (existingTeam.getType() == null) {
                existingTeam.setType(CompetitionTeamType.CLUB);
                return teamRepository.save(existingTeam);
            }
            return existingTeam;
        }

        // 🔹 Kreiraj novi tim
        Team newTeam = new Team();
        newTeam.setName(name);
        newTeam.setType(CompetitionTeamType.CLUB);
        newTeam.setBudget(0.0);
        newTeam.setReputation(50.0);
        newTeam.setJuniorCoachSkill(35 + new java.util.Random().nextInt(51)); // 35-85
        newTeam.setCountry(country);

        // 🔹 Kreiraj stadion
        Stadium stadium = new Stadium();
        stadium.setName(name + " Stadium");
        stadium.setCapacity(5000);
        stadium.setPitchQuality(75.0);

        newTeam.setStadium(stadium);

        // 🔹 Sačuvaj i vrati
        return teamRepository.save(newTeam);
    }
}
