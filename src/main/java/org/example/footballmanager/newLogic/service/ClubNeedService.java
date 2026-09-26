package org.example.footballmanager.newLogic.service;

import lombok.RequiredArgsConstructor;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.PlayerContract;
import org.example.footballmanager.newLogic.model.SquadRole;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.PlayerContractRepository;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Why a club would want a particular player (Sprint 3.6).
 *
 * <p>The buyer used to be picked uniformly at random and the price was a dice roll around market
 * value, which meant a club with eleven strikers bid for a ninety-fourth-minute left back, and a rich
 * club bid for players it had no use for. A market that behaves like that does not feel like a
 * market: it feels like noise.
 *
 * <p>So a bid is now a position, not a roll. A club looks at its own squad, works out where the gaps
 * are, and rates the player against the gap he would fill. The gaps come from the positions on the
 * pitch and from the quality of what is already there — a club full of 30-year-olds has a genuine
 * need for a 21-year-old, whether or not anyone wrote "rebuild" anywhere.
 *
 * <h2>What a club pays</h2>
 * Value is the starting point, not the answer. Age, the length of the player's contract, his form,
 * and how crowded his own position already is all move the number, which is why two clubs can
 * reasonably disagree about the same player and why a selling club can actually get a premium.
 */
@Service
@RequiredArgsConstructor
public class ClubNeedService {

    /** A squad with fewer than this many players in a position has a real gap in it. */
    private static final int THIN_POSITION = 2;

    /** A position is considered thin at all if it has fewer than this many. */
    private static final int MIN_USEFUL_DEPTH = 3;

    private final PlayerRepository players;
    private final PlayerContractRepository contracts;

    /**
     * How badly a club wants this player, from 0 (not at all) to 1 (would sign him tomorrow).
     *
     * <p>Zero means there is no interest, and callers should treat that as "do not bid" rather than
     * as "bid a token amount".
     */
    @Transactional(readOnly = true)
    public double interest(Team club, Player target) {
        if (club == null || target == null) return 0;
        if (target.getPosition() == null) return 0;
        // A club has no interest in its own player.
        if (target.getTeam() != null && club.getId() != null
                && java.util.Objects.equals(target.getTeam().getId(), club.getId())) {
            return 0;
        }

        List<Player> squad = clubSquad(club);
        String position = target.getPosition().name();

        long inPosition = squad.stream()
                .filter(p -> p.getPosition() != null && p.getPosition().name().equals(position))
                .count();
        if (inPosition >= MIN_USEFUL_DEPTH && !isBetterThanSquadInPosition(squad, position, target)) {
            // Covered, and nobody there is worse than him: no interest.
            return 0;
        }

        double score = 0.35;                              // some interest by default
        if (inPosition <= THIN_POSITION) {
            score += 0.35 - 0.12 * inPosition;            // the gap is the main reason
        }
        if (isBetterThanSquadInPosition(squad, position, target)) {
            score += 0.25;                                // an upgrade on what he has
        }
        if (isGettingOld(club, position)) {
            score += 0.15;                                // the position needs replacing
        }
        if (isYouth(squad)) {
            score += 0.05;                                // a young squad wants young players
        }
        if (target.getAge() <= 23) {
            score += 0.10;                                // and there is resale value in that
        }
        return Math.max(0, Math.min(1, score));
    }

    /**
     * What a club will offer, which is not the same as what the player is worth.
     *
     * <p>Interest sets the direction, value sets the level, and the club's standing decides how far
     * above value it is willing to go for someone it really wants.
     */
    @Transactional(readOnly = true)
    public double valuation(Team club, Player target) {
        double value = target.getPlayerValue();
        double appetite = interest(club, target);
        if (appetite <= 0) return 0;

        // Age: a curve peaking in the mid-twenties. Buying a 33-year-old at his 2007 price is how a
        // club ends up paying for last season.
        {
            int age = target.getAge();
            if (age <= 20) value *= 1.05;
            else if (age <= 27) value *= 1.00;
            else if (age <= 30) value *= 0.82;
            else if (age <= 33) value *= 0.55;
            else value *= 0.30;
        }

        // Contract length: two years of control is worth paying for, a free agent in six months is
        // worth much less than one still tied to the club for two seasons.
        PlayerContract contract = contracts.findByPlayerId(target.getId()).orElse(null);
        if (contract != null && contract.getExpirySeason() != null) {
            int yearsLeft = contract.getExpirySeason() - currentSeason();
            if (yearsLeft <= 0) value *= 0.70;            // expiring: he walks for nothing
            else if (yearsLeft >= 3) value *= 1.15;
        }

        // Form: a player in the form of his life is worth what he is doing now.
        {
            if (target.getForm() >= 8.0) value *= 1.10;
            else if (target.getForm() <= 4.0) value *= 0.90;
        }

        // A club that really wants him stretches; a club that could take him or leave him does not.
        double reputation = club.getReputation() == null ? 50 : club.getReputation();
        double stretch = 0.85 + (reputation / 100.0) * 0.45;
        return round2(value * stretch * (0.85 + appetite * 0.35));
    }

    /**
     * Picks the player a club would most like to sign.
     *
     * <p>Appetite first, value only as a tiebreak. Multiplying the two — which is what this did at
     * first — lets a price tag override a position: a club that badly needs a centre back will chase
     * a €20m midfielder it already has three of, because 0.6 × 20m beats 0.8 × 2m. That is not how a
     * manager thinks, and it is how a club ends up with eleven of the same player.
     */
    @Transactional(readOnly = true)
    public Player bestTarget(Team club, List<Player> candidates) {
        Player best = null;
        double bestAppetite = 0;
        for (Player candidate : candidates) {
            double appetite = interest(club, candidate);
            if (best == null || appetite > bestAppetite + APPETITE_TIE_MARGIN) {
                best = candidate;
                bestAppetite = appetite;
            } else if (appetite >= bestAppetite - APPETITE_TIE_MARGIN
                    && candidate.getPlayerValue() > best.getPlayerValue()) {
                // Wants him about as much, and he is worth more: take the better player.
                best = candidate;
                bestAppetite = Math.max(bestAppetite, appetite);
            }
        }
        return best;
    }

    /** How close two appetites have to be before value is allowed to break the tie. */
    private static final double APPETITE_TIE_MARGIN = 0.05;

    /**
     * The positions where a club is thin, worst first. Useful for a board report and for the AI.
     *
     * <p>Built from the {@link org.example.footballmanager.newLogic.model.Position} values rather
     * than a hand-written list. The hand-written one was Football Manager's eleven positions
     * (CB, LB, RB, AM, LW, RW, ST) while this codebase has five (GK, DEF, MID, ATT, WNG), so every
     * report came back saying the club had no goalkeeper, no striker and no full back - positions
     * that do not exist here - while silently never reporting a real gap.
     */
    @Transactional(readOnly = true)
    public List<String> gaps(Team club) {
        List<Player> squad = clubSquad(club);
        List<String> thin = new ArrayList<>();
        for (String position : Arrays.stream(
                org.example.footballmanager.newLogic.model.Position.values())
                .map(Enum::name)
                .toList()) {
            long count = squad.stream()
                    .filter(p -> p.getPosition() != null && p.getPosition().name().equals(position))
                    .count();
            if (count < MIN_USEFUL_DEPTH) thin.add(position);
        }
        thin.sort(Comparator.comparingLong(position -> squad.stream()
                .filter(p -> p.getPosition() != null && p.getPosition().name().equals(position))
                .count()));
        return thin;
    }

    private List<Player> clubSquad(Team club) {
        if (club == null || club.getId() == null) return List.of();
        return players.findByTeamId(club.getId()).stream()
                .filter(p -> p != null && p.getId() != null)
                .toList();
    }

    private boolean isBetterThanSquadInPosition(List<Player> squad, String position, Player target) {
        return squad.stream()
                .filter(p -> p.getPosition() != null && p.getPosition().name().equals(position))
                .anyMatch(p -> value(p) < value(target));
    }

    private boolean isGettingOld(Team club, String position) {
        double averageAge = clubSquad(club).stream()
                .filter(p -> p.getPosition() != null && p.getPosition().name().equals(position))
                .mapToInt(Player::getAge)
                .average()
                .orElse(0);
        return averageAge >= 29;
    }

    private boolean isYouth(List<Player> squad) {
        double averageAge = squad.stream()
                .mapToInt(Player::getAge)
                .average()
                .orElse(99);
        return averageAge <= 24;
    }

    private double value(Player p) {
        return p.getPlayerValue();
    }

    private int currentSeason() {
        return 1;
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
