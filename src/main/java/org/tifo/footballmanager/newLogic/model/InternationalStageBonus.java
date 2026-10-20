package org.tifo.footballmanager.newLogic.model;

/**
 * Elo for reaching a stage of an international club cup (owner, 2026-10-14).
 *
 * <p>Playing the matches was never the gap — those have moved Elo at {@code MatchValue.INTERNATIONAL}
 * = 1.35 since the replay was written. What did not exist was a bonus for <b>getting there</b>: a club
 * that survived three rounds and went out had done something the rating could not see, because a rating
 * only ever reads results.
 *
 * <h2>How the owner's stages map onto the bracket</h2>
 *
 * <p>The owner gave six stages — entry, 1/16, 1/4, 1/2, finalist, winner — against cups that have
 * <b>four rounds and no round of 16</b>: the Champions and Challenge cups enter 48 clubs and the Masters
 * 96, all with 24 ties in every round. Asked how six stages fit four rounds, the answer was to map by
 * bracket position: entering the cup is <i>entry</i>, and rounds 1 to 4 are <i>1/16, 1/4, 1/2</i> and then
 * <i>winner or finalist</i>.
 *
 * <p>The round-of-16 name does not match 48 clubs, and that is recorded rather than papered over. What
 * the ladder actually means here is <b>how deep you went</b>, which is what a manager feels and what the
 * bonus is for.
 *
 * <h2>Cumulative, and why it must be</h2>
 *
 * <p>The owner was explicit: every stage reached pays, and the stages add up. A Champions Cup winner
 * takes 50 + 50 + 75 + 150 + 200 = <b>525</b>, and a finalist who loses takes 50 + 50 + 75 + 150 + 150 =
 * <b>475</b>. A winner's margin over a finalist is therefore only the winner bonus, which is the shape
 * the owner drew.
 *
 * <p>There is no separate "final" stage. The owner corrected this: only <b>winner</b> and
 * <b>finalist (lost in the final)</b>.
 *
 * <h2>Idempotent by construction</h2>
 *
 * <p>{@link org.tifo.footballmanager.newLogic.service.ClubRatingService} replays the rating from the
 * match table rather than incrementing it, precisely so a re-run cannot rate a match twice. A stage
 * bonus read as "this club appears in round N" has the same property for free — it is recomputed on
 * every replay and cannot be counted twice, because nothing is counted.
 */
public enum InternationalStageBonus {

    CHAMPIONS("Champions", 50, 50, 75, 150, 150, 200),
    MASTERS("Masters", 30, 30, 35, 50, 60, 100),
    CHALLENGE("Challenge", 20, 20, 25, 40, 50, 90);

    /** Matches on this, so "Tier 2 Champions Cup" gets the Champions ladder. */
    private final String cupName;
    private final int entry;
    private final int roundOne;
    private final int roundTwo;
    private final int roundThree;
    private final int finalist;
    private final int winner;

    InternationalStageBonus(String cupName, int entry, int roundOne, int roundTwo, int roundThree,
                            int finalist, int winner) {
        this.cupName = cupName;
        this.entry = entry;
        this.roundOne = roundOne;
        this.roundTwo = roundTwo;
        this.roundThree = roundThree;
        this.finalist = finalist;
        this.winner = winner;
    }

    /**
     * The ladder for a competition name, or {@code null} when it is not one of the three.
     *
     * <p>Matched on the word, not by equality, because every tier has its own competition row —
     * "Tier 2 Champions Cup" is the same cup as "Champions Cup" at a different level, and writing six
     * separate ladders would be six places for them to drift apart.
     */
    public static InternationalStageBonus forCup(String competitionName) {
        if (competitionName == null) {
            return null;
        }
        for (InternationalStageBonus bonus : values()) {
            if (competitionName.contains(bonus.cupName)) {
                return bonus;
            }
        }
        return null;
    }

    /**
     * What a club has earned for how deep it went in one cup, counting its final round.
     *
     * @param deepestRound 1 for reaching round 1, 4 for reaching the last round; 0 means it entered but
     *                      was drawn out before playing
     * @param wonRound     whether it won the last round it reached
     */
    public int bonusFor(int deepestRound, boolean wonRound) {
        int total = entry;
        if (deepestRound >= 1) total += roundOne;
        if (deepestRound >= 2) total += roundTwo;
        if (deepestRound >= 3) total += roundThree;
        if (deepestRound >= 4) total += wonRound ? winner : finalist;
        return total;
    }

    public int entryBonus() {
        return entry;
    }

    public String cupName() {
        return cupName;
    }
}
