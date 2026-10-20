package org.example.footballmanager.newLogic.repository;

import org.example.footballmanager.newLogic.model.ManagerTenure;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ManagerTenureRepository extends JpaRepository<ManagerTenure, Long> {

    /** A manager's clubs, newest first — the history. */
    List<ManagerTenure> findByUserIdOrderByStartedAtDesc(Long userId);

    /** The spell he is in now, if he is in one. */
    List<ManagerTenure> findByUserIdAndEndedAtIsNull(Long userId);

    /** Everyone who has managed this club, for the club's own side of the history. */
    List<ManagerTenure> findByTeamIdOrderByStartedAtDesc(Long teamId);
}
