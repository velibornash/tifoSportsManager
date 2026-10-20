package org.example.footballmanager;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Builds one country's pyramid for a test, and nothing else.
 *
 * <p><b>The measurement that shaped this.</b> {@code DatabaseInitializer.ensureBaselineDataOnStartup()}
 * would have supplied every one of these fixtures in one call — it is public, idempotent, and builds exactly
 * the world the six tests were written against. It also builds the <b>entire world</b>: tens of thousands of
 * writes, and one test class ran for **thirteen minutes** without finishing. That would have defeated the
 * decision it was meant to serve, so it was reverted rather than shipped.
 *
 * <p>One country's pyramid is **310 clubs over 31 divisions**. That is the difference between a suite that
 * runs and one that does not, and it is why the seam is a single country rather than a world.
 *
 * <p>{@link org.example.footballmanager.newLogic.util.PyramidBuilder#build} returns early when the country already has divisions, so this is safe to
 * call from every {@code @BeforeEach}: the first call builds, the rest are a query.
 */
@TestConfiguration
public class TestPyramid {

    @Bean
    public Builder testPyramid(
            org.example.footballmanager.newLogic.util.PyramidBuilder builder,
            org.example.footballmanager.newLogic.repository.CountryRepository countries,
            org.example.footballmanager.newLogic.service.SeasonService seasons) {
        return new Builder(builder, countries, seasons);
    }

    /** Builds {@code isoCode}'s pyramid in the current season, if it does not already have one. */
    public static class Builder {

        private final org.example.footballmanager.newLogic.util.PyramidBuilder builder;
        private final org.example.footballmanager.newLogic.repository.CountryRepository countries;
        private final org.example.footballmanager.newLogic.service.SeasonService seasons;

        public Builder(org.example.footballmanager.newLogic.util.PyramidBuilder builder,
                           org.example.footballmanager.newLogic.repository.CountryRepository countries,
                           org.example.footballmanager.newLogic.service.SeasonService seasons) {
            this.builder = builder;
            this.countries = countries;
            this.seasons = seasons;
        }

        /**
         * @return how many divisions the country now has
         * @throws IllegalStateException when the catalogue does not carry that country — which is a real
         *     failure and not a fixture problem, and worth saying so rather than returning zero
         */
        public int build(String isoCode) {
            var country = countries.findByIsoCode(isoCode).orElseThrow(() -> new IllegalStateException(
                    "the catalogue has no country " + isoCode + "; TestCountryCatalogue must seed first"));
            return builder.build(country, seasons.getActiveSeasonYear()).divisions();
        }
    }
}
