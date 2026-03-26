package org.example.footballmanager.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.dto.*;
import org.example.footballmanager.model.Lineup;
import org.example.footballmanager.model.Player;
import org.example.footballmanager.model.Team;
import org.example.footballmanager.model.tactics.TeamTacticsProfile;
import org.example.footballmanager.repository.LineupRepository;
import org.example.footballmanager.repository.PlayerRepository;
import org.example.footballmanager.repository.TeamRepository;
import org.example.footballmanager.repository.TeamTacticsProfileRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class TeamTacticsService {
    private static final Set<String> ALLOWED_STYLES = Set.of(
            "BALANCED", "ATTACKING", "DEFENSIVE", "COUNTER", "POSSESSION", "HIGH_PRESS", "DIRECT"
    );

    private final TeamRepository teamRepository;
    private final PlayerRepository playerRepository;
    private final LineupRepository lineupRepository;
    private final TeamTacticsProfileRepository teamTacticsProfileRepository;
    private final ObjectMapper objectMapper;
    private final FormationSlotCatalog formationSlotCatalog;
    private final TacticsProfileBackupService tacticsProfileBackupService;

    @Transactional(readOnly = true)
    public TacticsEditorDTO getTacticsEditor(Long teamId, String requestedFormation) {
        Team team = teamRepository.findById(teamId).orElse(null);
        if (team == null) {
            return null;
        }

        Lineup lineup = lineupRepository.findFirstByTeamIdAndMatchIsNullOrderByIdDesc(teamId).orElse(null);
        TeamTacticsProfile profile = resolveProfile(team);

        String formation = formationSlotCatalog.normalizeFormation(firstNonBlank(
                requestedFormation,
                lineup != null ? lineup.getFormation() : null,
                profile != null ? profile.getFormation() : null,
                "4-4-2"
        ));
        String style = normalizeStyle(firstNonBlank(
                lineup != null ? lineup.getStyle() : null,
                profile != null ? profile.getStyle() : null,
                "BALANCED"
        ));

        List<TacticsSlotDTO> slots = formationSlotCatalog.getSlots(formation);
        List<TacticsRuleDTO> defaultRules = formationSlotCatalog.buildDefaultRules(formation);
        List<TacticsRuleDTO> rules = mergeWithDefaults(profile != null && Objects.equals(profile.getFormation(), formation)
                ? parseRules(profile.getRulesJson()) : List.of(), defaultRules);
        TacticsSetPieceDTO setPieces = sanitizeSetPieces(
                profile != null && Objects.equals(profile.getFormation(), formation)
                        ? parseSetPieces(profile.getSetPiecesJson())
                        : defaultSetPieces(slots),
                slots
        );

        TacticsEditorDTO dto = new TacticsEditorDTO();
        dto.setTeamId(team.getId());
        dto.setTeamName(team.getName());
        dto.setSaved(profile != null || lineup != null);
        dto.setFormation(formation);
        dto.setStyle(style);
        dto.setStarterIds(lineup != null ? lineup.getOrderedStarterIds() : List.of());
        dto.setBenchIds(lineup != null ? lineup.getOrderedBenchIds() : List.of());
        dto.setSlotDefinitions(slots);
        dto.setSupportedBallStates(formationSlotCatalog.getSupportedBallStates());
        dto.setSupportedTargetCells(formationSlotCatalog.getSupportedTargetCells());
        dto.setMovementRules(rules);
        dto.setSetPieceAssignments(setPieces);
        dto.setVersion(profile != null ? profile.getVersion() : 0L);
        dto.setSavedAt(profile != null ? profile.getUpdatedAt() : null);
        return dto;
    }

    @Transactional
    public TacticsEditorDTO saveTacticsEditor(Long teamId, TacticsEditorSaveRequest request) {
        request = request == null ? new TacticsEditorSaveRequest() : request;
        Team team = teamRepository.findById(teamId).orElse(null);
        if (team == null) {
            return null;
        }

        String formation = formationSlotCatalog.normalizeFormation(request.getFormation());
        String style = normalizeStyle(request.getStyle());
        Lineup lineup = saveLineup(team, formation, style, request.getStarterIds(), request.getBenchIds());

        List<TacticsSlotDTO> slots = formationSlotCatalog.getSlots(formation);
        List<TacticsRuleDTO> rules = sanitizeRules(formation, request.getMovementRules());
        TacticsSetPieceDTO setPieces = sanitizeSetPieces(request.getSetPieceAssignments(), slots);

        TeamTacticsProfile profile = teamTacticsProfileRepository.findByTeamId(teamId).orElseGet(TeamTacticsProfile::new);
        profile.setTeam(team);
        profile.setFormation(formation);
        profile.setStyle(style);
        profile.setRulesJson(writeJson(rules));
        profile.setSetPiecesJson(writeJson(setPieces));
        profile.setVersion(profile.getVersion() == null ? 1L : profile.getVersion() + 1L);
        profile.setUpdatedAt(LocalDateTime.now());
        teamTacticsProfileRepository.save(profile);
        tacticsProfileBackupService.saveOrUpdate(team, profile);

        TacticsEditorDTO dto = getTacticsEditor(teamId, formation);
        if (dto != null) {
            dto.setStarterIds(lineup.getOrderedStarterIds());
            dto.setBenchIds(lineup.getOrderedBenchIds());
        }
        return dto;
    }

    @Transactional(readOnly = true)
    public List<TacticsSlotDTO> getSlotDefinitions(String formation) {
        return formationSlotCatalog.getSlots(formation);
    }

    @Transactional(readOnly = true)
    public Map<String, String> getRuntimeRuleMap(Long teamId, String formation) {
        String normalizedFormation = formationSlotCatalog.normalizeFormation(formation);
        List<TacticsRuleDTO> defaults = formationSlotCatalog.buildDefaultRules(normalizedFormation);
        Team team = teamRepository.findById(teamId).orElse(null);
        TeamTacticsProfile profile = resolveProfile(team);
        List<TacticsRuleDTO> rules = mergeWithDefaults(
                profile != null && Objects.equals(profile.getFormation(), normalizedFormation)
                        ? parseRules(profile.getRulesJson()) : List.of(),
                defaults
        );
        return rules.stream().collect(Collectors.toMap(this::ruleKey, TacticsRuleDTO::getTargetCellKey, (left, right) -> right, LinkedHashMap::new));
    }

    @Transactional(readOnly = true)
    public TacticsSetPieceDTO getRuntimeSetPieces(Long teamId, String formation) {
        String normalizedFormation = formationSlotCatalog.normalizeFormation(formation);
        List<TacticsSlotDTO> slots = formationSlotCatalog.getSlots(normalizedFormation);
        Team team = teamId != null ? teamRepository.findById(teamId).orElse(null) : null;
        TeamTacticsProfile profile = resolveProfile(team);
        TacticsSetPieceDTO configured = profile != null && Objects.equals(profile.getFormation(), normalizedFormation)
                ? parseSetPieces(profile.getSetPiecesJson())
                : defaultSetPieces(slots);
        return sanitizeSetPieces(configured, slots);
    }

    private Lineup saveLineup(Team team, String formation, String style, List<Long> starterIds, List<Long> benchIds) {
        Long teamId = team.getId();
        List<Long> safeStarterIds = parseIdList(starterIds, 11);
        List<Long> safeBenchIds = parseIdList(benchIds, 7);

        List<Player> teamPlayers = playerRepository.findByTeamId(teamId);
        Map<Long, Player> byId = teamPlayers.stream()
                .filter(player -> !player.isInjured())
                .filter(player -> player.getId() != null)
                .collect(Collectors.toMap(Player::getId, Function.identity(), (a, b) -> a, LinkedHashMap::new));

        List<Player> starters = new ArrayList<>(safeStarterIds.stream().map(byId::get).filter(Objects::nonNull).toList());
        if (starters.size() < 11) {
            List<Player> finalStarters = starters;
            List<Player> fallback = byId.values().stream()
                    .filter(player -> finalStarters.stream().noneMatch(starter -> Objects.equals(starter.getId(), player.getId())))
                    .sorted((left, right) -> Integer.compare(right.getRating(), left.getRating()))
                    .limit(11 - starters.size())
                    .toList();
            starters.addAll(fallback);
        }

        List<Player> finalStarters1 = starters;
        List<Player> bench = new ArrayList<>(safeBenchIds.stream()
                .map(byId::get)
                .filter(Objects::nonNull)
                .filter(player -> finalStarters1.stream().noneMatch(starter -> Objects.equals(starter.getId(), player.getId())))
                .limit(7)
                .toList());
        if (bench.size() < 7) {
            List<Player> finalStarters2 = starters;
            List<Player> finalBench = bench;
            List<Player> fallbackBench = byId.values().stream()
                    .filter(player -> finalStarters2.stream().noneMatch(starter -> Objects.equals(starter.getId(), player.getId())))
                    .filter(player -> finalBench.stream().noneMatch(sub -> Objects.equals(sub.getId(), player.getId())))
                    .sorted((left, right) -> Integer.compare(right.getRating(), left.getRating()))
                    .limit(7 - bench.size())
                    .toList();
            bench.addAll(fallbackBench);
        }

        Lineup lineup = lineupRepository.findFirstByTeamIdAndMatchIsNullOrderByIdDesc(teamId).orElseGet(Lineup::new);
        lineup.setTeam(team);
        lineup.setMatch(null);
        lineup.setFormation(formation);
        lineup.setStyle(style);
        lineup.setStartingPlayers(new ArrayList<>(starters));
        lineup.setSubstitutes(new ArrayList<>(bench));
        lineup.setStarterOrderFromIds(new ArrayList<>(starters.stream().map(Player::getId).toList()));
        lineup.setBenchOrderFromIds(new ArrayList<>(bench.stream().map(Player::getId).toList()));
        return lineupRepository.save(lineup);
    }

    private List<TacticsRuleDTO> sanitizeRules(String formation, List<TacticsRuleDTO> requestedRules) {
        List<TacticsRuleDTO> defaults = formationSlotCatalog.buildDefaultRules(formation);
        Set<String> validSlots = formationSlotCatalog.getSlots(formation).stream().map(TacticsSlotDTO::getSlotKey).collect(Collectors.toSet());
        Set<String> validBallStates = new HashSet<>(formationSlotCatalog.getSupportedBallStates());
        Set<String> validTargets = new HashSet<>(formationSlotCatalog.getSupportedTargetCells());
        Map<String, TacticsRuleDTO> sanitized = new LinkedHashMap<>();
        for (TacticsRuleDTO rule : requestedRules == null ? List.<TacticsRuleDTO>of() : requestedRules) {
            if (rule == null
                    || !validSlots.contains(rule.getSlotKey())
                    || !validBallStates.contains(rule.getBallStateKey())
                    || !validTargets.contains(rule.getTargetCellKey())
                    || (!FormationSlotCatalog.WE_HAVE_BALL.equals(rule.getPossessionContext())
                    && !FormationSlotCatalog.OPPONENT_HAS_BALL.equals(rule.getPossessionContext()))) {
                continue;
            }
            sanitized.put(ruleKey(rule), new TacticsRuleDTO(rule.getSlotKey(), rule.getBallStateKey(), rule.getPossessionContext(), rule.getTargetCellKey()));
        }
        mirrorWeHaveBallRules(sanitized);

        List<TacticsRuleDTO> merged = new ArrayList<>();
        for (TacticsRuleDTO fallback : defaults) {
            merged.add(sanitized.getOrDefault(ruleKey(fallback), fallback));
        }
        return merged;
    }

    private List<TacticsRuleDTO> mergeWithDefaults(List<TacticsRuleDTO> persisted, List<TacticsRuleDTO> defaults) {
        Map<String, TacticsRuleDTO> byKey = new LinkedHashMap<>();
        for (TacticsRuleDTO rule : persisted) {
            if (rule != null) {
                byKey.put(ruleKey(rule), rule);
            }
        }
        mirrorWeHaveBallRules(byKey);
        List<TacticsRuleDTO> merged = new ArrayList<>();
        for (TacticsRuleDTO fallback : defaults) {
            TacticsRuleDTO rule = byKey.getOrDefault(ruleKey(fallback), fallback);
            merged.add(new TacticsRuleDTO(rule.getSlotKey(), rule.getBallStateKey(), rule.getPossessionContext(), rule.getTargetCellKey()));
        }
        return merged;
    }

    private void mirrorWeHaveBallRules(Map<String, TacticsRuleDTO> rulesByKey) {
        List<TacticsRuleDTO> sourceRules = new ArrayList<>(rulesByKey.values());
        for (TacticsRuleDTO rule : sourceRules) {
            if (rule == null
                    || !FormationSlotCatalog.WE_HAVE_BALL.equals(rule.getPossessionContext())) {
                continue;
            }
            TacticsRuleDTO mirrored = new TacticsRuleDTO(
                    rule.getSlotKey(),
                    rule.getBallStateKey(),
                    FormationSlotCatalog.OPPONENT_HAS_BALL,
                    rule.getTargetCellKey()
            );
            rulesByKey.put(ruleKey(mirrored), mirrored);
        }
    }

    private TeamTacticsProfile resolveProfile(Team team) {
        if (team == null || team.getId() == null) {
            return null;
        }
        TeamTacticsProfile persisted = teamTacticsProfileRepository.findByTeamId(team.getId()).orElse(null);
        if (persisted != null) {
            return persisted;
        }
        return tacticsProfileBackupService.findByTeamName(team.getName())
                .map(entry -> toVirtualProfile(team, entry))
                .orElse(null);
    }

    private TeamTacticsProfile toVirtualProfile(Team team, TacticsProfileBackupEntry entry) {
        TeamTacticsProfile profile = new TeamTacticsProfile();
        profile.setTeam(team);
        profile.setFormation(entry.getFormation());
        profile.setStyle(entry.getStyle());
        profile.setRulesJson(entry.getRulesJson());
        profile.setSetPiecesJson(entry.getSetPiecesJson());
        profile.setVersion(entry.getVersion() != null ? entry.getVersion() : 1L);
        profile.setUpdatedAt(entry.getUpdatedAt());
        return profile;
    }

    private TacticsSetPieceDTO sanitizeSetPieces(TacticsSetPieceDTO input, List<TacticsSlotDTO> slots) {
        TacticsSetPieceDTO defaults = defaultSetPieces(slots);
        Set<String> allowed = slots.stream().map(TacticsSlotDTO::getSlotKey).collect(Collectors.toSet());
        TacticsSetPieceDTO sanitized = new TacticsSetPieceDTO();
        sanitized.setPenaltyTakerSlot(validOrDefault(input != null ? input.getPenaltyTakerSlot() : null, allowed, defaults.getPenaltyTakerSlot()));
        sanitized.setFreeKickLeftTakerSlot(validOrDefault(input != null ? input.getFreeKickLeftTakerSlot() : null, allowed, defaults.getFreeKickLeftTakerSlot()));
        sanitized.setFreeKickRightTakerSlot(validOrDefault(input != null ? input.getFreeKickRightTakerSlot() : null, allowed, defaults.getFreeKickRightTakerSlot()));
        sanitized.setCornerLeftTakerSlot(validOrDefault(input != null ? input.getCornerLeftTakerSlot() : null, allowed, defaults.getCornerLeftTakerSlot()));
        sanitized.setCornerRightTakerSlot(validOrDefault(input != null ? input.getCornerRightTakerSlot() : null, allowed, defaults.getCornerRightTakerSlot()));
        return sanitized;
    }

    private TacticsSetPieceDTO defaultSetPieces(List<TacticsSlotDTO> slots) {
        List<String> slotKeys = slots.stream().map(TacticsSlotDTO::getSlotKey).toList();
        TacticsSetPieceDTO dto = new TacticsSetPieceDTO();
        dto.setPenaltyTakerSlot(firstPreferred(slotKeys, List.of("ST", "STR", "STL", "AMC", "AMR", "AML", "CM", "CMR", "CML")));
        dto.setFreeKickLeftTakerSlot(firstPreferred(slotKeys, List.of("AML", "CML", "CM", "WL", "ML", "DL", "STL")));
        dto.setFreeKickRightTakerSlot(firstPreferred(slotKeys, List.of("AMR", "CMR", "CM", "WR", "MR", "DR", "STR")));
        dto.setCornerLeftTakerSlot(firstPreferred(slotKeys, List.of("WL", "ML", "AML", "DL", "CML", "CM")));
        dto.setCornerRightTakerSlot(firstPreferred(slotKeys, List.of("WR", "MR", "AMR", "DR", "CMR", "CM")));
        return dto;
    }

    private List<TacticsRuleDTO> parseRules(String rulesJson) {
        if (rulesJson == null || rulesJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(rulesJson, new TypeReference<List<TacticsRuleDTO>>() {});
        } catch (Exception ex) {
            log.warn("Failed to parse tactics rules JSON", ex);
            return List.of();
        }
    }

    private TacticsSetPieceDTO parseSetPieces(String setPiecesJson) {
        if (setPiecesJson == null || setPiecesJson.isBlank()) {
            return new TacticsSetPieceDTO();
        }
        try {
            return objectMapper.readValue(setPiecesJson, TacticsSetPieceDTO.class);
        } catch (Exception ex) {
            log.warn("Failed to parse tactics set pieces JSON", ex);
            return new TacticsSetPieceDTO();
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new RuntimeException("Failed to serialize tactics payload", ex);
        }
    }

    private String ruleKey(TacticsRuleDTO rule) {
        return rule.getSlotKey() + "|" + rule.getBallStateKey() + "|" + rule.getPossessionContext();
    }

    private String validOrDefault(String slotKey, Set<String> allowed, String fallback) {
        return slotKey != null && allowed.contains(slotKey) ? slotKey : fallback;
    }

    private String firstPreferred(List<String> available, List<String> preferredOrder) {
        for (String preferred : preferredOrder) {
            if (available.contains(preferred)) {
                return preferred;
            }
        }
        return available.isEmpty() ? null : available.get(0);
    }

    private String normalizeStyle(String rawStyle) {
        String style = rawStyle == null ? "BALANCED" : rawStyle.trim().toUpperCase(Locale.ROOT);
        return ALLOWED_STYLES.contains(style) ? style : "BALANCED";
    }

    private List<Long> parseIdList(List<Long> rawIds, int limit) {
        return (rawIds == null ? List.<Long>of() : rawIds).stream()
                .filter(Objects::nonNull)
                .distinct()
                .limit(limit)
                .toList();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
