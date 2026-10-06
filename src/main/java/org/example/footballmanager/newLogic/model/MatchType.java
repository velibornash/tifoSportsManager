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

    /**
     * Does this match decide a competition table? — P0-CUPS-1.
     *
     * <p><b>It takes the match, and that is the correction.</b> This used to take nothing and answer
     * yes for {@link #LEAGUE} only, which said <i>no</i> to every cup match — so the eight tables of a
     * Champions Cup stayed at zero and "top two advance" fell through {@code LeagueTableOrder} to its
     * last key, team id. Every group was decided by seed order before a ball was kicked. A rule that
     * cannot see the match cannot answer a question about the match, so the no-argument form is gone
     * rather than kept beside it: it would still be right for a domestic cup and wrong for a
     * continental one, which is the worst shape a helper of this kind can have.
     *
     * <p>Group membership is read from the match's own {@code groupCode} rather than from its round
     * number, because the two cups in this world disagree about what a round number means: the domestic
     * cup's rounds 1–5 are knockout ties and the continental cups' rounds 1–5 are a group stage.
     */
    public boolean countsForTable(Match match) {
        if (this == LEAGUE) {
            return true;
        }
        return this == CUP && isGroupMatch(match);
    }

    /**
     * Whether this match was played inside a cup group.
     *
     * <p>Null-safe both ways: a match that was never given a group still has an answer, and a
     * competition with no group stage never sets one.
     */
    public static boolean isGroupMatch(Match match) {
        if (match == null || match.getGroupCode() == null) {
            return false;
        }
        String code = match.getGroupCode().trim();
        return !code.isEmpty();
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