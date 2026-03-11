package org.example.footballmanager.repository;

import org.example.footballmanager.model.Competition;
import org.example.footballmanager.model.Team;
	import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TeamRepository extends JpaRepository<Team, Long> {
	    @Override
	    @EntityGraph(attributePaths = {"competition", "country"})
	    Optional<Team> findById(Long id);

    Optional<Team> findByName(String name);

    long countByCompetition(Competition league);
}
