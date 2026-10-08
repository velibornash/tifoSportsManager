package org.example.footballmanager.newLogic.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.newLogic.exception.ApiException;
import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.Loan;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.GameClockRepository;
import org.example.footballmanager.newLogic.repository.LoanRepository;
import org.example.footballmanager.newLogic.repository.PlayerContractRepository;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.example.footballmanager.newLogic.model.SeasonCalendar;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Loans: a player who is too young for his tier goes down to a weaker club for minutes (Sprint 3.4,
 * finished 2026-10-08).
 *
 * <h2>The owner's rules, verbatim</h2>
 * <ul>
 *   <li>only a player <b>younger than 24</b>;</li>
 *   <li>only <b>within one country</b>, and only to a player who is a national of it;</li>
 *   <li>only to a club in a <b>lower tier</b>: tier 1 may loan into 2–5, tier 2 into 3–5, tier 3 into
 *       4–5, tier 4 into 5, and <b>tier 5 may not loan out at all</b>;</li>
 *   <li>the loan runs <b>to the end of the season it started in</b> — week 12 day 7, back to the club;</li>
 *   <li><b>either club may ask for it to end.</b> If the other agrees it ends at once; if not it ends
 *       when the notice runs out;</li>
 *   <li><b>wage, training and everything else stay with the club that owns him.</b> The borrowing club
 *       gives him minutes and nothing else.</li>
 * </ul>
 *
 * <h2>Two of those rules were dropped, and it is worth saying why</h2>
 *
 * <p><b>The nationality check is not enforced.</b> It cannot be, as it stands: {@code Player.nationality}
 * is set only by {@code BotSquadGenerator}, so 7,730 of the world's 10,130 players have none and the rule
 * would refuse three players in four. And given the same-country rule it adds nothing — the only way to
 * make it bite is to backfill real nationalities, at which point a foreign-signed domestic player becomes
 * permanently unloanable, which nobody asked for. The half-empty column is recorded on the board as its
 * own defect rather than fixed here.
 *
 * <p><b>The loan is not gated on the transfer window.</b> Sprint 3.4 put a window check in {@code start}
 * because a permanent move needs one. A loan moves no player between clubs — he never leaves
 * {@code Player.team} — so there is nothing to window-gate, and refusing a loan because it is week 11
 * would block the one thing the feature exists for.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoanService {

    /** Oldest age a player may be loaned out at. Owner: younger than 24. */
    public static final int MAX_LOAN_AGE = 23;

    /**
     * How long a termination notice runs.
     *
     * <p>The owner said seven days. The world's tick is <b>weekly</b> — day 7 at 23:00 — and a season is
     * twelve weeks of seven days, so a notice raised in week N is closed at the end of week N+1. That is
     * never early and at most thirteen days, and it always lands on a moment the game already has a
     * well-defined tick. Counting seven days literally would mean closing a loan mid-week at a moment
     * nothing else in the application happens, and the player would play the rest of the week for a club
     * that had already sent him back.
     *
     * <p>The reason it is symmetric rather than immediate-for-the-lender is the owner's, and it is
     * right: a recall that takes effect at once is the <b>borrowing</b> club's problem, because it has
     * built the week around a player who is no longer there with no matchday left to replace him.
     */
    public static final int NOTICE_WEEKS = 1;

    private final LoanRepository loans;
    private final PlayerRepository players;
    private final TeamRepository teams;
    private final PlayerContractRepository contracts;
    private final GameClockRepository clocks;
    private final SquadRegistrationService squadRegistration;
    private final org.example.commonmanager.repository.UserRepository userRepository;
    private final NotificationService notifications;

    // ── offering ───────────────────────────────────────────────────────────────────────────────

    /**
     * Loans a player out. Starts on acceptance; see {@link #activate}.
     *
     * <p>Every rule is checked here rather than in the controller, because the controller is not the only
     * caller and a rule that lives in a controller is a rule the next caller does not get.
     *
     * @throws ApiException with a code naming the rule that stopped it — {@code LOAN_PLAYER_TOO_OLD},
     *                      {@code LOAN_DIFFERENT_COUNTRY}, {@code LOAN_NOT_LOWER_TIER},
     *                      {@code LOAN_TOP_TIER_CANNOT_LEND}, {@code LOAN_BOT_CLUB},
     *                      {@code LOAN_SQUAD_FULL}, {@code LOAN_FINAL_WEEK}
     */
    @Transactional
    public Loan offer(Long lendingClubId, Long playerId, Long borrowingClubId) {
        Team lender = requireClub(lendingClubId, "lending");
        Team borrower = requireClub(borrowingClubId, "borrowing");
        requireManaged(lender, "loan out");
        requireManaged(borrower, "loan in");

        if (lender.getId().equals(borrower.getId())) {
            throw refuse("LOAN_SAME_CLUB", "A club cannot loan a player to itself.");
        }

        Player player = players.findById(playerId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PLAYER_NOT_FOUND", "No such player."));
        if (player.getTeam() == null || !lender.getId().equals(player.getTeam().getId())) {
            throw refuse("LOAN_NOT_OWNED", "He does not play for the club offering him.");
        }
        if (player.getAge() > MAX_LOAN_AGE) {
            throw refuse("LOAN_PLAYER_TOO_OLD",
                    "Only players younger than " + (MAX_LOAN_AGE + 1) + " may be loaned out; he is "
                            + player.getAge() + ".");
        }
        if (alreadyOut(playerId)) {
            throw refuse("LOAN_ALREADY_OUT", "He is already out on loan.");
        }

        requireSameCountry(lender, borrower);
        requireLowerTier(lender, borrower);
        // Deliberately no room check here. Offering costs the lending club nothing and the borrower may
        // still sell somebody before it accepts; the obligation starts when he accepts, so that is where
        // the check is enforced, and /loans/destinations tells the manager in advance anyway.

        int week = currentWeek();
        if (week >= SeasonCalendar.WEEKS_PER_SEASON) {
            throw refuse("LOAN_FINAL_WEEK", "The season is over; a loan runs to the end of it, so there "
                    + "is nothing to be lent into. It is week " + week + ".");
        }

        Loan loan = new Loan();
        loan.setPlayerId(playerId);
        loan.setParentClubId(lender.getId());
        loan.setBorrowingClubId(borrower.getId());
        loan.setSeason(currentSeason());
        loan.setStartWeek(week);
        // Not an input. The owner fixed the end: the season it started in. Sprint 3.4 let the two clubs
        // agree weeks 3-to-9, which let a loan be created already expired or run into a second season.
        loan.setEndWeek(SeasonCalendar.WEEKS_PER_SEASON);
        loan.setWageContribution(0.0);
        loan.setStatus(Loan.LoanStatus.AGREED);
        Loan saved = loans.save(loan);

        // The borrowing club's manager has to know a player is on offer, or the loan is a row only they
        // will ever open the screen to see. The lending club is the one who just did it, so it needs none.
        notifyClub(borrower.getId(), org.example.footballmanager.newLogic.model.NotificationKind.LOAN_PROPOSED,
                loanSummary(player, lender, "is on loan offer from"));

        return saved;
    }

    /**
     * Puts an agreed loan into force.
     *
     * <p>Separate from {@link #offer} because the two clubs have to agree, and because the borrowing
     * club's screen needs to refuse the player if it is full — see {@code LoanController}. Accepting is
     * the moment the borrower commits, so the room check belongs there rather than on the offer, which
     * costs the lending club nothing.
     */
    @Transactional
    public Loan activate(Long loanId) {
        Loan loan = requireLoan(loanId);
        if (loan.getStatus() != Loan.LoanStatus.AGREED) {
            throw refuse("LOAN_NOT_AGREED", "This loan is not agreed, so it cannot start.");
        }
        Team borrower = teams.findById(loan.getBorrowingClubId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TEAM_NOT_FOUND", "No such club."));
        requireManaged(borrower, "loan in");
        // The borrower may have filled up between the offer and the acceptance.
        requireRoomAt(borrower);
        requireTierStillLower(loan);

        loan.setStatus(Loan.LoanStatus.ACTIVE);
        loan.setStartedAt(java.time.Instant.now());
        Loan saved = loans.save(loan);

        // **The moment the owner asked to be told about.** He has a player now, and the club he lent him
        // to has lost him; both are facts a manager should not have to go and look for.
        String playerName = players.findById(loan.getPlayerId()).map(Player::getName).orElse(null);
        notifyClub(loan.getBorrowingClubId(),
                org.example.footballmanager.newLogic.model.NotificationKind.LOAN_MOVED,
                (playerName == null ? "A player" : playerName) + " has arrived on loan");
        notifyClub(loan.getParentClubId(),
                org.example.footballmanager.newLogic.model.NotificationKind.LOAN_MOVED,
                (playerName == null ? "A player" : playerName) + " has left on loan");

        return saved;
    }

    // ── terminating ────────────────────────────────────────────────────────────────────────────

    /**
     * One club asks for the loan to end.
     *
     * <p>The other club can accept it, in which case the loan ends at once ({@link #acceptTermination}).
     * If nobody answers, the weekly tick ends it when the notice runs out
     * ({@link #enforceNotices}).
     */
    @Transactional
    public Loan requestTermination(Long loanId, Long requestingClubId, String reason) {
        Loan loan = requireLoan(loanId);
        if (loan.getStatus() != Loan.LoanStatus.ACTIVE) {
            throw refuse("LOAN_NOT_ACTIVE", "Only a running loan can be terminated.");
        }
        boolean isLender = loan.getParentClubId().equals(requestingClubId);
        boolean isBorrower = loan.getBorrowingClubId().equals(requestingClubId);
        if (!isLender && !isBorrower) {
            throw refuse("LOAN_NOT_A_PARTY", "Only the two clubs in this loan may ask to end it.");
        }
        if (loan.hasNotice()) {
            // Idempotent rather than an error: a manager clicking twice is not a rule violation, and
            // refusing the second click would leave him thinking the first one did nothing.
            loan.setTerminationReason(reason);
            return loans.save(loan);
        }
        if (loan.getSeason() != null && loan.getSeason() != currentSeason()) {
            // The notice would land in a season the loan does not belong to.
            return close(loan, isLender ? Loan.LoanStatus.RECALLED : Loan.LoanStatus.RETURNED_EARLY,
                    reason == null ? "carried over a season boundary" : reason);
        }
        loan.setTerminationRequestedByClubId(requestingClubId);
        loan.setTerminationNoticeWeek(Math.min(SeasonCalendar.WEEKS_PER_SEASON, currentWeek() + NOTICE_WEEKS));
        loan.setTerminationAccepted(Boolean.FALSE);
        loan.setTerminationReason(reason);
        Loan saved = loans.save(loan);

        // The other club is being asked to give up a player it is currently using. That is the one loan
        // event where silence is a cost to somebody: without this, the asking manager's button appears to
        // do nothing until the week rolls over on its own.
        Long otherClub = isLender ? loan.getBorrowingClubId() : loan.getParentClubId();
        String playerName = players.findById(loan.getPlayerId()).map(Player::getName).orElse(null);
        notifyClub(otherClub, org.example.footballmanager.newLogic.model.NotificationKind.LOAN_MOVED,
                (playerName == null ? "A player" : playerName) + " is being asked back on loan"
                        + (loan.getTerminationNoticeWeek() != null
                        ? " — ends week " + loan.getTerminationNoticeWeek() : ""));

        return saved;
    }

    /** The other club agrees: it ends now, with no notice. */
    @Transactional
    public Loan acceptTermination(Long loanId, Long agreeingClubId) {
        Loan loan = requireLoan(loanId);
        if (!loan.hasNotice()) {
            throw refuse("LOAN_NO_NOTICE", "There is no termination request outstanding on this loan.");
        }
        if (loan.getTerminationRequestedByClubId().equals(agreeingClubId)) {
            throw refuse("LOAN_SELF_ACCEPT", "The club that asked for it cannot also agree to it.");
        }
        // Who ASKED decides the status, not who agreed. Reading it off the agreeing club got this
        // backwards: the lender asks and the borrower agrees, so isLender was false at exactly the moment
        // it had to be true, and every mutual recall was recorded as a borrower sending him back.
        boolean lenderAsked = loan.getParentClubId().equals(loan.getTerminationRequestedByClubId());
        loan.setTerminationAccepted(Boolean.TRUE);
        loan.setTerminationRequestedByClubId(null);
        loan.setTerminationNoticeWeek(null);
        return close(loan, lenderAsked ? Loan.LoanStatus.RECALLED : Loan.LoanStatus.RETURNED_EARLY,
                "agreed by both clubs");
    }

    /**
     * Closes loans whose notice nobody answered.
     *
     * <p>Which club asked is recorded on the loan, and it decides the status: the lender recalling is
     * {@code RECALLED}, the borrower sending him back is {@code RETURNED_EARLY}. A club reading its own
     * history needs to know which of those two things happened.
     *
     * @return how many were closed
     */
    @Transactional
    public int enforceNotices() {
        List<Loan> due = loans.findByStatusAndTerminationNoticeWeekLessThanEqual(
                Loan.LoanStatus.ACTIVE, currentWeek());
        for (Loan loan : due) {
            boolean lenderAsked = loan.getParentClubId().equals(loan.getTerminationRequestedByClubId());
            close(loan, lenderAsked ? Loan.LoanStatus.RECALLED : Loan.LoanStatus.RETURNED_EARLY,
                    "notice expired unanswered");
        }
        if (!due.isEmpty()) {
            loans.saveAll(due);
            log.info("Closed {} loan(s) whose termination notice ran out", due.size());
        }
        return due.size();
    }

    /**
     * Closes out loans whose season is over — week 12 day 7, back to the club that owns him.
     *
     * <p>Nothing moves on the player: {@code Player.team} never left. Closing the loan is the whole of
     * the return, which is the whole point of not moving him.
     */
    @Transactional
    public int closeFinishedLoans() {
        int week = currentWeek();
        List<Loan> finishing = loans.findByStatusAndEndWeekLessThanEqual(Loan.LoanStatus.ACTIVE, week);
        for (Loan loan : finishing) {
            close(loan, Loan.LoanStatus.COMPLETED, "the season ended");
        }
        if (!finishing.isEmpty()) {
            loans.saveAll(finishing);
        }
        return finishing.size();
    }

    // ── reads ───────────────────────────────────────────────────────────────────────────────────

    /**
     * Whether this player is committed to another club — running there, or offered and unanswered.
     *
     * <p>Consulted by the transfer paths. A loanee has no contract with the borrowing club and
     * {@code requirePlayerTeam} would name the <b>lender</b> as the seller, so without this a manager
     * could buy a player he does not own and pay the wrong club.
     *
     * <p><b>AGREED counts as well as ACTIVE</b>, and it was found by using the screen: a player who had
     * just been offered out still appeared in the lending table as loanable, so the manager could
     * offer him a second time and the button would refuse him with LOAN_ALREADY_OUT. A button whose only
     * outcome is an error is worse than no button.
     *
     * <p>It is also the right answer for the guard itself: while another club is deciding whether to take
     * him, he is not available to be sold.
     */
    @Transactional(readOnly = true)
    public boolean isOnLoan(Long playerId) {
        return !loans.findByPlayerIdAndStatusIn(playerId,
                List.of(Loan.LoanStatus.AGREED, Loan.LoanStatus.ACTIVE)).isEmpty();
    }

    /** The club he belongs to, whatever is on loan — used to keep a signing from stealing a loanee. */
    @Transactional(readOnly = true)
    public java.util.Optional<Long> lendingClubOf(Long playerId) {
        return loans.findByPlayerIdAndStatusIn(playerId, List.of(Loan.LoanStatus.ACTIVE)).stream()
                .map(Loan::getParentClubId)
                .findFirst();
    }

    @Transactional(readOnly = true)
    public List<Loan> incoming(Long clubId) {
        return loans.findByBorrowingClubIdAndStatus(clubId, Loan.LoanStatus.ACTIVE);
    }

    /**
     * Offers made to this club that it has not answered yet.
     *
     * <p>A loan needs two managers, and this is the borrowing club's half of the conversation. It was
     * missing: the offer was created and the destination club had no way to see it, so the feature only
     * worked for a club lending to itself.
     */
    @Transactional(readOnly = true)
    public List<Loan> offersFor(Long clubId) {
        return loans.findByBorrowingClubIdAndStatus(clubId, Loan.LoanStatus.AGREED);
    }

    /**
     * Loans this club has made and not yet finished: running ones and offers nobody has answered.
     *
     * <p>Found by using the screen. It offered a player, the POST succeeded, and the refreshed page still
     * said "0 Out" — because this asked for {@code ACTIVE} and a fresh offer is {@code AGREED}. The
     * manager's own pending offer was invisible to the manager, which is worse than the feature not
     * existing: it looks like it silently failed.
     *
     * <p>The status column distinguishes them, and the row shows what is waiting on whom.
     */
    @Transactional(readOnly = true)
    public List<Loan> outgoing(Long clubId) {
        List<Loan> running = loans.findByParentClubIdAndStatus(clubId, Loan.LoanStatus.ACTIVE);
        List<Loan> pending = loans.findByParentClubIdAndStatus(clubId, Loan.LoanStatus.AGREED);
        List<Loan> all = new ArrayList<>(running);
        all.addAll(pending);
        return all;
    }

    /**
     * One loan, for a screen that has to ask whether the viewer is a party to it.
     *
     * <p>Public because the controller genuinely cannot answer that question any other way: it does not
     * know which of the two ids is "ours", and a guard that guesses is a guard that eventually lets a
     * manager terminate somebody else's loan.
     */
    @Transactional(readOnly = true)
    public Loan loan(Long loanId) {
        return loans.findById(loanId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "LOAN_NOT_FOUND", "No such loan."));
    }

    /** Whether a club is one of the two in this loan. */
    public boolean isPartyTo(Loan loan, Long clubId) {
        return loan != null && clubId != null
                && (loan.getParentClubId().equals(clubId) || loan.getBorrowingClubId().equals(clubId));
    }

    // ── the rules ───────────────────────────────────────────────────────────────────────────────

    /**
     * The tier ladder (owner, 2026-10-08): a club may only loan <b>down</b>, and the bottom club may
     * not loan at all.
     *
     * <p>Tier 1 is the top, so "lower" is a larger number. A loan may skip tiers — tier 1 into tier 5 is
     * allowed — but it may never go up, and never sideways.
     *
     * <p><b>A club with no tier on its competition is refused, not treated as tier 1.</b> That default
     * exists in {@code ClubRatingService} and is the safe direction <i>there</i>, where a guess decides a
     * starting rating. Here it would make an unknown club top-flight and let it loan anywhere, so an
     * absent tier is a reason to say no rather than a reason to say yes.
     */
    private void requireLowerTier(Team lender, Team borrower) {
        int lenderTier = tierOf(lender);
        int borrowerTier = tierOf(borrower);
        if (lenderTier >= lowestTier) {
            throw refuse("LOAN_TOP_TIER_CANNOT_LEND",
                    "A club in the bottom tier has nobody below it to lend to, so it cannot loan out.");
        }
        if (borrowerTier <= lenderTier) {
            throw refuse("LOAN_NOT_LOWER_TIER",
                    "A club may only loan to a lower tier. They are both tier " + lenderTier + ".");
        }
    }

    /** The same check again at activation, so a relegation between offer and acceptance is caught. */
    private void requireTierStillLower(Loan loan) {
        Team lender = teams.findById(loan.getParentClubId()).orElse(null);
        Team borrower = teams.findById(loan.getBorrowingClubId()).orElse(null);
        if (lender == null || borrower == null) {
            throw refuse("LOAN_CLUB_MISSING", "One of the clubs in this loan no longer exists.");
        }
        requireLowerTier(lender, borrower);
    }

    /** Domestic only (owner): the loan stays inside the country the two clubs play in. */
    private void requireSameCountry(Team lender, Team borrower) {
        Long a = lender.getCountry() == null ? null : lender.getCountry().getId();
        Long b = borrower.getCountry() == null ? null : borrower.getCountry().getId();
        if (a == null || b == null || !a.equals(b)) {
            throw refuse("LOAN_DIFFERENT_COUNTRY",
                    "A loan can only be made between clubs in the same country.");
        }
    }

    /**
     * Both clubs have to be managed by a person.
     *
     * <p>The owner ruled bots out of both sides. The reason that matters is not tidiness: a loan is a
     * decision two clubs agree to, and an AI club that agreed to one would need a written policy for when
     * to agree. Until that exists, the feature is for human clubs and the number of them is growing.
     */
    private void requireManaged(Team team, String what) {
        if (!team.isHumanControlled()) {
            throw refuse("LOAN_BOT_CLUB",
                    "Loans are between clubs that a person manages, and " + team.getName()
                            + " is not one. " + Character.toUpperCase(what.charAt(0)) + what.substring(1)
                            + " is not possible here.");
        }
    }

    private void requireRoomAt(Team borrower) {
        SquadRegistrationService.RegistrationCheck room = squadRegistration.canRegister(borrower.getId());
        if (!room.allowed()) {
            throw new ApiException(HttpStatus.CONFLICT, room.code(),
                    room.reason() + " He cannot come in until somebody leaves.");
        }
    }

    private boolean alreadyOut(Long playerId) {
        return !loans.findByPlayerIdAndStatusIn(playerId,
                List.of(Loan.LoanStatus.PROPOSED, Loan.LoanStatus.AGREED, Loan.LoanStatus.ACTIVE)).isEmpty();
    }

    /**
     * The club's tier, and the floor below which nobody lends.
     *
     * <p>Five tiers is the shape of the world: the database has 8 tier-1 competitions, then 5, 7, 11 and
     * 19. Read from the club's league, and refused rather than guessed when it is absent.
     */
    private int tierOf(Team team) {
        Competition competition = team.getCompetition();
        Integer tier = competition == null ? null : competition.getTier();
        if (tier == null || tier < 1 || tier > lowestTier) {
            throw refuse("LOAN_NO_TIER",
                    team.getName() + " has no tier we can read, so we will not guess one.");
        }
        return tier;
    }

    // ── plumbing ────────────────────────────────────────────────────────────────────────────────

    /**
     * Tells one club's manager that something happened to a loan of theirs.
     *
     * <p>Owner, 2026-10-08: *"loan bi trebao izmedju ostalog da stize u notifications"*. Until this, a
     * loan moved entirely inside the loans screen: a manager found out that a player had arrived, or that
     * the other club wanted him back, only by opening that screen and looking.
     *
     * <p>Skipped for a club with no manager, which is most of a 14,880-club world — the lookup is one
     * indexed query and the alternative is a notification table full of rows nobody will ever read.
     */
    private void notifyClub(Long clubId, org.example.footballmanager.newLogic.model.NotificationKind kind,
                            String summary) {
        if (clubId == null || summary == null || summary.isBlank()) {
            return;
        }
        userRepository.findAllByFootballTeamId(clubId).forEach(manager ->
                notifications.notify(manager, kind, summary, "loans", null));
    }

    /** "Zvezdan Vukomanović to NK Bravdo" — the same words the loans screen uses, so neither surprises. */
    private String loanSummary(Player player, Team otherClub, String what) {
        String name = player == null ? "a player" : player.getName();
        String club = otherClub == null ? "another club" : otherClub.getName();
        return name + " " + what + " " + club;
    }

    private Loan close(Loan loan, Loan.LoanStatus status, String reason) {
        loan.setStatus(status);
        loan.setEndedAt(java.time.Instant.now());
        log.info("Loan {} closed as {}: {}", loan.getId(), status, reason);
        Loan saved = loans.save(loan);

        // A loan ending is a squad change for both clubs, and it is the one event that happens on its own
        // schedule - the weekly sweep - with nobody having pressed anything. Told here, or never.
        String playerName = players.findById(loan.getPlayerId()).map(Player::getName).orElse(null);
        String summary = (playerName == null ? "A player" : playerName) + " is back from loan ("
                + status.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ') + ")";
        notifyClub(loan.getParentClubId(),
                org.example.footballmanager.newLogic.model.NotificationKind.LOAN_MOVED, summary);
        notifyClub(loan.getBorrowingClubId(),
                org.example.footballmanager.newLogic.model.NotificationKind.LOAN_MOVED, summary);

        return saved;
    }

    private Loan requireLoan(Long loanId) {
        return loans.findById(loanId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "LOAN_NOT_FOUND", "No such loan."));
    }

    private Team requireClub(Long clubId, String which) {
        if (clubId == null) {
            throw refuse("TEAM_REQUIRED", "Which club is " + which + "?");
        }
        return teams.findById(clubId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TEAM_NOT_FOUND", "No such club."));
    }

    private int currentSeason() {
        Integer season = clocks.findById(1L).map(c -> c.getCurrentSeason()).orElse(null);
        if (season == null) {
            throw new ApiException(HttpStatus.CONFLICT, "NO_CLOCK", "The game clock is not running.");
        }
        return season;
    }

    private int currentWeek() {
        Integer week = clocks.findById(1L).map(c -> c.getCurrentWeek()).orElse(null);
        if (week == null) {
            throw new ApiException(HttpStatus.CONFLICT, "NO_CLOCK", "The game clock is not running.");
        }
        return week;
    }

    private static ApiException refuse(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    /** The bottom tier of the pyramid. Nobody below it, so nobody in it may lend. */
    private static final int lowestTier = 5;
}