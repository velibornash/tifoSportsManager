package org.tifo.footballmanager.newLogic.service;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tifo.footballmanager.newLogic.model.GamePosition;
import org.tifo.footballmanager.newLogic.model.MatchFixture;
import org.tifo.footballmanager.newLogic.repository.MatchFixtureRepository;

import java.util.ArrayList;
import java.util.List;

/**
 * The fixtures that were left behind — the second half of the owner's ruling (owner, 2026-10-19).
 *
 * <p><b>The ruling, verbatim:</b> <em>"every match whose start is in the past must be played — the game
 * looks only at its own season/week/day/hour against the current s/w/d/h — <b>and the sync has to
 * check it</b>."</em>
 *
 * <p>The first half of that was already true: the clock's walk offers every game hour it crosses to
 * the job runner, so a matchday that was missed while the app was down is replayed when it comes
 * back. **What was missing was the check** — nothing ever asked whether the walk had actually caught
 * up. A matchday that failed, a matchday whose publish job found nothing, a catch-up window longer
 * than the cap: all of them leave a fixture in the past that no job will ever play, and all of them
 * looked exactly like a healthy world.
 *
 * <p><b>What "in the past" means here.</b> The game has no calendar year. A fixture lives in a
 * <b>(season, week, day)</b> slot and the clock stands in a <b>(season, week, day, hour)</b> position,
 * so a fixture is behind when its slot is behind the position. The fixture's own {@code matchDate} is
 * deliberately not used — the three writers answer it three ways and the dates do not order the slots
 * (see {@link MatchFixtureRepository#countUnplayedBefore}).
 *
 * <p><b>The current day is not counted.</b> A fixture in the slot that is running right now is not
 * stranded: the publish job for that slot fires at its kickoff and may not have fired yet. A day is
 * only counted once it is over, which is also the frame the job runner uses.
 */
@Service
public class StrandedFixtureService {

    /** How many of them to name. A missed matchday is hundreds of rows; the panel needs a handful. */
    private static final int NAMED = 8;

    private final MatchFixtureRepository fixtures;

    public StrandedFixtureService(MatchFixtureRepository fixtures) {
        this.fixtures = fixtures;
    }

    /**
     * One fixture that was left behind.
     *
     * @param fixtureId the fixture's id
     * @param competition the competition it belongs to
     * @param home the home club
     * @param away the away club
     * @param season the season it was drawn in
     * @param week the week of that season
     * @param day the day of that week
     * @param kickedOffAt the slot it was drawn for, as the game writes it
     */
    public record Stranded(Long fixtureId,
                            String competition,
                            String home,
                            String away,
                            int season,
                            int week,
                            int day,
                            String kickedOffAt) {
    }

    /** How many fixtures are behind the clock and unplayed. */
    @Transactional(readOnly = true)
    public int count(GamePosition position) {
        return (int) fixtures.countUnplayedBefore(position.season(), position.week(), position.day());
    }

    /**
     * The fixtures that were left behind, oldest slot first.
     *
     * @param position where the clock stands
     */
    @Transactional(readOnly = true)
    public List<Stranded> stranded(GamePosition position) {
        return stranded(position, NAMED);
    }

    /**
     * The fixtures that were left behind, oldest slot first, up to a limit.
     *
     * @param position where the clock stands
     * @param limit how many to name
     */
    @Transactional(readOnly = true)
    public List<Stranded> stranded(GamePosition position, int limit) {
        List<Stranded> out = new ArrayList<>();
        for (MatchFixture fixture : fixtures.findUnplayedBefore(
                position.season(), position.week(), position.day(), PageRequest.of(0, limit))) {
            out.add(describe(fixture));
        }
        return out;
    }

    private Stranded describe(MatchFixture fixture) {
        return new Stranded(
                fixture.getId(),
                fixture.getCompetition() == null ? "?" : fixture.getCompetition().getName(),
                fixture.getHomeTeam() == null ? "?" : fixture.getHomeTeam().getName(),
                fixture.getAwayTeam() == null ? "?" : fixture.getAwayTeam().getName(),
                fixture.getSeasonYear() == null ? 0 : fixture.getSeasonYear(),
                fixture.getWeekNumber() == null ? 0 : fixture.getWeekNumber(),
                fixture.getDayNumber() == null ? 0 : fixture.getDayNumber(),
                fixture.getMatchDate() == null ? "?" : fixture.getMatchDate().toString());
    }
}
