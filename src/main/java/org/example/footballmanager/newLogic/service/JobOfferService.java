package org.example.footballmanager.newLogic.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.commonmanager.model.User;
import org.example.commonmanager.repository.UserRepository;
import org.example.footballmanager.newLogic.model.CompetitionEntry;
import org.example.footballmanager.newLogic.model.JobOffer;
import org.example.footballmanager.newLogic.model.ManagerTenure;
import org.example.footballmanager.newLogic.model.NotificationKind;
import org.example.footballmanager.newLogic.model.OfferStatus;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.SkillName;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.CompetitionEntryRepository;
import org.example.footballmanager.newLogic.repository.JobOfferRepository;
import org.example.footballmanager.newLogic.repository.ManagerTenureRepository;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A club offering a manager its job, and what happens when he answers (owner, 2026-10-18).
 *
 * <h2>The rules, as the owner set them</h2>
 *
 * <ul>
 *   <li>An **AI club from any higher tier** offers a human manager in a lower one. Same country: "higher
 *       tier" only means anything inside one pyramid.</li>
 *   <li>**One open offer at a time.** A manager with a decision on the table is skipped, so nobody collects
 *       a list of clubs to choose from.</li>
 *   <li>**Twenty-four real hours.** The game clock is real time plus an offset an admin can move, so a
 *       deadline in game time can be jumped past with one press and every open offer would lapse at once.</li>
 *   <li>Declining costs nothing. Accepting makes the offering club his, and his old club becomes AI.</li>
 * </ul>
 *
 * <h2>What accepting writes</h2>
 *
 * <p>Four things, and they have to be one transaction: the old club loses its manager, the new club gains
 * one, the manager's club changes, and the **history** gets a closing row and an opening one. A takeover
 * that half-happened would leave a club marked human with nobody running it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JobOfferService {

    /** How long an offer stands, in real hours. */
    public static final int OFFER_HOURS = 24;

    /** The most offers one pass makes. A weekly job, so this is offers per week, not per manager. */
    public static final int OFFERS_PER_PASS = 3;

    private final JobOfferRepository offers;
    private final ManagerTenureRepository tenures;
    private final UserRepository users;
    private final TeamRepository teams;
    private final PlayerRepository players;
    private final CompetitionEntryRepository entries;
    private final NotificationService notifications;
    private final SeasonService seasons;

    // ------------------------------------------------------------------ making offers

    /**
     * Offers a few jobs to a few managers. The weekly job and the admin button both call this.
     *
     * @return how many offers were made.
     */
    @Transactional
    public int runOffers(int maxOffers) {
        expireOverdue();

        List<User> candidates = new ArrayList<>();
        for (User user : users.findAll()) {
            if (user.getFootballTeam() == null) continue;
            // One at a time. A manager already holding a decision is not given a second.
            if (offers.existsByUserIdAndStatus(user.getId(), OfferStatus.OPEN)) continue;
            if (tierOf(user.getFootballTeam()) <= 1) continue;   // nothing above the top tier
            candidates.add(user);
        }
        // Shuffled, so the same managers are not always the ones asked and the order users come back in
        // does not decide who gets offered a job.
        Collections.shuffle(candidates);

        int made = 0;
        for (User user : candidates) {
            if (made >= maxOffers) break;
            if (offerOne(user)) {
                made++;
            }
        }
        if (made > 0) {
            log.info("Job offers: {} made this pass.", made);
        }
        return made;
    }

    /** One offer to one manager, or false when there is no club to make it. */
    private boolean offerOne(User user) {
        Team from = user.getFootballTeam();
        if (from == null || from.getCompetition() == null) return false;
        Long countryId = from.getCompetition().getCountry() == null
                ? null : from.getCompetition().getCountry().getId();
        Integer fromTier = from.getCompetition().getTier();
        if (countryId == null || fromTier == null || fromTier <= 1) return false;

        List<Team> pool = teams.findAiClubsInHigherTiers(countryId, fromTier);
        if (pool.isEmpty()) return false;

        Team club = pool.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(pool.size()));
        Instant now = Instant.now();

        JobOffer offer = new JobOffer();
        offer.setUser(user);
        offer.setOfferingTeam(club);
        offer.setFromTeam(from);
        offer.setStatus(OfferStatus.OPEN);
        offer.setOfferedAt(now);
        offer.setExpiresAt(now.plus(OFFER_HOURS, ChronoUnit.HOURS));
        offer.setOfferedSeason(seasonOf());
        offer.setOfferedWeek(weekOf());
        offers.save(offer);

        notifications.notify(user, NotificationKind.JOB_OFFERED,
                club.getName() + " want you as their manager. Answer within " + OFFER_HOURS + " hours.",
                "office", offer.getId());
        return true;
    }

    // ------------------------------------------------------------------ reading

    /** The manager's open offers, with everything the Office page shows. */
    @Transactional
    public List<OfferView> openOffers(Long userId) {
        expireOverdue();
        List<OfferView> out = new ArrayList<>();
        for (JobOffer offer : offers.findByUserIdAndStatusOrderByOfferedAtDesc(userId, OfferStatus.OPEN)) {
            out.add(describe(offer));
        }
        return out;
    }

    /** A manager's clubs, newest first — the history. */
    @Transactional(readOnly = true)
    public List<TenureView> history(Long userId) {
        List<TenureView> out = new ArrayList<>();
        for (ManagerTenure tenure : tenures.findByUserIdOrderByStartedAtDesc(userId)) {
            out.add(new TenureView(
                    tenure.getTeam() == null ? null : tenure.getTeam().getId(),
                    tenure.getTeam() == null ? null : tenure.getTeam().getName(),
                    tenure.getStartedSeason(), tenure.getStartedWeek(),
                    tenure.getEndedSeason(), tenure.getEndedWeek(),
                    tenure.isOpen(), tenure.getEndReason()));
        }

        // **The club he is at now always appears, recorded or not.** `manager_tenure` is new, so every
        // manager already in the game has a current spell that predates it — and without this his history
        // would be a list of everywhere he has been except where he is. Synthesised rather than written:
        // the start date is genuinely unknown, and inventing one would be a lie where an absent one is the
        // truth. It also means the first takeover records the club he left even if nothing ever wrote it
        // down.
        User user = users.findById(userId).orElse(null);
        Team current = user == null ? null : user.getFootballTeam();
        if (current != null) {
            boolean alreadyListed = out.stream().anyMatch(row -> row.current()
                    && row.teamId() != null && row.teamId().equals(current.getId()));
            if (!alreadyListed) {
                out.add(0, new TenureView(current.getId(), current.getName(),
                        null, null, null, null, true, null));
            }
        }
        return out;
    }

    /** Everything the offer message carries: the money, the best three players, and where the club sits. */
    private OfferView describe(JobOffer offer) {
        Team club = offer.getOfferingTeam();
        Integer season = seasonOf();
        CompetitionEntry entry = club == null ? null
                : entries.findFirstByTeamAndSeasonCompetitionSeasonYearOrderByIdDesc(club, season).orElse(null);
        Integer position = entry == null ? null : entry.getPosition();
        Integer leagueSize = null;
        if (entry != null && entry.getSeasonCompetition() != null) {
            try {
                leagueSize = entries.findBySeasonCompetition(entry.getSeasonCompetition()).size();
            } catch (RuntimeException e) {
                leagueSize = null;
            }
        }

        return new OfferView(
                offer.getId(),
                club == null ? null : club.getId(),
                club == null ? "Unknown club" : club.getName(),
                club == null || club.getCompetition() == null ? null : club.getCompetition().getId(),
                club == null || club.getCompetition() == null ? null : club.getCompetition().getName(),
                club == null || club.getCompetition() == null ? null : club.getCompetition().getTier(),
                club == null || club.getBudget() == null ? 0.0 : club.getBudget(),
                position,
                leagueSize,
                offer.getExpiresAt() == null ? null : offer.getExpiresAt().toString(),
                offer.getFromTeam() == null ? null : offer.getFromTeam().getName(),
                topPlayers(club));
    }

    /**
     * The three best players, with every skill.
     *
     * <p>By rating, which is the number the game already shows a manager as a player's quality — using
     * market value instead would put a promising teenager above the man who wins the matches.
     */
    private List<OfferPlayer> topPlayers(Team club) {
        if (club == null || club.getId() == null) return List.of();
        List<Player> squad = new ArrayList<>(players.findByTeamId(club.getId()));
        squad.sort(Comparator.comparingInt(Player::getRating).reversed()
                .thenComparing(p -> p.getId() == null ? 0L : p.getId()));

        List<OfferPlayer> out = new ArrayList<>();
        for (Player p : squad.subList(0, Math.min(3, squad.size()))) {
            Map<String, Integer> skills = new LinkedHashMap<>();
            if (p.getSkills() != null && p.getSkills().getSkills() != null) {
                for (Map.Entry<SkillName, Integer> skill : p.getSkills().getSkills().entrySet()) {
                    // FATIGUE is not a skill and it is not a reason to join a club. It is excluded rather
                    // than shown greyed out, because a list called "skills" that carries a tiredness
                    // reading invites the reader to compare it with the others.
                    if (skill.getKey() == SkillName.FATIGUE) continue;
                    skills.put(skill.getKey().name(), skill.getValue());
                }
            }
            out.add(new OfferPlayer(p.getName(),
                    p.getPosition() == null ? null : p.getPosition().name(),
                    p.getRating(), p.getPlayerValue(), skills));
        }
        return out;
    }

    // ------------------------------------------------------------------ answering

    /**
     * Takes the job.
     *
     * <p>Returns false for anything that is not a live offer belonging to this manager: an expired one, an
     * answered one, or somebody else's. The caller turns that into a refusal rather than an exception,
     * because a stale button on a page left open for a day is a normal thing to press.
     */
    @Transactional
    public boolean accept(Long offerId, Long userId) {
        JobOffer offer = liveOffer(offerId, userId);
        if (offer == null) return false;

        Instant now = Instant.now();
        User user = offer.getUser();
        Team from = user.getFootballTeam();
        Team to = offer.getOfferingTeam();
        if (to == null) return false;

        // The old club becomes AI and keeps everything it has: its squad, its tactics, its money. It was
        // never the manager's property, it was his job.
        if (from != null) {
            from.setHumanControlled(false);
            teams.save(from);
            closeOpenTenure(user, "MOVED", now);
        }
        to.setHumanControlled(true);
        teams.save(to);

        user.setFootballTeam(to);
        users.save(user);

        openTenure(user, to, now);

        offer.setStatus(OfferStatus.ACCEPTED);
        offer.setRespondedAt(now);
        offers.save(offer);
        // Anything else he was holding lapses: he has a club now and the others were never his to keep.
        expireOthers(user.getId(), offer.getId(), now);

        notifications.notify(user, NotificationKind.JOB_OFFERED,
                "You are the new manager of " + to.getName() + ".", "office", offer.getId());
        log.info("Job offer {} accepted: {} moved from {} to {}.",
                offerId, user.getUsername(), from == null ? "(none)" : from.getName(), to.getName());
        return true;
    }

    /** Turns the job down. Nothing else happens — the owner's words: "ako odbije - niko nista". */
    @Transactional
    public boolean decline(Long offerId, Long userId) {
        JobOffer offer = liveOffer(offerId, userId);
        if (offer == null) return false;
        offer.setStatus(OfferStatus.REJECTED);
        offer.setRespondedAt(Instant.now());
        offers.save(offer);
        return true;
    }

    /** Marks every offer past its deadline. Real time, never the game clock. */
    @Transactional
    public int expireOverdue() {
        List<JobOffer> stale = offers.findByStatusAndExpiresAtBefore(OfferStatus.OPEN, Instant.now());
        for (JobOffer offer : stale) {
            offer.setStatus(OfferStatus.EXPIRED);
            offer.setRespondedAt(Instant.now());
        }
        if (!stale.isEmpty()) {
            offers.saveAll(stale);
            log.info("Job offers: {} expired.", stale.size());
        }
        return stale.size();
    }

    /** An offer that is still open, belongs to this manager, and has not lapsed. */
    private JobOffer liveOffer(Long offerId, Long userId) {
        JobOffer offer = offers.findById(offerId).orElse(null);
        if (offer == null || userId == null) return null;
        if (offer.getUser() == null || !userId.equals(offer.getUser().getId())) return null;
        if (!offer.isOpen()) return null;
        if (offer.hasExpired(Instant.now())) {
            offer.setStatus(OfferStatus.EXPIRED);
            offer.setRespondedAt(Instant.now());
            offers.save(offer);
            return null;
        }
        return offer;
    }

    private void expireOthers(Long userId, Long keepId, Instant now) {
        for (JobOffer other : offers.findByUserIdAndStatusOrderByOfferedAtDesc(userId, OfferStatus.OPEN)) {
            if (other.getId().equals(keepId)) continue;
            other.setStatus(OfferStatus.EXPIRED);
            other.setRespondedAt(now);
            offers.save(other);
        }
    }

    // ------------------------------------------------------------------ history

    private void openTenure(User user, Team team, Instant now) {
        ManagerTenure tenure = new ManagerTenure();
        tenure.setUser(user);
        tenure.setTeam(team);
        tenure.setStartedAt(now);
        tenure.setStartedSeason(seasonOf());
        tenure.setStartedWeek(weekOf());
        tenures.save(tenure);
    }

    private void closeOpenTenure(User user, String reason, Instant now) {
        for (ManagerTenure open : tenures.findByUserIdAndEndedAtIsNull(user.getId())) {
            open.setEndedAt(now);
            open.setEndedSeason(seasonOf());
            open.setEndedWeek(weekOf());
            open.setEndReason(reason);
            tenures.save(open);
        }
    }

    private Integer seasonOf() {
        try {
            return seasons.getActiveSeasonYear();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private Integer weekOf() {
        try {
            return seasons.getCurrentWeek();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static int tierOf(Team team) {
        if (team == null || team.getCompetition() == null || team.getCompetition().getTier() == null) {
            return 0;
        }
        return team.getCompetition().getTier();
    }

    // ------------------------------------------------------------------ wire shapes

    public record OfferView(Long id,
                            Long clubId,
                            String clubName,
                            Long leagueId,
                            String leagueName,
                            Integer tier,
                            double balance,
                            Integer position,
                            Integer leagueSize,
                            String expiresAt,
                            String fromClubName,
                            List<OfferPlayer> topPlayers) {
    }

    public record OfferPlayer(String name,
                              String position,
                              int rating,
                              double value,
                              Map<String, Integer> skills) {
    }

    public record TenureView(Long teamId,
                             String teamName,
                             Integer startedSeason,
                             Integer startedWeek,
                             Integer endedSeason,
                             Integer endedWeek,
                             boolean current,
                             String endReason) {
    }
}
