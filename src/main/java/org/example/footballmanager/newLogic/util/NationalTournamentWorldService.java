package org.example.footballmanager.newLogic.util;

import jakarta.transaction.Transactional;
import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.MatchFixture;
import org.example.footballmanager.newLogic.model.NationalTeamLevel;
import org.example.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.example.footballmanager.newLogic.repository.GameClockRepository;
import org.example.footballmanager.newLogic.service.SeasonService;
import java.util.ArrayList;
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
    private final MatchFixtureRepository fixtures;

    public NationalTournamentWorldService(NationalTeamCompetitions catalogue,
                                          NationalTournamentSeeder seeder,
                                          SeasonService seasons,
                                          GameClockRepository clocks,
                                          MatchFixtureRepository fixtures) {
        this.catalogue = catalogue;
        this.seeder = seeder;
        this.seasons = seasons;
        this.clocks = clocks;
        this.fixtures = fixtures;
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

    /**
     * Forces a fresh re-draw of the national-team qualifying groups for the current season.
     *
     * <p>This is the admin "Re-draw national competitions" action. It deletes every unplayed qualifying
     * fixture (and any unplayed tournament-bracket fixture) for the current season, then re-creates the
     * four competitions if they are missing, and draws fresh qualifying groups for both the senior and
     * U-21 levels. The tournament bracket is cleared so it will be rebuilt from the new qualifying
     * results when week 12 arrives.
     *
     * <p><b>Refused once a qualifying tie has been played</b>, and that refusal is the point rather
     * than a limitation. A played fixture carries a group code, and a re-draw deals new groups: the
     * surviving result would then sit on the table of a group its two nations are no longer in — a
     * played match counted for the wrong six teams, in a competition the owner reads as fact. There is
     * no version of "redraw, but keep the results" that is also consistent, so the button says so and
     * leaves the campaign alone.
     */
    @Transactional
    public Result forceRedraw() {
        int seasonYear = seasons.getActiveSeasonYear();
        int created = catalogue.ensureAll().size();

        refuseIfQualifyingStarted(seasonYear);

        int cleared = 0;
        for (NationalTeamLevel level : NationalTeamLevel.values()) {
            cleared += clearUnplayed(catalogue.qualifiers(level).orElse(null), seasonYear);
            cleared += clearUnplayed(catalogue.tournament(level).orElse(null), seasonYear);
        }

        List<NationalTournamentSeeder.DrawResult> draws = new ArrayList<>();
        for (NationalTeamLevel level : NationalTeamLevel.values()) {
            draws.add(seeder.ensureGroupStage(level, seasonYear));
        }

        log.info("National-team re-draw for season {}: {} competition(s) created, {} unplayed fixture(s) cleared.",
                seasonYear, created, cleared);
        return new Result(created, draws, String.valueOf(seasonYear));
    }

    /**
     * Refuses a re-draw when the qualifying campaign already has a result in it.
     *
     * @throws IllegalStateException naming the level and the count, before anything is deleted
     */
    private void refuseIfQualifyingStarted(int seasonYear) {
        for (NationalTeamLevel level : NationalTeamLevel.values()) {
            Competition qualifying = catalogue.qualifiers(level).orElse(null);
            if (qualifying == null) {
                continue;
            }
            List<MatchFixture> groupFixtures =
                    fixtures.findByCompetitionIdAndSeasonYearOrderByRoundNumberAscMatchDateAsc(
                            qualifying.getId(), seasonYear);
            long played = groupFixtures.stream().filter(MatchFixture::isPlayed).count();
            if (played > 0) {
                throw new IllegalStateException("Season " + seasonYear + " already has " + played
                        + " played qualifying tie(s) for " + level
                        + ". Re-drawing now would leave those results standing in groups the nations are no longer in, "
                        + "so the campaign has been left exactly as it is.");
            }
        }
    }

    /**
     * Deletes the unplayed fixtures of one competition, and reports how many went.
     *
     * <p>Only unplayed, which follows from the refusal above: nothing with a result can reach this method.
     *
     * @return how many fixtures were deleted, zero when the competition does not exist yet
     */
    private int clearUnplayed(Competition competition, int seasonYear) {
        if (competition == null) {
            return 0;
        }
        List<MatchFixture> unplayed =
                fixtures.findByCompetitionIdAndSeasonYearAndPlayedFalse(competition.getId(), seasonYear);
        if (unplayed.isEmpty()) {
            return 0;
        }
        fixtures.deleteAll(unplayed);
        log.info("Cleared {} unplayed fixture(s) from '{}' (season {}).",
                unplayed.size(), competition.getName(), seasonYear);
        return unplayed.size();
    }
        

    /**
     * The season the draws are for, for the screen.
     */
    public int season() {
        return seasons.getActiveSeasonYear();
    }

    /**
     * Kept so a caller can read the clock's date without reaching for the repository itself.
     */
    public java.time.LocalDateTime now() {
        return clocks.findById(1L)
                .map(org.example.footballmanager.newLogic.model.GameClock::getCurrentDate)
                .orElse(java.time.LocalDateTime.of(2026, 7, 1, 12, 0));
    }
}