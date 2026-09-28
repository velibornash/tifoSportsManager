package org.example.footballmanager.newLogic.model;

import java.time.LocalTime;

/**
 * What kind of match this is, and what applies to it (owner, 2026-09-28).
 *
 * <p>The owner's reasoning: internationals and national-team matches are mostly the same thing, so
 * they should share a type that holds what they have in common, with the specifics underneath.
 *
 * <p>So this is an abstract base, not an enum. An enum cannot hold behaviour, and the differences
 * between these formats are behaviour: a two-legged tie is decided on aggregate, a knockout tie can
 * go to penalties, and a friendly can end in a draw that means nothing. Those are rules, and a
 * {@code switch} over an enum that grows a branch per format is how they end up inconsistent.
 *
 * <p>Deliberately separate from {@code CompetitionType}, which stays an enum because it is a
 * persisted discriminator and changing that is a schema migration. This class is the behaviour, the
 * enum is the key.
 */
public abstract class MatchFormat {

    private final String code;
    private final String label;
    private final LocalTime defaultKickoff;
    private final boolean twoLegs;
    private final boolean canEndLevel;
    private final boolean goesToPenalties;

    protected MatchFormat(String code, String label, LocalTime defaultKickoff,
                          boolean twoLegs, boolean canEndLevel, boolean goesToPenalties) {
        this.code = code;
        this.label = label;
        this.defaultKickoff = defaultKickoff;
        this.twoLegs = twoLegs;
        this.canEndLevel = canEndLevel;
        this.goesToPenalties = goesToPenalties;
    }

    public String code() {
        return code;
    }

    public String label() {
        return label;
    }

    public LocalTime defaultKickoff() {
        return defaultKickoff;
    }

    /** Two legs, decided on aggregate. */
    public boolean twoLegs() {
        return twoLegs;
    }

    /** Whether a draw is an acceptable result. */
    public boolean canEndLevel() {
        return canEndLevel;
    }

    public boolean goesToPenalties() {
        return goesToPenalties;
    }

    /**
     * Whether this format is played by national sides rather than clubs.
     *
     * <p>Drives the things that differ everywhere else: no transfer market, no wage bill, no
     * stadium to own, and a pool rather than a squad list.
     */
    public abstract boolean nationalSides();

    /** League football: two legs, draws allowed, no penalties. */
    public static final class League extends MatchFormat {
        public League() {
            super("LEAGUE", "League", java.time.LocalTime.of(19, 0), true, true, false);
        }

        @Override
        public boolean nationalSides() {
            return false;
        }
    }

    /**
     * A knockout cup.
     *
     * <p>Single leg and decided on the night - the owner's cup spec has one leg per round and the
     * final on week 11 day 5, with a level tie broken on penalties.
     */
    public static final class KnockoutCup extends MatchFormat {
        public KnockoutCup(LocalTime kickoff) {
            super("CUP", "Cup", kickoff, false, false, true);
        }

        @Override
        public boolean nationalSides() {
            return false;
        }
    }

    /**
     * A senior international, shared base for the national-team formats (owner, 2026-09-28).
     *
     * <p>Holds everything a senior international and a national-team match agree on: two legs
     * historically, a draw is a real result, no penalties, and the sides are national rather than
     * club - so no wages, no transfers, and form that carries across the season.
     */
    public static class NationalSide extends MatchFormat {
        public NationalSide(String code, String label, LocalTime kickoff) {
            super(code, label, kickoff, true, true, false);
        }

        @Override
        public boolean nationalSides() {
            return true;
        }
    }

    /** Senior internationals, day 1 of the game week. */
    public static final class International extends NationalSide {
        public International() {
            super("INTERNATIONAL", "International", LocalTime.of(20, 45));
        }
    }

    /**
     * A national-team match between countries' senior sides - the World Cup and its qualifiers.
     *
     * <p>Differs from {@link International} in exactly one way, and that difference is the reason the
     * base class exists: a qualifier can be drawn, but at a tournament proper a tie has to be won, so
     * knockout rounds go to penalties and the group phase does not.
     */
    public static final class Tournament extends NationalSide {
        public Tournament(LocalTime kickoff) {
            super("TOURNAMENT", "Tournament", kickoff);
        }

        @Override
        public boolean goesToPenalties() {
            return true;
        }
    }
}
