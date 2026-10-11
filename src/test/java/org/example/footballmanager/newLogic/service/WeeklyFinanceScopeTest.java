package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.CompetitionTeamType;
import org.example.footballmanager.newLogic.model.CompetitionType;
import org.example.footballmanager.newLogic.model.Country;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.CountryState;
import org.example.footballmanager.newLogic.model.FinanceLedgerEntry;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.CompetitionRepository;
import org.example.footballmanager.newLogic.repository.CountryRepository;
import org.example.footballmanager.newLogic.repository.FinanceLedgerEntryRepository;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D3, second half: the weekly finance settlement settles clubs, and it was settling national sides too.
 *
 * <p>{@code settleWeeklyFinancesForAllClubs} iterated {@code teamRepository.findAll()} — every team in
 * the world, which is 14,880 clubs and 96 national sides at target scale — and handed each to
 * {@code applyWeeklyFinances}, which is written for a club and objected to nothing. {@code broadcast}
 * reads the competition and <b>defaults a null one to weight 20</b>, so a national side was paid a
 * league broadcast share; {@code gate} projected a home fixture it never plays; {@code merchandising}
 * sized income off its 25-player squad; and a budget was written onto the team.
 *
 * <h2>Why these tests are NOT {@code @Transactional}, which every other test here is</h2>
 *
 * <p>{@code applyWeeklyFinances} is {@code REQUIRES_NEW}, so it commits in a transaction of its own —
 * and a transaction of its own <b>cannot see the uncommitted rows of the test's transaction.</b> Two
 * versions of this file were wrong because of it:
 *
 * <ol>
 *   <li>The first built its own teams inside a {@code @Transactional} test and asserted the ledger was
 *       empty for the national side. It passed — because <em>every</em> settlement had died on a
 *       foreign-key violation, including the real club the file used as its counterweight. The national
 *       side looked settled because nothing was settled at all.</li>
 *   <li>The second assumed a seeded world to borrow committed rows from. <b>The H2 test profile has no
 *       seeded world</b> — the nine-country seeding was removed on 2026-10-01 so that starting the app
 *       starts the app — so every test failed on "the seeded world has no national side".</li>
 * </ol>
 *
 * <p>So the fixtures are <b>committed</b> here, and cleaned up afterwards. The teams are left in place
 * because they are inert; the ledger rows they generated are deleted, because those are the rows that
 * would change another test's answer.
 *
 * <p>Assertions are per team id, never a count over the ledger: these tests share one database and other
 * classes write finance rows, so a global count measures running order.
 */
class WeeklyFinanceScopeTest extends BaseTest {

    @Autowired private WeeklyFinanceService finances;
    @Autowired private SeasonService seasons;
    @Autowired private TeamRepository teams;
    @Autowired private PlayerRepository players;
    @Autowired private CountryRepository countries;
    @Autowired private CompetitionRepository competitions;
    @Autowired private FinanceLedgerEntryRepository ledger;

    private final List<Long> createdTeams = new ArrayList<>();
    private Long aiClubId;
    private Integer season;

    @BeforeEach
    void createCommittedFixtures() {
        season = seasons.getOrCreateClock().getCurrentSeason();
        Country active = countries.save(aCountry(CountryState.ACTIVE));
        Country simulated = countries.save(aCountry(CountryState.SIMULATED));
        createdTeams.add(teams.save(nationalSide(active)).getId());
        createdTeams.add(withSquad(club(active)).getId());
        createdTeams.add(withSquad(club(simulated)).getId());
        aiClubId = createdTeams.get(2);
    }

    @AfterEach
    void removeTheLedgerRowsTheseTestsWrote() {
        for (Long teamId : createdTeams) {
            ledger.deleteAll(ledger.findByTeamIdAndSeasonYearOrderByWeekNumberAsc(teamId, season));
        }
    }

    @Test
    @DisplayName("a national side is not settled a club's economy")
    void aNationalSideIsNotSettled() {
        Team nationalSide = nationalSideById();
        Double budgetBefore = nationalSide.getBudget();
        int rowsBefore = rowsFor(nationalSide).size();

        WeeklyFinanceService.WeekResult result =
                finances.applyWeeklyFinances(nationalSide, season, 900);

        assertTrue(result.notApplied(),
                "the national side of " + nationalSide.getName() + " was settled. It has no competition, so "
                        + "the broadcast line defaults it to weight 20 and pays it league money it did not "
                        + "earn; the gate line projects a home fixture it never plays; merchandising sizes "
                        + "income off its 25-player squad.");

        assertEquals(rowsBefore, rowsFor(nationalSide).size(),
                "the settlement wrote ledger rows for a national side");

        assertEquals(budgetBefore, nationalSide.getBudget(),
                "the settlement wrote a budget onto a national side. Whatever it wrote came from a broadcast "
                        + "line that defaulted a missing competition to weight 20, so it is not a number "
                        + "derived from anything real.");
    }

    @Test
    @DisplayName("a real club is still settled — the guard is not a blanket skip")
    void aRealClubIsStillSettled() {
        // The counterweight. A guard that returned early for everything would satisfy the test above
        // perfectly while quietly ending every club's income — the failure this repository records most
        // often is a check that cannot fail because it cannot tell the two cases apart.
        Team club = clubById();
        Double budgetBefore = club.getBudget();

        WeeklyFinanceService.WeekResult result = finances.applyWeeklyFinances(club, season, 901);

        assertTrue(!result.notApplied(),
                club.getName() + " is a real club and was not settled. The guard is meant to skip national "
                        + "sides, not the economy the game runs on.");
        assertTrue(!rowsFor(club).isEmpty(),
                club.getName() + " was reported settled but has no ledger rows");
        assertTrue(club.getBudget() != budgetBefore,
                club.getName() + " was reported settled but its budget did not move");
    }

    /**
     * The other half of the owner's rule (2026-10-11): a club in a simulated country has no economy.
     *
     * <p>"Simulirane lige i AI klubovi nemaju ekonomiju i to, tu su samo za internacionalne mečeve,
     * bot klubovi i za NT bot reprezentacije. Samo human timovi (ili aktivne lige) imaju ekonomiju."
     *
     * <p>This is not tidiness. Settled, those clubs took merchandising and had no wages, no upkeep and
     * no gate to set against it: measured on the owner's world, 13,630 of them created **199,430,400 in
     * one week**. A second-division shell in Austria ended up carrying a bigger budget than a real club
     * in Serbia.
     */
    @Test
    @DisplayName("a club in a simulated country has no economy at all")
    void anAiClubIsNotSettled() {
        Team aiClub = teams.findById(aiClubId).orElseThrow();

        WeeklyFinanceService.WeekResult result = finances.applyWeeklyFinances(aiClub, season, 902);

        assertTrue(result.notApplied(),
                aiClub.getName() + " is in a SIMULATED country and was settled. It exists so a "
                        + "national side can be drawn against, and nothing else.");
        assertTrue(rowsFor(aiClub).isEmpty(),
                aiClub.getName() + " wrote ledger rows. With no wages, no upkeep and no gate to set "
                        + "against them, every line is money the game invented.");
    }

    // --- fixtures ---

    private Team nationalSideById() {
        return teams.findById(createdTeams.get(0)).orElseThrow();
    }

    private Team clubById() {
        return teams.findById(createdTeams.get(1)).orElseThrow();
    }

    private List<FinanceLedgerEntry> rowsFor(Team team) {
        if (season == null) {
            return List.of();
        }
        return ledger.findByTeamIdAndSeasonYearOrderByWeekNumberAsc(team.getId(), season);
    }

    private Country aCountry() {
        return aCountry(CountryState.SIMULATED);
    }

    private Country aCountry(CountryState state) {
        Country country = new Country();
        country.setName("ZZ Finance " + UUID.randomUUID());
        country.setIsoCode("I" + UUID.randomUUID().toString().substring(0, 2).toUpperCase());
        country.setState(state);
        return countries.save(country);
    }

    /**
     * Gives a club a squad, because a club with no players has no economy either.
     *
     * <p>Two guards now decide what gets settled, and both are load-bearing: a club must be in an
     * economy-eligible country, <em>and</em> have at least one player. The second exists because the
     * first alone was not the whole story - the 13,630 clubs that invented 199m a week were all
     * excluded by the first, but the rule that catches the shape of the bug is "no squad, no ledger",
     * whatever the country's state.
     */
    private Team withSquad(Team club) {
        club = teams.save(club);
        for (int i = 0; i < 18; i++) {
            Player p = new Player();
            p.setName("ZZ Finance player " + i + " " + UUID.randomUUID());
            p.setTeam(club);
            p.setAge(23);
            p.setRating(70);
            p.setPlayerValue(1_000_000);
            p.setEarnings(2_500);
            players.save(p);
        }
        return club;
    }

    private Team nationalSide(Country country) {
        Team team = new Team();
        team.setName("ZZ Finance national " + UUID.randomUUID());
        team.setCountry(country);
        team.setBudget(0.0);
        return team;
    }

    private Team club(Country country) {
        Competition competition = new Competition();
        competition.setName("ZZ Finance league " + UUID.randomUUID());
        competition.setType(CompetitionType.LEAGUE);
        competition.setTier(1);
        competition.setReputationWeight(20);
        competition.setCountry(country);
        competition = competitions.save(competition);

        Team team = new Team();
        team.setName("ZZ Finance club " + UUID.randomUUID());
        team.setCountry(country);
        team.setCompetition(competition);
        team.setReputation(60.0);
        team.setType(CompetitionTeamType.CLUB);
        team.setBudget(1000.0);
        return team;
    }
}
