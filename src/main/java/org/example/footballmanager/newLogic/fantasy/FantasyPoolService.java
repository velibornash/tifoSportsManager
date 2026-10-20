package org.example.footballmanager.newLogic.fantasy;

import lombok.RequiredArgsConstructor;
import org.example.footballmanager.newLogic.model.Position;
import org.example.footballmanager.newLogic.repository.FantasyPoolRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Builds a country's fantasy pool and prices every player in it (owner, 2026-10-17).
 *
 * <p><b>Prices are derived, never stored.</b> A player's price is his tier-and-position base plus a
 * premium for his rank inside that band, recomputed on every read. That means a rating that moves moves
 * the price with it, and there is no second copy of the truth to drift from the first. It also means the
 * pool has to be built in one pass, which is what the flat query exists for.
 *
 * <p><b>The ranking is deterministic.</b> Ties are broken by player id, so two players on the same rating
 * always come out in the same order and a manager who reloads the page does not see prices swap places.
 * A ranking that reshuffles on ties is a price that changes for no reason, which is the kind of thing a
 * manager notices and nobody can explain.
 */
@Service
@RequiredArgsConstructor
public class FantasyPoolService {

    private final FantasyPoolRepository poolRepository;
    private final org.example.footballmanager.newLogic.repository.FantasyPlayerScoreRepository playerScores;

    @Transactional(readOnly = true)
    public FantasyPool poolFor(Long countryId, Integer seasonYear) {
        List<FantasyPoolRow> rows = poolRepository.rowsForCountry(countryId);

        // What each player has scored so far, so the picker's Points column needs no second read. A player
        // who has not scored appears nowhere in the map, and the default of 0 is the honest answer: a
        // gameweek that has not been played is not a blank.
        java.util.Map<Long, Integer> pointsByPlayer = new java.util.HashMap<>();
        if (seasonYear != null) {
            for (FantasyPlayerPoints total : playerScores.seasonTotals(seasonYear)) {
                pointsByPlayer.put(total.playerId(), total.points() == null ? 0 : total.points().intValue());
            }
        }

        // Grouped by tier and fantasy position, because that is the band the price is derived in. A player
        // with no tier cannot be placed in the three-per-tier rule at all, so he is not in the pool.
        Map<Band, List<FantasyPoolRow>> bands = new LinkedHashMap<>();
        TreeSet<Integer> tiers = new TreeSet<>();
        for (FantasyPoolRow row : rows) {
            if (row.tier() == null) {
                continue;
            }
            tiers.add(row.tier());
            bands.computeIfAbsent(new Band(row.tier(), FantasyRules.fantasyPositionOf(row.position())),
                    key -> new ArrayList<>()).add(row);
        }

        List<FantasyPoolPlayer> priced = new ArrayList<>(rows.size());
        for (Map.Entry<Band, List<FantasyPoolRow>> entry : bands.entrySet()) {
            Band band = entry.getKey();
            List<FantasyPoolRow> members = entry.getValue();
            members.sort(Comparator.comparingInt(FantasyPoolRow::rating).reversed()
                    .thenComparing(FantasyPoolRow::playerId));

            for (int rank = 0; rank < members.size(); rank++) {
                FantasyPoolRow row = members.get(rank);
                priced.add(new FantasyPoolPlayer(
                        row.playerId(),
                        row.name(),
                        row.position(),
                        band.fantasyPosition(),
                        row.rating(),
                        band.tier(),
                        row.clubId(),
                        row.clubName(),
                        FantasyPricing.price(band.tier(), band.fantasyPosition(), rank, members.size()),
                        pointsByPlayer.getOrDefault(row.playerId(), 0)));
            }
        }

        return new FantasyPool(countryId, tiers.size(), priced);
    }

    /** The band a price is derived in: one tier, one fantasy position. */
    private record Band(int tier, Position fantasyPosition) {
    }
}
