package org.example.footballmanager.repository;

import org.example.footballmanager.model.League;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LeagueRepository extends JpaRepository<League, Long> {
}