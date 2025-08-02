package org.example.footballmanager.repository;

import org.example.footballmanager.model.event.MatchEndedEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MatchEndedEventRepository extends JpaRepository<MatchEndedEvent, Long> {}
