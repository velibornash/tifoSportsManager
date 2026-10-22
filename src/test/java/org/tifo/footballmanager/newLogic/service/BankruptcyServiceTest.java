package org.tifo.footballmanager.newLogic.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.tifo.footballmanager.BaseTest;
import org.tifo.footballmanager.newLogic.model.Player;
import org.tifo.footballmanager.newLogic.model.SkillName;
import org.tifo.footballmanager.newLogic.model.Skills;
import org.tifo.footballmanager.newLogic.model.Team;
import org.tifo.footballmanager.newLogic.model.Transfer;
import org.tifo.footballmanager.newLogic.model.TransferStatus;
import org.tifo.footballmanager.newLogic.repository.PlayerRepository;
import org.tifo.footballmanager.newLogic.repository.TeamRepository;
import org.tifo.footballmanager.newLogic.repository.TransferRepository;
import org.tifo.commonmanager.repository.UserRepository;
import org.tifo.commonmanager.model.User;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A club that cannot pay its way sells its players and, in the end, goes bankrupt.
 *
 * <p><b>Owner, 2026-10-22:</b> *"kad je tim 2 uzastopne nedelje budget u minusu - najbolji igrac
 * ide automatski na TL. Ako ta prodaja ne izvadi iz minusa ide sledeci dok ne izadje iz minusa.
 * ako ne izadje iz minusa - bankrot, igrac ostaje bez tima i tim postaje AI tim (resetuju mu se
 * finansije). Igrac samo moze cekati ako mu se javi neki drugi tim da preuzme sto se moze a i ne
 * mora desiti."*
 */
class BankruptcyServiceTest extends BaseTest {

    @Autowired BankruptcyService bankruptcy;
    @Autowired TeamRepository teams;
    @Autowired PlayerRepository players;
    @Autowired TransferRepository transfers;
    @Autowired UserRepository users;
    @Autowired TransferService transferService;

    @Test
    @Transactional
    @DisplayName("two weeks in the red lists the best player to survive")
    void twoWeeksInTheRedListsTheBestPlayer() {
        Team club = aClub(-100_000.0);
        club.setConsecutiveNegativeWeeks(2);
        teams.save(club);
        Player star = aPlayer(club, "Star", 90.0);
        Player squad = aPlayer(club, "Squad", 30.0);

        bankruptcy.check(club, club.getBudget());

        Transfer listing = transfers.findByPlayerId(star.getId()).orElse(null);
        assertNotNull(listing, "the best player goes on the transfer list to save the club");
        assertEquals(TransferStatus.LISTED, listing.getStatus(),
                "and he is listed, not sold - the market has to buy him");
        assertNull(transfers.findByPlayerId(squad.getId()).orElse(null),
                "the lesser player is left alone while the star is the one who goes");
    }

    @Test
    @Transactional
    @DisplayName("a club with money left does nothing")
    void aSolventClubDoesNothing() {
        Team club = aClub(500_000.0);
        club.setConsecutiveNegativeWeeks(2);
        teams.save(club);
        Player star = aPlayer(club, "Star", 90.0);

        bankruptcy.check(club, club.getBudget());

        assertNull(transfers.findByPlayerId(star.getId()).orElse(null),
                "a club that is not in the red does not sell its players");
    }

    @Test
    @Transactional
    @DisplayName("a club one week in the red does nothing")
    void oneWeekInTheRedDoesNothing() {
        Team club = aClub(-100_000.0);
        club.setConsecutiveNegativeWeeks(1);
        teams.save(club);
        Player star = aPlayer(club, "Star", 90.0);

        bankruptcy.check(club, club.getBudget());

        assertNull(transfers.findByPlayerId(star.getId()).orElse(null),
                "one bad week is not enough - the club gets a second chance");
    }

    @Test
    @Transactional
    @DisplayName("a club with no players left to sell goes bankrupt")
    void aClubWithNothingLeftGoesBankrupt() {
        Team club = aClub(-100_000.0);
        club.setConsecutiveNegativeWeeks(2);
        club.setHumanControlled(true);
        teams.save(club);

        // The only player is already on the market, so there is nobody left to sell.
        Player only = aPlayer(club, "Only", 50.0);
        transferService.forceListPlayer(only.getId(), only.getPlayerValue());

        User owner = new User();
        owner.setUsername("bankrupt-" + UUID.randomUUID());
        owner.setPassword("x");
        owner.setFootballTeam(club);
        users.save(owner);

        bankruptcy.check(club, club.getBudget());

        Team after = teams.findById(club.getId()).orElseThrow();
        assertFalse(after.isHumanControlled(),
                "the club becomes AI - the manager is gone");
        assertEquals(0.0, after.getBudget(), 0.01,
                "and the finances reset");
        assertEquals(0, after.getConsecutiveNegativeWeeks(),
                "and the counter resets");
        User afterUser = users.findById(owner.getId()).orElseThrow();
        assertNull(afterUser.getFootballTeam(),
                "the manager loses the club and is free to be picked up - or not");
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private Team aClub(double budget) {
        Team team = new Team();
        team.setName("Bankrupt Club " + UUID.randomUUID().toString().substring(0, 6));
        team.setFormation("4-4-2");
        team.setBudget(budget);
        team.setHumanControlled(true);
        return teams.save(team);
    }

    private Player aPlayer(Team club, String name, double value) {
        Player player = new Player();
        player.setName(name + " " + UUID.randomUUID().toString().substring(0, 4));
        player.setAge(24);
        player.setTeam(club);
        player.setPlayerValue(value);
        Skills skills = new Skills();
        for (SkillName skill : SkillName.values()) {
            skills.setSkill(skill, 12);
        }
        skills.initializeExactFromVisibleIfNeeded();
        player.setSkills(skills);
        return players.save(player);
    }
}
