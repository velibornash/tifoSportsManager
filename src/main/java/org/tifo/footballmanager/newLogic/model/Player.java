package org.tifo.footballmanager.newLogic.model;

import com.fasterxml.jackson.annotation.JsonBackReference;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "player",
        indexes = {
                @Index(name = "ix_player_team", columnList = "team_id"),
                @Index(name = "ix_player_team_position", columnList = "team_id,position"),
                // For the weekly fatigue recovery query, `fatigue > 0`. Measured on a Serbian world
                // (10,130 players, 2,400 tired) with EXPLAIN ANALYZE: without this the query is a Seq
                // Scan over the whole player table and is *slower* than the findAll() it replaced —
                // 4.75 ms against 3.77 ms, both reading the same 753 buffers — because narrowing the
                // result set does not narrow the scan when nothing can seek it. With it: 0.79 ms and
                // 279 buffers, an Index Scan, 4.8x faster than the load it replaced.
                //
                // A PARTIAL index (`WHERE fatigue > 0`) is the textbook form here, and it was measured
                // that way — but `jakarta.persistence.Index` cannot declare one, so building it would
                // mean hand-written DDL and a migration path. A plain btree over one small integer column
                // is cheap enough that the machinery is not worth it. Revisit if this table ever
                // carries a million rows at a 2% tired rate.
                @Index(name = "ix_player_fatigue_tired", columnList = "fatigue"),
                // The retirement sweep asks "club players at or past their retirement age who have not
                // retired". Without this it is a seq scan over every player in the world, once a season.
                // `team_id` leads because a retired player is not on a club any more and must not be
                // re-examined; `retired_season` follows so a retired row is excluded from the seek
                // rather than fetched and filtered. The age column is deliberately last: the sweep
                // filters a range on it, and a range on a trailing column of an index is still a range
                // on the leading two, which is what makes this an index scan rather than a scan.
                @Index(name = "ix_player_team_not_retired", columnList = "team_id,retired_season,age"),
        })
@Entity(name = "Player")
@org.hibernate.annotations.BatchSize(size = 50)
public class Player {

    /**
     * <b>Players are loaded in batches of fifty.</b>
     *
     * <p>A match reads its two squads three times over: the match stats, the career totals and the
     * condition the match left behind. Each of those loops asked for one player at a time, so one
     * match asked the database about fifty players - and a matchday is one match in every division
     * of the country, all of it in the hour before the kickoff.
     *
     * <p>With the batch, the same fifty players come in one query. The writes were never the
     * problem: they are already batched (the JDBC batch size is 50), and the second and third loop
     * write the same managed player, so it is written once.
     */


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

    /**
     * The form a player settles back toward when nothing is happening to him.
     *
     * <p>Six is the middle of the zero-to-ten scale. {@code MoraleService} settles the form back
     * to it every week, and it is the point at which a player's form makes no difference to his
     * performance — see {@link #performanceModifier()}.
     */
    public static final double NATURAL_FORM = 6.0;

    /** The morale at which a player's mood makes no difference to his performance. */
    public static final double NEUTRAL_MORALE = 60.0;

    /** How much one point of form is worth, as a fraction of a player's ability. */
    public static final double FORM_EFFECT_PER_POINT = 0.01;

    /** How much one point of morale is worth, as a fraction of a player's ability. */
    public static final double MORALE_EFFECT_PER_POINT = 0.0008;

    /** The floor and the ceiling of {@link #performanceModifier()}. */
    public static final double MIN_PERFORMANCE_MODIFIER = 0.90;
    public static final double MAX_PERFORMANCE_MODIFIER = 1.10;

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

    /**
     * When this player last appeared in a match (owner, 2026-09-29).
     *
     * <p>Daily recovery needs to know who actually played. Without it, every player in the database
     * recovers every day - including reserves who were not in the squad, who would tire from matches
     * they never played in.
     */
    @jakarta.persistence.Column(name = "last_played_at")
    private java.time.LocalDateTime lastPlayedAt;

    /**
     * The season this player retired, or null while he is still playing (P2-7).
     *
     * <p>Null rather than a status enum: {@code Player} has no status column and no CHECK constraint to
     * add a value to, and "null = still playing" is the honest reading of a career that has not ended.
     *
     * <p><b>Declared last on purpose</b>, like {@code sourcePlayerId} and {@code lastPlayedAt}:
     * {@code @AllArgsConstructor} grows by one parameter per field wherever it is placed, so inserting
     * it mid-class silently reorders every positional argument in every caller. Two tests construct
     * {@code Player} with every field spelled out.
     */
    @jakarta.persistence.Column(name = "retired_season")
    private Integer retiredSeason;

    /** Whether he has hung up his boots. */
    public boolean isRetired() {
        return retiredSeason != null;
    }




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

    /**
     * The form and the morale, as one factor on what the engine gives this player.
     *
     * <p><b>What this is for.</b> Until 2026-10-22 the form and the morale were written, read,
     * displayed, and had <b>no effect on the football at all</b>: the engine read a player's eight
     * skills and nothing else, so a player in the form of his life and a miserable one were
     * identical on the pitch. The form decided how he was <em>rated</em> and whether he was
     * <em>picked</em>; the morale decided how fast he <em>trained</em>. Neither touched a pass.
     *
     * <p><b>How much.</b> Deliberately small. A form of ten is four points above the natural level
     * and a form of two four below, and a point of form is worth one percent: so a player in peak
     * form is about <b>3% better</b> and a player in a bad run about <b>4% worse</b>, and a miserable
     * one a little worse again. The two are <b>added, not multiplied</b>, so the worst case in a real
     * world is around <b>−6%</b> and the best around <b>+5%</b>. Enough that a manager who reads
     * the squad list can see why his striker is not scoring; not enough that one week of form
     * decides a title.
     *
     * <p><b>Where it is applied.</b> In {@code RealSquadFactory}, which is the single funnel where
     * a real player's ability becomes the engine's ability — so there is no path by which this
     * factor is quietly bypassed.
     */
    public double performanceModifier() {
        // **The same reading the rest of the codebase uses.** A form of zero means "never set", not
        // "as bad as it gets" — `MoraleService.formOf` says so and `applyMatch` reads it the same
        // way. A player whose form was never written must not be treated as a player in the worst
        // run of his life, and a fresh `new Player()` has a form of zero.
        double effectiveForm = form <= 0 ? NATURAL_FORM : form;
        double formEffect = (effectiveForm - NATURAL_FORM) * FORM_EFFECT_PER_POINT;
        double moraleEffect = (morale - NEUTRAL_MORALE) * MORALE_EFFECT_PER_POINT;
        double factor = 1.0 + formEffect + moraleEffect;
        return Math.max(MIN_PERFORMANCE_MODIFIER, Math.min(MAX_PERFORMANCE_MODIFIER, factor));
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

    public java.time.LocalDateTime getLastPlayedAt() {
        return lastPlayedAt;
    }

    public void setLastPlayedAt(java.time.LocalDateTime lastPlayedAt) {
        this.lastPlayedAt = lastPlayedAt;
    }

    /**
     * The player's career rating: what he is worth, on a 1-100 scale, derived from his skills and
     * his position (owner decision, 2026-09-29).
     *
     * <p>There were three writers of the {@code rating} column and they meant three different
     * things. The bot seeder wrote a skill proxy as {@code BASE_SKILL * 8}, the academy wrote a
     * hardcoded 50, and the match engine overwrote it with <em>the last match's</em> rating on every
     * match. {@code PlayerDTO.calculateOverall} then read that one column three times, as a career
     * value — so a player's displayed OVR depended on which seeder created his row and on whether he
     * had played since. In a freshly seeded world: 2 350 players at 96 and 5 250 at 0, and identical
     * ability six OVR points apart.
     *
     * <p>It is one thing now, and it is computed rather than stored-and-guessed. The
     * {@code rating} column is kept, because it is read by queries and rendered, and is expected to
     * hold this value; {@code PlayerRatingBackfill} brings existing rows onto it.
     *
     * <p>Deliberately built on {@code getRatingScore}, which is already the position-weighted skill
     * sum the OVR formula normalises, so the two cannot drift apart. "How he has been playing lately"
     * is {@code form}, which {@code MoraleService} writes weekly and which OVR already reads.
     */
    public int careerRating() {
        if (skills == null) return 1;
        Position position = getPositionEnum() != null ? getPositionEnum() : Position.MID;
        double normalised = skills.getRatingScore(position) / maxRatingScore(position);
        return (int) Math.round(Math.max(1.0, Math.min(100.0, normalised * 100.0)));
    }

    /**
     * The rating score a player of this position would score at the maximum for every skill he
     * trains on. Shared with the OVR formula, which already had its own copy of these numbers — a
     * second copy is how the two would drift.
     *
     * <p>The maximum is 17, not 20. The visible skills run 1-20 but a player at 20 across the board
     * is not reachable, and the existing OVR normaliser already assumed 17: four of its five
     * per-position maxima were exactly 17 times the weight sum. The fifth, the defender's, was 93.6
     * where 17 gives 98.6 — hand-tweaked and out of step with the other four. It is 98.6 now, which
     * lowers a defender's OVR slightly, and that is the point of having one definition.
     */
    public static double maxRatingScore(Position position) {
        double maxSkill = 17.0;
        return switch (position) {
            case GK -> (2.0 + 1.0 + 1.0 + 0.5) * maxSkill;
            case DEF -> (1.5 + 1.5 + 1.0 + 1.0 + 0.8) * maxSkill;
            case MID -> (1.0 + 1.2 + 2.0 + 1.5 + 0.7) * maxSkill;
            case ATT -> (2.0 + 1.5 + 2.0 + 0.5) * maxSkill;
            case WNG -> (2.0 + 1.5 + 2.0) * maxSkill;
        };
    }

    /**
     * Whether this player was made by the bot squad generator, and is not a real player.
     *
     * <p><b>It used to be the name that said so</b> — {@code A. SRB-GK01} — and the owner's complaint
     * that the AI sides field "N. SRB-GK01" instead of people (redesign.md R-23) meant the name had
     * to change. A name that reads like a person's name cannot carry a flag, so the flag moved here:
     * a column is what it takes to say "this row is a stand-in" without saying it in the name.
     *
     * <p>The name pattern is still recognised for the rows that already exist — every world built
     * before this column was written has its AI squads named the old way, and a guard that forgot
     * them would treat them as real players and leave an activated country's national side fielding
     * stand-ins.
     *
     * <p>Declared <b>last</b>, for the same reason {@code nationality} is: Lombok builds the all-args
     * constructor in field order, and two tests construct a Player with every argument spelled out
     * positionally.
     *
     * <p><b>The default is in the column definition</b>, and it has to be: a plain
     * {@code ADD COLUMN bot_player boolean NOT NULL} is refused by PostgreSQL on a table that has
     * rows - "column bot_player of relation player contains null values" - and the world's players
     * are ten thousand rows long before this column is added. With {@code DEFAULT false} the
     * existing rows are filled in and the column is not null from the start.
     */
    @Column(name = "bot_player", nullable = false, columnDefinition = "boolean not null default false")
    private boolean botPlayer = false;

    /**
     * How this injury happened, in a sentence a manager can read.
     *
     * <p><b>Owner, 2026-10-22:</b> *"bilo bi dobro ako mozemo da saznamo kako i zasto bas ovaj
     * igrac (jel imao fatigue, jel bio duel, jel izvrsena izmena?)"* - it would be good if we could
     * find out how and why this particular player got hurt: was he fatigued, was it a duel, was a
     * substitution made?
     *
     * <p>The engine knew all three at the moment it injured him and wrote none of them down. The
     * record said only *"INJURY Nenad Kačar(DR) - KNEE, 34 days, at 14'"* - which is the same in
     * every injury there has ever been.
     *
     * <p>Declared <b>last</b>, for the reason {@code botPlayer} is: Lombok builds the all-args
     * constructor in field order, and three tests spell every argument out positionally.
     */
    @Column(name = "injury_cause", length = 240)
    private String injuryCause;

    /**
     * Whether the medical centre has already operated on this injury.
     *
     * <p>One operation per injury, because the operation <em>halves</em> what is left and halving it
     * twice is not a medical decision but a money sink. The flag is what stops the button offering
     * itself twice.
     */
    @Column(name = "injury_surgery_done", nullable = false,
            columnDefinition = "boolean not null default false")
    private boolean injurySurgeryDone = false;

    /**
     * How many weeks this player has been at his current club.
     *
     * <p><b>Owner, 2026-10-22 (P0-17, without the tax):</b> no sale tax, but the club should be able
     * to see how long a player has been with it. Incremented once a week for every player; reset to
     * zero when he moves clubs.
     */
    @Column(name = "weeks_at_club", nullable = false, columnDefinition = "int not null default 0")
    private int weeksAtClub = 0;
}