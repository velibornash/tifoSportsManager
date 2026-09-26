package org.example.footballmanager.newLogic.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.Data;

import java.time.Instant;

/**
 * A manager's conditional substitution plan for one match.
 *
 * <p>Server-side and keyed by match, per the owner decision of 2026-09-26. The alternative — holding
 * the plan in the browser — means a page reload at minute 55 silently loses every rule the manager
 * set at minute 0, and the rules then appear to fire at random.
 *
 * <p>The rules themselves are stored as a JSON array rather than as rows. They are only ever read
 * and written as a whole set, they are meaningless without their siblings (ordering, the reserved
 * sub slot), and a match has at most five of them. Normalising that into a child table would buy
 * nothing and cost a join on every tick of the evaluation loop.
 *
 * <p>Discarded when the match finishes. A plan is an instruction about one match; keeping it would
 * mean a stale plan silently reapplying to the next fixture.
 */
@Data
@Entity(name = "SubstitutionPlan")
public class SubstitutionPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "match_id", nullable = false, unique = true)
    private Long matchId;

    @Column(nullable = false)
    private String homeTeam;

    @Column(nullable = false)
    private String awayTeam;

    /**
     * The rules as a JSON array: trigger minute, condition, player on, player off. Kept as text so
     * the schema does not have to move every time a condition is added.
     *
     * <p>Mapped as {@code TEXT} and deliberately <b>not</b> {@code @Lob}. {@code @Lob} on a String
     * makes Hibernate emit {@code CLOB}, which H2 accepts and <b>PostgreSQL does not have</b> — so
     * the table was never created in production and conditional substitutions failed at runtime,
     * while every H2 test passed. The American-football entities already use {@code TEXT}; this is
     * now the same convention.
     */
    @Column(columnDefinition = "text")
    private String rulesJson;

    /** Minute the plan was last edited, for the live view. */
    private Instant updatedAt = Instant.now();

    public SubstitutionPlan() { }

    public SubstitutionPlan(Long matchId, String homeTeam, String awayTeam, String rulesJson) {
        this.matchId = matchId;
        this.homeTeam = homeTeam;
        this.awayTeam = awayTeam;
        this.rulesJson = rulesJson;
    }
}
