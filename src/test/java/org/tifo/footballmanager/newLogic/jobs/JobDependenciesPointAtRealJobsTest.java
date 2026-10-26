package org.tifo.footballmanager.newLogic.jobs;

import org.tifo.footballmanager.BaseTest;
import org.tifo.footballmanager.newLogic.jobs.impl.FantasyScoringJob;
import org.tifo.footballmanager.newLogic.jobs.impl.JobOfferJob;
import org.tifo.footballmanager.newLogic.jobs.impl.RecoveryJob;
import org.tifo.footballmanager.newLogic.jobs.impl.SeasonRolloverJob;
import org.tifo.footballmanager.newLogic.jobs.impl.StaffRecruitingJob;
import org.tifo.footballmanager.newLogic.jobs.impl.WeekRolloverJob;
import org.tifo.footballmanager.newLogic.model.GameDay;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A dependency has to name a job that exists, on a day it can actually be read for.
 *
 * <p><b>This is the test for a bug that cost six national matchdays.</b> The runner resolves
 * {@code dependsOn()} by looking up a {@code job_run} row for {@code (season, week, day, key)}. If the
 * key names nothing — or names a job that never runs on that day — the row never appears, the dependent
 * job answers {@code WAITING_FOR_DEPENDENCY} on every scan and <b>silently never runs</b>. No exception,
 * no failure, no log anyone reads: the matches simply do not happen.
 *
 * <p>That is exactly what the national publishes did. {@code MatchdayPublishJob.dependsOn()} returned
 * {@code "matchday-prepare-" + day} for every pair, but the national pairs are keyed
 * {@code matchday-prepare-qualifier-2} and {@code matchday-prepare-tournament-2}. The publish waited on
 * {@code matchday-prepare-2}, which no job has, for the whole season.
 *
 * <p>So the rules here are the ones a compiler would enforce if job keys were types:
 *
 * <ul>
 *   <li>a dependency names a registered job;</li>
 *   <li>that job is scheduled on the day the dependency is read for (or on every day);</li>
 *   <li>no job waits for itself, and no chain loops;</li>
 *   <li>a pinned dependency day is not in the future;</li>
 *   <li>and the chain the owner asked for is actually wired: prepare → publish → reconcile → week
 *       rollover → season rollover.</li>
 * </ul>
 *
 * <p>Structural, over the injected {@code List<DayJob>}, in the shape of
 * {@code JobTriggerUniquenessTest}: it asks what the application <em>is</em>, not what a document says.
 */
class JobDependenciesPointAtRealJobsTest extends BaseTest {

    @Autowired
    List<DayJob> jobs;

    @Test
    @DisplayName("every dependency names a registered job")
    void everyDependencyNamesARegisteredJob() {
        Set<String> registered = new TreeSet<>();
        for (DayJob job : jobs) {
            registered.add(job.key());
        }

        List<String> unknown = new ArrayList<>();
        for (DayJob job : jobs) {
            String dep = job.dependsOn();
            if (dep != null && !registered.contains(dep)) {
                unknown.add(job.key() + " waits on " + dep);
            }
        }

        assertTrue(unknown.isEmpty(),
                "these jobs wait on a key no job has: " + unknown + ". The runner looks the dependency up "
                        + "by (season, week, day, key); a key nothing owns means the row never appears, and "
                        + "the job waits for ever without failing. " + describeJobs());
    }

    @Test
    @DisplayName("the job a dependency names is scheduled on the day the dependency is read for")
    void theDependencyIsScheduledOnTheDayItIsReadFor() {
        Map<String, Set<Integer>> daysByKey = new TreeMap<>();
        for (DayJob job : jobs) {
            daysByKey.computeIfAbsent(job.key(), k -> new TreeSet<>()).add(job.day());
        }

        List<String> wrong = new ArrayList<>();
        for (DayJob job : jobs) {
            String dep = job.dependsOn();
            if (dep == null) {
                continue;
            }
            int day = dependencyDay(job);

            if (day == DayJob.ANY_DAY) {
                // The dependency is read for whatever day the job runs on, so it must be a job that
                // runs on every day - otherwise it is missing on six of the seven.
                Set<Integer> targetDays = daysByKey.getOrDefault(dep, Set.of());
                if (!targetDays.contains(DayJob.ANY_DAY)) {
                    wrong.add(job.key() + " (every day) waits on " + dep + ", which runs on " + targetDays);
                }
                continue;
            }

            if (job.day() != DayJob.ANY_DAY && day > job.day()) {
                wrong.add(job.key() + " (day " + job.day() + ") waits on a job pinned to day " + day
                        + ", which is in the future");
            }

            Set<Integer> targetDays = daysByKey.getOrDefault(dep, Set.of());
            boolean reachable = targetDays.contains(DayJob.ANY_DAY) || targetDays.contains(day);
            if (!reachable) {
                wrong.add(job.key() + " (day " + job.day() + ") waits on " + dep + " for day " + day
                        + ", but that job runs on " + targetDays);
            }
        }

        assertTrue(wrong.isEmpty(),
                "these dependencies can never be satisfied: " + wrong + ". A dependency is resolved for one "
                        + "specific day; if the job it names does not run on that day, the row never appears "
                        + "and the dependent job waits for ever, silently. " + describeJobs());
    }

    @Test
    @DisplayName("no job waits for itself, and no chain loops")
    void noJobWaitsForItselfAndNoChainLoops() {
        Map<String, Set<String>> edges = new TreeMap<>();
        for (DayJob job : jobs) {
            edges.computeIfAbsent(job.key(), k -> new TreeSet<>());
            String dep = job.dependsOn();
            if (dep != null) {
                edges.get(job.key()).add(dep);
            }
        }

        for (DayJob job : jobs) {
            assertTrue(!job.key().equals(job.dependsOn()),
                    job.key() + " waits for itself, so it can never run.");
        }

        for (String start : edges.keySet()) {
            List<String> cycle = findCycle(start, edges, new ArrayList<>());
            assertTrue(cycle == null,
                    () -> "the job dependency chain loops: " + String.join(" -> ", cycle)
                            + ". Every job in the loop waits for the next, and none of them can ever run.");
        }
    }

    @Test
    @DisplayName("the matchday chain is wired: prepare -> publish -> reconcile -> rollover -> season")
    void theMatchdayChainIsWired() {
        // The owner's rule: the next job may not start before the previous one has finished. It is one
        // thing for the runner to support that; it is another for the jobs to declare it.
        Map<String, DayJob> byKey = new LinkedHashMap<>();
        for (DayJob job : jobs) {
            byKey.putIfAbsent(job.key(), job);
        }

        for (int day : new int[] {1, 3, 5, 7}) {
            String publishKey = "matchday-publish-" + day;
            DayJob publish = byKey.get(publishKey);
            assertTrue(publish != null, publishKey + " is not registered; every club match day has one.");
            assertTrue(("matchday-prepare-" + day).equals(publish.dependsOn()),
                    publishKey + " must wait for its own prepare job, and waits for "
                            + publish.dependsOn() + " instead.");
        }

        assertDependsOn(byKey, "matchday-publish-qualifier-2", "matchday-prepare-qualifier-2");
        assertDependsOn(byKey, "matchday-publish-tournament-2", "matchday-prepare-tournament-2");
        assertDependsOn(byKey, "matchday-publish-qualifier-4", "matchday-prepare-qualifier-4");
        assertDependsOn(byKey, "matchday-publish-tournament-4", "matchday-prepare-tournament-4");
        assertDependsOn(byKey, "matchday-publish-qualifier-6", "matchday-prepare-qualifier-6");
        assertDependsOn(byKey, "matchday-publish-tournament-6", "matchday-prepare-tournament-6");

        // The two reconciliation jobs wait for the matchday they repair, and the day-4 one repairs the
        // day-3 matchday - which is the one dependency that is not read for the job's own day.
        assertDependsOn(byKey, JobKeys.RECONCILE_AFTER_FIRST_LEAGUE_DAY, "matchday-publish-3");
        assertTrue(byKey.get(JobKeys.RECONCILE_AFTER_FIRST_LEAGUE_DAY).dependsOnDay() == 3,
                "league-table-reconcile-a runs on day 4 and repairs the day-3 matchday, so its dependency "
                        + "must be read for day 3.");

        assertDependsOn(byKey, JobKeys.RECONCILE_AFTER_SECOND_LEAGUE_DAY, "matchday-publish-7");
        assertDependsOn(byKey, WeekRolloverJob.KEY, JobKeys.RECONCILE_AFTER_SECOND_LEAGUE_DAY);
        assertDependsOn(byKey, SeasonRolloverJob.KEY, WeekRolloverJob.KEY);
        assertDependsOn(byKey, FantasyScoringJob.KEY, "matchday-publish-" + GameDay.LEAGUE_SECOND_DAY);
        assertDependsOn(byKey, JobOfferJob.KEY, RecoveryJob.KEY);
        assertDependsOn(byKey, StaffRecruitingJob.KEY, JobOfferJob.KEY);
    }

    // --- helpers ---

    private void assertDependsOn(Map<String, DayJob> byKey, String key, String expected) {
        DayJob job = byKey.get(key);
        assertTrue(job != null, key + " is not registered, so the chain is broken at that point.");
        assertTrue(expected.equals(job.dependsOn()),
                key + " must wait for " + expected + ", and waits for " + job.dependsOn()
                        + " instead. A job that does not wait runs against half the previous job's work.");
    }

    /** The day {@code dependsOn()} is read for: the job's own day unless it pins another. */
    private int dependencyDay(DayJob job) {
        int pinned = job.dependsOnDay();
        return pinned == DayJob.ANY_DAY ? job.day() : pinned;
    }

    private List<String> findCycle(String key, Map<String, Set<String>> edges, List<String> path) {
        if (path.contains(key)) {
            List<String> cycle = new ArrayList<>(path.subList(path.indexOf(key), path.size()));
            cycle.add(key);
            return cycle;
        }
        path.add(key);
        for (String next : edges.getOrDefault(key, Set.of())) {
            List<String> cycle = findCycle(next, edges, path);
            if (cycle != null) {
                return cycle;
            }
        }
        path.remove(path.size() - 1);
        return null;
    }

    private String describeJobs() {
        StringBuilder text = new StringBuilder("Registered jobs: ");
        for (DayJob job : jobs) {
            text.append(System.lineSeparator())
                    .append("  ").append(job.key())
                    .append(" day ").append(job.day())
                    .append(" week ").append(job.week())
                    .append(" dependsOn ").append(job.dependsOn())
                    .append(" day ").append(job.dependsOnDay());
        }
        return text.toString();
    }
}
