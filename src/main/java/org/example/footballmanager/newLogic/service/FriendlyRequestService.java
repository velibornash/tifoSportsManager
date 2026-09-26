package org.example.footballmanager.newLogic.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.newLogic.model.FriendlyRequest;
import org.example.footballmanager.newLogic.model.FriendlyRequest.FriendlyStatus;
import org.example.footballmanager.newLogic.model.GameClock;
import org.example.footballmanager.newLogic.model.MatchFixture;
import org.example.footballmanager.newLogic.model.SeasonCalendar;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.GameClockRepository;
import org.example.footballmanager.newLogic.repository.FriendlyRequestRepository;
import org.example.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Friendlies are requested, not scheduled (owner-defined, 2026-09-26).
 *
 * <p>This replaced a generator that quietly created a full round of friendlies in weeks 5, 6, 11 and
 * 12, which was wrong twice over: a manager was handed matches they had not asked for, and a club
 * had no way to trade a friendly for training time. Now a week simply has two slots, an empty one
 * is an opportunity, and a club asks.
 *
 * <p>The decision the owner actually wants to make is therefore real: a club with an open slot can
 * take the friendly and give up a training session, or leave it and train. That is why
 * {@link #trainingSessionsLostToFriendlies} exists even though there is no training system yet — the
 * cost is <i>derived</i> from the agreed friendlies rather than stored, so it cannot drift away from
 * the fixtures and Sprint 4 can read it rather than reinvent it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FriendlyRequestService {

    /**
     * How many training sessions a club gets in a week with no football commitments.
     *
     * <p>Deliberately small and explicit: one friendly costs one session, so a club playing both
     * friendly slots in the week-12 break trains once less than one playing none.
     */
    public static final int BASE_TRAINING_SESSIONS_PER_WEEK = 3;

    /** Training sessions a club gives up per friendly played. */
    public static final int TRAINING_SESSIONS_PER_FRIENDLY = 1;

    private final FriendlyRequestRepository requests;
    private final MatchFixtureRepository fixtures;
    private final TeamRepository teams;
    private final GameClockRepository clocks;
    private final PlayerRepository players;

    /**
     * The current clock, read from its repository.
     *
     * <p>Straight from the repository rather than through SeasonService: SeasonService depends on
     * the transfer machinery, which already depends on the window service, and going back through
     * SeasonService here closes that cycle. The same reasoning as TransferWindowService.
     */
    private GameClock currentClock() {
        return clocks.findAll().stream().findFirst().orElse(null);
    }

    // ---------------------------------------------------------------- asking

    /**
     * Asks another club for a friendly in a given week and slot.
     *
     * <p>Refuses rather than throws for the ordinary "no" cases, because these are all things a
     * manager does by accident: asking a club that is already playing, asking a club that is
     * already busy, or asking for a slot that is not open. A refusal with a reason reads better in
     * the UI than a stack trace.
     */
    @Transactional
    public Optional<FriendlyRequest> requestFriendly(Long requesterId, Long opponentId, int week, int slot) {
        if (Objects.equals(requesterId, opponentId)) {
            return Optional.empty();
        }
        if (!teams.existsById(opponentId)) {
            return Optional.empty();
        }

        GameClock clock = currentClock();
        if (clock == null || clock.getCurrentSeason() == null) {
            return Optional.empty();
        }
        Integer season = clock.getCurrentSeason();
        // A friendly for a week that has already been played is not a friendly.
        int currentWeek = clock.getCurrentWeek() == null ? 1 : clock.getCurrentWeek();
        if (week < currentWeek || week > SeasonCalendar.WEEKS_PER_SEASON) {
            return Optional.empty();
        }

        SeasonCalendar.WeekSlot weekSlot = SeasonCalendar.slot(week, slot);
        if (weekSlot == null || !weekSlot.friendlyCapable()) {
            return Optional.empty();
        }
        // Week 11's Thursday slot belongs to the playoff, so a club in it cannot be asked to play
        // there, and cannot ask to play there either.
        if (week == SeasonCalendar.PLAYOFF_WEEK && isInPlayoff(requesterId, season, week)) {
            return Optional.empty();
        }
        if (isBusy(requesterId, season, week, slot) || isBusy(opponentId, season, week, slot)) {
            return Optional.empty();
        }
        // One request per club per slot, so a club cannot stack three asks on the same Thursday.
        if (hasPendingOrAccepted(requesterId, season, week, slot)) {
            return Optional.empty();
        }

        FriendlyRequest request = new FriendlyRequest();
        request.setRequesterTeamId(requesterId);
        request.setOpponentTeamId(opponentId);
        request.setSeason(season);
        request.setWeek(week);
        request.setSlot(slot);
        request.setStatus(FriendlyStatus.PENDING);
        return Optional.of(requests.save(request));
    }

    // ------------------------------------------------------------- answering

    /**
     * The other club accepts or refuses.
     *
     * <p>Only the club that was asked may answer, which is checked against the id rather than
     * trusting the caller — otherwise a manager could answer on another club's behalf.
     */
    @Transactional
    public FriendlyRequest respond(Long requestId, Long clubId, boolean accept, String declineReason) {
        FriendlyRequest request = requests.findByIdAndOpponentTeamId(requestId, clubId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No pending friendly request " + requestId + " addressed to club " + clubId));

        if (request.isAnswered()) {
            return request;     // already settled; do not create a second fixture
        }
        if (!accept) {
            request.setStatus(FriendlyStatus.DECLINED);
            request.setDeclineReason(declineReason == null || declineReason.isBlank()
                    ? "No reason given" : declineReason);
            return requests.save(request);
        }

        // Re-check the slot at the moment of acceptance: the week may have been ticked on since the
        // request was made, and the other club may have taken its own friendly in the meantime.
        if (isBusy(request.getOpponentTeamId(), request.getSeason(), request.getWeek(), request.getSlot())
                || isBusy(request.getRequesterTeamId(), request.getSeason(), request.getWeek(),
                request.getSlot())) {
            request.setStatus(FriendlyStatus.EXPIRED);
            request.setDeclineReason("The slot was taken before this was answered");
            return requests.save(request);
        }

        request.setStatus(FriendlyStatus.ACCEPTED);
        MatchFixture fixture = createFixture(request);
        request.setAcceptedFixtureId(fixtures.save(fixture).getId());
        return requests.save(request);
    }

    /** The club that asked withdraws. */
    @Transactional
    public FriendlyRequest cancel(Long requestId, Long requesterId) {
        FriendlyRequest request = requests.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("No friendly request " + requestId));
        if (!Objects.equals(request.getRequesterTeamId(), requesterId)) {
            throw new IllegalArgumentException("Only the club that asked can withdraw request " + requestId);
        }
        if (!request.isAnswered()) {
            request.setStatus(FriendlyStatus.CANCELLED);
            return requests.save(request);
        }
        return request;
    }

    /** Turns unanswered requests from a week that has gone into "nobody answered". */
    @Transactional
    public int expireStaleRequests() {
        GameClock clock = currentClock();
        if (clock == null || clock.getCurrentSeason() == null || clock.getCurrentWeek() == null) {
            return 0;
        }
        List<FriendlyRequest> stale = requests.findStalePending(
                clock.getCurrentSeason(), clock.getCurrentWeek());
        for (FriendlyRequest r : stale) {
            r.setStatus(FriendlyStatus.EXPIRED);
            r.setDeclineReason("The week passed with no answer");
        }
        if (!stale.isEmpty()) requests.saveAll(stale);
        return stale.size();
    }

    // ------------------------------------------------------------ the cost

    /**
     * How many friendlies a club has agreed to play in a week.
     *
     * <p>Counted from the agreed requests rather than a stored tally, so it cannot disagree with
     * the fixtures.
     */
    @Transactional(readOnly = true)
    public int friendliesAgreed(Long teamId, Integer season, Integer week) {
        return requests.findAcceptedForTeamAndWeek(teamId, season, week).size();
    }

    /**
     * How many training sessions a club loses to friendlies in a week.
     *
     * <p>One friendly costs one session. This is the reason to decline: a club that plays both
     * friendly slots in the break trains once less than one that plays none.
     */
    @Transactional(readOnly = true)
    public int trainingSessionsLostToFriendlies(Long teamId, Integer season, Integer week) {
        return friendliesAgreed(teamId, season, week) * TRAINING_SESSIONS_PER_FRIENDLY;
    }

    /** The sessions a club actually gets, after friendlies. */
    @Transactional(readOnly = true)
    public int trainingSessionsAvailable(Long teamId, Integer season, Integer week) {
        int lost = trainingSessionsLostToFriendlies(teamId, season, week);
        return Math.max(0, BASE_TRAINING_SESSIONS_PER_WEEK - lost);
    }

    // ------------------------------------------------------------- queries

    /** Requests addressed to a club that are still waiting for an answer. */
    @Transactional(readOnly = true)
    public List<FriendlyRequest> incoming(Long clubId, Integer season, Integer week) {
        return requests.findByOpponentTeamIdAndSeasonAndWeek(clubId, season, week).stream()
                .filter(r -> r.getStatus() == FriendlyStatus.PENDING)
                .toList();
    }

    /** Requests a club has made, settled or not. */
    @Transactional(readOnly = true)
    public List<FriendlyRequest> outgoing(Long clubId, Integer season, Integer week) {
        return requests.findByRequesterTeamIdAndSeasonAndWeek(clubId, season, week);
    }

    /**
     * Whether a club is free in a week and slot: no league match, no playoff, no agreed friendly.
     */
    @Transactional(readOnly = true)
    public boolean isBusy(Long teamId, Integer season, Integer week, Integer slot) {
        if (season == null || week == null) return false;
        for (MatchFixture f : fixtures.findBySeasonYearAndWeekNumber(season, week)) {
            if (involves(f, teamId)) return true;
        }
        return !requests.findAcceptedForTeamAndWeek(teamId, season, week).stream()
                .filter(r -> Objects.equals(r.getSlot(), slot))
                .toList()
                .isEmpty();
    }

    /** Whether a club is in that week's playoff, which costs it the Thursday slot. */
    @Transactional(readOnly = true)
    public boolean isInPlayoff(Long teamId, Integer season, Integer week) {
        if (season == null || week == null) return false;
        for (MatchFixture f : fixtures.findBySeasonYearAndWeekNumber(season, week)) {
            if (involves(f, teamId)) return true;
        }
        return false;
    }

    /** Whether a fixture has this club in it. The team is an object, so it is read off the entity. */
    private static boolean involves(MatchFixture f, Long teamId) {
        return (f.getHomeTeam() != null && Objects.equals(f.getHomeTeam().getId(), teamId))
                || (f.getAwayTeam() != null && Objects.equals(f.getAwayTeam().getId(), teamId));
    }

    private boolean hasPendingOrAccepted(Long teamId, Integer season, Integer week, int slot) {
        for (FriendlyRequest r : requests.findBySeasonAndWeek(season, week)) {
            if (!Objects.equals(r.getSlot(), slot)) continue;
            boolean mine = Objects.equals(r.getRequesterTeamId(), teamId)
                    || Objects.equals(r.getOpponentTeamId(), teamId);
            boolean live = r.getStatus() == FriendlyStatus.PENDING
                    || r.getStatus() == FriendlyStatus.ACCEPTED;
            if (mine && live) return true;
        }
        return false;
    }

    private MatchFixture createFixture(FriendlyRequest request) {
        Team home = teams.findById(request.getRequesterTeamId()).orElseThrow();
        Team away = teams.findById(request.getOpponentTeamId()).orElseThrow();
        MatchFixture fixture = new MatchFixture();
        fixture.setHomeTeam(home);
        fixture.setAwayTeam(away);
        fixture.setSeasonYear(request.getSeason());
        // The round number is derived from the week and slot so two friendlies in the same week
        // never collide with each other or with a league round.
        fixture.setRoundNumber(SeasonCalendar.friendlyRoundNumber(request.getWeek(), request.getSlot()));
        fixture.setWeekNumber(request.getWeek());
        fixture.setMatchDate(LocalDateTime.now().plusWeeks(request.getWeek()));
        fixture.setPlayed(false);
        return fixture;
    }

    // ------------------------------------------------------------------ the AI

    /**
     * How likely a club is to accept a friendly, given what it would cost it.
     *
     * <p>Not one number for everyone. A club in the playoff has one slot left and would rather keep
     * the training session; a club with nothing else to do, or one carrying an injury backlog, is
     * more willing. The point of the mechanic is that playing and not playing are both defensible,
     * so the AI has to disagree with itself sometimes or the owner never sees the choice.
     */
    private static final double BASE_ACCEPT_CHANCE = 0.55;

    /** Extra willingness when the club has an open slot and nothing else that week. */
    private static final double IDLE_CLUB_BONUS = 0.25;

    /** A club in the playoff guards its remaining training session. */
    private static final double PLAYOFF_CAUTION = 0.30;

    /** A club with a squad in good health gets less out of a friendly and says no more. */
    private static final double INJURED_CLUB_BONUS = 0.20;

    private final java.util.Random random = new java.util.Random();

    /**
     * Asks for and answers friendlies on behalf of every club that is not the player's.
     *
     * <p>Run once a week. Each AI club with an open slot looks for an opponent that is also free and
     * asks; the club asked then decides, with the same weighting above. Nothing is arranged behind
     * closed doors — every friendly that gets played went through a request and an answer, so a
     * manager looking at the schedule sees the same negotiation anyone else would.
     */
    @Transactional
    public int runAiFriendlyWeek(Integer season, Integer week, Long humanTeamId) {
        if (season == null || week == null) return 0;
        int friendlySlots = SeasonCalendar.friendlySlots(week);
        if (friendlySlots == 0) return 0;

        List<Team> clubs = teams.findAll();
        int arranged = 0;
        for (int slot = 1; slot <= SeasonCalendar.SLOTS_PER_WEEK && arranged < friendlySlots * 4; slot++) {
            if (!SeasonCalendar.slot(week, slot).friendlyCapable()) continue;
            arranged += pairUpAiClubs(season, week, slot, clubs, humanTeamId);
        }
        return arranged;
    }

    /** One pass of asking and answering for a single slot. */
    private int pairUpAiClubs(Integer season, Integer week, int slot, List<Team> clubs, Long humanTeamId) {
        List<Long> free = new ArrayList<>();
        for (Team club : clubs) {
            // The human's club is left alone: whether to ask is the manager's decision, not ours.
            if (humanTeamId != null && Objects.equals(club.getId(), humanTeamId)) continue;
            if (!isBusy(club.getId(), season, week, slot)) free.add(club.getId());
        }
        java.util.Collections.shuffle(free, random);

        int arranged = 0;
        for (Long requesterId : free) {
            if (isBusy(requesterId, season, week, slot)) continue;
            for (Long opponentId : free) {
                if (Objects.equals(requesterId, opponentId)) continue;
                if (isBusy(opponentId, season, week, slot)) continue;
                Optional<FriendlyRequest> request = requestFriendly(requesterId, opponentId, week, slot);
                if (request.isEmpty()) continue;
                FriendlyRequest saved = request.get();
                if (acceptChance(saved, clubs)) {
                    respond(saved.getId(), opponentId, true, null);
                    arranged++;
                } else {
                    respond(saved.getId(), opponentId, false, "We would rather train this week");
                }
                break;      // one request per club per slot, as the rules require
            }
        }
        return arranged;
    }

    /** The acceptance weighting described on the constants above. */
    private boolean acceptChance(FriendlyRequest request, List<Team> clubs) {
        double chance = BASE_ACCEPT_CHANCE;

        boolean inPlayoff = isInPlayoff(request.getOpponentTeamId(), request.getSeason(), request.getWeek());
        if (inPlayoff) {
            chance -= PLAYOFF_CAUTION;
        } else {
            // An open slot in a week with no fixtures is a real opportunity to train instead.
            boolean idle = !hasFixtureThatWeek(request.getOpponentTeamId(), request.getSeason(),
                    request.getWeek());
            if (idle) chance += IDLE_CLUB_BONUS;
        }
        if (hasInjuries(request.getOpponentTeamId())) chance += INJURED_CLUB_BONUS;

        if (chance <= 0) return false;
        if (chance >= 1) return true;
        return random.nextDouble() < chance;
    }

    /** Whether the club has a real fixture that week, as opposed to an empty slot. */
    private boolean hasFixtureThatWeek(Long teamId, Integer season, Integer week) {
        for (MatchFixture f : fixtures.findBySeasonYearAndWeekNumber(season, week)) {
            if (involves(f, teamId)) return true;
        }
        return false;
    }

    /** A club short of players is more willing to get minutes into them. */
    private boolean hasInjuries(Long teamId) {
        return players.countByTeamIdAndInjuredTrue(teamId) > 0;
    }

    /** Every open slot a club has this week, for the UI to offer. */
    @Transactional(readOnly = true)
    public List<Integer> openSlots(Long teamId, Integer season, Integer week) {
        List<Integer> open = new ArrayList<>();
        boolean inPlayoff = isInPlayoff(teamId, season, week);
        for (SeasonCalendar.WeekSlot slot : SeasonCalendar.slots(week)) {
            if (!slot.friendlyCapable()) continue;
            if (slot.kind() == SeasonCalendar.SlotKind.FRIENDLY_IF_NOT_IN_PLAYOFF && inPlayoff) continue;
            if (!isBusy(teamId, season, week, slot.slot())) open.add(slot.slot());
        }
        return open;
    }
}
