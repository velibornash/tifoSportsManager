package org.example.footballmanager.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.example.footballmanager.dto.training.*;
import org.example.footballmanager.model.*;
import org.example.footballmanager.repository.PlayerRepository;
import org.example.footballmanager.repository.TeamRepository;
import org.example.footballmanager.repository.TeamTrainingSetupRepository;
import org.example.footballmanager.repository.TrainingWeekReportRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TrainingProgressionService {

    private final TeamRepository teamRepository;
    private final PlayerRepository playerRepository;
    private final TeamTrainingSetupRepository teamTrainingSetupRepository;
    private final TrainingWeekReportRepository trainingWeekReportRepository;
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

    @Transactional
    public TrainingWeekReportDTO runWeeklyTraining(Long teamId) {
        GameClock clock = seasonService.getOrCreateClock();
        int season = clock.getCurrentSeason() == null ? 1 : clock.getCurrentSeason();
        int week = clock.getCurrentWeek() == null ? 1 : clock.getCurrentWeek();

        TeamTrainingSetup setup = teamTrainingSetupRepository
                .findByTeamIdAndSeasonNumberAndWeekNumber(teamId, season, week)
                .orElseGet(() -> createDefaultSetup(teamId, season, week));

        Map<Long, String> advancedRoleByPlayer = parseAssignments(setup).stream()
                .collect(Collectors.toMap(AdvancedAssignmentDTO::getPlayerId, a -> normalizeRole(a.getRole()), (a, b) -> a));

        List<Player> players = playerRepository.findByTeamId(teamId);
        TrainingWeekReportDTO report = new TrainingWeekReportDTO();
        report.setTeamId(teamId);
        report.setSeasonNumber(season);
        report.setWeekNumber(week);

        for (Player player : players) {
            Skills skills = player.getSkills();
            skills.initializeExactFromVisibleIfNeeded();

            String role = advancedRoleByPlayer.getOrDefault(player.getId(), roleFromPosition(player.getPosition()));
            boolean advanced = advancedRoleByPlayer.containsKey(player.getId());
            SkillName directSkill = dtSkillForRole(setup, role);

            Map<SkillName, Double> before = snapshotSkills(skills);
            applyWeeklyGrowth(player, skills, directSkill, advanced, week);
            skills.syncVisibleFromExact();
            player.setSkills(skills);
            playerRepository.save(player);
            Map<SkillName, Double> after = snapshotSkills(skills);

            PlayerTrainingReportDTO playerRow = new PlayerTrainingReportDTO();
            playerRow.setPlayerId(player.getId());
            playerRow.setPlayerName(player.getName());
            playerRow.setRole(role);
            playerRow.setDirectTrainingSkill(skillToKey(directSkill));
            playerRow.setAdvancedTraining(advanced);
            playerRow.setSkills(buildSkillDeltas(before, after));
            report.getPlayers().add(playerRow);
        }

        TrainingWeekReport dbReport = trainingWeekReportRepository
                .findByTeamIdAndSeasonNumberAndWeekNumber(teamId, season, week)
                .orElseGet(TrainingWeekReport::new);
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

    public TrainingWeekReportDTO getTeamReport(Long teamId, Integer season, Integer week) {
        TrainingWeekReport report = trainingWeekReportRepository
                .findByTeamIdAndSeasonNumberAndWeekNumber(teamId, season, week)
                .orElseThrow(() -> new RuntimeException("Training report not found"));
        try {
            return objectMapper.readValue(report.getReportJson(), TrainingWeekReportDTO.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse training report", e);
        }
    }

    public PlayerTrainingReportDTO getPlayerReport(Long teamId, Long playerId, Integer season, Integer week) {
        TrainingWeekReportDTO report = getTeamReport(teamId, season, week);
        return report.getPlayers().stream()
                .filter(p -> Objects.equals(p.getPlayerId(), playerId))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Player report not found"));
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
                    points.add(new PlayerTrainingGraphPointDTO(dto.getSeasonNumber(), dto.getWeekNumber(), s.getSkill(), s.getAfter(), s.getAfterInt()));
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

    private void applyWeeklyGrowth(Player player, Skills skills, SkillName directSkill, boolean advanced, int week) {
        double dt = computeDirectFragment(player, skills.getExact(directSkill), directSkill, advanced);
        dt *= slowSkillModifier(directSkill);
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
            double staminaGain = 0.14 * ageTrainingFactor(player.getAge(), SkillName.STAMINA) * talentFactor(effectiveTalent(player.getTalent()));
            skills.setExact(SkillName.STAMINA, skills.getExact(SkillName.STAMINA) + Math.max(0.03, staminaGain));
        }

        applyAgingDecay(player, skills);
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

    private double computeDirectFragment(Player player, double currentExact, SkillName skill, boolean advanced) {
        // Calibrated so average <=18 player at low skill (3->4) needs around ~2 weeks on Advanced DT.
        double base = 0.52;
        double talent = talentFactor(effectiveTalent(player.getTalent()));
        double ageFactor = ageTrainingFactor(player.getAge(), skill);
        double levelFactor = levelResistance(currentExact);
        double advancedFactor = advanced ? 1.0 : 0.5;
        double randomFactor = 0.85 + random.nextDouble() * 0.35;
        return Math.max(0.01, base * talent * ageFactor * levelFactor * advancedFactor * randomFactor);
    }

    private double levelResistance(double exact) {
        double normalized = Math.max(0.0, Math.min(21.0, exact));
        return Math.max(0.08, 1.0 - (normalized / 22.0) * 0.85);
    }

    private double ageTrainingFactor(int age, SkillName skill) {
        if (age <= 18) return 1.10;
        if (age <= 23) return 1.0;
        if (age <= 28) return 1.0 - ((age - 23) * 0.05);
        if (age == 29) return 0.70;
        double factor = 0.70 - ((age - 29) * 0.08);
        if (skill == SkillName.PACE) factor -= 0.08;
        return Math.max(0.20, factor);
    }

    private double effectiveTalent(double rawTalent) {
        if (rawTalent <= 0) return 6.0;
        return Math.max(1.0, Math.min(10.0, rawTalent));
    }

    private double talentFactor(double talent) {
        if (talent <= 1.0) return 0.55;
        if (talent <= 2.0) return 0.65;
        if (talent <= 3.0) return 0.75;
        if (talent <= 4.0) return 0.85;
        if (talent <= 5.0) return 0.93;
        if (talent <= 6.0) return 1.00;
        if (talent <= 7.0) return 1.12;
        if (talent <= 8.0) return 1.24;
        if (talent <= 9.0) return 1.40;
        return 1.55;
    }

    private boolean isSlowSkill(SkillName skill) {
        return skill == SkillName.PACE || skill == SkillName.STRIKER;
    }

    private double slowSkillModifier(SkillName skill) {
        if (skill == SkillName.STRIKER) return 0.76;
        if (skill == SkillName.PACE) return 0.86;
        return 1.0;
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
