package org.tifo.footballmanager.newLogic.service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import org.tifo.commonmanager.model.User;
import org.tifo.footballmanager.newLogic.exception.ApiException;
import org.tifo.footballmanager.newLogic.model.MatchFixture;
import org.tifo.footballmanager.newLogic.model.MatchType;
import org.tifo.footballmanager.newLogic.model.Team;
import org.tifo.footballmanager.newLogic.sim.SimMatchRunner;
import org.tifo.footballmanager.newLogic.sim.SimMatchService;
import org.tifo.footballmanager.newLogic.sim.TrainingOpponentFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.tifo.footballmanager.newLogic.repository.TeamRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * A manager's training match: a full ninety minutes against an opponent of his own choosing, which
 * changes nothing at all.
 *
 * <p><b>The owner's spec (2026-10-27).</b> One a day — unlimited for his own account so he can test the
 * engine as often as he likes. He picks the opponent's strength from 8 to 20 and every one of the
 * opponent's men arrives with every football skill at that number. The match is watched like any other
 * and its full statistics can be read afterwards. It is a <em>regular</em> ninety minutes, and
 * <b>nothing is written to the database</b>: no fixture, no match, no player stats, no condition, no
 * card, no table. The page has to say so, because a manager who thought a training result counted for
 * something would be reading a number that means nothing.
 *
 * <p><b>What is remembered, and only that.</b> The daily limit lives in {@link TrainingMatchLimitCache}
 * — an in-memory map, not a column — and the owner's own words for it were "samo se pamti limit i
 * stoji u cache". The score, the statistics and the recording are handed to the page that asked for
 * them and are deliberately not kept: reload, and they are gone.
 *
 * <p><b>The one thing that is written is a recording.</b> "Watch your match" opens the same proposal
 * viewer every other match opens, and that viewer reads a replay from
 * {@link org.tifo.footballmanager.newLogic.sim.SimReplayStore} — a file under {@code app.replay-dir},
 * bounded and evicted by age, and never the database. Without it there would be nothing to watch.
 */
@Service
public class TrainingMatchService {

    /** Where every match in this game is watched, including this one. */
    public static final String VIEWER_PATH = "/demo/service/ui/proposal/index.html";

    /** What the page tells the manager, before and after the match. */
    public static final String NOTICE =
            "This match is not saved to the database. It exists only to test tactics.";

    private final ClubOwnershipLinker ownership;
    private final GameClockService gameClock;
    private final SimMatchService simMatchService;
    private final TrainingMatchLimitCache limits;
    private final TeamRepository teams;

    public TrainingMatchService(ClubOwnershipLinker ownership, GameClockService gameClock,
                                SimMatchService simMatchService, TrainingMatchLimitCache limits,
                                TeamRepository teams) {
        this.ownership = ownership;
        this.gameClock = gameClock;
        this.simMatchService = simMatchService;
        this.limits = limits;
        this.teams = teams;
    }

    /**
     * What the page needs before anything has been played: the limit, the strength band, the notice.
     *
     * <p>No result is here, on purpose — see the class javadoc.
     */
    @Transactional
    public Map<String, Object> status(User user) {
        String account = accountOf(user);
        int day = gameDay();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", "ok");
        payload.put("canPlay", limits.canPlay(account, day));
        payload.put("unlimited", TrainingMatchLimitCache.isUnlimited(account));
        payload.put("gameDay", day);
        payload.put("minStrength", TrainingOpponentFactory.MIN_STRENGTH);
        payload.put("maxStrength", TrainingOpponentFactory.MAX_STRENGTH);
        payload.put("notice", NOTICE);
        return payload;
    }

    /**
     * Plays the ninety minutes, and writes nothing but the recording.
     *
     * <p>The engine is entered the same way every other match enters it — through
     * {@link SimMatchService#simulate} — because a second path into the engine is exactly what the
     * fixture-producer guard exists to notice. The only difference is the away side's eleven, which
     * comes from {@link TrainingOpponentFactory} instead of from a club in the world.
     *
     * @param strength the opponent's standard, 8..20; anything outside the band is brought inside it
     */
    @Transactional
    public Map<String, Object> play(User user, int strength) {
        String account = accountOf(user);
        Long clubId = ownership.clubIdOf(user);
        Team club = (clubId == null) ? null : teams.findById(clubId).orElse(null);
        if (club == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "NO_TEAM",
                    "Your account does not manage a club.");
        }

        int day = gameDay();
        if (!limits.canPlay(account, day)) {
            throw new ApiException(HttpStatus.CONFLICT, "TRAINING_LIMIT",
                    "You have already played your training match today.");
        }

        int skill = TrainingOpponentFactory.clampStrength(strength);

        Team opponent = new Team();
        opponent.setName("Training XI (" + skill + ")");
        opponent.setHumanControlled(false);

        MatchFixture fixture = new MatchFixture();
        fixture.setHomeTeam(club);
        fixture.setAwayTeam(opponent);
        fixture.setMatchType(MatchType.PRACTICE);
        fixture.setMatchDate(LocalDateTime.now());
        fixture.setPlayed(false);

        TrainingOpponentFactory.Squad squad = TrainingOpponentFactory.build(skill, null);
        SimMatchService.SimMatchOutcome sim = simMatchService.simulate(
                fixture, true, false, SimMatchRunner.FULL_MATCH_TICKS,
                squad.starters(), squad.bench());

        // Recorded only now: a match that threw must not have spent the day's one match.
        limits.record(account, day);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", "ok");
        payload.put("strength", skill);
        payload.put("unlimited", TrainingMatchLimitCache.isUnlimited(account));
        payload.put("gameDay", day);
        payload.put("homeTeam", club.getName());
        payload.put("awayTeam", opponent.getName());
        payload.put("homeGoals", sim.homeGoals());
        payload.put("awayGoals", sim.awayGoals());
        payload.put("replayId", sim.replayId());
        payload.put("viewerUrl", VIEWER_PATH + "?replayId=" + sim.replayId());
        payload.put("notice", NOTICE);
        payload.put("outcome", sim.outcome());
        return payload;
    }

    /**
     * The game day the limit is counted against.
     *
     * <p>Read from {@link GameClockService#position()} rather than from the clock row's
     * {@code current_season/week/day} columns: those four counters were retired when the clock became
     * an anchor plus an offset, and they are null in every world built since — a limit keyed on them
     * would never reset.
     */
    private int gameDay() {
        var position = gameClock.position();
        return position.season() * 1000 + position.week() * 10 + position.day();
    }

    private String accountOf(User user) {
        return user == null ? null : user.getEmail();
    }
}
