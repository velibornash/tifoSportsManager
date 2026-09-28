package org.example.footballmanager.newLogic.service;

/**
 * Which stage of a national-team competition a match belongs to (owner, 2026-09-28).
 *
 * <p>Exists purely so {@link RatingEngine#nationalK} can weight a match by how much it mattered. A
 * World Cup win and a qualifying win are both "a national team won", and treating them the same is
 * how a rating ends up measuring nothing.
 *
 * <p>{@link #QUALIFYING} and {@link #WORLD_CUP} are the two the owner named. {@link #OTHER} is the
 * safe default — friendlies, and any stage added later that nobody has thought about yet — and it is
 * weighted <b>lowest</b> deliberately. A stage nobody has classified should cost a rating a little,
 * not silently gain it the same as a World Cup game.
 */
public enum NationalStage {

    /** A match at the World Cup itself. Worth the most. */
    WORLD_CUP,

    /** A qualifier. Worth meaningfully more than a friendly, less than a World Cup match. */
    QUALIFYING,

    /** Friendlies and anything unclassified. The lowest weight. */
    OTHER
}
