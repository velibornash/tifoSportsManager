package org.tifo.footballmanager.newLogic.fantasy;

import org.tifo.footballmanager.newLogic.model.Position;

/**
 * The fantasy scoring table (owner, 2026-10-17).
 *
 * <p>Every line of it reads a column that is already stored on {@code MatchPlayerStats}, except the clean
 * sheet, which is worked out from the scoreline so that it applies to midfielders as well as to
 * goalkeepers and defenders. Nothing is invented and no new metric is computed.
 *
 * <pre>
 *   played 1-59 minutes          1
 *   played 60 or more            2
 *   goal, goalkeeper/defender    6
 *   goal, midfielder (and winger) 5
 *   goal, attacker               4
 *   assist                       3
 *   clean sheet, goalkeeper/defender, 60+ minutes   4
 *   clean sheet, midfielder, 60+ minutes            1
 *   every three saves, goalkeeper                   1
 *   yellow card                 -1
 *   red card                    -3
 *   bonus, top three ratings in the match     3 / 2 / 1
 * </pre>
 *
 * <p><b>Bonus comes from the existing match rating, not from a new engine.</b> The rating is already
 * stored, already on a 0–100 scale with a real spread, and already what the game shows a manager as a
 * player's form. Inventing a bonus-points system on top of it would be a second opinion about the same
 * match.
 *
 * <p><b>The captain is not here.</b> Doubling is a fact about a manager's squad, not about a player's
 * match, so it is applied where the squad is read. A rule that lived here would double a player for every
 * manager who happened to own him.
 */
public final class FantasyPointsRules {

    private FantasyPointsRules() {
    }

    /** What one player's match was worth. Never negative in practice, but not clamped either. */
    public static int points(FantasyMatchPerformance performance) {
        if (performance == null || performance.minutesPlayed() <= 0) {
            return 0;
        }
        Position position = FantasyRules.fantasyPositionOf(performance.position());
        int points = appearancePoints(performance.minutesPlayed());
        points += goalPoints(position) * performance.goals();
        points += 3 * performance.assists();
        points += cleanSheetPoints(position, performance.minutesPlayed(), performance.conceded());
        points += performance.saves() / 3;
        points -= performance.yellowCards();
        points -= 3 * performance.redCards();
        points += performance.bonusPoints();
        return points;
    }

    /** One for turning up, two for staying. */
    public static int appearancePoints(int minutesPlayed) {
        if (minutesPlayed <= 0) {
            return 0;
        }
        return minutesPlayed >= 60 ? 2 : 1;
    }

    /** A goal is worth more the further back the player plays. */
    public static int goalPoints(Position fantasyPosition) {
        return switch (fantasyPosition) {
            case GK, DEF -> 6;
            case MID -> 5;
            case ATT -> 4;
            default -> 0;
        };
    }

    /**
     * The clean sheet, on the scoreline rather than on the stored flag.
     *
     * <p>{@code MatchPlayerStats.cleanSheet} is only ever set for goalkeepers and defenders, so using it
     * here would mean a midfielder could never earn his point and a second rule would have to be written
     * beside it. One rule, read from the goals conceded, covers every position — and the sixty-minute
     * condition is the same one the stored flag already uses.
     */
    public static int cleanSheetPoints(Position fantasyPosition, int minutesPlayed, int conceded) {
        if (conceded != 0 || minutesPlayed < 60) {
            return 0;
        }
        return switch (fantasyPosition) {
            case GK, DEF -> 4;
            case MID -> 1;
            default -> 0;
        };
    }

    /** The top three ratings in a match are worth three, two and one. */
    public static int bonusFor(int rankInMatch) {
        return switch (rankInMatch) {
            case 0 -> 3;
            case 1 -> 2;
            case 2 -> 1;
            default -> 0;
        };
    }
}
