package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.GameDay;
import org.example.footballmanager.newLogic.model.MatchFixture;
import org.example.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * A match is closed to changes an hour before kickoff (owner, 2026-10-11).
 *
 * <p>The owner's rule, in the same sentence as the compute job: *"sat vremena pre početka utakmice
 * igrač ne sme više podešavati taktiku za taj meč."* An hour before kickoff the manager can no longer
 * change anything about that match.
 *
 * <p><b>A deadline that is not enforced is worse than none.</b> A manager who is told his lineup is
 * closed, and then changes it at 19:50, has been lied to — and will do it again next time. This service
 * is the single question every write path asks before it changes a club's match setup.
 *
 * <p><b>The lock is the prepared fixture, not a clock reading.</b> It would be tempting to say "within
 * an hour of kickoff" and compare timestamps. That is the wrong source of truth for two reasons: the
 * clock is the thing the job framework itself moves in jumps, and a manager's change must be refused
 * because <em>the match was already decided without it</em>, not because a number was crossed. A
 * fixture that has been computed and not published is exactly that fact, and it is already stored.
 *
 * <p>Both halves matter and they are not the same test. Before the prepare job has run there is nothing
 * to protect and a manager may change everything; after it has run, nothing may change until kickoff.
 * Asking "is it close to kickoff?" gets the second right and the first wrong on an app that was down
 * for the hour.
 */
@Service
public class MatchLockService {

    private final MatchFixtureRepository fixtures;

    public MatchLockService(MatchFixtureRepository fixtures) {
        this.fixtures = fixtures;
    }

    /**
     * The match this club can no longer change, if there is one.
     *
     * <p>Any team of the fixture counts, home or away: an opponent's tactics are not a manager's to
     * edit, but the question "is my club locked?" must not depend on which side it happens to be drawn.
     *
     * @return the prepared, unpublished fixture involving this club
     */
    public Optional<MatchFixture> lockFor(Long teamId) {
        if (teamId == null) {
            return Optional.empty();
        }
        return fixtures.findPreparedUnpublishedInvolving(teamId).stream().findFirst();
    }

    /** Whether anything this club would change has already been decided. */
    public boolean isLocked(Long teamId) {
        return lockFor(teamId).isPresent();
    }

    /**
     * The message a manager gets when he is refused.
     *
     * <p>It names the opponent and the kickoff hour, because "locked" on its own is a word that makes
     * a manager think the game is broken. The hour comes from the calendar rather than being written
     * into the message, so it cannot go stale.
     *
     * <p><b>Who is asking decides which club is the opponent</b>, which is why the club is a parameter
     * rather than something read off the fixture. It used to be derived from the fixture alone, by
     * comparing the home team's id with the home team's id — a comparison that is true by construction,
     * so the sentence always named the away side. For a home manager that is right by accident; for an
     * away manager it read *"Your match against OFK Omladinac is already decided"*, naming the manager's
     * own club back at him, on the one screen whose whole job is to explain the lock. Found by asking
     * the live endpoint while a matchday sat prepared: team 1 was drawn away and was told it was the
     * opponent.
     *
     * @param askingTeamId the club being refused; {@code null} or a team in neither side falls back to
     *                     the away side, which is what the broken version always did
     */
    public String refusalReason(MatchFixture fixture, Long askingTeamId) {
        Integer kickoff = fixture.getDayNumber() == null ? null
                : GameDay.of(fixture.getDayNumber()).kickoffHour();
        String opponent = opponentName(fixture, askingTeamId);
        String when = kickoff == null ? "today" : "at " + kickoff + ":00";

        return "Your match against " + opponent + " is already decided, so its tactics, squad and "
                + "orders are closed. The lock is an hour before kickoff, " + when + ".";
    }

    /** The side of the fixture that is not the club being refused. */
    private String opponentName(MatchFixture fixture, Long askingTeamId) {
        boolean askingIsHome = askingTeamId != null
                && fixture.getHomeTeam() != null
                && askingTeamId.equals(fixture.getHomeTeam().getId());
        return String.valueOf(askingIsHome ? fixture.getAwayTeam().getName()
                : fixture.getHomeTeam().getName());
    }

    /** The fixtures of a matchday that are computed and still hidden, for the admin view. */
    public List<MatchFixture> lockedFixtures(Integer seasonYear, Integer week, int day) {
        return fixtures.findUnpublishedOnDay(seasonYear, week, day).stream()
                .filter(MatchFixture::isPrepared)
                .toList();
    }
}
