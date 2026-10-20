package org.example.footballmanager.newLogic.service;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.newLogic.engine.MatchSimulator;
import org.example.footballmanager.newLogic.engine.ZonePositionCalculator;
import org.example.footballmanager.newLogic.model.*;
import org.example.footballmanager.newLogic.repository.LineupRepository;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.example.footballmanager.newLogic.store.MatchStore;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
public final class MatchOrchestrator {

    private static final Random RNG = new Random();
    private final MatchStore store;
    @Getter
    private final MatchSimulator simulator = new MatchSimulator();
    private final TeamRepository teamRepository;
    private final PlayerRepository playerRepository;
    private final LineupRepository lineupRepository;
    private final MatchPersistenceService persistenceService;

    public MatchOrchestrator(MatchStore store) {
        this(store, null, null, null, null);
    }

    public MatchOrchestrator(MatchStore store, TeamRepository teamRepository, PlayerRepository playerRepository) {
        this(store, teamRepository, playerRepository, null, null);
    }

    public MatchOrchestrator(MatchStore store, TeamRepository teamRepository, PlayerRepository playerRepository, LineupRepository lineupRepository) {
        this(store, teamRepository, playerRepository, lineupRepository, null);
    }

    public MatchOrchestrator(MatchStore store, TeamRepository teamRepository, PlayerRepository playerRepository, LineupRepository lineupRepository, MatchPersistenceService persistenceService) {
        this.store = store;
        this.teamRepository = teamRepository;
        this.playerRepository = playerRepository;
        this.lineupRepository = lineupRepository;
        this.persistenceService = persistenceService;
    }

    public long startMatch(String homeName, String awayName) {
        return startMatch(homeName, awayName, null, null, null, null);
    }

    /**
     * Start a match with custom tactic rules per team.
     * If homeTactics/homeSlots are null, generates defaults (4-3-3).
     * Loads real teams and players from DB if available.
     */
    public long startMatch(String homeName, String awayName,
                           TacticRules homeTactics, List<String> homeSlots,
                           TacticRules awayTactics, List<String> awaySlots) {
        
        // Try to load real team from DB
        Team home = loadTeamFromDB(homeName);
        if (home == null) {
            Set<String> usedNames = new HashSet<>();
            List<Player> homeSquad = generateSquad(11, 0, usedNames);
            home = new Team();
            home.setId(1L);
            home.setName(homeName);
            home.setPlayers(homeSquad);
            home.selectLineup(homeSquad.subList(0, 11), homeSquad.subList(11, Math.min(14, homeSquad.size())));
            home.setFormation("4-3-3");
        }

        Team away = loadTeamFromDB(awayName);
        if (away == null) {
            Set<String> usedNames = new HashSet<>();
            List<Player> awaySquad = generateSquad(11, 100, usedNames);
            away = new Team();
            away.setId(2L);
            away.setName(awayName);
            away.setPlayers(awaySquad);
            away.selectLineup(awaySquad.subList(0, 11), awaySquad.subList(11, Math.min(14, awaySquad.size())));
            away.setFormation("4-3-3");
        }

        // Slot keys — use saved formation from lineup, else caller-provided slots, else default
        String homeFormation = home.getFormation() != null ? home.getFormation() : "4-3-3";
        String awayFormation = away.getFormation() != null ? away.getFormation() : "4-3-3";
        List<String> effectiveHomeSlots = homeSlots != null ? homeSlots
            : ZonePositionCalculator.buildSlotKeys(homeFormation, home.startingXI());
        List<String> effectiveAwaySlots = awaySlots != null ? awaySlots
            : ZonePositionCalculator.buildSlotKeys(awayFormation, away.startingXI());
        home.setTacticRules(homeTactics != null ? homeTactics : generateDefaultTactics(effectiveHomeSlots), effectiveHomeSlots);
        away.setTacticRules(awayTactics != null ? awayTactics : generateDefaultTactics(effectiveAwaySlots), effectiveAwaySlots);

        Match match = new Match();
        match.setHomeTeam(home);
        match.setAwayTeam(away);
        match.setSeasonYear(2026);
        match.setRoundNumber(1);
        long matchId = store.createMatch(match);

        return matchId;
    }

    /**
     * Load real team with players from database.
     * Uses saved Lineup (tactic editor) if available, otherwise sorts by Position.
     * Ensures exactly 1 GK in starting XI.
     * Logs player positions and assigned slots.
     */
    private Team loadTeamFromDB(String teamName) {
        if (teamRepository == null || playerRepository == null) {
            return null;
        }
        
        try {
            Team dbTeam = teamRepository.findByName(teamName).orElse(null);
            if (dbTeam == null) return null;
            
            List<Player> players = playerRepository.findByTeamId(dbTeam.getId());
            if (players.isEmpty()) return null;
            
            for (Player p : players) {
                if (p.getId() == null || p.getName() == null || p.getSkills() == null || p.getPosition() == null) {
                    log.warn("Team {} has invalid player data, using synthetic team", teamName);
                    return null;
                }
            }
            
            Team team = new Team();
            team.setId(dbTeam.getId());
            team.setName(dbTeam.getName());
            team.setPlayers(players);

            // Try to use saved Lineup from tactic editor
            Lineup lineup = null;
            if (lineupRepository != null) {
                lineup = lineupRepository.findFirstByTeamIdAndMatchIsNullOrderByIdDesc(dbTeam.getId()).orElse(null);
            }

            List<Player> starters;
            List<Player> subs;

            if (lineup != null && !lineup.getOrderedStartingPlayers().isEmpty()) {
                starters = new ArrayList<>(lineup.getOrderedStartingPlayers());
                // If lineup has < 11, fill with best remaining players sorted by position
                if (starters.size() < 11) {
                    Set<Long> starterIds = starters.stream().map(Player::getId).collect(Collectors.toSet());
                    List<Player> remaining = players.stream()
                        .filter(p -> !starterIds.contains(p.getId()))
                        .sorted(Comparator.comparingInt(p -> positionOrder(p.getPosition())))
                        .collect(Collectors.toList());
                    int needed = 11 - starters.size();
                    starters.addAll(remaining.subList(0, Math.min(needed, remaining.size())));
                }
                Set<Long> starterIds = starters.stream().map(Player::getId).collect(Collectors.toSet());
                subs = players.stream()
                    .filter(p -> !starterIds.contains(p.getId()))
                    .limit(7)
                    .collect(Collectors.toList());
                if (lineup.getFormation() != null) {
                    team.setFormation(lineup.getFormation());
                }
                log.info("=== LINEUP for {} (from saved tactic editor, formation: {}) ===",
                    teamName, team.getFormation());
            } else {
                // Nema sačuvanog lineup-a — sortiraj po poziciji pa uzmi prvih 11
                List<Player> sorted = new ArrayList<>(players);
                sorted.sort(Comparator.comparingInt(p -> positionOrder(p.getPosition())));
                ensureSingleGK(sorted);
                starters = sorted.subList(0, Math.min(11, sorted.size()));
                subs = sorted.size() > 11
                    ? sorted.subList(11, Math.min(18, sorted.size()))
                    : List.of();
                team.setFormation("4-3-3");
                log.info("=== LINEUP for {} (auto-sorted by position, no saved lineup) ===", teamName);
            }

            team.selectLineup(starters, subs);

            // Uvek INDEX-based slot assignment — KRETNJA je po slotu, ne po prirodnoj poziciji
            List<String> slotKeys = ZonePositionCalculator.buildSlotKeys(
                team.getFormation() != null ? team.getFormation() : "4-3-3", starters);
            log.info("=== LINEUP for {} (formacija: {}) ===", teamName, team.getFormation());
            for (int i = 0; i < starters.size() && i < slotKeys.size(); i++) {
                log.info("  {}. {} | Position: {} | Slot: {}",
                    i + 1, starters.get(i).getName(), starters.get(i).getPosition(), slotKeys.get(i));
            }
            log.info("  Slot assignment: {}", String.join(", ", slotKeys));

            log.info("Loaded team {} from DB with {} players, {} starters, {} subs",
                teamName, players.size(), starters.size(), subs.size());
            return team;
        } catch (Exception e) {
            log.warn("Failed to load team {} from DB, using synthetic team: {}", teamName, e.getMessage());
            return null;
        }
    }

    private static int positionOrder(Position p) {
        return switch (p) {
            case GK -> 0;
            case DEF -> 1;
            case MID -> 2;
            case WNG -> 3;
            case ATT -> 4;
        };
    }

    /** Ensure at most 1 GK in first 11; move extras to end. */
    private static void ensureSingleGK(List<Player> sorted) {
        int firstGK = -1;
        for (int i = 0; i < Math.min(11, sorted.size()); i++) {
            if (sorted.get(i).getPosition() == Position.GK) {
                if (firstGK == -1) {
                    firstGK = i;
                } else {
                    // Extra GK in first 11 — swap with last non-GK
                    for (int j = sorted.size() - 1; j >= 11; j--) {
                        if (sorted.get(j).getPosition() != Position.GK) {
                            Collections.swap(sorted, i, j);
                            break;
                        }
                    }
                }
            }
        }
        // If no GK in first 11, swap first GK from subs
        if (firstGK == -1) {
            for (int i = 11; i < sorted.size(); i++) {
                if (sorted.get(i).getPosition() == Position.GK) {
                    Collections.swap(sorted, 0, i);
                    break;
                }
            }
        }
    }

    /**
     * Generates default tactic rules matching FormationSlotCatalog.buildDefaultRules().
     * For each slot × ball state × possession context, computes a target cell.
     */
    public static TacticRules generateDefaultTactics(List<String> slotKeys) {
        // Slot anchor cells (4-3-3 — matches FormationSlotCatalog / tactics editor)
        // 7 rows (progress 0-6), 6 cols (width 0-5)
        java.util.Map<String, SlotInfo> slots = new java.util.LinkedHashMap<>();
        slots.put("GK",  new SlotInfo("CELL_0_2", "GK", "GK"));
        slots.put("DL",  new SlotInfo("CELL_1_0", "DEF", "DEF"));
        slots.put("DCL", new SlotInfo("CELL_1_1", "DEF", "DEF"));
        slots.put("DCR", new SlotInfo("CELL_1_3", "DEF", "DEF"));
        slots.put("DR",  new SlotInfo("CELL_1_4", "DEF", "DEF"));
        slots.put("CML", new SlotInfo("CELL_2_1", "MID", "MID"));
        slots.put("CM",  new SlotInfo("CELL_2_2", "MID", "MID"));
        slots.put("CMR", new SlotInfo("CELL_2_3", "MID", "MID"));
        slots.put("WL",  new SlotInfo("CELL_4_0", "ATT", "WNG"));
        slots.put("ST",  new SlotInfo("CELL_4_2", "ATT", "ATT"));
        slots.put("WR",  new SlotInfo("CELL_4_4", "ATT", "WNG"));

        TacticRules rules = TacticRules.createDefault(slotKeys);

        // Generate rules for all ball states: 42 CELLs (7x6) + 4 corners
        int numCells = 7 * 6; // 42
        String[] ballStates = new String[numCells + 4];
        int idx = 0;
        for (int r = 0; r < 7; r++) {
            for (int c = 0; c < 6; c++) {
                ballStates[idx++] = "CELL_" + r + "_" + c;
            }
        }
        ballStates[42] = "ATTACK_LEFT_CORNER";
        ballStates[43] = "ATTACK_RIGHT_CORNER";
        ballStates[44] = "DEFEND_LEFT_CORNER";
        ballStates[45] = "DEFEND_RIGHT_CORNER";

        for (String slotKey : slotKeys) {
            SlotInfo info = slots.get(slotKey);
            if (info == null) continue;

            for (String ballState : ballStates) {
                int[] ballCell = syntheticBallCell(ballState);
                int[] weHave = computeDefaultTarget(info, ballCell[0], ballCell[1], true);
                int[] opponentHas = computeDefaultTarget(info, ballCell[0], ballCell[1], false);
                rules.setRule(slotKey, ballState, true, "CELL_" + weHave[0] + "_" + weHave[1]);
                rules.setRule(slotKey, ballState, false, "CELL_" + opponentHas[0] + "_" + opponentHas[1]);
            }
        }
        return rules;
    }

    private static int[] computeDefaultTarget(SlotInfo slot, int ballP, int ballW, boolean weHaveBall) {
        int[] anchor = parseCellKey(slot.anchorCellKey);
        int progressShift = switch (slot.line) {
            case "GK" -> 0;
            case "DEF" -> weHaveBall ? 1 : -1;
            case "MID" -> weHaveBall ? 1 : 0;
            case "ATT" -> weHaveBall ? 0 : -1;
            default -> 0;
        };
        int widthPull = switch (slot.role) {
            case "WNG" -> 1;
            case "DEF" -> 1;
            default -> 0;
        };

        float shiftFactor = weHaveBall ? 0.25f : 0.18f;
        int progress = clampToRow(Math.round(anchor[0] + progressShift + (ballP - anchor[0]) * shiftFactor));
        int width = clampToCol(Math.round(anchor[1] + (ballW - anchor[1]) * (weHaveBall ? 0.20f : 0.35f)));

        if ("WNG".equals(slot.role)) {
            width = anchor[1] <= 1 ? Math.min(width, 1 + widthPull) : Math.max(width, 3 - widthPull);
        }
        if ("GK".equals(slot.role)) {
            progress = 0;
            width = 2 + (ballW < 2 ? -1 : ballW > 2 ? 1 : 0);
        }
        if ("DEF".equals(slot.line)) {
            progress = Math.max(Math.max(0, anchor[0] - 1), Math.min(progress, anchor[0] + (weHaveBall ? 1 : 0)));
        }
        if ("MID".equals(slot.line)) {
            progress = Math.max(Math.max(1, anchor[0] - 1), Math.min(progress, anchor[0] + (weHaveBall ? 1 : 0)));
        }
        if (!weHaveBall && "ATT".equals(slot.line)) {
            // Wingers/strikers track the ball back when defending instead of
            // standing stranded high up the pitch
            progress = clampToRow(Math.min(anchor[0], ballP + 1));
        }

        if (!weHaveBall && "ATT".equals(slot.line)) {
            // Ball tracking is dominant when defending so the front line
            // drops back to the ball instead of staying glued to the anchor
            progress = clampToRow(progress);
        } else {
            // Keep the slot anchor dominant. Ball state should nudge positioning, not reshape it.
            progress = clampToRow(Math.round(anchor[0] + (progress - anchor[0]) * (weHaveBall ? 0.25f : 0.18f)));
        }
        width = clampToCol(Math.round(anchor[1] + (width - anchor[1]) * (weHaveBall ? 0.18f : 0.12f)));
        return new int[]{clampToRow(progress), clampToCol(width)};
    }

    private static int[] syntheticBallCell(String ballState) {
        // 7 rows (0-6), 6 cols (0-5)
        return switch (ballState) {
            case "ATTACK_LEFT_CORNER" -> new int[]{6, 0};
            case "ATTACK_RIGHT_CORNER" -> new int[]{6, 5};
            case "DEFEND_LEFT_CORNER" -> new int[]{0, 0};
            case "DEFEND_RIGHT_CORNER" -> new int[]{0, 5};
            default -> parseCellKey(ballState);
        };
    }

    private static int[] parseCellKey(String cellKey) {
        if (cellKey == null || !cellKey.startsWith("CELL_")) return new int[]{3, 2};
        String[] parts = cellKey.split("_");
        if (parts.length != 3) return new int[]{3, 2};
        try {
            return new int[]{clampToRow(Integer.parseInt(parts[1])), clampToCol(Integer.parseInt(parts[2]))};
        } catch (NumberFormatException e) {
            return new int[]{3, 2};
        }
    }

    private static int clampToRow(int v) {
        return Math.max(0, Math.min(6, v));
    }

    private static int clampToCol(int v) {
        return Math.max(0, Math.min(5, v));
    }

    private record SlotInfo(String anchorCellKey, String line, String role) {}

    public MatchResult simulate(long matchId) {
        Match match = store.getMatch(matchId);
        if (match == null) throw new IllegalArgumentException("Match not found: " + matchId);

        MatchResult result = simulator.simulate(match);
        store.storeResult(matchId, result);

        if (persistenceService != null) {
            try {
                persistenceService.saveMatchResult(result, match);
            } catch (Exception e) {
                log.warn("Failed to persist match result: {}", e.getMessage(), e);
            }
        }

        return result;
    }

    public MatchResult getResult(long matchId) {
        return store.getResult(matchId);
    }

    public Match getMatch(long matchId) {
        return store.getMatch(matchId);
    }

    private static final String[] FIRST_NAMES = {
        "Marko", "Nikola", "Luka", "Stefan", "Milan", "Aleksandar", "Filip", "Jovan",
        "Nemanja", "Vladimir", "Petar", "Dušan", "Ivan", "Dejan", "Miloš", "Bojan",
        "Darko", "Goran", "Zoran", "Siniša", "Dragan", "Slobodan", "Miroslav", "Srdjan"
    };

    private static final String[] LAST_NAMES = {
        "Jovanović", "Petrović", "Nikolić", "Marković", "Đorđević", "Stojanović",
        "Ilić", "Pavlović", "Milanović", "Kovačević", "Obradović", "Simić",
        "Vasić", "Tadić", "Popović", "Savić", "Mitrović", "Babić", "Vuković",
        "Gajić", "Miljković", "Ristić", "Stanković", "Lukić"
    };

    // Generate a squad with realistic skill distributions and unique names per match
    private List<Player> generateSquad(int baseSeed, int idOffset, Set<String> usedNames) {
        List<Player> squad = new ArrayList<>();
        Random rng = new Random(baseSeed);

        // 11 starters + 3 subs — 4-3-3: GK, 4 DEF, 3 MID, 2 WNG, 1 ATT
        Position[] order = {
            Position.GK,
            Position.DEF, Position.DEF, Position.DEF, Position.DEF,
            Position.MID, Position.MID, Position.MID,
            Position.WNG, Position.WNG,
            Position.ATT,
            Position.DEF, Position.MID, Position.ATT
        };

        for (int i = 0; i < order.length; i++) {
            Position pos = order[i];
            int pace = randSkill(rng, pos == Position.GK ? 4 : 6, 15);
            int shooting = randSkill(rng, pos == Position.GK ? 2 : 5, 16);
            int passing = randSkill(rng, pos == Position.DEF || pos == Position.GK ? 5 : 7, 16);
            int technique = randSkill(rng, pos == Position.GK ? 3 : 5, 16);
            int defending = randSkill(rng, pos == Position.GK ? 4 : (pos == Position.ATT ? 3 : 6), 16);
            int playmaking = randSkill(rng, pos == Position.MID ? 7 : 4, 15);
            int goalkeeping = randSkill(rng, pos == Position.GK ? 10 : 1, 17);
            int stamina = randSkill(rng, 8, 18);

            Skills skills = new Skills();
            skills.setPace(pace);
            skills.setStriker(shooting);
            skills.setPassing(passing);
            skills.setTechnique(technique);
            skills.setDefender(defending);
            skills.setPlaymaker(playmaking);
            skills.setGoalkeeper(goalkeeping);
            skills.setStamina(stamina);
            skills.initializeExactFromVisibleIfNeeded();
            long pid = idOffset + i;
            String name = generateUniqueName(rng, usedNames);
            Player player = new Player();
            player.setId(pid);
            player.setName(name);
            player.setPosition(pos);
            player.setSkills(skills);
            player.setSquadNumber(i + 1);
            squad.add(player);
        }
        return squad;
    }

    private String generateUniqueName(Random rng, Set<String> usedNames) {
        for (int attempt = 0; attempt < 80; attempt++) {
            String name = generateName(rng);
            if (usedNames.add(name)) return name;
        }
        String fallback = "Igrač " + (usedNames.size() + 1);
        usedNames.add(fallback);
        return fallback;
    }

    private String generateName(Random rng) {
        String first = FIRST_NAMES[rng.nextInt(FIRST_NAMES.length)];
        String last = LAST_NAMES[rng.nextInt(LAST_NAMES.length)];
        return first + " " + last;
    }

    private int randSkill(Random rng, int min, int max) {
        return Math.max(min, Math.min(max, min + rng.nextInt(Math.max(1, max - min))));
    }
}
