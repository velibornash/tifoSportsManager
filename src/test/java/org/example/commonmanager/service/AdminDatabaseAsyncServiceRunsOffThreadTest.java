package org.example.commonmanager.service;

import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.service.ClubRatingService;
import org.example.footballmanager.newLogic.util.SimulatedWorldSeeder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The admin world-building buttons must answer immediately and keep working in the background.
 *
 * <p><b>Why this class exists.</b> {@code AdminDatabaseAsyncService} had no {@code @Async}, no executor and
 * no thread of its own — {@code startOrGetRunningJob} called {@code execute(action)} inline inside
 * {@code synchronized (this)}. So {@code POST /admin/seed-other-nations} blocked for the entire run, the
 * status poll the front end depends on could not be issued from that page until the button's own call
 * returned, and a second press waited rather than being told what was already running.
 *
 * <p>It was also the cause of the growth the owner reported (2026-10-19: <i>"prvih par nacija uradi celu
 * piramidu za par sekundi a svaku sledeću baš značajno sporije"</i>). On the request thread,
 * Open-EntityManager-in-View binds one {@code EntityManager} for the whole request, every country
 * accumulates in that one persistence context, and every flush dirty-checks all of it. Measured:
 * 0.07 s per division for the first country and 0.53 s by the twelfth, with the database idle throughout.
 *
 * <p><b>How the test is built so it can actually fail.</b> The work is made to block on a latch, so
 * "returns before the work finishes" is a claim about control flow rather than about timing luck. Under the
 * old inline implementation this call could not return until the latch was released, so it would report
 * {@code completed} and would have taken the full latch timeout to get there — both assertions below turn
 * red. Nothing here depends on the work being genuinely slow.
 */
class AdminDatabaseAsyncServiceRunsOffThreadTest extends BaseTest {

    @Autowired AdminDatabaseAsyncService adminJobs;

    @MockBean SimulatedWorldSeeder simulatedWorldSeeder;
    @MockBean ClubRatingService clubRatingService;

    @Test
    @DisplayName("starting a job returns 'running' while the work is still going")
    void startReturnsBeforeTheWorkIsDone() throws Exception {
        CountDownLatch workStarted = new CountDownLatch(1);
        CountDownLatch releaseWork = new CountDownLatch(1);

        when(simulatedWorldSeeder.seedAllSimulated(anyInt())).thenAnswer(invocation -> {
            workStarted.countDown();
            // Stands in for a long seeding run. Ten seconds is far longer than any assertion below.
            releaseWork.await(10, TimeUnit.SECONDS);
            return null;
        });

        try {
            long startedAt = System.nanoTime();
            var snapshot = adminJobs.startOrGetRunningJob("seed-other-nations");
            long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

            assertEquals("running", snapshot.status(),
                    "The button reported a finished job, so it ran the work on the caller's thread.");
            assertTrue(elapsedMillis < 5_000,
                    "startOrGetRunningJob took " + elapsedMillis + " ms, so it blocked on the work itself.");
            assertTrue(workStarted.await(15, TimeUnit.SECONDS),
                    "The job never reached the seeding step, so this proves nothing about anything.");
        } finally {
            releaseWork.countDown();
        }
        awaitSettled();
    }

    @Test
    @DisplayName("a second press while a job runs reports that job and does not start another")
    void secondPressJoinsTheRunningJob() throws Exception {
        CountDownLatch workStarted = new CountDownLatch(1);
        CountDownLatch releaseWork = new CountDownLatch(1);

        when(simulatedWorldSeeder.seedAllSimulated(anyInt())).thenAnswer(invocation -> {
            workStarted.countDown();
            releaseWork.await(10, TimeUnit.SECONDS);
            return null;
        });

        try {
            var first = adminJobs.startOrGetRunningJob("seed-other-nations");
            assertTrue(workStarted.await(15, TimeUnit.SECONDS), "The first job never started.");

            var second = adminJobs.startOrGetRunningJob("seed-other-nations");

            assertEquals(first.jobId(), second.jobId(),
                    "A second press started a new job instead of reporting the running one.");
            assertEquals("running", second.status());
            // The whole point: one world rewrite at a time. This is ENG-TEST-6's subject.
            verify(simulatedWorldSeeder, times(1)).seedAllSimulated(anyInt());
        } finally {
            releaseWork.countDown();
        }

        awaitSettled();
        verify(clubRatingService, atLeastOnce()).recomputeDurably();
    }

    /**
     * Waits for the job to leave "running".
     *
     * <p>The snapshot is a field on a singleton service, so a job left running by one test would be
     * found by the next one and — correctly — refuse to start, which reads as a failure that is not one.
     * Each test settles the shared state before it hands over.
     */
    private void awaitSettled() throws InterruptedException {
        for (int i = 0; i < 100 && "running".equals(adminJobs.getJobSnapshot().status()); i++) {
            Thread.sleep(100);
        }
    }
}