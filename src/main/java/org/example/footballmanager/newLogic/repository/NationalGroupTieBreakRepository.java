package org.example.footballmanager.newLogic.repository;

import org.example.footballmanager.newLogic.model.NationalGroupTieBreak;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface NationalGroupTieBreakRepository extends JpaRepository<NationalGroupTieBreak, Long> {

    Optional<NationalGroupTieBreak> findByCompetitionIdAndSeasonYearAndGroupCode(
            Long competitionId, Integer seasonYear, String groupCode);
}