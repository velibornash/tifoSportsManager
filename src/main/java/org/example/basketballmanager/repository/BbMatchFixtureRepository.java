package org.example.basketballmanager.repository;

import org.example.basketballmanager.model.BbMatchFixture;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BbMatchFixtureRepository extends JpaRepository<BbMatchFixture, Long> {
    List<BbMatchFixture> findBySeasonYearAndCompetitionIdOrderByRoundNumber(Integer seasonYear, Long competitionId);
    List<BbMatchFixture> findByHomeTeamIdOrAwayTeamIdOrderByRoundNumber(Long homeTeamId, Long awayTeamId);
    List<BbMatchFixture> findBySeasonYearAndCompetitionIdAndPlayed(Integer seasonYear, Long competitionId, Boolean played);
    List<BbMatchFixture> findBySeasonYearAndCompetitionIdAndWeekNumber(Integer seasonYear, Long competitionId, Integer weekNumber);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE BbMatchFixture f SET f.played = false, f.playedMatch = null")
    void resetAllFixtures();
}
