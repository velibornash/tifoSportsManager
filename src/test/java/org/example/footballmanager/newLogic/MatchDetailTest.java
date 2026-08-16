package org.example.footballmanager.newLogic;

import org.example.footballmanager.newLogic.model.MatchResult;
import org.example.footballmanager.newLogic.model.event.*;
import org.example.footballmanager.newLogic.service.MatchOrchestrator;
import org.example.footballmanager.newLogic.store.MatchStore;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.stream.Collectors;

public class MatchDetailTest {

    @Test
    void printSingleMatchDetails() {
        MatchStore store = new MatchStore();
        MatchOrchestrator orchestrator = new MatchOrchestrator(store);

        long matchId = orchestrator.startMatch("Red Star Belgrade", "Partizan");
        MatchResult result = orchestrator.simulate(matchId);

        System.out.println("\n" + "=".repeat(60));
        System.out.println("MATCH RESULT: " + result.homeGoals() + " - " + result.awayGoals());
        System.out.println("=".repeat(60));

        System.out.println("\n--- TEAM STATS ---");
        System.out.println("  Possession:    H=" + String.format("%5.1f", result.homePossession()) + "%  A=" + String.format("%5.1f", result.awayPossession()) + "%");
        System.out.println("  Shots:         H=" + result.homeShots() + "  A=" + result.awayShots());
        System.out.println("  On target:     H=" + result.homeShotsOnTarget() + "  A=" + result.awayShotsOnTarget());
        System.out.println("  Fouls:         H=" + result.homeFouls() + "  A=" + result.awayFouls());
        System.out.println("  Corners:       H=" + result.homeCorners() + "  A=" + result.awayCorners());
        System.out.println("  Yellow cards:  H=" + result.homeYellowCards() + "  A=" + result.awayYellowCards());
        System.out.println("  Red cards:     H=" + result.homeRedCards() + "  A=" + result.awayRedCards());
        System.out.println("  Total ticks:   " + result.totalTicks());
        System.out.println("  Total events:  " + result.events().size());

        System.out.println("\n--- KEY EVENTS (filtered) ---");
        List<MatchEvent> keyEvents = result.events().stream()
            .filter(e -> !(e instanceof PossessionStartEvent))
            .filter(e -> !(e instanceof PossessionEndEvent))
            .filter(e -> !(e instanceof BallCarrierDecisionEvent))
            .collect(Collectors.toList());

        for (MatchEvent e : keyEvents) {
            String info = extractEventInfo(e);
            if (!info.isEmpty()) {
                System.out.printf("  %2d' [%s] %s%n", e.minute(), e.type().name(), info);
            }
        }

        System.out.println("\n--- GOAL SCORERS ---");
        result.events().stream()
            .filter(e -> e instanceof GoalEvent)
            .map(e -> (GoalEvent) e)
            .forEach(g -> System.out.printf("  %s (%s) - xG: %.2f%n",
                g.scorerName(),
                g.teamSide(),
                g.xG()));

        System.out.println("\n--- CARDS ---");
        result.events().stream()
            .filter(e -> e instanceof CardEvent)
            .map(e -> (CardEvent) e)
            .forEach(c -> System.out.printf("  %d' %s: %s (%s)%n",
                c.minute(), c.cardType(), c.playerName(), c.teamSide()));

        System.out.println("\n--- SUBSTITUTIONS ---");
        result.events().stream()
            .filter(e -> e instanceof SubstitutionEvent)
            .map(e -> (SubstitutionEvent) e)
            .forEach(s -> System.out.printf("  %d' %s: OUT %s -> IN %s%n",
                s.minute(), s.teamSide(), s.playerOutName(), s.playerInName()));

        System.out.println("\n--- INJURIES ---");
        result.events().stream()
            .filter(e -> e instanceof InjuryEvent)
            .map(e -> (InjuryEvent) e)
            .forEach(i -> System.out.printf("  %d' %s (%s)%n",
                i.minute(), i.playerName(), i.teamSide()));

        System.out.println("\n--- EVENT TYPE COUNTS ---");
        Map<String, Long> counts = result.events().stream()
            .collect(Collectors.groupingBy(e -> e.type().name(), Collectors.counting()));
        counts.entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .forEach(e -> System.out.printf("  %-25s %4d%n", e.getKey(), e.getValue()));

        System.out.println("\n" + "=".repeat(60));
        System.out.println("END OF MATCH REPORT");
        System.out.println("=".repeat(60));
    }

    private String extractEventInfo(MatchEvent e) {
        if (e instanceof GoalEvent g) return "GOAL! " + g.scorerName() + (g.assistantName() != null ? " (AST: " + g.assistantName() + ")" : "");
        if (e instanceof ShotSavedEvent s) return "Shot SAVED by " + s.goalkeeperName();
        if (e instanceof ShotMissedEvent s) return "Shot missed";
        if (e instanceof ShotBlockedEvent s) return "Shot BLOCKED";
        if (e instanceof PassEvent p) return "Pass: " + p.passerName() + " -> " + p.receiverName() + (p.intercepted() ? " [INT]" : "");
        if (e instanceof PassInterceptedEvent p) return "INTERCEPTED by " + p.interceptorName();
        if (e instanceof PassIncompleteEvent p) return "Incomplete pass by " + p.passerName();
        if (e instanceof TackleEvent t) return "Tackle: " + t.defenderName() + (t.success() ? " [WON]" : " [LOST]");
        if (e instanceof DuelEvent d) return "Duel: " + d.player1Name() + " vs " + d.player2Name() + " (attackerWon:" + d.attackerWon() + ")";
        if (e instanceof CardEvent c) return c.cardType() + " for " + c.playerName();
        if (e instanceof OffsideEvent o) return "Offside: " + o.playerName();
        if (e instanceof SubstitutionEvent s) return "Sub: " + s.playerInName() + " <-- " + s.playerOutName();
        if (e instanceof InjuryEvent i) return "Injury: " + i.playerName();
        if (e instanceof PenaltyEvent p) return "PENALTY: " + p.takerName() + " (scored:" + p.scored() + " saved:" + p.saved() + ")";
        if (e instanceof CrossEvent c) return "Cross by " + c.crosserName();
        if (e instanceof CrossHeaderEvent c) return "Header by " + c.headerName() + " (onTarget:" + c.onTarget() + ")";
        if (e instanceof CrossClearedEvent c) return "Cross CLEARED";
        if (e instanceof FoulEvent f) return "Foul: " + f.takerName();
        if (e instanceof SetPieceEvent s) return "Set piece: " + s.setPieceType() + " by " + s.takerName();
        if (e instanceof MatchStartEvent m) return "KICK OFF";
        if (e instanceof MatchEndEvent m) return "FULL TIME: " + m.homeGoals() + "-" + m.awayGoals();
        if (e instanceof GkSaveEvent g) return "GK SAVE: " + g.goalkeeperName();
        if (e instanceof GkCatchEvent g) return "GK CATCH: " + g.goalkeeperName();
        if (e instanceof GkPunchEvent g) return "GK PUNCH: " + g.goalkeeperName();
        if (e instanceof GkDistributionEvent g) return "GK DISTRIBUTION: " + g.goalkeeperName();
        if (e instanceof ClearanceEvent c) return "CLEARANCE by " + c.clearerName();
        if (e instanceof LooseBallEvent l) return "LOOSE BALL won by " + l.winnerName();
        if (e instanceof ThroughBallEvent t) return "Through ball: " + t.passerName() + " -> " + t.receiverName();
        if (e instanceof LongBallEvent l) return "Long ball: " + l.passerName() + " -> " + l.receiverName();
        if (e instanceof DribbleEvent d) return "Dribble by " + d.dribblerName();
        if (e instanceof DribbleLostEvent d) return "Dribble LOST by " + d.dribblerName();
        if (e instanceof ReceiveEvent r) return "Received: " + r.receiverName();
        return "";
    }
}
