package org.example.footballmanager.newLogic.util;

import org.example.footballmanager.newLogic.model.Country;
import org.example.footballmanager.newLogic.model.CountryCatalog;
import org.example.footballmanager.newLogic.model.CountryState;
import org.example.footballmanager.newLogic.repository.CountryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Makes every country in the catalogue exist, with its state set (owner, 2026-09-29).
 *
 * <p>The owner chose represented-but-unplayed countries over seeding 48 club pyramids up front. So all
 * 48 countries exist, each with a senior side and a U-21, which is enough for the national-team half
 * of the game to work across the whole map. Only Serbia is ACTIVE - a real club pyramid and a
 * simulated season. The rest are SIMULATED and cost nothing until they are activated.
 *
 * <p>This is the fix for the internationals drawing nothing: a country with no clubs used to produce
 * an empty national side, so there was nothing to draw against. With bot squads on all 48, there is.
 *
 * <p>Reputation and youth rating are set from a deterministic spread rather than left null, because a
 * country with no reputation is not a country a scout report can say anything about.
 */
@Component
public class WorldCatalogSeeder {

    private static final Logger log = LoggerFactory.getLogger(WorldCatalogSeeder.class);

    /** The only country played to begin with. */
    private static final String ACTIVE_COUNTRY = "SRB";

    private final CountryRepository countries;

    public WorldCatalogSeeder(CountryRepository countries) {
        this.countries = countries;
    }

    @Transactional
    public int seedAll() {
        List<Country> ensured = new ArrayList<>();
        int created = 0;
        for (CountryCatalog entry : CountryCatalog.all()) {
            if (countries.findByIsoCode(entry.code()).isPresent()) {
                ensured.add(countries.findByIsoCode(entry.code()).orElseThrow());
                continue;
            }
            Country country = new Country();
            country.setName(entry.displayName());
            country.setIsoCode(entry.code());
            // A spread, derived from the code so it is stable across installs. Not real-world ratings -
            // a claim about which country is stronger than which is not something to invent here.
            int spread = Math.floorMod(entry.code().hashCode(), 21) + 40;
            country.setReputation(spread);
            country.setYouthRating(spread);
            country.setState(entry.code().equals(ACTIVE_COUNTRY)
                    ? CountryState.ACTIVE : CountryState.SIMULATED);
            ensured.add(countries.save(country));
            created++;
        }
        // Serbia is the one country played, whatever the catalogue says.
        countries.findByIsoCode(ACTIVE_COUNTRY).ifPresent(serbia -> {
            if (serbia.getState() != CountryState.ACTIVE) {
                serbia.setState(CountryState.ACTIVE);
                countries.save(serbia);
            }
        });
        if (created > 0) {
            log.info("World catalogue: created {} country row(s); {} of {} now exist, {} active.",
                    created, ensured.size(), CountryCatalog.all().size(), 1);
        }
        return ensured.size();
    }
}
