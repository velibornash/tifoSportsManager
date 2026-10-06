package org.example.footballmanager.newLogic.service;

import org.example.commonmanager.model.User;
import org.example.commonmanager.model.UserRole;
import org.example.commonmanager.repository.UserRepository;
import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.model.DirectMessage;
import org.example.footballmanager.newLogic.model.MessageThread;
import org.example.footballmanager.newLogic.model.Notification;
import org.example.footballmanager.newLogic.model.NotificationKind;
import org.example.footballmanager.newLogic.repository.DirectMessageRepository;
import org.example.footballmanager.newLogic.repository.MessageThreadRepository;
import org.example.footballmanager.newLogic.repository.NotificationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

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
 * Private messages: a subject, a body, and a thread you can follow.
 *
 * <p>The owner's specification, clause by clause:
 *
 * <ul>
 *   <li>pick a manager from a list and send a direct message with <b>subject and body</b></li>
 *   <li>replying within a thread, so the correspondence can be followed</li>
 *   <li>sendable to any account with a live login — <b>not</b> only whoever is online</li>
 *   <li>a notification goes to the ticker and the notification store</li>
 * </ul>
 *
 * <p><b>The isolation tests are the point.</b> A messaging system whose read does not check membership
 * will show one manager another manager's correspondence, and the only way to catch that is two accounts
 * writing to each other and reading as one.
 */
class MessageServiceTest extends BaseTest {

    @Autowired
    MessageService messages;

    @Autowired
    MessageThreadRepository threads;

    @Autowired
    DirectMessageRepository directMessages;

    @Autowired
    NotificationRepository notifications;

    @Autowired
    UserRepository users;

    // ── Sending ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    @Transactional
    @DisplayName("a first message opens a thread carrying the subject")
    void aFirstMessageOpensAThread() {
        User sender = aUser();
        User recipient = aUser();

        DirectMessage sent = messages.send(sender, null, recipient.getId(),
                "About the Sremac keeper", "Will you take 500k for him?");

        MessageThread thread = threads.findById(sent.getThread().getId()).orElseThrow();
        assertEquals("About the Sremac keeper", thread.getSubject());
        assertEquals(1, thread.getMessageCount());
        assertEquals(sender.getId(), thread.getSenderUser().getId());
        assertEquals(recipient.getId(), thread.getRecipientUser().getId());
        assertNotNull(sent.getId());
    }

    @Test
    @Transactional
    @DisplayName("a reply joins the existing thread instead of starting another")
    void aReplyJoinsTheThread() {
        User sender = aUser();
        User recipient = aUser();
        DirectMessage first = messages.send(sender, null, recipient.getId(), "Subject", "First message.");

        DirectMessage reply = messages.send(recipient, first.getThread().getId(), null, null, "Second message.");

        assertEquals(first.getThread().getId(), reply.getThread().getId(),
                "a reply started its own conversation, so the history is in two places");
        assertEquals(1, threads.count(),
                "two threads exist between the same two managers");
        MessageThread thread = threads.findById(first.getThread().getId()).orElseThrow();
        assertEquals(2, thread.getMessageCount());
    }

    @Test
    @Transactional
    @DisplayName("a second subject does not fork the conversation, which is what makes it followable")
    void aSecondSubjectDoesNotForkTheThread() {
        User sender = aUser();
        User recipient = aUser();
        DirectMessage first = messages.send(sender, null, recipient.getId(), "Original subject", "First.");

        // The same route, addressed by recipient rather than thread, with a different subject. It must
        // continue the conversation: two threads about the same transfer read as two conversations.
        DirectMessage second = messages.send(sender, null, recipient.getId(),
                "Completely different subject", "Following up.");

        assertEquals(first.getThread().getId(), second.getThread().getId());
        assertEquals("Original subject",
                threads.findById(first.getThread().getId()).orElseThrow().getSubject(),
                "the thread's subject changed when it was continued");
    }

    @Test
    @Transactional
    @DisplayName("a blank message is refused rather than sent as an empty row")
    void aBlankMessageIsRefused() {
        User sender = aUser();
        User recipient = aUser();

        assertThrows(IllegalArgumentException.class,
                () -> messages.send(sender, null, recipient.getId(), "Subject", "   "));
        assertEquals(0, threads.count(), "a refused message still opened a thread");
    }

    @Test
    @Transactional
    @DisplayName("you cannot message yourself")
    void youCannotMessageYourself() {
        User sender = aUser();

        assertThrows(IllegalArgumentException.class,
                () -> messages.send(sender, null, sender.getId(), "Subject", "Talking to myself."));
    }

    @Test
    @Transactional
    @DisplayName("sending to nobody is refused rather than opening a thread with a null recipient")
    void sendingToNobodyIsRefused() {
        User sender = aUser();

        assertThrows(IllegalArgumentException.class,
                () -> messages.send(sender, null, null, "Subject", "Into the void."));
        assertEquals(0, threads.count());
    }

    @Test
    @Transactional
    @DisplayName("an unknown recipient is a 404")
    void anUnknownRecipientIsANotFound() {
        User sender = aUser();

        ResponseStatusException thrown = assertThrows(ResponseStatusException.class,
                () -> messages.send(sender, null, 999_999_999L, "Subject", "Hello?"));
        assertEquals(org.springframework.http.HttpStatus.NOT_FOUND, thrown.getStatusCode());
    }

    @Test
    @Transactional
    @DisplayName("a thread with no subject gets one rather than a blank title in a list")
    void aThreadWithNoSubjectGetsOne() {
        User sender = aUser();
        User recipient = aUser();

        DirectMessage sent = messages.send(sender, null, recipient.getId(), null, "Body only.");

        assertEquals("(no subject)",
                threads.findById(sent.getThread().getId()).orElseThrow().getSubject());
    }

    // ── Isolation ───────────────────────────────────────────────────────────────────────────────────

    @Test
    @Transactional
    @DisplayName("a third party cannot read a conversation they are not part of")
    void aThirdPartyCannotReadTheThread() {
        User a = aUser();
        User b = aUser();
        User stranger = aUser();
        DirectMessage sent = messages.send(a, null, b.getId(), "Private", "Between the two of us.");

        assertThrows(ResponseStatusException.class,
                () -> messages.viewThread(stranger, sent.getThread().getId(), 0, 30));

        assertThrows(ResponseStatusException.class,
                () -> messages.send(stranger, sent.getThread().getId(), null, null, "Butting in."));
    }

    @Test
    @Transactional
    @DisplayName("one manager's inbox contains only his conversations")
    void anInboxIsScopedToItsOwner() {
        User a = aUser();
        User b = aUser();
        User c = aUser();
        messages.send(a, null, b.getId(), "A to B", "Hello.");
        messages.send(b, null, c.getId(), "B to C", "Hello.");

        Map<String, Object> inboxOfA = messages.inbox(a, 0, 30);
        List<?> rows = (List<?>) inboxOfA.get("threads");

        assertEquals(1, rows.size(), "the inbox shows a conversation the viewer is not part of");
        assertEquals("A to B", ((Map<?, ?>) rows.get(0)).get("subject"));
    }

    @Test
    @Transactional
    @DisplayName("a thread appears in both participants' inboxes, once each")
    void aThreadAppearsInBothInboxes() {
        User a = aUser();
        User b = aUser();
        DirectMessage sent = messages.send(a, null, b.getId(), "Subject", "Body");

        Map<String, Object> inboxOfA = messages.inbox(a, 0, 30);
        Map<String, Object> inboxOfB = messages.inbox(b, 0, 30);

        assertEquals(1, ((List<?>) inboxOfA.get("threads")).size());
        assertEquals(1, ((List<?>) inboxOfB.get("threads")).size(),
                "the recipient does not see the conversation at all");
        assertEquals("Subject", ((Map<?, ?>) ((List<?>) inboxOfA.get("threads")).get(0)).get("subject"));
        assertNotNull(((Map<?, ?>) ((List<?>) inboxOfB.get("threads")).get(0)).get("otherUserId"));
    }

    // ── Read state ─────────────────────────────────────────────────────────────────────────────────

    @Test
    @Transactional
    @DisplayName("a new thread is unread for the recipient and read for the sender")
    void aNewThreadIsUnreadForTheRecipientOnly() {
        User a = aUser();
        User b = aUser();
        DirectMessage sent = messages.send(a, null, b.getId(), "Subject", "Body");

        assertFalse(threadOf(sent).isUnreadFor(a),
                "the sender sees his own message as unread");
        assertTrue(threadOf(sent).isUnreadFor(b),
                "the recipient is not told he has a message he has not seen");
        assertNotNull(threadOf(sent).getReadBySenderAt(),
                "the sender has no read cursor, so his own message badges as unread");
    }

    @Test
    @Transactional
    @DisplayName("after a reply it is unread for the first sender, which is what makes the badge useful")
    void aReplyMakesItUnreadForTheOtherSide() {
        User a = aUser();
        User b = aUser();
        DirectMessage first = messages.send(a, null, b.getId(), "Subject", "Body");

        messages.send(b, first.getThread().getId(), null, null, "A reply.");

        assertTrue(threadOf(first).isUnreadFor(a),
                "the man whose message was answered is not told it was answered");
        assertFalse(threadOf(first).isUnreadFor(b));
    }

    /**
     * Reading a thread clears it for the reader, and does not clear it for good.
     *
     * <p>The first version asserted the <i>replier</i> still had it unread after the other side opened the
     * conversation. That was wrong: he wrote the last message, so he has nothing unread, and asserting
     * otherwise would have demanded a badge on a message you just sent. A test that asserts the wrong
     * thing is worse than no test, because it will be "fixed" by breaking the feature.
     *
     * <p>What actually matters is the second half: reading must not mark the conversation read
     * permanently, or the badge never comes back for the next reply.
     */
    @Test
    @Transactional
    @DisplayName("reading a thread clears it for the reader, and the next reply badges it again")
    void readingAThreadClearsItForTheReaderOnly() {
        User a = aUser();
        User b = aUser();
        DirectMessage first = messages.send(a, null, b.getId(), "Subject", "Body");
        messages.send(b, first.getThread().getId(), null, null, "A reply.");

        messages.viewThread(a, first.getThread().getId(), 0, 30);

        assertFalse(threadOf(first).isUnreadFor(a), "reading the thread left it unread");
        assertFalse(threadOf(first).isUnreadFor(b),
                "the replier is badged for a message he just sent");

        messages.send(b, first.getThread().getId(), null, null, "One more thing.");

        assertTrue(threadOf(first).isUnreadFor(a),
                "the badge never came back, so a conversation looks read for good once opened");
    }

    @Test
    @Transactional
    @DisplayName("the inbox reports how many conversations are unread")
    void theInboxCountsUnreadThreads() {
        User a = aUser();
        User b = aUser();
        User c = aUser();
        DirectMessage toB = messages.send(a, null, b.getId(), "To B", "Body");
        DirectMessage toC = messages.send(a, null, c.getId(), "To C", "Body");

        assertEquals(0L, messages.inbox(a, 0, 30).get("unreadThreads"));

        messages.send(b, toB.getThread().getId(), null, null, "Reply from B.");

        assertEquals(1L, messages.inbox(a, 0, 30).get("unreadThreads"));
    }

    // ── The recipient list ─────────────────────────────────────────────────────────────────────────

    @Test
    @Transactional
    @DisplayName("every account is a valid recipient, whether or not he is at the keyboard")
    void everyAccountIsAValidRecipient() {
        User me = aUser();
        User neverSeen = aUser();
        // An account whose lastSeenAt is null has never made a request. "Active" in the owner's sense
        // is "has a live login", not "is online", and this is the account the naive filter would drop.
        assertNull(neverSeen.getLastSeenAt());

        List<Map<String, Object>> recipients = messages.activeRecipients(me);

        boolean listed = recipients.stream()
                .anyMatch(row -> neverSeen.getId().equals(row.get("userId")));
        assertTrue(listed, "an account that has never been seen online is missing from the recipient list");
    }

    @Test
    @Transactional
    @DisplayName("the recipient list excludes you and carries no password or email")
    void theRecipientListIsSafe() {
        User me = aUser();
        aUser();

        List<Map<String, Object>> recipients = messages.activeRecipients(me);

        boolean listedSelf = recipients.stream().anyMatch(row -> me.getId().equals(row.get("userId")));
        assertFalse(listedSelf, "you are in your own recipient list");

        for (Map<String, Object> row : recipients) {
            assertFalse(row.containsKey("password"), "the recipient list carries a password hash");
            // The `login` field is deliberately present and the `email` field deliberately absent. They
            // are the same string on a real account - User.username is an address that doubles as the
            // login - and the picker needs something to search on. Asserting the email is gone while
            // login is there documents which of the two is intentional.
            assertFalse(row.containsKey("email"),
                    "the recipient list carries an email address as a named field");
            assertNotNull(row.get("login"),
                    "the picker has nothing to search on for a manager with no chosen name");
            assertNotNull(row.get("displayName"));
        }
    }

    // ── History ────────────────────────────────────────────────────────────────────────────────────

    /**
     * The response names who is asking.
     *
     * <p>It has to: the client cannot tell its own messages from the other side's without it, and the
     * version that did not include it used {@code rows.map(messageHtml)} — which hands the array index
     * as the second argument, so "is this mine" was true for the first message of every conversation and
     * false for the rest. The page looked fine and was wrong about the only question it asked of every
     * row.
     */
    @Test
    @Transactional
    @DisplayName("a conversation says who is reading it, so its own messages can be marked")
    void theThreadNamesItsViewer() {
        User a = aUser();
        User b = aUser();
        DirectMessage first = messages.send(a, null, b.getId(), "Subject", "Body");

        assertEquals(a.getId(), messages.viewThread(a, first.getThread().getId(), 0, 30).get("viewerUserId"));
        assertEquals(b.getId(), messages.viewThread(b, first.getThread().getId(), 0, 30).get("viewerUserId"),
                "the response reports the thread's opener as the reader when somebody else is reading it");
    }

    @Test
    @Transactional
    @DisplayName("a thread's messages come back oldest first, which is what makes it followable")
    void messagesComeBackOldestFirst() {
        User a = aUser();
        User b = aUser();
        DirectMessage first = messages.send(a, null, b.getId(), "Subject", "One");
        messages.send(b, first.getThread().getId(), null, null, "Two");
        messages.send(a, first.getThread().getId(), null, null, "Three");

        List<?> rows = (List<?>) messages.viewThread(a, first.getThread().getId(), 0, 30).get("messages");

        assertEquals("One", ((Map<?, ?>) rows.get(0)).get("body"));
        assertEquals("Two", ((Map<?, ?>) rows.get(1)).get("body"));
        assertEquals("Three", ((Map<?, ?>) rows.get(2)).get("body"));
    }

    @Test
    @Transactional
    @DisplayName("paging a conversation walks it without repeating or skipping")
    void threadPagingIsStable() {
        User a = aUser();
        User b = aUser();
        DirectMessage first = messages.send(a, null, b.getId(), "Subject", "One");
        for (int i = 2; i <= 5; i++) {
            messages.send(a, first.getThread().getId(), null, null, "Post " + i);
        }

        Map<String, Object> page0 = messages.viewThread(a, first.getThread().getId(), 0, 2);
        Map<String, Object> page1 = messages.viewThread(a, first.getThread().getId(), 1, 2);

        assertEquals(2, ((List<?>) page0.get("messages")).size());
        assertEquals(2, ((List<?>) page1.get("messages")).size());

        long distinct = ((List<?>) page0.get("messages")).stream()
                .map(row -> ((Map<?, ?>) row).get("id")).distinct().count()
                + ((List<?>) page1.get("messages")).stream()
                .map(row -> ((Map<?, ?>) row).get("id")).distinct().count();
        assertEquals(4, distinct, "paging repeated or skipped a message");
    }

    @Test
    @Transactional
    @DisplayName("a hostile page size is clamped")
    void aHostilePageSizeIsClamped() {
        User a = aUser();
        messages.send(a, null, aUser().getId(), "Subject", "Body");

        assertEquals(100, messages.inbox(a, 0, 100_000).get("size"));
        assertEquals(0, messages.inbox(a, -5, 30).get("page"));
    }

    // ── Notifications ───────────────────────────────────────────────────────────────────────────────

    @Test
    @Transactional
    @DisplayName("the recipient is notified and the sender is not")
    void theRecipientIsNotified() {
        User a = aUser();
        User b = aUser();

        messages.send(a, null, b.getId(), "About the keeper", "Will you take 500k?");

        assertEquals(0, unreadNotificationsFor(a), "the sender was notified about his own message");
        assertEquals(1, unreadNotificationsFor(b));
    }

    @Test
    @Transactional
    @DisplayName("a notification names the subject and points at the conversation")
    void aNotificationPointsAtItsThread() {
        User a = aUser();
        User b = aUser();
        messages.send(a, null, b.getId(), "About the keeper", "Will you take 500k?");

        Notification notification = notifications
                .findByRecipientIdOrderByCreatedAtDescIdDesc(b.getId(),
                        org.springframework.data.domain.PageRequest.of(0, 1)).get(0);

        assertEquals(NotificationKind.PM_RECEIVED, notification.getKind());
        assertEquals("messageThread", notification.getTargetPage());
        assertTrue(notification.getSummary().contains("About the keeper"),
                "the notification does not say what it is about: " + notification.getSummary());
    }

    @Test
    @Transactional
    @DisplayName("a reply notifies the other side")
    void aReplyNotifiesTheOtherSide() {
        User a = aUser();
        User b = aUser();
        DirectMessage first = messages.send(a, null, b.getId(), "Subject", "Body");

        messages.send(b, first.getThread().getId(), null, null, "A reply.");

        assertEquals(1, unreadNotificationsFor(a));
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────────────────────────

    private MessageThread threadOf(DirectMessage message) {
        return threads.findById(message.getThread().getId()).orElseThrow();
    }

    private long unreadNotificationsFor(User user) {
        return notifications.countByRecipientIdAndReadAtIsNull(user.getId());
    }

    private User aUser() {
        User user = new User();
        user.setUsername("msg-" + UUID.randomUUID() + "@test.local");
        user.setEmail(user.getUsername());
        user.setPassword("not-a-real-hash");
        user.setDisplayName("Message tester");
        user.setRole(UserRole.REGULAR);
        user.setPlusSubscription(false);
        return users.save(user);
    }
}