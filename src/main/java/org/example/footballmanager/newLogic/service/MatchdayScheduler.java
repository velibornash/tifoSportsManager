package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.Match;
import org.example.footballmanager.newLogic.model.MatchFixture;
import org.example.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.example.footballmanager.newLogic.repository.MatchRepository;
import org.example.footballmanager.newLogic.sim.SimMatchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Computes a matchday ahead of kickoff and releases it at kickoff (owner, 2026-10-11).
 *
 * <p>Two states a fixture can be in that it could not be before, and both are about the deadline:
 *
 * <ul>
 *   <li><b>prepared</b> — computed, stored, {@code played == false}, with the match id on the fixture.
 *       The manager can no longer change anything about this match, and nothing about the result is
 *       readable. This state exists for exactly one hour.</li>
 *   <li><b>published</b> — {@code played == true}, pointing at the match computed an hour earlier.</li>
 * </ul>
 *
 * <p>The two are separated because a deadline that is not enforced is worse than none: a manager who
 * is told his lineup is locked and then changes it has been lied to, and he will do it again the next
 * time. Freezing the tactics an hour before kickoff while still deciding the football at kickoff would
 * be exactly that lie.
 *
 * <p><b>Publishing never re-simulates.</b> The football was done in the hour before. Re-running it here
 * would mean the result a manager sees is not the result he locked his team into.
 */
@Service
public class MatchdayScheduler {

    private static final Logger log = LoggerFactory.getLogger(MatchdayScheduler.class);

    private final MatchFixtureRepository fixtures;
    private final MatchRepository matches;
    private final SimMatchService sim;

    /**
     * One transaction per fixture, escaping the one the job framework wraps around a whole job.
     *
     * <p>{@code JobRunner} runs every job inside a single {@code REQUIRES_NEW} transaction, which is
     * right for a job that either happens or does not. A matchday is not that: it is 155 independent
     * matches, and holding them all in one transaction meant match ids ran to 918 while the table
     * still ended at 743 — twenty minutes of football, none of it committed, and one bad fixture at
     * the end losing the whole evening.
     *
     * <p><b>The fixture is loaded inside it, and so is everything the fixture reaches.</b> Not an
     * optimisation question but the whole bug: the day's fixtures were fetched once, outside any
     * session, and their {@code Team} references are lazy proxies bound to a persistence context that
     * has since closed. Opening a transaction afterwards does not help — a new context does not revive
     * a proxy owned by a dead one — so every fixture failed with *"could not initialize proxy Team#128
     * - no Session"*, 775 at a time, and the job was recorded as a successful matchday.
     *
     * <p>So the scheduler works on <b>ids</b> and re-reads each fixture inside its own transaction,
     * where the fixture, its two teams, their squads and their players all belong to a session that is
     * open for as long as the football takes. Which is the old rule again: a job can be registered,
     * scheduled, logged as run, and have failed on every single fixture, and only the data catches it.
     */
    private final TransactionTemplate perFixture;

    /**
     * What a matchday pass actually did, and what it could not do.
     *
     * <p>The counts are here because {@code JobRunner} marks a job DONE when its body returns
     * normally, and for a matchday "returned normally" is not the same as "played". A run with the
     * lazy-loading fault prepared **0 of 155** fixtures, logged 155 warnings and was recorded as a
     * successful matchday — which is how a broken world looks exactly like a working one, from the
     * table you would check to see whether it worked.
     *
     * @param prepared fixtures computed this pass
     * @param failed fixtures that existed and could not be prepared or published
     */
    public record MatchdayOutcome(int prepared, int failed) {
        /** A pass that hit a fixture it could not handle must not be recorded as a clean matchday. */
        public boolean complete() {
            return failed == 0;
        }
    }

    public MatchdayScheduler(MatchFixtureRepository fixtures, MatchRepository matches, SimMatchService sim,
                             PlatformTransactionManager transactionManager) {
        this.fixtures = fixtures;
        this.matches = matches;
        this.sim = sim;
        this.perFixture = new TransactionTemplate(transactionManager);
        this.perFixture.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Computes every fixture of a matchday and locks it. Returns how many were prepared.
     *
     * <p>Fixtures that already carry a prepared match are skipped, so a second run of this job in the
     * same hour cannot compute them twice and leave the manager locked into a result nobody has seen.
     *
     * <p><b>No transaction around the loop, on purpose.</b> The first version carried
     * {@code @Transactional} on this method, and a matchday took twenty minutes to commit nothing:
     * match ids ran past 800 while the table still ended at 743, because one transaction held 155
     * matches and a single failure at the end would have thrown the whole matchday away. Each match
     * commits itself through {@code SimMatchService}, so a fixture that fails costs that fixture and
     * not the evening.
     */
    public MatchdayOutcome prepareUnplayedOn(int seasonYear, int week, int day,
                                             List<Long> fixtureIds) {
        int prepared = 0;
        int failed = 0;
        for (Long fixtureId : fixtureIds) {
            if (fixtureId == null) {
                continue;
            }
            try {
                Long[] matchId = new Long[1];
                perFixture.executeWithoutResult(status -> {
                    MatchFixture fixture = loadForSimulation(fixtureId);
                    // Already computed, so already locked. Simulating it again would leave the
                    // manager locked into a result nobody has been shown.
                    if (fixture == null || fixture.getPreparedMatchId() != null) {
                        return;
                    }
                    var outcome = sim.simulate(fixture, true);
                    if (outcome != null) {
                        matchId[0] = sim.prepareMatch(fixture, outcome.outcome(), outcome.replayId(),
                                outcome.snapshots());
                    }
                });
                if (matchId[0] != null) {
                    prepared++;
                }
            } catch (RuntimeException e) {
                // One fixture failing must not lose the whole matchday, and must not stop the ones
                // after it. It must also not be forgotten: a fixture that failed is a fixture the
                // manager will not see at kickoff, so the pass reports it and the job is recorded as
                // FAILED rather than DONE.
                failed++;
                if (failed == 1) {
                    // The first failure carries its stack; the other 154 carry a line each. A matchday
                    // that loses every fixture to the same fault used to produce 155 identical one-line
                    // warnings and nothing that said where in the simulation it happened.
                    log.warn("Could not prepare fixture {} for week {} day {}: {}",
                            fixtureId, week, day, e.getMessage(), e);
                } else {
                    log.warn("Could not prepare fixture {} for week {} day {}: {}",
                            fixtureId, week, day, e.getMessage());
                }
            }
        }
        return new MatchdayOutcome(prepared, failed);
    }

    /**
     * Releases a prepared matchday, and computes anything that was missed.
     *
     * <p>The fallback matters: if the app was down for the hour before kickoff - which is the hour the
     * synchroniser's catch-up window covers, but not a guarantee - the matchday must still happen at
     * kickoff. Doing it here is exactly what the game did before the split, and it is late rather than
     * lost.
     *
     * <p>Like the prepare path, this is not one transaction: publishing must be able to show a
     * manager his result while the rest of the matchday is still coming out of the ground.
     */
    public MatchdayOutcome publishUnplayedOn(int seasonYear, int week, int day,
                                             List<Long> fixtureIds) {
        int published = 0;
        int failed = 0;
        for (Long fixtureId : fixtureIds) {
            if (fixtureId == null) {
                continue;
            }
            try {
                // The whole publish decision happens inside one transaction. Reading the fixture,
                // reading its prepared match and saving the fixture are three steps over two entities
                // that reference each other; split them across transactions and the match arrives
                // detached with proxies no session can revive, which is the failure this file has now
                // produced twice.
                Boolean[] released = new Boolean[]{false};
                Long[] gone = new Long[]{null};

                perFixture.executeWithoutResult(status -> {
                    MatchFixture fixture = loadForSimulation(fixtureId);
                    if (fixture == null || fixture.isPlayed()) {
                        return;
                    }
                    if (fixture.getPreparedMatchId() != null) {
                        Match match = matches.findById(fixture.getPreparedMatchId()).orElse(null);
                        if (match != null) {
                            fixture.setPlayedMatch(match);
                            fixture.setPlayed(true);
                            fixtures.save(fixture);
                            released[0] = true;
                            return;
                        }
                        // The prepared row is gone. Fall through and compute it now rather than
                        // publish a fixture that points at nothing.
                        gone[0] = fixture.getPreparedMatchId();
                    }
                    // Simulated here for the same reason as the prepare path: the fixture's teams are
                    // lazy proxies and there is no session without one.
                    var outcome = sim.simulate(fixture, true);
                    if (outcome != null) {
                        sim.persist(fixture, outcome.outcome(), outcome.replayId(), outcome.snapshots());
                        released[0] = true;
                    }
                });

                if (gone[0] != null) {
                    log.warn("Fixture {} was prepared into match {} and that match is gone; "
                            + "computing it at kickoff instead.", fixtureId, gone[0]);
                }
                if (Boolean.TRUE.equals(released[0])) {
                    published++;
                }
            } catch (RuntimeException e) {
                failed++;
                log.warn("Could not publish fixture {} for week {} day {}: {}",
                        fixtureId, week, day, e.getMessage());
            }
        }
        return new MatchdayOutcome(published, failed);
    }

    /**
     * Re-reads a fixture inside the caller's transaction, skipping one that no longer needs work.
     *
     * <p>Must be called from within {@link #perFixture}. A fixture read outside it would hand the
     * simulation proxies that can never be initialised.
     */
    private MatchFixture loadForSimulation(Long fixtureId) {
        MatchFixture fixture = fixtures.findById(fixtureId).orElse(null);
        if (fixture == null || fixture.isPlayed()) {
            return null;
        }
        return fixture;
    }

    /** Whether a fixture is computed but not yet shown. The hour before kickoff. */
    public boolean isPrepared(MatchFixture fixture) {
        return fixture != null && fixture.isPrepared();
    }

    /** When a fixture was computed, for the "your match is closed" message. */
    public LocalDateTime preparedAt(MatchFixture fixture) {
        return fixture == null ? null : fixture.getPreparedAt();
    }
}
