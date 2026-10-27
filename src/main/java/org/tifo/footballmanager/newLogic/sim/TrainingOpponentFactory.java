package org.tifo.footballmanager.newLogic.sim;

import java.util.ArrayList;
import java.util.List;

import org.tifo.footballmanager.newLogic.model.Player;
import org.tifo.footballmanager.newLogic.model.Position;
import org.tifo.footballmanager.newLogic.model.SkillName;
import org.tifo.footballmanager.newLogic.model.Skills;

/**
 * The training match's opponent: eleven starters and seven reserves whose every football skill is the
 * number the manager chose.
 *
 * <p><b>Why it exists (owner, 2026-10-27).</b> The training match exists so a manager can test a tactic
 * against an opponent of a known standard, so the standard has to <em>be</em> the number on the slider
 * and nothing else. A club from the world cannot do that: its squad was generated at its own tier and
 * carries a spread of ratings nobody chose.
 *
 * <p><b>Nothing here touches the database.</b> The men are built in memory with negative ids — no row
 * in the {@code player} table can have one — so an accidental lookup for one of them finds nothing
 * rather than the wrong man. They are never saved. The opponent exists for the ninety minutes and is
 * then gone, which is the whole point of the feature: a training match changes nothing.
 *
 * <p><b>The order of the pool is the team sheet.</b> {@link RealSquadFactory#buildSquadFromOrdered}
 * takes the first eleven as the XI and the next seven as the bench, and places each man by his position
 * against the formation's slots — so the pool below is written out the way a team is written out: one
 * keeper, four at the back, four in midfield, two up front, then the reserves.
 */
public final class TrainingOpponentFactory {

    /** The band the manager picks from (owner, 2026-10-27: "od 8 do 20"). */
    public static final int MIN_STRENGTH = 8;
    public static final int MAX_STRENGTH = 20;

    /**
     * Negative ids, counting down from -1.
     *
     * <p>Distinct from a real id on purpose rather than merely unlikely to collide: a synthetic player
     * that could be mistaken for a real one would put an imaginary man in a real club's stats the first
     * time anything parsed the id back.
     */
    private static final long ID_BASE = -1L;

    /**
     * The eight football skills, and only those.
     *
     * <p>{@link SkillName#FATIGUE} is deliberately absent: it is condition, not ability, and a manager
     * who asked for a strength of 20 would get twenty-two exhausted men and a match that proves
     * nothing.
     */
    private static final SkillName[] FOOTBALL_SKILLS = {
            SkillName.STAMINA, SkillName.GOALKEEPER, SkillName.DEFENDER, SkillName.PACE,
            SkillName.TECHNIQUE, SkillName.PLAYMAKER, SkillName.PASSING, SkillName.STRIKER
    };

    private TrainingOpponentFactory() {}

    /**
     * The eleven and the reserves, already in engine form.
     *
     * <p>The engine's own {@code sim.model.Player}, not the {@code model.Player} the pool is built
     * from: the factory takes men from the database's vocabulary and returns players the match engine
     * can run, and the two are different classes on purpose.
     */
    public record Squad(List<org.tifo.footballmanager.newLogic.sim.model.Player> starters,
                        List<org.tifo.footballmanager.newLogic.sim.model.Player> bench) {}

    /**
     * The strength the manager picked, brought inside the band the engine accepts.
     *
     * <p>Clamped rather than rejected here because the caller's job is to play a match, not to police a
     * query string; the controller still refuses a strength that is not a number at all.
     */
    public static int clampStrength(int requested) {
        return Math.max(MIN_STRENGTH, Math.min(MAX_STRENGTH, requested));
    }

    /**
     * Builds the opponent at {@code strength}, in {@code formation} (null is 4-4-2, the shape the
     * bundled fallback tactics are written for).
     */
    public static Squad build(int strength, String formation) {
        int skill = clampStrength(strength);

        List<Player> pool = new ArrayList<>(18);

        // The starting eleven, in the order the slot assignment reads them.
        add(pool, Position.GK, 1, skill);
        add(pool, Position.DEF, 4, skill);
        add(pool, Position.MID, 4, skill);
        add(pool, Position.ATT, 2, skill);

        // The bench: seven more, same standard, so the opponent can make substitutions of its own.
        add(pool, Position.GK, 1, skill);
        add(pool, Position.DEF, 2, skill);
        add(pool, Position.MID, 2, skill);
        add(pool, Position.ATT, 2, skill);

        List<org.tifo.footballmanager.newLogic.sim.model.Player> bench = new ArrayList<>();
        List<org.tifo.footballmanager.newLogic.sim.model.Player> starters =
                RealSquadFactory.buildSquadFromOrdered(pool, "AWAY", bench, 1.0, formation);
        if (starters == null || starters.size() != 11) {
            throw new IllegalStateException(
                    "A training opponent needs eleven men and the factory returned "
                            + (starters == null ? "none" : starters.size()));
        }
        return new Squad(starters, bench);
    }

    private static void add(List<Player> pool, Position position, int count, int skill) {
        for (int i = 0; i < count; i++) {
            int nth = (int) pool.stream().filter(p -> p.getPosition() == position).count() + 1;
            pool.add(reserve(position, nth, skill, pool.size()));
        }
    }

    /**
     * One man at the chosen standard.
     *
     * <p>Form is set to the natural value and morale is left at its neutral default, so
     * {@link Player#performanceModifier()} is exactly 1.0 and the skill the engine plays with is the
     * number the manager picked — not that number scaled by a mood he never chose.
     */
    private static Player reserve(Position position, int nth, int skill, int index) {
        Player player = new Player();
        player.setId(ID_BASE - index);
        player.setName("Training " + position.name() + " " + nth);
        player.setPosition(position);
        player.setForm(Player.NATURAL_FORM);
        player.setHeight(1.85);

        Skills skills = new Skills();
        for (SkillName name : FOOTBALL_SKILLS) {
            skills.setSkill(name, skill);
        }
        player.setSkills(skills);
        return player;
    }
}
