package org.example.footballmanager.newLogic.service;

import org.example.commonmanager.model.User;
import org.example.commonmanager.model.UserRole;
import org.example.commonmanager.repository.UserRepository;
import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.model.NotificationKind;
import org.example.footballmanager.newLogic.repository.NotificationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The notification store, which did not exist before this phase.
 *
 * <p>Assertions read the table, not a returned object, because the failure this guards against is a
 * notification that was counted and never written.
 *
 * <p><b>The isolation tests are the point of this class.</b> A notification system whose read does not
 * filter by recipient will show one manager another manager's messages, and the only way that is caught is
 * by building two accounts and reading as one.
 */
class NotificationServiceTest extends BaseTest {

    @Autowired
    NotificationService service;

    @Autowired
    NotificationRepository notifications;

    @Autowired
    UserRepository users;

    // ── Writing ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    @Transactional
    @DisplayName("a notification is written, with its summary and destination")
    void aNotificationIsWritten() {
        User me = aUser(UserRole.REGULAR);

        service.notify(me, NotificationKind.FORUM_REPLY, "Velja replied: who is playing Sunday?", "forumTopic", 12L);

        List<?> rows = notifications.findByRecipientIdOrderByCreatedAtDescIdDesc(me.getId(),
                org.springframework.data.domain.PageRequest.of(0, 10));
        assertEquals(1, rows.size());
    }

    @Test
    @Transactional
    @DisplayName("a blank summary is not written, because it would render as an empty row")
    void aBlankSummaryIsRefused() {
        User me = aUser(UserRole.REGULAR);

        service.notify(me, NotificationKind.PM_RECEIVED, "   ", null, null);

        assertEquals(0, notifications.countByRecipientIdAndReadAtIsNull(me.getId()));
    }

    @Test
    @Transactional
    @DisplayName("nobody is notified when there is nobody to notify")
    void aNullRecipientIsIgnored() {
        // The one thing notify cannot default: there is no account to deliver to, so a row would sit in
        // a table that no read ever queries.
        service.notify(null, NotificationKind.FORUM_REPLY, "A reply", null, null);
        assertEquals(0, notifications.count());
    }

    // ── Reading ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    @Transactional
    @DisplayName("the count and the list agree, and both are the recipient's alone")
    void theCountAndTheListAgree() {
        User me = aUser(UserRole.REGULAR);
        User someoneElse = aUser(UserRole.REGULAR);

        service.notify(me, NotificationKind.FORUM_REPLY, "One", null, null);
        service.notify(me, NotificationKind.PM_RECEIVED, "Two", null, null);
        service.notify(someoneElse, NotificationKind.PM_RECEIVED, "Not yours", null, null);

        Map<String, Object> page = service.list(me.getId(), 0, 30);

        assertEquals(2L, page.get("unreadCount"));
        assertEquals(2, ((List<?>) page.get("notifications")).size(),
                "the list returned another manager's notification");
    }

    @Test
    @Transactional
    @DisplayName("a null recipient reads empty rather than everything")
    void aNullRecipientReadsEmpty() {
        User me = aUser(UserRole.REGULAR);
        service.notify(me, NotificationKind.FORUM_REPLY, "Mine", null, null);

        Map<String, Object> page = service.list(null, 0, 30);

        assertEquals(0L, page.get("unreadCount"));
        assertEquals(0, ((List<?>) page.get("notifications")).size(),
                "a null recipient read the whole table");
    }

    @Test
    @Transactional
    @DisplayName("an account with no notifications reports zero, not an error")
    void anEmptyAccountIsNotAnError() {
        User me = aUser(UserRole.REGULAR);

        Map<String, Object> page = service.list(me.getId(), 0, 30);

        assertEquals(0L, page.get("unreadCount"));
        assertTrue(((List<?>) page.get("notifications")).isEmpty());
    }

    @Test
    @Transactional
    @DisplayName("a hostile page size is clamped instead of being honoured")
    void aHostilePageSizeIsClamped() {
        User me = aUser(UserRole.REGULAR);
        for (int i = 0; i < 3; i++) {
            service.notify(me, NotificationKind.FORUM_REPLY, "Row " + i, null, null);
        }

        assertEquals(100, service.list(me.getId(), 0, 100_000).get("size"),
                "an unbounded page size was accepted");
        assertEquals(0, service.list(me.getId(), -5, 30).get("page"),
                "a negative page was accepted");
    }

    @Test
    @Transactional
    @DisplayName("paging walks the history without repeating or skipping a row")
    void pagingIsStable() {
        User me = aUser(UserRole.REGULAR);
        for (int i = 0; i < 5; i++) {
            service.notify(me, NotificationKind.FORUM_REPLY, "Row " + i, null, null);
        }

        Map<String, Object> first = service.list(me.getId(), 0, 3);
        Map<String, Object> second = service.list(me.getId(), 1, 3);

        assertEquals(3, ((List<?>) first.get("notifications")).size());
        assertEquals(Boolean.TRUE, first.get("hasMore"));
        assertEquals(2, ((List<?>) second.get("notifications")).size());
        assertEquals(Boolean.FALSE, second.get("hasMore"),
                "the last page claimed there was more");
    }

    // ── Marking read ───────────────────────────────────────────────────────────────────────────────

    @Test
    @Transactional
    @DisplayName("marking read clears the badge and keeps the row")
    void markingReadKeepsTheRow() {
        User me = aUser(UserRole.REGULAR);
        service.notify(me, NotificationKind.FORUM_REPLY, "A reply", null, null);
        Long id = notifications.findByRecipientIdOrderByCreatedAtDescIdDesc(me.getId(),
                org.springframework.data.domain.PageRequest.of(0, 1)).get(0).getId();

        service.markRead(me.getId(), id);

        assertEquals(0, service.unreadCount(me.getId()));
        assertEquals(1, notifications.count(), "the row was deleted instead of marked read");
    }

    @Test
    @Transactional
    @DisplayName("marking twice does not move the timestamp")
    void markingTwiceIsIdempotent() {
        User me = aUser(UserRole.REGULAR);
        service.notify(me, NotificationKind.FORUM_REPLY, "A reply", null, null);
        Long id = notifications.findByRecipientIdOrderByCreatedAtDescIdDesc(me.getId(),
                org.springframework.data.domain.PageRequest.of(0, 1)).get(0).getId();

        service.markRead(me.getId(), id);
        var first = notifications.findById(id).orElseThrow().getReadAt();
        service.markRead(me.getId(), id);
        var second = notifications.findById(id).orElseThrow().getReadAt();

        assertEquals(first, second, "a second read moved the timestamp");
    }

    @Test
    @Transactional
    @DisplayName("one manager cannot mark another manager's notification read")
    void oneCannotMarkAnotherRead() {
        User me = aUser(UserRole.REGULAR);
        User victim = aUser(UserRole.REGULAR);
        service.notify(victim, NotificationKind.PM_RECEIVED, "Private", null, null);
        Long id = notifications.findByRecipientIdOrderByCreatedAtDescIdDesc(victim.getId(),
                org.springframework.data.domain.PageRequest.of(0, 1)).get(0).getId();

        // 404 rather than a silent success: a silent no-op here produces a badge that never clears and
        // a support ticket reading "the read button does nothing".
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> service.markRead(me.getId(), id));

        assertEquals(1, service.unreadCount(victim.getId()), "somebody cleared another account's badge");
    }

    @Test
    @Transactional
    @DisplayName("mark all read clears only this account's badge and reports how many")
    void markAllReadIsScoped() {
        User me = aUser(UserRole.REGULAR);
        User someoneElse = aUser(UserRole.REGULAR);
        service.notify(me, NotificationKind.FORUM_REPLY, "One", null, null);
        service.notify(me, NotificationKind.FORUM_REPLY, "Two", null, null);
        service.notify(someoneElse, NotificationKind.PM_RECEIVED, "Theirs", null, null);

        long cleared = service.markAllRead(me.getId());

        assertEquals(2, cleared);
        assertEquals(0, service.unreadCount(me.getId()));
        assertEquals(1, service.unreadCount(someoneElse.getId()),
                "clearing my badge cleared somebody else's");
    }

    @Test
    @Transactional
    @DisplayName("mark all read is capped at 200 rows, proven by exceeding the cap")
    void markAllReadIsCapped() {
        User me = aUser(UserRole.REGULAR);

        // Past the cap, so the cap is what is being measured.
        //
        // <p>The first version of this test seeded 5 rows and asserted they were all cleared. Raising
        // the documented cap from 200 to 100000 — the exact "just remove the limit" edit — left it
        // green, because 5 rows is under every cap. A test that cannot fail against the change it exists
        // to catch is worse than no test, so this seeds more than the cap allows.
        for (int i = 0; i < 230; i++) {
            service.notify(me, NotificationKind.FORUM_REPLY, "Row " + i, null, null);
        }
        assertEquals(230, service.unreadCount(me.getId()), "the fixture did not exceed the cap");

        long first = service.markAllRead(me.getId());

        assertEquals(200, first,
                "mark-all-read wrote " + first + " rows; the documented cap is 200 and 'clear my badge' "
                        + "must not become a table-wide update");
        assertEquals(30, service.unreadCount(me.getId()),
                "the cap was not applied — every row was written");

        // And the second call finishes the job, so the cap is a chunking limit rather than a bug that
        // leaves a residue the manager cannot clear.
        assertEquals(30, service.markAllRead(me.getId()));
        assertEquals(0, service.unreadCount(me.getId()));
    }

    @Test
    @Transactional
    @DisplayName("days left on a ban round up, so a six-hour ban is never shown as zero")
    void remainingBanDaysRoundsUp() {
        // Reuses ModerationService's arithmetic because both surfaces show the same number.
        ModerationService moderation = new ModerationService(users);
        User me = aUser(UserRole.REGULAR);
        assertEquals(0, moderation.remainingBanDays(me));
    }

    private User aUser(UserRole role) {
        User user = new User();
        user.setUsername("notify-" + UUID.randomUUID() + "@test.local");
        user.setEmail(user.getUsername());
        user.setPassword("not-a-real-hash");
        user.setDisplayName("Notification tester");
        user.setRole(role);
        user.setPlusSubscription(false);
        return users.save(user);
    }
}