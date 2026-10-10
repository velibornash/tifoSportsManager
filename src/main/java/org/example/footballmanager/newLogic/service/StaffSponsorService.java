package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.Sponsor;
import org.example.footballmanager.newLogic.model.StaffMember;
import org.example.footballmanager.newLogic.model.StaffRole;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.SponsorRepository;
import org.example.footballmanager.newLogic.repository.StaffMemberRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Year;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Staff and sponsors for every club in the pyramid (Sprint 2.3).
 *
 * <p>Neither existed as an entity. The staff directory was a hardcoded array in the browser and
 * coaching had no simulation effect at all, so the most expensive thing a club buys did nothing.
 *
 * <p>Quality and size scale with the club's reputation and the division tier, and the **specific
 * people are derived from the team id** rather than drawn at random. A random draw would reshuffle
 * every club's staff on each reseed, which makes a saved league meaningless and makes a manager's
 * relationship with their head coach evaporate between runs.
 */
@Service
public class StaffSponsorService {

    private static final String[] FIRST = {
            "Dragan", "Nikola", "Milos", "Stefan", "Vladimir", "Predrag", "Aleksandar", "Djordje",
            "Nemanja", "Luka", "Marko", "Ivan", "Sasa", "Bojan", "Zoran", "Filip", "Uros", "Sinisa"
    };
    private static final String[] LAST = {
            "Jovanovic", "Petrovic", "Nikolic", "Ilic", "Stojanovic", "Djordjevic", "Ristic",
            "Simic", "Pavlovic", "Mladenovic", "Todorovic", "Lazic", "Soric", "Vasic", "Markovic"
    };
    private static final String[] SPONSOR_A = {
            "Meridian", "Vukovar", "Sava", "Danube", "Krajina", "Tara", "Morava", "Ibar",
            "Kolubara", "Drina", "Sremac", "Backa", "Sumadija", "Banat"
    };
    private static final String[] SPONSOR_B = {
            "Group", "Logistics", "Energy", "Motors", "Foods", "Beverages", "Systems", "Bank",
            "Construction", "Insurance", "Telecom", "Steel", "Textiles", "Pharma"
    };

    private final TeamRepository teams;
    private final StaffMemberRepository staff;
    private final SponsorRepository sponsors;

    public StaffSponsorService(TeamRepository teams,
                               StaffMemberRepository staff,
                               SponsorRepository sponsors) {
        this.teams = teams;
        this.staff = staff;
        this.sponsors = sponsors;
    }

    /** Seeds any club that has no staff yet. Idempotent. */
    @Transactional
    public int seedAllClubs(int currentSeason) {
        List<Team> all = teams.findAll();

        // **One query for the set, not one per club.**
        //
        // The loop asked `staff.countByTeamId(club.getId())` for every club in the world, which is
        // 1 + 14,880 queries to answer a question that is one `select distinct`. The per-club answer
        // cannot narrow the query it makes, so it bought nothing but the round trip — the same shape as
        // `MatchdayJob`, and the same fix: read the set once and filter in Java.
        //
        // Both sets are read, not just staff: `seedClub` asks the sponsors the same question, and a
        // club with staff but no sponsor would otherwise still be asked once per seeding.
        Set<Long> staffed = new HashSet<>(staff.findStaffedTeamIds());
        Set<Long> sponsored = new HashSet<>(sponsors.findSponsoredTeamIds());

        int created = 0;
        for (Team club : all) {
            if (club.getId() == null) continue;
            if (staffed.contains(club.getId())) continue;
            seedClub(club, currentSeason, staffed, sponsored);
            created++;
        }
        return created;
    }

    @Transactional
    public void seedClub(Team club, int currentSeason) {
        // Single-club entry point, used by the admin button. It pays the count it needs rather than
        // forcing every caller to have loaded the world's sets for one club.
        seedClub(club, currentSeason, null, null);
    }

    /**
     * @param alreadyStaffed   the clubs that already have staff, or {@code null} when the caller has not
     *                         read them and the answer has to be looked up
     * @param alreadySponsored the clubs that already have a sponsor, or {@code null} for the same reason
     */
    private void seedClub(Team club, int currentSeason, Set<Long> alreadyStaffed, Set<Long> alreadySponsored) {
        boolean hasStaff = alreadyStaffed != null
                ? alreadyStaffed.contains(club.getId())
                : staff.countByTeamId(club.getId()) > 0;
        if (hasStaff) return;
        int tier = tierOf(club);
        // Quality follows reputation; reputation already follows tier.
        double quality = reputationOf(club);

        staff.saveAll(buildStaff(club, tier, quality, currentSeason));
        boolean sponsored = alreadySponsored != null
                ? alreadySponsored.contains(club.getId())
                : sponsors.countByTeamId(club.getId()) > 0;
        if (!sponsored) {
            sponsors.saveAll(buildSponsors(club, tier, quality, currentSeason));
        }
    }

    private List<StaffMember> buildStaff(Team club, int tier, double quality, int season) {
        List<StaffMember> out = new ArrayList<>();

        // Size: a top-flight club employs more of everything. 3-7 members.
        int headCount = switch (tier) {
            case 1 -> 6;
            case 2 -> 5;
            case 3 -> 4;
            default -> 3;
        };
        StaffRole[] roles = {
                StaffRole.HEAD_COACH, StaffRole.ASSISTANT, StaffRole.PHYSIO,
                StaffRole.SCOUT, StaffRole.GK_COACH, StaffRole.YOUTH_COACH
        };

        for (int i = 0; i < headCount && i < roles.length; i++) {
            StaffRole role = roles[i];
            StaffMember m = new StaffMember();
            m.setTeam(club);
            m.setRole(role);
            m.setName(nameFor(club.getId(), i));
            m.setAge(ageFor(club.getId(), i, role));
            // Math.abs again: without it a signed hash could produce a contract that expired in the past.
            m.setContractEndSeason(season + 1
                    + (int) Math.abs(stableUnit(club.getId(), i + 40) % 3));

            // Each role is strong in its own attribute. Derived from the club's quality so a
            // Premier club's physio is genuinely better, and from the id so it is stable.
            int base = clampSkill(quality);
            // Math.abs matters: stableUnit returns a signed long, so a bare `% 4` can be -3 and
            // drive spread to -2, which made attr() divide by zero.
            int spread = 1 + (int) Math.abs(stableUnit(club.getId(), i + 60) % 4);
            m.setDevelopment(attr(base, spread, i));
            m.setTactical(attr(base, spread, i));
            m.setMotivation(attr(base, spread, i));
            m.setGoalkeeping(attr(base, spread, i));
            m.setFitness(attr(base, spread, i));
            m.setScouting(attr(base, spread, i));

            // The eight coaching skills (owner, 2026-09-27). Assigned before specialise() so the
            // role's speciality lands on the coaching skills as well, and the wage is derived from
            // what the coach can actually do rather than from a single development number.
            m.setSkillStamina(attr(base, spread, i));
            m.setSkillGoalkeeper(attr(base, spread, i));
            m.setSkillDefender(attr(base, spread, i));
            m.setSkillPace(attr(base, spread, i));
            m.setSkillTechnique(attr(base, spread, i));
            m.setSkillPlaymaker(attr(base, spread, i));
            m.setSkillPassing(attr(base, spread, i));
            m.setSkillStriker(attr(base, spread, i));

            specialise(m, role, spread);
            applyCoachSpeciality(m, role, spread);

            // Wage last, because it is a function of the skills the coach ended up with.
            m.setWeeklyWage(CoachWage.weeklyWageFor(m, tier, club.getReputation() == null ? 50 : club.getReputation()));
            out.add(m);
        }
        return out;
    }

    /**
     * Nudges the <i>coaching</i> skills a role is for, and gives one or two of them a genuine peak.
     *
     * <p>The peak is what makes hiring a real decision: a head coach can be bought to be a passing
     * guru, and a club that trains passing every week gets more out of him than a club with an
     * all-round coach of the same total. Deliberately not a 20 for most staff — maxed skills are
     * priced heavily, so a squad-wide 20 on everything would be unaffordable for anyone.
     */
    private void applyCoachSpeciality(StaffMember m, StaffRole role, int spread) {
        int base = m.getSkillDefender() == null ? 10 : m.getSkillDefender();
        int peak = Math.min(20, base + spread + 2);
        switch (role) {
            case HEAD_COACH -> {
                m.setSkillPassing(peak);
                m.setSkillPlaymaker(Math.min(20, peak - 1));
            }
            case ASSISTANT -> {
                m.setSkillTechnique(peak);
                m.setSkillPace(Math.min(20, peak - 1));
            }
            case GK_COACH -> m.setSkillGoalkeeper(peak);
            case PHYSIO -> m.setSkillStamina(peak);
            case SCOUT -> m.setSkillPlaymaker(Math.max(1, peak - 3));
            case YOUTH_COACH -> {
                m.setSkillTechnique(peak);
                m.setSkillPlaymaker(peak);
            }
        }
    }

    /** Nudges the attribute a role is actually for, so a scout is a good scout. */
    private void specialise(StaffMember m, StaffRole role, int spread) {
        switch (role) {
            case HEAD_COACH -> {
                m.setTactical(clampSkill(m.getTactical() + spread));
                m.setMotivation(clampSkill(m.getMotivation() + spread - 1));
            }
            case ASSISTANT -> {
                m.setDevelopment(clampSkill(m.getDevelopment() + spread));
                m.setTactical(clampSkill(m.getTactical() + 1));
                m.setMotivation(clampSkill(m.getMotivation() + spread));
            }
            case GK_COACH -> {
                m.setGoalkeeping(clampSkill(m.getGoalkeeping() + spread + 1));
                m.setMotivation(clampSkill(m.getMotivation() + spread));
            }
            case PHYSIO -> m.setFitness(clampSkill(m.getFitness() + spread + 1));
            case SCOUT -> m.setScouting(clampSkill(m.getScouting() + spread + 1));
            case YOUTH_COACH -> {
                m.setDevelopment(clampSkill(m.getDevelopment() + spread + 1));
                m.setMotivation(clampSkill(m.getMotivation() + 2));
            }
        }
    }

    private List<Sponsor> buildSponsors(Team club, int tier, double quality, int season) {
        List<Sponsor> out = new ArrayList<>();
        int count = switch (tier) {
            case 1 -> 3;
            case 2 -> 2;
            default -> 1;
        };

        for (int i = 0; i < count; i++) {
            Sponsor s = new Sponsor();
            s.setTeam(club);
            s.setName(SPONSOR_A[(int) Math.abs(stableUnit(club.getId(), i + 80) % SPONSOR_A.length)]
                    + " " + SPONSOR_B[(int) Math.abs(stableUnit(club.getId(), i + 90) % SPONSOR_B.length)]);
            s.setTier(switch (tier) {
                case 1 -> i == 0 ? Sponsor.Tier.TITLE : Sponsor.Tier.MAJOR;
                case 2 -> Sponsor.Tier.MAJOR;
                default -> Sponsor.Tier.MINOR;
            });
            double base = switch (tier) {
                case 1 -> 1_400_000;
                case 2 -> 260_000;
                case 3 -> 70_000;
                default -> 18_000;
            };
            // Variance so two clubs in the same division are not identical, and no random draw.
            double variance = 0.75 + (Math.abs(stableUnit(club.getId(), i + 100) % 100) / 400.0);
            s.setAnnualValue((double) Math.round(base * variance));
            s.setStartSeason(season - 1);
            s.setEndSeason(season + 1 + (int) Math.abs(stableUnit(club.getId(), i + 110) % 3));
            s.setPerformanceBonusClause(0.05 + (Math.abs(stableUnit(club.getId(), i + 120) % 8) / 100.0));
            out.add(s);
        }
        return out;
    }

    // --- totals, used by the weekly settlement ---

    @Transactional(readOnly = true)
    public double weeklyStaffWage(Long teamId) {
        if (teamId == null) return 0;
        return staff.findByTeamId(teamId).stream()
                .mapToDouble(m -> m.getWeeklyWage() == null ? 0 : m.getWeeklyWage())
                .sum();
    }

    @Transactional(readOnly = true)
    public double weeklySponsorshipIncome(Long teamId, int season) {
        if (teamId == null) return 0;
        return sponsors.findByTeamId(teamId).stream()
                .mapToDouble(s -> s.weeklyIncome(season))
                .sum();
    }

    @Transactional(readOnly = true)
    public int staffCount(Long teamId) {
        return (int) staff.countByTeamId(teamId);
    }

    // --- helpers ---

    private int tierOf(Team club) {
        Competition c = club.getCompetition();
        if (c == null || c.getTier() == null) return 5;
        return Math.max(1, Math.min(6, c.getTier()));
    }

    private double reputationOf(Team club) {
        return club.getReputation() == null ? 50 : club.getReputation();
    }

    private int clampSkill(double reputation) {
        // 20 reputation -> attribute 6, 100 reputation -> attribute 19.
        return (int) Math.max(4, Math.min(19, Math.round(4 + reputation * 0.15)));
    }

    private int attr(int base, int spread, int index) {
        int offset = (int) Math.abs(stableUnit((long) index, base + spread) % (spread + 1));
        return clampSkill(base + offset - spread / 2);
    }

    private int clampSkill(int v) {
        return Math.max(1, Math.min(20, v));
    }

    private String nameFor(Long teamId, int index) {
        int a = (int) Math.abs(stableUnit(teamId, index) % FIRST.length);
        int b = (int) Math.abs(stableUnit(teamId, index + 20) % LAST.length);
        return FIRST[a] + " " + LAST[b];
    }

    private int ageFor(Long teamId, int index, StaffRole role) {
        int base = switch (role) {
            case HEAD_COACH -> 48;
            case ASSISTANT -> 40;
            case GK_COACH -> 44;
            case PHYSIO -> 38;
            case SCOUT -> 42;
            case YOUTH_COACH -> 34;
        };
        return base + (int) Math.abs(stableUnit(teamId, index + 30) % 14);
    }


    /**
     * A stable hash of two longs. Not a random draw: the same club must get the same staff on every
     * reseed, or a saved league and a manager's staff relationships are both meaningless.
     */
    private static long stableUnit(Long a, int b) {
        long h = 1125899906842597L;
        h = 31 * h + (a == null ? 0 : a);
        h = 31 * h + b;
        h ^= (h >>> 33);
        h *= 0xff51afd7ed558ccdL;
        h ^= (h >>> 33);
        return h;
    }
}
