package org.example.footballmanager.newLogic.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity(name = "MatchFixture")
@Table(name = "match_fixture",
        indexes = {
                // (season_year, week_number, day_number, played) — eight repository methods key on this
                // triple and the hourly job calls them every hour. The identical triple is indexed on
                // job_run: the bookkeeping table got it and the data table did not.
                //
                // <b>This index also serves the four "current matchday" request paths</b>, which is why
                // there is no index on round_number. There was one, and it was wrong: those endpoints
                // were filtering on round_number while the counter on the clock is a week, so in week 3
                // they fetched round 3 — which is week 2's football — and skipped rounds 5 and 6, and
                // rounds 13-18 were never reached at all because the clock stops at week 12. Owner ruled
                // (2026-10-03): one press plays one matchday. Day 3 and day 7 are league, day 1 is
                // international and day 5 is cup, so (season, week, day) is the axis that says which
                // football is due, and this index already serves it exactly. Dropped, and asserted
                // dropped, because an index that is removed and comes back is not a removal.
                @Index(name = "ix_match_fixture_season_week_day", columnList = "season_year,week_number,day_number,played"),

                // The fixture → played-match link, which is the only way back from a scheduled game to
                // its result. It was neither indexed nor constrained, and both halves mattered.
                //
                // <b>The index is needed the moment anything looks a result up by fixture</b> — which is
                // exactly what the substitution plan does at kickoff, and what every "who played this
                // fixture" surface does. Without it each read is a scan of this table.
                //
                // <b>The unique constraint is the invariant the whole pair rests on:</b> one played match
                // belongs to at most one fixture. Without it, two fixtures can point at one `Match`, and
                // the result of a game then reads as the result of a different game. This repository has
                // already paid for that class of mistake three times over — P0-PREV-1, -2 and -3 each
                // recorded "a fixture is not a match, and only the `playedMatch` knows which one this is",
                // and `ZoxApiController` carries the scar of a guess that resolved a dashboard link to
                // somebody else's played match.
                //
                // Declared `unique = true` on the column rather than as a table-level constraint so that
                // `ddl-auto=update` creates it with the column. Measured against the live database before
                // it was added: no two fixtures shared a played match, and no fixture was played with the
                // link unset.
                @Index(name = "ix_match_fixture_played_match", columnList = "played_match_id"),
        },
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_match_fixture_played_match",
                        columnNames = "played_match_id")
        })
public class MatchFixture {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    private Team homeTeam;

    @ManyToOne(fetch = FetchType.LAZY)
    private Team awayTeam;

    @ManyToOne(fetch = FetchType.LAZY)
    private Competition competition;

    /**
     * The type of fixture this is, when it is not implied by a competition (P2-8).
     *
     * <p>Left null by every scheduled fixture, because a league or cup fixture's type is exactly its
     * competition's type and saying so twice is one more thing that can disagree. A friendly or an
     * exhibition sets it, because those have no competition to imply anything.
     */
    @jakarta.persistence.Column(name = "match_type", length = 20)
    private MatchType matchType;

    /**
     * The type this fixture will produce, whichever way it was stated.
     *
     * <p>An explicit type wins; otherwise the competition decides; a fixture in no competition is a
     * friendly, which is what {@link MatchType#ofCompetition} returns for null.
     */
    public MatchType resolvedMatchType() {
        return matchType != null ? matchType : MatchType.ofCompetition(competition);
    }

    private Integer seasonYear;
    private Integer roundNumber;
    private Integer weekNumber;

    /**
     * Which day of the game week this fixture belongs to, 1..7 (owner, 2026-09-28).
     *
     * <p>Needed because a week has two league rounds - day 3 at 19:00 and day 7 at 16:00 - so a week
     * number alone cannot tell round A from round B, and a day-3 job would not know which fixtures are
     * its own. Null on fixtures seeded before this existed; those are read as "any day".
     */
    @jakarta.persistence.Column(name = "day_number")
    private Integer dayNumber;
    private LocalDateTime matchDate;
    /**
     * The group this fixture belongs to, for a competition with a group stage.
     *
     * <p>Null for a straight knockout — the national cup, and every league fixture. It is recorded here
     * rather than in a table of its own because a group's membership is already fully determined by who
     * appears in its fixtures, and a second record of that would be a second thing to fall out of step.
     */
    private String groupCode;

    private boolean played;

    /**
     * The {@link Match} this fixture was played into, once it has been.
     *
     * <p><b>One-directional on purpose.</b> {@code Match} carries no reference back to its fixture —
     * only copied values (the calendar day, the cup group code), because a table rebuilt from played
     * matches must not have to re-read every fixture. So this column is the only fixture → result path,
     * which is why it is indexed and unique rather than merely present.
     *
     * <p>Null until the fixture is played. A {@link Match} with no fixture is a real state, not a broken
     * one: an exhibition is simulated inline and never had a fixture to begin with.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "played_match_id", unique = true)
    private Match playedMatch;

    public Integer getDayNumber() {
        return dayNumber;
    }

    public void setDayNumber(Integer dayNumber) {
        this.dayNumber = dayNumber;
    }
}