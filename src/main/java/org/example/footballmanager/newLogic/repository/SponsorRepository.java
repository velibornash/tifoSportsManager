package org.example.footballmanager.newLogic.repository;

import org.example.footballmanager.newLogic.model.Sponsor;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SponsorRepository extends JpaRepository<Sponsor, Long> {
    List<Sponsor> findByTeamId(Long teamId);
    long countByTeamId(Long teamId);
}
