package org.example.footballmanager.repository;

import org.example.footballmanager.model.RegistrationRequest;
import org.example.footballmanager.model.RegistrationRequestStatus;
import org.example.footballmanager.model.Team;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RegistrationRequestRepository extends JpaRepository<RegistrationRequest, Long> {

    @Override
    @EntityGraph(attributePaths = {"team"})
    Optional<RegistrationRequest> findById(Long id);

    @EntityGraph(attributePaths = {"team"})
    List<RegistrationRequest> findAllByStatusOrderByCreatedAtAsc(RegistrationRequestStatus status);

    boolean existsByTeamAndStatus(Team team, RegistrationRequestStatus status);

    Optional<RegistrationRequest> findByEmailIgnoreCaseAndStatus(String email, RegistrationRequestStatus status);

    Optional<RegistrationRequest> findByUsernameIgnoreCaseAndStatus(String username, RegistrationRequestStatus status);
}