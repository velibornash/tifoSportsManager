package org.example.americanfootballmanager.repository;

import org.example.americanfootballmanager.model.AfMatchFixture;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AfMatchFixtureRepository extends JpaRepository<AfMatchFixture, Long> {
    List<AfMatchFixture> findBySeasonYearAndCompetitionIdOrderByRoundNumber(Integer seasonYear, Long competitionId);
    List<AfMatchFixture> findByHomeTeamIdOrAwayTeamIdOrderByRoundNumber(Long homeTeamId, Long awayTeamId);
    List<AfMatchFixture> findBySeasonYearAndCompetitionIdAndPlayed(Integer seasonYear, Long competitionId, Boolean played);
    List<AfMatchFixture> findBySeasonYearAndCompetitionIdAndWeekNumber(Integer seasonYear, Long competitionId, Integer weekNumber);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE AfMatchFixture f SET f.played = false, f.playedMatch = null")
    void resetAllFixtures();
}
