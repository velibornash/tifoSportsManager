package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.Country;
import org.example.footballmanager.newLogic.model.CountryCatalog;
import org.example.footballmanager.newLogic.repository.CountryRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.example.footballmanager.newLogic.util.NationalTeamSeeder;
import org.example.footballmanager.newLogic.util.WorldCatalogSeeder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Checks the world is whole, and puts it back if it is not (owner, 2026-09-29).
 *
 * <p>This exists because a reset interrupted part-way left a world that looked plausible and was not:
 * 310 clubs and 2,790 fixtures from the old bootstrap, nine countries still carrying legacy ISO codes
 * ({@code DEU}, {@code GBR}, {@code HRV}), and <b>zero</b> national teams — because the catalogue and
 * national-team seeding had not been reached yet. Nothing reported that. The world was simply a
 * different game, and there was no obvious way back.
 *
 * <p><b>Everything here is idempotent</b>, so it is safe to run on every boot and safe to run twice by
 * hand. The principle is that the world converges rather than depending on a run completing: a
 * process killed at any point is repaired by the next start, instead of leaving a half-built world
 * that nobody notices.
 */
@Service
public class WorldIntegrityService {

    private static final Logger log = LoggerFactory.getLogger(WorldIntegrityService.class);

    private final CountryRepository countries;
    private final TeamRepository teams;
    private final org.example.footballmanager.newLogic.repository.PlayerRepository players;
    private final WorldCatalogSeeder catalogue;
    private final NationalTeamSeeder nationalTeams;

    public WorldIntegrityService(CountryRepository countries, TeamRepository teams,
                                 org.example.footballmanager.newLogic.repository.PlayerRepository players,
                                 WorldCatalogSeeder catalogue, NationalTeamSeeder nationalTeams) {
        this.countries = countries;
        this.teams = teams;
        this.players = players;
        this.catalogue = catalogue;
        this.nationalTeams = nationalTeams;
    }

    /**
     * What the world looks like right now, and whether that is what it should be.
     *
     * <p>Reports rather than fixes, so it can be asked the question without changing anything.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> report() {
        List<Country> all = countries.findAll();
        int expectedCountries = CountryCatalog.all().size();
        int seniorSides = 0;
        int seniorSidesWithSquad = 0;
        for (Country country : all) {
            if (country.getSeniorNationalTeam() == null) {
                continue;
            }
            seniorSides++;
            if (!players.findByTeamId(country.getSeniorNationalTeam().getId()).isEmpty()) {
                seniorSidesWithSquad++;
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("countries", all.size());
        out.put("expectedCountries", expectedCountries);
        out.put("nationalSides", teams.findByType(org.example.footballmanager.newLogic.model.CompetitionTeamType.NATIONAL_TEAM).size());
        out.put("seniorSides", seniorSides);
        out.put("seniorSidesWithSquad", seniorSidesWithSquad);
        out.put("clubs", teams.findClubTeamsForOperations().size());
        out.put("legacyRows", legacyRows());
        out.put("healthy", all.size() == expectedCountries
                && seniorSides == expectedCountries
                && seniorSidesWithSquad == expectedCountries
                && legacyRows().isEmpty());
        return out;
    }

    private List<String> legacyRows() {
        return countries.findAll().stream()
                .map(Country::getIsoCode)
                .filter(code -> code != null && WorldCatalogSeeder.LEGACY_ISO_CODES.contains(code))
                .toList();
    }

    /**
     * Brings the world to the state it should be in.
     *
     * <p>Runs the same steps boot does, in the same order, so a repair and a normal start converge on
     * the same result. Safe to call repeatedly and safe to call on a healthy world.
     */
    @Transactional
    public Map<String, Object> repair() {
        Map<String, Object> before = report();
        log.info("World integrity check before repair: {}", before);
        catalogue.seedAll();
        nationalTeams.seedIfMissing(countries.findAll());
        Map<String, Object> after = report();
        log.info("World integrity check after repair: {}", after);
        return after;
    }
}
