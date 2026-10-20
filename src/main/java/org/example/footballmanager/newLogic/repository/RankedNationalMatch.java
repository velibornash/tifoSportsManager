package org.example.footballmanager.newLogic.repository;

import org.example.footballmanager.newLogic.model.CompetitionScope;
import org.example.footballmanager.newLogic.model.CompetitionType;
import org.example.footballmanager.newLogic.model.NationalStage;
import org.example.footballmanager.newLogic.model.NationalTeamLevel;

/**
 * One played match between national sides, with the season and the level (P0-RANK-3).
 *
 * <p>The national counterpart of {@link RankedMatch}, and it carries a third thing the club projection
 * does not need: <b>which level the match belongs to</b>. Senior and U-21 are separate competitions with
 * separate ratings and, in this system, separate points — a country that is excellent at both is not one
 * entity that did well twice.
 *
 * <p>The level is read from the <b>competition</b>, not inferred from the team. Inferring it from the
 * team is circular: a fixture's two sides may be senior while the competition that holds it is a
 * tournament, and the competition is what the owner means by "a World Cup match".
 *
 * <p>Nullable level falls back to {@link NationalTeamLevel#SENIOR} at read time, because an
 * international with no recorded level is a senior international in every world this project has — the
 * U-21 competitions are created with a level and the senior ones predate the column.
 */
public record RankedNationalMatch(Long id,
                                 Long homeTeamId,
                                 Long awayTeamId,
                                 int homeGoals,
                                 int awayGoals,
                                 Integer seasonYear,
                                 CompetitionScope scope,
                                 CompetitionType type,
                                 NationalStage stage,
                                 NationalTeamLevel level) {

    public NationalTeamLevel levelOrDefault() {
        return level == null ? NationalTeamLevel.SENIOR : level;
    }
}
