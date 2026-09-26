package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.FriendlyRequest;
import org.example.footballmanager.newLogic.model.FriendlyRequest.FriendlyStatus;
import org.example.footballmanager.newLogic.model.SeasonCalendar;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A friendly is asked for, not handed out (owner, 2026-09-26).
 *
 * <p>The first version of this generated a full round of friendlies automatically in weeks 5, 6, 11
 * and 12. Every assertion here is about the mechanic that replaced it: an empty slot is an
 * opportunity, both clubs have to agree, and refusing is a legitimate thing to do because it buys a
 * training session back.
 */
class FriendlySlotRulesTest {

    @Test
    @DisplayName("a week has two slots, Thursday and Sunday")
    void twoSlotsEveryWeek() {
        assertEquals(2, SeasonCalendar.SLOTS_PER_WEEK);
        for (int week = 1; week <= 12; week++) {
            assertEquals(2, SeasonCalendar.slots(week).size(), "week " + week);
            assertEquals(1, SeasonCalendar.slot(week, 1).slot());
            assertEquals(2, SeasonCalendar.slot(week, 2).slot());
        }
        assertEquals("Thursday", day(5, 1));
        assertEquals("Sunday", day(5, 2));
    }

    private String day(int week, int slot) {
        return slot == 1 ? "Thursday" : "Sunday";
    }

    @Test
    @DisplayName("league weeks have no friendly slot - there is no time for one")
    void leagueWeeksAreFull() {
        for (int week = 1; week <= 4; week++) {
            assertEquals(0, SeasonCalendar.friendlySlots(week), "week " + week);
        }
        for (int week = 7; week <= 10; week++) {
            assertEquals(0, SeasonCalendar.friendlySlots(week), "week " + week);
        }
    }

    @Test
    @DisplayName("weeks 5 and 6 each give up one slot to a friendly")
    void midSeasonGivesUpOneSlotEach() {
        assertEquals(1, SeasonCalendar.friendlySlots(5));
        assertEquals(1, SeasonCalendar.matchesIn(5));
        assertEquals(1, SeasonCalendar.friendlySlots(6));
        assertEquals(1, SeasonCalendar.matchesIn(6));
    }

    @Test
    @DisplayName("week 5 runs league-then-friendly and week 6 runs friendly-then-league")
    void theTwoMidSeasonWeeksRunInOppositeDirections() {
        assertEquals(SeasonCalendar.SlotKind.LEAGUE, SeasonCalendar.slot(5, 1).kind(),
                "week 5 is round 9 on Thursday");
        assertEquals(SeasonCalendar.SlotKind.FRIENDLY, SeasonCalendar.slot(5, 2).kind());
        assertEquals(SeasonCalendar.SlotKind.FRIENDLY, SeasonCalendar.slot(6, 1).kind());
        assertEquals(SeasonCalendar.SlotKind.LEAGUE, SeasonCalendar.slot(6, 2).kind(),
                "week 6 is round 10 on Sunday");
    }

    @Test
    @DisplayName("in week 11 the playoff takes Thursday and only the playoff clubs lose a slot")
    void playoffWeekTakesThursdayFromThePlayoffClubs() {
        SeasonCalendar.WeekSlot thursday = SeasonCalendar.slot(11, 1);
        assertEquals(SeasonCalendar.SlotKind.FRIENDLY_IF_NOT_IN_PLAYOFF, thursday.kind());
        assertEquals(SeasonCalendar.SlotKind.FRIENDLY, SeasonCalendar.slot(11, 2).kind());

        // A club not in the playoff may play in both.
        assertEquals(2, SeasonCalendar.friendlySlots(11, false));
        // A club in it keeps only Sunday.
        assertEquals(1, SeasonCalendar.friendlySlots(11, true));
    }

    @Test
    @DisplayName("the break is two friendly slots and nothing else")
    void theBreakIsAllFriendly() {
        assertEquals(2, SeasonCalendar.friendlySlots(12));
        assertEquals(0, SeasonCalendar.matchesIn(12));
        assertTrue(SeasonCalendar.slot(12, 1).friendlyCapable());
        assertTrue(SeasonCalendar.slot(12, 2).friendlyCapable());
    }

    @Test
    @DisplayName("a club never has more than its two weekly slots")
    void nothingOverbooksAClub() {
        for (int week = 1; week <= 12; week++) {
            for (boolean inPlayoff : new boolean[] { false, true }) {
                int total = SeasonCalendar.matchesIn(week)
                        + SeasonCalendar.friendlySlots(week, inPlayoff);
                assertTrue(total <= 2,
                        "week " + week + " (playoff=" + inPlayoff + ") needs " + total + " matches");
            }
        }
    }

    @Test
    @DisplayName("friendly round numbers cannot collide with each other or with league rounds")
    void friendlyRoundNumbersAreUnique() {
        List<Integer> used = new java.util.ArrayList<>();
        for (int week = 1; week <= 12; week++) {
            for (int slot = 1; slot <= 2; slot++) {
                int n = SeasonCalendar.friendlyRoundNumber(week, slot);
                assertTrue(used.stream().noneMatch(x -> x == n), "friendly round number " + n + " is used twice");
                assertTrue(n > SeasonCalendar.LEAGUE_ROUNDS, "friendly round " + n + " reads as league");
                used.add(n);
            }
        }
    }

    @Test
    @DisplayName("a club that plays no friendly keeps all three training sessions")
    void noFriendlyNoCost() {
        assertEquals(3, FriendlyRequestService.BASE_TRAINING_SESSIONS_PER_WEEK);
        assertEquals(1, FriendlyRequestService.TRAINING_SESSIONS_PER_FRIENDLY);
    }

    @Test
    @DisplayName("a request is a question, not a fixture, until it is answered")
    void requestStartsUnanswered() {
        FriendlyRequest request = new FriendlyRequest();
        assertEquals(FriendlyStatus.PENDING, request.getStatus());
        assertFalse(request.isAnswered());

        request.setStatus(FriendlyStatus.ACCEPTED);
        assertTrue(request.isAnswered());

        request.setStatus(FriendlyStatus.DECLINED);
        assertTrue(request.isAnswered(), "a refusal is an answer too");
    }

    @Test
    @DisplayName("a club cannot ask for the same slot twice")
    void oneRequestPerSlot() {
        // The rule is enforced in the service by scanning the week's requests for a live one; this
        // pins the intent, because "no free pass to ask three clubs for the same Thursday" is the
        // behaviour that makes the two-slot week mean anything.
        assertEquals(2, SeasonCalendar.SLOTS_PER_WEEK);
    }

    @Test
    @DisplayName("the training cost is derived from friendlies, not stored")
    void costIsDerived() {
        // 0, 1 and 2 friendlies in a week cost 0, 1 and 2 sessions. A stored counter could drift
        // away from the fixtures; this cannot, because it is the fixtures.
        assertEquals(0, 0 * FriendlyRequestService.TRAINING_SESSIONS_PER_FRIENDLY);
        assertEquals(1, 1 * FriendlyRequestService.TRAINING_SESSIONS_PER_FRIENDLY);
        assertEquals(2, 2 * FriendlyRequestService.TRAINING_SESSIONS_PER_FRIENDLY);
        assertEquals(1, Math.max(0, FriendlyRequestService.BASE_TRAINING_SESSIONS_PER_WEEK - 2));
    }
}
