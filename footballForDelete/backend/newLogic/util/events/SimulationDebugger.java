package org.example.footballmanager.newLogic.util.events;

import java.util.function.Consumer;
import org.example.footballmanager.newLogic.model.event.MatchEvent;
import org.example.footballmanager.newLogic.model.event.PossessionStartEvent;

public class SimulationDebugger implements Consumer<MatchEvent> {

    private int possessionSwitches = 0;
    private int longestPossessionTicks = 0;
    private double totalPossessionTicks = 0;
    private int possessionCount = 0;
    private double lastSwitchTick = -1;

    @Override
    public void accept(MatchEvent event) {
        if (event instanceof PossessionStartEvent) {
            if (lastSwitchTick >= 0) {
                int duration = (int)(event.tick() - lastSwitchTick);
                longestPossessionTicks = Math.max(longestPossessionTicks, duration);
                totalPossessionTicks += duration;
                possessionCount++;
            }
            lastSwitchTick = event.tick();
            possessionSwitches++;
        }
    }

    public String getHealthReport() {
        double avgPossession = possessionCount > 0 ? totalPossessionTicks / possessionCount / 120.0 : 0;
        double longestPossessionMin = longestPossessionTicks / 120.0;

        return String.format(
            "=== Simulation Health Report ===%n" +
            "Possession switches: %d%n" +
            "Average possession: %.1fs%n" +
            "Longest possession: %.1fs%s%n" +
            "Total possessions: %d%n",
            possessionSwitches,
            avgPossession,
            longestPossessionMin,
            longestPossessionMin > 5.0 ? " WARNING" : "",
            possessionCount
        );
    }
}
