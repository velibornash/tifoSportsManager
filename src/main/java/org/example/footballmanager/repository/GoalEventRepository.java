package org.example.footballmanager.repository;

import org.example.footballmanager.model.event.GoalEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GoalEventRepository extends JpaRepository<GoalEvent, Long> {
}