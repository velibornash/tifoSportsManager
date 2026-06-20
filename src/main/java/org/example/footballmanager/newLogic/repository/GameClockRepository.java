package org.example.footballmanager.newLogic.repository;

import org.example.footballmanager.newLogic.model.GameClock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface GameClockRepository extends JpaRepository<GameClock, Long> {
}