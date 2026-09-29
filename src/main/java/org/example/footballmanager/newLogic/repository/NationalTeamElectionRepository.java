package org.example.footballmanager.newLogic.repository;

import org.example.footballmanager.newLogic.model.NationalTeamCandidate;
import org.example.footballmanager.newLogic.model.NationalTeamElection;
import org.example.footballmanager.newLogic.model.NationalTeamLevel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NationalTeamElectionRepository
        extends JpaRepository<NationalTeamElection, Long> {

    Optional<NationalTeamElection> findByCountryIdAndLevelAndSeasonYear(
            Long countryId, NationalTeamLevel level, Integer seasonYear);

    List<NationalTeamElection> findByCountryIdAndStatusIn(Long countryId, List<NationalTeamElection.Status> statuses);

    /** Every election a country has held, for the legacy-country cleanup. */
    List<NationalTeamElection> findByCountryId(Long countryId);
}
