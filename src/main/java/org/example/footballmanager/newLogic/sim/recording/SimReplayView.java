package org.example.footballmanager.newLogic.sim.recording;

import org.example.footballmanager.newLogic.sim.engine.MatchOrchestrator;
import org.example.footballmanager.newLogic.sim.model.Position;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the compact viewer payload for a finished sim match. Snapshots are
 * downsampled (one per {@link #SNAPSHOT_STRIDE} ticks, plus the half-time and
 * final ticks) — the viewer interpolates positions between them. The JSON
 * shape mirrors the ProposalMatchController.generate response so the viewer
 * consumes it unchanged.
 */
public final class SimReplayView {

    public static final int SNAPSHOT_STRIDE = 10;

    private SimReplayView() {}

    public static Map<String, Object> build(MatchOrchestrator orchestrator, String homeName, String awayName) {
        var state = orchestrator.getState();
        var recorder = orchestrator.getRecorder();

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("homeTeamName", homeName);
        view.put("awayTeamName", awayName);
        view.put("homeGoals", state.getHomeGoals());
        view.put("awayGoals", state.getAwayGoals());
        view.put("finalScore", state.getHomeGoals() + "-" + state.getAwayGoals());
        view.put("events", recorder.getEvents());
        view.put("snapshots", downsample(recorder.getSnapshots()));
        view.put("logs", orchestrator.getEventLog());

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("teams", orchestrator.getStats().toTeamJson());
        stats.put("players", orchestrator.getStats().toPlayersJson());
        view.put("stats", stats);
        return view;
    }

    private static List<Map<String, Object>> downsample(List<MatchSnapshot> snapshots) {
        if (snapshots.isEmpty()) return List.of();
        int last = snapshots.size() - 1;
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < snapshots.size(); i++) {
            MatchSnapshot snap = snapshots.get(i);
            long tick = snap.getTick();
            boolean keep = i == 0
                    || i == last
                    || snap.isHalfTime()
                    || snap.isMatchFinished()
                    || tick % SNAPSHOT_STRIDE == 0;
            if (keep) out.add(toMap(snap));
        }
        return out;
    }

    private static Map<String, Object> toMap(MatchSnapshot snap) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tick", snap.getTick());
        m.put("homeGoals", snap.getHomeGoals());
        m.put("awayGoals", snap.getAwayGoals());
        m.put("halfTime", snap.isHalfTime());
        m.put("matchFinished", snap.isMatchFinished());

        Position ball = snap.getBallPosition();
        Map<String, Object> ballPos = new LinkedHashMap<>();
        ballPos.put("row", ball != null ? ball.getRow() : 4.5);
        ballPos.put("column", ball != null ? ball.getColumn() : 4.0);
        m.put("ballPosition", ballPos);
        m.put("ballCarrierId", snap.getBallCarrierId());

        List<Map<String, Object>> players = new ArrayList<>();
        for (PlayerSnapshot p : snap.getPlayers()) {
            Map<String, Object> pm = new LinkedHashMap<>();
            pm.put("id", p.getId());
            pm.put("label", p.getLabel());
            pm.put("team", p.getTeam());
            pm.put("role", p.getRole());
            Position pos = p.getPosition();
            Map<String, Object> ppos = new LinkedHashMap<>();
            ppos.put("row", pos != null ? pos.getRow() : 0.0);
            ppos.put("column", pos != null ? pos.getColumn() : 0.0);
            pm.put("position", ppos);
            players.add(pm);
        }
        m.put("players", players);
        return m;
    }
}