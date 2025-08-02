package org.example.footballmanager.repository;

import org.example.footballmanager.model.event.OffsideEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OffsideEventRepository extends JpaRepository<OffsideEvent, Long> {}