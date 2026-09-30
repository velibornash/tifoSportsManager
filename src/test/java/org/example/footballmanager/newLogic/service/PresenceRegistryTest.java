package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.BaseTest;
import org.example.commonmanager.model.User;
import org.example.commonmanager.model.UserRole;
import org.example.commonmanager.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Who is actually at their desk (owner, 2026-09-30).
 *
 * <p>The World page's stat read {@code countByRoleIsNotNull()} and was labelled "Human players" — the
 * number of accounts that have ever registered, unchanged since the day they did. The board said not to
 * call it "online" until something real existed, so these pin what "real" has to mean here: a wall-clock
 * window, a throttle, and a number that can honestly be zero.
 */
class PresenceRegistryTest extends BaseTest {

    @Autowired private PresenceRegistry presence;
    @Autowired private UserRepository users;

    @Test
    @Transactional
    @DisplayName("an account is online only while it keeps making requests")
    void onlineIsAWindowNotAFlag() {
        String username = "presence-window@test.local";
        User user = users.save(user(username));

        assertNull(user.getLastSeenAt(), "a new account starts with no presence at all");

        presence.markSeen(username);
        assertTrue(presence.isOnline(username), "the account just made a request but reads as offline");

        // The window is the whole point. Stamping an hour ago must not count, or the number would only
        // ever grow and "online" would mean "has logged in once since the last reset".
        user = users.findById(user.getId()).orElseThrow();
        user.setLastSeenAt(LocalDateTime.now().minusHours(1));
        users.save(user);
        presence.forget();

        assertFalse(presence.isOnline(username),
                "an account last seen an hour ago counts as online, so the window is not being applied");
    }

    @Test
    @Transactional
    @DisplayName("an account that has never been seen is not online")
    void neverSeenIsNotOnline() {
        String username = "presence-never@test.local";
        users.save(user(username));
        presence.forget();

        assertFalse(presence.isOnline(username), "an account with no last-seen reads as online");
        assertNull(users.findByUsernameOrEmail(username).orElseThrow().getLastSeenAt());
    }

    @Test
    @Transactional
    @DisplayName("registered and online are two different numbers")
    void registeredAndOnlineDiffer() {
        String online = "presence-online@test.local";
        String dormant = "presence-dormant@test.local";
        users.save(user(online));
        User sleeper = users.save(user(dormant));
        sleeper.setLastSeenAt(LocalDateTime.now().minusDays(3));
        users.save(sleeper);
        presence.forget();

        presence.markSeen(online);

        long registered = presence.registeredCount();
        long onlineNow = presence.onlineCount();

        assertTrue(registered > onlineNow,
                "registered (" + registered + ") is not larger than online (" + onlineNow + ") — either "
                        + "the numbers are the same figure under two names, or the window is not applied");
        assertTrue(presence.isOnline(online));
        assertFalse(presence.isOnline(dormant),
                "an account last seen three days ago counts as online");
    }

    @Test
    @Transactional
    @DisplayName("a burst of requests writes the column once, not once per request")
    void writesAreThrottled() {
        String username = "presence-throttle@test.local";
        User user = users.save(user(username));
        assertNull(user.getLastSeenAt(), "the account already had a presence before its first request");

        // One request writes it, so there is a value to compare the burst against. Comparing against the
        // pre-request state instead would just assert that a write happened at some point, which a
        // throttle that never throttles would also satisfy.
        presence.markSeen(username);
        LocalDateTime afterFirst = users.findById(user.getId()).orElseThrow().getLastSeenAt();
        assertNotNull(afterFirst, "the first authenticated request did not write the column");

        // The SPA polls the clock, so one manager with the page open is a request every few seconds.
        // Writing on each of them would be a database write per request from a filter, on a column read
        // once per page view.
        for (int request = 0; request < 50; request++) {
            presence.markSeen(username);
        }

        LocalDateTime afterBurst = users.findById(user.getId()).orElseThrow().getLastSeenAt();
        assertEquals(afterFirst, afterBurst,
                "the column moved during a burst of fifty requests, so the throttle is not holding");
        // And the burst still counts as online, because the map is the live truth and the column is only
        // a durable shadow of it. A throttle that stopped recording presence would be worse than one that
        // stopped writing it.
        assertTrue(presence.isOnline(username), "the account went offline during its own request burst");
    }

    @Test
    @Transactional
    @DisplayName("presence is wall-clock time, never the game clock")
    void presenceIgnoresTheGameClock() {
        String username = "presence-wallclock@test.local";
        users.save(user(username));

        java.time.LocalDateTime before = java.time.LocalDateTime.now();
        presence.markSeen(username);
        java.time.LocalDateTime after = java.time.LocalDateTime.now();
        LocalDateTime stamped = users.findByUsernameOrEmail(username).orElseThrow().getLastSeenAt();

        // The game clock can be advanced a week in one admin click. A presence system driven by it would
        // report every account in the world as online the moment the owner moved it.
        assertTrue(stamped.isAfter(before.minusMinutes(1)) && stamped.isBefore(after.plusMinutes(1)),
                "the stamp is " + stamped + ", which is not real time around the call (" + before
                        + " to " + after + ")");
    }

    @Test
    @Transactional
    @DisplayName("a blank or missing account is never counted")
    void rubbishInputIsIgnored() {
        presence.forget();

        presence.markSeen(null);
        presence.markSeen("");
        presence.markSeen("   ");

        assertEquals(0, presence.tracked(), "the registry tracked an account with no identity");
        assertFalse(presence.isOnline(null));
        assertFalse(presence.isOnline(""));
        assertEquals(0, presence.onlineCount(),
                "blank input produced an online row, so something is counting a non-account");
    }

    @Test
    @Transactional
    @DisplayName("the window is five minutes")
    void theWindowIsDocumented() {
        assertEquals(5, PresenceRegistry.ONLINE_WINDOW.toMinutes(),
                "the World page states this number in its hint text, so the two must not drift");
    }

    private User user(String username) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(username);
        user.setPassword("{noop}irrelevant");
        user.setRole(UserRole.REGULAR);
        user.setPlusSubscription(false);
        return user;
    }
}
