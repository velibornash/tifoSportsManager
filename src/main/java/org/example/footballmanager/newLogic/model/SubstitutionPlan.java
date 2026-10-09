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
 * <p><b>The dead {@code match_id} column, and why every new plan used to be a 500.</b> Re-keying this
 * entity from match to fixture left the old column in the database as
 * {@code bigint NOT NULL}, while the entity stopped mapping it entirely. So the insert that every save
 * performs had nothing to put in a column the schema forbade null in:
 *
 * <pre>
 *   ERROR: null value in column "match_id" of relation "substitution_plan" violates not-null constraint
 * </pre>
 *
 * <p><b>The tests did not see it.</b> They run against H2, where the schema is generated from the
 * entity — and the entity no longer has that field, so H2 never created the column. The production
 * schema kept it. A green suite and a feature that cannot be used at all, which is the fifth time in
 * this repository that a green status has meant nothing.
 *
 * <p>The column is dropped by {@code SubstitutionPlanSchemaRepair} at startup, with this comment as the
 * reason it is safe: nothing reads it, nothing writes it, and {@code fixture_id} is the key the feature
 * actually uses.
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

    /**
     * How each rule actually went, written once when the match is simulated.
     *
     * <p><b>This is the half of the feature that did not exist.</b> The engine marks a rule
     * {@code VOID} with a {@code ConditionalSubstitutionRules.VoidReason} — the player had left the
     * squad, the substitutions were gone, the window had closed — and <b>nothing read it back</b>. So a
     * rule that never fired was indistinguishable from a rule that was never set, and a manager who
     * had quietly given an instruction that could not be honoured had no way to find out, ever. T1-16
     * closed the other half by refusing a rule that cannot fire <em>before</em> kickoff; this is the
     * half for the rules that were legal when written and still could not be honoured.
     *
     * <p>Held on the plan rather than on {@code Match} because the plan is already keyed by fixture —
     * unique, one per fixture — and {@code MatchFixture.playedMatch} is the exact link from a played
     * match back to the instructions that produced it. No new table, and no new column on a hot table.
     *
     * <p>Null before the match is played. That is honest rather than empty: nothing is known about how
     * an instruction went until there is a match for it to have gone in.
     */
    @Column(name = "outcome_json", columnDefinition = "TEXT")
    private String outcomeJson;

    public SubstitutionPlan() { }

    public SubstitutionPlan(Long fixtureId, String homeTeam, String awayTeam, String rulesJson) {
        this.fixtureId = fixtureId;
        this.homeTeam = homeTeam;
        this.awayTeam = awayTeam;
        this.rulesJson = rulesJson;
    }
}
