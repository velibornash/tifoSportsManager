package org.example.footballmanager.repository;

import org.example.footballmanager.model.MatchFixture;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MatchFixtureRepository extends JpaRepository<MatchFixture, Long> {
    List<MatchFixture> findByCompetitionIdAndSeasonYearOrderByRoundNumberAscMatchDateAsc(Long competitionId, Integer seasonYear);
    List<MatchFixture> findByCompetitionIdAndSeasonYearAndRoundNumberOrderByMatchDateAsc(Long competitionId, Integer seasonYear, Integer roundNumber);
    List<MatchFixture> findByCompetitionIdAndSeasonYearAndRoundNumberAndPlayedFalseOrderByMatchDateAsc(Long competitionId, Integer seasonYear, Integer roundNumber);
    List<MatchFixture> findByCompetitionIdAndSeasonYearAndRoundNumberAndHomeTeamIdAndAwayTeamIdAndPlayedFalse(
            Long competitionId,
            Integer seasonYear,
            Integer roundNumber,
            Long homeTeamId,
            Long awayTeamId
    );
}
