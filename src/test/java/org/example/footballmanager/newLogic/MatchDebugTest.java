package org.example.footballmanager.newLogic;

import org.example.footballmanager.newLogic.engine.*;
import org.example.footballmanager.newLogic.model.*;
import org.example.footballmanager.newLogic.model.event.*;
import org.example.footballmanager.newLogic.service.MatchOrchestrator;
import org.example.footballmanager.newLogic.store.MatchStore;
import org.junit.jupiter.api.Test;

import java.util.List;

public class MatchDebugTest {

    @Test
    void debugFirst20Ticks() {
        MatchStore store = new MatchStore();
        MatchOrchestrator orchestrator = new MatchOrchestrator(store);
        long matchId = orchestrator.startMatch("Debug Home", "Debug Away");

        // Get match from store and manually initialize simulator state
        Match match = store.getMatch(matchId);
        MatchSimulator simulator = new MatchSimulator();
        simulator.initializeSystems(match);
        simulator.initializeMatchState(match);
        MatchState state = simulator.getState();

        System.out.println("=== INITIAL STATE ===");
        System.out.println("carrierId=" + state.carrierId + " carrierTeamSide=" + state.carrierTeamSide);
        System.out.println("possessionTeam=" + state.possessionTeam + " lastTouchTeam=" + state.lastTouchTeam);
        System.out.println("stoppage=" + state.stoppage + " stoppageTicks=" + state.stoppageTicks);
        System.out.println("ball=" + state.ball);
        System.out.println("homePlayers=" + state.homeSnapshots().size() + " awayPlayers=" + state.awaySnapshots().size());
        for (PlayerSnapshot p : state.homeSnapshots()) {
            System.out.println("  HOME " + p.name() + " pos=" + p.position() + " x=" + String.format("%.1f", p.x()) + " y=" + String.format("%.1f", p.y()));
        }
        for (PlayerSnapshot p : state.awaySnapshots()) {
            System.out.println("  AWAY " + p.name() + " pos=" + p.position() + " x=" + String.format("%.1f", p.x()) + " y=" + String.format("%.1f", p.y()));
        }

        for (int tick = 1; tick <= 20; tick++) {
            state.tick = tick;
            state.minute = 1;
            simulator.simulateTick();
            state.recordTick();

            PlayerSnapshot carrier = state.ballCarrierSnapshot();
            String carrierInfo = carrier != null
                    ? carrier.name() + "(" + carrier.teamSide() + ") @(" + String.format("%.1f", carrier.x()) + "," + String.format("%.1f", carrier.y()) + ")"
                    : "NONE";

            System.out.println("\n--- Tick " + tick + " ---");
            System.out.println("ball=(" + String.format("%.1f", state.ball.x()) + "," + String.format("%.1f", state.ball.y()) + ")");
            System.out.println("carrier=" + carrierInfo);
            System.out.println("ballInTransit=" + state.ballInTransit + " transitPossessionTeam=" + state.transitPossessionTeam);
            System.out.println("carrierId=" + state.carrierId + " carrierTeamSide=" + state.carrierTeamSide);
            System.out.println("possessionTeam=" + state.possessionTeam + " lastTouchTeam=" + state.lastTouchTeam);
            System.out.println("stoppage=" + state.stoppage + " stoppageTicks=" + state.stoppageTicks);

            // Show intents
            System.out.println("Intents:");
            for (PlayerSnapshot p : state.playerSnapshots) {
                System.out.println("  " + p.teamSide() + " " + p.name() + "(" + p.position() + ") intent=" + p.intent() + " @(" + String.format("%.1f", p.x()) + "," + String.format("%.1f", p.y()) + ")");
            }

            // Show recent events
            int currentTick = tick;
            List<MatchEvent> recentEvents = state.events.stream()
                    .filter(e -> e.tick() == currentTick)
                    .toList();
            if (!recentEvents.isEmpty()) {
                System.out.println("Events this tick:");
                for (MatchEvent e : recentEvents) {
                    System.out.println("  " + e.type() + " " + formatEventDetail(e));
                }
            }
        }
    }

    private String formatEventDetail(MatchEvent e) {
        return switch (e) {
            case GoalEvent g -> "GOAL " + g.scorerName() + " " + g.teamSide() + " " + g.minute() + "'";
            case ShotEvent s -> "SHOT " + s.shooterName() + " " + s.teamSide() + " xG=" + String.format("%.2f", s.xG());
            case PassEvent p -> "PASS " + p.passerName() + " -> " + p.receiverName() + " " + p.teamSide();
            case DuelEvent d -> "DUEL " + d.player1Name() + " vs " + d.player2Name() + " " + d.duelType();
            case FoulEvent f -> "FOUL " + f.takerName() + " on " + f.victimName();
            case CardEvent c -> "CARD " + c.playerName() + " " + c.cardType();
            case OffsideEvent o -> "OFFSIDE " + o.playerName();
            case SetPieceEvent sp -> "SET_PIECE " + sp.setPieceType() + " " + sp.teamSide();
            case LooseBallEvent lb -> "LOOSE_BALL (" + String.format("%.1f", lb.x()) + "," + String.format("%.1f", lb.y()) + ")";
            case BallCarrierDecisionEvent bd -> "DECISION " + bd.carrierName() + " -> " + bd.action() + " " + bd.teamSide();
            case PossessionStartEvent ps -> "POSS_START " + ps.teamSide() + " chain=" + ps.chainId();
            case PossessionEndEvent pe -> "POSS_END " + pe.teamSide() + " chain=" + pe.chainId();
            default -> e.type().name();
        };
    }
}
