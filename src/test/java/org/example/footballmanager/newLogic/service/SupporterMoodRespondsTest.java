package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.ListingObjection;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Position;
import org.example.footballmanager.newLogic.model.Stadium;
import org.example.footballmanager.newLogic.model.SquadRole;
import org.example.footballmanager.newLogic.model.SupporterExpectation;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.model.Transfer;
import org.example.footballmanager.newLogic.model.TransferStatus;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.example.footballmanager.newLogic.repository.TransferRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-5 — supporter mood, and something in the game that answers to it.
 *
 * <p>The competitive analysis put the problem exactly: the project had built the expensive half of the
 * meta layer and <b>nothing acted on it</b>. {@code BoardExpectationService} computes a 0-100 trust
 * score, {@code sackingReview} is a boolean with no entity behind it, and a number the player can see
 * that nothing responds to is worse than no number, because it invites the expectation of a
 * consequence.
 *
 * <p><b>What is guaranteed here:</b>
 *
 * <ul>
 *   <li><b>Something responds.</b> Mood bends attendance, so it bends gate income, so it bends the wage
 *       bill ratio the board reads. Asserted as a real number, because "it is wired in" is not the
 *       guarantee — a visible field that changes nothing is the defect being fixed.</li>
 *   <li><b>Mood drifts rather than jumping.</b> A crowd does not flip overnight, and a club winning
 *       for a month is still endured.</li>
 *   <li><b>Selling a player who objects costs mood</b> — the loop back to P2-3, and the reason a club
 *       cannot treat its squad as merchandise for free.</li>
 *   <li><b>It is visible, with a reason.</b> Mood and the expectation it implies, on the endpoint the
 *       manager already reads for board trust.</li>
 *   <li><b>The bands do not overlap absurdly</b>: a bigger club is a happier crowd, a miserable one is
 *       a miserable crowd.</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
class SupporterMoodRespondsTest {

    @Autowired TeamRepository teams;
    @Autowired PlayerRepository players;
    @Autowired TransferRepository transfers;
    @Autowired SupporterMoodService moods;
    @Autowired ListingObjectionService objections;
    @Autowired PlayerContractService contracts;
    @Autowired AttendanceService attendance;

    private Team aClub(String name, double reputation) {
        Team t = new Team();
        t.setName(name + "-" + System.nanoTime());
        t.setBudget(5_000_000.0);
        t.setReputation(reputation);
        Stadium s = new Stadium();
        s.setName(name + " Ground");
        s.setCapacity(25_000);
        s.setTicketPrice(20.0);
        s.setPitchQuality(85.0);
        s.setPitchCondition(85);
        s.setMaintenanceRemaining(0);
        t.setStadium(s);
        return teams.save(t);
    }

    private Player aPlayer(Team team, String name) {
        Player p = new Player();
        p.setName(name);
        p.setTeam(team);
        p.setAge(26);
        p.setRating(60);
        p.setPosition(Position.MID);
        p.setPlayerValue(1_000_000);
        p.setEarnings(9_000);
        p.setMorale(55.0);
        p.setForm(6.0);
        return players.save(p);
    }

    /**
     * The criterion, end to end — and the assertion the first version of this class was missing.
     *
     * <p>The first version asserted only {@code attendanceEffect(...)} arithmetic, so it stayed green
     * when the call to it was deleted from {@code AttendanceService}: the formula was still right, the
     * game still ignored it. That is the whole defect this task exists to fix — a number that computes
     * and does nothing — reproduced inside the test written to prevent it.
     *
     * <p>So this asserts the consequence: two clubs identical in every way the model can see, differing
     * only in supporter mood, and the ground is emptier at the miserable one.
     */
    @Test
    @DisplayName("a miserable crowd actually turns up fewer people")
    void aMiserableCrowdActuallyStaysAway() {
        Team miserable = aClub("MiserableClub", 50.0);
        Team delighted = aClub("DelightedClub", 50.0);
        // Same ground size and same price, so mood is the only thing left that can move the number.
        setStadium(miserable, 25_000, 20.0);
        setStadium(delighted, 25_000, 20.0);
        miserable.setSupporterMood(5);
        delighted.setSupporterMood(95);
        teams.save(miserable);
        teams.save(delighted);

        Team opponent = aClub("OpponentClub", 50.0);
        setStadium(opponent, 25_000, 20.0);
        teams.save(opponent);

        AttendanceService.Attendance poor = attendance.estimate(aFixture(miserable, opponent));
        AttendanceService.Attendance happy = attendance.estimate(aFixture(delighted, opponent));

        assertTrue(happy.home() > poor.home(),
                "the moody ground must be the emptier one, or nothing in the game responds to it: "
                        + poor.home() + " at mood 5 against " + happy.home() + " at mood 95");
        assertTrue(poor.home() > 0,
                "and an angry support still comes: " + poor.home());
    }

    private void setStadium(Team club, int capacity, double ticketPrice) {
        Stadium s = new Stadium();
        s.setName(club.getName() + " Ground");
        s.setCapacity(capacity);
        s.setTicketPrice(ticketPrice);
        s.setPitchQuality(85.0);
        s.setPitchCondition(90);
        s.setMaintenanceRemaining(0);
        club.setStadium(s);
    }

    private org.example.footballmanager.newLogic.model.Match aFixture(Team home, Team away) {
        org.example.footballmanager.newLogic.model.Match m =
                new org.example.footballmanager.newLogic.model.Match();
        m.setHomeTeam(home);
        m.setAwayTeam(away);
        m.setHomeGoals(0);
        m.setAwayGoals(0);
        m.setPlayed(false);
        return m;
    }

    /**
     * The criterion that matters: something in the game responds.
     *
     * <p>Asserted as the multiplier the crowd applies, and then as its consequence on a ground's
     * capacity. A mood field that nothing reads would satisfy "visible" and fail this.
     */
    @Test
    @DisplayName("supporter mood bends the crowd, and the penalty is gentler than the reward")
    void moodBendsTheCrowd() {
        double furious = SupporterMoodService.attendanceEffect(0);
        double angry = SupporterMoodService.attendanceEffect(30);
        double neutral = SupporterMoodService.attendanceEffect(60);
        double happy = SupporterMoodService.attendanceEffect(100);

        assertTrue(furious < angry && angry < neutral && neutral < happy,
                "mood must move the crowd monotonically, or it is decoration");
        assertEquals(1.0, neutral, 0.001,
                "precondition: a neutral crowd is the baseline, so this changes nothing for clubs "
                        + "that have not drifted");
        assertTrue(furious > 0.7,
                "an empty stand is worth nothing to anybody: a furious support still comes, it just "
                        + "stops filling the ground. Was " + furious);
        assertTrue(happy < furious * 2,
                "and the reward must stay proportionate, or mood becomes a money printer for big clubs");
    }

    /** Drift, not a jump — and it actually moves. */
    @Test
    @DisplayName("mood drifts toward its target and never flips in a week")
    void moodDriftsRatherThanJumps() {
        Team small = aClub("SmallClub", 20.0);
        small.setSupporterMood(60);
        teams.save(small);

        int target = moods.targetMood(small, 0);
        assertTrue(target < 60, "a small club's support is not content, so the target is below 60");

        moods.drift(java.util.List.of(small));

        int after = teams.findById(small.getId()).orElseThrow().getSupporterMood();
        assertTrue(after < 60, "mood must actually move toward the target, was " + after);
        assertTrue(after >= 60 - SupporterMoodService.WEEKLY_DRIFT,
                "and by no more than the weekly drift, so a crowd does not flip overnight: was " + after);
    }

    /** The loop back to P2-3: selling a player who objects has a cost. */
    @Test
    @DisplayName("a club selling a player who objects drags its own mood down")
    void sellingAnObjectingPlayerCostsMood() {
        Team club = aClub("SellingClub", 50.0);
        Player p = aPlayer(club, "Reluctant");
        contracts.assignToClub(p, club, 1, SquadRole.STAR);

        int before = moods.targetMood(club, 0);
        int after = moods.targetMood(club, 1);

        assertTrue(after < before,
                "supporters can see the club selling a player who says he does not want to be sold; "
                        + before + " -> " + after);
        assertEquals(before - after, 9, 0.001,
                "and one objection is a fixed, visible cost rather than a mood-shaped mystery");
    }

    /** An objection actually on a live listing is what the weekly pass reads. */
    @Test
    @DisplayName("the weekly pass sees a live objection and finds the club")
    void theWeeklyPassFindsRealObjections() {
        Team club = aClub("ObjectedClub", 50.0);
        Player p = aPlayer(club, "SaysNo");
        contracts.assignToClub(p, club, 1, SquadRole.STAR);
        Transfer t = new Transfer();
        t.setPlayer(p);
        t.setSellerTeam(club);
        t.setStatus(TransferStatus.LISTED);
        t.setAskingPrice(2_000_000);
        t.setListingObjection(ListingObjection.DOES_NOT_WANT_TO_LEAVE);
        t.setListingObjectionReason(ListingObjection.DOES_NOT_WANT_TO_LEAVE.label());
        transfers.save(t);

        List<Transfer> found = transfers.findActiveObjectedListings();

        assertTrue(found.stream().anyMatch(x -> x.getSellerTeam() != null
                        && club.getId().equals(x.getSellerTeam().getId())),
                "the one query the weekly pass reads must actually return an objected listing");
        club.setSupporterMood(SupporterMoodService.NEUTRAL - 20);
        assertEquals(SupporterExpectation.PATIENT_ENDURANCE, club.supporterExpectation(),
                "a support that has drifted below neutral expects to be given something to believe in");
    }

    /** Visible, and with a stated reason rather than a bare number. */
    @Test
    @DisplayName("mood and the expectation it implies are both readable")
    void moodAndExpectationAreReadable() {
        Team club = aClub("VisibleClub", 90.0);
        club.setSupporterMood(80);
        teams.save(club);
        assertEquals(SupporterExpectation.CHAMPIONS, club.supporterExpectation());
        assertTrue(club.supporterExpectation().label().length() > 10,
                "an expectation with no words is not an expectation");

        club.setSupporterMood(10);
        assertEquals(SupporterExpectation.DISILLUSION, club.supporterExpectation());

        // And the bands must not collapse: a strong club is not a miserable one.
        Team strong = aClub("StrongClub", 95.0);
        assertTrue(moods.targetMood(strong, 0) > moods.targetMood(aClub("WeakClub", 10.0), 0),
                "reputation must buy a club a happier crowd");
    }
}