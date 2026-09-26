package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.PlayerContract;
import org.example.footballmanager.newLogic.model.SquadRole;
import org.example.footballmanager.newLogic.repository.PlayerContractRepository;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Brings the world up to date with contracts (Sprint 3.1).
 *
 * <p>Every existing player needs a contract before the market means anything, and the terms have to
 * be <em>plausible</em> rather than uniform: a 19-year-old academy product on a five-year deal and a
 * 33-year-old free agent on a one-year deal are both normal, and a database where all 4,000 players
 * have identical two-year contracts makes renewal a formality.
 *
 * <p>Lengths are derived from the player's id rather than drawn at random, so this is safe to run
 * repeatedly and does not reshuffle the world between deployments.
 */
@Service
public class ContractBackfillService {

    private final PlayerRepository players;
    private final PlayerContractRepository contracts;
    private final PlayerContractService contractService;

    public ContractBackfillService(PlayerRepository players,
                                   PlayerContractRepository contracts,
                                   PlayerContractService contractService) {
        this.players = players;
        this.contracts = contracts;
        this.contractService = contractService;
    }

    /**
     * Gives every player without a contract one.
     *
     * @return how many were created
     */
    @Transactional
    public int backfill(int currentSeason) {
        // One query for the players who need a contract, rather than a lookup per player over the
        // whole table. The original loop was the single slowest thing in the week advance.
        List<Player> needing = contracts.findPlayersWithoutContract();
        int created = 0;
        for (Player p : needing) {
            if (p.getId() == null) continue;
            if (p.getTeam() == null || p.getTeam().getId() == null) continue;
            createFor(p, currentSeason);
            created++;
        }
        return created;
    }

    private void createFor(Player player, int currentSeason) {
        SquadRole role = contractService.inferRole(player);
        int lengthYears = plausibleLengthYears(player, role);

        PlayerContract c = new PlayerContract();
        c.setPlayer(player);
        c.setTeam(player.getTeam());
        c.setWeeklyWage(player.getEarnings());
        c.setLengthMonths(lengthYears * 12);
        c.setSignedSeason(currentSeason - stable(player.getId(), 1) % 3);
        c.setExpirySeason(currentSeason + lengthYears);
        c.setSquadRole(role);
        // A release clause on roughly the top third of a club's squad, as there is in reality.
        c.setReleaseClause(releaseClauseFor(player, role));
        contracts.save(c);
    }

    /**
     * Contract length in years.
     *
     * <p>Young players get longer deals — a club is betting on the curve — and older players get
     * shorter ones, because nobody signs a 34-year-old for four years. Stars get slightly longer
     * than squad players, since a club that wants to keep one is usually buying the years.
     */
    private int plausibleLengthYears(Player player, SquadRole role) {
        int age = player.getAge();
        int base;
        if (age <= 21) base = 5;
        else if (age <= 25) base = 4;
        else if (age <= 29) base = 3;
        else if (age <= 32) base = 2;
        else base = 1;

        if (role == SquadRole.STAR) base += 1;
        if (role == SquadRole.YOUTH) base = Math.max(base, 4);

        int jitter = stable(player.getId(), 2) % 2;
        return Math.max(1, Math.min(6, base - jitter));
    }

    private Double releaseClauseFor(Player player, SquadRole role) {
        if (role != SquadRole.STAR && role != SquadRole.STARTER) return null;
        if (stable(player.getId(), 3) % 3 != 0) return null;      // about a third
        double value = Math.max(0, player.getPlayerValue());
        return (double) Math.round(value * 2.0);
    }

    /** A stable per-player value in 0..n-1. Not a random draw. */
    private int stable(Long id, int salt) {
        if (id == null) return 0;
        long h = id * 0x9E3779B97F4A7C15L + salt * 0x165667B19E3779F9L;
        h ^= (h >>> 33);
        return (int) Math.abs(h % 1000);
    }
}
