package org.example.footballmanager.newLogic.repository;

import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.CompetitionEntry;
import org.example.footballmanager.newLogic.model.SeasonCompetition;
import org.example.footballmanager.newLogic.model.Team;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

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

    @EntityGraph(attributePaths = {
            "seasonCompetition",
            "seasonCompetition.competition",
            "seasonCompetition.competition.country"
    })
    Optional<CompetitionEntry> findFirstByTeamAndSeasonCompetitionSeasonYearOrderByIdDesc(Team team, Integer seasonYear);

    List<CompetitionEntry> findByTeam(Team team);

    // Svi timovi u određenoj sezoni (nebitno koja liga)
    List<CompetitionEntry> findBySeasonCompetition(SeasonCompetition sc);
}