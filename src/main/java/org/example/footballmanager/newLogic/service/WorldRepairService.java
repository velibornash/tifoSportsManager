package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.repository.CountryRepository;
import org.example.footballmanager.newLogic.util.CupFixtureSeeder;
import org.example.footballmanager.newLogic.util.InternationalClubCups;
import org.example.footballmanager.newLogic.util.NationalTeamSeeder;
import org.example.footballmanager.newLogic.util.SimulatedWorldSeeder;
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
    private final InternationalClubCups internationalClubCups;
    private final SimulatedWorldSeeder simulatedWorldSeeder;
    private final SeasonService seasons;
    private final CountryRepository countries;

    public WorldRepairService(NationalTeamSeeder nationalTeamSeeder, CupFixtureSeeder cupFixtureSeeder,
                              InternationalClubCups internationalClubCups,
                              SimulatedWorldSeeder simulatedWorldSeeder,
                              SeasonService seasons,
                              CountryRepository countries) {
        this.nationalTeamSeeder = nationalTeamSeeder;
        this.cupFixtureSeeder = cupFixtureSeeder;
        this.internationalClubCups = internationalClubCups;
        this.simulatedWorldSeeder = simulatedWorldSeeder;
        this.seasons = seasons;
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
            case "international-cups", "club-cups" -> {
                int season = seasons.getActiveSeasonYear();
                int qualifyingSeason = Math.max(1, season - 1);
                // The rows are needed by the World and cup pages immediately. Commit this small,
                // durable boundary before the expensive static-world seed, which may touch roughly
                // 14,000 simulated clubs and must not hide the competition rows until it finishes.
                int competitions = internationalClubCups.ensureCompetitionsDurably().size();
                SimulatedWorldSeeder.Summary qualifyingWorld = simulatedWorldSeeder.seedAllSimulated(qualifyingSeason);
                SimulatedWorldSeeder.Summary currentWorld = simulatedWorldSeeder.seedAllSimulated(season);
                out.put("action", "International club cups repaired");
                out.put("competitionRows", competitions);
                out.put("simulatedCountries", Math.max(qualifyingWorld.countries(), currentWorld.countries()));
                out.put("simulatedClubs", Math.max(qualifyingWorld.clubs(), currentWorld.clubs()));
                out.put("qualifyingSeason", qualifyingSeason);
                out.put("note", "Run the club-cup draw job in week 1 to create this season's fixtures.");
            }
            case "all" -> {
                nationalTeamSeeder.seedIfMissing(countries.findAll());
                cupFixtureSeeder.seedIfMissing();
                out.put("nationalSides", nationalTeamSeeder.totalSides());
                out.put("action", "Re-seeded national teams and re-drew the cup");
            }
            default -> {
                out.put("action", "Nothing done");
                out.put("error", "Unknown action '" + what + "'. Use national-teams, cup, international-cups or all.");
            }
        }
        return out;
    }
}
