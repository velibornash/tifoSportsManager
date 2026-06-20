package org.example.basketballmanager.repository;

import org.example.basketballmanager.model.BbCompetitionEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BbCompetitionEntryRepository extends JpaRepository<BbCompetitionEntry, Long> {
    List<BbCompetitionEntry> findBySeasonCompetitionId(Long seasonCompetitionId);
    Optional<BbCompetitionEntry> findBySeasonCompetitionIdAndTeamId(Long seasonCompetitionId, Long teamId);
    List<BbCompetitionEntry> findByTeamId(Long teamId);

    @Modifying
    @Query("UPDATE BbCompetitionEntry e SET e.points = 0, e.wins = 0, e.losses = 0, e.pointsScored = 0, e.pointsConceded = 0, e.pointDiff = 0, e.position = null")
    void resetAllEntries();
}
