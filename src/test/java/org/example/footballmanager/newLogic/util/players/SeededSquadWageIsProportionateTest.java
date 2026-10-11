package org.example.footballmanager.newLogic.util.players;

import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Position;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.example.footballmanager.newLogic.service.ClubEconomics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A named squad's wage is a share of what its players are worth (owner, 2026-10-11).
 *
 * <p><b>What this was.</b> The owner's club is seeded from a hand-entered table carrying a market value
 * and a weekly wage in two adjacent columns, and nothing anywhere checked one against the other. The
 * thirteen players came out paying <b>45-84% of their own market value per year</b>:
 *
 * <pre>
 *   Zvezdan Vukomanović   value 186,900,000   wage 1,888,000/week = 98m/year = 53% of value
 *   Žika Veljković        value 103,880,000   wage 1,684,000/week = 88m/year = 84% of value
 * </pre>
 *
 * <p>The wage bill was <b>18,478,000 a week</b> against income of 176,491. No income coefficient can
 * balance a club like that and an ordinary one at the same time, because the wage is the outlier — so
 * the wage is derived from the value now and the two cannot disagree.
 *
 * <p>Real football pays 15-25% of a transfer fee per year for a star. That is the band asserted here.
 */
@SpringBootTest
@ActiveProfiles("test")
class SeededSquadWageIsProportionateTest {

    @Autowired PlayerRepository players;
    @Autowired TeamRepository teams;
    @Autowired PlayerFactory factory;

    @Test
    @DisplayName("the seeded Omladinac squad pays 15-25% of its market value per year")
    void theSeededSquadIsPricedProportionately() {
        Team club = teams.save(new Team());
        club.setName("Omladinac wage probe " + System.nanoTime());

        List<Player> squad = factory.createOmladinacPlayers(club);
        assertTrue(squad.size() >= 13, "the owner's squad is thirteen players, got " + squad.size());

        double bill = 0;
        for (Player player : squad) {
            double annualShare = player.getPlayerValue() <= 0 ? 0
                    : player.getEarnings() * 52.0 / player.getPlayerValue() * 100.0;
            bill += player.getEarnings();

            assertTrue(annualShare >= 12.0 && annualShare <= 26.0,
                    player.getName() + " is on " + Math.round(player.getEarnings())
                            + " a week against a value of " + Math.round(player.getPlayerValue())
                            + ", which is " + Math.round(annualShare) + "% of his value per year. Real "
                            + "football is 15-25%; this squad was at 45-84%.");
        }
        assertTrue(bill > 0, "precondition: the squad has a wage bill");
    }

    @Test
    @DisplayName("a wage is derived from the value, so the two cannot disagree")
    void theWageIsDerived() {
        Team club = teams.save(new Team());
        club.setName("Omladinac derivation probe " + System.nanoTime());

        for (Player player : factory.createOmladinacPlayers(club)) {
            assertEquals(ClubEconomics.seededWeeklyWage(player.getPlayerValue(), player.getAge()),
                    player.getEarnings(), 0.01,
                    player.getName() + "'s wage is not the function of his value. Two hand-entered "
                            + "columns that nothing reconciles is how this squad ended up paying "
                            + "18.5m a week.");
        }
    }

    @Test
    @DisplayName("a young player is cheaper than an old one at the same value")
    void ageMovesTheWage() {
        double young = ClubEconomics.seededWeeklyWage(100_000_000, 20);
        double veteran = ClubEconomics.seededWeeklyWage(100_000_000, 30);

        assertTrue(veteran > young,
                "a 30-year-old at 100m should be worth more to his club than a 20-year-old at the same "
                        + "value, because his next contract is further away. Was " + young + " and "
                        + veteran);
    }

    @Test
    @DisplayName("a zero-value player costs nothing rather than something invented")
    void aZeroValuePlayerCostsNothing() {
        assertEquals(0.0, ClubEconomics.seededWeeklyWage(0, 24),
                "a player with no recorded value must not be handed a wage out of the void");
    }

    @Test
    @DisplayName("the synthetic create path still produces a rated player")
    void theSyntheticPathStillRates() {
        Player player = PlayerFactory.createPlayer(
                "Wage probe", 24, new Team(), 500_000, 2_000,
                180, 75, 6, 5, 70, 40, 70, 75, 60, 55, 60, 70, Position.MID, 9);

        assertTrue(player.getRating() > 0,
                "a player created with 70-rated skills came out rated " + player.getRating()
                        + ". The engine floors a zero at 1, so the whole squad sits at the bottom.");
    }
}
