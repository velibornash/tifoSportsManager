package org.example.footballmanager.newLogic.service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * PPDA — passes per defensive action.
 *
 * <p><b>The definition is on the screen, because "PPDA" is not a number a manager can act on.</b> It is the
 * standard idea — how much passing a team gets done per time the opposition has to intervene — but the
 * denominator is spelled out beside the figure, because a number whose meaning is a matter of opinion is a
 * number two managers will read as saying opposite things.
 *
 * <p><b>What is in the denominator.</b> Clearances, interceptions, blocks, deflections and fouls. Those are
 * all counted per team by the engine and all reach the stored match. <b>Tackles are not</b> — they are
 * counted per player and never aggregated to the side — so this is deliberately <em>not</em> the textbook
 * "tackles + interceptions + fouls", and the screen says which set it uses rather than implying a
 * textbook one it is not computing.
 */
public final class PassingPressure {

    private PassingPressure() { }

    /** The keys this reads, in the order it names them beside the number. */
    public static final String DEFINITION =
            "passes attempted per defensive action (clearances, interceptions, blocks, deflections, fouls)";

    /**
     * The figure, or {@code null} when the stored match predates the counts.
     *
     * <p><b>Null rather than zero.</b> A match whose {@code statsJson} predates these keys has not been
     * measured, and showing 0.0 there would read as "this team never passed", which is a claim about a
     * game rather than a statement about a column.
     */
    public static Double forSide(Map<String, Object> stats, String side) {
        if (stats == null) {
            return null;
        }
        String prefix = "HOME".equals(side) ? "home" : "away";

        Double passes = number(stats.get(prefix + "PassesAttempted"));
        if (passes == null) {
            return null;
        }

        double defensiveActions = 0.0;
        for (String key : new String[]{"Clearances", "Interceptions", "Blocks", "Deflections", "Fouls"}) {
            Double value = number(stats.get(prefix + key));
            if (value != null) {
                defensiveActions += value;
            }
        }
        if (defensiveActions <= 0.0) {
            // A team that was not pressed at all has an undefined ratio, not an infinite one.
            return null;
        }
        return Math.round((passes / defensiveActions) * 10.0) / 10.0;
    }

    /** Both sides plus the definition, in the shape the screen reads. */
    public static Map<String, Object> bothSides(Map<String, Object> stats) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("homePpda", forSide(stats, "HOME"));
        out.put("awayPpda", forSide(stats, "AWAY"));
        out.put("definition", DEFINITION);
        return out;
    }

    private static Double number(Object value) {
        if (value == null) {
            return null;
        }
        double parsed = value instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(value));
        return Double.isFinite(parsed) ? parsed : null;
    }
}