package org.tifo.basketballmanager.repository;

import org.tifo.basketballmanager.model.BbInboxMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface BbInboxRepository extends JpaRepository<BbInboxMessage, Long> {
    List<BbInboxMessage> findByTeamIdOrderByCreatedAtDesc(Long teamId);
    long countByTeamIdAndReadFalse(Long teamId);

    @Modifying
    @Transactional
    @Query("UPDATE BbInboxMessage m SET m.read = true WHERE m.teamId = :teamId")
    void markAllRead(Long teamId);
}
