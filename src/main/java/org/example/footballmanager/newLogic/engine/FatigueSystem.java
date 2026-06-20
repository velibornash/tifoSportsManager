package org.example.footballmanager.newLogic.engine;

import org.example.footballmanager.newLogic.model.MatchState;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.PlayerSnapshot;
import org.example.footballmanager.newLogic.model.Position;
import org.example.footballmanager.newLogic.model.event.InjuryEvent;
import org.example.footballmanager.newLogic.model.event.SubstitutionEvent;

import java.util.*;
import java.util.stream.Collectors;

public final class FatigueSystem {

    private static final Random RNG = new Random();

    public static void updateFatigue(MatchState state, int minute) {
        for (var p : state.homePlayers()) increaseFatigue(p, minute);
        for (var p : state.awayPlayers()) increaseFatigue(p, minute);
    }

    private static void increaseFatigue(Player player, int minute) {
        if (player.position() == Position.GK) {
            if (minute % 18 == 0) {
                player.addFatigue(1);
            }
            return;
        }

        double staminaPenalty = Math.max(0, 13.0 - player.skills().stamina()) / 24.0;
        double positionLoad = switch (player.position()) {
            case WNG, ATT -> 0.05;
            case MID -> 0.04;
            case DEF -> 0.03;
            default -> 0.03;
        };
        double chance = 0.16 + staminaPenalty + positionLoad;
        if (minute >= 60) chance += 0.07;
        if (minute >= 78) chance += 0.08;

        if (RNG.nextDouble() < chance) player.addFatigue(1);
        if (minute >= 75 && RNG.nextDouble() < 0.14 + staminaPenalty) player.addFatigue(1);
    }

    public static void maybeInjury(MatchState state, int minute, String side) {
        if (minute < 8 || minute > 88) return;

        List<Player> players = "HOME".equals(side) ? state.homePlayers() : state.awayPlayers();
        if (players.isEmpty()) return;

        Player injured = pickInjuryRisk(players);
        if (injured == null) return;

        double chance = 0.00028 + Math.max(0, injured.fatigue() - 18) * 0.00008;
        if (injured.position() == Position.WNG || injured.position() == Position.ATT) chance += 0.00008;

        if (RNG.nextDouble() >= chance) return;

        // Apply injury
        state.injuredPlayers.add(injured.id());
        state.addEvent(new InjuryEvent(minute, state.tick, injured.id(), injured.name(), side));

        // Auto substitute if possible
        int subsUsed = "HOME".equals(side) ? state.homeSubsUsed : state.awaySubsUsed;
        if (subsUsed < 3) {
            List<Player> bench = getBench(state, side);
            Player replacement = pickReplacement(bench, injured.position());
            if (replacement != null) {
                applySubstitution(state, minute, side, injured, replacement);
            }
        }
    }

    public static void maybeSubstitution(MatchState state, int minute, String side) {
        if (minute < 58 || minute > 84) return;

        List<Player> players = "HOME".equals(side) ? state.homePlayers() : state.awayPlayers();
        int subsUsed = "HOME".equals(side) ? state.homeSubsUsed : state.awaySubsUsed;
        if (subsUsed >= 3) return;

        List<Player> bench = getBench(state, side);
        if (bench.isEmpty()) return;

        Player mostTired = players.stream()
            .filter(p -> p.position() != Position.GK)
            .max(Comparator.comparingDouble(Player::fatigue))
            .orElse(null);
        if (mostTired == null || mostTired.fatigue() < 16) return;

        double chance = 0.012;
        if (mostTired.fatigue() >= 18) chance += 0.04;
        if (mostTired.fatigue() >= 26) chance += 0.06;
        if (minute >= 72) chance += 0.018;

        if (RNG.nextDouble() < chance) {
            Player replacement = pickReplacement(bench, mostTired.position());
            if (replacement != null) {
                applySubstitution(state, minute, side, mostTired, replacement);
            }
        }
    }

    private static void applySubstitution(MatchState state, int minute, String side, Player out, Player in) {
        List<Player> targetList = "HOME".equals(side) ? state.homePlayers() : state.awayPlayers();

        // Replace in the player list
        int idx = targetList.indexOf(out);
        if (idx >= 0) {
            targetList.set(idx, in);
        }

        if ("HOME".equals(side)) state.homeSubsUsed++;
        else state.awaySubsUsed++;

        state.playerTeamSide.put(in.id(), side);
        state.playerMinutes.put(in.id(), 91 - minute);

        // Copy position from outgoing player, then remove old snapshot
        var outSnap = state.snapshotById(out.id());
        state.playerSnapshots.removeIf(s -> s.playerId() == out.id());
        if (outSnap != null) {
            state.playerSnapshots.add(new PlayerSnapshot(in.id(), in.name(), side, in.position(),
                outSnap.x(), outSnap.y(), "IDLE", false));
        }

        state.addEvent(new SubstitutionEvent(minute, state.tick, out.id(), out.name(), in.id(), in.name(), side));
    }

    private static Player pickInjuryRisk(List<Player> players) {
        List<Player> candidates = players.stream()
            .filter(p -> p.position() != Position.GK)
            .toList();
        if (candidates.isEmpty()) return null;

        double totalWeight = candidates.stream()
            .mapToDouble(p -> 1.0 + Math.max(0, p.fatigue() - 10) * 0.25)
            .sum();
        double r = RNG.nextDouble() * totalWeight;
        for (var p : candidates) {
            r -= 1.0 + Math.max(0, p.fatigue() - 10) * 0.25;
            if (r <= 0) return p;
        }
        return candidates.getLast();
    }

    private static List<Player> getBench(MatchState state, String side) {
        Set<Long> onPitch = ("HOME".equals(side) ? state.homePlayers() : state.awayPlayers()).stream()
            .map(Player::id).collect(Collectors.toSet());

        List<Player> full = "HOME".equals(side) ? state.match.homeTeam().allPlayers() : state.match.awayTeam().allPlayers();
        return full.stream()
            .filter(p -> !onPitch.contains(p.id()))
            .filter(p -> !state.injuredPlayers.contains(p.id()))
            .filter(p -> !state.playerMinutes.containsKey(p.id()))
            .toList();
    }

    private static Player pickReplacement(List<Player> bench, Position position) {
        if (bench.isEmpty()) return null;
        return bench.stream()
            .filter(p -> p.position() == position)
            .findFirst()
            .orElseGet(() -> bench.stream().filter(p -> p.position() != Position.GK).findFirst().orElse(bench.getFirst()));
    }
}
