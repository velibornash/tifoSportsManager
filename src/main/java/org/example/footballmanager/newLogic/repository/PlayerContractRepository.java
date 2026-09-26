package org.example.footballmanager.newLogic.repository;

import org.example.footballmanager.newLogic.model.PlayerContract;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface PlayerContractRepository extends JpaRepository<PlayerContract, Long> {

    Optional<PlayerContract> findByPlayerId(Long playerId);

    List<PlayerContract> findByTeamId(Long teamId);

    List<PlayerContract> findByTeamIdAndExpirySeasonLessThanEqual(Long teamId, Integer season);

    /** Free agents: a contract record with no club behind it. */
    List<PlayerContract> findByTeamIsNull();

    long countByTeamId(Long teamId);

    /**
     * Players who have a club but no contract, in one query.
     *
     * <p>Exists so the backfill is not an N+1 loop over every player in the database. That version
     * took almost six minutes on the test database, which is a few thousand players; on a real
     * dataset it would be far worse, and it ran on every week advance.
     */
    @Query("select p from Player p where p.team is not null and p.id not in "
            + "(select c.player.id from PlayerContract c where c.player is not null)")
    List<org.example.footballmanager.newLogic.model.Player> findPlayersWithoutContract();
}
