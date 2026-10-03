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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "played_match_id")
    private Match playedMatch;

    public Integer getDayNumber() {
        return dayNumber;
    }

    public void setDayNumber(Integer dayNumber) {
        this.dayNumber = dayNumber;
    }
}