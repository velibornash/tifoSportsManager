package org.example.footballmanager.util;

import jakarta.transaction.Transactional;
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
            return existing.get();
        }

        // 🔹 Kreiraj novi tim
        Team newTeam = new Team();
        newTeam.setName(name);
        newTeam.setBudget(0.0);
        newTeam.setReputation(50.0);
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
