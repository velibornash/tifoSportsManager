package org.example.footballmanager.newLogic.repository;

import org.example.footballmanager.newLogic.model.NationalTeamVote;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NationalTeamVoteRepository extends JpaRepository<NationalTeamVote, Long> {

    List<NationalTeamVote> findByElectionId(Long electionId);

    Optional<NationalTeamVote> findByElectionIdAndVoterId(Long electionId, Long voterId);

    long countByElectionId(Long electionId);
}
