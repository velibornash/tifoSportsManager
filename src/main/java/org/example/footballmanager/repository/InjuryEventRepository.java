package org.example.footballmanager.repository;

import org.example.footballmanager.model.event.InjuryEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository public interface InjuryEventRepository extends JpaRepository<InjuryEvent, Long> {}
