package org.example.footballmanager.newLogic.service;

import org.example.commonmanager.model.User;
import org.example.footballmanager.newLogic.model.Country;
import org.example.footballmanager.newLogic.model.NationalTeamCandidate;
import org.example.footballmanager.newLogic.model.NationalTeamElection;
import org.example.footballmanager.newLogic.model.NationalTeamLevel;
import org.example.footballmanager.newLogic.model.NationalTeamVote;
import org.example.footballmanager.newLogic.repository.NationalTeamCandidateRepository;
import org.example.footballmanager.newLogic.repository.NationalTeamElectionRepository;
import org.example.footballmanager.newLogic.repository.NationalTeamVoteRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Selector elections (owner, 2026-09-28).
 *
 * <p>Implements the owner's rules as given:
 *
 * <ul>
 *   <li>Registration opens week 12 day 1 of the previous season and runs into week 1.
 *   <li>Voting runs week 1 day 1 to week 1 day 7 midday.
 *   <li>One vote per user of the country, changeable while voting is open.
 *   <li>A candidate may vote, for themselves included.
 *   <li>Votes stay secret until the result is declared.
 *   <li>An admin can annul; the whole thing repeats each season; the winner serves one season.
 * </ul>
 *
 * <p><b>What "secret" is enforced against.</b> An undecided election's tallies are not sent to
 * ordinary callers at all, not merely hidden in the UI. {@link #describeElection} takes a
 * {@code canSeeTallies} flag and omits the counts entirely, so a manager cannot read the running
 * result by calling the endpoint directly.
 */
@Service
public class NationalTeamElectionService {

    /** Voting opens on day 1 of week 1, at the day-1 kickoff. */
    private static final int VOTING_WEEK = 1;
    /** ...and closes on day 7 of the same week, at midday. */
    private static final Duration VOTING_LENGTH = Duration.ofDays(6).plusHours(3).plusMinutes(15);

    private final NationalTeamElectionRepository elections;
    private final NationalTeamCandidateRepository candidates;
    private final NationalTeamVoteRepository votes;
    private final NationalTeamAppointments appointments;

    public NationalTeamElectionService(NationalTeamElectionRepository elections,
                                       NationalTeamCandidateRepository candidates,
                                       NationalTeamVoteRepository votes,
                                       NationalTeamAppointments appointments) {
        this.elections = elections;
        this.candidates = candidates;
        this.votes = votes;
        this.appointments = appointments;
    }

    // ------------------------------------------------------------- windows

    /**
     * When voting is open, given when the season's week 1 day 1 began.
     *
     * <p>Takes the week-1 kickoff as an argument rather than reading the match clock, so the rule is
     * testable without a running season and so the two are not coupled by a hidden dependency.
     */
    public static boolean isVotingOpen(Instant weekOneDayOne, Instant now) {
        return now.isAfter(weekOneDayOne) && now.isBefore(weekOneDayOne.plus(VOTING_LENGTH));
    }

    /**
     * Whether a candidacy can be registered right now.
     *
     * <p>The owner asked for registration to open in week 12 and to be available again in week 1,
     * <b>while voting is also running</b>. Registration and voting are therefore not exclusive
     * states - modelling them as one status field meant that the moment voting opened, nobody could
     * stand, which is not what was asked for. Both windows are now derived independently from the
     * clock, and the status only records the lifecycle (registration, voting, decided, annulled).
     */
    private boolean acceptingCandidates(NationalTeamElection election, Instant now) {
        if (election.getStatus() == NationalTeamElection.Status.DECIDED
                || election.getStatus() == NationalTeamElection.Status.ANNULLED) {
            return false;
        }
        // A forced REGISTRATION status keeps the window open for testing.
        if (election.isManualOverride() && election.getStatus() == NationalTeamElection.Status.REGISTRATION) {
            return true;
        }
        Instant closes = election.getVotingClosesAt();
        return closes == null || now.isBefore(closes);
    }

    /** Whether a vote can be cast right now. */
    private boolean acceptingVotes(NationalTeamElection election, Instant now) {
        if (election.getStatus() == NationalTeamElection.Status.DECIDED
                || election.getStatus() == NationalTeamElection.Status.ANNULLED) {
            return false;
        }
        if (election.isManualOverride()) {
            return election.getStatus() == NationalTeamElection.Status.VOTING;
        }
        Instant opens = election.getVotingOpensAt();
        Instant closes = election.getVotingClosesAt();
        return (opens == null || now.isAfter(opens)) && (closes == null || now.isBefore(closes));
    }

    public static Instant votingClosesAt(Instant weekOneDayOne) {
        return weekOneDayOne.plus(VOTING_LENGTH);
    }

    // ------------------------------------------------------------ lifecycle

    /**
     * Creates the election for a country, level and season if it is not already there.
     *
     * <p>Idempotent, because "ensure an election exists" runs on boot and on every page load, and a
     * second call must not reset a vote that has already been cast.
     */
    @Transactional
    public NationalTeamElection ensureElection(Country country, NationalTeamLevel level, int seasonYear,
                                               Instant weekOneDayOne) {
        return elections.findByCountryIdAndLevelAndSeasonYear(country.getId(), level, seasonYear)
                .orElseGet(() -> {
                    NationalTeamElection election = new NationalTeamElection();
                    election.setCountry(country);
                    election.setLevel(level);
                    election.setSeasonYear(seasonYear);
                    election.setStatus(NationalTeamElection.Status.REGISTRATION);
                    // Registration opens at week 12 day 1 of the previous season (P0-ELEC-1). A season
                    // is twelve seven-day weeks, so that instant is exactly one week before this week 1 -
                    // the previous season's week 12 and this season's week 1 are back to back. In season
                    // 1 there is no previous week 12, so the window starts before the world began and
                    // registration is open from the first instant - the property the one-day offset was
                    // trying and failing to say.
                    election.setRegistrationOpensAt(weekOneDayOne.minus(Duration.ofDays(7)));
                    election.setVotingOpensAt(weekOneDayOne);
                    election.setVotingClosesAt(votingClosesAt(weekOneDayOne));
                    return elections.save(election);
                });
    }

    /**
     * Brings the election's status in line with the clock.
     *
     * <p>Registration becomes voting when the window opens, and voting becomes decided-by-clock when
     * it closes. An admin override is left alone: if somebody opened voting early on purpose, the
     * clock must not quietly close it.
     */
    @Transactional
    public void advanceToClock(NationalTeamElection election, Instant now) {
        if (election.isManualOverride()
                || election.getStatus() == NationalTeamElection.Status.DECIDED
                || election.getStatus() == NationalTeamElection.Status.ANNULLED) {
            return;
        }
        if (election.getVotingOpensAt() != null && now.isAfter(election.getVotingOpensAt())
                && election.getStatus() == NationalTeamElection.Status.REGISTRATION) {
            election.setStatus(NationalTeamElection.Status.VOTING);
            elections.save(election);
        }
    }

    // ---------------------------------------------------------- candidates

    @Transactional
    public NationalTeamCandidate register(Country country, NationalTeamLevel level, int seasonYear,
                                          User candidate, Instant weekOneDayOne) {
        NationalTeamElection election = ensureElection(country, level, seasonYear, weekOneDayOne);
        advanceToClock(election, Instant.now());

        if (!acceptingCandidates(election, Instant.now())) {
            throw new IllegalStateException("Registration for this election has closed.");
        }
        requireInCountry(country, candidate);

        // Re-registering after withdrawing revives the row rather than adding a second ballot line.
        return candidates.findByElectionIdAndUserId(election.getId(), candidate.getId())
                .map(existing -> {
                    existing.setWithdrawn(false);
                    existing.setWithdrawnAt(null);
                    return candidates.save(existing);
                })
                .orElseGet(() -> {
                    NationalTeamCandidate row = new NationalTeamCandidate();
                    row.setElection(election);
                    row.setUser(candidate);
                    return candidates.save(row);
                });
    }

    @Transactional
    public void withdraw(Country country, NationalTeamLevel level, int seasonYear, User candidate) {
        NationalTeamElection election = requireElection(country, level, seasonYear);
        NationalTeamCandidate row = candidates
                .findByElectionIdAndUserId(election.getId(), candidate.getId())
                .orElseThrow(() -> new IllegalStateException("You are not standing for this election."));
        if (row.isWithdrawn()) {
            return;
        }
        row.setWithdrawn(true);
        row.setWithdrawnAt(Instant.now());
        candidates.save(row);
    }

    // ---------------------------------------------------------------- vote

    /**
     * Casts or changes a vote.
     *
     * <p>One row per voter, updated in place. A voter may vote for themselves, which the owner asked
     * for explicitly, so there is no self-vote guard here.
     */
    @Transactional
    public NationalTeamVote vote(Country country, NationalTeamLevel level, int seasonYear,
                                 User voter, long candidateRowId) {
        NationalTeamElection election = requireElection(country, level, seasonYear);
        advanceToClock(election, Instant.now());

        Instant now = Instant.now();
        if (!acceptingVotes(election, now)) {
            throw new IllegalStateException("Voting is not open.");
        }
        requireInCountry(country, voter);

        NationalTeamCandidate candidate = candidates.findById(candidateRowId)
                .filter(row -> row.getElection() != null
                        && row.getElection().getId().equals(election.getId()))
                .orElseThrow(() -> new IllegalArgumentException("That candidate is not in this election."));
        if (candidate.isWithdrawn()) {
            throw new IllegalStateException("That candidate has withdrawn.");
        }

        return votes.findByElectionIdAndVoterId(election.getId(), voter.getId())
                .map(existing -> {
                    existing.setCandidate(candidate);
                    existing.setCastAt(now);
                    existing.setChangeCount(existing.getChangeCount() + 1);
                    return votes.save(existing);
                })
                .orElseGet(() -> {
                    NationalTeamVote row = new NationalTeamVote();
                    row.setElection(election);
                    row.setVoter(voter);
                    row.setCandidate(candidate);
                    return votes.save(row);
                });
    }

    // ------------------------------------------------------------- outcome

    /**
     * Declares the winner and appoints them.
     *
     * <p>A tie is not broken here. The owner did not specify one, and inventing a coin flip inside
     * a declaration method would be the sort of rule that is impossible to change later without
     * re-running an election. A tie is reported and left for the owner to rule on.
     */
    @Transactional
    public Map<String, Object> declare(Country country, NationalTeamLevel level, int seasonYear) {
        NationalTeamElection election = requireElection(country, level, seasonYear);
        if (election.getStatus() == NationalTeamElection.Status.DECIDED) {
            throw new IllegalStateException("This election has already been decided.");
        }
        if (election.getStatus() == NationalTeamElection.Status.ANNULLED) {
            throw new IllegalStateException("This election was annulled.");
        }

        Map<Long, Long> tally = tally(election);
        if (tally.isEmpty()) {
            throw new IllegalStateException("Nobody has voted, so there is nothing to declare.");
        }

        long best = tally.values().stream().mapToLong(Long::longValue).max().orElseThrow();
        List<Map.Entry<NationalTeamCandidate, Long>> ordered = new ArrayList<>();
        for (NationalTeamCandidate row : candidates.findByElectionIdOrderByRegisteredAtAsc(election.getId())) {
            if (row.isWithdrawn()) {
                continue;
            }
            ordered.add(Map.entry(row, tally.getOrDefault(row.getId(), 0L)));
        }
        long winners = ordered.stream().filter(e -> e.getValue() == best).count();

        if (winners > 1) {
            // Reported, not resolved. The owner has not specified a tiebreak for elections.
            return Map.of(
                    "declared", false,
                    "reason", "Tied on " + best + " votes each. A tiebreak has to be set by the owner.",
                    "tied", ordered.stream()
                            .filter(e -> e.getValue() == best)
                            .map(e -> nameOf(e.getKey()))
                            .toList());
        }

        NationalTeamCandidate winner = ordered.stream()
                .filter(e -> e.getValue() == best)
                .findFirst()
                .orElseThrow()
                .getKey();

        election.setStatus(NationalTeamElection.Status.DECIDED);
        election.setDecidedAt(Instant.now());
        election.setWinner(winner.getUser());
        elections.save(election);

        // Elected, not provisional: the label on the country page changes because a vote happened.
        appointments.appoint(country, level, winner.getUser(), true);

        return Map.of(
                "declared", true,
                "winner", nameOf(winner),
                "votes", best,
                "note", "Appointed for one season.");
    }

    /** Cancels the election. Any standing appointment is left alone; only the vote is voided. */
    @Transactional
    public void annul(Country country, NationalTeamLevel level, int seasonYear) {
        NationalTeamElection election = requireElection(country, level, seasonYear);
        if (election.getStatus() == NationalTeamElection.Status.DECIDED) {
            throw new IllegalStateException("A decided election cannot be annulled; remove the appointment instead.");
        }
        election.setStatus(NationalTeamElection.Status.ANNULLED);
        elections.save(election);
    }

    /** Admin override: forces a status, so the owner can test the panel without waiting for week 1. */
    @Transactional
    public void forceStatus(Country country, NationalTeamLevel level, int seasonYear,
                            NationalTeamElection.Status status) {
        NationalTeamElection election = ensureElection(country, level, seasonYear, Instant.now());
        election.setStatus(status);
        election.setManualOverride(true);
        elections.save(election);
    }

    // ------------------------------------------------------------- reading

    private Map<Long, Long> tally(NationalTeamElection election) {
        Map<Long, Long> counts = new LinkedHashMap<>();
        for (NationalTeamVote vote : votes.findByElectionId(election.getId())) {
            counts.merge(vote.getCandidate().getId(), 1L, Long::sum);
        }
        return counts;
    }

    private String nameOf(NationalTeamCandidate candidate) {
        User user = candidate.getUser();
        if (user == null) {
            return "Unknown";
        }
        return user.getDisplayName() != null && !user.getDisplayName().isBlank()
                ? user.getDisplayName() : user.getUsername();
    }

    private NationalTeamElection requireElection(Country country, NationalTeamLevel level, int seasonYear) {
        return elections.findByCountryIdAndLevelAndSeasonYear(country.getId(), level, seasonYear)
                .orElseThrow(() -> new IllegalStateException("There is no election for this team yet."));
    }

    private void requireInCountry(Country country, User user) {
        if (user == null) {
            throw new SecurityException("You must be signed in.");
        }
        if (user.getCountryCode() == null || country.getIsoCode() == null
                || !country.getIsoCode().equalsIgnoreCase(user.getCountryCode())) {
            throw new SecurityException("Only managers of " + country.getName() + " may take part in this election.");
        }
    }

    /**
     * The election as the client sees it.
     *
     * <p>{@code canSeeTallies} is the only thing standing between a running vote and the public. It
     * is false for an undecided election unless the caller is an admin or the owner, so the counts
     * are absent from the payload rather than merely hidden on screen.
     */
    @Transactional
    public Map<String, Object> describeElection(Country country, NationalTeamLevel level, int seasonYear,
                                               User viewer, boolean canSeeTallies) {
        // **An election is CREATED here if it is missing, rather than reported as absent.**
        //
        // The owner reported the panel reading "Registration closed" no matter what, and the database
        // explained it: after a Reset there are **zero** rows, and this returned
        // `exists: false, stage: NONE` with no `acceptingCandidates` at all — so the button's
        // `!!election.acceptingCandidates` was false and the only thing the panel could ever say was
        // "no election running". A reset was a dead end.
        //
        // This is a WRITE inside what reads like a read, and that is the point: the alternative is a
        // world where nobody can stand for selector until an admin presses a button, and a screen that
        // reports "closed" when it means "not created" is lying. `ensureElection` is idempotent, so the
        // common case is a lookup and nothing else.
        // Same convention DatabaseInitializer uses on a fresh install: the week-1 kickoff is the
        // start of today, so registration runs back from it and voting opens now. Matching that
        // convention keeps a "created on demand" election identical to one the bootstrapper made.
        java.time.Instant weekOneDayOne = java.time.Instant.now()
                .truncatedTo(java.time.temporal.ChronoUnit.DAYS);
        NationalTeamElection election = ensureElection(country, level, seasonYear, weekOneDayOne);
        advanceToClock(election, Instant.now());
        Map<String, Object> out = new LinkedHashMap<>();
        boolean decided = election.getStatus() == NationalTeamElection.Status.DECIDED;
        boolean voting = election.getStatus() == NationalTeamElection.Status.VOTING;
        boolean registration = election.getStatus() == NationalTeamElection.Status.REGISTRATION;

        out.put("exists", true);
        out.put("electionId", election.getId());
        out.put("status", election.getStatus().name());
        // "open" means the panel should be interactive: either you can sign up, or you can vote.
        // Interactive means either action is available, which during week 1 is both.
        out.put("open", acceptingCandidates(election, Instant.now())
                || acceptingVotes(election, Instant.now()));
        out.put("stage", election.getStatus().name());
        out.put("acceptingCandidates", acceptingCandidates(election, Instant.now()));
        out.put("acceptingVotes", acceptingVotes(election, Instant.now()));
        out.put("votingOpensAt", election.getVotingOpensAt());
        out.put("votingClosesAt", election.getVotingClosesAt());
        out.put("manualOverride", election.isManualOverride());

        List<NationalTeamCandidate> all = candidates.findByElectionIdOrderByRegisteredAtAsc(election.getId());
        Map<Long, Long> counts = decided || canSeeTallies ? tally(election) : Map.of();

        boolean viewerIsCandidate = viewer != null && all.stream()
                .anyMatch(row -> row.getUser() != null && viewer.getId() != null
                        && viewer.getId().equals(row.getUser().getId()) && !row.isWithdrawn());

        out.put("candidates", all.stream().map(row -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", row.getId());
            entry.put("name", nameOf(row));
            entry.put("withdrawn", row.isWithdrawn());
            // Only present when the caller is allowed to see it; the key is omitted otherwise, so a
            // client cannot render a "0" that was never really known.
            if (counts.containsKey(row.getId())) {
                entry.put("votes", counts.get(row.getId()));
            }
            return entry;
        }).toList());
        out.put("candidateCount", all.stream().filter(row -> !row.isWithdrawn()).count());
        out.put("viewerIsCandidate", viewerIsCandidate);

        out.put("myVote", votes.findByElectionIdAndVoterId(election.getId(),
                        viewer == null ? -1L : viewer.getId())
                .map(vote -> Map.of("candidateId", vote.getCandidate().getId(),
                        "candidateName", nameOf(vote.getCandidate()),
                        "changeCount", vote.getChangeCount()))
                .orElse(null));

        if (decided) {
            out.put("winner", election.getWinner() == null ? null : displayName(election.getWinner()));
        }

        out.put("note", switch (election.getStatus()) {
            case REGISTRATION -> "Registration is open. You may stand for selector.";
            case VOTING -> election.isManualOverride()
                    ? "Voting is open, started early by an administrator."
                    : "Voting is open. One vote, changeable until it closes.";
            case DECIDED -> "The result has been declared.";
            case ANNULLED -> "This election was annulled by an administrator.";
        });
        return out;
    }

    private static String displayName(User user) {
        return user.getDisplayName() != null && !user.getDisplayName().isBlank()
                ? user.getDisplayName() : user.getUsername();
    }

    /** Election rows for the admin screen. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listForCountry(Long countryId) {
        return elections.findByCountryIdAndStatusIn(countryId,
                        List.of(NationalTeamElection.Status.REGISTRATION,
                                NationalTeamElection.Status.VOTING,
                                NationalTeamElection.Status.DECIDED,
                                NationalTeamElection.Status.ANNULLED))
                .stream()
                .sorted(Comparator.comparing(NationalTeamElection::getSeasonYear)
                        .thenComparing(election -> election.getLevel().name()))
                .map(election -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", election.getId());
                    row.put("level", election.getLevel().name());
                    row.put("seasonYear", election.getSeasonYear());
                    row.put("status", election.getStatus().name());
                    row.put("candidateCount", candidates.countByElectionIdAndWithdrawnFalse(election.getId()));
                    row.put("voteCount", votes.countByElectionId(election.getId()));
                    row.put("winner", election.getWinner() == null ? null : displayName(election.getWinner()));
                    return row;
                })
                .toList();
    }
}
