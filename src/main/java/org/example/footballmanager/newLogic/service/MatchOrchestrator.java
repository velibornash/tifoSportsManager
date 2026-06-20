package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.engine.MatchSimulator;
import org.example.footballmanager.newLogic.engine.ZonePositionCalculator;
import org.example.footballmanager.newLogic.model.*;
import org.example.footballmanager.newLogic.store.MatchStore;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

public final class MatchOrchestrator {

    private static final Random RNG = new Random();
    private final MatchStore store;
    private final MatchSimulator simulator = new MatchSimulator();

    public MatchOrchestrator(MatchStore store) {
        this.store = store;
    }

    public long startMatch(String homeName, String awayName) {
        return startMatch(homeName, awayName, null, null, null, null);
    }

    /**
     * Start a match with custom tactic rules per team.
     * If homeTactics/homeSlots are null, generates defaults (4-3-3).
     */
    public long startMatch(String homeName, String awayName,
                           TacticRules homeTactics, List<String> homeSlots,
                           TacticRules awayTactics, List<String> awaySlots) {
        // Generate home team (IDs 0-13)
        Set<String> usedNames = new HashSet<>();
        List<Player> homeSquad = generateSquad(11, 0, usedNames);
        Team home = new Team();
        home.setId(1L);
        home.setName(homeName);
        home.setPlayers(homeSquad);
        home.selectLineup(homeSquad.subList(0, 11), homeSquad.subList(11, Math.min(14, homeSquad.size())));
        home.setFormation("4-3-3");

        // Generate away team (IDs 100-113 — distinct from home)
        List<Player> awaySquad = generateSquad(11, 100, usedNames);
        Team away = new Team();
        away.setId(2L);
        away.setName(awayName);
        away.setPlayers(awaySquad);
        away.selectLineup(awaySquad.subList(0, 11), awaySquad.subList(11, Math.min(14, awaySquad.size())));
        away.setFormation("4-3-3");

        // Attach tactic rules (custom from DB or default 4-3-3)
        List<String> effectiveHomeSlots = homeSlots != null ? homeSlots
            : ZonePositionCalculator.buildSlotKeys("4-3-3", home.startingXI());
        List<String> effectiveAwaySlots = awaySlots != null ? awaySlots
            : ZonePositionCalculator.buildSlotKeys("4-3-3", away.startingXI());
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
     * Generates default tactic rules matching FormationSlotCatalog.buildDefaultRules().
     * For each slot × ball state × possession context, computes a target cell.
     */
    public static TacticRules generateDefaultTactics(List<String> slotKeys) {
        // Slot anchor cells (4-3-3 — matches FormationSlotCatalog / tactics editor)
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

        // Generate rules for all ball states: 25 CELLs + 4 corners
        String[] ballStates = new String[29];
        int idx = 0;
        for (int p = 0; p < 5; p++) {
            for (int w = 0; w < 5; w++) {
                ballStates[idx++] = "CELL_" + p + "_" + w;
            }
        }
        ballStates[25] = "ATTACK_LEFT_CORNER";
        ballStates[26] = "ATTACK_RIGHT_CORNER";
        ballStates[27] = "DEFEND_LEFT_CORNER";
        ballStates[28] = "DEFEND_RIGHT_CORNER";

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
        int progress = clamp(Math.round(anchor[0] + progressShift + (ballP - anchor[0]) * shiftFactor));
        int width = clamp(Math.round(anchor[1] + (ballW - anchor[1]) * (weHaveBall ? 0.20f : 0.35f)));

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
            progress = Math.max(Math.max(2, anchor[0] - 1), progress);
        }

        // Keep the slot anchor dominant. Ball state should nudge positioning, not reshape it.
        progress = clamp(Math.round(anchor[0] + (progress - anchor[0]) * (weHaveBall ? 0.25f : 0.18f)));
        width = clamp(Math.round(anchor[1] + (width - anchor[1]) * (weHaveBall ? 0.18f : 0.12f)));
        return new int[]{clamp(progress), clamp(width)};
    }

    private static int[] syntheticBallCell(String ballState) {
        return switch (ballState) {
            case "ATTACK_LEFT_CORNER" -> new int[]{4, 0};
            case "ATTACK_RIGHT_CORNER" -> new int[]{4, 4};
            case "DEFEND_LEFT_CORNER" -> new int[]{0, 0};
            case "DEFEND_RIGHT_CORNER" -> new int[]{0, 4};
            default -> parseCellKey(ballState);
        };
    }

    private static int[] parseCellKey(String cellKey) {
        if (cellKey == null || !cellKey.startsWith("CELL_")) return new int[]{2, 2};
        String[] parts = cellKey.split("_");
        if (parts.length != 3) return new int[]{2, 2};
        try {
            return new int[]{clamp(Integer.parseInt(parts[1])), clamp(Integer.parseInt(parts[2]))};
        } catch (NumberFormatException e) {
            return new int[]{2, 2};
        }
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(4, v));
    }

    private record SlotInfo(String anchorCellKey, String line, String role) {}

    public MatchResult simulate(long matchId) {
        Match match = store.getMatch(matchId);
        if (match == null) throw new IllegalArgumentException("Match not found: " + matchId);

        MatchResult result = simulator.simulate(match);
        store.storeResult(matchId, result);
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
