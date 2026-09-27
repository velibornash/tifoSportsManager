package org.example.footballmanager.newLogic.model;

import com.fasterxml.jackson.annotation.JsonManagedReference;
import jakarta.persistence.*;
import org.hibernate.annotations.ColumnDefault;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@Entity(name = "Stadium")
public class Stadium {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;
    private Integer capacity;
    private String location;
    @ManyToOne(fetch = FetchType.LAZY)
    @EqualsAndHashCode.Exclude
    @JsonManagedReference
    private Team owner;

    /**
     * Face value of a standard adult ticket, in euros. Gate income is attendance multiplied by
     * this, so it is a direct lever on the club's weekly income and the manager's main stadium
     * control.
     */
    private Double ticketPrice;

    /**
     * Long-term intrinsic quality of the surface (0-100): the quality of the grass and the
     * facilities that cannot be fixed by spending this week. It decays with use and is restored
     * by maintenance, which is why it is separate from {@link #pitchCondition}.
     */
    private Double pitchQuality;

    /**
     * Current condition of the playing surface (0-100). Drops with every match played on it and
     * recovers only through maintenance spending. This is what the match engine and the crowd
     * can feel, so it moves every week rather than being a fixed profile number.
     */
    private Integer pitchCondition;

    /**
     * How much of the current maintenance programme is still funded, 0-100. The club sets a
     * maintenance budget; each week that budget is spent against pitch wear, and whatever is left
     * of the programme carries into next week. At zero the pitch keeps degrading.
     */
    private Integer maintenanceRemaining;

    private Integer condition;

    /**
     * The club's general training facilities, 1-20. Pitches, cones, a gym, the rest of it.
     *
     * <p>This field has existed in the database for a long time and did nothing at all until Sprint
     * 4.4: it was never read by the training code, so a club's training ground was a decoration on
     * a record. That is the same failure this sprint has hit three times now — the goalkeeping coach
     * who did nothing, the head coach who did nothing, and now the ground. The fix is not a new
     * entity but reading the field that was already there.
     */
    private Integer trainingQuality;

    /**
     * Strength and conditioning facilities, 1-20. The gym a club builds affects stamina work and,
     * more importantly, how often that work hurts somebody (Sprint 4.4).
     */
    private Integer gymLevel;

    /** Tactical teaching facilities — the video room, the pitch, the whiteboard. 1-20. */
    private Integer tacticalLevel;

    /** Youth development facilities. 1-20, consumed by the academy in Sprint 5. */
    private Integer youthLevel;

    /**
     * How well this club's facilities support a given kind of work, as a multiplier on growth.
     *
     * <p>Symmetric around 1.0, and deliberately so: poor facilities do not merely fail to help, they
     * <b>waste</b> the week. A squad training on a rutted pitch with no gym does not get the same
     * benefit from a coach's session as one doing it at a modern complex, and a game where the floor
     * is 1.0 would quietly say otherwise. Bounded at ±10%, the same discipline the head coach is held
     * to — a facility is a support, not a substitute for talent.
     *
     * <p>Which facility is relevant depends on the work: conditioning is a gym question, tactical
     * technique is a teaching-facilities question, and everything else is the general ground.
     */
    public double trainingFactorFor(SkillName skill) {
        if (skill == null) return 1.0;
        Integer recorded = switch (skill) {
            case STAMINA, FATIGUE -> gymLevel;
            case PASSING, PLAYMAKER, TECHNIQUE -> tacticalLevel;
            default -> trainingQuality;
        };
        // Unrecorded is neutral, which is a different thing from level 1 and the distinction is the
        // whole point. Every club that predates this feature has null in these columns, so reading
        // null as "no facilities" would quietly take 10% off the growth of every existing club in
        // the database - a retroactive nerf dressed up as a default. A club that has genuinely
        // built nothing can say so with an explicit 1.
        if (recorded == null) return 1.0;
        return 0.90 + 0.20 * ((clamp(recorded) - 1) / 19.0);
    }

    /**
     * How much this club's gym reduces the risk of training hurting somebody, 0.0 to 0.4.
     *
     * <p>The gym is the injury facility, not a general one. A superb gym with no pitch is still a
     * superb gym; weight work in a good facility is what keeps a squad fit rather than breaking it.
     */
    public double injuryProtection() {
        if (gymLevel == null) return 0.0;
        return 0.4 * ((clamp(gymLevel) - 1) / 19.0);
    }

    /**
     * A facility level, defaulting to 1 when nothing is recorded.
     *
     * <p>One, not ten and not the middle. A club whose gym level was never set has no gym, and
     * defaulting it to something respectable would hand every legacy club a free upgrade that nobody
     * paid for.
     */
    private int level(Integer value) {
        return value == null ? 1 : clamp(value);
    }

    /** Keeps a recorded level inside the scale, whatever a backfill or a typo put there. */
    private int clamp(int value) {
        return Math.max(1, Math.min(20, value));
    }

    // --- appearance and build-out (stadium page) ---

    /**
     * The eight parts of a ground the manager can colour, as CSS colours.
     *
     * <p>Four sides and four corners, because that is how a ground is actually described and it maps
     * onto a plan view. Null on any of them means "not set", and the page substitutes a neutral
     * rather than the column holding a colour nobody chose — a stored default is indistinguishable
     * from a decision the manager made.
     */
    private String northColour;
    private String southColour;
    private String eastColour;
    private String westColour;
    private String northEastCornerColour;
    private String northWestCornerColour;
    private String southEastCornerColour;
    private String southWestCornerColour;

    /**
     * How good the seats are, 1-20. Deliberately separate from capacity: a ground can hold forty
     * thousand people on concrete terraces, and the difference between terraces and a seat is most
     * of what atmosphere is.
     */
    @ColumnDefault("10")
    private Integer seatQuality = 10;

    /** Whether the ground has a roof. Affects the match day, not how many can get in. */
    @ColumnDefault("false")
    private boolean roof = false;

    /**
     * The largest this ground can be built out to, or null for no limit.
     *
     * <p>A ceiling rather than an open number, so "expand" is a decision with a finish line. A ground
     * that can grow forever is a ground nobody has to think about, and a municipal ground expandable
     * to fifty thousand is not a municipal ground.
     */
    private Integer expandableTo;

    @OneToOne(mappedBy = "stadium")
    @EqualsAndHashCode.Exclude
    @JsonManagedReference
    private Team team;
}