package org.example.footballmanager.repository;

import org.example.footballmanager.model.Season;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SeasonRepository extends JpaRepository<Season, Long> {
    Optional<Season> findBySeasonYear(int year);
}