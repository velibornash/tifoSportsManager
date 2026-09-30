package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.CompetitionType;
import org.example.footballmanager.newLogic.model.Country;
import org.example.footballmanager.newLogic.model.CountryState;
import org.example.footballmanager.newLogic.repository.CompetitionRepository;
import org.example.footballmanager.newLogic.repository.CountryRepository;
import org.example.footballmanager.newLogic.util.PyramidBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turning a country from represented into played (owner, 2026-09-30).
 *
 * <p>{@code CountryState} was set once for Serbia by the catalogue seeder, styled on the World page, and
 * read by nothing else. So the panel the owner asked for would have been a switch for a label. A country
 * is ACTIVE when it has football in it, and this is what puts the football there.
 *
 * <h2>Why the flag is written last</h2>
 *
 * <p>The state is flipped <b>after</b> the pyramid, never before. If the flag went first and the build
 * then failed — halfway through 7,750 player rows — the world would hold a country marked ACTIVE with
 * a third of a pyramid in it, and every reader would believe it was playable. The flag is a claim about
 * the rest of the transaction having succeeded, so it has to be the last thing written.
 */
@Service
public class CountryActivationService {

    private static final Logger log = LoggerFactory.getLogger(CountryActivationService.class);

    private final CountryRepository countries;
    private final CompetitionRepository competitions;
    private final PyramidBuilder pyramids;
    private final SeasonService seasons;

    public CountryActivationService(CountryRepository countries,
                                    CompetitionRepository competitions,
                                    PyramidBuilder pyramids,
                                    SeasonService seasons) {
        this.countries = countries;
        this.competitions = competitions;
        this.pyramids = pyramids;
        this.seasons = seasons;
    }

    /** What activating a country produced, for the panel to show rather than "done". */
    public record Result(String isoCode,
                         String name,
                         CountryState state,
                         int divisions,
                         int clubs,
                         boolean alreadyBuilt) {
    }

    public Country find(String isoCode) {
        return countries.findByIsoCode(isoCode == null ? null : isoCode.trim().toUpperCase())
                .orElseThrow(() -> new IllegalArgumentException("No country with the code " + isoCode));
    }

    /**
     * Gives a country its pyramid and marks it active.
     *
     * <p>Idempotent. A second call finds the divisions the first one built and returns without adding a
     * second pyramid — the guard asks the database rather than trusting the state flag, because a
     * country can hold a pyramid and still be SIMULATED if a previous activation was interrupted.
     */
    @Transactional
    public Result activate(String isoCode) {
        Country country = find(isoCode);
        int seasonYear = seasons.getActiveSeasonYear();

        PyramidBuilder.Result built = pyramids.build(country, seasonYear);
        if (built.alreadyBuilt()) {
            // The divisions exist, so the only thing possibly missing is the flag. That is the state a
            // half-finished activation leaves behind, and this is where it heals.
            if (country.getState() != CountryState.ACTIVE) {
                country.setState(CountryState.ACTIVE);
                countries.save(country);
                log.info("{} already had a pyramid; marked it ACTIVE.", country.getName());
            }
            return new Result(country.getIsoCode(), country.getName(), CountryState.ACTIVE,
                    built.divisions(), built.clubs(), true);
        }

        // Last. See the class comment: a country marked active without a pyramid is a broken world.
        country.setState(CountryState.ACTIVE);
        countries.save(country);
        log.info("Activated {}: {} division(s), {} club(s).", country.getName(), built.divisions(), built.clubs());
        return new Result(country.getIsoCode(), country.getName(), CountryState.ACTIVE,
                built.divisions(), built.clubs(), false);
    }

    /** What the panel lists: every country, its state, and whether it holds football. */
    public record CountryRow(String isoCode, String name, String state, int divisions, boolean active) {
    }

    @Transactional(readOnly = true)
    public java.util.List<CountryRow> overview() {
        return countries.findAll().stream()
                .map(country -> {
                    int divisions = competitions
                            .findByCountryIsoCodeAndType(country.getIsoCode(), CompetitionType.LEAGUE)
                            .size();
                    return new CountryRow(country.getIsoCode(), country.getName(),
                            country.getState() == null ? CountryState.SIMULATED.name() : country.getState().name(),
                            divisions, country.getState() == CountryState.ACTIVE);
                })
                .sorted(java.util.Comparator.comparing(CountryRow::name))
                .toList();
    }
}
