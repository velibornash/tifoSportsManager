package org.example.footballmanager.newLogic.repository;

import org.example.footballmanager.newLogic.model.RankingTieBreakSeed;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RankingTieBreakSeedRepository extends JpaRepository<RankingTieBreakSeed, Long> {

    Optional<RankingTieBreakSeed> findByScopeAndSeasonYearAndSubjectKey(
            RankingTieBreakSeed.Scope scope, Integer seasonYear, String subjectKey);
}