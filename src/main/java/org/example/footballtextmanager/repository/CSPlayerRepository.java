package org.example.footballtextmanager.repository;

import org.example.footballtextmanager.model.CPlayer;
import org.example.footballtextmanager.model.CTeam;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.PagingAndSortingRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CSPlayerRepository extends JpaRepository<CPlayer, Long>, PagingAndSortingRepository<CPlayer, Long> {
    List<CPlayer> findByCTeamId(Long teamId);
    Optional<CPlayer> findByNameAndCTeam(String name, CTeam CTeam);
    int countByCTeam(CTeam CTeam);
    List<CPlayer> findByCTeam(CTeam homeCTeam);
    Collection<CPlayer> findByCTeamIdIn(List<Long> teamIds);
    List<CPlayer> findByInjuryDaysRemainingGreaterThan(int days);

    @Modifying
    @Query("update CPlayer p set p.age = p.age + 1")
    int incrementAgeForAllPlayers();
}
