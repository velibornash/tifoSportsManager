package org.tifo.footballmanager.newLogic.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * How far a rating replay has got.
 *
 * <p>The club and national replays are folds over played matches, and a season is ~89,280 of them.
 * Replaying the whole world after every matchday is O(matches^2) over a season, which is why the
 * rating only ever ran on two deliberate batch points - and why a matchday played by the clock moved
 * no rating at all: nothing on that path called the replay.
 *
 * <p>This is the resume point that makes the replay incremental. It is deliberately the highest
 * <i>id</i> folded in, not a date: ids are what the fold is ordered by as a tiebreak, and a date
 * would silently skip a match persisted late.
 */
@Entity
@Table(name = "rating_watermark")
public class RatingWatermark {

    /** The club replay. */
    public static final String CLUB = "club";

    /** The national replay. Same shape, disjoint pool by competition type. */
    public static final String NATIONAL = "national";

    @Id
    @Column(name = "name", nullable = false, length = 32)
    private String name;

    /** Highest match id folded into the ratings. Null means never replayed. */
    @Column(name = "applied_through_match_id")
    private Long appliedThroughMatchId;

    protected RatingWatermark() {
    }

    public RatingWatermark(String name, Long appliedThroughMatchId) {
        this.name = name;
        this.appliedThroughMatchId = appliedThroughMatchId;
    }

    public String getName() {
        return name;
    }

    public Long getAppliedThroughMatchId() {
        return appliedThroughMatchId;
    }

    public void setAppliedThroughMatchId(Long appliedThroughMatchId) {
        this.appliedThroughMatchId = appliedThroughMatchId;
    }
}
