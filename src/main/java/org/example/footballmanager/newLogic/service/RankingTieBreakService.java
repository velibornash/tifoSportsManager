package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.RankingTieBreakSeed;
import org.example.footballmanager.newLogic.repository.RankingTieBreakSeedRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * The coin that settles a ranking ladder when the points run out (owner, 2026-10-08).
 *
 * <p><b>The owner's rule:</b> equal totals get <b>distinct</b> positions, never a shared rank and never
 * alphabetical. Two clubs on the same points are not tied in football — the ledger has run out of things
 * to separate them — so what is left is a draw, and the draw has to be the same every time the ladder is
 * read. A coin re-rolled per read is a ladder that reorders itself while nobody is watching, which is the
 * defect {@link NationalGroupTieBreak} exists to prevent for a group table.
 *
 * <p>The seed is written once per ladder and read from here afterwards. It is derived from the season and
 * the ladder's own subject on first use and then <b>stored</b>, so a fresh install and a restored backup
 * land on the same coin and the answer can be shown and audited rather than being arithmetic.
 */
@Service
public class RankingTieBreakService {

    private final RankingTieBreakSeedRepository seeds;

    public RankingTieBreakService(RankingTieBreakSeedRepository seeds) {
        this.seeds = seeds;
    }

    /**
     * This ladder's coin, writing it the first time it is asked for.
     *
     * @param subjectKey the country id for a club ladder, empty for the national one
     */
    @Transactional
    public long seedFor(RankingTieBreakSeed.Scope scope, int seasonYear, String subjectKey) {
        String key = subjectKey == null ? "" : subjectKey;
        return seeds.findByScopeAndSeasonYearAndSubjectKey(scope, seasonYear, key)
                .map(RankingTieBreakSeed::getSeed)
                .orElseGet(() -> store(scope, seasonYear, key));
    }

    /**
     * One team's draw from the ladder's coin.
     *
     * <p>Mixing the stored seed with the team id gives every team its own stable draw from the one stored
     * number, which is what makes the coin replayable. A shuffle would depend on the order the list
     * arrived in, so two reads of the same ladder could disagree.
     */
    public long coin(long seed, Long teamId) {
        long id = teamId == null ? 0L : teamId;
        long z = seed + 0x9E3779B97F4A7C15L * (id + 1);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** The country ladder's coin, for the national ranking. */
    @Transactional
    public long countrySeed(int seasonYear) {
        return seedFor(RankingTieBreakSeed.Scope.COUNTRY, seasonYear, "");
    }

    /** One country's club ladder, which draws from its own coin rather than the world's. */
    @Transactional
    public long clubSeed(int seasonYear, Long countryId) {
        return seedFor(RankingTieBreakSeed.Scope.CLUB, seasonYear,
                countryId == null ? "" : String.valueOf(countryId));
    }

    private long store(RankingTieBreakSeed.Scope scope, int seasonYear, String subjectKey) {
        RankingTieBreakSeed row = new RankingTieBreakSeed();
        row.setScope(scope);
        row.setSeasonYear(seasonYear);
        row.setSubjectKey(subjectKey);
        row.setSeed(deriveSeed(scope, seasonYear, subjectKey));
        row.setDrawnAt(Instant.now());
        seeds.save(row);
        return row.getSeed();
    }

    /**
     * The first seed a ladder would have had, derived from what identifies it.
     *
     * <p>Derived rather than random so that the stored value is reproducible: if two fresh databases each
     * write their own coin at the same moment, they write the same one.
     */
    private long deriveSeed(RankingTieBreakSeed.Scope scope, int seasonYear, String subjectKey) {
        long z = 0x243F6A8885A308D3L;
        z = z * 31 + seasonYear;
        z = z * 31 + scope.ordinal();
        z = z * 31 + (subjectKey == null ? 0 : subjectKey.hashCode());
        z = (z ^ (z >>> 33)) * 0xFF51AFD7ED558CCDL;
        return z ^ (z >>> 33);
    }
}