package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.CompetitionTeamType;
import org.example.footballmanager.newLogic.model.FriendlyRequest;
import org.example.footballmanager.newLogic.model.FriendlyRequest.FriendlyStatus;
import org.example.footballmanager.newLogic.model.MatchFixture;
import org.example.footballmanager.newLogic.model.NationalFriendlySlots;
import org.example.footballmanager.newLogic.model.SeasonCalendar;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.FriendlyRequestRepository;
import org.example.footballmanager.newLogic.repository.GameClockRepository;
import org.example.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A national side's friendly: one side asks, the other may refuse, both are national (owner,
 * 2026-10-06).
 *
 * <h2>Why this is a separate service rather than a mode of {@link FriendlyRequestService}</h2>
 *
 * <p>That service is 596 lines and every one of its rules is about clubs: two league slots a week, a
 * playoff that costs a club its Thursday, a training session to spend, and an AI pass that pairs bot
 * clubs with each other. A national side has no league, no playoff and no training budget, plays on a
 * different day, and is a human's or a bot's to answer for.
 *
 * <p>Adding a "is this a national side?" branch to each of those rules would put the tournament's
 * calendar inside the league's, and the first rule that forgot the branch would be a national side playing
 * on a day it cannot. Two lanes, one shared table.
 *
 * <h2>What it does not do</h2>
 *
 * <p>It does not pair bots, and there is nothing to pair: AI clubs do not play friendlies at all
 * (owner decision, 2026-10-06), so no national side answers a request on a bot's behalf either.
 */
@Service
public class NationalFriendlyRequestService {

    private static final Logger log = LoggerFactory.getLogger(NationalFriendlyRequestService.class);

    private final FriendlyRequestRepository requests;
    private final MatchFixtureRepository fixtures;
    private final TeamRepository teams;
    private final GameClockRepository clocks;

    public NationalFriendlyRequestService(FriendlyRequestRepository requests,
                                          MatchFixtureRepository fixtures,
                                          TeamRepository teams,
                                          GameClockRepository clocks) {
        this.requests = requests;
        this.fixtures = fixtures;
        this.teams = teams;
        this.clocks = clocks;
    }

    /**
     * Asks another national side for a warm-up.
     *
     * <p>Empty rather than throwing for every refusal, so the caller answers with a reason: the only week
     * and day available is week 6 day 1, both sides have to be national sides, neither may already have a
     * fixture in it, and one side cannot stack two asks on the same day.
     */
    @Transactional
    public Optional<FriendlyRequest> requestFriendly(Long requesterId, Long opponentId,
                                                     int season, int week) {
        if (!NationalFriendlySlots.availableIn(week)) {
            return Optional.empty();
        }
        Team requester = nationalSide(requesterId);
        Team opponent = nationalSide(opponentId);
        if (requester == null || opponent == null) {
            return Optional.empty();
        }
        if (busy(requesterId, season, week) || busy(opponentId, season, week)) {
            return Optional.empty();
        }
        if (hasPendingOrAccepted(requesterId, season, week) || hasPendingOrAccepted(opponentId, season, week)) {
            return Optional.empty();
        }

        FriendlyRequest request = new FriendlyRequest();
        request.setRequesterTeamId(requesterId);
        request.setOpponentTeamId(opponentId);
        request.setSeason(season);
        request.setWeek(week);
        request.setSlot(NationalFriendlySlots.SLOT);
        request.setStatus(FriendlyStatus.PENDING);
        return Optional.of(requests.save(request));
    }

    /** Accepts or refuses a request addressed to this side. */
    @Transactional
    public FriendlyRequest respond(Long requestId, Long teamId, boolean accept) {
        FriendlyRequest request = requests.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("No such friendly request: " + requestId));
        if (!request.getOpponentTeamId().equals(teamId)) {
            throw new IllegalArgumentException("That request was not addressed to this team.");
        }
        if (request.getStatus() != FriendlyStatus.PENDING) {
            throw new IllegalStateException("That request has already been answered.");
        }
        if (!accept) {
            request.setStatus(FriendlyStatus.DECLINED);
            request.setDeclineReason("Declined");
            return requests.save(request);
        }
        // Re-checked at the moment of acceptance: the week may have been ticked on, and a tie played into
        // an occupied day is worse than a refused request.
        if (busy(teamId, request.getSeason(), request.getWeek())) {
            request.setStatus(FriendlyStatus.DECLINED);
            request.setDeclineReason("The day was taken before this was answered");
            return requests.save(request);
        }
        MatchFixture fixture = createFixture(request);
        fixtures.save(fixture);
        request.setStatus(FriendlyStatus.ACCEPTED);
        request.setAcceptedFixtureId(fixture.getId());
        return requests.save(request);
    }

    /** Withdraws a request this side made. */
    @Transactional
    public FriendlyRequest cancel(Long requestId, Long requesterId) {
        FriendlyRequest request = requests.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("No such friendly request: " + requestId));
        if (!request.getRequesterTeamId().equals(requesterId)) {
            throw new IllegalArgumentException("That request was not made by this team.");
        }
        if (request.getStatus() != FriendlyStatus.PENDING) {
            throw new IllegalStateException("That request has already been answered.");
        }
        request.setStatus(FriendlyStatus.CANCELLED);
        return requests.save(request);
    }

    /** Requests addressed to this side and still waiting. */
    @Transactional(readOnly = true)
    public List<FriendlyRequest> incoming(Long teamId, int season, int week) {
        return requests.findByOpponentTeamIdAndSeasonAndWeek(teamId, season, week).stream()
                .filter(r -> r.getStatus() == FriendlyStatus.PENDING)
                .toList();
    }

    /** Requests this side has made. */
    @Transactional(readOnly = true)
    public List<FriendlyRequest> outgoing(Long teamId, int season, int week) {
        return requests.findByRequesterTeamIdAndSeasonAndWeek(teamId, season, week);
    }

    /** Whether this side already has something on the one day a national friendly can use. */
    @Transactional(readOnly = true)
    public boolean busy(Long teamId, int season, int week) {
        return fixtures.findBySeasonYearAndWeekNumber(season, week).stream()
                .anyMatch(f -> f.getHomeTeam() != null && f.getHomeTeam().getId().equals(teamId)
                        || f.getAwayTeam() != null && f.getAwayTeam().getId().equals(teamId));
    }

    /** One ask per side per week, so two requests cannot be stacked on the same day. */
    private boolean hasPendingOrAccepted(Long teamId, int season, int week) {
        for (FriendlyRequest request : requests.findByOpponentTeamIdAndSeasonAndWeek(teamId, season, week)) {
            if (isLive(request)) {
                return true;
            }
        }
        for (FriendlyRequest request : requests.findByRequesterTeamIdAndSeasonAndWeek(teamId, season, week)) {
            if (isLive(request)) {
                return true;
            }
        }
        return false;
    }

    private boolean isLive(FriendlyRequest request) {
        return request.getStatus() == FriendlyStatus.PENDING
                || request.getStatus() == FriendlyStatus.ACCEPTED;
    }

    /** A side or null, and null is the answer for a club. */
    private Team nationalSide(Long teamId) {
        Team team = teams.findById(teamId).orElse(null);
        return team != null && team.getType() == CompetitionTeamType.NATIONAL_TEAM ? team : null;
    }

    /**
     * The fixture an accepted request creates.
     *
     * <p>No competition, and that is deliberate: a friendly decides nothing, so it belongs to none. It is
     * labelled {@code FRIENDLY} so the engine gives it the behaviour of one, and its round number is
     * derived from the week and slot so two warm-ups can never collide with each other or with a
     * qualifying matchday.
     *
     * <p><b>It is not attached to a competition, so the day-1 international matchday job will not find
     * it</b> — the known consequence of a fixture with no competition, recorded on the board for
     * friendly fixtures generally. Until that is solved, a national warm-up is written and shown and
     * waits to be played; inventing a competition for it would put it in the national ranking.
     */
    private MatchFixture createFixture(FriendlyRequest request) {
        Team home = teams.findById(request.getRequesterTeamId()).orElseThrow();
        Team away = teams.findById(request.getOpponentTeamId()).orElseThrow();
        MatchFixture fixture = new MatchFixture();
        fixture.setHomeTeam(home);
        fixture.setAwayTeam(away);
        fixture.setMatchType(org.example.footballmanager.newLogic.model.MatchType.FRIENDLY);
        fixture.setSeasonYear(request.getSeason());
        fixture.setRoundNumber(SeasonCalendar.friendlyRoundNumber(request.getWeek(), request.getSlot()));
        fixture.setWeekNumber(request.getWeek());
        fixture.setDayNumber(NationalFriendlySlots.DAY);
        fixture.setMatchDate(NationalFriendlySlots.matchDate(seasonStart()));
        fixture.setPlayed(false);
        log.info("National warm-up arranged: {} v {} in week {} day {}.",
                home.getName(), away.getName(), request.getWeek(), NationalFriendlySlots.DAY);
        return fixture;
    }

    private LocalDateTime seasonStart() {
        return clocks.findById(1L)
                .map(org.example.footballmanager.newLogic.model.GameClock::getCurrentDate)
                .orElse(LocalDateTime.of(2026, 7, 1, 12, 0));
    }
}