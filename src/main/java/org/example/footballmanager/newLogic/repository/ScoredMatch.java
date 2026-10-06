package org.example.footballmanager.newLogic.repository;

import org.example.footballmanager.newLogic.model.CompetitionScope;
import org.example.footballmanager.newLogic.model.CompetitionType;
import org.example.footballmanager.newLogic.model.NationalStage;

/**
 * One played match, as a rating replay reads it — and nothing else.
 *
 * <p><b>Why this exists: {@code Match.eventJson} is 742 KB to 1,035 KB on every simulated match.</b> It
 * is the whole per-tick decision log — every tick, every player, {@code DECISION} / {@code PASS} /
 * {@code RECEIVE} with a human-readable description — written into one text column by
 * {@code SimMatchService}. Measured on the real rows: fetching it costs <b>3.0 ms per row</b> against
 * <b>0.014 ms</b> for the same row without it, so <b>215× per row</b>, and 155 matches' worth is already
 * 130 MB of text for 155 rows.
 *
 * <p>Both Elo replays read a match's date, its two sides and its score, and <b>neither reads the
 * blob</b>. Returning {@code Match} entities made Hibernate select it anyway: one replay season is
 * 89,280 matches, so <b>~66 GB of Strings in a single result list</b> — not slow, unable to run. That is
 * why this is a projection rather than a lazy attribute. {@code @Basic(fetch = LAZY)} would fix these two
 * callers and hand {@code GoalEventRepository} an extra query per row for the two places that genuinely
 * parse the log, which is the same trade one field-width narrower.
 *
 * <p><b>The sides are ids and names, not {@code Team} references</b>, so the replay loads no proxies and
 * asks the database for no second row per team. A null side survives as a null id, which lands in the
 * "no league division, not rated" branch instead of the {@code NullPointerException} the entity version
 * threw — a corrupt row no longer stops the other fourteen thousand clubs being rated.
 *
 * <p><b>There is deliberately no field for the blob, and {@code ScoredMatchCarriesNoBlobTest} holds it
 * that way.</b> Adding one back is the one change that puts 66 GB back on the table.
 *
 * @param scope       the competition's scope, or null when it has no competition — the replay reads a
 *                    missing competition as a league match rather than refusing to rate the row
 */
public record ScoredMatch(Long id,
                          Long homeTeamId,
                          String homeTeamName,
                          Long awayTeamId,
                          String awayTeamName,
                          int homeGoals,
                          int awayGoals,
                          CompetitionScope scope,
                          CompetitionType type,
                          NationalStage stage) {

    /**
     * The competition's stage, or {@link NationalStage#OTHER} when it has none.
     *
     * <p>Null-safe because a missing competition arrives here as a null type too, and a replay that
     * threw on it would stop every other country in the world being rated because of one corrupt row.
     *
     * <p>The stage travels with the projection rather than being derived from the type because the two
     * are different questions: {@code TOURNAMENT} covers both a qualifier and a World Cup, and the
     * owner wants those weighted differently.
     */
    public NationalStage stage() {
        return stage == null ? NationalStage.OTHER : stage;
    }
}
