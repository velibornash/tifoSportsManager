package org.tifo.footballmanager.newLogic.service;

import org.tifo.footballmanager.newLogic.model.FinanceCategory;
import org.tifo.footballmanager.newLogic.model.GameClock;
import org.tifo.footballmanager.newLogic.model.FinanceLedgerEntry;
import org.tifo.footballmanager.newLogic.model.StaffMember;
import org.tifo.footballmanager.newLogic.model.StaffRole;
import org.tifo.footballmanager.newLogic.model.Team;
import org.tifo.commonmanager.model.User;
import org.tifo.footballmanager.newLogic.repository.FinanceLedgerEntryRepository;
import org.tifo.footballmanager.newLogic.repository.StaffMemberRepository;
import org.tifo.footballmanager.newLogic.repository.TeamRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The staff transfer market (owner, 2026-10-18).
 *
 * <p>The owner: *"da, zelim da napravis mogucnost da se regrutuju svi od postojecih a na klubu je da li
 * ce ih regrutovati .. maks 1 glavni trener 2 pomocnika 1 doktor 1 fizio 1 scout .. dolaze svake nedelje
 * pocevsi od prve sa random skilovima, korisnik moze da ih prihvati (ako ima mesta - moze da otpusti
 * postojeceg koji dobija platu za odlazak) ili odbije."*
 *
 * <h2>A candidate is a staff row with no club</h2>
 * <p>The market is <b>not</b> a second entity. {@code staff_member.team_id} is already nullable, so
 * somebody looking for work is exactly a {@link StaffMember} whose team is null. That one fact buys
 * almost the whole feature:
 *
 * <ul>
 *   <li>Hiring is {@code setClub}; the row the market listed becomes the club's employee, and the
 *       directory, the wage bill, {@code coachingStrength} and the physio all start reading it through
 *       the queries they already have.</li>
 *   <li>Refusing deletes the row, which is the only honest meaning of "he is no longer available".</li>
 *   <li>Nothing in the existing readers can be tricked into treating a candidate as an employee,
 *       because every one of them asks by team.</li>
 * </ul>
 *
 * <p>A second table would have duplicated fifteen columns to store a distinction the schema already
 * makes, and would have needed a synchroniser to keep the two from disagreeing about who works where.
 *
 * <h2>Slots</h2>
 * <p>The limits are the owner's, and they live here because <b>nothing else can enforce them</b>: there
 * is no unique constraint on {@code (team_id, role)}, so a bug that signs a third assistant is silent.
 * {@link #countByTeamIdAndRole} is the check, and it is the reason the limit is a map rather than a
 * constant — a club is allowed two assistants and one of everything else.
 *
 * <h2>AI recruitment is throttled on purpose</h2>
 * <p>There are 7,316 clubs and two human managers. An AI rule of "every club with a vacancy signs the
 * best man it can afford" would empty the market in the first week and leave the humans with nothing
 * to look at, which is the opposite of the feature. So a small number of AI clubs are signed each
 * week. Full staffing of an AI world is a seeding question, not a market one —
 * {@link StaffSponsorService} already does that, and remains the tool for it.
 */
@Service
public class StaffRecruitingService {

    private static final Logger log = LoggerFactory.getLogger(StaffRecruitingService.class);

    /**
     * How many of each role a club may employ. The owner's limits.
     *
     * <p>Every role defaults to one; the assistant is the only one allowed two, which is why this is a
     * map and not a constant.
     */
    private static final Map<StaffRole, Integer> SLOTS_PER_ROLE;

    static {
        Map<StaffRole, Integer> slots = new EnumMap<>(StaffRole.class);
        for (StaffRole role : StaffRole.values()) {
            slots.put(role, 1);
        }
        slots.put(StaffRole.ASSISTANT, 2);
        SLOTS_PER_ROLE = Collections.unmodifiableMap(slots);
    }

    /** Candidates generated each week — one per role, so every vacancy has somebody to look at. */
    private static final int CANDIDATES_PER_ROLE_PER_WEEK = 1;

    /**
     * AI signings per week. See the class comment: the number is small because the market has to stay
     * worth a human manager's time, and large enough that the world is not frozen.
     */
    static final int AI_SIGNUPS_PER_WEEK = 3;

    /**
     * The neutral club a candidate's asking wage is quoted at.
     *
     * <p><b>The wage is fixed when the candidate is generated.</b> It is a function of the buyer's tier
     * and reputation, so it cannot be shown on a market shared by clubs of every division. Quoting it
     * once at a neutral weight means the number a manager sees is the number he pays — which is the
     * only property a price on a shared board can honestly have.
     */
    private static final int WAGE_QUOTE_TIER = 3;
    private static final double WAGE_QUOTE_REPUTATION = 50.0;

    /** The club roles are filled in this order when an AI club has more than one vacancy. */
    private static final StaffRole[] AI_PRIORITY = {
            StaffRole.HEAD_COACH, StaffRole.ASSISTANT, StaffRole.PHYSIO,
            StaffRole.DOCTOR, StaffRole.SCOUT, StaffRole.GK_COACH, StaffRole.YOUTH_COACH
    };

    private final StaffMemberRepository staff;
    private final TeamRepository teams;
    private final FinanceLedgerEntryRepository ledger;
    private final ClubOwnershipLinker ownership;
    private final SeasonService seasons;
    private final Random random = new Random();

    public StaffRecruitingService(StaffMemberRepository staff,
                                  TeamRepository teams,
                                  FinanceLedgerEntryRepository ledger,
                                  ClubOwnershipLinker ownership,
                                  SeasonService seasons) {
        this.staff = staff;
        this.teams = teams;
        this.ledger = ledger;
        this.ownership = ownership;
        this.seasons = seasons;
    }

    // ------------------------------------------------------------------
    // The market
    // ------------------------------------------------------------------

    /** Everybody looking for work, oldest first — the order they arrived in. */
    @Transactional(readOnly = true)
    public List<StaffMember> market() {
        List<StaffMember> out = new ArrayList<>(staff.findByTeamIsNull());
        // findByTeamIsNull has no order guarantee, and a market that presents itself differently on
        // each render is a market nobody learns to read.
        out.sort((a, b) -> {
            int byId = Long.compare(a.getId(), b.getId());
            return byId != 0 ? byId : a.getRole().compareTo(b.getRole());
        });
        return out;
    }

    /**
     * Signs a candidate for the manager's club.
     *
     * @return the same row, now employed
     * @throws IllegalStateException with a reason the UI can show: the manager has no club, the
     *                               candidate is gone, or the role is full
     */
    @Transactional
    public StaffMember accept(User user, Long candidateId) {
        Team club = ownership.clubOf(user);
        if (club == null) {
            throw new StaffRecruitingException("no_club", "This account is not managing a club.");
        }
        if (club.getBudget() != null && club.getBudget() < 0) {
            throw new StaffRecruitingException("in_debt", "The club is in debt and cannot hire staff.");
        }
        StaffMember candidate = staff.findById(candidateId)
                .filter(m -> m.getTeam() == null)
                .orElseThrow(() -> new StaffRecruitingException("gone",
                        "That candidate is no longer available. Somebody else signed him, or he was turned down."));
        int employed = Math.toIntExact(staff.countByTeamIdAndRole(club.getId(), candidate.getRole()));
        int allowed = SLOTS_PER_ROLE.getOrDefault(candidate.getRole(), 1);
        if (employed >= allowed) {
            throw new StaffRecruitingException("slot_full",
                    "The " + candidate.getRole() + " position is full. Somebody has to leave before he can join.");
        }
        candidate.setTeam(club);
        return staff.save(candidate);
    }

    /** Turns a candidate down. He is gone, which is what refusing means. */
    @Transactional
    public void refuse(Long candidateId) {
        staff.findById(candidateId)
                .filter(m -> m.getTeam() == null)
                .ifPresent(staff::delete);
    }

    /**
     * Lets an employee go.
     *
     * <p>The owner's rule is that a fired man is paid for leaving: <b>one week's wage</b>, charged to
     * the club and written to the ledger. The line is written before the row is deleted so the club's
     * accounts say why the money moved — a deletion on its own leaves a gap in the wage bill and no
     * explanation.
     */
    @Transactional
    public void fire(User user, Long teamId, Long memberId) {
        Team club = ownership.clubOf(user);
        if (club == null || !club.getId().equals(teamId)) {
            throw new StaffRecruitingException("not_yours", "That is not your club to make this decision about.");
        }
        StaffMember member = staff.findById(memberId)
                .filter(m -> m.getTeam() != null && m.getTeam().getId().equals(teamId))
                .orElseThrow(() -> new StaffRecruitingException("not_yours", "That man does not work for this club."));

        double wage = member.getWeeklyWage() == null ? 0.0 : member.getWeeklyWage();
        int season = clock().getCurrentSeason();
        int week = clock().getCurrentWeek();
        ledger.save(FinanceLedgerEntry.of(club, season, week, FinanceCategory.STAFF_SEVERANCE,
                -wage, "Severance pay: " + member.getName()));
        Team fresh = teams.findById(teamId).orElseThrow();
        fresh.setBudget(round2((fresh.getBudget() == null ? 0.0 : fresh.getBudget()) - wage));
        teams.save(fresh);

        staff.delete(member);
    }

    // ------------------------------------------------------------------
    // The weekly batch
    // ------------------------------------------------------------------

    /**
     * Puts this week's candidates on the market — one per role, with random skills.
     *
     * <p>What stops this running twice in a week is not in this method: {@code job_run} carries a unique
     * constraint on {@code (season, week, day, job_key)} and the runner skips a job that has a DONE row,
     * so one key cannot fire twice for the same day. A guard here as well would be a second answer to a
     * question the schema already answers.
     *
     * @return how many were created
     */
    @Transactional
    public int weeklyBatch() {
        int season = clock().getCurrentSeason();
        int created = 0;
        for (StaffRole role : StaffRole.values()) {
            for (int i = 0; i < CANDIDATES_PER_ROLE_PER_WEEK; i++) {
                staff.save(randomCandidate(role, season));
                created++;
            }
        }
        log.info("Staff market: {} candidates put up in season {} week {}.",
                created, season, clock().getCurrentWeek());
        return created;
    }

    /**
     * Lets a handful of AI clubs each fill one vacancy.
     *
     * <p><b>Three queries, and the world is not walked one club at a time</b> (owner, 2026-10-21:
     * "nije stigao staff, nesto ga zeza"). It used to ask every club in the world for its staff count
     * — seven queries for each of fourteen thousand clubs — and to ask for the candidates on the
     * market again for every club it looked at. That is a hundred thousand round trips inside one
     * transaction, and the job never finished: the market's weekly batch was rolled back with it, the
     * row stayed PENDING for ever, and the next scan took the slot again while the first copy was
     * still running. **The staff never arrived because the job that brings them never completed.**
     *
     * <p>So the counts come in one query, the candidates in one query, and the clubs in one, and the
     * rest is arithmetic.
     *
     * @return how many signed
     */
    @Transactional
    public int aiRecruit() {
        int signed = 0;
        List<StaffMember> candidates = staff.findByTeamIsNull();
        if (candidates.isEmpty()) {
            return 0;
        }
        Map<Long, Map<StaffRole, Integer>> held = staffCountsByTeamAndRole();

        List<Team> clubs = new ArrayList<>();
        for (Team club : teams.findAll()) {
            if (club.isHumanControlled()) continue;
            if (!hasVacancy(held, club.getId())) continue;
            clubs.add(club);
        }
        Collections.shuffle(clubs, random);

        for (Team club : clubs) {
            if (signed >= AI_SIGNUPS_PER_WEEK) break;
            StaffMember best = bestCandidateFor(club, candidates, held);
            if (best == null) continue;
            best.setTeam(club);
            staff.save(best);
            signed++;
        }
        if (signed > 0) {
            log.info("Staff market: {} AI club(s) made an appointment.", signed);
        }
        return signed;
    }

    /**
     * How many staff each club holds, by role, in one query.
     *
     * <p>The shape the answer is used in: a club's vacancies are read out of it rather than asked
     * for, so the world's fourteen thousand clubs cost one round trip between them.
     */
    private Map<Long, Map<StaffRole, Integer>> staffCountsByTeamAndRole() {
        Map<Long, Map<StaffRole, Integer>> held = new HashMap<>();
        for (Object[] row : staff.countsByTeamAndRole()) {
            Long teamId = (Long) row[0];
            StaffRole role = (StaffRole) row[1];
            int count = ((Number) row[2]).intValue();
            if (teamId == null || role == null) continue;
            held.computeIfAbsent(teamId, ignored -> new HashMap<>()).put(role, count);
        }
        return held;
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /** The best candidate a club could still use and pay, or null. */
    private StaffMember bestCandidateFor(Team club, List<StaffMember> candidates,
                                        Map<Long, Map<StaffRole, Integer>> held) {
        double budget = club.getBudget() == null ? 0.0 : club.getBudget();
        StaffMember best = null;
        for (StaffMember candidate : candidates) {
            if (!hasVacancy(held, club.getId(), candidate.getRole())) continue;
            double wage = candidate.getWeeklyWage() == null ? 0.0 : candidate.getWeeklyWage();
            if (wage > budget) continue;
            if (best == null || candidate.overallSkill() > best.overallSkill()) {
                best = candidate;
            }
        }
        return best;
    }

    /**
     * Whether a club has a gap in any of the roles the AI clubs hire for.
     *
     * <p>Read out of the counts that came in one query, so the whole world is arithmetic here.
     */
    private boolean hasVacancy(Map<Long, Map<StaffRole, Integer>> held, Long teamId) {
        for (StaffRole role : AI_PRIORITY) {
            if (hasVacancy(held, teamId, role)) return true;
        }
        return false;
    }

    /** Whether a club has a gap in one role. */
    private boolean hasVacancy(Map<Long, Map<StaffRole, Integer>> held, Long teamId, StaffRole role) {
        int allowed = SLOTS_PER_ROLE.getOrDefault(role, 1);
        int employed = held.getOrDefault(teamId, Map.of()).getOrDefault(role, 0);
        return employed < allowed;
    }

    /**
     * A man looking for work, with the skills the owner asked to be random.
     *
     * <p>Random here is a genuine draw, unlike the seeder: a seeded coach's abilities are derived from
     * his club so that a saved league keeps its staff, and a market candidate has no club to be
     * derived from. The spread keeps him inside 1-20 and the role specialities come from the seeder
     * too, so a physio on the market is recognisably a physio.
     */
    private StaffMember randomCandidate(StaffRole role, int season) {
        StaffMember m = new StaffMember();
        m.setRole(role);
        m.setName(randomName());
        m.setAge(StaffSponsorService.ageBase(role) + random.nextInt(14));
        m.setContractEndSeason(season + 1 + random.nextInt(3));

        int base = 8 + random.nextInt(9);      // 8-16: a market of mixed quality
        int spread = 1 + random.nextInt(4);    // 1-4
        m.setDevelopment(skill(base, spread));
        m.setTactical(skill(base, spread));
        m.setMotivation(skill(base, spread));
        m.setGoalkeeping(skill(base, spread));
        m.setFitness(skill(base, spread));
        m.setScouting(skill(base, spread));

        m.setSkillStamina(skill(base, spread));
        m.setSkillGoalkeeper(skill(base, spread));
        m.setSkillDefender(skill(base, spread));
        m.setSkillPace(skill(base, spread));
        m.setSkillTechnique(skill(base, spread));
        m.setSkillPlaymaker(skill(base, spread));
        m.setSkillPassing(skill(base, spread));
        m.setSkillStriker(skill(base, spread));

        StaffSponsorService.specialise(m, role, spread);
        StaffSponsorService.applyCoachSpeciality(m, role, spread);

        // Last, because it is a function of the skills he ended up with.
        m.setWeeklyWage(CoachWage.weeklyWageFor(m, WAGE_QUOTE_TIER, WAGE_QUOTE_REPUTATION));
        return m;
    }

    private String randomName() {
        return StaffSponsorService.FIRST[random.nextInt(StaffSponsorService.FIRST.length)]
                + " " + StaffSponsorService.LAST[random.nextInt(StaffSponsorService.LAST.length)];
    }

    /** A skill drawn around a base, clamped into 1-20 like every other rating in the model. */
    private int skill(int base, int spread) {
        int offset = random.nextInt(2 * spread + 1) - spread;
        return Math.max(1, Math.min(20, base + offset));
    }

    private GameClock clock() {
        return seasons.getOrCreateClock();
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /**
     * A refusal the UI is meant to read, not a failure it is meant to swallow.
     *
     * <p>Carrying a reason code is the whole point: "the position is full" and "that man is gone" are
     * different things to tell a manager, and both are normal outcomes of a button pressed a week late.
     * The job-offer endpoint learned this the same way.
     */
    public static class StaffRecruitingException extends RuntimeException {
        private final String reason;

        public StaffRecruitingException(String reason, String message) {
            super(message);
            this.reason = reason;
        }

        public String reason() {
            return reason;
        }
    }
}
