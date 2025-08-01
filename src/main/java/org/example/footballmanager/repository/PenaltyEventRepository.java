package org.example.footballmanager.repository;

import org.example.footballmanager.model.event.PenaltyEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository public interface PenaltyEventRepository extends JpaRepository<PenaltyEvent, Long> {}
