package org.tifo.footballmanager.newLogic.service;

import org.tifo.footballmanager.UniqueIsoCodes;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import org.tifo.footballmanager.newLogic.model.Competition;
import org.tifo.footballmanager.newLogic.model.CompetitionType;
import org.tifo.footballmanager.newLogic.model.Country;
import org.tifo.footballmanager.newLogic.model.CountryState;
import org.tifo.footballmanager.newLogic.model.Player;
import org.tifo.footballmanager.newLogic.model.Team;
import org.tifo.footballmanager.newLogic.repository.CompetitionRepository;
import org.tifo.footballmanager.newLogic.repository.CountryRepository;
import org.tifo.footballmanager.newLogic.repository.PlayerRepository;
import org.tifo.footballmanager.newLogic.repository.TeamRepository;
import org.tifo.footballmanager.newLogic.util.BotSquadGenerator;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A country that is played has to be able to field a national side.
 *
 * <p><b>What was wrong, measured on the owner's own world.</b> A national side is drawn from the
 * players of its own country's clubs — that is the whole idea of it. But the squads were seeded
 * <b>before</b> any club existed, so the country that is played had nothing to draw from and its
 * side came out <b>empty</b>: Serbia's senior side held <b>0 players</b> and its U-21 side <b>0</b>.
 * The forty-seven countries that are only represented were fine, because they are given a squad of
 * <b>generated</b> players — they have no clubs to draw from either.
 *
 * <p>The world's own code says the side must not be a stand-in: <em>"AKTIVNA liga MORA imati STVARNE
 * igrace a ne simulirane"</em> — an ACTIVE league must have real players.
 *
 * <p><b>Why the test builds a small country rather than a whole one.</b> The defect is an
 * <em>ordering</em> — the squads were seeded before the clubs — and the ordering can be shown with
 * thirty clubs and thirty players. A full pyramid is 310 clubs and seven thousand players, and the
 * guard would cost the suite a minute to prove the same thing.
 */
@SpringBootTest
@Transactional
class ActivatedCountryFieldsRealPlayersTest {

    @Autowired CountryActivationService activation;
    @Autowired CountryRepository countries;
    @Autowired CompetitionRepository competitions;
    @Autowired TeamRepository teams;
    @Autowired PlayerRepository players;
    @Autowired BotSquadGenerator bots;

    @Test
    @DisplayName("a country that is played fields a side of real players")
    void anActivatedCountryFieldsRealPlayers() {
        Country country = myCountryWithClubsAndPlayers();

        // The door the admin panel's "Activate" button uses.
        activation.activate(country.getIsoCode());

        Country activated = countries.findByIsoCode(country.getIsoCode()).orElseThrow();
        assertTrue(activated.getState() == CountryState.ACTIVE, "the country is played now");

        assertNotNull(activated.getSeniorNationalTeam(),
                "a played country has a senior side. It did not: activating a country left it with no "
                        + "national team at all, because the side was only ever seeded on the path "
                        + "that seeds a whole world.");

        List<Player> senior = players.findByTeamId(activated.getSeniorNationalTeam().getId());
        assertEquals(25, senior.size(),
                "a played country fields a side of twenty-five — it had " + senior.size()
                        + ". The side is drawn from the clubs, so it can only be drawn once the "
                        + "clubs exist, and it was drawn before they did.");

        List<Player> standIns = senior.stream().filter(bots::isGenerated).toList();
        assertTrue(standIns.isEmpty(),
                "and the twenty-five are real players, not the generated stand-ins a country with no "
                        + "clubs is given: " + standIns.stream().map(Player::getName).limit(4).toList());
    }

    /**
     * A country of this test's own: one division, thirty clubs, a player on each.
     *
     * <p>The division is what makes {@code PyramidBuilder.build()} return early — it asks the
     * database whether the country already has leagues, and one league is enough — so the test
     * exercises the <em>activation</em> and not the building of a pyramid.
     */
    private Country myCountryWithClubsAndPlayers() {
        long stamp = System.nanoTime();
        Country country = new Country();
        country.setIsoCode(UniqueIsoCodes.next());
        country.setName("Activation Probe " + stamp);
        country.setState(CountryState.SIMULATED);
        country = countries.save(country);

        Competition league = new Competition();
        league.setName("Probe League " + stamp);
        league.setType(CompetitionType.LEAGUE);
        league.setTier(1);
        league.setCountry(country);
        league = competitions.save(league);

        for (int i = 0; i < 30; i++) {
            Team club = new Team();
            club.setName("Probe Club " + stamp + " " + i);
            club.setCountry(country);
            club.setCompetition(league);
            club.setHumanControlled(false);
            club = teams.save(club);

            Player player = new Player();
            player.setName("Probe Player " + stamp + " " + i);
            player.setTeam(club);
            player.setAge(20);
            player.setRating(80);
            players.save(player);
        }
        return country;
    }
}
