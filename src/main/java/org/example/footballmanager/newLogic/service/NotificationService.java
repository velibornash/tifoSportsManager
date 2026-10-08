package org.example.footballmanager.newLogic.service;

import org.example.commonmanager.model.User;
import org.example.commonmanager.repository.UserRepository;
import org.example.footballmanager.newLogic.model.Notification;
import org.example.footballmanager.newLogic.model.NotificationKind;
import org.example.footballmanager.newLogic.repository.NotificationRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The notification store (owner, 2026-10-05).
 *
 * <p><b>Built, not extended.</b> There was no notification entity, no table, and no service. The dashboard
 * ticker was recomputed from eight endpoints on every dashboard render and thrown away, so a badge could
 * not be cleared, could not name what it was about, and could not distinguish one unread message from four.
 *
 * <h2>Every method names its recipient</h2>
 *
 * <p>Not a convention — the only authorization this table has. A method that could read notifications
 * without naming who they are for would be a method whose caller has to remember a filter, and the forum
 * and messages features both call it. Passing the id in makes "whose notifications are these" unanswerable
 * by accident.
 *
 * <h2>Writes never throw</h2>
 *
 * <p>A notification is a courtesy about something that already happened. If one fails to save, the forum
 * post is still there and the reply is still delivered — losing the badge is a far smaller failure than
 * losing the write that caused it. {@link #notify} catches and logs; nothing else in the application
 * should have to know that notifications exist.
 */
@Service
public class NotificationService {

    /** Enough for the dropdown and one page of the full list. */
    private static final int DEFAULT_PAGE_SIZE = 30;
    private static final int MAX_PAGE_SIZE = 100;

    private final NotificationRepository notifications;
    private final UserRepository users;

    public NotificationService(NotificationRepository notifications, UserRepository users) {
        this.notifications = notifications;
        this.users = users;
    }

    /**
     * Records one notification. Never throws.
     *
     * <p>A null recipient is the one thing that cannot be defaulted — there is nobody to notify, and
     * writing the row anyway would leave a notification in a table no read ever queries.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void notify(User recipient, NotificationKind kind, String summary,
                       String targetPage, Long targetId) {
        if (recipient == null || recipient.getId() == null || kind == null) {
            return;
        }
        String cleanSummary = summary == null ? "" : summary.trim();
        if (cleanSummary.isEmpty() || cleanSummary.length() > 300) {
            // A blank summary renders as an empty row in the dropdown, and a long one is truncated by
            // the column into something that ends mid-sentence. Both are worse than not sending it.
            return;
        }
        try {
            Notification notification = new Notification();
            notification.setRecipient(recipient);
            notification.setKind(kind);
            notification.setSummary(cleanSummary);
            notification.setTargetPage(targetPage);
            notification.setTargetId(targetId);
            notifications.save(notification);
        } catch (RuntimeException e) {
            // Deliberately swallowed. The caller is in the middle of posting a forum reply or sending a
            // message, and failing that write because a courtesy row did not save would be trading a
            // real feature for a cosmetic one.
            System.err.println("Could not save a " + kind + " notification: " + e.getMessage());
        }
    }

    /** The badge number. Cheap enough for the 30-second poll. */
    @Transactional(readOnly = true)
    public long unreadCount(Long recipientId) {
        if (recipientId == null) {
            return 0;
        }
        return notifications.countByRecipientIdAndReadAtIsNull(recipientId);
    }

    /**
     * One page of notifications, newest first, with the unread count attached.
     *
     * <p>Both come back together because the dropdown needs them together and the frontend polls this
     * every 30 seconds: two requests would be two chances for the count and the list to disagree.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> list(Long recipientId, int page, int size) {
        if (recipientId == null) {
            return emptyList();
        }
        int safeSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        int safePage = Math.max(0, page);

        List<Notification> rows = notifications
                .findByRecipientIdOrderByCreatedAtDescIdDesc(recipientId, PageRequest.of(safePage, safeSize));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("unreadCount", notifications.countByRecipientIdAndReadAtIsNull(recipientId));
        result.put("page", safePage);
        result.put("size", safeSize);
        result.put("hasMore", rows.size() == safeSize);
        result.put("notifications", rows.stream().map(NotificationService::toMap).toList());
        return result;
    }

    /**
     * Marks one notification read, if this account owns it.
     *
     * <p>The ownership check is inside the query, not beside it. A caller passing an id that belongs to
     * somebody else gets 404 rather than a silent success, so a bug in the frontend shows up as a failed
     * request instead of a badge that never clears.
     */
    @Transactional
    public void markRead(Long recipientId, Long notificationId) {
        Notification notification = notifications.findById(notificationId)
                .filter(n -> n.getRecipient() != null
                        && java.util.Objects.equals(n.getRecipient().getId(), recipientId))
                .orElseThrow(() -> new ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "No such notification."));
        if (notification.getReadAt() == null) {
            notification.setReadAt(java.time.LocalDateTime.now());
            notifications.save(notification);
        }
    }

    /**
     * Marks everything read and reports how many were unread.
     *
     * <p>Capped at 200 rows. An unbounded version would write a row per notification this account has
     * ever received, and "clear my badge" is not worth a table-wide update.
     */
    @Transactional
    public long markAllRead(Long recipientId) {
        if (recipientId == null) {
            return 0;
        }
        List<Notification> unread = notifications
                .findTop200ByRecipientIdAndReadAtIsNullOrderByIdAsc(recipientId);
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        for (Notification notification : unread) {
            notification.setReadAt(now);
        }
        notifications.saveAll(unread);
        return unread.size();
    }

    /**
     * Tells a manager he has been banned from the forum, and why.
     *
     * <p>The owner's rule (2026-10-07): a forum ban has to reach the person it was applied to. It did
     * not — {@link #notifyModeratorsOfBan} told the moderators and <b>had no callers at all</b>, so a ban
     * produced no notification anywhere: the target found out by trying to post and being refused.
     *
     * <p>Best-effort like every other {@code notify}. A notification failing is not a reason to roll back
     * a moderation action that has already been applied and recorded on the account.
     *
     * @param days how long the ban lasts, stated in days rather than as a date because that is how the
     *             moderator was asked for it and how the account screen will show it
     */
    @Transactional
    public void notifyForumBan(User banned, String moderatorName, int days, String reason) {
        if (banned == null || banned.getId() == null) {
            return;
        }
        String who = moderatorName == null || moderatorName.isBlank() ? "a moderator" : moderatorName.trim();
        String summary = "You have been banned from posting in the forum for " + days + " day(s)"
                + " by " + who + ".";
        if (reason != null && !reason.isBlank()) {
            summary = summary + " Reason: " + reason.trim();
        }
        // targetPage "admin" is where a moderator would look; the banned manager cannot moderate, so
        // this row exists to be read, not to be clicked into a page he may not open.
        notify(banned, NotificationKind.FORUM_BANNED, summary, "admin", null);
    }

    /**
     * Notifies the moderators that an account has been banned, so the decision is not silent.
     *
     * <p>A ban applied with no notification is a manager discovering he cannot post and having no idea
     * why. The ban itself is visible on his profile, but the profile is not somewhere a banned manager
     * is told to look.
     */
    @Transactional
    public void notifyModeratorsOfBan(User banned, String moderatorName, int days, String reason) {
        for (User staff : users.findAll()) {
            if (org.example.commonmanager.model.UserRoles.mayModerate(staff)
                    && !staff.getId().equals(banned.getId())) {
                notify(staff, NotificationKind.FORUM_BANNED,
                        "You banned " + nameOf(banned) + " from the forum for " + days + " day(s).",
                        "admin", null);
            }
        }
    }

    private static Map<String, Object> toMap(Notification notification) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", notification.getId());
        row.put("kind", notification.getKind() == null ? null : notification.getKind().name());
        row.put("summary", notification.getSummary());
        row.put("targetPage", notification.getTargetPage());
        row.put("targetId", notification.getTargetId());
        row.put("read", notification.getReadAt() != null);
        row.put("createdAt", notification.getCreatedAt());
        return row;
    }

    private static Map<String, Object> emptyList() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("unreadCount", 0L);
        result.put("page", 0);
        result.put("size", DEFAULT_PAGE_SIZE);
        result.put("hasMore", false);
        result.put("notifications", List.of());
        return result;
    }

    private static String nameOf(User user) {
        if (user == null) {
            return "a manager";
        }
        if (user.getDisplayName() != null && !user.getDisplayName().isBlank()) {
            return user.getDisplayName().trim();
        }
        return user.getUsername();
    }
}