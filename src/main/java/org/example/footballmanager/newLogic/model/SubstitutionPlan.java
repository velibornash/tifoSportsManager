package org.example.footballmanager.newLogic.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;

import java.time.Instant;

/**
 * A manager's conditional substitution plan for one fixture.
 *
 * <p>Server-side, and keyed by <b>fixture</b> rather than by match. That key is the whole design and it
 * was wrong when this was written.
 *
 * <p><b>Why not the match.</b> It was keyed by {@code matchId}, which means a plan could only be created
 * once a {@link Match} row existed — that is, <b>after the match had already been simulated</b>. A rule
 * set before kickoff had nowhere to go, and a rule set during the match arrived after the engine that
 * would honour it had finished running. The owner's rule is that substitution decisions close
 * <b>an hour before kickoff</b>, which is before any match exists.
 *
 * <p><b>Why the fixture, and why there is no ambiguity about which match that fixture became.</b>
 * {@link MatchFixture#getPlayedMatch()} is the only fixture → result path, and it is a unique indexed
 * column: one played match belongs to at most one fixture, and {@code SimMatchService} sets it in the
 * same block that sets {@code played = true}. So a plan written against a fixture is read by exactly one
 * simulation — the one for that fixture — and the engine already has the fixture in hand when it reads it,
 * so the lookup costs nothing.
 *
 * <p>The alternative — resolving fixture → match at read time — would put the join in the hot path of
 * every kickoff, and would reintroduce the guess this repository has already been burned by three times.
 *
 * <p>The rules are stored as a JSON array rather than as rows. They are only ever read and written as a
 * whole set, they are meaningless without their siblings (ordering, the reserved sub slot), and a match
 * has at most five of them. Normalising that into a child table would buy nothing and cost a join on
 * every tick of the evaluation loop.
 *
 * <p>One plan per fixture, enforced by a unique constraint: a second PUT replaces the set rather than
 * adding to it, and two rows for one fixture would mean the engine had to choose which to honour.
 *
 * <p>Discarded when the match finishes. A plan is an instruction about one match; keeping it would mean
 * a stale plan silently reapplying to the next fixture.
 */
@Data
@Entity(name = "SubstitutionPlan")
@Table(name = "substitution_plan",
        uniqueConstraints = @UniqueConstraint(name = "uk_substitution_plan_fixture",
                columnNames = "fixture_id"))
public class SubstitutionPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The fixture this plan is for. The key, and unique. */
    @Column(name = "fixture_id", nullable = false, unique = true)
    private Long fixtureId;

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

    public SubstitutionPlan(Long fixtureId, String homeTeam, String awayTeam, String rulesJson) {
        this.fixtureId = fixtureId;
        this.homeTeam = homeTeam;
        this.awayTeam = awayTeam;
        this.rulesJson = rulesJson;
    }
}
