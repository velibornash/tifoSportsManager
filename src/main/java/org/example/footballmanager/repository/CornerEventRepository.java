package org.example.footballmanager.repository;

import org.example.footballmanager.model.event.CornerEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CornerEventRepository extends JpaRepository<CornerEvent, Long> {}
