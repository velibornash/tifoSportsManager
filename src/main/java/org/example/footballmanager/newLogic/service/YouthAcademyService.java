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
        if (weekNumber != 2) return;

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

            long alreadyGenerated = juniorRepository.countByTeamIdAndArrivalSeasonNumberAndArrivalWeekNumber(team.getId(), seasonNumber, 2);
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
                j.setAge(15 + random.nextInt(5));
                j.setTalent(rollTalent());
                double initialSkill = rollInitialAcademySkill();
                j.setAcademySkillExact(round2(initialSkill));
                j.setAcademySkill((int) Math.floor(j.getAcademySkillExact()));
                j.setLastWeeklyDelta(0.0);
                j.setArrivalSeasonNumber(seasonNumber);
                j.setArrivalWeekNumber(2);
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
     * Promotes every junior who has run out of graduation window.
     *
     * <p><b>This does not age anyone.</b> Ageing already happens once a year at the season boundary
     * in {@code SeasonService.agePlayersAndJuniorsOneYear()}, and an earlier version of this method
     * aged juniors as well, which would have made every player a year older per season twice over.
     * The bug it caused is the one worth recording: because a junior's age moved at the season
     * boundary, adding a year at promotion double-counted it, which is why graduation had to be
     * hard-coded to a floor of seventeen to compensate.
     *
     * <p>What was genuinely missing is what happens when the window closes. Nothing acted on a junior
     * reaching twenty, so he sat in the academy indefinitely — a twenty-four-year-old "prospect". The
     * window is the rule, and a manager who has had all five years to decide has had the decision.
     *
     * <p><b>And what happens when the club has no room for him (P2-6).</b> Graduation used to be
     * unconditional: every ACTIVE junior aged twenty in the entire world was turned into a senior
     * {@code Player} in one loop, with no check that the club could field him. {@code canRegister}
     * could not stop it either, because graduation creates no {@code PlayerContract} and
     * {@code canRegister} counts contracts — so a graduate was invisible to the 25-senior cap and then
     * drew a wage for a season before the backfill noticed. A club's academy was therefore an
     * unlimited source of free players.
     *
     * <p>So the cap is the squad, not a number: a graduate is promoted only while his club has room,
     * and otherwise he is <b>released</b>. That is the football answer and it now has teeth in both
     * directions — a club that refuses to let players go fills its own squad and blocks its own
     * academy, which is what P2-7's retirement mechanic is for.
     */
    @Transactional
    public int promoteJuniorsPastWindow(int seasonNumber, int seasonNumberNow) {
        List<Junior> overAge = juniorRepository.findByStatusAndAgeGreaterThanEqual(
                JuniorStatus.ACTIVE, GRADUATION_MAX_AGE);
        if (overAge == null || overAge.isEmpty()) return 0;

        Map<Long, Integer> squadSizes = squadSizesOf(overAge);

        int promoted = 0;
        int released = 0;
        // Room is counted down per club as its graduates are made, so a club with five due juniors and
        // two places promotes exactly two rather than all five and discovers the overflow later. The
        // countdown is merge(clubId, -1, Integer::sum): a remapping function of (a, b) -> a returns
        // the OLD value, so the room never shrank and a squad of twenty-eight got through until the
        // test below caught it.
        Map<Long, Integer> roomLeft = new HashMap<>();
        for (Junior junior : overAge) {
            Long clubId = junior.getTeam() == null ? null : junior.getTeam().getId();
            if (clubId == null) {
                // No club to graduate into. Releasing is the honest outcome; a "senior" player with
                // no team is a row nobody will ever select.
                releaseUnplaced(junior);
                released++;
                continue;
            }
            if (roomLeft.computeIfAbsent(clubId, id -> {
                // Bounded twice: by the senior places the club has free, and by the academy's own
                // capacity. The second bound is unreachable through intake — which stops at
                // MAX_ACTIVE_JUNIORS — but the sweep reads rows directly, and fixtures and the
                // seeder insert juniors without going through it. A graduation pass that can promote
                // more than the academy holds is relying on an invariant it does not enforce.
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
                junior.setStatus(JuniorStatus.PROMOTED);
                junior.setPromotedPlayer(build.player);
                promoted++;
            } catch (RuntimeException e) {
                // One unpromotable junior must not cost every other club's deadline. He is left
                // ACTIVE and picked up next season, which is the safe failure: a slightly late
                // promotion beats a season of missing players.
                log.warn("Junior {} ({}) reached the graduation age and could not be promoted",
                        junior.getId(), junior.getName(), e);
            }
        }
        if (promoted > 0) {
            log.info("Season {}: promoted {} junior(s) who reached the age of {}",
                    seasonNumber, promoted, GRADUATION_MAX_AGE);
        }
        if (released > 0) {
            log.info("Season {}: released {} junior(s) with no senior place at their club",
                    seasonNumber, released);
        }
        return promoted;
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

        // One resolution of the youth coach per request rather than per junior: it is the same
        // attribute for all of them, and the report narrows against it (TalentRange).
        Integer youthCoachDevelopment = youthCoachDevelopment(team);

        visible.forEach(j -> dto.getJuniors().add(toDto(j, canSeeTalent, youthCoachDevelopment)));
        juniorRepository.findByTeamIdAndArchivedTrueOrderByArrivalSeasonNumberDescAcademySkillExactDesc(teamId)
                .forEach(j -> dto.getArchive().add(toDto(j, canSeeTalent, youthCoachDevelopment)));
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
     * <p>These are not decoration. The upper bound is a deadline — a junior who reaches it is
     * promoted whether the manager is ready or not, because a twenty-one-year-old in a youth academy
     * is a squad player being described as a prospect, and the whole point of the window is that it
     * closes. The lower bound exists because intake can produce a fifteen-year-old, and a manager
     * who wants to debut him immediately should be able to.
     */
    public static final int GRADUATION_MIN_AGE = 15;
    public static final int GRADUATION_MAX_AGE = 20;

    /**
     * The weeks in which a manager may decide a junior's fate (owner, 2026-09-27).
     *
     * <p>Promoting a youth player is a <b>registration</b> decision, not a match-day one: real football
     * submits squad lists at the start of a season, and a manager does not sign a seventeen-year-old in
     * week nine because he had a good month. Before this the window ran from the start of the following
     * season to the end of it — effectively "always available".
     *
     * <p>Two weeks rather than one rigid week, so that logging in slightly late does not cost a
     * prospect a whole season. It is still a window, not a standing permission.
     *
     * <p><b>Does not apply to the age ceiling.</b> A junior who reaches
     * {@link #GRADUATION_MAX_AGE} is promoted whether the manager is ready or not, in whatever week
     * that falls — a twenty-one-year-old in an academy is a squad player described as a prospect. That
     * path contains no decision, so there is nothing for the window to protect against. Nor does it
     * apply to school closure, which is a scheduled end-of-season decision.
     */
    public static final int DECISION_WINDOW_FIRST_WEEK = 1;
    public static final int DECISION_WINDOW_LAST_WEEK = 2;

    /** Whether manager-initiated junior decisions are open this week. */
    public static boolean isDecisionWindow(int weekNumber) {
        return weekNumber >= DECISION_WINDOW_FIRST_WEEK && weekNumber <= DECISION_WINDOW_LAST_WEEK;
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
        return toDto(junior, canSeeTalent, youthCoachDevelopment(junior.getTeam()));
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
        return toDto(junior, canSeeTalent, youthCoachDevelopment(junior.getTeam()));
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
            return toDto(junior, false, null);
        }
        PromotionBuild build = createSeniorFromJunior(junior);
        Player player = build.player;
        transferService.listPlayerForTransfer(player.getId(), player.getPlayerValue());
        junior.setStatus(JuniorStatus.TRANSFER_LISTED);
        junior.setPromotedPlayer(player);
        juniorRepository.save(junior);
        return toDto(junior, false, null);
    }

    @Transactional
    public JuniorAcademyItemDTO releaseJunior(Long juniorId, int currentSeason, int currentWeek,
                                             boolean canSeeTalent) {
        Junior junior = loadDecisionJunior(juniorId, currentSeason, currentWeek);
        junior.setStatus(JuniorStatus.RELEASED);
        junior.setLastWeeklyDelta(0.0);
        juniorRepository.save(junior);
        return toDto(junior, canSeeTalent, youthCoachDevelopment(junior.getTeam()));
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
            throw new ApiException(HttpStatus.CONFLICT, "DECISION_WINDOW_CLOSED",
                    "Junior decisions are open in weeks " + DECISION_WINDOW_FIRST_WEEK + "-"
                            + DECISION_WINDOW_LAST_WEEK + " only; it is week " + currentWeek
                            + ". A prospect cannot be signed into the first team mid-season.");
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

    private int rollIntakeCount() {
        int[] weights = {3, 6, 9, 12, 14, 14, 12, 9, 6, 3}; // 1..10
        int total = Arrays.stream(weights).sum();
        int r = random.nextInt(total);
        int acc = 0;
        for (int i = 0; i < weights.length; i++) {
            acc += weights[i];
            if (r < acc) return i + 1;
        }
        return 5;
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
        int count = 4 + random.nextInt(3); // 4-6 for immediate testing
        List<Junior> seed = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Junior j = new Junior();
            j.setName(NameGenerator.fullName());
            j.setAge(15 + random.nextInt(5)); // 15-19
            j.setTalent(rollTalent());
            j.setAcademySkillExact(round2(5 + random.nextDouble() * 9.99)); // mid range for test visibility
            j.setAcademySkill((int) Math.floor(j.getAcademySkillExact()));
            j.setLastWeeklyDelta(0.0);
            j.setArrivalSeasonNumber(Math.max(0, seasonNumber - 1)); // eligible in current season week 1 as seed data
            j.setArrivalWeekNumber(2);
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
    private JuniorAcademyItemDTO toDto(Junior j, boolean canSeeTalent, Integer youthCoachDevelopment) {
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
            // The observation horizon is the graduation DEADLINE, not this junior's own graduation age.
            // graduationAge() clamps to the current age, so passing it here would make the span
            // (graduationAge - arrivalAge) equal the elapsed time for every active junior — progress
            // would be 1.0 for all of them, every report would sit at the +/-1 floor from arrival, and
            // the whole narrowing mechanic would be inert. A19-year-old has one more season of
            // observation left, and his band should say so.
            double halfWidth = TalentRange.currentHalfWidth(
                    j, j.getAge(), GRADUATION_MAX_AGE, youthCoachDevelopment);
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
