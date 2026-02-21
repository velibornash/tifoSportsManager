package org.example.footballmanager.repository;

import org.example.footballmanager.model.GameClock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface GameClockRepository extends JpaRepository<GameClock, Long> {
    Optional<GameClock> findById(long id);

}
