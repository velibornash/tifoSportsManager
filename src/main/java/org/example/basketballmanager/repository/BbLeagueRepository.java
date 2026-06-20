package org.example.basketballmanager.repository;

import org.example.basketballmanager.model.BbLeague;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface BbLeagueRepository extends JpaRepository<BbLeague, Long> {

    Optional<BbLeague> findByCountryAndTier(String country, Integer tier);

    Optional<BbLeague> findByName(String name);
}