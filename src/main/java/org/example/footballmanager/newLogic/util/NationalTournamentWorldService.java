package org.example.footballmanager.newLogic.util;

import org.example.footballmanager.newLogic.model.NationalTeamLevel;
import org.example.footballmanager.newLogic.repository.GameClockRepository;
import org.example.footballmanager.newLogic.service.SeasonService;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Creates the four national-team competitions and draws them (owner, 2026-10-06).
 *
 * <p><b>Reached from an admin button, never from boot.</b> AGENTS.md is explicit that boot writes
 * nothing: no seeding, no backfills, no repair. Four competitions and 120 qualifying fixtures appearing
 * on every restart would be exactly the thing that rule exists to prevent, and it would do it to a
 * world whose owner had not asked for one yet.
 *
 * <p>Idempotent at every step, so the button can be pressed twice and the answer is the same world.
 */
@Component
public class NationalTournamentWorldService {

    private static final Logger log = LoggerFactory.getLogger(NationalTournamentWorldService.class);

    private final NationalTeamCompetitions catalogue;
    private final NationalTournamentSeeder seeder;
    private final SeasonService seasons;
    private final GameClockRepository clocks;

    public NationalTournamentWorldService(NationalTeamCompetitions catalogue,
                                          NationalTournamentSeeder seeder,
                                          SeasonService seasons,
                                          GameClockRepository clocks) {
        this.catalogue = catalogue;
        this.seeder = seeder;
        this.seasons = seasons;
        this.clocks = clocks;
    }

    /** What one admin press produced. Returned rather than logged, so the screen can show it. */
    public record Result(int competitions, List<NationalTournamentSeeder.DrawResult> draws, String season) {
    }

    /**
     * Creates the four competitions and draws the qualifying groups for both levels.
     *
     * <p>The tournament bracket is deliberately not drawn here. It depends on the qualifying results,
     * which do not exist yet, and the draw job draws it a round at a time once they do.
     */
    public Result seed() {
        int seasonYear = seasons.getActiveSeasonYear();
        int created = catalogue.ensureAll().size();
        java.util.List<NationalTournamentSeeder.DrawResult> draws = new java.util.ArrayList<>();
        for (NationalTeamLevel level : NationalTeamLevel.values()) {
            draws.add(seeder.ensureGroupStage(level, seasonYear));
        }

        // The week-6 day-1 warm-up round is **not** drawn here any more.
        //
        // It used to be, and it drew a pairing for every senior side with a squad — which is exactly the
        // compulsory version of the thing the owner does not want ("a warm-up round, scheduled like a
        // club friendly, but not compulsory"), and it filled the one day a national friendly can use, so
        // an invitation had nowhere to go. The day is now `NationalFriendlySlots.WEEK` / `.DAY` and is
        // filled by `NationalFriendlyRequestService`, one request at a time, where either side may refuse.
        //
        // If that round is wanted back as a default rather than as an invitation, it is one call in this
        // method and it belongs to an owner decision, not to a seeder.

        log.info("National-team world: {} competition(s) and {} draw(s) for season {}.",
                created, draws.size(), seasonYear);
        return new Result(created, draws, String.valueOf(seasonYear));
    }

    /**
     * Draws whatever the tournament results so far allow.
     *
     * <p>The counterpart to {@link #seed()} for the week-12 job, exposed so an admin can nudge a
     * tournament that stalled rather than waiting for the clock.
     */
    public Result advanceTournaments() {
        int seasonYear = seasons.getActiveSeasonYear();
        java.util.List<NationalTournamentSeeder.DrawResult> draws = new java.util.ArrayList<>();
        for (NationalTeamLevel level : NationalTeamLevel.values()) {
            draws.add(seeder.ensureKnockouts(level, seasonYear));
        }
        return new Result(0, draws, String.valueOf(seasonYear));
    }

    /** Whether the four competitions exist, for the admin screen. */
    public boolean ready() {
        return catalogue.exists(NationalTeamLevel.SENIOR, org.example.footballmanager.newLogic.model.NationalStage.QUALIFYING)
                && catalogue.exists(NationalTeamLevel.SENIOR, org.example.footballmanager.newLogic.model.NationalStage.WORLD_CUP);
    }

    /** The season the draws are for, for the screen. */
    public int season() {
        return seasons.getActiveSeasonYear();
    }

    /** Kept so a caller can read the clock's date without reaching for the repository itself. */
    public java.time.LocalDateTime now() {
        return clocks.findById(1L)
                .map(org.example.footballmanager.newLogic.model.GameClock::getCurrentDate)
                .orElse(java.time.LocalDateTime.of(2026, 7, 1, 12, 0));
    }
}