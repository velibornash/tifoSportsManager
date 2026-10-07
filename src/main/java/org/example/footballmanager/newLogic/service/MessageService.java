package org.example.footballmanager.newLogic.service;

import org.example.commonmanager.model.User;
import org.example.commonmanager.repository.UserRepository;
import org.example.footballmanager.newLogic.model.DirectMessage;
import org.example.footballmanager.newLogic.model.MessageThread;
import org.example.footballmanager.newLogic.model.NotificationKind;
import org.example.footballmanager.newLogic.repository.DirectMessageRepository;
import org.example.footballmanager.newLogic.repository.MessageThreadRepository;
import org.springframework.data.domain.PageRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Private messages (owner, 2026-10-05).
 *
 * <p>The owner's rules: pick an active account, send a direct message with a subject and a body, reply
 * within a thread so the correspondence can be followed, and be able to send to any account with a live
 * login — <b>not</b> only to whoever happens to be online.
 *
 * <h2>"Active" means a real account, not a session</h2>
 *
 * <p>There is no online/offline gate on sending, and deliberately so. A JWT is stateless and stays valid
 * for 24 hours after a browser closes, so "is he online" is a question this application answers with a
 * five-minute window on one page and cannot answer correctly anywhere else. Requiring the recipient to be
 * present would make a message undeliverable to someone asleep — which is precisely who you want to write
 * to. The recipient list is every account that exists.
 *
 * <h2>One thread per pair</h2>
 *
 * <p>A reply appends to the existing thread and carries no subject. Opening a second conversation with
 * the same manager continues the first, which is what "follow the history" requires: without it, two
 * subjects about the same transfer produce two threads that read as two conversations and are one.
 */
@Service
public class MessageService {

    private static final Logger log = LoggerFactory.getLogger(MessageService.class);

    private static final int MAX_SUBJECT = 150;
    private static final int MAX_BODY = 4000;
    private static final int DEFAULT_PAGE = 30;

    private final MessageThreadRepository threads;
    private final DirectMessageRepository messages;
    private final UserRepository users;
    private final NotificationService notifications;

    public MessageService(MessageThreadRepository threads,
                          DirectMessageRepository messages,
                          UserRepository users,
                          NotificationService notifications) {
        this.threads = threads;
        this.messages = messages;
        this.users = users;
        this.notifications = notifications;
    }

    // ── Sending ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * Sends a message, opening a conversation or appending to one.
     *
     * <p>One method for both cases, decided by whether a thread already exists between the pair. The
     * alternative — a separate "start conversation" and "reply" — puts the decision on the client, and a
     * client that sends the wrong one either forks a thread or writes a reply into nothing.
     *
     * @param threadId to reply in a known thread; null to find or start the conversation
     */
    @Transactional
    public DirectMessage send(User sender, Long threadId, Long recipientUserId, String subject, String body) {
        if (sender == null || sender.getId() == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.UNAUTHORIZED, "Sign in first.");
        }
        String cleanBody = requireText(body, MAX_BODY, "A message cannot be empty.");

        MessageThread thread;
        User recipient;
        boolean alreadyCounted = false;

        if (threadId != null) {
            thread = threads.findById(threadId)
                    .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                            org.springframework.http.HttpStatus.NOT_FOUND, "That conversation does not exist."));
            // Membership is checked inside this method rather than at the route, because a thread id in a
            // URL is a thing a caller can change and this is the only place that knows who belongs to it.
            if (!isParticipant(thread, sender)) {
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.FORBIDDEN, "That is not your conversation.");
            }
            recipient = otherParticipant(thread, sender);
        } else {
            if (recipientUserId == null) {
                throw new IllegalArgumentException("Choose someone to send this to.");
            }
            if (recipientUserId.equals(sender.getId())) {
                throw new IllegalArgumentException("You cannot message yourself.");
            }
            recipient = users.findById(recipientUserId)
                    .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                            org.springframework.http.HttpStatus.NOT_FOUND, "No such manager."));
            thread = existingThreadBetween(sender, recipient);
            if (thread == null) {
                thread = openThread(sender, recipient, subject);
                // The opening message is the first one, so the increment below must not run for it.
                // Counting it twice is how a new thread reported two messages.
                alreadyCounted = true;
            }
        }

        DirectMessage message = new DirectMessage();
        message.setThread(thread);
        message.setSender(sender);
        message.setBody(cleanBody);
        messages.save(message);

        if (!alreadyCounted) {
            thread.setMessageCount(thread.getMessageCount() + 1);
        }
        thread.setLastActivityAt(LocalDateTime.now());
        // The sender has by definition read their own message. `markRead` picks the column by thread
        // membership rather than by "whoever sent this one": on a thread the two are the same only until
        // the first reply, and writing readBySenderAt unconditionally marked the thread read for the
        // man who opened it the moment the other side replied. He was then never told he had been
        // answered, which is the entire point of a notification.
        markRead(thread, sender);
        threads.save(thread);

        notifications.notify(recipient, NotificationKind.PM_RECEIVED,
                nameOf(sender) + " wrote: " + thread.getSubject(),
                "messageThread", thread.getId());

        return message;
    }

    /**
     * The existing conversation between two accounts, or null.
     *
     * <p>Newest first, so if a pair somehow has two threads the most recent one is continued. Lowest id
     * breaks a tie, which makes the answer stable rather than dependent on row order.
     */
    private MessageThread existingThreadBetween(User a, User b) {
        List<MessageThread> forward = threads.findBySenderUserIdAndRecipientUserId(a.getId(), b.getId());
        List<MessageThread> backward = threads.findBySenderUserIdAndRecipientUserId(b.getId(), a.getId());
        List<MessageThread> all = new ArrayList<>(forward);
        all.addAll(backward);
        if (all.isEmpty()) {
            return null;
        }
        return all.stream()
                .min(Comparator
                        .comparing(MessageThread::getId))
                .orElse(null);
    }

    private MessageThread openThread(User sender, User recipient, String subject) {
        String cleanSubject = subject == null ? "" : subject.trim();
        if (cleanSubject.isEmpty()) {
            cleanSubject = "(no subject)";
        }
        if (cleanSubject.length() > MAX_SUBJECT) {
            cleanSubject = cleanSubject.substring(0, MAX_SUBJECT);
        }

        MessageThread thread = new MessageThread();
        thread.setSubject(cleanSubject);
        thread.setSenderUser(sender);
        thread.setRecipientUser(recipient);
        thread.setMessageCount(1);
        return threads.save(thread);
    }

    private static boolean isParticipant(MessageThread thread, User user) {
        if (thread.getSenderUser() == null || thread.getRecipientUser() == null) {
            return false;
        }
        Long id = user == null ? null : user.getId();
        return Objects.equals(thread.getSenderUser().getId(), id)
                || Objects.equals(thread.getRecipientUser().getId(), id);
    }

    private static User otherParticipant(MessageThread thread, User user) {
        return Objects.equals(thread.getSenderUser().getId(), user.getId())
                ? thread.getRecipientUser()
                : thread.getSenderUser();
    }

    // ── Reading ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * Every conversation this account is in, newest activity first.
     *
     * <p>Two indexed queries merged in Java rather than one {@code OR}, which Postgres would not serve
     * from either index.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> inbox(User viewer, int page, int size) {
        int safeSize = clamp(size);
        int safePage = Math.max(0, page);
        Long viewerId = viewer == null ? null : viewer.getId();

        if (viewerId == null) {
            return emptyInbox(safePage, safeSize);
        }

        Set<Long> seen = new LinkedHashSet<>();
        List<MessageThread> merged = new ArrayList<>();
        for (MessageThread thread : threads.findBySenderUserIdOrderByLastActivityAtDescIdDesc(
                viewerId, PageRequest.of(safePage, safeSize))) {
            if (seen.add(thread.getId())) {
                merged.add(thread);
            }
        }
        for (MessageThread thread : threads.findByRecipientUserIdOrderByLastActivityAtDescIdDesc(
                viewerId, PageRequest.of(safePage, safeSize))) {
            if (seen.add(thread.getId())) {
                merged.add(thread);
            }
        }
        merged.sort(Comparator
                .comparing(MessageThread::getLastActivityAt,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(MessageThread::getId, Comparator.reverseOrder()));

        long unread = 0;
        for (MessageThread thread : merged) {
            if (thread.isUnreadFor(viewer)) {
                unread++;
            }
        }

        // One query for every preview on the page, not one per row (owner, 2026-10-07): "u listi poruka
        // se samo vidi subject i poslednja poruka". Thirty threads is thirty queries otherwise.
        Map<Long, String> previews = newestBodies(merged);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("page", safePage);
        result.put("size", safeSize);
        result.put("unreadThreads", unread);
        result.put("threads", merged.stream().map(t -> threadSummary(t, viewer, previews)).toList());
        return result;
    }

    /**
     * One conversation and its messages, and marks this side as having read it.
     *
     * <p>The read is a write on a read, which is a thing worth flagging: {@code GET} on a thread sets a
     * cursor. It is here because the owner asked for history to be followable and a read receipt that
     * only advances when you happen to open the thread is not one. The cost is one column write per
     * thread view, not per message.
     */
    @Transactional
    public Map<String, Object> viewThread(User viewer, Long threadId, int page, int size) {
        MessageThread thread = threads.findById(threadId)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "That conversation does not exist."));
        if (!isParticipant(thread, viewer)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "That is not your conversation.");
        }

        int safeSize = clamp(size);
        int safePage = Math.max(0, page);
        List<DirectMessage> rows = messages.findByThread_IdOrderByIdAsc(threadId, PageRequest.of(safePage, safeSize));

        // Marked read only after the messages have been read out, so a failure to read leaves the badge
        // on rather than clearing it.
        markRead(thread, viewer);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("thread", threadSummary(thread, viewer));
        // The viewer's own id, so the client can tell its own messages from the other side's without a
        // second request for /auth/me. It first tried to work this out from `map(messageHtml)`, which
        // passes the array INDEX as the second argument — so "is this mine" was true for message 1 of
        // every conversation and false for the rest.
        result.put("viewerUserId", viewer.getId());
        result.put("page", safePage);
        result.put("size", safeSize);
        result.put("hasMore", rows.size() == safeSize);
        result.put("messages", rows.stream().map(this::messageSummary).toList());
        return result;
    }

    /** Moves this account's cursor to now, so the thread stops showing as unread. */
    @Transactional
    public void markRead(MessageThread thread, User viewer) {
        LocalDateTime now = LocalDateTime.now();
        boolean sender = thread.getSenderUser() != null
                && Objects.equals(thread.getSenderUser().getId(), viewer.getId());
        if (sender) {
            thread.setReadBySenderAt(now);
        } else {
            thread.setReadByRecipientAt(now);
        }
        threads.save(thread);
    }

    /**
     * Every account that exists, for the recipient picker.
     *
     * <p><b>Every account, not the online ones.</b> "Active" in the owner's sense is "has a live login",
     * and this application cannot answer "is he at the keyboard" correctly — {@code PresenceRegistry}
     * has a five-minute window and the World page is the only place that states it. Restricting the
     * picker to currently-online managers would make a message undeliverable to somebody asleep.
     *
     * <p>No email and no password: the picker needs a name to show and an id to send to.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> activeRecipients(User viewer) {
        if (viewer == null || viewer.getId() == null) {
            return List.of();
        }
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (User user : users.findAll()) {
            if (user.getId() == null || user.getId().equals(viewer.getId())) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("userId", user.getId());
            row.put("displayName", nameOf(user));
            row.put("hasChosenName", user.getDisplayName() != null && !user.getDisplayName().isBlank());
            // The login as a separate field, for searching and nothing else. The picker filters on it
            // because a manager who never set a name is displayed by his login, and typing "kecko" has
            // to find Kecko whichever of the two you would have typed.
            //
            // It is the same string as the email address on a real account - `User.username` is an
            // address that doubles as the login - and this route is authenticated, so nothing new is
            // disclosed here. `CommunityRecipientDTO` sent it to every logged-in manager years ago.
            row.put("login", user.getUsername());
            rows.add(row);
        }
        rows.sort(Comparator.comparing(r -> String.valueOf(r.get("displayName"))));
        return rows;
    }

    // ── Rendering ───────────────────────────────────────────────────────────────────────────────────

    /**
     * The newest message body per thread, in one query.
     *
     * <p>An empty map rather than an exception where the database cannot serve the {@code DISTINCT ON}
     * query — H2 in the test profile, or any future one. The list then shows subject and counts with no
     * preview, which is exactly the screen this had a week ago, and a missing preview is a far better
     * failure than a broken list.
     */
    private Map<Long, String> newestBodies(List<MessageThread> threads) {
        List<Long> ids = threads.stream().map(MessageThread::getId).filter(java.util.Objects::nonNull).toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        try {
            Map<Long, String> previews = new LinkedHashMap<>();
            for (DirectMessage message : messages.findNewestPerThread(ids)) {
                previews.put(message.getThread().getId(), message.getBody());
            }
            return previews;
        } catch (RuntimeException e) {
            log.debug("No message previews for this database ({}); listing without them.", e.getMessage());
            return Map.of();
        }
    }

    private Map<String, Object> threadSummary(MessageThread thread, User viewer) {
        return threadSummary(thread, viewer, Map.of());
    }

    private Map<String, Object> threadSummary(MessageThread thread, User viewer,
                                              Map<Long, String> previews) {
        User other = otherParticipant(thread, viewer);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", thread.getId());
        row.put("subject", thread.getSubject());
        // The last thing said in the conversation, which is what the list is read for.
        row.put("lastMessage", previews.get(thread.getId()));
        row.put("otherUserId", other == null ? null : other.getId());
        row.put("otherName", nameOf(other));
        row.put("otherHasChosenName", other != null
                && other.getDisplayName() != null && !other.getDisplayName().isBlank());
        row.put("messageCount", thread.getMessageCount());
        row.put("unread", thread.isUnreadFor(viewer));
        row.put("lastActivityAt", thread.getLastActivityAt());
        row.put("createdAt", thread.getCreatedAt());
        return row;
    }

    private Map<String, Object> messageSummary(DirectMessage message) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", message.getId());
        row.put("threadId", message.getThread() == null ? null : message.getThread().getId());
        row.put("senderUserId", message.getSender() == null ? null : message.getSender().getId());
        row.put("senderName", nameOf(message.getSender()));
        row.put("body", message.displayBody());
        row.put("deleted", message.isDeleted());
        row.put("edited", message.getEditedAt() != null);
        row.put("editedAt", message.getEditedAt());
        row.put("createdAt", message.getCreatedAt());
        return row;
    }

    private static Map<String, Object> emptyInbox(int page, int size) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("page", page);
        result.put("size", size);
        result.put("unreadThreads", 0);
        result.put("threads", List.of());
        return result;
    }

    private static String requireText(String value, int max, String message) {
        String clean = value == null ? "" : value.trim();
        if (clean.isEmpty()) {
            throw new IllegalArgumentException(message);
        }
        if (clean.length() > max) {
            throw new IllegalArgumentException("That is longer than " + max + " characters.");
        }
        return clean;
    }

    private static int clamp(int size) {
        if (size <= 0) {
            return DEFAULT_PAGE;
        }
        return Math.min(size, 100);
    }

    /** Eighth copy of this fallback. See {@code ForumService} for the same note. */
    private static String nameOf(User user) {
        if (user == null) {
            return "Someone";
        }
        if (user.getDisplayName() != null && !user.getDisplayName().isBlank()) {
            return user.getDisplayName().trim();
        }
        return user.getUsername();
    }
}