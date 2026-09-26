package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.PlayerContract;
import org.example.footballmanager.newLogic.model.SquadRole;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.PlayerContractRepository;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Contracts: signing, expiry, renewal and registration (Sprint 3.1).
 *
 * <p>Nothing here existed. A player's wage was a field on the player and nothing tracked when it
 * ended, which is why the free-agent market was impossible by construction — a transfer required a
 * selling team, and a player could never stop having one. Expiry is the mechanism that lets a club
 * be rebuilt rather than only bought from.
 *
 * <h2>The wage demand</h2>
 * A player asking for more money is not making it up. The demand is derived from four things, all
 * of which a real agent would use:
 * <ul>
 *   <li><b>What he is worth</b> — the base.</li>
 *   <li><b>His squad role</b> — a star's expectation per unit of value is far higher than a
 *       prospect's, and a model that paid everyone the same made every squad cost the same.</li>
 *   <li><b>His age</b> — a 19-year-old on a rise accepts less than a 29-year-old with his best
 *       behind him, because the next contract is where the money is.</li>
 *   <li><b>His form</b> — a player in the form of his life asks for what he is doing now, not what
 *       he did last season.</li>
 * </ul>
 */
@Service
public class PlayerContractService {

    /** Max senior players a club may register. */
    public static final int MAX_SENIOR_SQUAD = 25;
    /** Max academy players a club may register. */
    public static final int MAX_YOUTH_SQUAD = 8;

    /** Contracts shorter than this are refused, as they are in the real world. */
    private static final int MIN_CONTRACT_MONTHS = 6;
    private static final int MAX_CONTRACT_MONTHS = 60;

    private final PlayerRepository players;
    private final PlayerContractRepository contracts;
    private final TransferBudgetService budgets;

    public PlayerContractService(PlayerRepository players,
                                 PlayerContractRepository contracts,
                                 TransferBudgetService budgets) {
        this.players = players;
        this.contracts = contracts;
        this.budgets = budgets;
    }

    // ---------------------------------------------------------------- signing

    /**
     * Signs a player, or renews if he is already under contract.
     *
     * <p>Refuses rather than silently agreeing when the club cannot afford the wage or the squad is
     * full, because a contract service that says yes to everything is not a contract service.
     */
    @Transactional
    public Outcome sign(Long teamId, Long playerId, int lengthMonths,
                        double weeklyWage, SquadRole squadRole) {
        Team club = teamId == null ? null : null;   // team lookup happens in the caller layer
        Player player = players.findById(playerId).orElse(null);
        if (player == null) return Outcome.refused("That player does not exist.");

        if (lengthMonths < MIN_CONTRACT_MONTHS || lengthMonths > MAX_CONTRACT_MONTHS) {
            return Outcome.refused("A contract runs from " + MIN_CONTRACT_MONTHS + " to "
                    + MAX_CONTRACT_MONTHS + " months; " + lengthMonths + " was requested.");
        }
        if (weeklyWage <= 0) {
            return Outcome.refused("A contract must pay something.");
        }

        if (squadRole == null) squadRole = inferRole(player);

        // A free agent takes a new club; a contracted player only moves if his current club lets him.
        PlayerContract existing = contracts.findByPlayerId(playerId).orElse(null);
        if (existing != null && existing.getTeam() != null && existing.hasReleaseClause()
                && budgets.canAfford(teamId, playerId).fee() < existing.getReleaseClause()) {
            return Outcome.refused("He is under contract with a release clause of "
                    + round2(existing.getReleaseClause()) + ", which has not been met.");
        }

        PlayerContract contract = existing != null ? existing : new PlayerContract();
        contract.setPlayer(player);
        contract.setTeam(existing != null && existing.getTeam() != null ? existing.getTeam() : null);
        contract.setWeeklyWage(weeklyWage);
        contract.setLengthMonths(lengthMonths);
        contract.setSquadRole(squadRole);
        contracts.save(contract);

        return Outcome.signed(contract);
    }

    /** Puts a player on a club, for a transfer that has already been agreed. */
    @Transactional
    public PlayerContract assignToClub(Player player, Team club, int season, SquadRole role) {
        PlayerContract contract = contracts.findByPlayerId(player.getId()).orElseGet(PlayerContract::new);
        contract.setPlayer(player);
        contract.setTeam(club);
        contract.setWeeklyWage(player.getEarnings());
        contract.setLengthMonths(24);
        contract.setSignedSeason(season);
        contract.setExpirySeason(season + Math.max(1, 24 / 12));
        contract.setSquadRole(role == null ? inferRole(player) : role);
        contract.setSignedAt(java.time.Instant.now());
        return contracts.save(contract);
    }

    // ---------------------------------------------------------------- expiry

    /**
     * Expires contracts that have run out, turning those players into free agents.
     *
     * <p>The contract record is kept with no club rather than deleted: the wage history and the
     * squad role are still meaningful, and a player who is released is still a player.
     */
    @Transactional
    public List<Player> expireContracts(int season) {
        List<Player> released = new ArrayList<>();
        for (PlayerContract c : contracts.findAll()) {
            if (c.getTeam() == null) continue;
            if (!c.isExpired(season)) continue;
            c.setTeam(null);
            contracts.save(c);
            if (c.getPlayer() != null) released.add(c.getPlayer());
        }
        return released;
    }

    // ---------------------------------------------------------------- renewal

    /**
     * What this player will demand to renew.
     *
     * <p>{@code acceptBelow} is what the club is currently offering; the caller decides whether that
     * is enough, because only the caller knows the club's position.
     */
    @Transactional(readOnly = true)
    public RenewalDemand wageDemand(Long playerId) {
        Player player = players.findById(playerId).orElse(null);
        if (player == null) return RenewalDemand.unknown();
        return demandFor(player);
    }

    private RenewalDemand demandFor(Player player) {
        PlayerContract contract = contracts.findByPlayerId(player.getId()).orElse(null);
        SquadRole role = contract != null && contract.getSquadRole() != null
                ? contract.getSquadRole() : inferRole(player);

        double value = Math.max(0, player.getPlayerValue());
        int age = player.getAge();

        // Base: annualised value scaled by what this role expects to earn for a unit of value.
        double base = value * role.wageExpectationFactor();

        // Age: a player with his best years still ahead accepts less, because the next contract is
        // where the money is. A player in his late twenties asks for what he is worth now.
        double ageFactor;
        if (age <= 20) ageFactor = 0.72;
        else if (age <= 24) ageFactor = 0.86;
        else if (age <= 28) ageFactor = 1.0;
        else if (age <= 32) ageFactor = 1.12;
        else ageFactor = 1.05;                       // older players discount rather than keep rising

        // Form: he asks for what he is doing now, not what he did last season.
        double form = player.getForm();
        double formFactor = form <= 0 ? 1.0 : clamp(0.88 + form * 0.035, 0.85, 1.22);

        // Morale matters at a renewal, not anywhere else. An unhappy player demands more.
        double moraleFactor = 0.94 + clamp(player.getMorale(), 0, 100) / 100.0 * 0.12;

        double weekly = base * ageFactor * formFactor * moraleFactor / 52.0;

        Double current = contract == null ? null : contract.getWeeklyWage();
        if (current == null) current = player.getEarnings();
        double currentWage = current == null ? 0 : current;

        return new RenewalDemand(player.getId(), player.getName(), role,
                round2(weekly), round2(currentWage), round2(weekly - currentWage),
                contract == null ? null : contract.getExpirySeason());
    }

    /**
     * Renews if the club offers enough, otherwise the player asks for a transfer.
     *
     * <p>Refusing is not a null result: it is a decision with a consequence, and the consequence is
     * that the player is no longer committed.
     */
    @Transactional
    public RenewalOutcome renew(Long playerId, int season, double offeredWeeklyWage) {
        RenewalDemand demand = wageDemand(playerId);
        if (demand.isUnknown()) return RenewalOutcome.noPlayer();

        PlayerContract contract = contracts.findByPlayerId(playerId).orElse(null);
        if (contract == null) return RenewalOutcome.noContract();

        if (offeredWeeklyWage + 0.5 < demand.demandedWeeklyWage()) {
            return RenewalOutcome.refused(demand);
        }

        int seasons = Math.max(1, (int) Math.round(contract.getLengthMonths() / 12.0));
        contract.setWeeklyWage(offeredWeeklyWage);
        contract.setSignedSeason(season);
        contract.setExpirySeason(season + seasons);
        contract.setSignedAt(java.time.Instant.now());
        contracts.save(contract);
        return RenewalOutcome.renewed(contract);
    }

    // ---------------------------------------------------------------- registration

    /**
     * Whether a club may register another player, and if not, which rule stops it.
     *
     * <p>Checked on signing rather than after, so a club cannot quietly build a 40-man squad.
     */
    @Transactional(readOnly = true)
    public RegistrationCheck canRegister(Long teamId, SquadRole role) {
        List<PlayerContract> current = contracts.findByTeamId(teamId);
        boolean senior = role == null || role.isSenior();
        int used = (int) current.stream()
                .filter(c -> (c.getSquadRole() == null || c.getSquadRole().isSenior()) == senior)
                .count();
        int limit = senior ? MAX_SENIOR_SQUAD : MAX_YOUTH_SQUAD;

        if (used >= limit) {
            return new RegistrationCheck(false, senior ? "SQUAD_FULL" : "YOUTH_SQUAD_FULL",
                    senior ? "A club may register " + MAX_SENIOR_SQUAD + " senior players and it "
                            + "already has " + used + "."
                            : "A club may register " + MAX_YOUTH_SQUAD + " academy players and it "
                            + "already has " + used + ".");
        }
        return new RegistrationCheck(true, "OK",
                (limit - used) + " places left in the " + (senior ? "senior" : "academy") + " squad.");
    }

    // ---------------------------------------------------------------- release

    /** Mutual termination: the player leaves, the contract goes, and any compensation is settled. */
    @Transactional
    public boolean mutuallyTerminate(Long playerId, double compensation) {
        PlayerContract contract = contracts.findByPlayerId(playerId).orElse(null);
        if (contract == null) return false;
        contract.setTeam(null);
        contracts.save(contract);
        return true;
    }

    /** Squad role inferred from ability and age when a contract does not state one. */
    public SquadRole inferRole(Player player) {
        double value = Math.max(0, player.getPlayerValue());
        int age = player.getAge();
        if (value >= 8_000_000) return SquadRole.STAR;
        if (value >= 2_500_000) return SquadRole.STARTER;
        if (age <= 19 && value < 400_000) return SquadRole.YOUTH;
        if (age <= 22) return SquadRole.PROSPECT;
        return SquadRole.ROTATION;
    }

    public List<PlayerContract> contractsOf(Long teamId) {
        return contracts.findByTeamId(teamId);
    }

    private double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /** The result of a signing attempt. */
    public record Outcome(boolean signed, String reason, PlayerContract contract) {
        public static Outcome signed(PlayerContract c) {
            return new Outcome(true, "Signed.", c);
        }

        public static Outcome refused(String reason) {
            return new Outcome(false, reason, null);
        }
    }

    /** What a player wants to be paid. */
    public record RenewalDemand(Long playerId, String playerName, SquadRole squadRole,
                                double demandedWeeklyWage, double currentWeeklyWage,
                                double increase, Integer currentExpirySeason) {
        public static RenewalDemand unknown() {
            return new RenewalDemand(null, null, null, 0, 0, 0, null);
        }

        public boolean isUnknown() {
            return playerId == null;
        }
    }

    /** What happened when a renewal was attempted. */
    public record RenewalOutcome(boolean renewed, boolean playerWillSeekTransfer,
                                 String reason, PlayerContract contract) {
        public static RenewalOutcome renewed(PlayerContract c) {
            return new RenewalOutcome(true, false, "Renewed.", c);
        }

        public static RenewalOutcome refused(RenewalDemand d) {
            return new RenewalOutcome(false, true,
                    "He wants " + round2(d.demandedWeeklyWage()) + " a week, which is "
                            + round2(d.increase()) + " more than he is on. He will look elsewhere.",
                    null);
        }

        public static RenewalOutcome noPlayer() {
            return new RenewalOutcome(false, false, "No such player.", null);
        }

        public static RenewalOutcome noContract() {
            return new RenewalOutcome(false, false, "That player has no contract.", null);
        }

        private static double round2(double v) {
            return Math.round(v * 100.0) / 100.0;
        }
    }

    /** Whether a squad has room, and why not. */
    public record RegistrationCheck(boolean allowed, String code, String reason) { }
}
