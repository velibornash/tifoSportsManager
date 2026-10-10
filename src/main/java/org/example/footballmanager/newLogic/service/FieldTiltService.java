package org.example.footballmanager.newLogic.service;

import lombok.RequiredArgsConstructor;
import org.example.footballmanager.newLogic.model.Match;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.repository.MatchRepository;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.PlayerZoneLoadRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Where a side actually spent its match, read from the zone loads the recorder already writes.
 *
 * <p><b>No new data.</b> {@code player_zone_load} holds, for every player, the minutes he spent in each
 * of nine zones — {@code ATTACKING}, {@code MIDFIELD}, {@code DEFENSIVE} × {@code LEFT}, {@code CENTRE},
 * {@code RIGHT} — with an intensity. It is 27,757 rows over 235 matches, and field tilt is arithmetic on
 * it.
 *
 * <p><b>The zones are named from the player's own perspective.</b> {@code ATTACKING} is the attacking third
 * <em>for that player</em>, so home's attacking third and away's attacking third are the same concept and
 * are directly comparable — the same perspective discipline as the tactical mirror, and the reason the
 * two sides are compared share-for-share rather than one side's zones being reversed. Reversing them would
 * compare the home side's attacking third against the away side's defensive one and produce a number that
 * looks like field tilt and means nothing.
 */
@Service
@RequiredArgsConstructor
public class FieldTiltService {

    /** {@code Zone}'s attacking third is row 2 — DEFENSIVE is 0, MIDFIELD is 1. */
    private static final int ATTACKING_ROW = 2;

    private final PlayerZoneLoadRepository zoneLoads;
    private final PlayerRepository players;
    private final MatchRepository matches;

    /**
     * Each side's share of the ball-side-of-half-way minutes spent in the attacking third, 0–100.
     *
     * @return {@code null} when the match has no zone data, so the screen can say so rather than show 50%
     *         — a default that reads as "the teams were evenly balanced" when in fact nothing was measured
     */
    @Transactional(readOnly = true)
    public Map<String, Object> forMatch(Long matchId, TeamNames teams) {
        Map<String, Double> attacking = new HashMap<>();
        Map<String, Double> total = new HashMap<>();
        attacking.put("HOME", 0.0);
        attacking.put("AWAY", 0.0);
        total.put("HOME", 0.0);
        total.put("AWAY", 0.0);

        Map<Long, String> sideOfPlayer = sideOfPlayers(matchId);

        for (var load : zoneLoads.findByMatchId(matchId)) {
            Long playerId = load.getPlayer() == null ? null : load.getPlayer().getId();
            String side = playerId == null ? null : sideOfPlayer.get(playerId);
            if (side == null || load.getZone() == null) {
                continue;
            }
            // `minutes` is a primitive double on the row, so there is no null to guard; a row that was
            // written with no minutes simply contributes nothing.
            double minutes = load.getMinutes();
            if (minutes <= 0.0) {
                continue;
            }
            total.merge(side, minutes, Double::sum);
            if (load.getZone().third() == ATTACKING_ROW) {
                attacking.merge(side, minutes, Double::sum);
            }
        }

        if (total.get("HOME") + total.get("AWAY") <= 0.0) {
            return null;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("homeAttackShare", pct(attacking.get("HOME") / total.get("HOME")));
        out.put("awayAttackShare", pct(attacking.get("AWAY") / total.get("AWAY")));
        out.put("homeMinutesInAttackingThird", round1(attacking.get("HOME")));
        out.put("awayMinutesInAttackingThird", round1(attacking.get("AWAY")));
        out.put("homeTeamName", teams == null ? null : teams.home());
        out.put("awayTeamName", teams == null ? null : teams.away());
        return out;
    }

    /** The match's players mapped to the side they played for, by the match's own home/away teams. */
    private Map<Long, String> sideOfPlayers(Long matchId) {
        Map<Long, String> sides = new HashMap<>();
        Match match = matches.findById(matchId).orElse(null);
        if (match == null) {
            return sides;
        }
        if (match.getHomeTeam() != null && match.getHomeTeam().getId() != null) {
            for (Player player : players.findByTeamId(match.getHomeTeam().getId())) {
                sides.put(player.getId(), "HOME");
            }
        }
        if (match.getAwayTeam() != null && match.getAwayTeam().getId() != null) {
            for (Player player : players.findByTeamId(match.getAwayTeam().getId())) {
                sides.put(player.getId(), "AWAY");
            }
        }
        return sides;
    }

    private static double pct(double share) {
        return Math.round(share * 1000.0) / 10.0;
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    /** The two names, so the screen can label the number without a second lookup. */
    public record TeamNames(String home, String away) { }
}