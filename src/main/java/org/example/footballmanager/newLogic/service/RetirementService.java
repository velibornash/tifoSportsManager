package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.PlayerContract;
import org.example.footballmanager.newLogic.repository.PlayerContractRepository;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Players hang up their boots (P2-7).
 *
 * <p><b>Before this, nobody ever left.</b> A player aged once a year by
 * {@code PlayerRepository.incrementAgeForAllPlayers} and there was no other exit from a squad except a
 * transfer or a contract expiring. So a 32-year-old became 60, then 90, stayed at his club, stayed on
 * the transfer list, and {@code ClubNeedService} kept pricing him at 30% of value and bidding for him.
 * The player pool only ever grew, which is why the pyramid could not turn over and why an archived
 * audit could point at a 93-year-old as the all-time hat-trick record holder.
 *
 * <p><b>Retirement is a quality-scaled band, not a birthday.</b> A 35-year-old goalkeeper and a
 * 35-year-old winger are not the same footballer, and a 35-year-old who is still the best player in
 * the league is not the same player as a 35-year-old who is fourth-best at his position. The bands are
 * set against the real rating scale — {@code Player.rating} is 0-100, peaking around 68 on the dev
 * world — so the cut points are 85 / 75 / 65, not the 0-10 scale the skills use.
 *
 * <p><b>He leaves; he is not deleted.</b> The player row, his statistics and his history all survive.
 * {@code Team.removePlayer} would have been the obvious call and it is the wrong one: {@code
 * Team.players} is mapped {@code orphanRemoval = true}, so removing him from that collection makes
 * Hibernate <b>delete the row</b> on flush. That is why the method has had no callers since it was
 * written. This service does what contract expiry does instead — null the club, end the contract —
 * and leaves the history alone.
 */
@Service
public class RetirementService {

    private static final Logger log = LoggerFactory.getLogger(RetirementService.class);

    /** The earliest anyone here stops, and the latest. Four seasons, scaled by quality. */
    public static final int EARLIEST_RETIREMENT_AGE = 33;
    public static final int LATEST_RETIREMENT_AGE = 36;

    /** Rating cut points, on the real 0-100 scale. */
    private static final int ELITE_RATING = 85;
    private static final int TOP_RATING = 75;
    private static final int SOLID_RATING = 65;

    private final PlayerRepository players;
    private final PlayerContractRepository contracts;

    public RetirementService(PlayerRepository players, PlayerContractRepository contracts) {
        this.players = players;
        this.contracts = contracts;
    }

    /**
     * The age this player stops playing.
     *
     * <p>Public and pure, because it is the rule a manager is entitled to know: a club plans a squad
     * around when a player ends, and a mechanic nobody can predict is not a mechanic.
     */
    public int retirementAge(Player player) {
        if (player == null) {
            return LATEST_RETIREMENT_AGE;
        }
        int rating = player.getRating();
        if (rating >= ELITE_RATING) return 36;
        if (rating >= TOP_RATING) return 35;
        if (rating >= SOLID_RATING) return 34;
        return EARLIEST_RETIREMENT_AGE;
    }

    /**
     * Retires every club player who has reached his own retirement age.
     *
     * <p>Called once a season, immediately after the age increment, so a player who turns 33 this year
     * and is a 33-year-old journeyman stops in the same pass.
     *
     * @param season the season being entered, recorded on each player who retires
     * @return how many retired
     */
    @Transactional
    public int retireOverduePlayers(int season) {
        // Only club players, and only those still playing: a retired player has no club and is skipped
        // by the query, so this sweep is idempotent and can be run twice without consequence.
        List<Player> clubPlayers = players.findActiveClubPlayers();
        int retired = 0;
        for (Player player : clubPlayers) {
            if (player == null || player.getId() == null || player.isRetired()) {
                continue;
            }
            if (player.getAge() < retirementAge(player)) {
                continue;
            }
            retire(player, season);
            retired++;
        }
        if (retired > 0) {
            log.info("Season {}: {} player(s) retired", season, retired);
        }
        return retired;
    }

    /**
     * Ends one player's career. The row stays; the club does not.
     *
     * <p>The contract is ended as well as the club, and that matters: {@code canRegister} counts
     * {@code contracts.findByTeamId}, so a retired player left holding a contract would occupy one of
     * the 25 senior registration slots for ever, and a club could never replace him.
     */
    private void retire(Player player, int season) {
        String name = player.getName();
        Integer age = player.getAge();
        player.setRetiredSeason(season);
        player.setTeam(null);
        player.setEarnings(0);
        players.save(player);

        PlayerContract contract = contracts.findByPlayerId(player.getId()).orElse(null);
        if (contract != null && contract.getTeam() != null) {
            contract.setTeam(null);
            contracts.save(contract);
        }
        log.info("{} retired at {} after the {} season", name, age, season);
    }
}