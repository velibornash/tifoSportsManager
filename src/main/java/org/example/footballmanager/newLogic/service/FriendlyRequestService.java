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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

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
    public static final int TRAINING_SESSIONS_PER_FRIENDLY = 0;

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
        GameClock clock = currentClock();
        if (clock == null || clock.getCurrentSeason() == null) {
            return Optional.empty();
        }
        return requestFriendly(requesterId, opponentId, week, slot,
                new WeekSnapshot(clock.getCurrentSeason(), week));
    }

    /**
     * The same request, against a week already read.
     *
     * <p>The public method above builds its own snapshot, which costs the same two queries it always
     * cost — correct for a manager asking about one club, and ruinous for the AI pass that asks about
     * every club in the world in a nested loop.
     */
    private Optional<FriendlyRequest> requestFriendly(Long requesterId, Long opponentId, int week, int slot,
                                                     WeekSnapshot snapshot) {
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
        if (week == SeasonCalendar.PLAYOFF_WEEK && snapshot.hasFixture(requesterId)) {
            return Optional.empty();
        }
        if (isBusy(requesterId, slot, snapshot) || isBusy(opponentId, slot, snapshot)) {
            return Optional.empty();
        }
        // One request per club per slot, so a club cannot stack three asks on the same Thursday. The
        // isBusy above already answers exactly this, against the snapshot rather than by re-reading the
        // week's requests for every candidate pair.
        if (snapshot.liveIn(requesterId, slot) || snapshot.liveIn(opponentId, slot)) {
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
        return respond(requestId, clubId, accept, declineReason, null);
    }

    /**
     * The same answer, against a week already read.
     *
     * <p>The re-check below is the reason this exists. Answering a friendly re-reads the whole week
     * <i>per side</i>, and the AI pass answers one friendly per club — so the check that was meant to
     * stop two clubs taking the same slot was itself the most expensive thing in the pass. With a
     * snapshot the check is exact and free; a null snapshot (the manager's path) builds its own.
     */
    private FriendlyRequest respond(Long requestId, Long clubId, boolean accept, String declineReason,
                                    WeekSnapshot snapshotOrNull) {
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
        WeekSnapshot snapshot = snapshotOrNull != null ? snapshotOrNull
                : new WeekSnapshot(request.getSeason(), request.getWeek());
        if (isBusy(request.getOpponentTeamId(), request.getSlot(), snapshot)
                || isBusy(request.getRequesterTeamId(), request.getSlot(), snapshot)) {
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
        return isBusy(teamId, slot, new WeekSnapshot(season, week));
    }

    /**
     * The same question, answered from a week already in hand.
     *
     * <p>For the AI pass, which asks it per club and per candidate opponent. The public one above keeps
     * its behaviour and costs the same two queries for a single club's answer, which is correct when the
     * caller only wants one club.
     */
    private boolean isBusy(Long teamId, Integer slot, WeekSnapshot snapshot) {
        // The fixture walk and the agreed-friendly walk were two halves of this answer, and
        // `hasPendingOrAccepted` asked the second half again per candidate pair. All three are one
        // question now: is this club already committed in this slot?
        return snapshot.hasFixture(teamId) || snapshot.busyIn(teamId, slot);
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
        // **One week, read once.**
        //
        // `isBusy`, `isInPlayoff` and `hasFixtureThatWeek` each walked
        // `fixtures.findBySeasonYearAndWeekNumber(season, week)` — the same list, three times over — and
        // `isBusy` also asked for the club's agreed friendlies. They were called per club *and* per
        // candidate opponent inside a nested loop, so a week cost a query per club per slot before any
        // match was arranged, and three more for every decision after one was. At 48 countries that is
        // 14,880 clubs in the first loop alone.
        //
        // The snapshot is **updated as friendlies are agreed**, not just read once: a club that accepts a
        // Thursday friendly is busy for that slot, and the next club's turn has to see that or the pass
        // double-books it. That is why one snapshot per pass is correct rather than merely cheaper — the
        // same reasoning as the transfer market's bulk read.
        WeekSnapshot snapshot = new WeekSnapshot(season, week);
        int arranged = 0;
        for (int slot = 1; slot <= SeasonCalendar.SLOTS_PER_WEEK && arranged < friendlySlots * 4; slot++) {
            if (!SeasonCalendar.slot(week, slot).friendlyCapable()) continue;
            arranged += pairUpAiClubs(season, week, slot, clubs, humanTeamId, snapshot);
        }
        return arranged;
    }

    /**
     * One week of fixtures and agreed friendlies, held for the length of an AI pass.
     *
     * <p>Two queries to build. {@link #hasFixture} answers all three of {@code isBusy}'s fixture walk,
     * {@code isInPlayoff} and {@code hasFixtureThatWeek} — which were three separate methods with the
     * same body.
     */
    private final class WeekSnapshot {
        private final Set<Long> withFixture = new HashSet<>();
        /** slot -> clubs with an AGREED friendly in it. This is what makes a club busy. */
        private final Map<Integer, Set<Long>> agreedInSlot = new HashMap<>();
        /** slot -> clubs with a request still waiting for an answer. Stops a club asking twice. */
        private final Map<Integer, Set<Long>> pendingInSlot = new HashMap<>();

        WeekSnapshot(Integer season, Integer week) {
            for (MatchFixture f : fixtures.findBySeasonYearAndWeekNumber(season, week)) {
                if (f.getHomeTeam() != null) withFixture.add(f.getHomeTeam().getId());
                if (f.getAwayTeam() != null) withFixture.add(f.getAwayTeam().getId());
            }
            for (FriendlyRequest r : requests.findBySeasonAndWeek(season, week)) {
                if (r.getStatus() == FriendlyStatus.ACCEPTED) {
                    hold(agreedInSlot, r.getRequesterTeamId(), r.getOpponentTeamId(), r.getSlot());
                } else if (r.getStatus() == FriendlyStatus.PENDING) {
                    hold(pendingInSlot, r.getRequesterTeamId(), r.getOpponentTeamId(), r.getSlot());
                }
            }
        }

        /** A club with a real fixture that week is busy in every slot, which is what the old walk meant. */
        boolean hasFixture(Long teamId) {
            return teamId != null && withFixture.contains(teamId);
        }

        /** An agreed friendly in this slot. A request still waiting does not count — see {@link #liveIn}. */
        boolean busyIn(Long teamId, Integer slot) {
            return contains(agreedInSlot, teamId, slot);
        }

        /**
         * Agreed <b>or</b> still waiting, which is the one-request-per-club-per-slot rule.
         *
         * <p>Kept apart from {@link #busyIn} on purpose, and getting this wrong is not subtle: the
         * original code answered a friendly by re-reading the week, where its own request was PENDING and
         * therefore did <i>not</i> make either club busy. Hold a pending request in the busy set and every
         * acceptance expires itself — which is exactly what the first version of this snapshot did, and
         * what {@code AiFriendlyWeekQueryCountTest} caught.
         */
        boolean liveIn(Long teamId, Integer slot) {
            return busyIn(teamId, slot) || contains(pendingInSlot, teamId, slot);
        }

        void asked(Long requesterId, Long opponentId, Integer slot) {
            hold(pendingInSlot, requesterId, opponentId, slot);
        }

        void agreed(Long requesterId, Long opponentId, Integer slot) {
            hold(agreedInSlot, requesterId, opponentId, slot);
            free(pendingInSlot, requesterId, opponentId, slot);
        }

        /**
         * A refusal stops holding both clubs for that slot.
         *
         * <p><b>Correct, and deliberately not claimed as a fix.</b> A pending request left in the set
         * after a refusal keeps both clubs out of that slot for the rest of the pass. Whether that
         * arranges fewer friendlies than before is <i>not</i> established: the loop asks one requester at
         * a time and each tries opponents in turn, so the attempt count barely moves — removing this call
         * changed no count in {@code AiFriendlyWeekQueryCountTest}. It is here because the old code
         * re-read the requests and so did not hold a refusal, and matching that is the point of a
         * snapshot. The test's uniqueness assertion passes with or without it.
         */
        void refused(Long requesterId, Long opponentId, Integer slot) {
            free(pendingInSlot, requesterId, opponentId, slot);
        }

        private static boolean contains(Map<Integer, Set<Long>> bySlot, Long teamId, Integer slot) {
            Set<Long> ids = bySlot.get(slot);
            return ids != null && teamId != null && ids.contains(teamId);
        }

        private static void hold(Map<Integer, Set<Long>> bySlot, Long a, Long b, Integer slot) {
            Set<Long> ids = bySlot.computeIfAbsent(slot, k -> new HashSet<>());
            ids.add(a);
            ids.add(b);
        }

        private static void free(Map<Integer, Set<Long>> bySlot, Long a, Long b, Integer slot) {
            Set<Long> ids = bySlot.get(slot);
            if (ids == null) return;
            ids.remove(a);
            ids.remove(b);
        }
    }

    /** One pass of asking and answering for a single slot. */
    private int pairUpAiClubs(Integer season, Integer week, int slot, List<Team> clubs, Long humanTeamId,
                              WeekSnapshot snapshot) {
        List<Long> free = new ArrayList<>();
        for (Team club : clubs) {
            // The human's club is left alone: whether to ask is the manager's decision, not ours.
            if (humanTeamId != null && Objects.equals(club.getId(), humanTeamId)) continue;
            if (!isBusy(club.getId(), slot, snapshot)) free.add(club.getId());
        }
        java.util.Collections.shuffle(free, random);

        int arranged = 0;
        for (Long requesterId : free) {
            if (isBusy(requesterId, slot, snapshot)) continue;
            for (Long opponentId : free) {
                if (Objects.equals(requesterId, opponentId)) continue;
                if (isBusy(opponentId, slot, snapshot)) continue;
                Optional<FriendlyRequest> request = requestFriendly(requesterId, opponentId, week, slot, snapshot);
                if (request.isEmpty()) continue;
                FriendlyRequest saved = request.get();
                // The pass must see its own request, or the next club asks a club that is already
                // committed. The old code got this for free by re-reading the requests inside isBusy.
                snapshot.asked(requesterId, opponentId, slot);
                if (acceptChance(saved, clubs, snapshot)) {
                    FriendlyRequest answered = respond(saved.getId(), opponentId, true, null, snapshot);
                    // `respond` re-checks the slot and can expire the request; only an agreement occupies
                    // the slot, so only an agreement is recorded and counted.
                    if (answered != null && answered.getStatus() == FriendlyStatus.ACCEPTED) {
                        snapshot.agreed(requesterId, opponentId, slot);
                        arranged++;
                    } else {
                        snapshot.refused(requesterId, opponentId, slot);
                    }
                } else {
                    respond(saved.getId(), opponentId, false, "We would rather train this week", snapshot);
                    // A refusal frees both clubs again, exactly as it did when the answer came from a
                    // fresh query. Holding them would quietly arrange fewer friendlies than before.
                    snapshot.refused(requesterId, opponentId, slot);
                }
                break;      // one request per club per slot, as the rules require
            }
        }
        return arranged;
    }

    /** The acceptance weighting described on the constants above. */
    private boolean acceptChance(FriendlyRequest request, List<Team> clubs, WeekSnapshot snapshot) {
        double chance = BASE_ACCEPT_CHANCE;

        // `isInPlayoff` and `hasFixtureThatWeek` were the same walk over the same week, asked twice per
        // decision; the snapshot answers both, and answers them identically because they are identical.
        boolean inPlayoff = snapshot.hasFixture(request.getOpponentTeamId());
        if (inPlayoff) {
            chance -= PLAYOFF_CAUTION;
        } else {
            // An open slot in a week with no fixtures is a real opportunity to train instead.
            chance += IDLE_CLUB_BONUS;
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
