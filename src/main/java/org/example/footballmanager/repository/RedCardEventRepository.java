package org.example.footballmanager.repository;

import org.example.footballmanager.model.event.RedCardEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository public interface RedCardEventRepository extends JpaRepository<RedCardEvent, Long> {}
