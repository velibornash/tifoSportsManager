package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.dto.ReplayChunkDTO;
import org.example.footballmanager.newLogic.dto.ReplayMetadataDTO;
import org.example.footballmanager.newLogic.model.Match;
import org.example.footballmanager.newLogic.model.MatchResult;
import org.example.footballmanager.newLogic.model.TickSnapshot;
import org.example.footballmanager.newLogic.model.event.*;

import java.util.*;

public final class ReplayBuilder {

    private static final int CHUNK_SIZE = 120;

    public ReplayMetadataDTO buildMetadata(long matchId, Match match, MatchResult result) {
        List<Map<String, Object>> eventSummaries = result.events().stream()
            .filter(e -> !(e instanceof MatchStartEvent) && !(e instanceof MatchEndEvent))
            .map(this::summarizeEvent)
            .toList();

        return new ReplayMetadataDTO(
            matchId,
            result.totalTicks(),
            90,
            result.ticksPerMinute(),
            match.homeTeam().name(),
            match.awayTeam().name(),
            result.homeGoals(),
            result.awayGoals(),
            eventSummaries
        );
    }

    public List<ReplayChunkDTO> buildChunks(long matchId, MatchResult result) {
        List<ReplayChunkDTO> chunks = new ArrayList<>();
        List<TickSnapshot> ticks = result.tickHistory();
        int totalTicks = ticks.size();

        for (int i = 0; i < totalTicks; i += CHUNK_SIZE) {
            int end = Math.min(i + CHUNK_SIZE, totalTicks);
            List<TickSnapshot> chunkTicks = ticks.subList(i, end);

            chunks.add(new ReplayChunkDTO(
                matchId,
                chunks.size(),
                ticks.get(i).tick(),
                ticks.get(end - 1).tick(),
                chunkTicks
            ));
        }

        return chunks;
    }

    private Map<String, Object> summarizeEvent(MatchEvent event) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("minute", event.minute());
        map.put("tick", event.tick());

        switch (event) {
            case GoalEvent g -> {
                map.put("type", "GOAL");
                map.put("scorerName", g.scorerName());
                map.put("teamSide", g.teamSide());
                map.put("homeScore", g.homeScoreAfter());
                map.put("awayScore", g.awayScoreAfter());
            }
            case ShotEvent s -> {
                map.put("type", s.onTarget() ? (s.saved() ? "SHOT_SAVED" : "SHOT_ON_TARGET") : "SHOT_MISSED");
                map.put("shooterName", s.shooterName());
                map.put("teamSide", s.teamSide());
                map.put("xG", s.xG());
            }
            case PassEvent p -> {
                map.put("type", p.intercepted() ? "INTERCEPTION" : "PASS");
                map.put("passerName", p.passerName());
                map.put("receiverName", p.receiverName());
                map.put("teamSide", p.teamSide());
            }
            case FoulEvent f -> {
                map.put("type", "FOUL");
                map.put("takerName", f.takerName());
                map.put("victimName", f.victimName());
                map.put("teamSide", f.teamSide());
                map.put("penaltyFoul", f.penaltyFoul());
            }
            case CardEvent c -> {
                map.put("type", c.cardType() == CardEvent.CardType.YELLOW ? "YELLOW_CARD" : "RED_CARD");
                map.put("playerName", c.playerName());
                map.put("teamSide", c.teamSide());
            }
            case OffsideEvent o -> {
                map.put("type", "OFFSIDE");
                map.put("playerName", o.playerName());
                map.put("teamSide", o.teamSide());
            }
            case SetPieceEvent sp -> {
                map.put("type", sp.setPieceType().name());
                map.put("teamSide", sp.teamSide());
                map.put("takerName", sp.takerName());
            }
            case DuelEvent d -> {
                map.put("type", "DUEL");
                map.put("player1Name", d.player1Name());
                map.put("player2Name", d.player2Name());
                map.put("duelType", d.duelType());
            }
            case InjuryEvent i -> {
                map.put("type", "INJURY");
                map.put("playerName", i.playerName());
                map.put("teamSide", i.teamSide());
            }
            case SubstitutionEvent s -> {
                map.put("type", "SUBSTITUTION");
                map.put("playerOutName", s.playerOutName());
                map.put("playerInName", s.playerInName());
                map.put("teamSide", s.teamSide());
            }
            case PenaltyEvent p -> {
                map.put("type", "PENALTY");
                map.put("takerName", p.takerName());
                map.put("teamSide", p.teamSide());
                map.put("scored", p.scored());
            }
            default -> map.put("type", event.type().name());
        }

        return map;
    }
}
