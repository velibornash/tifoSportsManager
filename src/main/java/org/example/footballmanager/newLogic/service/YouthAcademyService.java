package org.example.footballmanager.newLogic.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.newLogic.dto.junior.JuniorAcademyItemDTO;
import org.example.footballmanager.newLogic.dto.junior.JuniorAcademyStateDTO;
import org.example.footballmanager.newLogic.dto.junior.JuniorPromotionResultDTO;
import org.example.footballmanager.newLogic.exception.ApiException;
import org.example.commonmanager.model.User;
import org.example.footballmanager.newLogic.model.*;
import org.example.footballmanager.newLogic.repository.JuniorRepository;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.StaffMemberRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.example.footballmanager.newLogic.util.players.NameGenerator;
import org.example.footballmanager.newLogic.util.players.SquadNumberAssigner;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class YouthAcademyService {

    private final TeamRepository teamRepository;
    private final JuniorRepository juniorRepository;
    private final PlayerRepository playerRepository;
    private final TransferService transferService;
    private final SquadNumberAssigner squadNumberAssigner;
    private final StaffMemberRepository staffMemberRepository;
    private final PlusFeatureService plusFeatures;
    private final Random random = new Random();

    @Transactional
    public void generateSeasonIntakeForWeek2(int seasonNumber, int weekNumber) {
        if (weekNumber != INTAKE_WEEK) return;

        List<Team> teams = teamRepository.findClubTeamsForOperations();

        for (Team team : teams) {
            if (team.getId() == null) continue;

            // The academy is a purchase, not a default (Sprint 5.3a, owner 2026-09-27). Before this
            // every one of the 310 clubs rolled an intake every season for free, human-managed or not.
            // A club without a school now has to buy its way to young players, which is what makes
            // the scouting network and the transfer market matter.
            //
            // Human-controlled only, by the same rule: bot squads keep the same players and train at
            // the default pace.
            if (!team.isHumanControlled() || !Boolean.TRUE.equals(team.getJuniorSchoolActive())) {
                continue;
            }

            long alreadyGenerated = juniorRepository.countByTeamIdAndArrivalSeasonNumberAndArrivalWeekNumber(team.getId(), seasonNumber, INTAKE_WEEK);
            if (alreadyGenerated > 0) continue;
            archiveResolvedJuniorsBeforeSeason(team.getId(), seasonNumber);

            ensureCoachSkill(team);
            long activeVisible = juniorRepository.countVisibleByTeamIdAndStatus(team.getId(), JuniorStatus.ACTIVE);
            int freeSlots = Math.max(0, MAX_ACTIVE_JUNIORS - (int) activeVisible);
            if (freeSlots <= 0) {
                log.info("Youth intake skipped for team {} in season {}: academy already has {} active juniors (max 10).",
                        team.getName(), seasonNumber, activeVisible);
                continue;
            }
            int intakeCount = Math.min(rollIntakeCount(), freeSlots);
            List<Junior> intake = new ArrayList<>();
            for (int i = 0; i < intakeCount; i++) {
                Junior j = new Junior();
                j.setName(NameGenerator.fullName());
                j.setAge(INTAKE_MIN_AGE + random.nextInt(INTAKE_AGE_SPAN));
                j.setTalent(rollTalent());
                double initialSkill = rollInitialAcademySkill();
                j.setAcademySkillExact(round2(initialSkill));
                j.setAcademySkill((int) Math.floor(j.getAcademySkillExact()));
                j.setLastWeeklyDelta(0.0);
                j.setArrivalSeasonNumber(seasonNumber);
                j.setArrivalWeekNumber(INTAKE_WEEK);
                j.setArrivalAge(j.getAge());
                // Signed for a position, not rolled on the way out (Sprint 5.3).
                j.setPosition(rollPosition());
                rollBodyAndTemperament(j);
                // How badly the club is guessing about him on day one. This does NOT touch the
                // quality distribution -- talent and academy skill are rolled exactly as they always
                // were, which is the owner rule that the graduation distribution must not move. All
                // this does is decide how wide the report drawn on that roll is allowed to be.
                j.setTalentRangeHalfWidth(TalentRange.intakeHalfWidth(random.nextInt(4)));
                j.setStatus(JuniorStatus.ACTIVE);
                j.setArchived(false);
                j.setTeam(team);
                intake.add(j);
            }
            juniorRepository.saveAll(intake);
            log.info("Youth intake generated for team {}: {} juniors (season {}, week 2)", team.getName(), intakeCount, seasonNumber);
        }
    }

    @Transactional
    public void progressActiveJuniorsWeekly(int seasonNumber, int weekNumber) {
        if (weekNumber <= 2) return;
        List<Junior> active = juniorRepository.findByStatus(JuniorStatus.ACTIVE);
        for (Junior junior : active) {
            if (junior.getArrivalSeasonNumber() < seasonNumber) {
                // Unresolved juniors from previous seasons remain in academy view but do not train anymore.
                junior.setLastWeeklyDelta(0.0);
                continue;
            }
            Team team = junior.getTeam();
            if (team == null) continue;
            ensureCoachSkill(team);
            double delta = computeWeeklyDelta(junior, team.getJuniorCoachSkill(), academyQualityOf(team));
            double nextExact = clamp(junior.getAcademySkillExact() + delta, 0.0, 20.99);
            junior.setAcademySkillExact(round2(nextExact));
            junior.setAcademySkill((int) Math.floor(junior.getAcademySkillExact()));
            junior.setLastWeeklyDelta(round2(delta));
            developBody(junior, team);
        }
        juniorRepository.saveAll(active);
        log.info("Youth academy weekly progression done for season {}, week {} ({} juniors).", seasonNumber, weekNumber, active.size());
    }

    /**
     * Resolves every junior whose one season in the academy is over, by transfer-listing him
     * (owner, 2026-10-08).
     *
     * <p><b>This does not age anyone.</b> Ageing happens once a year at the season boundary in
     * {@code SeasonService.agePlayersAndJuniorsOneYear()}, and an earlier version of this method
     * aged juniors as well, which would have made every player a year older per season twice over.
     *
     * <p><b>What replaced what.</b> The sweep used to read {@code age >= 20}. That made tenure a
     * function of the intake roll — one to five seasons — and it needed the age ceiling as an
     * emergency exit, because nothing else ever closed: a junior who was released, listed or
     * promoted left the ACTIVE pool, but a junior nobody acted on simply kept ageing and never left.
     * Tenure is now {@link #TENURE_SEASONS} and the query is the <b>arrival season</b>, so the
     * emergency exit is the same code path as the normal one. There is no second rule to forget.
     *
     * <p><b>Why transfer listing rather than promotion</b> (owner). A manager who never opened the
     * academy page should not end the season with ten players he did not choose, and a prospect the
     * club spent a season developing should be worth something to somebody. He is built as a senior,
     * listed at his own estimated value, and another manager decides.
     *
     * <p><b>The squad cap still has teeth</b> (P2-6, kept deliberately). Graduation creates no
     * {@code PlayerContract} and {@code canRegister} counts contracts, so a graduate is invisible to
     * the 25-senior cap — an academy would otherwise be an unlimited source of players. Room is
     * therefore counted down per club here, and a junior whose club has no place for him is
     * <b>released</b> rather than listed, because listing him would put a twenty-sixth player in a
     * twenty-five-man squad. He is still better off than the version where a failed promotion left
     * him {@code ACTIVE} past twenty forever.
     */
    @Transactional
    public int graduateExpiredJuniors(int seasonNumber) {
        // Called while seasonNumber is still the season being closed, so "arrived before this
        // season" is exactly the cohort whose one season is over.
        List<Junior> expired = juniorRepository.findByStatusAndArrivalSeasonNumberLessThan(
                JuniorStatus.ACTIVE, seasonNumber);
        if (expired == null || expired.isEmpty()) return 0;

        Map<Long, Integer> squadSizes = squadSizesOf(expired);

        int listed = 0;
        int released = 0;
        // Room is counted down per club as its juniors are resolved, so a club with six expired
        // juniors and two places resolves exactly two rather than all six and discovers the overflow
        // later. The countdown is merge(clubId, -1, Integer::sum): a remapping function of
        // (a, b) -> a returns the OLD value, so the room never shrank and a squad of twenty-eight got
        // through until the test below caught it.
        Map<Long, Integer> roomLeft = new HashMap<>();
        for (Junior junior : expired) {
            Long clubId = junior.getTeam() == null ? null : junior.getTeam().getId();
            if (clubId == null) {
                // No club to resolve him into. Releasing is the honest outcome; a listed senior with
                // no team is a row nobody will ever select.
                releaseUnplaced(junior);
                released++;
                continue;
            }
            if (roomLeft.computeIfAbsent(clubId, id -> {
                // Bounded twice: by the senior places the club has free, and by the academy's own
                // capacity. The second bound is unreachable through intake — which stops at
                // MAX_ACTIVE_JUNIORS — but the sweep reads rows directly, and fixtures and the
                // seeder insert juniors without going through it. A pass that can resolve more
                // juniors than the academy holds is relying on an invariant it does not enforce.
                int seniorRoom = Math.max(0, PlayerContractService.MAX_SENIOR_SQUAD
                        - squadSizes.getOrDefault(id, 0));
                return Math.min(MAX_ACTIVE_JUNIORS, seniorRoom);
            }) <= 0) {
                log.info("Season {}: {} released {} — no senior places left at his club",
                        seasonNumber, junior.getName(), PlayerContractService.MAX_SENIOR_SQUAD);
                releaseUnplaced(junior);
                released++;
                continue;
            }
            roomLeft.merge(clubId, -1, Integer::sum);

            try {
                PromotionBuild build = createSeniorFromJunior(junior);
                transferService.listPlayerForTransfer(build.player.getId(), build.player.getPlayerValue());
                junior.setStatus(EXPIRED_JUNIOR_STATUS);
                junior.setPromotedPlayer(build.player);
                listed++;
            } catch (RuntimeException e) {
                // One unresolvable junior must not cost every other club's deadline. He is left
                // ACTIVE and picked up next season, which is the safe failure: a slightly late
                // listing beats a season of missing players.
                log.warn("Junior {} ({}) finished his academy season and could not be transfer-listed",
                        junior.getId(), junior.getName(), e);
            }
        }
        if (listed > 0) {
            log.info("Season {}: transfer-listed {} junior(s) whose academy season was over",
                    seasonNumber, listed);
        }
        if (released > 0) {
            log.info("Season {}: released {} expired junior(s) with no senior place at their club",
                    seasonNumber, released);
        }
        return listed;
    }

    /**
     * How many senior players each of these clubs already has, in one query.
     *
     * <p>Counted from {@code Team.players} rather than from contracts, because a graduate has no
     * contract — that is the whole reason {@code canRegister} could not see him. One grouped query for
     * the world instead of one count per club: at 14,880 clubs the per-club version would be 14,880
     * round-trips inside the season rollover.
     */
    private Map<Long, Integer> squadSizesOf(List<Junior> juniors) {
        Set<Long> clubIds = juniors.stream()
                .map(j -> j.getTeam() == null ? null : j.getTeam().getId())
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (clubIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Integer> sizes = new HashMap<>();
        for (Object[] row : playerRepository.countSquadSizesByTeamIds(clubIds)) {
            sizes.put((Long) row[0], ((Number) row[1]).intValue());
        }
        return sizes;
    }

    /** He leaves the club rather than occupy a place it cannot give him. */
    private void releaseUnplaced(Junior junior) {
        junior.setStatus(JuniorStatus.RELEASED);
        junior.setLastWeeklyDelta(0.0);
        juniorRepository.save(junior);
    }

    /**
     * The academy screen for a specific viewer.
     *
     * <p>Delegates to the boolean overload but resolves the entitlement <b>per junior</b> through
     * {@code PlusFeatureService.canSeeJunior}, which re-checks the junior's own club rather than
     * trusting the {@code teamId} in the URL. The two agree today; the per-junior form is the one that
     * stays correct if a caller ever passes a team it does not own, and it keeps the gate rule in one
     * place instead of each controller open-coding it.
     */
    @Transactional
    public JuniorAcademyStateDTO getAcademyState(Long teamId, int currentSeason, int currentWeek,
                                                 User viewer, Long viewerTeamId) {
        List<Junior> probe = juniorRepository.findVisibleByTeamId(teamId);
        boolean canSeeTalent = !probe.isEmpty()
                && probe.get(0) != null
                && plusFeatures.canSeeJunior(probe.get(0), viewer, viewerTeamId);
        return getAcademyState(teamId, currentSeason, currentWeek, canSeeTalent);
    }

    @Transactional
    public JuniorAcademyStateDTO getAcademyState(Long teamId, int currentSeason, int currentWeek,
                                                 boolean canSeeTalent) {
        Team team = teamRepository.findById(teamId).orElseThrow(() -> new RuntimeException("Team not found"));
        ensureCoachSkill(team);
        List<Junior> visible = juniorRepository.findVisibleByTeamId(teamId);

        JuniorAcademyStateDTO dto = new JuniorAcademyStateDTO();
        dto.setTeamId(team.getId());
        dto.setTeamName(team.getName());
        dto.setCurrentSeasonNumber(currentSeason);
        dto.setCurrentWeekNumber(currentWeek);
        dto.setJuniorCoachSkill(team.getJuniorCoachSkill() == null ? 0 : team.getJuniorCoachSkill());
        boolean hasCarryoverActive = visible.stream()
                .anyMatch(j -> j.getStatus() == JuniorStatus.ACTIVE && j.getArrivalSeasonNumber() < currentSeason);
        dto.setDecisionsOpen(hasCarryoverActive);

        double quality = academyQualityOf(team);
        Integer coachDevelopment = youthCoachDevelopment(team);
        dto.setAcademyQuality(AcademyQuality.round2(quality));
        dto.setAcademyQualityLabel(AcademyQuality.label(quality));
        dto.setYouthFacilityLevel(team.getStadium() == null ? null : team.getStadium().getYouthLevel());
        dto.setYouthCoachDevelopment(coachDevelopment);

        dto.setIntakeWeek(INTAKE_WEEK);
        dto.setIntakeMinCount(INTAKE_MIN);
        dto.setIntakeMaxCount(INTAKE_MAX);
        dto.setDecisionWeek(DECISION_WINDOW_FIRST_WEEK);
        dto.setMaxActiveJuniors(MAX_ACTIVE_JUNIORS);

        // One resolution of the youth coach per request rather than per junior: it is the same
        // attribute for all of them, and the report narrows against it (TalentRange).
        Integer youthCoachDevelopment = youthCoachDevelopment(team);

        visible.forEach(j -> dto.getJuniors().add(toDto(j, canSeeTalent, youthCoachDevelopment, currentSeason, currentWeek)));
        juniorRepository.findByTeamIdAndArchivedTrueOrderByArrivalSeasonNumberDescAcademySkillExactDesc(teamId)
                .forEach(j -> dto.getArchive().add(toDto(j, canSeeTalent, youthCoachDevelopment, currentSeason, currentWeek)));
        return dto;
    }

    /**
     * The YOUTH_COACH's development attribute, or null when the club has no youth coach.
     *
     * <p>Feeds {@link TalentRange}: a better youth coach does not produce better prospects, it
     * <b>narrows the report on them faster</b>. This is the attribute's first consumer in the codebase.
     */
    /**
     * How good this club's academy is, as a growth multiplier (Sprint 5.3).
     *
     * <p>Both inputs were added in earlier sprints and read by nothing: {@code Stadium.youthLevel}
     * by S4.4, whose item 5 said outright that it was "consumed in Sprint 5", and the
     * {@code YOUTH_COACH}'s development attribute by S4.3, which deferred the scouting half of its
     * effect here. A manager could buy a training ground, hire a youth coach, and watch the academy
     * produce exactly the same prospects as a clubhouse with neither.
     */
    /**
     * One week of a junior's body changing (Sprint 5.3, owner 2026-09-27).
     *
     * <p>Height taps to nothing at the graduation deadline and weight is pulled toward the junior's
     * natural weight at a rate the club's <b>gym</b> sets. The gym does not give a club a better body;
     * it lets the club correct the one it was given, and a club without one lets a heavy prospect stay
     * heavy — which is a risk the manager accepted by not spending the money.
     *
     * <p>Height accumulates on the birthday rather than weekly, because a boy's growth spurt is not
     * spread evenly across a season. Applying it weekly would make him creep upward in a straight line
     * and look synthetic.
     */
    private void developBody(Junior junior, Team team) {
        Integer arrivalAge = junior.getArrivalAge();
        if (arrivalAge == null) {
            return;
        }
        int graduationAge = graduationAge(junior);

        // Height: gains are granted on the year he turns, and taper to nothing at the deadline.
        int yearsElapsed = junior.getAge() - arrivalAge;
        if (yearsElapsed > 0) {
            double gain = JuniorDevelopment.seasonalHeightGain(yearsElapsed, graduationAge, random.nextDouble());
            if (gain > 0 && junior.getHeight() != null) {
                junior.setHeight(JuniorDevelopment.round2(junior.getHeight() + gain));
            }
        }

        // Weight: pulled toward the natural figure, at a rate the gym decides.
        if (junior.getWeight() != null && junior.getNaturalWeight() != null) {
            Integer gym = JuniorDevelopment.gymLevelOf(team == null ? null : team.getStadium());
            double change = JuniorDevelopment.weeklyWeightChange(
                    junior.getWeight(), junior.getNaturalWeight(), gym, random.nextDouble());
            if (change != 0.0) {
                junior.setWeight(JuniorDevelopment.round2(junior.getWeight() + change));
            }
        }
    }

    double academyQualityOf(Team team) {
        if (team == null) return 1.0;
        return AcademyQuality.multiplierFor(team.getStadium(), youthCoach(team));
    }

    /** The youth coach as a staff member, or null when the club has not hired one. */
    private StaffMember youthCoach(Team team) {
        if (team == null || team.getId() == null) return null;
        return staffMemberRepository.findByTeamIdAndRole(team.getId(), StaffRole.YOUTH_COACH).orElse(null);
    }

    private Integer youthCoachDevelopment(Team team) {
        if (team == null || team.getId() == null) return null;
        return staffMemberRepository.findByTeamIdAndRole(team.getId(), StaffRole.YOUTH_COACH)
                .map(StaffMember::getDevelopment)
                .orElse(null);
    }

    /**
     * How many ACTIVE juniors one academy may hold, and therefore how many it can take in and produce
     * in a season (P2-6).
     *
     * <p>This was the bare literal {@code 10} inline in the intake, and the same number was hardcoded
     * three more times in {@code static/js/pages/features/academy.js} ("{n}/10", "0/10", and the
     * refusal text). Named here so Java has one source; the frontend copies are recorded as
     * outstanding rather than silently re-hardcoded a fifth time.
     */
public static final int MAX_ACTIVE_JUNIORS = 10;

    /**
     * The age window a junior may leave the academy in (owner rule 2026-09-27): <b>15 to 20</b>.
     *
     * <p><b>This is a clamp, not a deadline.</b> It used to be the deadline: a junior who reached
     * {@link #GRADUATION_MAX_AGE} was promoted on the spot. Since the one-season rule
     * ({@link #TENURE_SEASONS}) the sweep reads the <b>arrival season</b> instead of the age, so a
     * prospect can no longer sit in an academy until twenty. The clamp stays because intake produces
     * ages 15-19 and one ageing step puts a nineteen-year-old at twenty, which is the top of a
     * sensible football age for a debut. A row carrying a nonsense age is pulled inside the window
     * rather than trusted.
     */
    public static final int GRADUATION_MIN_AGE = 15;
    public static final int GRADUATION_MAX_AGE = 20;

    // ── The intake, the tenure, the decision (owner, 2026-10-08) ───────────────────────────────
    // One coherent cycle, replacing the age window:
    //
    //   season N   week 2   INTAKE_MIN..INTAKE_MAX juniors arrive, aged INTAKE_MIN_AGE..19
    //   season N   week 3+  they train, and the talent estimate narrows week by week
    //   end of N            they age one year
    //   season N+1 week 1   the manager decides: Promote / Transfer List / Release
    //   end of N+1          anything still ACTIVE is transfer-listed automatically

    /** The week of the season the intake arrives in. One arrival, one cohort, once a season. */
    public static final int INTAKE_WEEK = 2;

    /** Fewest juniors an intake can bring, owner-specified. */
    public static final int INTAKE_MIN = 6;

    /** Most juniors an intake can bring, owner-specified. */
    public static final int INTAKE_MAX = 10;

    /** Youngest age at intake. */
    public static final int INTAKE_MIN_AGE = 15;

    /** Age spread at intake, so intake runs {@value #INTAKE_MIN_AGE}..(15 + span - 1). */
    public static final int INTAKE_AGE_SPAN = 5;

    /**
     * How long a junior stays, in seasons. <b>Exactly one</b>, owner-specified 2026-10-08.
     *
     * <p>Replaces a tenure that was {@code GRADUATION_MAX_AGE - arrivalAge} and therefore ranged
     * from one to five seasons depending on a roll. That was indefensible as a game mechanic: the
     * same intake produced nineteen-year-olds who debuted immediately and fifteen-year-olds who sat
     * in the academy for five seasons, and the only reason the long case existed was that a junior
     * had to be given enough time to reach twenty. He no longer has to reach anything.
     */
    public static final int TENURE_SEASONS = 1;

    /**
     * The weeks from the intake to the decision week: week 2 of season N through week 1 of season
     * N+1, which is 11 weeks of a 12-week season plus the decision itself.
     *
     * <p>This is the denominator of the talent narrowing. It is deliberately the <b>whole</b> tenure
     * rather than the training weeks: a manager watches a prospect during the season and puts a name
     * to him in week 1, so by the moment the decision is available the report should be tight.
     */
    public static final int TENURE_WEEKS = SeasonCalendar.WEEKS_PER_SEASON - INTAKE_WEEK + 1;

    /**
     * The week in which a manager may decide a junior's fate: <b>week 1 only</b> (owner, 2026-10-08).
     *
     * <p>Was weeks 1-2. Two weeks was a late-login allowance, and with a one-season tenure it became
     * the whole of the manager's involvement: he arrives on Tuesday of week 2 and finds the prospects
     * he never looked at already transfer-listed at the end of it. One week is the registration
     * window, and the deadline is real either way.
     *
     * <p><b>Does not apply to school closure</b>, which is a scheduled end-of-season decision about a
     * whole intake, and <b>not to the automatic transfer listing</b>, which contains no decision.
     */
    public static final int DECISION_WINDOW_FIRST_WEEK = 1;
    public static final int DECISION_WINDOW_LAST_WEEK = 1;

    /** Whether manager-initiated junior decisions are open this week. */
    public static boolean isDecisionWindow(int weekNumber) {
        return weekNumber >= DECISION_WINDOW_FIRST_WEEK && weekNumber <= DECISION_WINDOW_LAST_WEEK;
    }

    /**
     * The outcome for a junior who reaches the end of his tenure still {@code ACTIVE}: he is
     * converted to a senior player and put on the transfer market (owner, 2026-10-08).
     *
     * <p>Chosen over auto-promotion because a manager who never opened the academy page should not
     * end up with ten players he did not choose; chosen over release because a prospect the club
     * spent a season developing is worth something to somebody. He leaves on the club's terms and
     * another manager decides whether to buy him.
     */
    public static final JuniorStatus EXPIRED_JUNIOR_STATUS = JuniorStatus.TRANSFER_LISTED;

    /**
 * How many weeks of his tenure this junior has been watched for, clamped to {@link #TENURE_WEEKS}.
 *
 * <p>Both dates are placed on one flat week line ({@code season * 12 + week}) and subtracted, so a
 * prospect who arrived in week 2 of season 4 has been observed for 0 weeks in week 2, 10 in week 12,
 * and 11 by week 1 of season 5 — which is the week the manager decides, and the point the report
 * should already be tight.
 *
 * <p><b>A junior with no recorded arrival season gets zero.</b> That is a real case, not a
 * hypothetical: {@code arrivalSeasonNumber} is a primitive {@code int}, so a row written before the
 * column existed reads as {@code 0} rather than null. Left alone, season 0 sits far in the past, the
 * subtraction returns a huge number, and every one of those legacy rows would be handed a confident
 * ±1 it never earned — the exact leak this class exists to prevent. Zero is the widest report and the
 * honest one: the club does not know how long it has been watching.
 */
    static int weeksObserved(Junior junior, int currentSeason, int currentWeek) {
        if (junior == null) return 0;
        if (junior.getArrivalSeasonNumber() <= 0 || junior.getArrivalWeekNumber() <= 0) return 0;
        int arrival = junior.getArrivalSeasonNumber() * SeasonCalendar.WEEKS_PER_SEASON
                + junior.getArrivalWeekNumber();
        int now = currentSeason * SeasonCalendar.WEEKS_PER_SEASON + currentWeek;
        return Math.max(0, Math.min(TENURE_WEEKS, now - arrival));
    }

    @Transactional
    public JuniorAcademyItemDTO promoteJunior(Long juniorId, int currentSeason, int currentWeek,
                                             boolean canSeeTalent) {
        Junior junior = loadDecisionJunior(juniorId, currentSeason, currentWeek);
        PromotionBuild build = createSeniorFromJunior(junior);
        Player player = build.player;
        junior.setStatus(JuniorStatus.PROMOTED);
        junior.setPromotedPlayer(player);
        juniorRepository.save(junior);
        return toDto(junior, canSeeTalent, youthCoachDevelopment(junior.getTeam()), currentSeason, currentWeek);
    }

    @Transactional
    public JuniorPromotionResultDTO promoteJuniorWithReveal(Long juniorId, int currentSeason, int currentWeek,
                                                            boolean canSeeTalent) {
        Junior junior = loadDecisionJunior(juniorId, currentSeason, currentWeek);
        PromotionBuild build = createSeniorFromJunior(junior);
        junior.setStatus(JuniorStatus.PROMOTED);
        junior.setPromotedPlayer(build.player);
        juniorRepository.save(junior);

        JuniorPromotionResultDTO dto = new JuniorPromotionResultDTO();
        dto.setJuniorId(junior.getId());
        dto.setPlayerId(build.player.getId());
        dto.setPlayerName(build.player.getName());
        dto.setPosition(build.player.getPosition() != null ? build.player.getPosition().name() : "MID");
        dto.setTotalSkillBudget(build.totalBudget);
        dto.setRemainingAfterFill(build.remainingAfterFill);
        // The reveal. Gated on the same subscription rule as the band, because the moment a manager
        // discovers the ceiling is the paid moment.
        if (canSeeTalent) {
            dto.setTalent(TalentRange.revealExact(junior.getTalent()));
        }
        dto.setAllocatedSkills(build.allocatedSkills);
        dto.setAllocationSequence(build.allocationSequence);
        return dto;
    }

    @Transactional
    public JuniorAcademyItemDTO transferListJunior(Long juniorId, int currentSeason, int currentWeek,
                                                   boolean canSeeTalent) {
        Junior junior = loadDecisionJunior(juniorId, currentSeason, currentWeek);
        PromotionBuild build = createSeniorFromJunior(junior);
        Player player = build.player;
        transferService.listPlayerForTransfer(player.getId(), player.getPlayerValue());
        junior.setStatus(JuniorStatus.TRANSFER_LISTED);
        junior.setPromotedPlayer(player);
        juniorRepository.save(junior);
        return toDto(junior, canSeeTalent, youthCoachDevelopment(junior.getTeam()), currentSeason, currentWeek);
    }

    /**
     * Graduates a junior because the junior school closed (Sprint 5.3a).
     *
     * <p><b>Deliberately bypasses the "decisions open from next season" lock</b> that
     * {@link #loadDecisionJunior} enforces for the manager's own buttons.
     *
     * <p>That lock exists to stop a manager impulsively deciding the fate of a prospect he signed
     * three days ago. Closing a school in week 12 is not that: it is a scheduled end-of-season
     * decision about a whole intake, and the owner specified that it graduates everyone. Going through
     * the locked path made a school opened in week 1 <b>unable to graduate anybody in the same
     * season</b> — which is the only season in which the feature is used.
     *
     * <p>No {@code catch} here on purpose: a swallowed exception inside a transaction marks it
     * rollback-only and the caller then fails on commit with a confusing
     * {@code UnexpectedRollbackException} instead of a real cause.
     */
    @Transactional
    public JuniorAcademyItemDTO graduateForSchoolClosure(Long juniorId, int currentSeason, int currentWeek) {
        Junior junior = juniorRepository.findById(juniorId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "JUNIOR_NOT_FOUND", "Junior not found."));
        if (junior.getStatus() != JuniorStatus.ACTIVE) {
            return toDto(junior, false, null, currentSeason, currentWeek);
        }
        PromotionBuild build = createSeniorFromJunior(junior);
        Player player = build.player;
        transferService.listPlayerForTransfer(player.getId(), player.getPlayerValue());
        junior.setStatus(JuniorStatus.TRANSFER_LISTED);
        junior.setPromotedPlayer(player);
        juniorRepository.save(junior);
        return toDto(junior, false, null, currentSeason, currentWeek);
    }

    @Transactional
    public JuniorAcademyItemDTO releaseJunior(Long juniorId, int currentSeason, int currentWeek,
                                             boolean canSeeTalent) {
        Junior junior = loadDecisionJunior(juniorId, currentSeason, currentWeek);
        junior.setStatus(JuniorStatus.RELEASED);
        junior.setLastWeeklyDelta(0.0);
        juniorRepository.save(junior);
        return toDto(junior, canSeeTalent, youthCoachDevelopment(junior.getTeam()), currentSeason, currentWeek);
    }

    /**
     * The age this junior graduates at, inside the 15-20 window.
     *
     * <p>Clamped rather than trusted, because the window is the rule and a bad age in the database
     * should not be how the rule gets broken. A junior who arrived as a fourteen-year-old graduates
     * at fifteen, not at fourteen.
     */
    private int graduationAge(Junior junior) {
        int age = junior == null ? GRADUATION_MIN_AGE : junior.getAge();
        return Math.max(GRADUATION_MIN_AGE, Math.min(GRADUATION_MAX_AGE, age));
    }

    private Junior loadDecisionJunior(Long juniorId, int currentSeason, int currentWeek) {
        Junior junior = juniorRepository.findById(juniorId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "JUNIOR_NOT_FOUND", "Junior not found."));
        if (junior.getStatus() != JuniorStatus.ACTIVE) {
            throw new ApiException(HttpStatus.CONFLICT, "JUNIOR_INACTIVE", "Junior is no longer active in the academy.");
        }
        if (junior.getArrivalSeasonNumber() >= currentSeason) {
            throw new ApiException(HttpStatus.CONFLICT, "DECISION_LOCKED",
                    "This junior is too new. Decisions open from next season.");
        }
        if (!isDecisionWindow(currentWeek)) {
            // Spelled out in words rather than interpolated from two constants. With the window now a
            // single week, "weeks 1-1" is what that concatenation produces, and a refusal message is
            // the one piece of text a manager reads when he is trying to work out what to do next.
            throw new ApiException(HttpStatus.CONFLICT, "DECISION_WINDOW_CLOSED",
                    "Junior decisions are open in week " + DECISION_WINDOW_FIRST_WEEK
                            + " only; it is week " + currentWeek
                            + ". A prospect cannot be signed into the first team mid-season, and he is "
                            + "transfer-listed when his academy season ends whether you are ready or not.");
        }
        return junior;
    }

    private PromotionBuild createSeniorFromJunior(Junior junior) {
        Player player = new Player();
        player.setName(junior.getName());
        // His own age, not age+1. A junior's age already moves once a year at the season boundary,
        // so adding a year here double-counted it and pushed every graduate a season too old. That
        // double-count is why promotion used to be floored at seventeen: the age was not tracking
        // anything, so a floor was propping it up.
        player.setAge(graduationAge(junior));
        player.setTalent(junior.getTalent());
        player.setTeam(junior.getTeam());
        // Carry the body and the temperament across (Sprint 5.3). Height and weight were previously
        // re-rolled at promotion, so a club that spent two years shaping a prospect got a different
        // body out of the academy than the one it developed.
        player.setHeight(junior.getHeight() != null ? junior.getHeight() : round2(1.72 + random.nextDouble() * 0.24));
        player.setWeight(junior.getWeight() != null ? junior.getWeight() : 65 + random.nextInt(20));
        player.setPersonality(junior.getPersonality());
        player.setPreferredFoot(junior.getPreferredFoot());
        player.setForm(round2(4.5 + random.nextDouble() * 3.2));
        player.setEarnings(500 + random.nextInt(2500));

        // The position he was signed for. Legacy rows predate the column and are rolled here, once,
        // so a pre-existing academy is not left with players who have no position at all.
        Position position = junior.getPosition() != null ? junior.getPosition() : rollPosition();
        player.setPosition(position);
        player.setSquadNumber(squadNumberAssigner.nextNumberForTeam(junior.getTeam(), position));

        int budget = Math.max(6, (int) Math.round(junior.getAcademySkillExact() * 3 + (random.nextInt(9) - 4)));
        SkillBuild skillBuild = createSkillsetFromBudget(budget, position == Position.GK);
        Skills skills = skillBuild.skills;
        player.setSkills(skills);
        // After the position and the skills, both of which it is derived from. This was a hardcoded
        // 50, so a graduate's OVR depended on his class rather than on what he can do.
        player.setRating(player.careerRating());

        double value = estimateJuniorMarketValue(junior, position, skills);
        player.setPlayerValue(value);

        player = playerRepository.save(player);

        Team team = junior.getTeam();
        if (team != null) {
            team.getPlayers().add(player);
            teamRepository.save(team);
            squadNumberAssigner.assignMissingNumbers(team);
        }
        PromotionBuild build = new PromotionBuild();
        build.player = player;
        build.totalBudget = budget;
        build.remainingAfterFill = skillBuild.remaining;
        build.allocatedSkills = skillBuild.allocatedSkills;
        build.allocationSequence = skillBuild.allocationSequence;
        return build;
    }

    private SkillBuild createSkillsetFromBudget(int budget, boolean goalkeeper) {
        Map<SkillName, Integer> base = new EnumMap<>(SkillName.class);
        for (SkillName s : List.of(SkillName.STAMINA, SkillName.GOALKEEPER, SkillName.DEFENDER, SkillName.PACE,
                SkillName.TECHNIQUE, SkillName.PLAYMAKER, SkillName.PASSING, SkillName.STRIKER)) {
            base.put(s, 0);
        }

        List<String> allocationSequence = new ArrayList<>();
        int remaining = budget;
        if (goalkeeper) {
            base.put(SkillName.GOALKEEPER, 5);
            remaining = Math.max(0, remaining - 5);
            for (int i = 0; i < 5; i++) {
                allocationSequence.add("goalkeeper");
            }
        }

        List<SkillName> pool = new ArrayList<>(List.of(SkillName.STAMINA, SkillName.GOALKEEPER, SkillName.DEFENDER, SkillName.PACE,
                SkillName.TECHNIQUE, SkillName.PLAYMAKER, SkillName.PASSING, SkillName.STRIKER));

        int safety = 0;
        while (remaining > 0 && safety < 5000) {
            safety++;
            SkillName target = pool.get(random.nextInt(pool.size()));
            int current = base.get(target);
            if (current >= 10) continue;
            base.put(target, current + 1);
            allocationSequence.add(toRevealKey(target));
            remaining--;
        }

        Skills skills = new Skills();
        skills.setFatigue(0);
        for (SkillName s : base.keySet()) {
            int intPart = base.get(s);
            double exact = Math.min(20.99, intPart + random.nextDouble() * 0.99);
            skills.setSkill(s, intPart);
            skills.setExact(s, exact);
        }
        skills.syncVisibleFromExact();
        SkillBuild build = new SkillBuild();
        build.skills = skills;
        build.remaining = remaining;
        build.allocatedSkills = new LinkedHashMap<>();
        build.allocatedSkills.put("stamina", base.get(SkillName.STAMINA));
        build.allocatedSkills.put("goalkeeper", base.get(SkillName.GOALKEEPER));
        build.allocatedSkills.put("defending", base.get(SkillName.DEFENDER));
        build.allocatedSkills.put("pace", base.get(SkillName.PACE));
        build.allocatedSkills.put("technique", base.get(SkillName.TECHNIQUE));
        build.allocatedSkills.put("playmaker", base.get(SkillName.PLAYMAKER));
        build.allocatedSkills.put("passing", base.get(SkillName.PASSING));
        build.allocatedSkills.put("shooting", base.get(SkillName.STRIKER));
        build.allocationSequence = allocationSequence;
        return build;
    }

    private double estimateJuniorMarketValue(Junior junior, Position position, Skills skills) {
        double academySkill = Math.max(1.0, junior.getAcademySkillExact());
        double talent = Math.max(1.0, junior.getTalent());
        double visibleSkillAverage = (
                skills.getStamina()
                        + skills.getGoalkeeper()
                        + skills.getDefender()
                        + skills.getPace()
                        + skills.getTechnique()
                        + skills.getPlaymaker()
                        + skills.getPassing()
                        + skills.getStriker()
        ) / 8.0;

        double baseValue = 18_000
                + (academySkill * 4_200)
                + (talent * 3_500)
                + (visibleSkillAverage * 2_250);

        if (junior.getAge() <= 17) {
            baseValue *= 1.18;
        } else if (junior.getAge() >= 19) {
            baseValue *= 0.94;
        }

        if (position == Position.ATT) {
            baseValue *= 1.12;
        } else if (position == Position.GK) {
            baseValue *= 0.92;
        }

        double floor = 22_500 + (talent * 2_250);
        double swing = 0.92 + random.nextDouble() * 0.22;
        return round2(Math.max(floor, baseValue * swing));
    }

    private String toRevealKey(SkillName skillName) {
        return switch (skillName) {
            case STAMINA -> "stamina";
            case GOALKEEPER -> "goalkeeper";
            case DEFENDER -> "defending";
            case PACE -> "pace";
            case TECHNIQUE -> "technique";
            case PLAYMAKER -> "playmaker";
            case PASSING -> "passing";
            case STRIKER -> "shooting";
            case FATIGUE -> "stamina";
        };
    }

    /**
     * Gives every pre-existing junior the position he is graduating into (Sprint 5.3).
     *
     * <p>Without this a legacy row has a null position and promotion re-rolls one, quietly restoring the
     * exact behaviour the field was added to remove — but only for saves that predate it.
     *
     * <p>Rolling here rather than at graduation is <b>not</b> a distribution change: the same
     * probabilities over the same juniors produce the same number of goalkeepers and the same spread of
     * outfielders. Only the moment at which a position becomes knowable moves, which is the point.
     */
    @Transactional
    public int assignMissingPositions() {
        List<Junior> legacy = juniorRepository.findByPositionIsNull();
        if (legacy.isEmpty()) return 0;
        for (Junior junior : legacy) {
            junior.setPosition(rollPosition());
        }
        juniorRepository.saveAll(legacy);
        return legacy.size();
    }

    /**
     * Everything about a prospect that is decided on the day he signs (Sprint 5.3).
     *
     * <p>One method for both intake paths, deliberately. The season intake and the legacy seed had
     * already drifted apart once over the arrival-age and range-width fields, and two copies of "what a
     * new junior looks like" is two places for the next divergence to hide.
     */
    private void rollBodyAndTemperament(Junior junior) {
        junior.setWorkRate(JuniorDevelopment.rollWorkRate(random.nextDouble()));
        junior.setPersonality(JuniorDevelopment.rollPersonality(random.nextDouble()));
        junior.setPreferredFoot(JuniorDevelopment.rollFoot(random.nextDouble()));
        double height = JuniorDevelopment.rollHeight(junior.getPosition(), random.nextDouble());
        junior.setHeight(JuniorDevelopment.round2(height));
        double[] body = JuniorDevelopment.rollBody(height, random.nextDouble());
        // He arrives carrying his natural weight, so the gym has nothing to correct on day one.
        junior.setNaturalWeight(JuniorDevelopment.round2(body[0]));
        junior.setWeight(JuniorDevelopment.round2(body[0]));
    }

    private Position rollPosition() {
        int roll = random.nextInt(100);
        if (roll < 12) return Position.GK;
        int outfield = random.nextInt(3);
        if (outfield == 0) return Position.DEF;
        if (outfield == 1) return Position.MID;
        return Position.ATT;
    }

    private double computeWeeklyDelta(Junior junior, int coachSkill, double academyQuality) {
        double coachFactor = 0.55 + (coachSkill / 100.0) * 0.95;
        double talentFactor = mapTalentFactor(junior.getTalent());
        double levelFactor = Math.max(0.10, 1.0 - (junior.getAcademySkillExact() / 21.0) * 0.82);
        double randomFactor = 0.82 + random.nextDouble() * 0.42;
        // The academy setup: the youth facility and the youth coach (Sprint 5.3). It scales the *rate*,
        // never the intake roll, so graduation mechanics and the talent distribution are untouched --
        // what a better academy changes is the level a graduate reaches, which is the point of paying
        // for one. 1.0 for a club that has recorded neither.
        // Effort and temperament (Sprint 5.3), multiplied rather than added so a hard-working
        // Temperamental and a laid-back Professional land in the same place: neither rescues the other.
        double characterFactor = JuniorDevelopment.growthFactor(junior.getWorkRate(), junior.getPersonality());
        // And the weekly wander that makes a difficult player difficult week to week.
        double characterWander = 1.0 + (random.nextDouble() * 2.0 - 1.0)
                * JuniorDevelopment.growthVariance(junior.getPersonality());
        double base = 0.24 * coachFactor * talentFactor * levelFactor * randomFactor * academyQuality
                * characterFactor * characterWander;

        // Small negative swing to simulate uncertain evaluation periods.
        if (random.nextDouble() < 0.08) {
            return -1.0 * (0.02 + random.nextDouble() * 0.09);
        }
        return Math.max(0.01, base);
    }

    private double mapTalentFactor(double rawTalent) {
        if (rawTalent <= 1.0) return 0.54;
        if (rawTalent <= 2.0) return 0.66;
        if (rawTalent <= 3.0) return 0.77;
        if (rawTalent <= 4.0) return 0.88;
        if (rawTalent <= 5.0) return 0.95;
        if (rawTalent <= 6.0) return 1.00;
        if (rawTalent <= 7.0) return 1.10;
        if (rawTalent <= 8.0) return 1.20;
        if (rawTalent <= 9.0) return 1.38;
        return 1.56;
    }

    private void ensureCoachSkill(Team team) {
        if (team.getJuniorCoachSkill() == null || team.getJuniorCoachSkill() < 1 || team.getJuniorCoachSkill() > 100) {
            team.setJuniorCoachSkill(40 + random.nextInt(46)); // 40-85
            teamRepository.save(team);
        }
    }

    /**
     * How many juniors this intake brings: uniform over {@value #INTAKE_MIN}..{@value #INTAKE_MAX}.
     *
     * <p>Was a bell over 1..10 (weights {@code {3,6,9,12,14,14,12,9,6,3}}). The owner moved the
     * floor to six (2026-10-08) and asked for a flat band, so the shape is gone rather than
     * rescaled — a bell clipped into 6..10 would still cluster on 7-8, and the number a manager reads
     * on the screen is now a rule he can quote.
     */
    private int rollIntakeCount() {
        return INTAKE_MIN + random.nextInt(INTAKE_MAX - INTAKE_MIN + 1);
    }

    private double rollTalent() {
        int[] weights = {1, 2, 8, 12, 16, 18, 17, 14, 3, 1}; // 1..10
        int total = Arrays.stream(weights).sum();
        int r = random.nextInt(total);
        int acc = 0;
        for (int i = 0; i < weights.length; i++) {
            acc += weights[i];
            if (r < acc) return i + 1;
        }
        return 6.0;
    }

    private double rollInitialAcademySkill() {
        int whole = random.nextInt(16); // 0..15
        return whole + random.nextDouble() * 0.99;
    }

    @Transactional
    public void seedInitialJuniorsForTeam(Long teamId, int seasonNumber) {
        Team team = teamRepository.findById(teamId).orElseThrow(() -> new RuntimeException("Team not found"));
        ensureCoachSkill(team);
        List<Junior> existing = juniorRepository.findByTeamIdOrderByAcademySkillExactDesc(teamId);
        if (!existing.isEmpty()) return;
        int count = rollIntakeCount();
        List<Junior> seed = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Junior j = new Junior();
            j.setName(NameGenerator.fullName());
            j.setAge(INTAKE_MIN_AGE + random.nextInt(INTAKE_AGE_SPAN)); // 15-19
            j.setTalent(rollTalent());
            j.setAcademySkillExact(round2(5 + random.nextDouble() * 9.99)); // mid range for test visibility
            j.setAcademySkill((int) Math.floor(j.getAcademySkillExact()));
            j.setLastWeeklyDelta(0.0);
            j.setArrivalSeasonNumber(Math.max(0, seasonNumber - 1)); // eligible in current season week 1 as seed data
            j.setArrivalWeekNumber(INTAKE_WEEK);
            // The two Sprint 5.2 fields. Without them a seeded junior reports the maximum uncertainty
            // forever, which looks like a bug rather than a missing column.
            j.setArrivalAge(j.getAge());
            j.setTalentRangeHalfWidth(TalentRange.intakeHalfWidth(random.nextInt(4)));
            j.setPosition(rollPosition());
            rollBodyAndTemperament(j);
            j.setStatus(JuniorStatus.ACTIVE);
            j.setArchived(false);
            j.setTeam(team);
            seed.add(j);
        }
        juniorRepository.saveAll(seed);
        log.info("Seeded {} initial juniors for team {}", seed.size(), team.getName());
    }

    private void archiveResolvedJuniorsBeforeSeason(Long teamId, int seasonNumber) {
        List<Junior> all = juniorRepository.findByTeamIdOrderByAcademySkillExactDesc(teamId);
        boolean changed = false;
        for (Junior junior : all) {
            if (junior.getArrivalSeasonNumber() < seasonNumber
                    && junior.getStatus() != JuniorStatus.ACTIVE
                    && !Boolean.TRUE.equals(junior.getArchived())) {
                junior.setArchived(true);
                changed = true;
            }
        }
        if (changed) {
            juniorRepository.saveAll(all);
        }
    }

    /**
     * Builds the manager's view of a junior, with talent reported as a <b>band</b> rather than a number.
     *
     * <p>Three rules, all from the owner (2026-09-27), and they are not interchangeable:
     * <ol>
     *   <li>a viewer without PLUS sees <b>nothing</b> — both bounds and the exact value are null;</li>
     *   <li>a viewer with PLUS sees the <b>band</b>, which narrows with observation;</li>
     *   <li>the <b>exact</b> value appears only once he has been promoted.</li>
     * </ol>
     *
     * <p>The exact value is withheld from an active junior even from a paying viewer on purpose. The
     * academy exists to make a manager watch a player; printing the ceiling on arrival removes the
     * only thing the feature was for.
     */
    private JuniorAcademyItemDTO toDto(Junior j, boolean canSeeTalent, Integer youthCoachDevelopment,
                                       int currentSeason, int currentWeek) {
        JuniorAcademyItemDTO dto = new JuniorAcademyItemDTO();
        dto.setId(j.getId());
        dto.setName(j.getName());
        dto.setAge(j.getAge());
        dto.setAcademySkill(j.getAcademySkill());
        dto.setAcademySkillExact(round2(j.getAcademySkillExact()));
        dto.setLastWeeklyDelta(round2(j.getLastWeeklyDelta()));
        dto.setStatus(j.getStatus() != null ? j.getStatus().name() : JuniorStatus.ACTIVE.name());
        dto.setArrivalSeasonNumber(j.getArrivalSeasonNumber());
        dto.setArrivalWeekNumber(j.getArrivalWeekNumber());
        dto.setArrivalAge(j.getArrivalAge());
        dto.setPosition(j.getPosition() != null ? j.getPosition().name() : null);
        dto.setWorkRate(j.getWorkRate());
        dto.setPersonality(j.getPersonality() != null ? j.getPersonality().name() : null);
        dto.setPersonalityVariance(j.getPersonality() != null ? j.getPersonality().variance() : 0.0);
        dto.setPreferredFoot(j.getPreferredFoot() != null ? j.getPreferredFoot().name() : null);
        dto.setHeight(j.getHeight());
        dto.setWeight(j.getWeight());
        dto.setNaturalWeight(j.getNaturalWeight());
        dto.setPromotedPlayerId(j.getPromotedPlayer() != null ? j.getPromotedPlayer().getId() : null);
        dto.setArchived(Boolean.TRUE.equals(j.getArchived()));

        if (canSeeTalent) {
            boolean revealed = j.getStatus() != null
                    && (j.getStatus() == JuniorStatus.PROMOTED || j.getStatus() == JuniorStatus.TRANSFER_LISTED);
            if (revealed) {
                dto.setTalentExact(round2(j.getTalent()));
            }
            // Progress is weeks of the tenure, so the band tightens week by week while the season
            // runs and is already at the +/-1 floor on the morning the manager is allowed to decide.
            double halfWidth = TalentRange.currentHalfWidth(j,
                    weeksObserved(j, currentSeason, currentWeek), TENURE_WEEKS, youthCoachDevelopment);
            dto.setTalentRangeHalfWidth(halfWidth);
            double[] bounds = TalentRange.bounds(j.getTalent(), halfWidth);
            if (bounds != null) {
                dto.setTalentLow(bounds[0]);
                dto.setTalentHigh(bounds[1]);
            }
        }
        return dto;
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static class SkillBuild {
        private Skills skills;
        private int remaining;
        private Map<String, Integer> allocatedSkills;
        private List<String> allocationSequence;
    }

    private static class PromotionBuild {
        private Player player;
        private int totalBudget;
        private int remainingAfterFill;
        private Map<String, Integer> allocatedSkills;
        private List<String> allocationSequence;
    }
}
