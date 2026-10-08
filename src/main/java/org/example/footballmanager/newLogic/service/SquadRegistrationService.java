package org.example.footballmanager.newLogic.service;

import lombok.RequiredArgsConstructor;
import org.example.footballmanager.newLogic.exception.ApiException;
import org.example.footballmanager.newLogic.model.Loan;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * How many players a club may have. One number, counting players.
 *
 * <p><b>Owner, 2026-10-08: a club has 30 players.</b> Seniors, youth players and — when loans exist —
 * loanees all count against the same 30. National teams are a separate rule
 * ({@code NationalTeamService.SQUAD_SIZE}, also 25, also counted in players) and are deliberately not
 * routed through here: an NT squad is a call-up, not a registration.
 *
 * <h2>Why this is its own class</h2>
 *
 * <p>The rule used to live in {@code PlayerContractService} as {@code MAX_SENIOR_SQUAD = 25} plus
 * {@code MAX_YOUTH_SQUAD = 8}, and it was enforced in exactly one place — the signing path — by
 * counting <b>{@code PlayerContract} rows</b>. That was wrong three times over:
 *
 * <ol>
 *   <li><b>It counted the wrong thing.</b> A {@code Player} with no contract is invisible to it, and
 *       several real paths create exactly that: a player made from an academy junior, and every player
 *       in the world between creation and the next season's contract backfill. A club could hold 40
 *       players and be told it had room.</li>
 *   <li><b>It was in the wrong place.</b> Buying from the transfer list never consulted it at all, so
 *       the market — the main way a manager adds players — was entirely uncapped. Only signing a
 *       free agent was checked.</li>
 *   <li><b>It had two buckets.</b> 25 senior plus 8 academy is 33 players, split by an attribute that
 *       is inferred from age and value, so the same club was over its limit after a backfill
 *       reclassified a nineteen-year-old as a prospect.</li>
 * </ol>
 *
 * <p>Counting {@code Player} rows makes all three disappear. It is also the same shape as the national
 * team rule, so the two limits are now written the same way and can be read side by side.
 *
 * <h2>Where it is enforced</h2>
 *
 * <p>Every path that moves a player <b>into</b> a club:
 * <ul>
 *   <li>{@code PlayerContractService.sign} — signing a free agent or negotiating one over;</li>
 *   <li>{@code TransferService.completeTransfer} — buying from the transfer list;</li>
 *   <li>{@code YouthAcademyService} — a manager's own Promote / Transfer List buttons.</li>
 * </ul>
 *
 * <p><b>And deliberately not on the forced paths.</b> When a junior's academy season ends, and when a
 * manager closes the school, every prospect is created and put on the market regardless of room
 * (owner, 2026-10-08: <i>"svi idu na TL i klub zaradjuje od prodaje"</i>). The club may sit above 30
 * until they are sold, which is the cost and the revenue of an academy in the same decision. Being
 * over the cap blocks signing and promotion; it does not block selling.
 *
 * <p><b>Not enforced on {@code PlayerController.createPlayer}.</b> That endpoint is a fixture tool for
 * building a world, and the seeders use the same shape. Capping it would make the 14,880-club
 * construction order-dependent.
 */
@Service
@RequiredArgsConstructor
public class SquadRegistrationService {

    /** Max players a club may have. Owner, 2026-10-08. */
    public static final int MAX_CLUB_SQUAD = 30;

    private final PlayerRepository players;
    private final org.example.footballmanager.newLogic.repository.LoanRepository loans;

    /**
     * The players this club can put on a pitch right now.
     *
     * <p><b>Owned, minus the ones it has loaned away, plus the ones it has borrowed.</b> All three terms
     * are load-bearing and the middle one is the one that was missing when this was first written:
     *
     * <ul>
     *   <li><b>Owned minus loaned out</b> — a loanee keeps {@code Player.team} here, so a plain
     *       {@code findByTeamId} still returns him, and a squad list built from it offered him to
     *       <em>both</em> clubs. He could be picked in the lender's eleven and fielded by the borrower on
     *       the same matchday, which is not a subtle bug: it is two clubs fielding one person.</li>
     *   <li><b>Plus borrowed in</b> — otherwise he is not in the borrowing club's squad at all and the
     *       loan achieves nothing.</li>
     * </ul>
     *
     * <p><b>This is deliberately not the same list {@link #squadSize} counts</b>, and the difference is
     * not an oversight. A player loaned <i>away</i> cannot be fielded here, but he is still registered
     * with this club and still one of its thirty: a manager who loans out fifteen players has not freed
     * fifteen places. So:
     *
     * <table border="1">
     *   <caption>the two lists</caption>
     *   <tr><th></th><th>fieldable</th><th>counts against the 30</th></tr>
     *   <tr><td>owned, not loaned out</td><td>yes</td><td>yes</td></tr>
     *   <tr><td>owned, loaned out</td><td><b>no</b></td><td><b>yes</b></td></tr>
     *   <tr><td>borrowed in</td><td>yes</td><td>yes</td></tr>
     * </table>
     *
     * <p>The wage bill ({@code WeeklyFinanceService}) and the trainer ({@code SquadTrainingService}) read
     * {@code findByTeamId} directly and correctly need neither list: they want everything owned, loaned
     * out included, because the lending club pays him and its coaches work on him (owner's rule).
     */
    @Transactional(readOnly = true)
    public List<Player> availablePlayers(Long teamId) {
        List<Player> owned = players.findByTeamId(teamId);
        if (teamId == null || owned == null || owned.isEmpty()) {
            return borrowed(teamId, owned == null ? List.of() : owned);
        }
        Set<Long> outOnLoan = new HashSet<>(safe(loans.findActiveLoanedOutPlayerIds(teamId, Loan.LoanStatus.ACTIVE)));
        Set<Long> ownedIds = new HashSet<>();
        List<Player> fieldable = new ArrayList<>();
        for (Player p : owned) {
            if (p == null || p.getId() == null) continue;
            ownedIds.add(p.getId());
            if (!outOnLoan.contains(p.getId())) {
                fieldable.add(p);
            }
        }
        return borrowed(teamId, fieldable, ownedIds);
    }

    private List<Player> borrowed(Long teamId, List<Player> into) {
        return borrowed(teamId, into, new HashSet<>());
    }

    /** Adds the players this club has on loan in, skipping any id already present. */
    private List<Player> borrowed(Long teamId, List<Player> into, Set<Long> alreadyPresent) {
        Set<Long> present = new HashSet<>(alreadyPresent);
        List<Player> squad = new ArrayList<>(into);
        if (teamId == null) return squad;
        List<Long> borrowedIds = safe(loans.findActiveLoanedInPlayerIds(teamId, Loan.LoanStatus.ACTIVE));
        if (borrowedIds.isEmpty()) return squad;
        for (Player p : players.findAllById(borrowedIds)) {
            // A missing row must not become a null in a squad a lineup editor is about to render.
            if (p != null && p.getId() != null && present.add(p.getId())) {
                squad.add(p);
            }
        }
        return squad;
    }

    private static List<Long> safe(List<Long> ids) {
        return ids == null ? List.of() : ids;
    }

    /**
     * Whether this club may add another player, and how many places it has left.
     *
     * <p>Counts <b>owned plus borrowed</b>, so a loanee occupies one of the thirty — the owner's rule,
     * and the opposite of what this class's predecessor assumed. An academy graduate has no
     * {@code PlayerContract} until the next season's backfill, so a contract count could not see him
     * and a club could hold far more than its limit.
     *
     * <p>A player on the transfer list still occupies a place: he has not left, and until he has, he is
     * one of the manager's thirty.
     */
    @Transactional(readOnly = true)
    public RegistrationCheck canRegister(Long teamId) {
        if (teamId == null) {
            return new RegistrationCheck(false, "TEAM_REQUIRED", "Which club? A club id is required to add a player.");
        }
        int used = squadSize(teamId);
        if (used >= MAX_CLUB_SQUAD) {
            return new RegistrationCheck(false, "SQUAD_FULL",
                    "A club may have " + MAX_CLUB_SQUAD + " players and it already has " + used
                            + ". Sell or release someone before signing another.");
        }
        return new RegistrationCheck(true, "OK",
                (MAX_CLUB_SQUAD - used) + " places left in the squad.");
    }

    /**
     * The same question asked as a refusal, for the paths where adding is the only thing being done.
     *
     * @param what the action being attempted, named in the message so the manager is told which button
     *             refused him rather than being handed a bare code
     */
    public void requireRoom(Long teamId, String what) {
        RegistrationCheck check = canRegister(teamId);
        if (!check.allowed()) {
            throw new ApiException(HttpStatus.CONFLICT, check.code(),
                    check.reason() + " (" + what + " refused.)");
        }
    }

    /**
     * How many players count against this club's limit of thirty.
     *
     * <p><b>Owned, plus borrowed in — and owned includes the ones loaned out.</b> A player on loan away
     * cannot be fielded here, but he is still registered with this club and still one of its thirty: a
     * manager who loans out fifteen players has not freed fifteen places, and reading it the other way
     * would make loans a way to grow a squad past the limit one loan at a time.
     *
     * <p>So this is deliberately <b>not</b> {@code availablePlayers(teamId).size()}. They are two
     * different questions and both callers exist.
     */
    @Transactional(readOnly = true)
    public int squadSize(Long teamId) {
        if (teamId == null) return 0;
        List<Player> owned = players.findByTeamId(teamId);
        List<Long> borrowedIds = safe(loans.findActiveLoanedInPlayerIds(teamId, Loan.LoanStatus.ACTIVE));
        return (owned == null ? 0 : owned.size()) + borrowedIds.size();
    }

    public record RegistrationCheck(boolean allowed, String code, String reason) { }
}