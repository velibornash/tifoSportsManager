package org.example.footballmanager.newLogic.model;

import com.fasterxml.jackson.annotation.JsonBackReference;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity(name = "Player")
public class Player {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    @Embedded
    private Skills skills;


    @ColumnDefault("6.0")
    private double talent;

    @ColumnDefault("24")
    private int age;
    @ColumnDefault("0")
    private double playerValue;
    @ColumnDefault("0")
    private double earnings;
    @ColumnDefault("1.8")
    private double height;
    @ColumnDefault("75")
    private double weight;
    @ColumnDefault("6.0")
    private double form;

    // The two Sprint 5.3 fields sit at the END of the class, deliberately. Player carries a positional
    // all-args constructor, so a field inserted mid-list silently reorders every argument after it and
    // breaks each caller with a "no suitable constructor" that says nothing about the real cause. This
    // is the third time this trap has been sprung in this project; familiarity is last for the same
    // reason, and the class wants a builder.



    /**
     * Dressing-room morale, 0-100. Distinct from {@link #form}: form is a week-to-week swing, morale
     * is the season-long state that decides whether the swing happens at all. Moved by
     * {@code MoraleService} on minutes played, what the player did, how the team did, and whether he
     * is paid what he is worth.
     */
    @ColumnDefault("60.0")
    private double morale = 60.0;
    @ColumnDefault("6")
    private int rating;

    @Enumerated(EnumType.STRING)
    private Position position;

    @ColumnDefault("0")
    private int totalGoals;
    @ColumnDefault("0")
    private int totalAssists;
    private Integer squadNumber;
    @ColumnDefault("false")
    private boolean injured;
    private Integer injuryDaysRemaining;
    private Integer injurySeasonNumber;
    private Integer injuryWeekNumber;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id")
    @JsonBackReference
    private Team team;

    /**
     * ISO country code of the player's nationality, e.g. {@code SRB}, {@code BRA}, {@code ESP}.
     *
     * <p>Used only by the registration rules: whether a player counts against a competition's
     * non-EU quota, and whether he needs a work permit at all. Null is treated as domestic, which
     * is the safe default for a database that predates the column.
     *
     * <p>Nullable, for the same reason {@code Team.cohesion} is: 0 means "knows nothing" and has to
     * stay expressible, so null carries the "never measured" sentinel and reads as 100.
     *
     * <p>Declared <b>last</b> on purpose. Lombok builds the all-args constructor in field order, and
     * two tests construct a Player with every argument spelled out positionally; adding a field in
     * the middle recompiled them into a call to a constructor that no longer exists.
     */
    @Column(name = "nationality", length = 3)
    private String nationality;

    /**
     * His detailed job, as distinct from his broad {@link Position}.
     *
     * <p>Stored as a name rather than an ordinal so the column survives a role being inserted in the
     * middle of the enum, and left nullable: a player created before this column existed has no role,
     * and {@link #effectiveRole()} derives one from his position rather than making every caller
     * cope with null.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "role", length = 40)
    private PlayerRole role;

    /**
     * How well this player knows the club's way of playing, 0-100 (Sprint 4.6).
     *
     * <p>Defaults to 100 rather than to "new signing", so every player already in the database is
     * established at their own club and nobody is retroactively signed into a system they have known
     * for years. A genuinely new arrival is set to 30 by the transfer settlement.
     */
    @ColumnDefault("100")

    private Double familiarity = 100.0;
    /**
     * How this player is wired, carried from the academy (Sprint 5.3, owner 2026-09-27).
     *
     * <p><b>This field is consumed.</b> It feeds the morale → growth factor in {@code MoraleService},
     * so a club that signs a difficult teenager has signed a difficult player rather than resetting him
     * at eighteen. That was the point of asking for personality to survive promotion.
     *
     * <p>Nullable means "never recorded" and is treated as {@link Personality#PROFESSIONAL}: a player
     * from before the column existed is not thereby a handful.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "personality", length = 16)
    private Personality personality;

    /**
     * Which foot he prefers. Carried from the academy for the same reason — a right-footed keeper
     * does not arrive as a lefty — and readable by anything that models technique or crossing.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "preferred_foot", length = 8)
    private PreferredFoot preferredFoot;

    /**
     * The club player this row was copied from, when the row belongs to a national team.
     *
     * <p>National squads are copies so that calling a player up does not remove them from their club
     * (see NationalTeamService.addToSquad). A copy needs a link back to its source, or the national
     * roster is a set of names with no way to tell which club each came from, and squad editing
     * cannot tell a national row from a club row. Null on every ordinary club player.
     *
     * <p>Declared last on purpose: {@code @AllArgsConstructor} grows by one parameter per field
     * wherever it is placed, so inserting it mid-class silently reorders every positional argument
     * in every caller.
     */
    @jakarta.persistence.Column(name = "source_player_id")
    private Long sourcePlayerId;


    /** The role to actually use: the stored one, or a sensible default for his position. */
    @Transient
    public PlayerRole effectiveRole() {
        return role != null ? role : PlayerRole.defaultFor(position);
    }

    /**
     * Sets the role, and the position with it.
     *
     * <p>Assigning a role is what a manager means, and it implies the position — a centre back
     * <i>is</i> a defender. Leaving the two to drift apart is how a squad screen shows a striker who
     * trains as a full back.
     */
    public void setRole(PlayerRole role) {
        this.role = role;
        if (role != null) {
            this.position = role.position();
        }
    }

    public Position getPositionEnum() {
        return position;
    }

    public double getCurrentFatigue() {
        return skills.getFatigue() * 0.7 + (10.0 - form) * 0.3;
    }

    public boolean canReceiveBall() {
        return skills.getStamina() > 0 && getCurrentFatigue() < 8.0;
    }

    public boolean isInjured() {
        return getInjuryDaysRemaining() > 0;
    }

    public int getInjuryDaysRemaining() {
        return injuryDaysRemaining == null ? 0 : injuryDaysRemaining;
    }

    // Fluent accessors for MatchSimulator compatibility
    public Long id() { return this.id; }
    public String name() { return this.name; }
    public Position position() { return position; }
    public Skills skills() { return skills; }
    public double fatigue() { return skills != null ? skills.getFatigue() : 0; }
    public int fatigueInt() { return (int) fatigue(); }

    public void addFatigue(int amount) {
        skills.setFatigue(skills.getFatigue() + amount);
    }

    public double movementModifier() {
        return skills.movementModifier((int) getCurrentFatigue());
    }
}