package org.example.americanfootballmanager.repository;

import org.example.americanfootballmanager.model.AfCompetitionEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AfCompetitionEntryRepository extends JpaRepository<AfCompetitionEntry, Long> {
    List<AfCompetitionEntry> findBySeasonCompetitionId(Long seasonCompetitionId);
    Optional<AfCompetitionEntry> findBySeasonCompetitionIdAndTeamId(Long seasonCompetitionId, Long teamId);
    List<AfCompetitionEntry> findByTeamId(Long teamId);

    @Modifying
    @Query("UPDATE AfCompetitionEntry e SET e.points = 0, e.wins = 0, e.losses = 0, e.pointsScored = 0, e.pointsConceded = 0, e.pointDiff = 0, e.position = null")
    void resetAllEntries();
}
