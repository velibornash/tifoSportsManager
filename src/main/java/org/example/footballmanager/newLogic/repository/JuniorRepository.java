package org.example.footballmanager.newLogic.repository;

import org.example.footballmanager.newLogic.model.Junior;
import org.example.footballmanager.newLogic.model.JuniorStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface JuniorRepository extends JpaRepository<Junior, Long> {
    List<Junior> findByTeamId(Long teamId);
    List<Junior> findByTeamIdAndStatus(Long teamId, JuniorStatus status);
}