package org.example.footballmanager.newLogic.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.newLogic.dto.junior.JuniorAcademyItemDTO;
import org.example.footballmanager.newLogic.dto.junior.JuniorAcademyStateDTO;
import org.example.footballmanager.newLogic.dto.junior.JuniorPromotionResultDTO;
import org.example.footballmanager.newLogic.exception.ApiException;
import org.example.footballmanager.newLogic.model.*;
import org.example.footballmanager.newLogic.repository.JuniorRepository;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.example.footballmanager.newLogic.util.players.NameGenerator;
import org.example.footballmanager.newLogic.util.players.SquadNumberAssigner;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class YouthAcademyService {

    private final TeamRepository teamRepository;
    private final JuniorRepository juniorRepository;
    private final PlayerRepository playerRepository;
    private final TransferService transferService;
    private final SquadNumberAssigner squadNumberAssigner;
    private final Random random = new Random();

    @Transactional
    public void generateSeasonIntakeForWeek2(int seasonNumber, int weekNumber) {
        if (weekNumber != 2) return;

        List<Team> teams = teamRepository.findClubTeamsForOperations();

        for (Team team : teams) {
            if (team.getId() == null) continue;
            long alreadyGenerated = juniorRepository.countByTeamIdAndArrivalSeasonNumberAndArrivalWeekNumber(team.getId(), seasonNumber, 2);
            if (alreadyGenerated > 0) continue;
            archiveResolvedJuniorsBeforeSeason(team.getId(), seasonNumber);

            ensureCoachSkill(team);
            long activeVisible = juniorRepository.countVisibleByTeamIdAndStatus(team.getId(), JuniorStatus.ACTIVE);
            int freeSlots = Math.max(0, 10 - (int) activeVisible);
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
            double delta = computeWeeklyDelta(junior, team.getJuniorCoachSkill());
            double nextExact = clamp(junior.getAcademySkillExact() + delta, 0.0, 20.99);
            junior.setAcademySkillExact(round2(nextExact));
            junior.setAcademySkill((int) Math.floor(junior.getAcademySkillExact()));
            junior.setLastWeeklyDelta(round2(delta));
        }
        juniorRepository.saveAll(active);
        log.info("Youth academy weekly progression done for season {}, week {} ({} juniors).", seasonNumber, weekNumber, active.size());
    }

    @Transactional
    public JuniorAcademyStateDTO getAcademyState(Long teamId, int currentSeason, int currentWeek) {
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

        visible.forEach(j -> dto.getJuniors().add(toDto(j)));
        juniorRepository.findByTeamIdAndArchivedTrueOrderByArrivalSeasonNumberDescAcademySkillExactDesc(teamId)
                .forEach(j -> dto.getArchive().add(toDto(j)));
        return dto;
    }

    @Transactional
    public JuniorAcademyItemDTO promoteJunior(Long juniorId, int currentSeason, int currentWeek) {
        Junior junior = loadDecisionJunior(juniorId, currentSeason, currentWeek);
        PromotionBuild build = createSeniorFromJunior(junior);
        Player player = build.player;
        junior.setStatus(JuniorStatus.PROMOTED);
        junior.setPromotedPlayer(player);
        juniorRepository.save(junior);
        return toDto(junior);
    }

    @Transactional
    public JuniorPromotionResultDTO promoteJuniorWithReveal(Long juniorId, int currentSeason, int currentWeek) {
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
        dto.setAllocatedSkills(build.allocatedSkills);
        dto.setAllocationSequence(build.allocationSequence);
        return dto;
    }

    @Transactional
    public JuniorAcademyItemDTO transferListJunior(Long juniorId, int currentSeason, int currentWeek) {
        Junior junior = loadDecisionJunior(juniorId, currentSeason, currentWeek);
        PromotionBuild build = createSeniorFromJunior(junior);
        Player player = build.player;
        transferService.listPlayerForTransfer(player.getId(), player.getPlayerValue());
        junior.setStatus(JuniorStatus.TRANSFER_LISTED);
        junior.setPromotedPlayer(player);
        juniorRepository.save(junior);
        return toDto(junior);
    }

    @Transactional
    public JuniorAcademyItemDTO releaseJunior(Long juniorId, int currentSeason, int currentWeek) {
        Junior junior = loadDecisionJunior(juniorId, currentSeason, currentWeek);
        junior.setStatus(JuniorStatus.RELEASED);
        junior.setLastWeeklyDelta(0.0);
        juniorRepository.save(junior);
        return toDto(junior);
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
        return junior;
    }

    private PromotionBuild createSeniorFromJunior(Junior junior) {
        Player player = new Player();
        player.setName(junior.getName());
        player.setAge(Math.max(17, junior.getAge() + 1));
        player.setTalent(junior.getTalent());
        player.setTeam(junior.getTeam());
        player.setForm(round2(4.5 + random.nextDouble() * 3.2));
        player.setRating(50);
        player.setHeight(round2(1.72 + random.nextDouble() * 0.24));
        player.setWeight(round2(65 + random.nextDouble() * 20));
        player.setEarnings(500 + random.nextInt(2500));

        Position position = rollPosition();
        player.setPosition(position);
        player.setSquadNumber(squadNumberAssigner.nextNumberForTeam(junior.getTeam(), position));

        int budget = Math.max(6, (int) Math.round(junior.getAcademySkillExact() * 3 + (random.nextInt(9) - 4)));
        SkillBuild skillBuild = createSkillsetFromBudget(budget, position == Position.GK);
        Skills skills = skillBuild.skills;
        player.setSkills(skills);

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

    private Position rollPosition() {
        int roll = random.nextInt(100);
        if (roll < 12) return Position.GK;
        int outfield = random.nextInt(3);
        if (outfield == 0) return Position.DEF;
        if (outfield == 1) return Position.MID;
        return Position.ATT;
    }

    private double computeWeeklyDelta(Junior junior, int coachSkill) {
        double coachFactor = 0.55 + (coachSkill / 100.0) * 0.95;
        double talentFactor = mapTalentFactor(junior.getTalent());
        double levelFactor = Math.max(0.10, 1.0 - (junior.getAcademySkillExact() / 21.0) * 0.82);
        double randomFactor = 0.82 + random.nextDouble() * 0.42;
        double base = 0.24 * coachFactor * talentFactor * levelFactor * randomFactor;

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

    private JuniorAcademyItemDTO toDto(Junior j) {
        JuniorAcademyItemDTO dto = new JuniorAcademyItemDTO();
        dto.setId(j.getId());
        dto.setName(j.getName());
        dto.setAge(j.getAge());
        dto.setTalent(round2(j.getTalent()));
        dto.setAcademySkill(j.getAcademySkill());
        dto.setAcademySkillExact(round2(j.getAcademySkillExact()));
        dto.setLastWeeklyDelta(round2(j.getLastWeeklyDelta()));
        dto.setStatus(j.getStatus() != null ? j.getStatus().name() : JuniorStatus.ACTIVE.name());
        dto.setArrivalSeasonNumber(j.getArrivalSeasonNumber());
        dto.setArrivalWeekNumber(j.getArrivalWeekNumber());
        dto.setPromotedPlayerId(j.getPromotedPlayer() != null ? j.getPromotedPlayer().getId() : null);
        dto.setArchived(Boolean.TRUE.equals(j.getArchived()));
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
