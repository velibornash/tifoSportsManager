package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Position;
import org.example.footballmanager.newLogic.model.Stadium;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Why a club would want a player (Sprint 3.6).
 *
 * <p>The buyer used to be picked uniformly at random and the price was a dice roll around market
 * value. These tests are about the two things that makes a market feel like a market: a club bids
 * for the gap it actually has, and what it offers is a view rather than a coin toss.
 */
@SpringBootTest
@ActiveProfiles("test")
class ClubNeedServiceTest {

    @Autowired ClubNeedService needs;
    @Autowired PlayerRepository players;
    @Autowired TeamRepository teams;

    private Team club(String name, double reputation) {
        Team t = new Team();
        t.setName(name + "-" + System.nanoTime());
        t.setBudget(8_000_000.0);
        t.setReputation(reputation);
        Stadium s = new Stadium();
        s.setName(name + " Ground");
        s.setCapacity(20_000);
        s.setTicketPrice(18.0);
        s.setPitchQuality(85.0);
        s.setPitchCondition(85);
        s.setMaintenanceRemaining(0);
        t.setStadium(s);
        return teams.save(t);
    }

    private Player aPlayer(Team at, Position position, int age, double value, double form) {
        Player p = new Player();
        p.setName("P" + System.nanoTime() + "-" + position);
        p.setTeam(at);
        p.setPosition(position);
        p.setAge(age);
        p.setPlayerValue(value);
        p.setEarnings(4_000);
        p.setForm(form);
        p.setMorale(60.0);
        return players.save(p);
    }

    /** A player at another club, which is the only kind any of these tests may bid on. */
    private Player rivalPlayer(Position position, int age, double value, double form) {
        Team rival = club("Rival", 55);
        return aPlayer(rival, position, age, value, form);
    }

    @Test
    @DisplayName("a club with a gap in a position wants a player for that position")
    void aGapCreatesInterest() {
        Team club = club("Thin", 60);
        aPlayer(club, Position.GK, 30, 3_000_000, 6);
        aPlayer(club, Position.MID, 28, 5_000_000, 6);
        aPlayer(club, Position.WNG, 27, 5_000_000, 6);

        // No defender at all in the squad, so there is a genuine gap in the back line.
        Player wantedStriker = rivalPlayer(Position.DEF, 23, 6_000_000, 7);

        assertTrue(needs.interest(club, wantedStriker) > 0,
                "a club with one centre back has a real need for another");
    }

    @Test
    @DisplayName("a club with nobody to replace does not bid")
    void noGapNoBid() {
        Team club = club("Covered", 60);
        // Covered at the position the target plays, with nobody there worth replacing.
        for (int i = 0; i < 3; i++) {
            aPlayer(club, Position.ATT, 26 + i, 2_000_000, 6);
            aPlayer(club, Position.DEF, 27 + i, 2_000_000, 6);
        }
        // And not even an upgrade on what is already there.
        Player nobodyWants = rivalPlayer(Position.DEF, 24, 500_000, 6);

        assertEquals(0, needs.interest(club, nobodyWants),
                "covered, and nobody there is worse than him, so there is no reason to bid - and "
                        + "that is what stops the market being noise");
    }

    @Test
    @DisplayName("a club is more interested in an upgrade than in a replacement")
    void upgradesBeatReplacements() {
        Team old = club("Old", 60);
        aPlayer(old, Position.GK, 30, 3_000_000, 6);
        for (int i = 0; i < 3; i++) aPlayer(old, Position.MID, 31, 3_000_000, 5);

        Team young = club("Young", 60);
        aPlayer(young, Position.GK, 30, 3_000_000, 6);
        for (int i = 0; i < 3; i++) aPlayer(young, Position.MID, 22, 3_000_000, 5);

        Player target = rivalPlayer(Position.MID, 23, 8_000_000, 7);

        assertTrue(needs.interest(old, target) > 0);
        assertTrue(needs.interest(old, target) > needs.interest(young, target),
                "a club with three 31-year-old midfielders wants that 23-year-old more than a club "
                        + "with three 22-year-olds does - the gap is the ages, not the headcount");
    }

    @Test
    @DisplayName("a club never bids for its own player")
    void noSelfInterest() {
        Team club = club("Self", 60);
        aPlayer(club, Position.GK, 30, 3_000_000, 6);
        Player mine = aPlayer(club, Position.ATT, 24, 5_000_000, 7);
        assertEquals(0, needs.interest(club, mine));
    }

    @Test
    @DisplayName("age moves the price: a view, not a market value")
    void ageMovesTheNumber() {
        Team club = club("Valuer", 70);
        aPlayer(club, Position.GK, 30, 3_000_000, 6);
        aPlayer(club, Position.DEF, 29, 4_000_000, 6);
        aPlayer(club, Position.MID, 28, 5_000_000, 6);

        Player peak = rivalPlayer(Position.DEF, 25, 10_000_000, 6);
        Player veteran = rivalPlayer(Position.DEF, 34, 10_000_000, 6);

        double forPeak = needs.valuation(club, peak);
        double forVeteran = needs.valuation(club, veteran);

        assertTrue(forPeak > forVeteran,
                "two players with identical market value are not worth the same: "
                        + forPeak + " vs " + forVeteran);
    }

    @Test
    @DisplayName("form moves the price too")
    void formMovesTheNumber() {
        Team club = club("Scout", 70);
        aPlayer(club, Position.GK, 30, 3_000_000, 6);
        aPlayer(club, Position.DEF, 29, 4_000_000, 6);
        aPlayer(club, Position.MID, 28, 5_000_000, 6);

        Player onFire = rivalPlayer(Position.DEF, 25, 8_000_000, 9.0);
        Player outOfForm = rivalPlayer(Position.DEF, 25, 8_000_000, 3.0);

        assertTrue(needs.valuation(club, onFire) > needs.valuation(club, outOfForm),
                "a player is worth what he is doing now, not what he did last season");
    }

    @Test
    @DisplayName("a club with no interest puts no value on a player at all")
    void noInterestNoValuation() {
        Team club = club("Full", 60);
        // Covered everywhere, including the back line, so a rival defender is of no use.
        for (int i = 0; i < 3; i++) {
            aPlayer(club, Position.ATT, 26, 9_000_000, 7);
            aPlayer(club, Position.DEF, 27, 9_000_000, 7);
        }
        aPlayer(club, Position.GK, 30, 3_000_000, 6);
        Player unwanted = rivalPlayer(Position.DEF, 24, 9_000_000, 7);

        assertEquals(0, needs.valuation(club, unwanted),
                "a club must not bid for a player it does not want, however much money it has");
    }

    @Test
    @DisplayName("a club picks the player it wants most, not the most valuable one")
    void theBestTargetIsTheOneItWants() {
        Team club = club("Chooser", 60);
        aPlayer(club, Position.GK, 30, 3_000_000, 6);
        for (int i = 0; i < 3; i++) aPlayer(club, Position.MID, 28, 5_000_000, 6);

        // Twenty million, and the club is covered at that position; two million, and it is not.
        Player expensive = rivalPlayer(Position.MID, 26, 20_000_000, 7);
        Player needed = rivalPlayer(Position.DEF, 22, 2_000_000, 7);

        assertEquals(needed.getId(), needs.bestTarget(club, List.of(expensive, needed)).getId(),
                "the gap decides, not the price tag");
    }

    @Test
    @DisplayName("a club can be told where it is thin")
    void gapsAreReportable() {
        Team club = club("Report", 60);
        aPlayer(club, Position.GK, 30, 3_000_000, 6);
        for (int i = 0; i < 3; i++) aPlayer(club, Position.MID, 28, 5_000_000, 6);

        // Gaps are reported by role now, because "no left back" is actionable and "no defenders" is
        // not: a club with three centre backs and no full backs is not short of defenders.
        List<String> thin = new ArrayList<>(needs.gapsByRole(club));
        assertTrue(thin.contains("STRIKER"), "no striker at all is a gap");
        assertTrue(thin.contains("LEFT_BACK"), "and no left back");
        assertFalse(thin.contains("MID") || thin.contains("DEF") || thin.contains("ATT"),
                "positions are not roles, and this report must not mix them");

        // And the position view is still available, for squad-balance questions.
        List<String> byPosition = new ArrayList<>(needs.gapsByPosition(club));
        assertFalse(byPosition.contains("MID"), "three midfielders is not a positional gap");
        assertTrue(byPosition.contains("ATT"), "but no striker is");
    }
}
