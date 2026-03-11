package org.example.footballmanager.repository;

import org.example.footballmanager.model.CommunityMessage;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CommunityMessageRepository extends JpaRepository<CommunityMessage, Long> {

    @EntityGraph(attributePaths = {"authorUser", "authorUser.team", "recipientUser", "recipientUser.team", "registrationRequest", "registrationRequest.team"})
    List<CommunityMessage> findTop150ByOrderByCreatedAtDescIdDesc();
}