package org.example.footballmanager.repository;

import org.example.footballmanager.model.Season;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SeasonRepository extends JpaRepository<Season, Long> {
}