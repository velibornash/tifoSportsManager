package org.example.footballmanager.newLogic.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.newLogic.dto.training.*;
import org.example.footballmanager.newLogic.model.*;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.example.footballmanager.newLogic.repository.TeamTrainingSetupRepository;
import org.example.footballmanager.newLogic.repository.TrainingWeekReportRepository;
import org.example.footballmanager.newLogic.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class TrainingProgressionService {

    private final TeamRepository teamRepository;
    private final PlayerRepository playerRepository;
    private final TeamTrainingSetupRepository teamTrainingSetupRepository;
    private final TrainingWeekReportRepository trainingWeekReportRepository;
    private final TrainingPercentService trainingPercentService;
    private final TrainingFocusService focusService;
    private final TrainingIntensityService intensityService;
    private final SeasonService seasonService;
    private final ObjectMapper objectMapper;
    private final Random random = new Random();

    @Transactional
    public TrainingSetupDTO getCurrentSetup(Long teamId) {
        GameClock clock = seasonService.getOrCreateClock();
        int season = clock.getCurrentSeason() == null ? 1 : clock.getCurrentSeason();
        int week = clock.getCurrentWeek() == null ? 1 : clock.getCurrentWeek();
        TeamTrainingSetup setup = teamTrainingSetupRepository
                .findByTeamIdAndSeasonNumberAndWeekNumber(teamId, season, week)
                .orElseGet(() -> createDefaultSetup(teamId, season, week));
        return toSetupDto(setup);
    }

    @Transactional
    public TrainingSetupDTO saveCurrentSetup(Long teamId, TrainingSetupDTO request) {
        GameClock clock = seasonService.getOrCreateClock();
        int season = clock.getCurrentSeason() == null ? 1 : clock.getCurrentSeason();
        int week = clock.getCurrentWeek() == null ? 1 : clock.getCurrentWeek();
        TeamTrainingSetup setup = teamTrainingSetupRepository
                .findByTeamIdAndSeasonNumberAndWeekNumber(teamId, season, week)
                .orElseGet(() -> createDefaultSetup(teamId, season, week));

        Map<String, String> groupSkills = request.getGroupSkills() == null ? Map.of() : request.getGroupSkills();
        setup.setDtSkillGk(normalizeDtSkill(groupSkills.getOrDefault("GK", setup.getDtSkillGk()), "GK"));
        setup.setDtSkillDef(normalizeDtSkill(groupSkills.getOrDefault("DEF", setup.getDtSkillDef()), "DEF"));
        setup.setDtSkillMid(normalizeDtSkill(groupSkills.getOrDefault("MID", setup.getDtSkillMid()), "MID"));
        setup.setDtSkillAtt(normalizeDtSkill(groupSkills.getOrDefault("ATT", setup.getDtSkillAtt()), "ATT"));
        try {
            List<AdvancedAssignmentDTO> assignments = request.getAdvancedAssignments() == null
                    ? List.of()
                    : request.getAdvancedAssignments().stream().limit(10).toList();
            setup.setAdvancedAssignmentsJson(objectMapper.writeValueAsString(assignments));
        } catch (Exception ignored) {}
        setup.setUpdatedAt(LocalDateTime.now());
        setup = teamTrainingSetupRepository.save(setup);
        return toSetupDto(setup);
    }

    /**
     * Applies one week of training growth to every player in the squad and writes the weekly report.
     *
     * <p>Idempotency: a team gets exactly one training run per (season, week). Calling this twice
     * used to apply the growth twice and silently overwrite the report, which made the free
     * "Run Weekly Training" button in the UI an unlimited skill-point exploit. The existing report
     * row is the natural lock - it is keyed on (team, season, week) and is only written at the end
     * of a successful run.
     *
     * @param force re-run and overwrite. Intended for an admin correcting a misconfigured week.
     * @throws ApiException {@code TRAINING_ALREADY_RUN} when the week has already been trained
     */
    @Transactional
    public TrainingWeekReportDTO runWeeklyTraining(Long teamId) {
        return runWeeklyTraining(teamId, false);
    }

    /**
     * Week-advance entry point. Unlike {@link #runWeeklyTraining(Long)} this never throws when the
     * week has already been trained - it returns the stored report instead.
     *
     * <p>This matters because the season flow can legitimately reach the advance step after a
     * manager has already pressed "Run Weekly Training" for the same week. Treating that as an
     * error would make the week impossible to advance. Growth must be applied at most once per
     * week, and the existing report is the record of that.
     */
    @Transactional
    public Optional<TrainingWeekReportDTO> runWeeklyTrainingIfDue(Long teamId) {
        GameClock clock = seasonService.getOrCreateClock();
        int season = clock.getCurrentSeason() == null ? 1 : clock.getCurrentSeason();
        int week = clock.getCurrentWeek() == null ? 1 : clock.getCurrentWeek();

        return trainingWeekReportRepository
                .findByTeamIdAndSeasonNumberAndWeekNumber(teamId, season, week)
                .map(TrainingWeekReport::getReportJson)
                .map(json -> {
                    try {
                        return objectMapper.readValue(json, TrainingWeekReportDTO.class);
                    } catch (Exception ex) {
                        log.warn("Stored training report for team {} (season {}, week {}) is unreadable: {}",
                                teamId, season, week, ex.toString());
                        return null;
                    }
                })
                .map(Optional::of)
                .orElseGet(() -> Optional.of(runWeeklyTraining(teamId, false)));
    }

    @Transactional
    public TrainingWeekReportDTO runWeeklyTraining(Long teamId, boolean force) {
        GameClock clock = seasonService.getOrCreateClock();
        int season = clock.getCurrentSeason() == null ? 1 : clock.getCurrentSeason();
        int week = clock.getCurrentWeek() == null ? 1 : clock.getCurrentWeek();

        Optional<TrainingWeekReport> existing = trainingWeekReportRepository
                .findByTeamIdAndSeasonNumberAndWeekNumber(teamId, season, week);
        if (existing.isPresent() && !force) {
            throw new ApiException(HttpStatus.CONFLICT, "TRAINING_ALREADY_RUN",
                    "Training has already been run for week " + week + " of season " + season
                            + ". Each week can only be trained once.");
        }
        if (existing.isPresent()) {
            log.warn("Forced re-run of weekly training for team {} (season {}, week {}), overwriting report {}",
                    teamId, season, week, existing.get().getId());
        }

        TeamTrainingSetup setup = teamTrainingSetupRepository
                .findByTeamIdAndSeasonNumberAndWeekNumber(teamId, season, week)
                .orElseGet(() -> createDefaultSetup(teamId, season, week));

        Map<Long, String> advancedRoleByPlayer = parseAssignments(setup).stream()
                .collect(Collectors.toMap(AdvancedAssignmentDTO::getPlayerId, a -> normalizeRole(a.getRole()), (a, b) -> a));

        List<Player> players = playerRepository.findByTeamId(teamId);
        List<Player> updatedPlayers = new ArrayList<>(players.size());
        TrainingWeekReportDTO report = new TrainingWeekReportDTO();
        report.setTeamId(teamId);
        report.setSeasonNumber(season);
        report.setWeekNumber(week);

        for (Player player : players) {
            Skills skills = player.getSkills();
            skills.initializeExactFromVisibleIfNeeded();

            String role = advancedRoleByPlayer.getOrDefault(player.getId(), roleFromPosition(player.getPosition()));
            boolean advanced = advancedRoleByPlayer.containsKey(player.getId());
            // An individual focus wins over the role default (Sprint 4.1). The role default still
            // applies to everything the focus does not name, so a striker focused on heading is still
            // a striker - he is just being worked on differently.
            SkillName roleDefault = dtSkillForRole(setup, role);
            SkillName directSkill = focusService.primarySkillFor(player, roleDefault, season, week);
            boolean hasFocus = focusService.hasFocus(player, season, week);

            Map<SkillName, Double> before = snapshotSkills(skills);
            double trainingPercent = trainingPercentService.percentForWeek(
                    player, season, week, directSkill);
            // Intensity is the missing cost side (Sprint 4.2). Resolved and charged once per player
            // here, not per skill: a week of work costs a week of fatigue however many skills it
            // grew, and charging it per skill would punish versatile players for being versatile.
            TrainingIntensity intensity = intensityService.intensityFor(
                    player, teamIntensity(setup), season, week);
            // Resolved for the skill he is actually being worked on, so a club that hired a
            // goalkeeping coach is not still developing its striker on his say-so (Sprint 4.3).
            StaffMember coach = coachForSkill(player, directSkill);
            applyWeeklyGrowth(player, skills, directSkill, advanced, season, week, trainingPercent,
                    intensity, coach);
            // The cost lands whether or not the growth arrived, and is charged after the growth so
            // an injury this week does not skip this week's training.
            intensityService.apply(player, intensity, season, week);
            skills.syncVisibleFromExact();
            player.setSkills(skills);
            updatedPlayers.add(player);
            Map<SkillName, Double> after = snapshotSkills(skills);

            PlayerTrainingReportDTO playerRow = new PlayerTrainingReportDTO();
            playerRow.setTrainingPercent(Math.round(trainingPercent * 10.0) / 10.0);
            playerRow.setPlayerId(player.getId());
            playerRow.setPlayerName(player.getName());
            playerRow.setRole(role);
            playerRow.setDirectTrainingSkill(skillToKey(directSkill));
            playerRow.setAdvancedTraining(advanced);
            playerRow.setIndividualFocus(hasFocus);
            playerRow.setSkills(buildSkillDeltas(before, after));
            report.getPlayers().add(playerRow);
        }

        if (!updatedPlayers.isEmpty()) {
            playerRepository.saveAll(updatedPlayers);
        }

        TrainingWeekReport dbReport = existing.orElseGet(TrainingWeekReport::new);
        dbReport.setTeam(teamRepository.findById(teamId).orElseThrow());
        dbReport.setSeasonNumber(season);
        dbReport.setWeekNumber(week);
        dbReport.setCreatedAt(LocalDateTime.now());
        try {
            dbReport.setReportJson(objectMapper.writeValueAsString(report));
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize training report", e);
        }
        trainingWeekReportRepository.save(dbReport);
        log.info("Weekly training applied to {} players for team {} (season {}, week {}), report {}",
                updatedPlayers.size(), teamId, season, week, dbReport.getId());
        return report;
    }

    public List<TrainingWeekSummaryDTO> getTeamReportSummaries(Long teamId) {
        Map<String, TrainingWeekSummaryDTO> unique = new LinkedHashMap<>();
        trainingWeekReportRepository.findByTeamIdOrderBySeasonNumberDescWeekNumberDesc(teamId).forEach(r -> {
            String key = r.getSeasonNumber() + "|" + r.getWeekNumber();
            unique.putIfAbsent(key, new TrainingWeekSummaryDTO(r.getSeasonNumber(), r.getWeekNumber(), r.getCreatedAt()));
        });
        return new ArrayList<>(unique.values());
    }

    /**
     * @return the report for that week, or {@code null} if training has not been run for it
     * @throws org.example.footballmanager.exception.ApiException if a report exists but cannot be
     *         read back - that genuinely is a server fault, unlike the missing case
     */
    public TrainingWeekReportDTO getTeamReport(Long teamId, Integer season, Integer week) {
        TrainingWeekReport report = trainingWeekReportRepository
                .findByTeamIdAndSeasonNumberAndWeekNumber(teamId, season, week)
                // Not an exception. "You have not trained this week yet" is a normal state, and
                // throwing turned it into a 500, which the training page renders as a hard API
                // error rather than as an empty week.
                .orElse(null);
        if (report == null) {
            return null;
        }
        try {
            return objectMapper.readValue(report.getReportJson(), TrainingWeekReportDTO.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse training report", e);
        }
    }

    /** @return the player's row in that week's report, or null if there is no report or no row */
    public PlayerTrainingReportDTO getPlayerReport(Long teamId, Long playerId, Integer season, Integer week) {
        TrainingWeekReportDTO report = getTeamReport(teamId, season, week);
        if (report == null || report.getPlayers() == null) {
            return null;
        }
        return report.getPlayers().stream()
                .filter(p -> Objects.equals(p.getPlayerId(), playerId))
                .findFirst()
                .orElse(null);
    }

    public List<PlayerTrainingGraphPointDTO> getPlayerGraph(Long teamId, Long playerId) {
        List<TrainingWeekReport> reports = trainingWeekReportRepository.findByTeamIdOrderBySeasonNumberDescWeekNumberDesc(teamId)
                .stream()
                .sorted(Comparator.comparing(TrainingWeekReport::getSeasonNumber).thenComparing(TrainingWeekReport::getWeekNumber))
                .toList();
        List<PlayerTrainingGraphPointDTO> points = new ArrayList<>();
        for (TrainingWeekReport r : reports) {
            try {
                TrainingWeekReportDTO dto = objectMapper.readValue(r.getReportJson(), TrainingWeekReportDTO.class);
                PlayerTrainingReportDTO player = dto.getPlayers().stream()
                        .filter(p -> Objects.equals(p.getPlayerId(), playerId))
                        .findFirst().orElse(null);
                if (player == null) continue;
                for (SkillDeltaDTO s : player.getSkills()) {
                    points.add(new PlayerTrainingGraphPointDTO(
                            dto.getSeasonNumber(),
                            dto.getWeekNumber(),
                            s.getSkill(),
                            s.getAfter(),
                            s.getAfterInt(),
                            player.getRole(),
                            player.getDirectTrainingSkill(),
                            player.isAdvancedTraining()
                    ));
                }
            } catch (Exception ignored) {}
        }
        return points;
    }

    private TeamTrainingSetup createDefaultSetup(Long teamId, int season, int week) {
        Team team = teamRepository.findById(teamId).orElseThrow();
        TeamTrainingSetup setup = new TeamTrainingSetup();
        setup.setTeam(team);
        setup.setSeasonNumber(season);
        setup.setWeekNumber(week);
        setup.setDtSkillGk("goalkeeper");
        setup.setDtSkillDef("defending");
        setup.setDtSkillMid("playmaker");
        setup.setDtSkillAtt("shooting");
        try {
            List<Player> players = playerRepository.findByTeamId(teamId);
            List<AdvancedAssignmentDTO> defaults = players.stream().limit(10).map(p -> {
                AdvancedAssignmentDTO a = new AdvancedAssignmentDTO();
                a.setPlayerId(p.getId());
                a.setRole(roleFromPosition(p.getPosition()));
                return a;
            }).toList();
            setup.setAdvancedAssignmentsJson(objectMapper.writeValueAsString(defaults));
        } catch (Exception e) {
            setup.setAdvancedAssignmentsJson("[]");
        }
        setup.setUpdatedAt(LocalDateTime.now());
        return teamTrainingSetupRepository.save(setup);
    }

    private TrainingSetupDTO toSetupDto(TeamTrainingSetup setup) {
        TrainingSetupDTO dto = new TrainingSetupDTO();
        dto.setTeamId(setup.getTeam().getId());
        dto.setSeasonNumber(setup.getSeasonNumber());
        dto.setWeekNumber(setup.getWeekNumber());
        dto.setGroupSkills(Map.of(
                "GK", normalizeDtSkill(setup.getDtSkillGk(), "GK"),
                "DEF", normalizeDtSkill(setup.getDtSkillDef(), "DEF"),
                "MID", normalizeDtSkill(setup.getDtSkillMid(), "MID"),
                "ATT", normalizeDtSkill(setup.getDtSkillAtt(), "ATT")
        ));
        dto.setAdvancedAssignments(parseAssignments(setup));
        return dto;
    }

    private List<AdvancedAssignmentDTO> parseAssignments(TeamTrainingSetup setup) {
        try {
            return objectMapper.readValue(
                    setup.getAdvancedAssignmentsJson() == null ? "[]" : setup.getAdvancedAssignmentsJson(),
                    new TypeReference<List<AdvancedAssignmentDTO>>() {}
            );
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private Map<SkillName, Double> snapshotSkills(Skills skills) {
        Map<SkillName, Double> map = new EnumMap<>(SkillName.class);
        for (SkillName s : List.of(SkillName.GOALKEEPER, SkillName.DEFENDER, SkillName.PACE, SkillName.TECHNIQUE,
                SkillName.PLAYMAKER, SkillName.PASSING, SkillName.STRIKER, SkillName.STAMINA)) {
            map.put(s, skills.getExact(s));
        }
        return map;
    }

    private List<SkillDeltaDTO> buildSkillDeltas(Map<SkillName, Double> before, Map<SkillName, Double> after) {
        List<SkillDeltaDTO> list = new ArrayList<>();
        for (SkillName s : List.of(SkillName.GOALKEEPER, SkillName.DEFENDER, SkillName.PACE, SkillName.TECHNIQUE,
                SkillName.PLAYMAKER, SkillName.PASSING, SkillName.STRIKER, SkillName.STAMINA)) {
            double b = before.getOrDefault(s, 0.0);
            double a = after.getOrDefault(s, 0.0);
            SkillDeltaDTO dto = new SkillDeltaDTO();
            dto.setSkill(skillToKey(s));
            dto.setBefore(round2(b));
            dto.setAfter(round2(a));
            dto.setDecimalChange(round2(a - b));
            dto.setBeforeInt((int) Math.floor(b));
            dto.setAfterInt((int) Math.floor(a));
            dto.setIntegerChange(dto.getAfterInt() - dto.getBeforeInt());
            list.add(dto);
        }
        return list;
    }

    private void applyWeeklyGrowth(Player player, Skills skills, SkillName directSkill,
                                     boolean advanced, int season, int week, double trainingPercent,
                                     TrainingIntensity intensity, StaffMember coach) {
        double injuryFactor = injuryTrainingFactor(player, season, week);
        double dt = computeDirectFragment(player, skills.getExact(directSkill), directSkill,
                advanced, trainingPercent, intensity, coach);
        dt *= injuryFactor;
        // The old global "striker x0.76 / pace x0.86 for everybody" penalty is gone rather than kept
        // alongside the position matrix, which would have counted the same idea twice and made every
        // striker's finishing 0.76 for two unrelated reasons.
        dt *= ceilingDamping(player, directSkill, skills.getExact(directSkill));
        // Rare jackpot is allowed only for low-skill players, to avoid unrealistic fast growth on 14+.
        if (skills.getExact(directSkill) <= 4.0
                && effectiveTalent(player.getTalent()) >= 9.0
                && random.nextDouble() < 0.03) {
            dt += 0.8 + random.nextDouble() * 0.8;
        }
        skills.setExact(directSkill, skills.getExact(directSkill) + dt);

        List<SkillName> generalSkills = isGoalkeeper(player)
                ? List.of(SkillName.GOALKEEPER, SkillName.DEFENDER, SkillName.PACE, SkillName.TECHNIQUE,
                SkillName.PLAYMAKER, SkillName.PASSING, SkillName.STRIKER)
                : List.of(SkillName.DEFENDER, SkillName.PACE, SkillName.TECHNIQUE,
                SkillName.PLAYMAKER, SkillName.PASSING, SkillName.STRIKER);

        for (SkillName skill : generalSkills) {
            if (skill == directSkill) continue;
            double gt = dt / 5.0;
            gt *= generalSkillModifier(skill);
            gt *= levelResistance(skills.getExact(skill));
            skills.setExact(skill, skills.getExact(skill) + gt);
        }

        if (week % 4 == 0) {
            // Stamina follows the same rule as everything else: the percentage decides how much of
            // the week's work happens, and the old talent factor is gone rather than left to be
            // applied a second time in a different place.
            double staminaGain = 0.14
                    * PositionGrowthProfile.ageFactor(
                            player.getPosition(), SkillName.STAMINA, player.getAge())
                    * PositionGrowthProfile.learningRate(player.getPosition(), SkillName.STAMINA)
                    * (trainingPercent / 100.0);
            staminaGain *= injuryFactor;
            skills.setExact(SkillName.STAMINA, skills.getExact(SkillName.STAMINA) + Math.max(0.03, staminaGain));
        }

        applyAgingDecay(player, skills);
    }

    private double injuryTrainingFactor(Player player, int season, int week) {
        if (player == null) return 1.0;
        if (player.getInjurySeasonNumber() != null
                && player.getInjuryWeekNumber() != null
                && player.getInjurySeasonNumber() == season
                && player.getInjuryWeekNumber() == week) {
            return 0.35;
        }
        return 1.0;
    }

    private void applyAgingDecay(Player player, Skills skills) {
        int age = player.getAge();
        if (age < 29) return;
        for (SkillName skill : List.of(SkillName.GOALKEEPER, SkillName.DEFENDER, SkillName.PACE, SkillName.TECHNIQUE,
                SkillName.PLAYMAKER, SkillName.PASSING, SkillName.STRIKER)) {
            double ageDecayBase = 0.03 + (Math.max(0, age - 29) * 0.025);
            if (skill == SkillName.PACE) ageDecayBase *= 1.25;
            double skillHeightFactor = Math.max(0.8, skills.getExact(skill) / 12.0);
            double decay = (random.nextDouble() * ageDecayBase) * skillHeightFactor;
            skills.setExact(skill, skills.getExact(skill) - decay);
        }
    }

    /**
     * The skill points a player actually gains this week.
     *
     * <p>Built from two things the owner asked to be kept apart:
     * <ul>
     *   <li>the <b>training percentage</b> — talent, the coach's rating for this skill, and minutes
     *       played. One source of truth, {@link TrainingPercent}.</li>
     *   <li>the <b>fragment</b> — how good a point is worth, which is skill height and age. These
     *       belong here and nowhere else, so a veteran and a prospect at the same percentage still
     *       gain different amounts.</li>
     * </ul>
     *
     * <p>The old version multiplied in its own talent factor, 0.55 to 1.55, <i>as well as</i> the
     * new percentage — talent was counted twice, and a 9/10 prospect with a good coach and a full
     * week of minutes got 1.4x1.4 the growth of the formula says he should. Removed at the owner's
     * instruction: one factor, applied once.
     *
     * <p>Base stays at 0.52, which is the existing calibration (an average under-18 improving 3->4
     * in about two weeks on advanced direct training) and is not part of what the owner changed.
     */
    private double computeDirectFragment(Player player, double currentExact, SkillName skill,
                                          boolean advanced, double trainingPercent,
                                          TrainingIntensity intensity, StaffMember coach) {
        // Growth scales with intensity, and by how much of the coaching actually lands (Sprint 4.3).
        // Applied here rather than at the call site so every path that grows a direct skill is
        // scaled the same amount.
        double base = 0.52
                * (intensity == null ? 1.0 : intensity.growthMultiplier())
                * TrainingPercent.disciplineFactor(coach)
                * facilityFactor(player, skill);
        // Rate, timing, ceiling and body are four different questions (Sprint 4.5). They are answered
        // in four different places on purpose, so a later change to one cannot quietly re-tune
        // another.
        base *= PositionGrowthProfile.learningRate(player.getPosition(), skill);
        base *= PositionGrowthProfile.physicalFactor(player, skill);
        double ageFactor = PositionGrowthProfile.ageFactor(player.getPosition(), skill, player.getAge());
        double levelFactor = levelResistance(currentExact);
        double advancedFactor = advanced ? 1.0 : 0.5;
        double randomFactor = 0.85 + random.nextDouble() * 0.35;
        double share = trainingPercent / 100.0;
        return Math.max(0.01, base * share * ageFactor * levelFactor * advancedFactor * randomFactor);
    }

    private double levelResistance(double exact) {
        double normalized = Math.max(0.0, Math.min(21.0, exact));
        return Math.max(0.08, 1.0 - (normalized / 22.0) * 0.85);
    }

    /**
     * How hard a player is pushed back as he approaches the ceiling of a skill that is not his job
     * (Sprint 4.5).
     *
     * <p>Returns 1.0 — no damping at all — for a skill played at his own position, and for a player
     * with no recorded position. Damping is applied on the last third of the approach so that early
     * gains come at full rate and it is the asymptote that does the work: a centre-half worked on
     * passing gets somewhere useful quickly, and then slows as he nears a plausible limit rather
     * than stopping dead.
     */
    private double ceilingDamping(Player player, SkillName skill, double currentExact) {
        if (player == null || player.getPosition() == null) return 1.0;
        double ceiling = PositionGrowthProfile.naturalCeiling(player.getPosition(), skill);
        if (ceiling >= 20.0) return 1.0;
        double remaining = ceiling - currentExact;
        if (remaining > 6.0) return 1.0;
        return Math.max(0.15, remaining / 6.0);
    }

    private double effectiveTalent(double rawTalent) {
        if (rawTalent <= 0) return 6.0;
        return Math.max(1.0, Math.min(10.0, rawTalent));
    }

    private boolean isSlowSkill(SkillName skill) {
        return skill == SkillName.PACE || skill == SkillName.STRIKER;
    }

    private double generalSkillModifier(SkillName skill) {
        return isSlowSkill(skill) ? 0.90 : 1.0;
    }

    private String roleFromPosition(Position position) {
        if (position == null) return "MID";
        return switch (position) {
            case GK -> "GK";
            case DEF -> "DEF";
            case MID, WNG -> "MID";
            case ATT -> "ATT";
        };
    }

    private SkillName dtSkillForRole(TeamTrainingSetup setup, String role) {
        String key = switch (normalizeRole(role)) {
            case "GK" -> setup.getDtSkillGk();
            case "DEF" -> setup.getDtSkillDef();
            case "ATT" -> setup.getDtSkillAtt();
            default -> setup.getDtSkillMid();
        };
        return skillKeyToEnum(normalizeDtSkill(key, role));
    }

    /**
     * How well the club's own facilities support this piece of work (Sprint 4.4).
     *
     * <p>Null stadium is 1.0 — a club that has not recorded one is not thereby penalised, and this
     * is the fourth multiplier in one expression, which is enough. Each answers a different question
     * and none of them is allowed to answer another's.
     */
    private double facilityFactor(Player player, SkillName skill) {
        if (player == null || player.getTeam() == null) return 1.0;
        Stadium ground = player.getTeam().getStadium();
        return ground == null ? 1.0 : ground.trainingFactorFor(skill);
    }

    /** The best coach this club has for the skill being trained, or null if it has nobody. */
    private StaffMember coachForSkill(Player player, SkillName skill) {
        return trainingPercentService.coachForSkill(player, skill);
    }

    /** The club's intensity for the week, defaulting to NORMAL when unset or unrecognised. */
    private TrainingIntensity teamIntensity(TeamTrainingSetup setup) {
        if (setup == null) return TrainingIntensity.NORMAL;
        TrainingIntensity parsed = TrainingIntensity.byName(setup.getTrainingIntensity());
        return parsed != null ? parsed : TrainingIntensity.NORMAL;
    }

    private String normalizeRole(String role) {
        if (role == null) return "MID";
        String up = role.toUpperCase(Locale.ROOT);
        if (List.of("GK", "DEF", "MID", "ATT").contains(up)) return up;
        return "MID";
    }

    private String normalizeDtSkill(String skillKey, String role) {
        String roleKey = normalizeRole(role);
        Set<String> allowed = new LinkedHashSet<>(List.of("pace", "defending", "technique", "passing"));
        if ("GK".equals(roleKey)) allowed.add("goalkeeper");
        if ("MID".equals(roleKey)) allowed.add("playmaker");
        if ("ATT".equals(roleKey)) allowed.add("shooting");
        if ("DEF".equals(roleKey)) allowed.add("defending");
        String candidate = skillKey == null ? "" : skillKey.toLowerCase(Locale.ROOT);
        if (allowed.contains(candidate)) return candidate;
        return switch (roleKey) {
            case "GK" -> "goalkeeper";
            case "DEF" -> "defending";
            case "ATT" -> "shooting";
            default -> "playmaker";
        };
    }

    private SkillName skillKeyToEnum(String key) {
        return switch (key.toLowerCase(Locale.ROOT)) {
            case "goalkeeper" -> SkillName.GOALKEEPER;
            case "defending" -> SkillName.DEFENDER;
            case "pace" -> SkillName.PACE;
            case "technique" -> SkillName.TECHNIQUE;
            case "playmaker" -> SkillName.PLAYMAKER;
            case "passing" -> SkillName.PASSING;
            case "shooting" -> SkillName.STRIKER;
            case "stamina" -> SkillName.STAMINA;
            default -> SkillName.PLAYMAKER;
        };
    }

    private String skillToKey(SkillName skillName) {
        return switch (skillName) {
            case GOALKEEPER -> "goalkeeper";
            case DEFENDER -> "defending";
            case PACE -> "pace";
            case TECHNIQUE -> "technique";
            case PLAYMAKER -> "playmaker";
            case PASSING -> "passing";
            case STRIKER -> "shooting";
            case STAMINA -> "stamina";
            case FATIGUE -> "fatigue";
        };
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private boolean isGoalkeeper(Player player) {
        return player != null && player.getPosition() == Position.GK;
    }
}
