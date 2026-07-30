package org.example.footballmanager.newLogic.repository;

import org.example.footballmanager.newLogic.model.CommunityMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CommunityMessageRepository extends JpaRepository<CommunityMessage, Long> {
    List<CommunityMessage> findByAuthorUserId(Long authorUserId);
    List<CommunityMessage> findByRecipientUserId(Long recipientUserId);
    List<CommunityMessage> findTop150ByOrderByCreatedAtDescIdDesc();
}