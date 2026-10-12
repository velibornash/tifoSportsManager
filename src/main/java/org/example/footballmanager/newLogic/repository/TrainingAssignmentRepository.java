package org.example.footballmanager.newLogic.repository;

import org.example.footballmanager.newLogic.model.Position;
import org.example.footballmanager.newLogic.model.TrainingAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Which slot a player is training in.
 *
 * <p><b>The model existed with no entity, no repository and no caller</b> — {@code TrainingAssignment} was
 * drawn for T-MATCH-8 and nothing was ever built on it, while the {@code training} table sat in the
 * database holding nothing. This is the first way in.
 *
 * <p>{@code findByTeamId} is derived from the column name and will not match: the row holds a player, not a
 * team, so the training run asks for a club by its players instead. That is
 * {@code TrainingPercentService}'s problem and it is written there, where the squad is already in hand.
 */
@Repository
public interface TrainingAssignmentRepository extends JpaRepository<TrainingAssignment, Long> {

    Optional<TrainingAssignment> findByPlayerId(Long playerId);

    /** Every player's training position at one club, in one query rather than one per player. */
    @Query("select a from TrainingAssignment a where a.playerId in :playerIds")
    List<TrainingAssignment> findByPlayerIds(List<Long> playerIds);
}
