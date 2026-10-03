package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.ListingObjection;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.model.Transfer;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.example.footballmanager.newLogic.repository.TransferRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Supporter mood, and something in the game that answers to it (P2-5).
 *
 * <p>The competitive analysis put this precisely: the project had built the expensive half of the meta
 * layer — {@code BoardExpectationService} computes a 0-100 trust score from FFP, league standing,
 * unhappy players and squad size — and <b>nothing acts on it</b>. {@code sackingReview} is a boolean
 * with no entity behind it. A number the player can see that nothing responds to is worse than no
 * number, because it invites the expectation of a consequence.
 *
 * <p>This is the cheap half, and the one that costs supporters something to be angry about. The
 * analysis is right that board trust and supporter mood are different things: the board reads the
 * wage bill, the stand reads how the club treats its players and what it has won.
 *
 * <p><b>The loop it closes.</b> A player objects to being listed (P2-3) → supporters notice the club is
 * selling its own people → mood falls → fewer of them come → gate income falls → the wage bill looks
 * worse against income → the board's trust falls. Every step of that already existed; nothing was
 * connected.
 *
 * <p><b>Scale.</b> The weekly pass reads clubs in one query and outstanding objections in one query.
 * It deliberately does <em>not</em> ask each club for its recent results: {@code AttendanceService
 * .formOf} does that with a query per club, which is survivable for one match and would be 14,880
 * round-trips a week. Results reach mood through reputation instead, which is already maintained by
 * {@code ClubRatingService} and already sits on the club row.
 */
@Service
public class SupporterMoodService {

    private static final Logger log = LoggerFactory.getLogger(SupporterMoodService.class);

    /** Where a club starts when nothing is known about it. */
    public static final int NEUTRAL = 60;

    /** Most a mood can move in one week. A crowd does not flip overnight. */
    public static final int WEEKLY_DRIFT = 6;

    /** How far the mood can bend a crowd at the extremes, either side of neutral. */
    private static final double SPAN = 0.38;

    /** How far reputation, on the economy's 0-100 scale, can pull the mood. */
    private static final double REPUTATION_WEIGHT = 0.30;

    /** What one unresolved player objection costs the mood. */
    private static final double OBJECTION_PENALTY = 9.0;

    private final TeamRepository teamRepository;
    private final TransferRepository transferRepository;

    public SupporterMoodService(TeamRepository teamRepository, TransferRepository transferRepository) {
        this.teamRepository = teamRepository;
        this.transferRepository = transferRepository;
    }

    /**
     * Where this club's mood wants to be, before any drift.
     *
     * <p>Two terms and no more. How big and successful the club is, which supporters already respond
     * to, and whether it is currently selling players who are objecting to being sold — which is the
     * thing a reputation figure cannot express.
     */
    public int targetMood(Team club, int unresolvedObjections) {
        if (club == null) return NEUTRAL;
        double reputation = club.getReputation() == null ? 50.0 : club.getReputation();
        double mood = NEUTRAL + (reputation - 50.0) * REPUTATION_WEIGHT;
        mood -= OBJECTION_PENALTY * unresolvedObjections;
        return clamp((int) Math.round(mood));
    }

    /**
     * Moves every club's mood one step toward its target.
     *
     * <p>A drift rather than a jump, because the lag is the point: a club can be winning for a month
     * and still be endured, and supporters who were insulted in August are not appeased in September.
     *
     * @return how many clubs changed
     */
    @Transactional
    public int driftWeekly() {
        return drift(teamRepository.findAllClubsWithDivision());
    }

    /**
     * Moves the given clubs one step toward their targets.
     *
     * <p>Separate from {@link #driftWeekly()} so it can be exercised against a known set of clubs
     * rather than against every club in the world: {@code findAllClubsWithDivision} only returns
     * clubs that have a division, so a fixture without one is silently skipped and a test asserting
     * "the mood moved" would pass against a sweep that had done nothing at all.
     */
    public int drift(List<Team> clubs) {
        if (clubs == null || clubs.isEmpty()) {
            return 0;
        }
        Map<Long, Long> objectionsByClub = unresolvedObjectionsByClub();

        int moved = 0;
        for (Team club : clubs) {
            if (club == null || club.getId() == null) continue;
            int target = targetMood(club, objectionsByClub.getOrDefault(club.getId(), 0L).intValue());
            int current = club.getSupporterMood();
            if (current == target) continue;
            int next = current + clamp(target - current, -WEEKLY_DRIFT, WEEKLY_DRIFT);
            club.setSupporterMood(next);
            moved++;
        }
        if (moved > 0) {
            teamRepository.saveAll(clubs);
            log.info("Supporter mood drifted for {} of {} clubs", moved, clubs.size());
        }
        return moved;
    }

    /**
     * Which clubs currently have a player refusing to be listed, and how many.
     *
     * <p>One query for the whole world. Asking per club would be 14,880 round-trips a week to count
     * rows that are, in a healthy world, nearly always zero.
     */
    private Map<Long, Long> unresolvedObjectionsByClub() {
        List<Transfer> objected = transferRepository.findActiveObjectedListings();
        if (objected == null || objected.isEmpty()) {
            return Map.of();
        }
        return objected.stream()
                .filter(t -> t != null && t.getSellerTeam() != null && t.getSellerTeam().getId() != null)
                .filter(t -> {
                    ListingObjection o = t.getListingObjection();
                    return o != null && o.isBlocking();
                })
                .collect(Collectors.groupingBy(t -> t.getSellerTeam().getId(), Collectors.counting()));
    }

    /**
     * How the mood bends a crowd.
     *
     * <p>An empty stand is worth nothing to anybody, so the penalty is deliberately gentler than the
     * reward: a furious support still comes, it simply stops paying to watch.
     *
     * <p><b>Exactly 1.0 at {@link #NEUTRAL}.</b> Not approximately — exactly. Every club starts at 60,
     * so a formula whose neutral point sat anywhere else would silently change gate income for the
     * entire world the day this shipped, with nothing in the diff to say so. The first version of this
     * returned 1.032 at a mood of 60 and a test caught it.
     *
     * <p>Exposed as a method rather than inlined so {@code AttendanceService} and any test read the
     * same number.
     */
    public static double attendanceEffect(int mood) {
        return 1.0 + ((clamp(mood) - NEUTRAL) / 100.0) * SPAN;
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(100, value));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int clamp(double value) {
        return clamp((int) value);
    }
}