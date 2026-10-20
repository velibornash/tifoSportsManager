package org.example.footballmanager.newLogic.repository;

import org.example.footballmanager.newLogic.model.FantasyPick;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FantasyPickRepository extends JpaRepository<FantasyPick, Long> {

    List<FantasyPick> findByFantasyTeamId(Long fantasyTeamId);

    /**
     * Clears a squad before it is written again.
     *
     * <p>A save replaces the fifteen rather than diffing them. A squad is small, and working out which
     * picks changed and which stayed is the sort of code that is wrong once and then wrong quietly — the
     * purchase price of an unchanged player is the only thing a diff would protect, and the caller has
     * already decided to keep it.
     */
    void deleteByFantasyTeamId(Long fantasyTeamId);
}
