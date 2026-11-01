package org.tifo.americanfootballmanager.repository;

import org.tifo.americanfootballmanager.model.AfInboxMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface AfInboxRepository extends JpaRepository<AfInboxMessage, Long> {
    List<AfInboxMessage> findByTeamIdOrderByCreatedAtDesc(Long teamId);
    long countByTeamIdAndReadFalse(Long teamId);

    @Modifying
    @Transactional
    @Query("UPDATE AfInboxMessage m SET m.read = true WHERE m.teamId = :teamId")
    void markAllRead(Long teamId);
}
