package org.example.footballmanager.newLogic.repository;

import org.example.footballmanager.newLogic.model.GameClock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface GameClockRepository extends JpaRepository<GameClock, Long> {

    /**
     * The single clock row.
     *
     * <p>Read directly by {@code ZoneLoadService} rather than through the clock service, on purpose: the
     * clock service depends on the job runner, the job runner depends on the recovery job, and the
     * recovery job depends on {@code ZoneLoadService}. Going through the service would close that loop
     * and fail the boot for the whole world. The repository is a leaf, so this is safe.
     */
    Optional<GameClock> findTopByOrderByIdDesc();
}