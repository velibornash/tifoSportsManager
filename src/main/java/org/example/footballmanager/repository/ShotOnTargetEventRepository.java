package org.example.footballmanager.repository;

import org.example.footballmanager.model.event.ShotOnTargetEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository public interface ShotOnTargetEventRepository extends JpaRepository<ShotOnTargetEvent, Long> {}
