package org.example.footballmanager.newLogic.sim.tactics;

import org.example.footballmanager.newLogic.sim.model.Position;

/**
 * Each side's own tactical vocabulary, selected by the side asking.
 *
 * <p><b>Why this class exists.</b> {@code TacticsRules} held one grid and everybody asked it, so the away
 * side was resolved against the <b>home club's</b> vocabulary. A 4-3-3 visiting a 4-4-2 asked about
 * {@code CM}, {@code WL}, {@code WR} and {@code ST} — names a 4-4-2 grid never contains — and every one of
 * those players silently fell back to his own formation's anchor. Two clubs, one shape.
 *
 * <p><b>The perspective is already handled, and this class does not touch it.</b>
 * {@link TacticalPerspectiveTransformer} converts between editor coordinates — which are
 * <b>always home-perspective</b>, for every club, because the editor has a single frame — and physical
 * coordinates for a named team. {@link TacticsRules#desiredCell(String, Position, String)} applies that mirror
 * once, at lookup. So a club's stored grid is in the same frame whoever wrote it, and selecting the right
 * object per side is the whole of the fix. There is deliberately no mirror here: adding one would mirror
 * twice.
 *
 * <p><b>Both sides may be the same object.</b> That is the fallback for a club with no profile, and it is
 * what every caller before this class did.
 */
public final class SideTactics {

    private final TacticsRules home;
    private final TacticsRules away;

    public SideTactics(TacticsRules home, TacticsRules away) {
        this.home = home == null ? TacticsRules.defaults() : home;
        this.away = away == null ? this.home : away;
    }

    /**
     * One set of rules for both sides.
     *
     * <p>Preserves exactly the behaviour that existed before each side had its own, so a caller that has
     * only one grid — or a test that does not care — gets the old answer rather than a surprise.
     */
    public SideTactics(TacticsRules both) {
        this(both, both);
    }

    /** The rules for the side named, falling back to the home set for anything unrecognised. */
    public TacticsRules forTeam(String team) {
        return "AWAY".equals(team) ? away : home;
    }

    /** Convenience for the common case: the rules for the side a player belongs to. */
    public TacticsRules forPlayer(org.example.footballmanager.newLogic.sim.model.Player player) {
        return player == null ? home : forTeam(player.getTeam());
    }

    public TacticsRules home() {
        return home;
    }

    public TacticsRules away() {
        return away;
    }
}