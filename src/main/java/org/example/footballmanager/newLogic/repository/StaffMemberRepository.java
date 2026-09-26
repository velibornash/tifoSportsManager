package org.example.footballmanager.newLogic.repository;

import org.example.footballmanager.newLogic.model.StaffMember;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StaffMemberRepository extends JpaRepository<StaffMember, Long> {
    List<StaffMember> findByTeamId(Long teamId);
    Optional<StaffMember> findByTeamIdAndRole(Long teamId, org.example.footballmanager.newLogic.model.StaffRole role);
    long countByTeamId(Long teamId);
}
