package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.FinanceCategory;
import org.example.footballmanager.newLogic.model.FinanceLedgerEntry;
import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.CompetitionType;
import org.example.footballmanager.newLogic.model.GameClock;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.PlayerContract;
import org.example.footballmanager.newLogic.model.SquadRole;
import org.example.footballmanager.newLogic.model.Stadium;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.PlayerContractRepository;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.CompetitionRepository;
import org.example.footballmanager.newLogic.repository.GameClockRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 3.1 — contracts.
 *
 * <p>Nothing here existed before: a player's wage was a field on the player and nothing tracked when
 * it ended, which is why the free-agent market was impossible by construction. The property that
 * matters most is that **expiry produces a free agent**, because that is the mechanism that lets a
 * club be rebuilt rather than only bought from.
 */
@SpringBootTest
@ActiveProfiles("test")
class PlayerContractServiceTest {

    /**
     * The game is playing a season before any of its finances mean anything.
     *
     * <p>Every ledger figure is read for the clock's season, so a fixture with no clock has no season
     * to read. That is now honest rather than a calendar year, which means the ledger comes back empty
     * and a club that has genuinely earned nothing is genuinely granted nothing. Signing a player is
     * therefore impossible without one, which is correct: the game has not started.
     */
    @BeforeEach
    void theGameIsPlaying() {
        if (clocks.findAll().isEmpty()) {
            GameClock clock = new GameClock();
            clock.setCurrentSeason(1);
            clock.setCurrentWeek(1);
            clocks.save(clock);
        }
    }

    private Competition aLeague() {
        Competition competition = new Competition();
        competition.setName("ZZ Contracts league " + System.nanoTime());
        competition.setType(CompetitionType.LEAGUE);
        competition.setTier(1);
        competition.setReputationWeight(20);
        return competitions.save(competition);
    }

    /**
     * Settles a week, in the season actually being played, so the club is granted a transfer budget.
     *
     * <p>{@code TransferBudgetService} derives the board's grant from settled income less wages and a
     * reserve, capped at half the cash -- and never reads the balance itself. A club handed a large
     * {@code budget} and no settled week is refused, correctly, with "No settled income yet".
     */
    private Team withTransferBudget(Team club) {
        int season = clocks.findAll().stream()
                .map(GameClock::getCurrentSeason)
                .filter(java.util.Objects::nonNull)
                .max(Integer::compareTo).orElse(1);
        finances.applyWeeklyFinances(club, season, 1);
        return club;
    }

    @Autowired TeamRepository teams;
    @Autowired PlayerRepository players;
    @Autowired PlayerContractRepository contracts;
    @Autowired PlayerContractService service;
    @Autowired SquadRegistrationService registration;
    @Autowired ContractBackfillService backfill;
    @Autowired org.example.footballmanager.newLogic.repository.FinanceLedgerEntryRepository ledger;
    @Autowired CompetitionRepository competitions;
    @Autowired GameClockRepository clocks;
    @Autowired WeeklyFinanceService finances;

    private Team aClub(String name) {
        return aClub(name, 5_000_000.0);
    }

    private Team aClub(String name, double budget) {
        Team t = new Team();
        t.setName(name + "-" + System.nanoTime());
        // A club is a team with a competition: WeeklyFinanceService skips a team with none, so it
        // would earn nothing and be granted no transfer budget however rich it looks.
        t.setCompetition(aLeague());
        t.setBudget(budget);
        t.setReputation(60.0);
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

    /**
     * Gives a club a trading history, because the wage ceiling is income-based: a club with no
     * settled weeks has an income of zero, a ceiling of zero, and canAfford correctly refuses any
     * wage at all. Right behaviour, useless as a fixture.
     */
    private void settleIncome(Team club) {
        int season = contracts.findAll().isEmpty() ? 1 : 1;
        for (int week = 1; week <= 8; week++) {
            ledger.save(FinanceLedgerEntry.of(club, season, week,
                    FinanceCategory.BROADCAST, 120_000, "Broadcast income"));
        }
    }

    private Player aPlayer(Team team, String name, int age, double value, double wage) {
        Player p = new Player();
        p.setName(name);
        p.setTeam(team);
        p.setAge(age);
        p.setPlayerValue(value);
        p.setEarnings(wage);
        p.setMorale(60.0);
        p.setForm(6.0);
        return players.save(p);
    }

    @Test
    @DisplayName("a signed contract records who, what, and until when")
    void signingRecordsTheTerms() {
        Team club = aClub("Signers");
        Player p = aPlayer(club, "Signer", 24, 2_000_000, 8_000);

        PlayerContract c = service.assignToClub(p, club, 3, SquadRole.STARTER);
        assertNotNull(c.getId());
        assertEquals(club.getId(), c.getTeam().getId());
        assertEquals(8_000.0, c.getWeeklyWage(), 0.01);
        assertEquals(SquadRole.STARTER, c.getSquadRole());
        assertTrue(c.getExpirySeason() > 3, "a contract must expire at some point");
        assertFalse(c.hasReleaseClause(), "no clause was agreed, which is not the same as free");
    }

    @Test
    @DisplayName("an expired contract turns the player into a free agent")
    void expiryProducesAFreeAgent() {
        Team club = aClub("Expiring");
        Player p = aPlayer(club, "Expiring", 30, 1_000_000, 4_000);
        PlayerContract c = service.assignToClub(p, club, 3, SquadRole.ROTATION);
        c.setExpirySeason(3);
        contracts.save(c);

        // Season 3: still under contract, because a contract runs out at the END of its season.
        assertTrue(service.expireContracts(3).isEmpty(), "a contract expires AT THE END of its season");
        assertNotNull(contracts.findByPlayerId(p.getId()).orElseThrow().getTeam());

        // Season 4: now a free agent.
        List<Player> released = service.expireContracts(4);
        assertTrue(released.stream().anyMatch(x -> x.getId().equals(p.getId())),
                "the player must be released once the contract has run out");
        assertNull(contracts.findByPlayerId(p.getId()).orElseThrow().getTeam(),
                "a free agent has no club behind the contract");
    }

    @Test
    @DisplayName("a free agent can be signed by anyone - the market is no longer impossible")
    void freeAgentsCanBeSigned() {
        Team oldClub = aClub("Old");
        Team newClub = aClub("New");
        Player p = aPlayer(oldClub, "Freebie", 27, 1_500_000, 5_000);
        PlayerContract c = service.assignToClub(p, oldClub, 1, SquadRole.ROTATION);
        c.setExpirySeason(2);
        contracts.save(c);
        service.expireContracts(3);

        assertNull(contracts.findByPlayerId(p.getId()).orElseThrow().getTeam());
        service.assignToClub(p, newClub, 3, SquadRole.STARTER);
        assertEquals(newClub.getId(),
                contracts.findByPlayerId(p.getId()).orElseThrow().getTeam().getId(),
                "a released player must be signable by a new club");
    }

    @Test
    @DisplayName("a player demands more in the form of his life")
    void formRaisesTheDemand() {
        Team club = aClub("Form");
        Player p = aPlayer(club, "InForm", 25, 2_000_000, 6_000);
        service.assignToClub(p, club, 3, SquadRole.STARTER);
        double before = service.wageDemand(p.getId()).demandedWeeklyWage();

        p.setForm(9.5);
        players.save(p);
        double after = service.wageDemand(p.getId()).demandedWeeklyWage();

        assertTrue(after > before,
                "a player asks for what he is doing now, not what he did last season: "
                        + after + " vs " + before);
    }

    @Test
    @DisplayName("a young player's wage demand is below his value - the next contract is the payday")
    void youngPlayersAskForLess() {
        Team club = aClub("Young");
        Player young = aPlayer(club, "Young", 19, 900_000, 3_000);
        Player old = aPlayer(club, "Old", 30, 900_000, 3_000);
        service.assignToClub(young, club, 3, SquadRole.PROSPECT);
        service.assignToClub(old, club, 3, SquadRole.ROTATION);

        assertTrue(service.wageDemand(young.getId()).demandedWeeklyWage()
                        < service.wageDemand(old.getId()).demandedWeeklyWage(),
                "identical value, different age, different demand");
    }

    @Test
    @DisplayName("a star demands more per unit of value than a squad player")
    void roleChangesTheExpectation() {
        Team club = aClub("Roles");
        Player star = aPlayer(club, "Star", 27, 8_000_000, 30_000);
        Player squad = aPlayer(club, "Squad", 27, 8_000_000, 30_000);
        service.assignToClub(star, club, 3, SquadRole.STAR);
        service.assignToClub(squad, club, 3, SquadRole.ROTATION);

        assertTrue(service.wageDemand(star.getId()).demandedWeeklyWage()
                        > service.wageDemand(squad.getId()).demandedWeeklyWage(),
                "a model that pays everyone the same makes every squad cost the same");
    }

    @Test
    @DisplayName("refusing a player's demand makes him look elsewhere")
    void refusingRenewalHasConsequences() {
        Team club = aClub("Refusers");
        Player p = aPlayer(club, "Greedy", 27, 5_000_000, 10_000);
        service.assignToClub(p, club, 3, SquadRole.STAR);

        PlayerContractService.RenewalOutcome low = service.renew(p.getId(), 2027, 1);
        assertFalse(low.renewed());
        assertTrue(low.playerWillSeekTransfer(),
                "refusing is not a null result, it is a decision with a consequence");

        PlayerContractService.RenewalOutcome fair =
                service.renew(p.getId(), 2027, service.wageDemand(p.getId()).demandedWeeklyWage());
        assertTrue(fair.renewed(), "meeting the demand must renew");
    }

    /**
     * Owner, 2026-10-08: one limit, 30 players, counted in players.
     *
     * <p>Replaces two tests that asserted 25 seniors plus a separate 8 academy players — 33 in total,
     * split by an attribute inferred from age and value. The split meant a club was over its limit or
     * not depending on how the last backfill happened to classify a nineteen-year-old.
     *
     * <p>The fillers go in through {@code assignToClub}, which is what actually creates the contract,
     * so this still measures the real thing. What it no longer proves is anything about contracts:
     * the rule counts {@code Player} rows, and {@link SquadRegistrationService} is the class under
     * test for that.
     */
    @Test
    @DisplayName("a club cannot exceed 30 players")
    void squadLimitIsEnforced() {
        Team club = aClub("Full");
        assertTrue(registration.canRegister(club.getId()).allowed());

        for (int i = 0; i < SquadRegistrationService.MAX_CLUB_SQUAD; i++) {
            Player p = aPlayer(club, "Filler" + i, 24, 500_000, 2_000);
            service.assignToClub(p, club, 3, SquadRole.ROTATION);
        }
        SquadRegistrationService.RegistrationCheck blocked = registration.canRegister(club.getId());
        assertFalse(blocked.allowed(), "a 31-man squad must not be possible");
        assertEquals("SQUAD_FULL", blocked.code());
        assertNotNull(blocked.reason());
    }

    /**
     * The old rule's real defect, pinned: a player with no contract counts.
     *
     * <p>Twenty-nine players with contracts, and one with none — which is what every academy graduate
     * is until the next season's backfill. The contract-counting cap could not see that thirty-first
     * player and would have told the manager he had room.
     */
    @Test
    @DisplayName("a player with no contract still occupies a place in the squad")
    void aContractlessPlayerStillCounts() {
        Team club = aClub("Contractless");
        for (int i = 0; i < SquadRegistrationService.MAX_CLUB_SQUAD - 1; i++) {
            Player p = aPlayer(club, "Contracted " + i, 24, 500_000, 2_000);
            service.assignToClub(p, club, 3, SquadRole.ROTATION);
        }
        // A junior's worth of player: created at the club, never given a contract.
        Player graduate = aPlayer(club, "Graduate", 20, 120_000, 400);
        assertTrue(contracts.findByPlayerId(graduate.getId()).isEmpty(),
                "precondition: he really has no contract");

        assertFalse(registration.canRegister(club.getId()).allowed(),
                "the 30th player exists, contract or not, so the squad is full");
    }

    /**
     * Seniors and youth players share the thirty, which is the rule now: there is one bucket.
     */
    @Test
    @DisplayName("academy players take places out of the same thirty")
    void youthPlayersShareTheOneLimit() {
        Team club = aClub("Mixed");
        for (int i = 0; i < SquadRegistrationService.MAX_CLUB_SQUAD - 4; i++) {
            Player p = aPlayer(club, "Senior " + i, 24, 500_000, 2_000);
            service.assignToClub(p, club, 3, SquadRole.ROTATION);
        }
        for (int i = 0; i < 4; i++) {
            Player p = aPlayer(club, "Young " + i, 18, 50_000, 500);
            service.assignToClub(p, club, 3, SquadRole.YOUTH);
        }
        assertFalse(registration.canRegister(club.getId()).allowed(),
                "26 seniors plus 4 academy players is thirty, and there is no separate academy limit");
    }

    @Test
    @DisplayName("backfill gives every player a plausible contract, and is safe to run twice")
    void backfillIsPlausibleAndIdempotent() {
        Team club = aClub("Backfilled");
        Player p = aPlayer(club, "Needs a deal", 19, 300_000, 1_200);
        assertTrue(contracts.findByPlayerId(p.getId()).isEmpty());

        int first = backfill.backfill(3);
        assertTrue(first >= 1, "the player must have been given a contract");
        int second = backfill.backfill(3);
        assertEquals(0, second, "running it twice must not create a second contract");

        PlayerContract c = contracts.findByPlayerId(p.getId()).orElseThrow();
        assertNotNull(c.getTeam());
        assertTrue(c.getLengthMonths() >= 12, "a 19-year-old should be on a long deal, got "
                + c.getLengthMonths() + " months");
    }

    @Test
    @DisplayName("an older player is not given a five-year deal")
    void olderPlayersGetShorterDeals() {
        Team club = aClub("Ages");
        Player veteran = aPlayer(club, "Veteran", 34, 800_000, 4_000);
        backfill.backfill(3);
        assertTrue(contracts.findByPlayerId(veteran.getId()).orElseThrow().getLengthMonths() <= 36,
                "nobody signs a 34-year-old for four years");
    }

    // ---------------------------------------------------------------- signing really signs

    /**
     * These three are the regression tests for a signing that accomplished nothing.
     *
     * <p>{@code sign} looked like it worked: it validated the terms and returned a contract. But it
     * wrote that contract with no club on it, never moved the player, and never applied the wage —
     * so a free agent who "signed" stayed at his old club on his old money, and the free-agent route
     * was a no-op dressed as a feature. The old tests passed because they only ever read the
     * contract record and never asked where the player ended up.
     */
    @Test
    @DisplayName("signing a free agent puts him on the club's books, on the club's payroll")
    void signingMovesThePlayerToTheClub() {
        Team from = aClub("Old employer");
        Team to = withTransferBudget(aClub("New employer"));
        settleIncome(to);
        Player p = aPlayer(from, "Free agent signing", 23, 1_500_000, 3_000);
        p.setTeam(null);                      // he is a free agent: no club at all
        players.save(p);

        PlayerContractService.Outcome outcome =
                service.sign(to.getId(), p.getId(), 24, 11_000, SquadRole.STARTER);

        assertTrue(outcome.signed(), "the signing should go through: " + outcome.reason());

        Player reloaded = players.findById(p.getId()).orElseThrow();
        assertNotNull(reloaded.getTeam(), "a signed player must belong to the club he signed with");
        assertEquals(to.getId(), reloaded.getTeam().getId());
        assertEquals(11_000.0, reloaded.getEarnings(), 0.01,
                "the agreed wage is what the player is actually paid");

        PlayerContract contract = contracts.findByPlayerId(p.getId()).orElseThrow();
        assertEquals(to.getId(), contract.getTeam().getId(),
                "the contract must name the club, not be left empty");
    }

    @Test
    @DisplayName("a club that cannot meet the wage is told no, not signed anyway")
    void anUnaffordableWageIsRefused() {
        Team broke = aClub("Broke", 40_000);
        settleIncome(broke);
        Player p = aPlayer(null, "Too expensive", 24, 500_000, 1_000);
        p.setTeam(null);
        players.save(p);

        // A wage far above anything the club earns.
        PlayerContractService.Outcome outcome =
                service.sign(broke.getId(), p.getId(), 24, 900_000, SquadRole.STARTER);

        assertFalse(outcome.signed(),
                "a club that cannot pay the wage must not end up with the player");
        assertNotNull(outcome.reason());
    }

    @Test
    @DisplayName("a registered player cannot be signed out from under his club")
    void aContractedPlayerIsNotPoachable() {
        Team seller = aClub("His club");
        Team buyer = aClub("Poacher");
        settleIncome(buyer);
        Player p = aPlayer(seller, "Under contract", 25, 3_000_000, 6_000);
        PlayerContract contract = service.assignToClub(p, seller, 3, SquadRole.STARTER);
        contract.setReleaseClause(null);
        contracts.save(contract);

        PlayerContractService.Outcome outcome =
                service.sign(buyer.getId(), p.getId(), 24, 12_000, SquadRole.STARTER);

        assertFalse(outcome.signed(),
                "overwriting a contract is not a transfer route, it is a purchase button");
        assertTrue(outcome.reason().toLowerCase().contains("negotiated"),
                "and the manager is told to negotiate instead: " + outcome.reason());
        assertEquals(seller.getId(), players.findById(p.getId()).orElseThrow().getTeam().getId(),
                "he is still where he was");
    }

    @Test
    @DisplayName("a contract lasts as long as it says, in this game's twelve-week seasons")
    void contractLengthIsInSeasonsNotYears() {
        // A season is twelve weeks, so about three months. A 24-month deal is roughly eight seasons.
        // Dividing by 12 treated a season as a year and expired every contract four times too soon.
        assertEquals(2, PlayerContractService.seasonsFor(6));
        assertEquals(4, PlayerContractService.seasonsFor(12));
        assertEquals(8, PlayerContractService.seasonsFor(24));
        assertEquals(20, PlayerContractService.seasonsFor(60));
    }

    @Test
    @DisplayName("a signing with no club or a club that does not exist is refused")
    void signingNeedsARealClub() {
        Team club = aClub("Needs a club");
        Player p = aPlayer(null, "Homeless", 24, 800_000, 2_000);

        assertFalse(service.sign(null, p.getId(), 24, 5_000, SquadRole.STARTER).signed());
        assertFalse(service.sign(999_999_999L, p.getId(), 24, 5_000, SquadRole.STARTER).signed());
        assertTrue(club.getId() > 0);
    }

    @Test
    @DisplayName("a full squad refuses a signing rather than over-registering")
    void aFullSquadTurnsSigningsAway() {
        Team club = aClub("Full");
        Team other = aClub("Filler");
        for (int i = 0; i < SquadRegistrationService.MAX_CLUB_SQUAD; i++) {
            Player existing = aPlayer(other, "Filler " + i, 24, 500_000, 1_000);
            service.assignToClub(existing, club, 3, SquadRole.STARTER);
        }
        Player hopeful = aPlayer(other, "Hopeful", 22, 900_000, 1_000);
        hopeful.setTeam(null);
        players.save(hopeful);

        PlayerContractService.Outcome outcome =
                service.sign(club.getId(), hopeful.getId(), 24, 4_000, SquadRole.STARTER);

        assertFalse(outcome.signed(), "a club at the registration limit must not sign anyone");
        assertNotNull(outcome.reason());
    }

    @Test
    @DisplayName("an expired player leaves his club rather than lingering in its squad")
    void expiryReleasesThePlayerNotJustThePaperwork() {
        Team club = aClub("Releasing");
        Player p = aPlayer(club, "Runs out", 31, 400_000, 2_000);
        PlayerContract c = service.assignToClub(p, club, 3, SquadRole.ROTATION);
        c.setExpirySeason(3);
        contracts.save(c);

        // A contract running to 3 is still valid during 3; it lapses going into 2027.
        service.expireContracts(3);
        assertNotNull(players.findById(p.getId()).orElseThrow().getTeam(),
                "he is still under contract in his final season");
        service.expireContracts(2027);

        Player reloaded = players.findById(p.getId()).orElseThrow();
        assertNull(reloaded.getTeam(),
                "his contract ran out, so he has left the club - not merely lost his paperwork");
        assertNull(contracts.findByPlayerId(p.getId()).orElseThrow().getTeam());
    }
}
