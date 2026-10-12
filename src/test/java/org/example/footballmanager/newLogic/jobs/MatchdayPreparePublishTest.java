package org.example.footballmanager.newLogic.jobs;

import org.example.footballmanager.newLogic.jobs.impl.MatchdayJob;
import org.example.footballmanager.newLogic.jobs.impl.MatchdayPrepareJob;
import org.example.footballmanager.newLogic.jobs.impl.MatchdayPublishJob;
import org.example.footballmanager.newLogic.model.CompetitionType;
import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.model.GameDay;
import org.example.footballmanager.newLogic.model.NationalTournamentSchedule;
import org.example.footballmanager.newLogic.model.MatchFixture;
import org.example.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.example.footballmanager.newLogic.service.MatchdayScheduler;
import org.example.footballmanager.newLogic.sim.SimMatchService;
import org.example.footballmanager.newLogic.sim.result.ProposalMatchOutcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The hour before kickoff (owner, 2026-10-11).
 *
 * <p>Three claims are being tested, and each is a claim about the deadline rather than about football:
 *
 * <ul>
 *   <li>the prepare job fires an hour before the day's kickoff and the publish job at it, read from the
 *       template so the pair cannot drift;</li>
 *   <li>preparing <b>does not</b> set {@code played} — a computed match the manager cannot see, which is
 *       the entire reason for the split;</li>
 *   <li>publishing <b>does not</b> re-simulate — the football a manager locked into at 19:00 is the
 *       football they are shown at 20:45.</li>
 * </ul>
 */
class MatchdayPreparePublishTest extends org.example.footballmanager.BaseTest {

    @org.springframework.beans.factory.annotation.Autowired
    java.util.List<org.example.footballmanager.newLogic.jobs.DayJob> registeredJobs;


    private static final int DAY = GameDay.INTERNATIONAL_DAY;

    private MatchFixtureRepository fixtures;
    private MatchdayScheduler scheduler;

    /** A no-op transaction manager: the unit tests assert what was called, not what was committed. */
    private static final PlatformTransactionManager NO_TRANSACTIONS = new AbstractPlatformTransactionManager() {
        @Override protected Object doGetTransaction() {
            return new Object();
        }

        @Override protected void doBegin(Object transaction, TransactionDefinition definition) { }

        @Override protected void doCommit(DefaultTransactionStatus status) { }

        @Override protected void doRollback(DefaultTransactionStatus status) { }
    };

    private MatchdayPrepareJob prepareJob() {
        return new MatchdayPrepareJob(DAY, fixtures, scheduler);
    }

    private MatchdayPublishJob publishJob() {
        return new MatchdayPublishJob(DAY, fixtures, scheduler);
    }

    @Test
    @DisplayName("the pair covers every match day the calendar names, club and national alike")
    void everyMatchDayHasAPair() {
        // Written as a property of the injected job list rather than of a class, because the failure
        // this guards against is registration, not behaviour: eight jobs that compiled, were green,
        // and had never run.
        java.util.Map<Integer, java.util.Set<String>> byDay = new java.util.TreeMap<>();
        for (DayJob job : registeredJobs) {
            byDay.computeIfAbsent(job.day(), d -> new java.util.TreeSet<>()).add(job.key());
        }

        for (int day : new int[]{1, 3, 5, 7}) {
            assertThat(byDay.getOrDefault(day, java.util.Set.of()))
                    .as("club matchday %d must have a prepare and a publish", day)
                    .contains("matchday-prepare-" + day, "matchday-publish-" + day);
        }

        // Days 2, 4 and 6 exist only for national football, and only in its two weeks - so they carry
        // a qualifier and a tournament pair, four jobs rather than two.
        for (int day : new int[]{2, 4, 6}) {
            java.util.Set<String> keys = byDay.getOrDefault(day, java.util.Set.of());
            for (String phase : new String[]{"qualifier", "tournament"}) {
                assertThat(keys)
                        .as("national day %d, %s week, must have a prepare and a publish", day, phase)
                        .contains("matchday-prepare-" + phase + "-" + day,
                                "matchday-publish-" + phase + "-" + day);
            }
        }
    }

    @Test
    @DisplayName("the national pair reads the national kickoff, an hour earlier, in its own week")
    void theNationalPairUsesTheNationalCalendar() {
        for (int day : new int[]{2, 4, 6}) {
            int kickoff = NationalTournamentSchedule.kickoffHour(day);
            int week = NationalTournamentSchedule.QUALIFYING_WEEK;

            assertThat(new MatchdayPrepareJob(day, kickoff, week, fixtures, scheduler).hour())
                    .as("national day %d prepares an hour before its kickoff", day)
                    .isEqualTo(kickoff - 1);
            assertThat(new MatchdayPublishJob(day, kickoff, week, fixtures, scheduler).hour())
                    .as("national day %d publishes at its kickoff", day)
                    .isEqualTo(kickoff);
            assertThat(new MatchdayPrepareJob(day, kickoff, week, fixtures, scheduler).week())
                    .as("a national job must not fire in every week of the season")
                    .isEqualTo(week);
        }

        // Day 2 has no kickoff in the club calendar, which is the whole reason the hour is passed in:
        // reading GameDay there yields null, and a job with a null kickoff would prepare at hour 0.
        assertThat(GameDay.of(2).kickoffHour()).isNull();
    }

    private void repositories() {
        fixtures = mock(MatchFixtureRepository.class);
        scheduler = mock(MatchdayScheduler.class);
    }

    @Test
    @DisplayName("prepare fires an hour before kickoff, publish at kickoff, both from the template")
    void thePairIsAnHourApartAndReadFromTheTemplate() {
        repositories();

        // Day 1 is the 20:45 slot. "An hour before" is hour 19; the publish is the kickoff hour.
        assertThat(GameDay.of(DAY).kickoffHour()).isEqualTo(20);
        assertThat(prepareJob().hour()).isEqualTo(19);
        assertThat(publishJob().hour()).isEqualTo(20);

        // Day 7 kicks off at 16:00, so it locks at 15:00 — not at 19 because day 1 does.
        assertThat(new MatchdayPrepareJob(GameDay.LEAGUE_SECOND_DAY, fixtures, scheduler).hour()).isEqualTo(15);
        assertThat(new MatchdayPrepareJob(GameDay.CUP_DAY, fixtures, scheduler).hour()).isEqualTo(17);
    }

    @Test
    @DisplayName("the two jobs of one day have different keys, so neither suppresses the other")
    void thePairDoesNotSuppressItself() {
        repositories();

        // The done-flag is keyed on (season, week, day, key). A shared key would let the 19:00 run mark
        // the day done and the 20:45 run would then never publish anything.
        assertThat(prepareJob().key()).isNotEqualTo(publishJob().key());
        assertThat(prepareJob().day()).isEqualTo(publishJob().day());
        assertThat(publishJob().hour()).isGreaterThan(prepareJob().hour());
    }

    @Test
    @DisplayName("both jobs opt out of the framework transaction, or a matchday commits nothing")
    void thePairOptsOutOfTheJobTransaction() {
        repositories();

        // JobRunner wraps every job in one transaction. A matchday of 155 matches in one transaction
        // committed nothing for twenty minutes, and the per-fixture REQUIRES_NEW that was meant to fix
        // that deadlocked against the very transaction it was nested in.
        assertFalse(prepareJob().transactionalRun(), "the prepare job must manage its own boundaries");
        assertFalse(publishJob().transactionalRun(), "the publish job must manage its own boundaries");

        // And the jobs that were not written for this must be untouched by the change.
        assertTrue(new MatchdayJob("x", CompetitionType.LEAGUE, 3, 19, 40, null, null, null)
                .transactionalRun(), "an ordinary job keeps the framework transaction");
    }

    @Test
    @DisplayName("preparing leaves the fixture unplayed and puts the match id on preparedMatchId")
    void preparingDoesNotPublish() {
        SimMatchService sim = mock(SimMatchService.class);
        MatchFixtureRepository repo = mock(MatchFixtureRepository.class);
        MatchdayScheduler real = new MatchdayScheduler(repo, mock(org.example.footballmanager.newLogic.repository.MatchRepository.class), sim, NO_TRANSACTIONS);

        MatchFixture fixture = new MatchFixture();
        fixture.setId(42L);
        when(sim.simulate(any(), any(Boolean.class))).thenReturn(new SimMatchService.SimMatchOutcome(
                org.mockito.Mockito.mock(ProposalMatchOutcome.class), 7L, null, null, null));
        // The real prepareMatch writes the id onto the fixture; the stub has to do the same, or this
        // test would be asserting on a mock's silence rather than on the scheduler's behaviour.
        when(sim.prepareMatch(any(), any(), any(Long.class), any())).thenAnswer(call -> {
            ((MatchFixture) call.getArgument(0)).setPreparedMatchId(9001L);
            return 9001L;
        });

        int prepared = real.prepareUnplayedOn(1, 3, DAY, List.of(fixture));

        assertThat(prepared).isEqualTo(1);
        // The whole point. If this ever becomes true, a manager can read the result an hour early.
        assertThat(fixture.isPlayed()).isFalse();
        assertThat(fixture.getPreparedMatchId()).isEqualTo(9001L);
        assertThat(fixture.isPrepared()).isTrue();
        // prepareMatch, not persist: persist would set played = true.
        verify(sim, never()).persist(any(), any(), any(Long.class), any());
    }

    @Test
    @DisplayName("publishing releases the prepared match and never simulates again")
    void publishingDoesNotSimulate() {
        SimMatchService sim = mock(SimMatchService.class);
        MatchFixtureRepository repo = mock(MatchFixtureRepository.class);
        org.example.footballmanager.newLogic.repository.MatchRepository matches =
                mock(org.example.footballmanager.newLogic.repository.MatchRepository.class);
        MatchdayScheduler real = new MatchdayScheduler(repo, matches, sim, NO_TRANSACTIONS);

        MatchFixture fixture = new MatchFixture();
        fixture.setId(42L);
        fixture.setPreparedMatchId(9001L);

        org.example.footballmanager.newLogic.model.Match prepared =
                new org.example.footballmanager.newLogic.model.Match();
        prepared.setId(9001L);
        when(matches.findById(9001L)).thenReturn(java.util.Optional.of(prepared));

        int published = real.publishUnplayedOn(1, 3, DAY, List.of(fixture));

        assertThat(published).isEqualTo(1);
        assertThat(fixture.isPlayed()).isTrue();
        assertThat(fixture.getPlayedMatch()).isSameAs(prepared);
        assertThat(fixture.isPrepared()).isFalse();
        // Re-simulating here would mean the shown result is not the locked one.
        verify(sim, never()).simulate(any(), any(Boolean.class));

        ArgumentCaptor<MatchFixture> saved = ArgumentCaptor.forClass(MatchFixture.class);
        verify(repo).save(saved.capture());
        assertThat(saved.getValue().isPlayed()).isTrue();
    }

    @Test
    @DisplayName("a matchday the app missed in the hour before kickoff still happens at kickoff")
    void anUnpreparedMatchdayIsComputedAtKickoff() {
        SimMatchService sim = mock(SimMatchService.class);
        MatchFixtureRepository repo = mock(MatchFixtureRepository.class);
        MatchdayScheduler real = new MatchdayScheduler(repo,
                mock(org.example.footballmanager.newLogic.repository.MatchRepository.class), sim, NO_TRANSACTIONS);

        MatchFixture fixture = new MatchFixture();
        fixture.setId(42L);
        when(sim.simulate(any(), any(Boolean.class))).thenReturn(new SimMatchService.SimMatchOutcome(
                org.mockito.Mockito.mock(ProposalMatchOutcome.class), 7L, null, null, null));

        int published = real.publishUnplayedOn(1, 3, DAY, List.of(fixture));

        // Late rather than lost. This is the case that would silently eat a matchday.
        assertThat(published).isEqualTo(1);
        verify(sim).simulate(any(), any(Boolean.class));
        verify(sim).persist(any(), any(), any(Long.class), any());
    }

    @Test
    @DisplayName("a fixture already prepared is not prepared twice")
    void preparingTwiceComputesOnce() {
        SimMatchService sim = mock(SimMatchService.class);
        MatchFixtureRepository repo = mock(MatchFixtureRepository.class);
        MatchdayScheduler real = new MatchdayScheduler(repo,
                mock(org.example.footballmanager.newLogic.repository.MatchRepository.class), sim, NO_TRANSACTIONS);

        MatchFixture fixture = new MatchFixture();
        fixture.setId(42L);
        fixture.setPreparedMatchId(9001L);

        int prepared = real.prepareUnplayedOn(1, 3, DAY, List.of(fixture));

        assertThat(prepared).isZero();
        verify(sim, never()).simulate(any(), any(Boolean.class));
    }
}