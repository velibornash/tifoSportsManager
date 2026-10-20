package org.example.footballmanager.newLogic.service;

import org.example.commonmanager.model.User;
import org.example.commonmanager.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who is actually at their desk (owner, 2026-09-30).
 *
 * <p>The World page's stat was labelled "Human players" and read
 * {@code countByRoleIsNotNull()} — the number of <b>registered accounts</b>. The board's note was
 * explicit: "Do not label the number 'online' until this exists." It did not exist, so the honest fixes
 * were to build it or to stop implying it. This is the building half.
 *
 * <h2>Why the writes are throttled and why that matters</h2>
 *
 * <p>This is called from the JWT filter, which sees every authenticated request. The SPA polls the
 * game clock, so one manager with the page open generates a request every few seconds, and there is no
 * way to put a hook in the filter that is not on the hot path of all of them. Writing
 * {@code last_seen_at} per request would be a database write per request, from a filter, on a column
 * that is read once per page view.
 *
 * <p>So the in-memory map is the live truth and the column is a durable shadow of it, refreshed at most
 * once per {@link #WRITE_INTERVAL} per account. A restart empties the map, and the number honestly drops
 * to zero until people come back — which is the correct answer, not a lost state.
 *
 * <h2>Wall-clock, never the game clock</h2>
 *
 * <p>Everything here uses {@link LocalDateTime#now()}. The game clock can be advanced a week in a single
 * admin click, and a presence system driven by it would report every account in the world as online the
 * moment the owner moved it.
 */
@Service
public class PresenceRegistry {

    private static final Logger log = LoggerFactory.getLogger(PresenceRegistry.class);

    /** How long after its last request an account still counts as online. */
    public static final Duration ONLINE_WINDOW = Duration.ofMinutes(5);

    /** The shortest gap between two writes of the same account's column. */
    private static final Duration WRITE_INTERVAL = Duration.ofMinutes(1);

    private final UserRepository users;
    private final Map<String, LocalDateTime> lastRequestByUser = new ConcurrentHashMap<>();
    private final Map<String, LocalDateTime> lastWriteByUser = new ConcurrentHashMap<>();

    public PresenceRegistry(UserRepository users) {
        this.users = users;
    }

    /**
     * Records that this account just made a request.
     *
     * <p>Never throws. A presence stamp is the least important thing that happens on a request, and a
     * failure here must not be the reason a manager cannot open the dashboard.
     */
    public void markSeen(String username) {
        if (username == null || username.isBlank()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        lastRequestByUser.put(username, now);
        try {
            if (dueForWrite(username, now)) {
                writeLastSeen(username, now);
            }
        } catch (RuntimeException e) {
            log.debug("Could not stamp presence for {}: {}", username, e.getMessage());
        }
    }

    private boolean dueForWrite(String username, LocalDateTime now) {
        LocalDateTime lastWrite = lastWriteByUser.get(username);
        if (lastWrite != null && lastWrite.plus(WRITE_INTERVAL).isAfter(now)) {
            return false;
        }
        // Claim the slot before the write, so two requests arriving together do not both write.
        lastWriteByUser.put(username, now);
        return true;
    }

    /** Its own transaction: the filter runs before any controller transaction exists. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void writeLastSeen(String username, LocalDateTime when) {
        users.findByUsernameOrEmail(username).ifPresent(user -> {
            user.setLastSeenAt(when);
            users.save(user);
        });
    }

    /** The number the World page shows next to "online". */
    public long onlineCount() {
        return users.countByLastSeenAtAfter(cutoff());
    }

    /** Registered accounts — the old "Human players" number, kept and correctly labelled. */
    public long registeredCount() {
        return users.countByRoleIsNotNull();
    }

    /**
     * Is this specific account online right now?
     *
     * <p>Checks the column as well as the map, and the reason is a disagreement this class would
     * otherwise ship: {@link #onlineCount()} counts the column, so a registry reading only its own map
     * would say "you are offline" on a page that has just said three people are online — after every
     * restart, and for up to a minute after any account's first request. The map is a shortcut for the
     * freshest answer; the column is what makes the two agree.
     */
    public boolean isOnline(String username) {
        if (username == null || username.isBlank()) {
            return false;
        }
        LocalDateTime lastRequest = lastRequestByUser.get(username);
        if (lastRequest != null && lastRequest.isAfter(cutoff())) {
            return true;
        }
        LocalDateTime cutoff = cutoff();
        return users.findByUsernameOrEmail(username)
                .map(User::getLastSeenAt)
                .map(lastSeen -> lastSeen != null && lastSeen.isAfter(cutoff))
                .orElse(false);
    }

    /** The moment before which an account no longer counts. */
    public LocalDateTime cutoff() {
        return LocalDateTime.now().minus(ONLINE_WINDOW);
    }

    /** Forgets the in-memory state. Used by tests, and honest on a restart. */
    public void forget() {
        lastRequestByUser.clear();
        lastWriteByUser.clear();
    }

    /** How many accounts this registry has seen this run, whether or not their column is written. */
    public int tracked() {
        return lastRequestByUser.size();
    }
}
