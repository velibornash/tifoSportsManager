package org.example.footballmanager.newLogic.util;

import org.example.footballmanager.newLogic.model.Country;
import org.example.footballmanager.newLogic.repository.CountryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Puts every country back on the starting rating (owner, 2026-10-06).
 *
 * <h2>What is wrong</h2>
 *
 * <p>The World page showed every country on 1500 except Serbia on <b>50</b> — the one country a manager
 * actually plays, ranked last out of forty-eight, against nations three hundred points or more above
 * it. The World page's own text says every country starts on 1500, so the page was contradicting
 * itself in the same view.
 *
 * <h2>Why Serbia was 50</h2>
 *
 * <p>Two scales collide on the same column name, which AGENTS.md warns about and this is the second
 * time it has bitten. {@code Country.reputation} is a national Elo on a 1500 scale; {@code Team
 * .reputation} is a 0-100 economy number, and {@code TeamFactory} creates clubs at
 * {@code reputation = 50}. A legacy seeding path put Serbia on the club scale, where 50 is an average
 * side rather than the worst team in the world — which is exactly why it looks plausible in the data
 * and wrong on the screen.
 *
 * <h2>What this does, and deliberately does not do</h2>
 *
 * <p>It <b>resets both national columns</b> to {@link WorldCatalogSeeder#STARTING_RATING}: the current
 * value, and the seed the Elo replay starts from. The replay already re-derives the current rating
 * from the match table on every boot, so resetting the seed makes the rating right without touching a
 * single match row.
 *
 * <p><b>It is an admin action, never a boot action.</b> AGENTS.md is explicit that boot writes nothing.
 * A boot listener that rewrote forty-eight countries' ratings would quietly discard real results every
 * time the app restarted — the ratings would be rebuilt by the replay a moment later, so the damage
 * would be invisible and permanent.
 *
 * <p>Idempotent: a country already at 1500 is counted and skipped, so pressing it twice changes
 * nothing and the log says how many actually moved.
 */
@Component
public class NationalRatingResetBackfill {

    private static final Logger log = LoggerFactory.getLogger(NationalRatingResetBackfill.class);

    /** What one run did. Returned so an admin screen can show it rather than only logging it. */
    public record Result(int countries, int moved, int alreadyCorrect) {
    }

    private final CountryRepository countries;

    public NationalRatingResetBackfill(CountryRepository countries) {
        this.countries = countries;
    }

    /**
     * Resets every country's senior and U-21 national rating to the starting rating.
     *
     * <p>Both columns, because they are two independent Elo tracks and a country 50 in one of them
     * and 1500 in the other is the same defect twice.
     */
    @Transactional
    public Result resetAll() {
        int start = WorldCatalogSeeder.STARTING_RATING;
        List<Country> changed = new ArrayList<>();
        int already = 0;

        for (Country country : countries.findAll()) {
            boolean needsWork = country.getReputation() == null || country.getReputation() != start
                    || country.getYouthRating() == null || country.getYouthRating() != start;
            if (!needsWork) {
                already++;
                continue;
            }
            long before = country.getReputation() == null ? -1L : country.getReputation();
            country.setReputation(start);
            country.setYouthRating(start);
            changed.add(country);
            log.info("{} national rating was {}, now {}.", country.getName(), before, start);
        }

        if (!changed.isEmpty()) {
            countries.saveAll(changed);
        }
        Result result = new Result(countries.findAll().size(), changed.size(), already);
        log.info("National ratings reset: {} country/countries, {} moved to {}, {} already correct.",
                result.countries(), result.moved(), start, result.alreadyCorrect());
        return result;
    }

    /**
     * The countries whose rating is not the starting rating, for a screen that wants to show the
     * problem before it is fixed.
     *
     * @return the names and the two ratings, worst first
     */
    @Transactional(readOnly = true)
    public List<String> offenders() {
        List<String> out = new ArrayList<>();
        for (Country country : countries.findAll()) {
            if (country.getReputation() != null && country.getReputation() != WorldCatalogSeeder.STARTING_RATING) {
                out.add(country.getName() + ": senior " + country.getReputation()
                        + ", u21 " + country.getYouthRating());
            }
        }
        return out;
    }
}