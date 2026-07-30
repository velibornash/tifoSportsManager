package org.example.footballmanager.newLogic.repository;

import org.example.footballmanager.newLogic.model.Match;
import org.example.footballmanager.newLogic.model.MatchTickState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MatchTickStateRepository extends JpaRepository<MatchTickState, Long> {

    /**
     * Returns all tick states for the given match ordered by tick for replay.
     */
    List<MatchTickState> findByMatchOrderByTickAsc(Match match);

    /**
     * Deletes all stored states for a match.
     */
    void deleteByMatch(Match match);
}
