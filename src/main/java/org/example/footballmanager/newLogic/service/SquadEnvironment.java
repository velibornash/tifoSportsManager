package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Position;
import org.example.footballmanager.newLogic.model.Team;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The people side of a squad: mentoring, familiarity and cohesion (Sprint 4.6).
 *
 * <p>Everything here is <b>derived, not stored</b>, with two exceptions that live on the entities
 * where they belong — {@code Player.familiarity} and {@code Team.cohesion}. That is a deliberate
 * choice in a project that has already been bitten five times this sprint by a field that was
 * carefully stored and never read, and once by a table that could drift out of agreement with
 * reality. A mentor/mentee pairing recomputed from the squad cannot be stale, cannot be left
 * pointing at a sold player, and needs no migration when a club is promoted or disbanded.
 *
 * <p>All three effects are small on purpose. They are seasoning, not a second growth system: a
 * mentoring pair is worth about as much as one good week from a good coach, and a cohesive squad is
 * worth a couple of percent. Anything larger would quietly rebalance a calibrated match engine
 * because of a squad-management decision, and that is how a season's tuning gets lost.
 */
public final class SquadEnvironment {

    private SquadEnvironment() {
    }

    /** A junior is a player the club is still developing. */
    public static final int JUNIOR_MAX_AGE = 21;

    /** A mentor must be at least this much older, and old enough to have something to teach. */
    public static final int MENTOR_MIN_AGE_GAP = 6;
    public static final int MENTOR_MIN_AGE = 27;

    /** What a mentoring pair is worth to the junior's growth, and to the senior's morale. */
    public static final double MENTOR_GROWTH_BONUS = 0.08;
    public static final double MENTOR_MORALE_BONUS = 4.0;

    /** A new signing knows nothing; an established player knows everything. */
    public static final int NEW_SIGNING_FAMILIARITY = 30;
    public static final int FULL_FAMILIARITY = 100;

    /** Familiarity gained per full match played, and lost per week of no football. */
    public static final int FAMILIARITY_PER_MATCH = 6;
    public static final int FAMILIARITY_DECAY_WHEN_IDLE = 4;

    /** How much a new signing is held back by not yet knowing the system. */
    public static final double FAMILIARITY_PENALTY = 0.10;

    /** Cohesion movement, and what it is worth. */
    public static final int COHESION_GAIN_STABLE_SQUAD = 3;
    public static final int COHESION_LOSS_PER_ARRIVAL = 8;
    public static final double COHESION_GROWTH_MAX = 0.04;
    public static final double COHESION_MATCH_MAX = 0.03;

    /**
     * Pairs each junior with a senior at the same position, best pair first.
     *
     * <p>One senior mentors at most one junior and one junior has at most one mentor, so this is a
     * real allocation. Without that cap every veteran would rub off on every youngster in the squad
     * and the whole squad would get the bonus — which is a different feature, a worse one, and one
     * that no manager could reason about.
     *
     * <p>Best means highest talent, because the point of a mentor is that he is worth listening to.
     *
     * @return mentor id to junior id, and the reverse
     */
    public static Map<Long, Long> mentorPairs(List<Player> squad) {
        Map<Long, Long> mentorToJunior = new LinkedHashMap<>();
        Map<Long, Long> juniorToMentor = new LinkedHashMap<>();
        if (squad == null || squad.isEmpty()) {
            return mentorToJunior;
        }

        List<Player> juniors = squad.stream()
                .filter(SquadEnvironment::isUsableJunior)
                .sorted(byTalentDescending())
                .toList();
        List<Player> seniors = squad.stream()
                .filter(SquadEnvironment::isUsable)
                .sorted(byTalentDescending())
                .toList();

        for (Player junior : juniors) {
            for (Player senior : seniors) {
                if (mentorToJunior.containsValue(junior.getId())) break;
                if (mentorToJunior.containsKey(senior.getId())) continue;
                if (!canMentor(senior, junior)) continue;
                mentorToJunior.put(senior.getId(), junior.getId());
                juniorToMentor.put(junior.getId(), senior.getId());
                break;
            }
        }
        return mentorToJunior;
    }

    /** The senior mentoring this player, or null. */
    public static Long mentorOf(Map<Long, Long> juniorToMentor, Long playerId) {
        return playerId == null ? null : juniorToMentor.get(playerId);
    }

    /** True if this player is old enough, skilled enough and of the right kind to mentor this one. */
    public static boolean canMentor(Player senior, Player junior) {
        if (!isUsable(senior) || !isUsableJunior(junior)) return false;
        if (senior.getId() == null || junior.getId() == null) return false;
        if (senior.getId().equals(junior.getId())) return false;
        if (senior.getAge() < MENTOR_MIN_AGE) return false;
        if (senior.getAge() - junior.getAge() < MENTOR_MIN_AGE_GAP) return false;
        // A mentor teaches his own trade. A centre-half helping a winger with crossing is a
        // friendship, not a mentorship, and the position check is what keeps the bonus meaningful.
        return senior.getPosition() != null && senior.getPosition() == junior.getPosition();
    }

    private static boolean isUsableJunior(Player p) {
        return isUsable(p) && p.getAge() <= JUNIOR_MAX_AGE;
    }

    private static boolean isUsable(Player p) {
        return p != null && p.getId() != null;
    }

    private static Comparator<Player> byTalentDescending() {
        return Comparator.comparingDouble((Player p) -> p.getTalent()).reversed()
                .thenComparing(p -> p.getId());
    }

    /**
     * How well this player knows the club's way of playing, 0-100.
     *
     * <p>An established player is 1.0 and a new signing starts at {@link #NEW_SIGNING_FAMILIARITY},
     * so he is held back by about a tenth until he has played his way into the system. That is the
     * whole of item 4: a signing is not immediately the player he was, and a club that turns over
     * its squad every summer has a permanently developing team.
     */
    public static double familiarityFactor(Player player) {
        if (player == null) return 1.0;
        double familiarity = familiarityOf(player);
        double shortfall = 1.0 - Math.min(1.0, familiarity / FULL_FAMILIARITY);
        return 1.0 - FAMILIARITY_PENALTY * shortfall;
    }

    /**
     * This player's familiarity, with null read as "fully familiar".
     *
     * <p>One place, so the "null means established" decision is made exactly once instead of in
     * every caller with a slightly different fallback.
     */
    public static double familiarityOf(Player player) {
        if (player == null || player.getFamiliarity() == null) return FULL_FAMILIARITY;
        return player.getFamiliarity();
    }

    /** The familiarity a player gains from a week in which he played {@code minutes}. */
    public static int familiarityAfterWeek(int current, int minutes) {
        // No "0 means new signing" shortcut here: a player at 0 who plays a match must go to 6, not
        // jump to 36. Substituting the default belongs to the caller, which knows whether the value
        // is missing or genuinely zero.
        int value = Math.max(0, current);
        if (minutes >= 90) {
            value += FAMILIARITY_PER_MATCH;
        } else if (minutes > 0) {
            // A substitute's week counts for something, but not for a full match.
            value += Math.max(1, FAMILIARITY_PER_MATCH * minutes / 90);
        } else {
            // No football at all, and familiarity is a use-it-or-lose-it thing.
            value -= FAMILIARITY_DECAY_WHEN_IDLE;
        }
        return Math.max(0, Math.min(FULL_FAMILIARITY, value));
    }

    /** What a cohesive dressing room is worth to training, 0 to {@link #COHESION_GROWTH_MAX}. */
    public static double cohesionGrowthFactor(Team team) {
        if (team == null) return 1.0;
        return 1.0 + COHESION_GROWTH_MAX * (cohesionOf(team) / 100.0);
    }

    /** A club's cohesion, with null read as an average 50 — a genuinely zero stays zero. */
    public static double cohesionOf(Team team) {
        if (team == null || team.getCohesion() == null) return 50.0;
        return team.getCohesion();
    }

    /** What a cohesive dressing room is worth on the pitch, 0 to {@link #COHESION_MATCH_MAX}. */
    public static double cohesionMatchFactor(Team team) {
        if (team == null) return 1.0;
        return 1.0 + COHESION_MATCH_MAX * (cohesionOf(team) / 100.0);
    }

    /**
     * A club's cohesion after a week in which {@code arrivals} players joined it.
     *
     * <p>A squad that is turning over gets no credit for being settled, which is the point: cohesion
     * is about the same faces having played together, and a club that signs three players a summer
     * has not built that just by existing.
     */
    public static int cohesionAfterWeek(int current, int arrivals) {
        // Same rule as familiarity: 0 is a real value and the caller substitutes any default.
        int value = Math.max(0, current);
        if (arrivals > 0) {
            value -= COHESION_LOSS_PER_ARRIVAL * Math.min(5, arrivals);
        } else {
            value += COHESION_GAIN_STABLE_SQUAD;
        }
        return Math.max(0, Math.min(100, value));
    }

    /**
     * Whether this player is still new enough to the system that his presence unsettles a dressing
     * room.
     *
     * <p>Measured from familiarity rather than from an arrivals log, so there is exactly one record of
     * how new a player is and cohesion cannot disagree with the growth maths.
     */
    public static boolean isUnsettled(Player player) {
        return player != null && familiarityOf(player) < SETTLED_FAMILIARITY;
    }

    /** The familiarity at which a player counts as settled in his club's way of playing. */
    public static final int SETTLED_FAMILIARITY = 50;

    /** The juniors in this squad who currently have a mentor. */
    public static List<Player> mentoredJuniors(List<Player> squad) {
        List<Player> out = new ArrayList<>();
        Map<Long, Long> pairs = mentorPairs(squad);
        if (pairs.isEmpty()) return out;
        Map<Long, Player> byId = new LinkedHashMap<>();
        for (Player p : squad) {
            if (p != null && p.getId() != null) byId.put(p.getId(), p);
        }
        for (Long juniorId : pairs.values()) {
            Player junior = byId.get(juniorId);
            if (junior != null) out.add(junior);
        }
        return out;
    }
}
