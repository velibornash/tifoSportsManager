package org.example.footballmanager.newLogic.model;

import jakarta.persistence.Entity;
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
@Table(name = "match_fixture")
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