package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.repository.CountryRepository;
import org.example.footballmanager.newLogic.util.CupFixtureSeeder;
import org.example.footballmanager.newLogic.util.NationalTeamSeeder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The repairs an admin reaches for when the world is wrong but a season is in progress (owner, 2026-09-29).
 *
 * <p>Deliberately separate from {@link WorldIntegrityService}. That one verifies and repairs the
 * whole world and runs on every boot; this one runs a single named step on demand, so an admin can
 * fix one thing without a reset that would cost them the season.
 *
 * <p>Every step is idempotent. Re-seeding tops up what is missing rather than replacing what exists,
 * and the cup draw skips rounds that already have ties, so running these twice is harmless.
 */
@Service
public class WorldRepairService {

    private final NationalTeamSeeder nationalTeamSeeder;
    private final CupFixtureSeeder cupFixtureSeeder;
    private final CountryRepository countries;

    public WorldRepairService(NationalTeamSeeder nationalTeamSeeder, CupFixtureSeeder cupFixtureSeeder,
                              CountryRepository countries) {
        this.nationalTeamSeeder = nationalTeamSeeder;
        this.cupFixtureSeeder = cupFixtureSeeder;
        this.countries = countries;
    }

    @Transactional
    public Map<String, Object> repair(String what) {
        Map<String, Object> out = new LinkedHashMap<>();
        String step = what == null ? "" : what.trim().toLowerCase();
        switch (step) {
            case "national-teams" -> {
                nationalTeamSeeder.seedIfMissing(countries.findAll());
                out.put("action", "Re-seeded national teams");
                out.put("nationalSides", nationalTeamSeeder.totalSides());
            }
            case "cup" -> {
                cupFixtureSeeder.seedIfMissing();
                out.put("action", "Re-drew the cup");
                out.put("note", "Rounds that already had ties were left alone.");
            }
            case "all" -> {
                nationalTeamSeeder.seedIfMissing(countries.findAll());
                cupFixtureSeeder.seedIfMissing();
                out.put("nationalSides", nationalTeamSeeder.totalSides());
                out.put("action", "Re-seeded national teams and re-drew the cup");
            }
            default -> {
                out.put("action", "Nothing done");
                out.put("error", "Unknown action '" + what + "'. Use national-teams, cup or all.");
            }
        }
        return out;
    }
}
