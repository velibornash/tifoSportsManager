package org.example.footballmanager.newLogic.jobs.impl;

import org.example.footballmanager.newLogic.jobs.JobContext;
import org.example.footballmanager.newLogic.repository.CompetitionRepository;
import org.example.footballmanager.newLogic.util.InternationalClubCups;
import org.example.footballmanager.newLogic.util.SimulatedWorldSeeder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Week 1 must not build the world's static half twice (found 2026-10-08).
 *
 * <p><b>The defect.</b> The job seeds the qualifying season and then the active season. In season 1 those
 * are the <b>same season</b> — {@code qualifyingSeason} clamps at 1 because there is no season 0 — so the
 * simulated world was asked to build itself twice. And this job fires on <b>every hour of week 1</b>, so a
 * day advance on a world whose simulated half does not exist yet paid that build twice per tick.
 *
 * <p><b>What it looked like from outside:</b> one {@code advance day} on this 48-country world ran for
 * over twenty minutes and the clock never moved. It read as a hang. It was the seeding, counted twice, and
 * it is exactly why the class's own javadoc — *"running this job twice in one week changes nothing"* — was
 * not true.
 *
 * <p>Asserted here through the seeder rather than through fixtures, because the fixture count was never
 * the thing that doubled. The existing {@code theJobIsIdempotent} test passes against the broken code for
 * that reason.
 */
class InternationalClubCupJobSeedingTest {

    private final SimulatedWorldSeeder seeder = mock(SimulatedWorldSeeder.class);
    private final InternationalClubCups cups = mock(InternationalClubCups.class);
    private final CompetitionRepository competitions = mock(CompetitionRepository.class);

    private InternationalClubCupJob job() {
        // The competitions catalogue is a static list inside the collaborator; the repository behind it
        // answers empty, which is the "no cup rows yet" state the job is expected to cope with.
        when(competitions.findAll()).thenReturn(java.util.List.of());
        return new InternationalClubCupJob(cups, seeder,
                mock(org.example.footballmanager.newLogic.util.InternationalClubCupDraw.class), competitions);
    }

    private static JobContext context(int season, int week) {
        return new JobContext(season, week, 1, 8);
    }

    @Test
    @DisplayName("season 1 seeds the world once, because the qualifying season is the active one")
    void seasonOneSeedsOnce() {
        job().run(context(1, 1));

        verify(seeder, times(1)).seedAllSimulated(1);
        verify(seeder, never()).seedAllSimulated(0);
    }

    @Test
    @DisplayName("a later season seeds both, because entry comes off last season's finished tables")
    void laterSeasonsSeedBoth() {
        job().run(context(5, 1));

        verify(seeder).seedAllSimulated(4);
        verify(seeder).seedAllSimulated(5);
    }

    @Test
    @DisplayName("a knockout week seeds nothing at all")
    void knockoutWeeksDoNotSeed() {
        job().run(context(5, 8));

        verify(seeder, never()).seedAllSimulated(anyInt());
    }
}