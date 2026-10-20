package org.example.footballmanager.newLogic.model;

/**
 * Which kind of competition a row is.
 *
 * <p>This is a persisted discriminator, so it is an enum and stays one. What a match in each of these
 * <i>may do</i> — whether it is allowed to finish level, whether it has to be settled — is not on this
 * enum and is not on {@code Competition} either, because a cup needs both answers at once: five drawable
 * group matchdays and five undecidable knockout rounds inside one competition. That question is answered
 * per match, by whether the match carries a cup group code. See {@code Match.groupCode} and
 * {@code MatchType.isGroupMatch}.
 *
 * <p>{@code MatchFormat} used to answer it as a per-competition type and was deleted on the owner's
 * decision, 2026-10-06: it had zero callers, and its own {@code Tournament} subclass admitted the flaw in
 * its javadoc.
 */
public enum CompetitionType {
    LEAGUE,
    /** Senior internationals between countries' senior sides. */
    INTERNATIONAL,
    /** A national-team tournament proper: World Cup and qualifiers. */
    TOURNAMENT,
    /**
     * A cup.
     *
     * <p>Domestic or continental is told apart by {@code Competition.scope}, not here: the domestic cup
     * and the fifteen international club cups are the same type, and {@code scope} is the only column that
     * says the entrants come from several countries.
     */
    CUP
}