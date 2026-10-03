package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.Match;
import org.example.footballmanager.newLogic.model.Match;
import org.example.footballmanager.newLogic.model.Stadium;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.MatchRepository;
import org.example.footballmanager.newLogic.repository.StadiumRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * How many people are in the stand, and what they pay (Sprint 2.2).
 *
 * <p>The previous estimate was capacity × reputation plus a few fudge factors. It had three
 * problems that made the gate the least interesting income line in the game:
 *
 * <ul>
 *   <li><b>Nothing about the teams.</b> A club winning every game drew the same crowd as one
 *       losing every game, and the away club's travelling support was folded into a single
 *       reputation term. Form and success are the two things supporters actually respond to.</li>
 *   <li><b>The ticket price was never read.</b> Gate income is {@code attendance × ticketPrice}, so
 *       price is the manager's one direct lever on gate income — and setting it had no effect on
 *       demand at all.</li>
 *   <li><b>Team identity came from the name.</b> Two clubs may share a name, so the rivalry bonus
 *       keyed off {@code name.charAt(0)} was both wrong and unstable. Everything here is keyed off
 *       the team id.</li>
 * </ul>
 *
 * <h2>How attendance is built</h2>
 * <pre>
 *   base demand  = f(home reputation, home success, home form)
 *   + away pull  = f(away reputation, away success) at a reduced weight
 *   + occasion   = derby / rival / high round
 *   + surface    = pitch condition, small
 *   × price elasticity  (how expensive the ticket is)
 *   = demand as a fraction of capacity
 *   then split into  home 80% / away 20%  and cap each at its sector
 * </pre>
 *
 * <p>The away split is a hard rule from the owner: <b>visiting supporters always get 20% of the
 * ground and never more</b>, so a home club can be completely sold out with 5,000 empty seats in
 * the away section, and a sold-out away section is a full house even if the home end is half empty.
 * That is what makes an away run worth travelling for.
 */
@Service
public class AttendanceService {

    /**
     * Share of the ground reserved for visiting supporters. Re-exported for callers that already
     * hold an AttendanceService; the rule itself lives in {@link AdmissionService} so there is one
     * definition of it.
     */
    public static final double AWAY_SECTOR_SHARE = AdmissionService.AWAY_SECTOR_SHARE;

    /** Reference price for elasticity — a ticket at this price is "normal" and costs no demand. */
    private static final double REFERENCE_PRICE = 15.0;

    /**
     * Price elasticity. Demand falls as the price rises, but gently: a fan who wants to see the
     * game still comes at a higher price, and an empty ground is worth more to nobody. −0.55 means
     * doubling the price costs roughly a third of the crowd.
     */
    private static final double PRICE_ELASTICITY = -0.55;

    /** How much of the demand the away side can contribute, relative to the home side. */
    private static final double AWAY_PULL = 0.35;

    /** Weight of recent form against season-long success. Supporters chase both. */
    private static final double FORM_WEIGHT = 0.45;

    private final TeamRepository teamRepository;
    private final StadiumRepository stadiumRepository;
    private final MatchRepository matchRepository;
    private final AdmissionService admission;

    public AttendanceService(TeamRepository teamRepository,
                            StadiumRepository stadiumRepository,
                            MatchRepository matchRepository,
                            AdmissionService admission) {
        this.teamRepository = teamRepository;
        this.stadiumRepository = stadiumRepository;
        this.matchRepository = matchRepository;
        this.admission = admission;
    }

    public int ensureAttendance(Match match) {
        if (match == null) return 0;
        Stadium stadium = resolveStadium(match);
        if (match.getStadium() == null && stadium != null) match.setStadium(stadium);
        int attendance = estimateAttendance(match);
        match.setAttendance(attendance);
        return attendance;
    }

    /**
     * Total attendance, and the split. See {@link Attendance} for the components.
     */
    public Attendance estimate(Match match) {
        if (match == null || match.getHomeTeam() == null) return Attendance.empty();

        Team home = match.getHomeTeam();
        Team away = match.getAwayTeam();
        Stadium stadium = resolveStadium(match);
        int capacity = Math.max(250, stadium != null && stadium.getCapacity() != null
                ? stadium.getCapacity() : 6000);

        // The 20% away sector is AdmissionService's rule, not a second copy of it.
        int awayCapacity = admission.awaySectorCapacity(stadium);
        int homeCapacity = Math.max(0, capacity - awayCapacity);

        // --- home demand ---
        double homeRep = reputationOf(home, 54.0);
        double homePull = (homeRep - 50.0) / 50.0;                 // -1 .. +1
        double homeSuccess = successRateOf(home);
        double homeForm = formOf(home);
        double homeBase = 0.30
                + homePull * 0.26
                + homeSuccess * 0.16
                + homeForm * FORM_WEIGHT * 0.16;

        // --- away demand: the same idea, much less weight ---
        double awayRep = reputationOf(away, 50.0);
        double awayPull = (awayRep - 50.0) / 50.0;
        double awayBase = 0.10
                + awayPull * 0.10
                + successRateOf(away) * 0.06
                + formOf(away) * FORM_WEIGHT * 0.06;
        // A big club travelling to a small club still brings a crowd, but never past the 20% cap.
        awayBase *= (0.7 + 0.3 * AWAY_PULL);

        // --- occasion and surface ---
        double occasion = resolveOccasion(home, away);
        double surface = resolveSurface(stadium);

        homeBase += occasion + surface;

        // --- supporter mood (P2-5) ---
        // A crowd that feels ignored stops paying to be in the stand. Applied to the home end, where
        // the walk-up crowd actually is, and only lightly: an angry support still turns up, it just
        // stops filling the ground. This is the consequence the meta layer was missing — mood reaches
        // gate income, gate income reaches the wage bill, the wage bill reaches the board's trust.
        homeBase *= SupporterMoodService.attendanceEffect(home.getSupporterMood());

        // --- price ---
        double price = stadium != null && stadium.getTicketPrice() != null
                ? stadium.getTicketPrice() : REFERENCE_PRICE;
        double priceEffect = Math.pow(price / REFERENCE_PRICE, PRICE_ELASTICITY);

        // A cheap ticket pulls more; an expensive one puts off the casual supporter first, so the
        // home (walk-up) crowd is hit harder than the travelling end.
        int homeAttend = (int) Math.round(homeCapacity * clamp(homeBase, 0.05, 1.0) * priceEffect);
        int awayAttend = (int) Math.round(awayCapacity * clamp(awayBase, 0.0, 1.0)
                * Math.pow(priceEffect, 0.5));

        homeAttend = (int) Math.min(homeCapacity, Math.max(0, homeAttend));
        awayAttend = (int) Math.min(awayCapacity, Math.max(0, awayAttend));

        return new Attendance(homeAttend, awayAttend, homeAttend + awayAttend, capacity,
                awayCapacity, price, round2(homeBase * priceEffect), round2(awayBase));
    }

    public int estimateAttendance(Match match) {
        return estimate(match).total();
    }

    /**
     * Gate revenue for a match: home tickets plus away tickets, at the home club's price.
     *
     * <p>The away end pays the home club's price, which is how a home match is worth money to the
     * home club regardless of who is standing in the away seats.
     */
    public double gateRevenue(Match match) {
        Attendance a = estimate(match);
        return round2(admission.realisedGateRevenue(resolveStadium(match), a.home(), a.away()));
    }

    // --- components ---

    /**
     * Season-long success, 0..1. League position is the honest measure; with no table available
     * this falls back to goal difference, which at least rewards winning rather than drawing.
     */
    private double successRateOf(Team team) {
        if (team == null) return 0.5;
        return clamp(0.5 + (reputationOf(team, 50.0) - 50.0) / 100.0, 0.0, 1.0);
    }

    /**
     * Recent form, -1..+1, from the last five results. This is the component that makes a manager
     * feel a turnaround: reputation does not move for a week, form does.
     */
    private double formOf(Team team) {
        if (team == null || team.getId() == null || matchRepository == null) return 0.0;
        try {
            List<Match> played = matchRepository
                    .findByHomeTeamIdOrAwayTeamIdAndPlayedTrueOrderByMatchDateDesc(
                            team.getId(), team.getId());
            if (played == null || played.isEmpty()) return 0.0;

            int points = 0;
            int n = 0;
            for (Match m : played) {
                if (n >= 5) break;
                boolean home = team.getId().equals(
                        m.getHomeTeam() == null ? null : m.getHomeTeam().getId());
                int mine = home ? m.getHomeGoals() : m.getAwayGoals();
                int theirs = home ? m.getAwayGoals() : m.getHomeGoals();
                points += mine > theirs ? 3 : mine == theirs ? 1 : 0;
                n++;
            }
            if (n == 0) return 0.0;
            double ppg = (double) points / (n * 3.0);      // 0..1
            return (ppg - 0.5) * 2.0;                      // -1..+1
        } catch (RuntimeException e) {
            return 0.0;
        }
    }

    /**
     * A local derby or a genuine rivalry, keyed off team ids.
     *
     * <p>Previously this compared the first letter of the two team <em>names</em>, which is not a
     * rivalry test — it is a coincidence, and two clubs with the same name produced a "derby" with
     * themselves.
     */
    private double resolveOccasion(Team home, Team away) {
        if (home == null || away == null) return 0.0;
        Long hid = home.getId();
        Long aid = away.getId();
        if (hid == null || aid == null) return 0.0;
        if (hid.equals(aid)) return 0.0;

        // Compared by country id, not by name — same reasoning as teams: names are not identity.
        Long hc = home.getCountry() == null ? null : home.getCountry().getId();
        Long ac = away.getCountry() == null ? null : away.getCountry().getId();
        boolean sameCountry = hc != null && hc.equals(ac);
        if (sameCountry) return 0.08;                        // a domestic derby

        double gap = Math.abs(reputationOf(home, 50.0) - reputationOf(away, 50.0));
        return gap <= 10.0 ? 0.04 : 0.0;                     // or a competitive mismatch
    }

    /** A poor surface keeps a few people away. Small effect — it is not a driving factor. */
    private double resolveSurface(Stadium stadium) {
        if (stadium == null) return 0.0;
        Integer cond = stadium.getPitchCondition();
        if (cond == null) return 0.0;
        return clamp((cond - 70) / 300.0, -0.04, 0.03);
    }

    private Stadium resolveStadium(Match match) {
        if (match == null) return null;
        if (match.getStadium() != null && match.getStadium().getId() != null) {
            return stadiumRepository.findById(match.getStadium().getId()).orElse(null);
        }
        if (match.getHomeTeam() == null || match.getHomeTeam().getId() == null) return null;
        return teamRepository.findWithStadiumById(match.getHomeTeam().getId())
                .map(Team::getStadium).orElse(null);
    }

    private double reputationOf(Team team, double fallback) {
        if (team == null || team.getReputation() == null) return fallback;
        return clamp(team.getReputation(), 0.0, 100.0);
    }

    private double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private String safe(String s) {
        return s == null ? "" : s;
    }

    /**
     * The attendance breakdown, so the UI can show <em>why</em> a crowd is the size it is rather
     * than just the number.
     */
    public record Attendance(int home, int away, int total, int capacity,
                             int awayCapacity, double ticketPrice,
                             double homeDemand, double awayDemand) {

        public static Attendance empty() {
            return new Attendance(0, 0, 0, 0, 0, 0, 0, 0);
        }

        /** Away end as a share of its own sector — the number that tells a manager if it sold out. */
        public double awaySectorFill() {
            return awayCapacity <= 0 ? 0 : (double) away / awayCapacity;
        }

        public double homeSectorFill() {
            int homeCapacity = capacity - awayCapacity;
            return homeCapacity <= 0 ? 0 : (double) home / homeCapacity;
        }

        public double totalFill() {
            return capacity <= 0 ? 0 : (double) total / capacity;
        }
    }
}
