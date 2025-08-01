package org.example.footballmanager.repository;

import org.example.footballmanager.model.event.YellowCardEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

// ili po tipu događaja (ako hoćeš filtrirano po tipu)
@Repository public interface YellowCardEventRepository extends JpaRepository<YellowCardEvent, Long> {}
