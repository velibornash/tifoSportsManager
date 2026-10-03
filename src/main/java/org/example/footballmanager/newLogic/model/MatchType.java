package org.example.footballmanager.newLogic.model;

/**
 * What kind of match this is, and therefore what it changes (P2-8, owner decision 2026-10-03).
 *
 * <p><b>Every match carries one, always.</b> A match used to record only which <em>competition</em> it
 * belonged to, which left nowhere to write "friendly" or "exhibition" — those belong to no competition,
 * so the field was simply empty and the two were indistinguishable, unlabelable and unfilterable. The
 * owner asked for a type on every match so results can be filtered by it, and that is what this is.
 *
 * <p>It is a label <em>and</em> the rulebook. Putting "does this count?" in one enum rather than
 * scattering `if (friendly)` through the services means the answer to "what does an exhibition change?"
 * is one file, and the read paths can filter on the column in SQL rather than re-deriving intent.
 *
 * <p>The competition-backed values mirror {@link CompetitionType} so a match's type and its
 * competition's type can never disagree; {@link #ofCompetition} is the only bridge between them.
 */
public enum MatchType {

    /** A league fixture. Decides the table. */
    LEAGUE("League"),

    /** A cup tie. Decides ratings, never a league table. */
    CUP("Cup"),

    /** Senior internationals. */
    INTERNATIONAL("International"),

    /** A national-team tournament proper. */
    TOURNAMENT("Tournament"),

    /**
     * A negotiated match between two clubs that decides nothing.
     *
     * <p>Exists as a type because the fixtures for these were being written and could never be played:
     * they carried no competition and no matchday, so every playback path skipped them. The type is
     * what makes them visible and filterable; making them playable is a separate step.
     */
    FRIENDLY("Friendly"),

    /**
     * A manager's own practice match. Costs fatigue, carries reduced injury risk, and changes nothing
     * else — not the table, not ratings, not a player's career record, not morale or form.
     */
    EXHIBITION("Exhibition");

    private final String label;

    MatchType(String label) {
        this.label = label;
    }

    /** What a manager sees. */
    public String label() {
        return label;
    }

    /** Does this match decide a league table? */
    public boolean countsForTable() {
        return this == LEAGUE;
    }

    /**
     * Does this match move club Elo?
     *
     * <p>League and cup only. A club's rating is its performance in competition, and a practice match
     * is not a performance.
     */
    public boolean countsForRatings() {
        return this == LEAGUE || this == CUP;
    }

    /**
     * Does this match belong in a player's career record?
     *
     * <p>Everything competitive, nothing else. A goal in an exhibition is a goal the player scored and
     * the match report should show it, but it is not part of his career total — and if it were, a
     * manager could pad a striker's record with practice matches.
     */
    public boolean countsForCareer() {
        return countsForRatings() || this == INTERNATIONAL || this == TOURNAMENT;
    }

    /**
     * Relative chance of picking up an injury, against a competitive match.
     *
     * <p>Fitness is real in a practice match — a player who works ninety minutes works ninety minutes,
     * so <b>fatigue is identical whatever the type</b>, which is why there is no fatigue term here. The
     * injury risk is lower because nobody contests a practice match properly, and because the owner
     * asked for it to be lower rather than absent.
     */
    public double injuryRisk() {
        return switch (this) {
            case EXHIBITION -> 0.35;
            case FRIENDLY -> 0.6;
            default -> 1.0;
        };
    }

    /**
     * The type of a match in a competition, or {@link #FRIENDLY} when it is in none.
     *
     * <p>Null competition means the fixture was created outside a competition, which is what a friendly
     * is. An exhibition is never inferred — it has to be asked for.
     */
    public static MatchType ofCompetition(Competition competition) {
        if (competition == null || competition.getType() == null) {
            return FRIENDLY;
        }
        return switch (competition.getType()) {
            case LEAGUE -> LEAGUE;
            case CUP -> CUP;
            case INTERNATIONAL -> INTERNATIONAL;
            case TOURNAMENT -> TOURNAMENT;
        };
    }
}