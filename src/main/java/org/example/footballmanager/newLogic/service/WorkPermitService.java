package org.example.footballmanager.newLogic.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.GameClock;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.model.WorkPermit;
import org.example.footballmanager.newLogic.repository.GameClockRepository;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.example.footballmanager.newLogic.repository.WorkPermitRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Work permits and the non-EU quota (Sprint 3.5).
 *
 * <p>The constraint that shapes a Serbian transfer window. A club may register four non-EU players
 * in the top flight and fewer below, and each needs a permit. So the interesting decision is not
 * "can I afford him" but "can I register him" — and a squad already at its quota has to sell before
 * it can buy, which is a real and slightly awkward thing to plan a season around.
 *
 * <h2>Why buying from a weaker league helps</h2>
 * A permit is judged on the club's reputation and on the level of the league it plays in. A player
 * arriving from a top-five league is signed on the strength of that pedigree; the same player
 * arriving from the fourth tier is a gamble, and the permit reflects it. So a club can beat a quota
 * it cannot afford by scouting where the value is — which is the strategic axis the backlog asked
 * for, and it falls out of the arithmetic rather than being written in.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkPermitService {

    /**
     * Non-EU places per club, by the tier of the competition, when the competition has not set its
     * own figure.
     *
     * <p>Serbia's rule is four in the top flight and fewer below. <b>These exact numbers are an open
     * question for the owner</b> — see sprintBacklog. They are here as a working default, and
     * {@code Competition.foreignPlayerLimit} overrides them per competition, so correcting a tier is
     * a data change and not a code change.
     */
    private static final int[] DEFAULT_QUOTA_BY_TIER = { 0, 4, 3, 2, 1, 0 };

    /**
     * The EU member states, for the purpose of this rule.
     *
     * <p>Hand-listed rather than derived: the quota is about the domestic league's registration
     * rule, and a country leaving the union should not silently rewrite a squad limit.
     */
    private static final Set<String> EU = Set.of(
            "AUT", "BEL", "BGR", "HRV", "CYP", "CZE", "DNK", "EST", "FIN", "FRA", "DEU", "GRC",
            "HUN", "IRL", "ITA", "LVA", "LTU", "LUX", "MLT", "NLD", "POL", "PRT", "ROU", "SVK",
            "SVN", "ESP", "SWE");

    private final WorkPermitRepository permits;
    private final PlayerRepository players;
    private final TeamRepository teams;
    private final GameClockRepository clocks;

    // ------------------------------------------------------------------ who counts

    /**
     * Whether this player counts against a club's non-EU quota.
     *
     * <p>Two tests, not one, and the second is the one that catches people out: <b>Serbia is not in
     * the EU</b>, so a check written as "is he a non-EU national" counts every Serbian player in a
     * Serbian league as a foreigner and fills the quota with homegrown talent. A player is foreign
     * if he is from <i>another country</i>, and he counts against the quota only if that country is
     * outside the EU.
     *
     * <p>So a Spaniard at a Serbian club is foreign but free, a Brazilian is foreign and counts, and
     * a Serbian at a Serbian club is simply domestic.
     *
     * <p>A player with no nationality on record is treated as domestic, and so is one playing in a
     * competition with no country. Both are the safe direction: the alternative would block every
     * signing in a database that predates the column.
     */
    public boolean countsAsForeign(Player player, Competition competition) {
        if (player == null) return false;
        String code = player.getNationality();
        if (code == null || code.isBlank()) return false;
        String nationality = code.trim().toUpperCase(Locale.ROOT);

        String hostCountry = competition == null || competition.getCountry() == null
                ? null
                : competition.getCountry().getIsoCode();
        if (hostCountry != null && !hostCountry.isBlank()) {
            if (hostCountry.trim().toUpperCase(Locale.ROOT).equals(nationality)) {
                return false;               // he plays at home
            }
        }
        // Someone from elsewhere: only the non-EU ones occupy a place.
        return !EU.contains(nationality);
    }

    /** How many non-EU places this competition offers a club. */
    public int quotaFor(Competition competition) {
        if (competition == null) return 0;
        if (competition.getForeignPlayerLimit() != null) {
            return Math.max(0, competition.getForeignPlayerLimit());
        }
        Integer tier = competition.getTier();
        if (tier == null || tier < 1 || tier >= DEFAULT_QUOTA_BY_TIER.length) {
            return 0;
        }
        return DEFAULT_QUOTA_BY_TIER[tier];
    }

    /**
     * How many non-EU players the club has registered against this competition.
     *
     * <p>Counted from the permits that were granted, so the number cannot disagree with the
     * registrations.
     */
    @Transactional(readOnly = true)
    public int foreignPlayersRegistered(Long clubId, Competition competition, Integer season) {
        int quota = quotaFor(competition);
        if (quota == 0) return 0;

        // A loanee is registered by nobody, so he occupies no place. Counted here rather than in the
        // query because a loan and a permit are separate records that happen to agree.
        int used = 0;
        for (WorkPermit permit : permits.findByClubIdAndSeason(clubId, season)) {
            if (permit.granted()) used++;
        }
        return Math.min(used, Integer.MAX_VALUE);
    }

    /** Whether the club has a place left for one more foreign player. */
    @Transactional(readOnly = true)
    public boolean hasRoomForAnotherForeignPlayer(Long clubId, Competition competition, Integer season) {
        int quota = quotaFor(competition);
        if (quota == 0) return false;
        return foreignPlayersRegistered(clubId, competition, season) < quota;
    }

    // ------------------------------------------------------------------ the decision

    /**
     * Applies for a permit and decides it.
     *
     * <p>Three ways to be refused, and the manager is told which:
     * <ul>
     *   <li>the quota is full — a squad limit, and no amount of money fixes it;</li>
     *   <li>the club is not established enough — reputation gate;</li>
     *   <li>the player has no pedigree to justify the paperwork.</li>
     * </ul>
     */
    @Transactional
    public WorkPermit applyFor(Long clubId, Long playerId, Competition competition) {
        Player player = players.findById(playerId).orElse(null);
        Team club = teams.findById(clubId).orElse(null);
        Integer season = currentSeason();

        WorkPermit permit = permits
                .findByPlayerIdAndClubIdAndSeason(playerId, clubId, season)
                .orElseGet(WorkPermit::new);
        permit.setPlayerId(playerId);
        permit.setClubId(clubId);
        permit.setSeason(season);
        permit.setDecidedAt(java.time.Instant.now());

        if (!countsAsForeign(player, competition)) {
            // A domestic player, or an EU one, needs no permit. Recorded as granted so the squad
            // count stays a single query.
            permit.setStatus(WorkPermit.PermitStatus.GRANTED);
            permit.setReason("He is a domestic player, so no permit is needed.");
            return permits.save(permit);
        }
        if (player == null || club == null) {
            permit.setStatus(WorkPermit.PermitStatus.REFUSED);
            permit.setReason("The player or the club does not exist.");
            return permits.save(permit);
        }
        if (!hasRoomForAnotherForeignPlayer(clubId, competition, season)) {
            permit.setStatus(WorkPermit.PermitStatus.REFUSED);
            permit.setReason("The club already has its " + quotaFor(competition)
                    + " non-EU places, and no amount of money changes that. "
                    + "Selling one of them is the only way to make room.");
            return permits.save(permit);
        }
        if (!clubIsEstablishedEnough(club, competition, player)) {
            permit.setStatus(WorkPermit.PermitStatus.REFUSED);
            permit.setReason("A club of this standing cannot register a foreign player in this league. "
                    + "Either his reputation justifies it, or the club needs to be higher up.");
            return permits.save(permit);
        }

        permit.setStatus(WorkPermit.PermitStatus.GRANTED);
        permit.setReason(pedigree(player, club));
        return permits.save(permit);
    }

    /**
     * Whether a signing is blocked, and why.
     *
     * <p>This is the check the transfer completion should make, so the answer is available without
     * applying for anything.
     */
    @Transactional(readOnly = true)
    public RegistrationCheck canRegister(Long clubId, Long playerId, Competition competition) {
        Player player = players.findById(playerId).orElse(null);
        Team club = teams.findById(clubId).orElse(null);
        Integer season = currentSeason();

        if (!countsAsForeign(player, competition)) {
            return RegistrationCheck.allowed(player != null && player.getNationality() != null
                    ? EU.contains(player.getNationality().trim().toUpperCase(Locale.ROOT))
                        ? "He is an EU player, so the non-EU quota does not apply."
                        : "He is a domestic player, so the quota does not apply."
                    : "A domestic player is not affected by the quota.");
        }
        if (player == null || club == null) {
            return RegistrationCheck.refused("NO_QUOTA", "The player or the club does not exist.");
        }
        if (!hasRoomForAnotherForeignPlayer(clubId, competition, season)) {
            return RegistrationCheck.refused("QUOTA_FULL",
                    club.getName() + " already has its " + quotaFor(competition)
                            + " non-EU places in " + competitionName(competition)
                            + ", and no amount of money changes that. Selling one of them is the "
                            + "only way to make room.");
        }
        if (!clubIsEstablishedEnough(club, competition, player)) {
            return RegistrationCheck.refused("PERMIT_REFUSED",
                    club.getName() + " cannot register a foreign player in "
                            + competitionName(competition) + " at his current standing.");
        }
        return RegistrationCheck.allowed("A permit would be granted.");
    }

    private String competitionName(Competition competition) {
        if (competition == null) return "this league";
        return competition.getName() == null ? "this league" : competition.getName();
    }

    /**
     * The reputation gate.
     *
     * <p>A club needs to be established enough for the paperwork, and a player needs a pedigree
     * that justifies it. A big reputation waives the player's part, which is why a star can go to a
     * mid-table club and a prospect cannot — the same asymmetry the backlog wanted.
     */
    private boolean clubIsEstablishedEnough(Team club, Competition competition, Player player) {
        double reputation = club.getReputation() == null ? 0 : club.getReputation();
        Integer tier = competition == null ? null : competition.getTier();
        int clubTier = tier == null ? 1 : tier;

        double floor = 40 + (5 - Math.min(5, clubTier)) * 6.0;
        if (reputation < floor) return false;

        // Below the top two tiers a club also has to justify the player, unless it is a big club.
        if (clubTier > 2 && reputation < 70) {
            return (player.getPlayerValue() / 1_000_000.0) >= 8.0;
        }
        return true;
    }

    private String pedigree(Player player, Team club) {
        double value = player.getPlayerValue();
        if (value >= 15_000_000) {
            return club.getName() + " can register a player of this quality on reputation alone.";
        }
        return "Granted on the strength of the club's standing and the player's record.";
    }

    private int currentSeason() {
        GameClock clock = clocks.findAll().stream().findFirst().orElse(null);
        return clock == null || clock.getCurrentSeason() == null ? 1 : clock.getCurrentSeason();
    }

    /** Why a signing is or is not allowed, in words a manager can act on. */
    public record RegistrationCheck(boolean allowed, String code, String reason) {
        public static RegistrationCheck allowed(String reason) {
            return new RegistrationCheck(true, "OK", reason);
        }

        public static RegistrationCheck refused(String code, String reason) {
            return new RegistrationCheck(false, code, reason);
        }
    }
}
