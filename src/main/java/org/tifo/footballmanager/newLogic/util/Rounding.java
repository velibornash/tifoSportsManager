package org.tifo.footballmanager.newLogic.util;

/**
 * Rounding, in one place.
 *
 * <p>Thirty-seven call sites across thirty-two files carried their own copy of
 * {@code Math.round(x * 100.0) / 100.0}. There was no {@code Rounding} class, so the formula was the
 * helper: every class that needed two decimal places wrote it again, with its own name for it
 * ({@code round2}, {@code round}, {@code roundMoney}) and its own chance to be written slightly
 * differently.
 *
 * <p>The formula is not obvious enough to be copied. {@code Math.round} is <b>half up</b>, not half
 * even, and it is applied to the scaled value — so {@code 2.675} becomes {@code 2.68} and not
 * {@code 2.67}, which is the answer a reader expects and the one {@code BigDecimal} would have to be
 * told to give explicitly. Every money figure and every rating in the API goes through here, so it is
 * written once and tested once.
 *
 * <p>One decimal place and three decimal places are <b>not</b> this: they round by a different rule at
 * a different scale and stay where they are until something needs them twice.
 */
public final class Rounding {

    private static final double TWO_DECIMAL_SCALE = 100.0;

    private Rounding() {
    }

    /**
     * {@code value} rounded to two decimal places, half up — {@link Math#round}'s own rule.
     *
     * @param value anything, including {@code NaN} and infinities, which {@link Math#round} maps to 0
     *              and the extremes rather than throwing
     */
    public static double to2(double value) {
        return Math.round(value * TWO_DECIMAL_SCALE) / TWO_DECIMAL_SCALE;
    }
}
