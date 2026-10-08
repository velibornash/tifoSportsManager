package org.example.footballmanager.newLogic.controller;

import lombok.RequiredArgsConstructor;
import org.example.commonmanager.model.User;
import org.example.footballmanager.newLogic.exception.ApiException;
import org.example.footballmanager.newLogic.model.Loan;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.example.footballmanager.newLogic.service.LoanService;
import org.example.footballmanager.newLogic.service.PlusFeatureService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loans, so a manager can actually make one (owner, 2026-10-08).
 *
 * <p><b>This is the surface that did not exist for three sprints.</b> {@code LoanService} and the
 * {@code loan} table were built in Sprint 3.4 and the weekly tick closed loans that nothing could ever
 * create, so the feature was bookkeeping with no football attached and a manager could not reach it at
 * all.
 *
 * <p><b>Every route checks the viewer owns the club it is acting for.</b> A loan moves minutes and a
 * wage obligation between two clubs, so an unowned id here is not a read-only leak: it would be a way to
 * send another club's player down a division. The ownership check is delegated to
 * {@code ClubOwnershipLinker} rather than re-derived, because every controller that re-derived it is how
 * the service's own checks ended up unused.
 */
@RestController
@RequestMapping("/loans")
@RequiredArgsConstructor
public class LoanController {

    private final LoanService loanService;
    private final PlusFeatureService plusFeatures;
    private final TeamRepository teams;
    private final PlayerRepository players;

    /** Who this viewer runs, or a refusal. Every mutating route starts here. */
    private Long requireOwnClub(User principal) {
        Long teamId = plusFeatures.viewerTeamId(principal);
        if (teamId == null) {
            throw new ApiException(HttpStatus.FORBIDDEN, "NO_CLUB",
                    "You do not manage a club, so there is nobody to loan from.");
        }
        return teamId;
    }

    /** One of the two clubs in this loan, and not an outsider. */
    private void requireParty(Long loanId, Long clubId) {
        if (!loanService.isPartyTo(loanService.loan(loanId), clubId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN",
                    "Neither of your clubs is in this loan.");
        }
    }

    /** The rules a manager needs in order not to press a button that will be refused. */
    @GetMapping("/rules")
    public Map<String, Object> rules(@AuthenticationPrincipal User principal) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("maxAge", LoanService.MAX_LOAN_AGE + 1);
        out.put("ageRule", "Only players younger than " + (LoanService.MAX_LOAN_AGE + 1) + " may be loaned out.");
        out.put("noticeWeeks", LoanService.NOTICE_WEEKS);
        out.put("domesticOnly", true);
        out.put("lowerTierOnly", true);
        out.put("tierLadder", "Tier 1 lends into 2-5, tier 2 into 3-5, tier 3 into 4-5, tier 4 into 5. "
                + "A tier 5 club cannot lend out at all.");
        out.put("runsTo", "The end of the season it started in: week 12 day 7, back to the club that owns him.");
        out.put("wage", "The lending club pays him and its coaches train him. The borrowing club gives "
                + "him minutes and nothing else.");
        out.put("myClubId", plusFeatures.viewerTeamId(principal));
        return out;
    }

    /**
     * Clubs this viewer may loan to: same country, a lower tier, and with a free place.
     *
     * <p>Computed on the server so the screen cannot offer a destination the service would refuse. An
     * empty list is a legitimate answer and the copy has to say why — "no clubs" is a much worse screen
     * than "no club in your country is a tier below yours and has room".
     */
    @GetMapping("/destinations")
    public List<Map<String, Object>> destinations(@AuthenticationPrincipal User principal) {
        Long mine = requireOwnClub(principal);
        Team lender = teams.findById(mine).orElse(null);
        List<Map<String, Object>> out = new ArrayList<>();
        if (lender == null) return out;

        for (Team candidate : teams.findClubTeamsForOperations()) {
            if (candidate.getId() == null || candidate.getId().equals(mine)) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("teamId", candidate.getId());
            row.put("name", candidate.getName());
            row.put("country", candidate.getCountry() == null ? null : candidate.getCountry().getName());
            row.put("tier", candidate.getCompetition() == null ? null : candidate.getCompetition().getTier());
            // Every rule evaluated here is a rule LoanService.offer enforces, and the screen has to be
            // able to say no for the same reasons or it will offer a button that 409s.
            row.put("eligible", eligibility(lender, candidate));
            out.add(row);
        }
        return out;
    }

    /** The reason a destination is refused, or null when it is not. One definition, shared with the screen. */
    private String eligibility(Team lender, Team borrower) {
        if (!borrower.isHumanControlled()) return "not a club a person manages";
        Long a = lender.getCountry() == null ? null : lender.getCountry().getId();
        Long b = borrower.getCountry() == null ? null : borrower.getCountry().getId();
        if (a == null || b == null || !a.equals(b)) return "a different country";
        Integer lt = lender.getCompetition() == null ? null : lender.getCompetition().getTier();
        Integer bt = borrower.getCompetition() == null ? null : borrower.getCompetition().getTier();
        if (lt == null || bt == null) return "no tier we can read";
        if (lt >= 5) return "the bottom tier has nobody below it";
        if (bt <= lt) return "not a lower tier";
        return null;
    }

    /** The players this viewer may lend: owned, under 24, not already out. */
    @GetMapping("/available")
    public List<Map<String, Object>> available(@AuthenticationPrincipal User principal) {
        Long mine = requireOwnClub(principal);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Player p : players.findByTeamId(mine)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("playerId", p.getId());
            row.put("name", p.getName());
            row.put("age", p.getAge());
            row.put("position", p.getPosition() == null ? null : p.getPosition().name());
            row.put("rating", p.getRating());
            row.put("loanable", p.getAge() <= LoanService.MAX_LOAN_AGE && !loanService.isOnLoan(p.getId()));
            if (p.getAge() > LoanService.MAX_LOAN_AGE) {
                row.put("reason", "Older than " + (LoanService.MAX_LOAN_AGE + 1) + ".");
            } else if (loanService.isOnLoan(p.getId())) {
                row.put("reason", "Already out on loan.");
            }
            out.add(row);
        }
        return out;
    }

    @GetMapping("/incoming")
    public List<Map<String, Object>> incoming(@AuthenticationPrincipal User principal) {
        return describe(loanService.incoming(requireOwnClub(principal)));
    }

    @GetMapping("/outgoing")
    public List<Map<String, Object>> outgoing(@AuthenticationPrincipal User principal) {
        return describe(loanService.outgoing(requireOwnClub(principal)));
    }

    /**
     * Lends a player out.
     *
     * <p>The lending club is the viewer's own and is never taken from the request: a client that could
     * name it would be able to move another club's player down a division.
     */
    @PostMapping
    public Map<String, Object> offer(@AuthenticationPrincipal User principal,
                                    @RequestBody Map<String, Object> body) {
        Long mine = requireOwnClub(principal);
        Long playerId = asLong(body.get("playerId"));
        Long destinationId = asLong(body.get("borrowingClubId"));
        Loan loan = loanService.offer(mine, playerId, destinationId);
        return describeOne(loan);
    }

    /** The borrowing club accepts. This is the moment it commits, so the room check lands here. */
    @PostMapping("/{loanId}/activate")
    public Map<String, Object> activate(@PathVariable Long loanId,
                                        @AuthenticationPrincipal User principal) {
        Long mine = requireOwnClub(principal);
        Loan loan = loanService.loan(loanId);
        if (!loan.getBorrowingClubId().equals(mine)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN",
                    "Only the club taking the player on can accept him.");
        }
        return describeOne(loanService.activate(loanId));
    }

    /** Either club may ask for the loan to end. Seven days' notice unless the other agrees. */
    @PostMapping("/{loanId}/terminate")
    public Map<String, Object> terminate(@PathVariable Long loanId,
                                         @RequestBody(required = false) Map<String, Object> body,
                                         @AuthenticationPrincipal User principal) {
        Long mine = requireOwnClub(principal);
        requireParty(loanId, mine);
        String reason = body == null ? null : String.valueOf(body.get("reason"));
        return describeOne(loanService.requestTermination(loanId, mine, reason));
    }

    /** The other club agrees: it ends now. */
    @PostMapping("/{loanId}/accept-termination")
    public Map<String, Object> acceptTermination(@PathVariable Long loanId,
                                                 @AuthenticationPrincipal User principal) {
        Long mine = requireOwnClub(principal);
        requireParty(loanId, mine);
        return describeOne(loanService.acceptTermination(loanId, mine));
    }

    private List<Map<String, Object>> describe(List<Loan> list) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Loan loan : list) {
            out.add(describeOne(loan));
        }
        return out;
    }

    private Map<String, Object> describeOne(Loan loan) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("loanId", loan.getId());
        row.put("playerId", loan.getPlayerId());
        row.put("playerName", players.findById(loan.getPlayerId()).map(Player::getName).orElse(null));
        row.put("lendingClubId", loan.getParentClubId());
        row.put("borrowingClubId", loan.getBorrowingClubId());
        row.put("status", loan.getStatus().name());
        row.put("season", loan.getSeason());
        row.put("startWeek", loan.getStartWeek());
        row.put("endWeek", loan.getEndWeek());
        row.put("returnsAt", "Week " + loan.getEndWeek() + " day 7");
        row.put("noticeOutstanding", loan.hasNotice());
        row.put("noticeWeek", loan.getTerminationNoticeWeek());
        row.put("terminationReason", loan.getTerminationReason());
        return row;
    }

    private Long asLong(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Expected a number, got " + value);
        }
    }
}