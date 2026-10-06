package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.model.CompetitionTeamType;
import org.example.footballmanager.newLogic.model.Country;
import org.example.footballmanager.newLogic.model.FriendlyOffer;
import org.example.footballmanager.newLogic.model.GameClock;
import org.example.footballmanager.newLogic.model.SeasonCalendar;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.CountryRepository;
import org.example.footballmanager.newLogic.repository.FriendlyOfferRepository;
import org.example.footballmanager.newLogic.repository.GameClockRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The free-slot board (owner, 2026-10-06).
 *
 * <p>Two rules, both the owner's, and both easy to get wrong in opposite directions:
 *
 * <ul>
 *   <li><b>"Only human teams play friendlies."</b> Tested on the board rather than on the ad, because a
 *       bot that can post fills the board with adverts nobody may take, and a board nobody may take is
 *       worse than no board.</li>
 *   <li><b>"Once the friendly slot passes, it expires — and since there are several slots in the
 *       invitation, it has to say precisely which season/week/day it applies to."</b> The interesting half
 *       is the expiry being per <b>day</b>, not per week: week 11 offers two slots and week 11's day-3
 *       posting must be dead while its day-7 posting is still live.</li>
 * </ul>
 */
class FriendlyOfferServiceTest extends BaseTest {

    @Autowired private FriendlyOfferService offers;
    @Autowired private SeasonService seasons;
    @Autowired private CountryRepository countries;
    @Autowired private TeamRepository teams;
    @Autowired private GameClockRepository clocks;
    @Autowired private FriendlyOfferRepository offerRepository;

    private Team human;
    private Team otherHuman;
    private Team bot;

    @BeforeEach
    void setUp() {
        Country country = new Country();
        country.setName("Offer Republic " + System.nanoTime());
        String tail = Long.toString(Math.abs(System.nanoTime()) % 1296, 36);
        while (tail.length() < 2) {
            tail = "0" + tail;
        }
        country.setIsoCode("O" + tail);
        country.setReputation(1500);
        country = countries.save(country);
        human = club(country, "Human", true);
        otherHuman = club(country, "Other Human", true);
        bot = club(country, "Bot", false);
        seasons.getOrCreateClock();
    }

    private Team club(Country country, String name, boolean humanRun) {
        Team t = new Team();
        t.setName(name + " " + System.nanoTime());
        t.setType(CompetitionTeamType.CLUB);
        t.setCountry(country);
        t.setReputation(60.0);
        t.setHumanControlled(humanRun);
        return teams.save(t);
    }

    private int season() {
        return clocks.findById(1L).map(GameClock::getCurrentSeason).orElse(1);
    }

    private int week() {
        return clocks.findById(1L).map(GameClock::getCurrentWeek).orElse(1);
    }

    @Test
    @Transactional
    @DisplayName("only human clubs may post, and only human clubs may take")
    void onlyHumanClubs() {
        assertTrue(offers.post(bot.getId(), season(), week(), 1).isEmpty(),
                "a bot-run club does not advertise friendlies: only human teams play them");
        assertTrue(offers.post(human.getId(), season(), week(), 1).isPresent(),
                "a human club may advertise its slot");

        FriendlyOffer offer = offers.board(season(), week()).get(0);
        assertTrue(offers.claim(offer.getId(), bot.getId()).isEmpty(),
                "a bot-run club does not take a friendly either");
    }

    @Test
    @Transactional
    @DisplayName("a posting states its season, week and day - and never a club it does not belong to")
    void aPostingNamesItsPeriod() {
        // Slot 3, not 2: under the four-slot calendar slot 2 is day 3, the league's day, and a league
        // slot is not friendly-capable. Slot 3 is day 5, which is a friendly slot in every week.
        FriendlyOffer offer = offers.post(human.getId(), season(), week(), 3).orElseThrow();
        assertEquals(season(), offer.getSeasonYear());
        assertEquals(week(), offer.getWeekNumber());
        assertEquals(SeasonCalendar.dayForSlot(3), offer.getDayNumber(),
                "the posting states the day its slot falls on, rather than leaving it to be derived "
                        + "at read time - which is the owner's whole point about naming the period");
        assertEquals(5, offer.getDayNumber(), "and that day is 5, not the league's 3");
        assertTrue(offers.withdraw(offer.getId(), otherHuman.getId()).isEmpty(),
                "another club cannot withdraw a posting it did not make");
    }

    @Test
    @Transactional
    @DisplayName("a posting expires when its own slot passes, not at the end of the week")
    void expiryIsPerSlotNotPerWeek() {
        int currentWeek = week();
        FriendlyOffer offer = offers.post(human.getId(), season(), currentWeek, 1).orElseThrow();
        assertEquals(SeasonCalendar.dayForSlot(1), offer.getDayNumber());

        // Move the clock past the first slot but not the second.
        GameClock clock = clocks.findById(1L).orElseThrow();
        clock.setCurrentDay(SeasonCalendar.dayForSlot(4));
        clocks.save(clock);

        assertTrue(offers.hasPassed(season(), currentWeek, SeasonCalendar.dayForSlot(1)),
                "the first slot's day is over once the clock has moved past it");
        assertFalse(offers.hasPassed(season(), currentWeek, SeasonCalendar.dayForSlot(4)),
                "the last slot's day is still live, and a posting for it must still be claimable");

        // And the expiry pass retires the passed one.
        offers.expirePassed();
        assertTrue(offerRepository.findById(offer.getId()).orElseThrow().getStatus()
                        == FriendlyOffer.OfferStatus.EXPIRED,
                "a posting whose slot has passed is retired by the expiry pass");
    }

    @Test
    @Transactional
    @DisplayName("a posting cannot be taken after its slot has gone")
    void aPostingCannotBeTakenLate() {
        FriendlyOffer offer = offers.post(human.getId(), season(), week(), 1).orElseThrow();
        GameClock clock = clocks.findById(1L).orElseThrow();
        clock.setCurrentDay(SeasonCalendar.dayForSlot(4));
        clocks.save(clock);

        assertTrue(offers.claim(offer.getId(), otherHuman.getId()).isEmpty(),
                "taking an advertisement for a day that has passed must be refused");
    }

    @Test
    @Transactional
    @DisplayName("taking a posting asks the club that posted it, and the slot leaves the board")
    void takingAnOfferCreatesARequest() {
        FriendlyOffer offer = offers.post(human.getId(), season(), week(), 1).orElseThrow();
        assertTrue(offers.claim(offer.getId(), otherHuman.getId()).isPresent(),
                "taking a posting is asking the club that posted it");

        assertTrue(offers.board(season(), week()).isEmpty(),
                "a fulfilled posting is off the board, so two clubs cannot both take it");
        assertEquals(FriendlyOffer.OfferStatus.FULFILLED,
                offerRepository.findById(offer.getId()).orElseThrow().getStatus());
    }

    @Test
    @Transactional
    @DisplayName("a club cannot take its own posting")
    void noSelfTake() {
        FriendlyOffer offer = offers.post(human.getId(), season(), week(), 1).orElseThrow();
        assertTrue(offers.claim(offer.getId(), human.getId()).isEmpty());
    }

    @Test
    @Transactional
    @DisplayName("a league slot cannot be advertised, but a league week's friendly slot can")
    void onlyFriendlyCapableSlotsCanBeAdvertised() {
        // **This test was wrong first, and in an instructive way.** It assumed weeks 1-5 and 7-10 were
        // pure league weeks with nothing to give away. The calendar is **four slots wide** - day 1
        // friendly, day 3 league, day 5 friendly, day 7 league - so a league week has two friendly slots
        // and one of them is exactly what this feature is for.
        //
        // The correct rule is not "league week" but "friendly-capable slot", and that is what is asserted:
        // the calendar is the authority, and it is asked rather than restated.
        for (int week = 1; week <= 12; week++) {
            for (int slot = 1; slot <= SeasonCalendar.SLOTS_PER_WEEK; slot++) {
                SeasonCalendar.WeekSlot spec = SeasonCalendar.slot(week, slot);
                boolean shouldWork = spec != null && spec.friendlyCapable();
                boolean worked = offers.post(human.getId(), season(), week, slot).isPresent();
                assertEquals(shouldWork, worked,
                        "week " + week + " slot " + slot + " (" + (spec == null ? "none" : spec.kind())
                                + "): whether it can be advertised must come from the calendar");
                if (worked) {
                    offers.mine(human.getId(), season(), week);
                }
            }
        }
    }

    @Test
    @Transactional
    @DisplayName("the same club cannot advertise the same slot twice")
    void noDuplicatePosting() {
        assertTrue(offers.post(human.getId(), season(), week(), 1).isPresent());
        assertTrue(offers.post(human.getId(), season(), week(), 1).isEmpty(),
                "the unique constraint would catch this, but as an exception rather than an answer");
    }
}