package org.example.footballmanager.newLogic.repository;

import org.example.footballmanager.newLogic.model.NationalTeamCandidate;
import org.example.footballmanager.newLogic.model.NationalTeamElection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NationalTeamCandidateRepository
        extends JpaRepository<NationalTeamCandidate, Long> {

    List<NationalTeamCandidate> findByElectionIdOrderByRegisteredAtAsc(Long electionId);

    List<NationalTeamCandidate> findByElectionIdAndWithdrawnFalseOrderByRegisteredAtAsc(Long electionId);

    Optional<NationalTeamCandidate> findByElectionIdAndUserId(Long electionId, Long userId);

    long countByElectionIdAndWithdrawnFalse(Long electionId);
}
