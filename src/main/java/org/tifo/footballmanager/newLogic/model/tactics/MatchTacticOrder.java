package org.tifo.footballmanager.newLogic.model.tactics;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Transient;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import org.tifo.footballmanager.newLogic.model.MatchFixture;
import org.tifo.footballmanager.newLogic.sim.tactics.TacticMatchCondition;

/**
 * One instruction to change tactic during a match (T-MATCH-6).
 *
 * <p><b>The owner's ruling, 2026-10-12:</b> *"Mogu se dodati 3 razlicite TAKTIKE. Za te 3 taktike mogu se
 * dodati 10 REDOVA SA INSTRUKCIJAMA kad na koju da se predje tj 10 puta u toku utakmice igrac moze preci
 * na drugu taktiku."* — three tactics in the plan, and ten rows of instructions saying when to switch to
 * which of them.
 *
 * <p>So this is <b>not</b> a fourth, fifth or sixth tactic. {@link MatchTacticAssignment} is the plan and
 * stays capped at three; this is a switch between the tactics the plan already holds.
 *
 * <p><b>Two decisions from the owner, both of which the shape of this row exists to serve:</b>
 *
 * <ul>
 *   <li><b>One-shot.</b> An order fires once and is done — the same model the substitution orders already
 *       use. A standing rule was the alternative and was not chosen, so {@link #firedAtMinute} records
 *       that an order has spent itself and nothing asks twice.</li>
 *   <li><b>The last matching order wins.</b> So {@link #position} is the order they were written in and the
 *       engine takes the highest, which is why it is stored rather than inferred from the id.</li>
 * </ul>
 *
 * <p>The target is one of the club's own tactics, and it does not have to be one of the three in the plan.
 * A manager who swaps to a fourth shape is expressing that the plan was three lines and this one was a
 * mistake; the engine honours it, and that is cheaper than refusing to save a row.
 */
@Entity
@Table(name = "match_tactic_order",
        indexes = @Index(name = "ix_tactic_order_fixture", columnList = "fixture_id,team"),
        uniqueConstraints = @UniqueConstraint(name = "uk_tactic_order_position",
                columnNames = {"fixture_id", "team", "position"}))
public class MatchTacticOrder {

    /** How many orders one side may set for one match. Owner ruling, 2026-10-12. */
    public static final int MAX_ORDERS_PER_SIDE = 10;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "fixture_id", nullable = false)
    private MatchFixture fixture;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tactic_id", nullable = false)
    private Tactic tactic;

    /** HOME or AWAY, as the assignments carry it. */
    @Column(nullable = false, length = 8)
    private String team;

    /** Where it sits in the list. Higher wins when two orders match at the same minute. */
    @Column(name = "position", nullable = false)
    private int position;

    @Enumerated(EnumType.STRING)
    @Column(name = "match_condition", nullable = false, length = 32)
    private TacticMatchCondition condition = TacticMatchCondition.ALWAYS;

    @Column(name = "minute_from", nullable = false)
    private int minuteFrom;

    /**
     * The minute this order fired, or null while it is still waiting.
     *
     * <p>Not persisted between matches — it is live engine state for one match — but held on the row so
     * that the row, the engine's copy of it and the report all agree about which orders have been spent.
     */
    @Transient
    private Integer firedAtMinute;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public MatchFixture getFixture() { return fixture; }
    public void setFixture(MatchFixture fixture) { this.fixture = fixture; }

    public Tactic getTactic() { return tactic; }
    public void setTactic(Tactic tactic) { this.tactic = tactic; }

    public String getTeam() { return team; }
    public void setTeam(String team) { this.team = team; }

    public int getPosition() { return position; }
    public void setPosition(int position) { this.position = position; }

    public TacticMatchCondition getCondition() { return condition; }
    public void setCondition(TacticMatchCondition condition) { this.condition = condition; }

    public int getMinuteFrom() { return minuteFrom; }
    public void setMinuteFrom(int minuteFrom) { this.minuteFrom = minuteFrom; }

    public Integer getFiredAtMinute() { return firedAtMinute; }
    public void setFiredAtMinute(Integer firedAtMinute) { this.firedAtMinute = firedAtMinute; }

    /** Whether this order is still waiting to fire. */
    public boolean isPending() {
        return firedAtMinute == null;
    }
}