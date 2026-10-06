package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.FriendlyOffer;
import org.example.footballmanager.newLogic.model.FriendlyOffer.OfferStatus;
import org.example.footballmanager.newLogic.model.GameClock;
import org.example.footballmanager.newLogic.model.MatchFixture;
import org.example.footballmanager.newLogic.model.MatchType;
import org.example.footballmanager.newLogic.model.SeasonCalendar;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.FriendlyOfferRepository;
import org.example.footballmanager.newLogic.repository.GameClockRepository;
import org.example.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The free-slot board: a club advertises a slot, any other human club takes it (owner, 2026-10-06).
 *
 * <h2>Only human clubs, and that is a rule rather than a default</h2>
 *
 * <p>The owner: <b>"only human teams play friendlies."</b> So posting is refused for a bot-run club, and so
 * is taking. That is written in {@link #isHuman} rather than assumed from the caller's identity, because
 * the question "is this a bot" is a property of the <i>club</i> and asking the caller who it is gets the
 * answer from whoever is asking.
 *
 * <h2>The posting names its season, week and day, and expires with that slot</h2>
 *
 * <p>Also the owner's: <i>"once the friendly slot passes, it expires; and since there are several slots in
 * the invitation it has to say precisely which season/week/day it applies to."</i> A week has more than one
 * friendly slot — the league's two, and week 11 hands one to the playoff — so a posting that named only a
 * week would be a claim on the wrong day.
 *
 * <h2>Taking a posting goes through the request service, not around it</h2>
 *
 * <p>A club that accepts an ad has not agreed to anything the moment it clicks; the two sides are still two
 * sides, and the ad says nothing about whether they will turn up. So taking it creates a real
 * {@code FriendlyRequest} between the two clubs and the slot is held from that moment, which means the
 * "one live request per side per slot" rule applies to ads exactly as it does to asks.
 */
@Service
public class FriendlyOfferService {

    private static final Logger log = LoggerFactory.getLogger(FriendlyOfferService.class);

    private final FriendlyOfferRepository offers;
    private final FriendlyRequestService requests;
    private final MatchFixtureRepository fixtures;
    private final TeamRepository teams;
    private final GameClockRepository clocks;

    public FriendlyOfferService(FriendlyOfferRepository offers,
                                FriendlyRequestService requests,
                                MatchFixtureRepository fixtures,
                                TeamRepository teams,
                                GameClockRepository clocks) {
        this.offers = offers;
        this.requests = requests;
        this.fixtures = fixtures;
        this.teams = teams;
        this.clocks = clocks;
    }

    /** Whether this club is run by a person. The owner's rule, asked of the club and not the caller. */
    private boolean isHuman(Team team) {
        return team != null && Boolean.TRUE.equals(team.isHumanControlled());
    }

    /**
     * Advertises one slot.
     *
     * <p>Empty rather than throwing for every refusal, and the reasons are distinct enough to be worth
     * separating in the log: a bot may not post, a slot that has already passed may not be advertised, a
     * slot that is not open may not be advertised, and a club already playing it has nothing to offer.
     */
    @Transactional
    public Optional<FriendlyOffer> post(Long teamId, int seasonYear, int week, int slot) {
        Team team = teams.findById(teamId).orElse(null);
        if (!isHuman(team)) {
            log.info("{} is not human-run, so it does not post friendlies.", teamId);
            return Optional.empty();
        }
        int day = dayOf(slot);
        if (!isOpen(seasonYear, week, slot)) {
            return Optional.empty();
        }
        if (hasPassed(seasonYear, week, day)) {
            return Optional.empty();
        }
        if (offers.findByOfferingTeamIdAndSeasonYearAndWeekNumberAndDayNumber(
                teamId, seasonYear, week, day).isPresent()) {
            // The unique constraint would say so too, but as an exception rather than an answer.
            return Optional.empty();
        }
        if (alreadyPlaying(teamId, seasonYear, week, day)) {
            return Optional.empty();
        }

        FriendlyOffer offer = new FriendlyOffer();
        offer.setOfferingTeam(team);
        offer.setSeasonYear(seasonYear);
        offer.setWeekNumber(week);
        offer.setDayNumber(day);
        offer.setSlot(slot);
        offer.setStatus(OfferStatus.OPEN);
        offer.setCreatedAt(Instant.now());
        FriendlyOffer saved = offers.save(offer);
        log.info("{} advertised week {} day {} (season {}).", team.getName(), week, day, seasonYear);
        return Optional.of(saved);
    }

    /**
     * Takes an advertised slot, by asking the club that posted it.
     *
     * <p>The ad is marked fulfilled the moment the request exists, not when it is accepted: two clubs both
     * clicking the same ad would otherwise both succeed and one of them would find out on the day.
     */
    @Transactional
    public Optional<FriendlyOffer> claim(Long offerId, Long claimingTeamId) {
        FriendlyOffer offer = offers.findById(offerId).orElse(null);
        if (offer == null || offer.getStatus() != OfferStatus.OPEN) {
            return Optional.empty();
        }
        Team claimer = teams.findById(claimingTeamId).orElse(null);
        if (!isHuman(claimer) || claimingTeamId.equals(offer.getOfferingTeam().getId())) {
            return Optional.empty();
        }
        if (hasPassed(offer.getSeasonYear(), offer.getWeekNumber(), offer.getDayNumber())) {
            return Optional.empty();
        }

        // The request service reads the season from the clock rather than being told, which is right: a
        // posting carries the season it was written for, but a request is a live act and belongs to the
        // season that is running now. `hasPassed` above has already refused an offer from a past season.
        Optional<org.example.footballmanager.newLogic.model.FriendlyRequest> request = requests.requestFriendly(
                offer.getOfferingTeam().getId(), claimingTeamId,
                offer.getWeekNumber(), offer.getSlot());
        if (request.isEmpty()) {
            // The two clubs cannot actually play each other in that slot - one of them is busy, or they
            // already have a request live. The ad stays open for somebody else.
            return Optional.empty();
        }

        offer.setStatus(OfferStatus.FULFILLED);
        offer.setFilledFixtureId(null);
        offers.save(offer);
        return Optional.of(offer);
    }

    /** Withdraws a posting this club made. */
    @Transactional
    public Optional<FriendlyOffer> withdraw(Long offerId, Long teamId) {
        FriendlyOffer offer = offers.findById(offerId).orElse(null);
        if (offer == null || offer.getStatus() != OfferStatus.OPEN) {
            return Optional.empty();
        }
        if (!teamId.equals(offer.getOfferingTeam().getId())) {
            return Optional.empty();
        }
        offer.setStatus(OfferStatus.WITHDRAWN);
        return Optional.of(offers.save(offer));
    }

    /**
     * Retires every posting whose slot has passed, and returns how many.
     *
     * <p>Run from the week rollover. Idempotent, and it only ever moves {@code OPEN} rows, so a posting
     * that was taken stays {@code FULFILLED} and still points at the match it produced.
     */
    @Transactional
    public int expirePassed() {
        GameClock clock = clocks.findById(1L).orElse(null);
        if (clock == null || clock.getCurrentSeason() == null) {
            return 0;
        }
        int season = clock.getCurrentSeason();
        int week = clock.getCurrentWeek() == null ? 1 : clock.getCurrentWeek();
        int day = clock.getCurrentDay() == null ? 1 : clock.getCurrentDay();

        int expired = 0;
        for (FriendlyOffer offer : offers.findBySeasonYearAndWeekNumberAndStatusOrderByIdAsc(
                season, week, OfferStatus.OPEN)) {
            if (hasPassed(offer.getSeasonYear(), offer.getWeekNumber(), offer.getDayNumber())) {
                offer.setStatus(OfferStatus.EXPIRED);
                offers.save(offer);
                expired++;
            }
        }
        // The clock's own week has moved on, so last week's postings are dead even though no clock tick
        // happened while they were open.
        for (FriendlyOffer offer : offers.findBySeasonYearAndWeekNumberAndStatusOrderByIdAsc(
                season, week - 1, OfferStatus.OPEN)) {
            offer.setStatus(OfferStatus.EXPIRED);
            offers.save(offer);
            expired++;
        }
        if (expired > 0) {
            log.info("{} friendly posting(s) expired as their slot passed.", expired);
        }
        return expired;
    }

    /** The board: what is advertised in a week, and by whom. */
    @Transactional(readOnly = true)
    public List<FriendlyOffer> board(int seasonYear, int week) {
        List<FriendlyOffer> out = new ArrayList<>();
        for (FriendlyOffer offer : offers.findBySeasonYearAndWeekNumberAndStatusOrderByIdAsc(
                seasonYear, week, OfferStatus.OPEN)) {
            if (!hasPassed(offer.getSeasonYear(), offer.getWeekNumber(), offer.getDayNumber())) {
                out.add(offer);
            }
        }
        return out;
    }

    /** This club's own postings in a week, whatever their state. */
    @Transactional(readOnly = true)
    public List<FriendlyOffer> mine(Long teamId, int seasonYear, int week) {
        return offers.findByOfferingTeamIdAndSeasonYearAndWeekNumber(teamId, seasonYear, week);
    }

    // ------------------------------------------------------------------ rules

    /** Whether a week and slot can be offered at all, per the season's own table. */
    private boolean isOpen(int seasonYear, int week, int slot) {
        SeasonCalendar.WeekSlot spec = SeasonCalendar.slot(week, slot);
        return spec != null && spec.friendlyCapable();
    }

    /**
     * Whether the slot this posting is about has gone.
     *
     * <p>The owner's rule, and it is compared against the **day**, not the week: a posting for day 3 is dead
     * once day 3 is over, and one for day 7 is still live until then. That is the entire reason the day is
     * stored on the row rather than derived at read time.
     */
    boolean hasPassed(int seasonYear, int week, int day) {
        GameClock clock = clocks.findById(1L).orElse(null);
        if (clock == null || clock.getCurrentSeason() == null) {
            return false;
        }
        int currentSeason = clock.getCurrentSeason();
        int currentWeek = clock.getCurrentWeek() == null ? 1 : clock.getCurrentWeek();
        int currentDay = clock.getCurrentDay() == null ? 1 : clock.getCurrentDay();
        if (currentSeason != seasonYear) {
            return seasonYear < currentSeason;
        }
        if (currentWeek != week) {
            return currentWeek > week;
        }
        return currentDay > day;
    }

    private boolean alreadyPlaying(Long teamId, int seasonYear, int week, int day) {
        return fixtures.findBySeasonYearAndWeekNumber(seasonYear, week).stream()
                .anyMatch(f -> f.getHomeTeam() != null && f.getHomeTeam().getId().equals(teamId)
                        || f.getAwayTeam() != null && f.getAwayTeam().getId().equals(teamId));
    }

    /** The game day a slot falls on. Derived from the calendar rather than restated. */
    static int dayOf(int slot) {
        // The calendar is four slots wide (day 1, 3, 5, 7) and owns that mapping. Deriving it here as
        // "slot 1 or else day 3" would put a slot-3 posting on the league's day, which is the exact
        // collision the posting is supposed to avoid.
        return SeasonCalendar.dayForSlot(slot);
    }
}