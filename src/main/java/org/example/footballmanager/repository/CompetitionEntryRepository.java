package org.example.footballmanager.repository;

import org.example.footballmanager.model.Competition;
import org.example.footballmanager.model.CompetitionEntry;
import org.example.footballmanager.model.SeasonCompetition;
import org.example.footballmanager.model.Team;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface CompetitionEntryRepository extends JpaRepository<CompetitionEntry, Long> {

    // Broj timova u jednoj sezoni lige
    long countBySeasonCompetition(SeasonCompetition sc);

    // Svi timovi u jednoj ligi (preko trenutne sezone)
    List<CompetitionEntry> findBySeasonCompetitionCompetition(Competition league);

    // Pronađi entry za određeni tim u određenoj sezoni lige
    Optional<CompetitionEntry> findBySeasonCompetitionAndTeam(SeasonCompetition sc, Team team);

    List<CompetitionEntry> findByTeam(Team team);

    // Svi timovi u određenoj sezoni (nebitno koja liga)
    List<CompetitionEntry> findBySeasonCompetition(SeasonCompetition sc);
}