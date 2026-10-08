package org.example.footballmanager.newLogic.service;

import lombok.RequiredArgsConstructor;
import org.example.footballmanager.newLogic.exception.ApiException;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    /**
     * Whether this club may add another player, and how many places it has left.
     *
     * <p>Counts {@code Player} rows, so a player without a contract counts — which is the entire point.
     * A squad member who is on the transfer list still occupies a place: he has not left, and until he
     * has, he is one of the manager's thirty.
     */
    @Transactional(readOnly = true)
    public RegistrationCheck canRegister(Long teamId) {
        if (teamId == null) {
            return new RegistrationCheck(false, "TEAM_REQUIRED", "Which club? A club id is required to add a player.");
        }
        int used = players.findByTeamId(teamId).size();
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

    /** How many players a club has right now. One query, and the same count {@link #canRegister} uses. */
    @Transactional(readOnly = true)
    public int squadSize(Long teamId) {
        return teamId == null ? 0 : players.findByTeamId(teamId).size();
    }

    public record RegistrationCheck(boolean allowed, String code, String reason) { }
}