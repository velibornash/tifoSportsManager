package org.tifo.footballmanager.newLogic.repository;

import java.time.LocalDateTime;

/**
 * One played match, as a form snapshot reads it — and nothing else.
 *
 * <p><b>Why this exists: the same defect {@link ScoredMatch} was written for, in the one place it was
 * missed.</b> {@code Match.eventJson} is 742 KB to 1,035 KB on every simulated match — the whole per-tick
 * decision log in one text column — and {@code lineupJson} and {@code statsJson} sit beside it. Measured on
 * the real rows, fetching the blob costs about 3.0 ms per row against 0.014 ms without it, so roughly
 * 215x per row.
 *
 * <p>{@code ScheduleInsightService.buildTeamSnapshots} asks each club for its <b>five most recent
 * results</b>. That is a date, two sides and a score. It does not read the log, the lineups or the stats,
 * and it did not need to read any of them — but it was handed {@code Match} entities, so Hibernate
 * selected every column anyway.
 *
 * <p><b>What that costs at world scale.</b> One matchday is 7,440 matches (48 countries x31 divisions x5
 * matches), and this runs over the whole world from {@code ClubRankingPointsService.recompute} at the end
 * of every simulation batch. A single matchday's worth is therefore roughly <b>6 GB of Strings held in a
 * single result list</b> — which is the reported "heap blows up when it starts generating all matches"
 * (owner, 2026-10-19). The replays were fixed; this caller was not, and it is the one on the button.
 *
 * <p><b>Deliberately no field for the blob.</b> {@code RecentMatchRowCarriesNoBlobTest} holds it that way.
 * Adding one back is the one change that puts the heap back.
 *
 * @param matchDate  when it was played; the snapshot sorts on it and keeps the five newest. Nullable,
 *                   because a fixture row can carry no date and the old sort treated null as oldest.
 * @param homeTeamId the home side's id, or null when the row has no home team.
 * @param awayTeamId the away side's id, or null when the row has no away team.
 */
public record RecentMatchRow(Long id,
                             LocalDateTime matchDate,
                             Long homeTeamId,
                             Long awayTeamId,
                             int homeGoals,
                             int awayGoals) {
}