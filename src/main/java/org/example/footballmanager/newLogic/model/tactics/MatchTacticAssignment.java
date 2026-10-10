package org.example.footballmanager.newLogic.model.tactics;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;
import org.example.footballmanager.newLogic.model.MatchFixture;
import org.example.footballmanager.newLogic.sim.tactics.TacticMatchCondition;

/**
 * One club's instruction for one fixture: which tactic, when it applies, and in what order.
 *
 * <p><b>A fixture, not a match.</b> The plan is written before kickoff — the same reasoning as the
 * substitution plan, whose decisions close an hour before — and a {@code Match} row does not exist until
 * the simulation that consumes it has already run.
 *
 * <p><b>It references the tactic; it does not copy its rules.</b> An edit to a tactic after a fixture has
 * been assigned therefore reaches the match. That is the deliberate choice: a manager who fixes a tactic on
 * the morning of Saturday means to use the fixed one on Saturday, and a snapshot would silently play the
 * version they had already corrected. The consequence is that an assignment can outlive the thing it
 * points at, so the read side refuses an assignment whose tactic no longer exists and falls back — see
 * {@code MatchTacticPlan}.
 *
 * <p><b>Max three per club per fixture</b>, and the rule is in the service rather than here for the same
 * reason the library's one-default rule is: a check constraint one database enforces and another ignores is
 * a constraint nobody can rely on.
 */
@Data
@Entity
@Table(name = "match_tactic_assignment",
        uniqueConstraints = {
                // Two assignments of the same tactic to the same side is one instruction written twice.
                @UniqueConstraint(name = "uk_assignment_fixture_team_priority",
                        columnNames = {"fixture_id", "team", "priority"})
        },
        indexes = {
                @Index(name = "ix_assignment_fixture", columnList = "fixture_id")
        })
public class MatchTacticAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "fixture_id", nullable = false)
    private MatchFixture fixture;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tactic_id", nullable = false)
    private Tactic tactic;

    /** {@code HOME} or {@code AWAY}. Each club sets its own, and each is read from its own point of view. */
    @Column(nullable = false, length = 8)
    private String team;

    /** 1 is the first thing tried. Lower wins when several conditions are true at once. */
    @Column(nullable = false)
    private int priority;

    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(name = "match_condition", nullable = false, length = 32)
    private TacticMatchCondition condition = TacticMatchCondition.ALWAYS;

    /** The minute from which this instruction may apply. Zero means from kickoff. */
    @Column(name = "minute_from", nullable = false)
    private int minuteFrom;
}