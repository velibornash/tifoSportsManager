package org.example.footballmanager.newLogic.util;

import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.CompetitionType;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Position;
import org.example.footballmanager.newLogic.model.Skills;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.example.footballmanager.newLogic.util.players.BotLeagueStandard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Re-standards the squads of the clubs already in the database (owner, 2026-09-30).
 *
 * <p>The generator now builds a bot club to its division's standard, but the 310 clubs already seeded
 * were built by the old uniform draw. Measured on the live world before this ran: tier 1 → 8.61,
 * tier 2 → 8.71, tier 3 → 8.57, tier 4 → 8.56, tier 5 → 8.57, with the same 2.9-to-14.0 spread in
 * every one. A flat pyramid means promotion and relegation decide a table on reputation, so fixing it
 * only in the generator would leave every world someone is already playing wrong until they reset.
 *
 * <p><b>Human clubs are never touched.</b> The two hand-authored clubs — Omladinac and Sremac — have
 * named players with written skill rows, and Omladinac is the manager's own team. A backfill that
 * quietly re-rolled them would destroy the one squad in the game the owner has actually watched.
 * {@code Team.humanControlled} is the gate.
 *
 * <p><b>Deterministic.</b> Seeded from the club's name, so a given club is re-standardised to the
 * same squad on every machine and every run. The same reasoning as the national-side bot squads: a
 * side that re-rolls between boots is impossible to debug.
 *
 * <p><b>Its own transaction.</b> This runs inside the boot listener's transaction, and joining it meant
 * the saves were rolled back with it — the log said it had re-standardised 4,620 players and the
 * skills table did not move. This is the third time that lesson has cost a session on this codebase.
 */
@Component
public class BotLeagueStandardBackfill {

    private static final Logger log = LoggerFactory.getLogger(BotLeagueStandardBackfill.class);

    private final TeamRepository teams;
    private final PlayerRepository players;
    private final BotLeagueStandard standard;
    private final TransactionTemplate requiresNew;

    public BotLeagueStandardBackfill(TeamRepository teams,
                                     PlayerRepository players,
                                     BotLeagueStandard standard,
                                     PlatformTransactionManager transactionManager) {
        this.teams = teams;
        this.players = players;
        this.standard = standard;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** What one club's re-standardisation cost, so the boot log can prove it moved something. */
    public record Result(int clubs, int players, Map<Integer, Double> averageByTier) {
    }

    public Result backfill() {
        return requiresNew.execute(status -> run());
    }

    private Result run() {
        // Keyed by tier, so the boot log reads 1=12.1, 2=11.0 in tier order rather than hash order.
        Map<Integer, Double> tierTotals = new LinkedHashMap<>();
        Map<Integer, Integer> tierCounts = new LinkedHashMap<>();

        int clubCount = 0;
        int playerCount = 0;

        for (Team team : teams.findAll()) {
            if (team.isHumanControlled() || team.getCompetition() == null) {
                continue;
            }
            Competition competition = team.getCompetition();
            if (competition.getType() != CompetitionType.LEAGUE) {
                continue;
            }

            List<Player> squad = players.findByTeamId(team.getId());
            if (squad.isEmpty()) {
                continue;
            }

            Integer tier = competition.getTier();
            Random random = new Random(team.getName() == null
                    ? team.getId()
                    : team.getName().hashCode());

            for (Player player : squad) {
                Position position = player.getPosition() == null ? Position.MID : player.getPosition();
                Skills skills = standard.skillsForTier(
                        tier, position, standard.squadOffset(random), random);
                player.setSkills(skills);
                // Rating is derived, so it has to be restated with the skills or the stored column
                // keeps describing the old standard. This is the same trap as the generator's.
                player.setRating(player.careerRating());
            }
            players.saveAll(squad);

            clubCount++;
            playerCount += squad.size();
            accumulateTierAverage(tierTotals, tierCounts, tier, squad);
        }

        Map<Integer, Double> averages = new LinkedHashMap<>();
        tierTotals.forEach((tier, total) ->
                averages.put(tier, round(Math.round(total / tierCounts.get(tier) * 100.0) / 100.0)));

        Result result = new Result(clubCount, playerCount, averages);
        log.info("Bot league standards: re-standardised {} player(s) across {} club(s). Average by tier: {}",
                playerCount, clubCount, averages);
        return result;
    }

    private void accumulateTierAverage(Map<Integer, Double> totals,
                                       Map<Integer, Integer> counts,
                                       Integer tier,
                                       List<Player> squad) {
        int key = tier == null ? 0 : tier;
        double total = 0.0;
        for (Player player : squad) {
            Skills skills = player.getSkills();
            if (skills == null) {
                continue;
            }
            int sum = 0;
            for (var skill : BotLeagueStandard.FOOTBALL_SKILLS.keySet()) {
                sum += skills.visibleInt(skill);
            }
            total += sum / (double) BotLeagueStandard.FOOTBALL_SKILLS.size();
        }
        totals.merge(key, total, Double::sum);
        counts.merge(key, squad.size(), Integer::sum);
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
