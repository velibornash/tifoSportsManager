package org.tifo.footballmanager.newLogic.util;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.tifo.footballmanager.newLogic.model.Country;
import org.tifo.footballmanager.newLogic.model.CountryState;
import org.tifo.footballmanager.newLogic.repository.CountryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Gives every <b>simulated</b> country a place in the world without giving it a season's worth of work.
 *
 * <p>The world is 48 countries and the international competitions need all 48, so a country that has not
 * been activated still has to have clubs, ratings and a standing position. What it must not do is play.
 *
 * <p>That is the whole distinction between this and {@link PyramidBuilder#build}: this builds the
 * <b>fixture of a league</b> — divisions, clubs, ratings, a table — and stops. No players, no
 * fixtures, no matches. The country holds that table until somebody activates its league, at which
 * point the ordinary builder takes over and it starts simulating its own results.
 *
 * <p>Activation is not a rebuild: it finds the divisions already here and fills in the parts that were
 * skipped. See {@link #seed(Country, int)} for the guard that makes a second call a no-op.
 *
 * <p><b>Not "active" and not "in the world" — keep the two apart.</b> A simulated country is fully in
 * the world and enters the international club cups, and is not a single one of those things the owner
 * can play. Only ACTIVE countries simulate.
 */
@Service
public class SimulatedWorldSeeder {

    private static final Logger log = LoggerFactory.getLogger(SimulatedWorldSeeder.class);

    private final CountryRepository countries;
    private final PyramidBuilder pyramids;

    /**
     * The transaction each country is built in, when the loop drives it.
     *
     * <p>{@link #seed} is annotated {@code REQUIRES_NEW} and the annotation is honoured when an outside
     * caller reaches it through the proxy. It is <em>not</em> honoured when {@link #seedAll} calls it,
     * because a call on {@code this} never passes through a proxy — so on the button's path, which is
     * {@code seedAllSimulated}, all forty-six countries ran in whatever transaction the caller had, and
     * the caller had none. This template is how the loop gets the boundary the annotation describes.
     *
     * <p>Same shape as {@code MatchdayScheduler.perFixture}, for the same reason: one transaction per unit
     * of work, committed as it goes, so a failure costs one country and the rest still build.
     */
    private final TransactionTemplate perCountry;

    private final ExecutorService executor;

    public SimulatedWorldSeeder(CountryRepository countries, PyramidBuilder pyramids,
                                PlatformTransactionManager transactionManager) {
        this.countries = countries;
        this.pyramids = pyramids;
        this.perCountry = new TransactionTemplate(transactionManager);
        this.perCountry.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.executor = Executors.newFixedThreadPool(4);
    }

    @jakarta.annotation.PreDestroy
    public void shutdown() {
        executor.shutdown();
    }

    /**
     * Seeds one simulated country, and does nothing at all to an active one.
     *
     * <p>Refuses active countries outright rather than quietly skipping them: the caller asked for the
     * world's static half, and an active country is not part of it. Passing one in is a bug in the
     * caller and the log should say so.
     *
     * <p><b>{@code REQUIRES_NEW}, and that is the point (owner, 2026-10-08: "break it up and speed it
     * up, keep the functionality").</b> This used to join the caller's transaction, and the caller walks
     * all forty-six countries — so the entire static world was one transaction. Measured on this world:
     * after <b>thirty-seven</b> countries the {@code team} table still read <b>406</b> rows, because
     * nothing commits until the last country finishes. A failure at country 40 threw all of it away and
     * left an empty table and a slow button, with no way to tell how far it had got.
     *
     * <p>One transaction per country: progress is visible while it runs, a failure costs one country and
     * the rest still build, and a second press continues where the first stopped.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PyramidBuilder.Result seed(Country country, int seasonYear) {
        if (country.getState() == CountryState.ACTIVE) {
            log.warn("{} is ACTIVE and already simulates; refusing to give it a static table.",
                    country.getName());
            return new PyramidBuilder.Result(0, 0, 0, true);
        }
        return pyramids.buildStatic(country, seasonYear);
    }

    /**
     * Seeds every simulated country in the world.
     *
     * <p>Idempotent: a country that already has divisions is counted and skipped, so this is safe to
     * call on every boot. That matters because it runs against 46 countries and the alternative is a
     * world that has a duplicate pyramid in it.
     *
     * <p><b>Not transactional on purpose.</b> Each {@link #seed} call is its own transaction, so this
     * loop must not wrap them all in one — see the note there for what that used to cost.
     */
    public Summary seedAllSimulated(int seasonYear) {
        return seedAll(countries.findAll().stream()
                .filter(c -> c.getState() == CountryState.SIMULATED)
                .toList(), seasonYear);
    }

    /**
     * Seeds a given set of countries, already filtered to the ones that want it.
     *
     * <p>The overload exists so the caller can be tested without seeding the planet. The first version
     * of this test called {@link #seedAllSimulated} to prove it skipped active countries, and in doing
     * so built all forty-six — fourteen thousand clubs — to assert one row was left alone.
     */
    public Summary seedAll(List<Country> simulated, int seasonYear) {
        List<Country> toSeed = simulated.stream()
                .filter(c -> c.getState() != CountryState.ACTIVE)
                .toList();

        AtomicInteger divisions = new AtomicInteger(0);
        AtomicInteger clubs = new AtomicInteger(0);
        AtomicInteger built = new AtomicInteger(0);
        AtomicInteger alreadyThere = new AtomicInteger(0);

        // **Parallel, not sequential.** Each country is independent — its own divisions, clubs and
        // ratings — and each runs in its own transaction via perCountry, so a failure costs one
        // country and the rest still build. A pool of 4 keeps DB contention low while cutting the
        // wall time roughly fourfold.
        List<CompletableFuture<Void>> futures = toSeed.stream().map(country ->
                CompletableFuture.runAsync(() -> {
                    PyramidBuilder.Result result = perCountry.execute(status -> seed(country, seasonYear));
                    divisions.addAndGet(result.divisions());
                    clubs.addAndGet(result.clubs());
                    if (result.alreadyBuilt()) {
                        alreadyThere.incrementAndGet();
                    } else {
                        built.incrementAndGet();
                    }
                }, executor)).toList();

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        log.info("Simulated world: {} country/countries seeded ({} already had a pyramid), "
                        + "{} division(s), {} club(s). No players, no fixtures.",
                toSeed.size(), alreadyThere.get(), divisions.get(), clubs.get());
        return new Summary(toSeed.size(), built.get(), alreadyThere.get(), divisions.get(), clubs.get());
    }

    public record Summary(int countries, int built, int alreadyThere, int divisions, int clubs) {
    }
}