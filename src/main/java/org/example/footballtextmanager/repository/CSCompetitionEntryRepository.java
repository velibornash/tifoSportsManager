package org.example.footballtextmanager.repository;

import org.example.footballtextmanager.model.*;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CSCompetitionEntryRepository extends JpaRepository<CSCompetitionEntry, Long> {

    // Broj timova u jednoj sezoni lige
    long countByCsSeasonCompetition(CSSeasonCompetition sc);

    // Svi timovi u jednoj ligi (preko trenutne sezone)
    List<CSCompetitionEntry> findByCsSeasonCompetitionCsCompetition(CSCompetition league);

    // Pronađi entry za određeni tim u određenoj sezoni lige
    Optional<CSCompetitionEntry> findByCsSeasonCompetitionAndCTeam(CSSeasonCompetition sc, CTeam CTeam);

    @EntityGraph(attributePaths = {
            "csSeasonCompetition",
            "csSeasonCompetition.csCompetition",
            "csSeasonCompetition.csCompetition.csCountry"
    })
    Optional<CSCompetitionEntry> findFirstByCTeamAndCsSeasonCompetitionSeasonYearOrderByIdDesc(CTeam CTeam, Integer seasonYear);

    List<CSCompetitionEntry> findByCTeam(CTeam CTeam);

    // Svi timovi u određenoj sezoni (nebitno koja liga)
    List<CSCompetitionEntry> findByCsSeasonCompetition(CSSeasonCompetition sc);
}