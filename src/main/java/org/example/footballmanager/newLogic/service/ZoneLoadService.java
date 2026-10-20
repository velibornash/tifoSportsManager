package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.GameClock;
import org.example.footballmanager.newLogic.model.PlayerZoneLoad;
import org.example.footballmanager.newLogic.model.Zone;
import org.example.footballmanager.newLogic.repository.MatchPageEntry;
import org.example.footballmanager.newLogic.repository.MatchRepository;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.PlayerZoneLoadRepository;
import org.example.footballmanager.newLogic.repository.ZoneLoadMinutes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Turns where a player worked into what it costs them (owner, 2026-09-29).
 *
 * <p>The owner's requirement: "morale / form should be a zone compute, recovery is happening each
 * day." So this is the arithmetic behind both, kept in one place because a recovery rule and a morale
 * rule that disagree with each other produce a game nobody can reason about.
 */
@Service
public class ZoneLoadService {

    private static final Logger log = LoggerFactory.getLogger(ZoneLoadService.class);

    /** Effective minutes a player can bank per day before recovery stops helping. */
    private static final double DAILY_RECOVERY_CAP = 90.0;

    /**
     * Fraction of yesterday's work recovered in a day.
     *
     * <p>Not 1.0. A footballer does not wake up fully recovered, and a model that says he does makes
     * fatigue a number that only ever goes up and never comes down.
     */
    private static final double DAILY_RECOVERY_RATE = 0.65;

    /** How many days of zone load count towards today's recovery. */
    private static final int RECOVERY_WINDOW_DAYS = 2;

    /**
     * Matches per page when reading the recovery window.
     *
     * <p>Deliberately not a round number like 1000. It has to divide the work evenly enough that the
     * last page is not a remainder, and it has to be small enough that one statement never holds more
     * than a slice of the table: a page of 500 matches is 99,000 zone-load rows, against 1,473,120 for
     * the whole window and 17,677,440 for the season.
     */
    private static final int RECOVERY_PAGE_MATCHES = 500;

    /**
     * A ceiling on the paging loop, so a cursor that stops advancing fails the job instead of hanging it.
     *
     * <p>Generous on purpose: the window is two game days, a full-scale matchday is 7,440 matches and so
     * about 15 pages, and even a season-long window would be 180. Ten thousand pages is roughly two
     * hundred times a season, so it only trips on a genuine fault.
     */
    private static final int MAX_RECOVERY_PAGES = 10_000;

    private final PlayerZoneLoadRepository loads;
    private final PlayerRepository players;
    private final org.example.footballmanager.newLogic.repository.GameClockRepository clocks;
    private final MatchRepository matchesPlayed;

    public ZoneLoadService(PlayerZoneLoadRepository loads, PlayerRepository players,
            org.example.footballmanager.newLogic.repository.GameClockRepository clocks,
            MatchRepository matchesPlayed) {
        this.loads = loads;
        this.players = players;
        this.clocks = clocks;
        this.matchesPlayed = matchesPlayed;
    }

    /**
     * How much a player recovers today, based on the zones he worked in his last match.
     *
     * <p>Effective minutes, scaled by the recovery rate and capped. A ninety-minute shift in central
     * midfield recovers more than ninety minutes of standing in a defensive third, which is the entire
     * point of recording zones rather than a single fatigue number.
     */
    @Transactional(readOnly = true)
    public double recoveryFor(Long playerId) {
        // The game clock, like the bulk pass below. Leaving this on the wall clock while the job used
        // the game clock would mean the player screen and the daily job answer the same question two
        // different ways, and the second one to be written is the one that is wrong.
        LocalDateTime windowStart = inGameNow().minusDays(RECOVERY_WINDOW_DAYS);
        List<PlayerZoneLoad> recent = loads.findByPlayerIdOrderByIdDesc(playerId).stream()
                .filter(load -> load.getMatch() != null && load.getMatch().getMatchDate() != null)
                .filter(load -> load.getMatch().getMatchDate().isAfter(windowStart))
                .toList();
        if (recent.isEmpty()) {
            return 0.0;
        }
        double work = 0.0;
        for (PlayerZoneLoad load : recent) {
            work += load.effectiveMinutes();
        }
        return Math.min(work, DAILY_RECOVERY_CAP) * DAILY_RECOVERY_RATE;
    }

    /**
     * A player's workload split by zone, most-worked first.
     *
     * <p>For the player screen and for the morale calculation, which needs to know whether a player
     * has been living in one part of the pitch.
     */
    @Transactional(readOnly = true)
    public Map<Zone, Double> zoneBreakdown(Long playerId) {
        Map<Zone, Double> byZone = new EnumMap<>(Zone.class);
        for (PlayerZoneLoad load : loads.findByPlayerIdOrderByIdDesc(playerId)) {
            byZone.merge(load.getZone(), load.effectiveMinutes(), Double::sum);
        }
        return byZone;
    }

    /**
     * Where a player has spent most of their recent football, or null if there is not enough to say.
     *
     * <p>Null rather than a default: "this player has not played enough to have a shape" is a real
     * answer, and returning MIDFIELD_CENTRE for a reserve who has never played would invent one.
     */
    @Transactional(readOnly = true)
    public Zone dominantZone(Long playerId) {
        Map<Zone, Double> breakdown = zoneBreakdown(playerId);
        double total = breakdown.values().stream().mapToDouble(Double::doubleValue).sum();
        if (total < DAILY_RECOVERY_CAP * 0.5) {
            return null;
        }
        return breakdown.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    /**
     * Applies today's recovery and stamps players who played.
     *
     * <p>Every player who has a {@code lastPlayedAt} recovers; nobody else is touched. That is the
     * difference between a model and a tax on the reserves.
     */
    @Transactional
    public int applyDailyRecovery() {
        LocalDateTime gameNow = inGameNow();
        LocalDateTime windowStart = gameNow.minusDays(RECOVERY_WINDOW_DAYS);

        // One query for the whole world instead of one per player. The previous shape called
        // recoveryFor(playerId) inside a findAll() loop, and recoveryFor queried the zone loads, so a
        // 16,354-player world cost 16,354 round trips and the job logged its result 42 minutes later.
        //
        // The arithmetic is identical to recoveryFor(): same window, same sum, same cap, same rate. The
        // per-player method stays for the single-player screens, where one query is correct.
        //
        // **Paged on the match id, because the unpaged read scans a whole season to find one matchday.**
        // `findLoadMinutesPlayedSince` joins the window's matches to a scan of every zone load ever
        // written: one matchday is 7,440 matches and 1,473,120 rows wanted out of 17,677,440 read,
        // measured at 4,441 ms. A page of match ids turns that into `match_id IN (:ids)`, which an index
        // on `match_id` can serve, and the job reads the rows it wants instead of the table it has.
        //
        // The arithmetic is untouched: same window, same `merge`, same cap, same rate. Only the order the
        // rows arrive in changes, and a per-player sum does not care about the order it is summed in.
        Map<Long, Double> workedSinceWindow = new HashMap<>();
        // The cursor is the last row of the previous page, both halves of it: the window is walked in
        // (matchDate, id) order so the page query can use the index on those two columns.
        LocalDateTime cursorDate = windowStart;
        long cursorId = Long.MIN_VALUE;
        int matchesRead = 0;
        int pages = 0;
        List<MatchPageEntry> page;
        do {
            page = matchesPlayed.findMatchPagePlayedSince(windowStart, cursorDate, cursorId,
                    PageRequest.of(0, RECOVERY_PAGE_MATCHES));
            if (page.isEmpty()) {
                break;
            }
            for (ZoneLoadMinutes load : loads.findLoadMinutesForMatches(idsOf(page))) {
                if (load.playerId() == null) {
                    continue;
                }
                workedSinceWindow.merge(load.playerId(), load.effectiveMinutes(), Double::sum);
            }
            matchesRead += page.size();
            pages++;
            MatchPageEntry last = page.get(page.size() - 1);
            cursorDate = last.matchDate();
            cursorId = last.id();
            if (pages >= MAX_RECOVERY_PAGES) {
                // **A paging loop that cannot end is worse than a job that fails.**
                //
                // The window is two game days, so a full-scale matchday is 45 pages and the bound below is
                // two hundred times that. Reaching it means the cursor is not advancing — the page query is
                // returning rows at or before where the last page stopped — and the alternative is a loop
                // that reads the same page forever.
                //
                // Stopping quietly would be the worst outcome: recovery would credit less work than the
                // players did, and the log would report a plausible number of touched players. So it
                // throws, the job is recorded as failed, and the reason is in the message.
                throw new IllegalStateException("Daily recovery read " + pages
                        + " pages of " + RECOVERY_PAGE_MATCHES + " matches without reaching the end of the "
                        + "window; the page cursor is not advancing. Refusing to loop rather than credit "
                        + "a fraction of the work.");
            }
        } while (page.size() == RECOVERY_PAGE_MATCHES);
        log.debug("Daily recovery read {} match(es) in the window over {} page(s) of {}.",
                matchesRead, pages, RECOVERY_PAGE_MATCHES);
        if (workedSinceWindow.isEmpty()) {
            return 0;
        }

        int touched = 0;
        // **Only the players the map already names.** This used to ask
        // findByLastPlayedAtIsNotNull() — every player who has ever played — and `continue` past
        // everyone not in the map. The map was built on the line above, so the loop below already knows
        // exactly who can be touched: loading the rest of the world to discover that it was going to
        // skip them is 370,000 rows a day to recover the few thousand who actually turned up.
        //
        // The `lastPlayedAt` predicate is kept in the query rather than assumed from "these ids came
        // from zone loads, so they must have played". That is true today and would be the sort of thing
        // that stops being true; stated here, a violation is a missed player rather than a quietly
        // recovered one.
        for (Player player : players.findByIdInAndLastPlayedAtIsNotNull(workedSinceWindow.keySet())) {
            Double work = workedSinceWindow.get(player.getId());
            if (work == null || work <= 0.0) {
                continue;
            }
            player.setMorale(player.getMorale() + 0.2);
            touched++;
        }
        if (touched > 0) {
            log.debug("Daily recovery applied to {} player(s) at {}.", touched, gameNow);
        }
        return touched;
    }

    /**
     * The in-game date, not the wall clock.
     *
     * <p>Recovery is a claim about <i>a day of football</i>, and the recovery window used to be measured
     * against {@link LocalDateTime#now()}. The owner can play a twelve-week season in one evening, which
     * means every match he has ever played falls inside a two-day wall-clock window — so "what did this
     * player do in the last two days" answered with the entire season. Reading the game clock makes the
     * question mean what it says.
     *
     * <p>This is the opposite decision to the one made for online presence, deliberately: presence means
     * "is a person at their desk", which is a fact about the real world, while recovery means "how much
     * did he play yesterday", which is a fact about the calendar the manager is living in.
     *
     * <p>Reads the clock repository directly rather than through the clock service, because the service
     * depends on the job runner and the job runner on this class, and a cycle here would fail the boot
     * for the whole world.
     */
    private LocalDateTime inGameNow() {
        return clocks.findTopByOrderByIdDesc()
                .map(GameClock::getCurrentDate)
                .filter(java.util.Objects::nonNull)
                .orElse(LocalDateTime.now());
    }

    /**
     * The page's match ids, in page order — the only thing the zone-load query needs from a page.
     *
     * <p>Kept as a list rather than a set because the zone-load query is an {@code IN} list and its cost
     * is measured in the length of that list, not in its order. The order is still the window's order,
     * which is what makes the aggregate reproducible.
     */
    private static List<Long> idsOf(List<MatchPageEntry> page) {
        List<Long> ids = new ArrayList<>(page.size());
        for (MatchPageEntry entry : page) {
            ids.add(entry.id());
        }
        return ids;
    }
}
