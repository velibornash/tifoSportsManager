package org.example.footballmanager.repository;

import org.example.footballmanager.model.Match;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MatchRepository extends JpaRepository<Match, Long> {
}