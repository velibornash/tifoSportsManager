package org.example.footballmanager.repository;

import org.example.footballmanager.model.event.VARReviewEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VARReviewEventRepository extends JpaRepository<VARReviewEvent, Long> {}
