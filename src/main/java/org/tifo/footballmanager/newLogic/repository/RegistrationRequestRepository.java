package org.tifo.footballmanager.newLogic.repository;

import org.tifo.footballmanager.newLogic.model.RegistrationRequest;
import org.tifo.footballmanager.newLogic.model.RegistrationRequestStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RegistrationRequestRepository extends JpaRepository<RegistrationRequest, Long> {
    Optional<RegistrationRequest> findByUsername(String username);
    Optional<RegistrationRequest> findByEmail(String email);
    List<RegistrationRequest> findByStatus(RegistrationRequestStatus status);
    Optional<RegistrationRequest> findByUsernameIgnoreCaseAndStatus(String username, RegistrationRequestStatus status);
    Optional<RegistrationRequest> findByEmailIgnoreCaseAndStatus(String email, RegistrationRequestStatus status);
}