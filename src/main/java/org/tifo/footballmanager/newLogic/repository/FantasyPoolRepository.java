package org.tifo.footballmanager.newLogic.repository;

import org.tifo.footballmanager.newLogic.fantasy.FantasyPoolRow;
import org.tifo.footballmanager.newLogic.model.Player;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * The one query the fantasy game needs: every player in a country, with his tier and his club.
 *
 * <p>A dedicated interface rather than a method on {@code PlayerRepository}, because it exists for exactly
 * one caller and a shared repository is where a query nobody else uses goes to be forgotten.
 *
 * <p>Read-only on purpose. Fantasy derives a price from these rows and never writes a player.
 */
public interface FantasyPoolRepository extends Repository<Player, Long> {

    /**
     * Every player in a country's pyramid, flat.
     *
     * <p>The join is the whole path — player to club to competition to country — because the tier lives on
     * the competition and the country lives on the competition too. Clubs outside a competition are left
     * out by the join rather than by a filter: a club with no league has no tier, and a player with no tier
     * cannot be placed in the three-per-tier rule.
     */
    @Query("select new org.tifo.footballmanager.newLogic.fantasy.FantasyPoolRow("
            + "p.id, p.name, p.rating, p.position, c.tier, t.id, t.name) "
            + "from Player p join p.team t join t.competition c "
            + "where c.country.id = :countryId")
    List<FantasyPoolRow> rowsForCountry(@Param("countryId") Long countryId);
}
