package org.example.footballmanager.newLogic.repository;

/**
 * One played club match, with the two things a ranking-points replay needs and {@link ScoredMatch} does
 * not carry: <b>which season it belonged to</b> and <b>which division each club was in</b>.
 *
 * <p>{@code ScoredMatch} exists to drive an Elo replay, which never needed either — a running rating has
 * no seasons and a division gap is read off the ratings themselves. The owner's ranking system needs
 * both, and needs them <i>per match</i> rather than per club: a club promoted between seasons earned
 * different points for the same fixture in each.
 *
 * <p>Recorded as projections rather than entities on purpose, for the reason {@code ScoredMatch} already
 * sets out: a whole season of club football is tens of thousands of rows, and materialising a
 * {@code Match} with its competition, two teams and a competition tier for each of them is a result list
 * too large to hold.
 *
 * <p>Tiers are nullable rather than defaulted, because "this club was in no division" and "this club was
 * in tier 1" are different facts and {@link RankingPointsTier} is where that decision is made — once,
 * and where it can be tested.
 */
public record RankedMatch(Long id,
                          Long homeTeamId,
                          Long awayTeamId,
                          int homeGoals,
                          int awayGoals,
                          Integer seasonYear,
                          org.example.footballmanager.newLogic.model.CompetitionScope scope,
                          org.example.footballmanager.newLogic.model.CompetitionType type,
                          org.example.footballmanager.newLogic.model.CompetitionTeamType teamType,
                          org.example.footballmanager.newLogic.model.NationalStage stage,
                          Integer homeTier,
                          Integer awayTier) {

    /**
     * The division a club was in, or 1 when it was in none.
     *
     * <p>Fallback to tier 1 rather than to the bottom division, because
     * {@link org.example.footballmanager.newLogic.service.RankingPointsEngine#tierWeight} scores an
     * unknown tier at full weight — a club with no recorded division is not a bottom-division club, and
     * silently scoring it as one would under-rate it.
     */
    public int homeTierOrDefault() {
        return homeTier == null ? 1 : homeTier;
    }

    public int awayTierOrDefault() {
        return awayTier == null ? 1 : awayTier;
    }
}