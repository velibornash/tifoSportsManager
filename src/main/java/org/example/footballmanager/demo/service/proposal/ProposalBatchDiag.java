package org.example.footballmanager.demo.service.proposal;

import org.example.footballmanager.demo.service.proposal.engine.MatchOrchestrator;
import org.example.footballmanager.demo.service.proposal.model.*;

public class ProposalBatchDiag {
    public static void main(String[] args) {
        int n = args.length > 0 ? Integer.parseInt(args[0]) : 10;
        int goals=0, shots=0, sot=0, passes=0, comp=0, homeGoals=0, awayGoals=0, zeroZero=0;
        for (int i=0;i<n;i++){
            MatchState state = new MatchState();
            MatchSimulationLauncher.addTeam(state, "HOME");
            MatchSimulationLauncher.addTeam(state, "AWAY");
            MatchOrchestrator o = new MatchOrchestrator(state);
            o.getRestartManager().handleKickoff(state, "HOME");
            for (Player p : state.getPlayers()) {
                state.setRoundStartPosition(p.getId(), p.getPosition());
                state.setRoundPaceSkill(p.getId(), (int) Math.round(p.getSkills().pace()));
            }
            o.simulate(3600);
            goals += state.getHomeGoals()+state.getAwayGoals();
            homeGoals += state.getHomeGoals(); awayGoals += state.getAwayGoals();
            shots += state.getShots(); sot += state.getShotsOnTarget();
            passes += state.getPassAttempts(); comp += state.getPassesCompleted();
            if (state.getHomeGoals()==0 && state.getAwayGoals()==0) zeroZero++;
            System.out.printf("m%d H%d:%d shots=%d sot=%d pass=%d/%d%n",
                i, state.getHomeGoals(), state.getAwayGoals(), state.getShots(), state.getShotsOnTarget(),
                state.getPassesCompleted(), state.getPassAttempts());
        }
        System.out.printf("\n=== %d matches ===%n", n);
        System.out.printf("avg goals %.2f (H %.2f / A %.2f), 0-0 count %d%n", goals/(double)n, homeGoals/(double)n, awayGoals/(double)n, zeroZero);
        System.out.printf("avg shots %.1f sot %.1f (%.0f%%)%n", shots/(double)n, sot/(double)n, 100.0*sot/(shots>0?shots:1));
        System.out.printf("avg pass %d/%d (%.0f%%)%n", comp/n, passes/n, 100.0*comp/(passes>0?passes:1));
    }
}