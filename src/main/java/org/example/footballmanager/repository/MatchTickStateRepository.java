package org.example.footballmanager.repository;

import org.example.footballmanager.model.Match;
import org.example.footballmanager.model.MatchTickState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MatchTickStateRepository extends JpaRepository<MatchTickState, Long> {

    /**
     * Vraća sve tick stanja za dati meč, sortirano po tick-u (za replay)
     */
    List<MatchTickState> findByMatchOrderByTickAsc(Match match);

    /**
     * Briše sva stanja za meč (npr. posle brisanja meča ili za čišćenje)
     */
    void deleteByMatch(Match match);
}